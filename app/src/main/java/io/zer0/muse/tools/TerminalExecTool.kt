package io.zer0.muse.tools

import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v2.x 终端一期:terminal_exec 工具 — 在应用沙盒内执行单条 shell 命令。
 *
 * 与 [ShellSandboxTool](execute_shell:只读白名单) 和 device_shell(Shizuku/Root 设备级操控)
 * 互补:本工具是 **App 自身权限** 的终端,工作目录锁定应用工作区,用于文件处理、脚本、
 * 诊断等沙盒内任务。HIGH 风险,走既有审批链。
 *
 * 安全边界:[ShellCommandBoundary] 两档判据。本工具只强制第一档「绝对不许出现」的最小集
 * (递归删除工作区/应用私有数据、写系统分区、格式化、下载即执行、提权与设备级命令等),
 * 审批模式绕不过;第二档「有破坏力但可能是正常工作」的命令照旧执行,由 HIGH 风险的
 * 逐次审批承担,本工具只记一条警告日志。管道、重定向、环境变量赋值不被拦。
 */
object TerminalExecTool {
    const val NAME = "terminal_exec"
    private const val TAG = "TerminalExec"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun toolDef() = ToolRegistry.ToolDef(
        name = NAME,
        description = "Execute a single shell command inside the app sandbox (working directory is the app workspace, with the app's own permissions)." +
            "Suitable for file processing/scripts/diagnostics; for device-level operations such as tapping or starting/stopping apps, use device_shell instead (requires Shizuku/Root authorisation)." +
            "Returns the exit code and full output; long output is saved to an app file that can be read in chunks; timed-out commands are terminated.",
        parameters = mapOf(
            "command" to "Required. A single shell command, e.g. ls -la / cat notes.txt / mkdir -p out (pipes and redirection are allowed;" +
                "commands such as recursively deleting the whole workspace, writing to system partitions, or download-and-execute are refused by the security boundary)",
            "timeout_ms" to "Optional. Timeout in milliseconds, default 15000, range 1000-60000",
        ),
        required = setOf("command"),
        category = "built-in",
        parameterTypes = mapOf("timeout_ms" to "integer"),
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(args: Map<String, String>, workspaceDir: File, outputDirectory: File): String {
        val command = args["command"]?.trim().orEmpty()
        if (command.isEmpty()) return "Error: command is required."
        // 安全检查:命令硬边界第一档(与 execute_shell 黑名单同层的地板,判据按破坏力而不是字符串形状)。
        // 放在执行之前、审批之后:TRUSTED 模式与「始终允许本工具」都不能越过它。
        val forbidden = ShellCommandBoundary.forbidReason(command, workspaceDir)
        if (forbidden != null) {
            Logger.w(TAG, "Hard boundary refusal: $forbidden")
            return "Error: command refused by the security boundary — ${forbidden}."
        }
        // 第二档:有破坏力但属于正常工作,不在这里拦(由 HIGH 风险的逐次审批承担),只留可回溯的日志。
        ShellCommandBoundary.cautionReason(command)?.let { note ->
            Logger.w(TAG, "Destructive command allowed through (the approval chain is responsible): $note")
        }
        val timeoutMs = (args["timeout_ms"]?.toIntOrNull() ?: 15_000).coerceIn(1_000, 60_000)
        return withContext(Dispatchers.IO) {
            val capture = ToolOutputCapture(outputDirectory, "terminal_exec")
            val process = runCatching {
                ProcessBuilder("/system/bin/sh", "-c", command)
                    .directory(workspaceDir)
                    .redirectErrorStream(true)
                    .start()
            }.getOrElse {
                capture.close()
                return@withContext "Launch failed: ${it.message ?: "unknown error"}"
            }

            val readerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val readerJob = scope.launch {
                try {
                    val buffer = CharArray(4096)
                    process.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val n = reader.read(buffer)
                            if (n < 0) break
                            capture.append(String(buffer, 0, n))
                        }
                    }
                } catch (error: Throwable) {
                    readerFailure.set(error)
                }
            }

            val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                readerJob.join()
                readerFailure.get()?.let { error ->
                    capture.close()
                    return@withContext "Command timed out, and the full output could not be saved: ${error.message ?: "storage failed"}"
                }
                return@withContext capture.finish(
                    header = "Command timed out (${timeoutMs / 1000}s); terminated",
                    emptyMessage = "(No output)",
                )
            }
            readerJob.join()
            readerFailure.get()?.let { error ->
                capture.close()
                return@withContext "Command finished, but the full output could not be saved: ${error.message ?: "storage failed"}"
            }
            val exit = process.exitValue()
            capture.finish(header = "exit=$exit", emptyMessage = "(No output)")
        }
    }
}
