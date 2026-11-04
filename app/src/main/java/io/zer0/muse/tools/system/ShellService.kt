package io.zer0.muse.tools.system

import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import io.zer0.common.Logger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

private val SAFE_SHELL_VERB_REGEX = Regex("^[A-Za-z][A-Za-z0-9_-]*$")

/** Keep device-command diagnostics useful without persisting arguments or their sensitive values. */
internal fun sanitizeShellCommandForLog(command: String): String {
    val verb = command.trim().split(Regex("\\s+"), limit = 2).firstOrNull()
    return verb?.takeIf { SAFE_SHELL_VERB_REGEX.matches(it) } ?: "<unknown>"
}

/**
 * P3-3: Shizuku UserService — 以 shell 权限执行命令的服务。
 *
 * 该服务通过 [Shizuku.bindUserService] 在 Shizuku 管理的独立进程中启动,
 * 继承 Shizuku 的 shell (ADB) 权限。主进程通过 AIDL IPC 调用 [execute]。
 *
 * 注意：Shizuku UserService 不是 Android [android.app.Service]。服务类本身必须
 * 实现 [android.os.IBinder]，这里直接继承生成的 AIDL Stub；否则 bindUserService
 * 不会得到可用的 binder，调用方会持续等待连接超时。
 *
 * 生命周期:
 *  - 由 [ShizukuAuthorizer] 在首次执行时绑定(bindUserService)
 *  - 服务在独立进程运行,与主进程隔离,崩溃不影响主进程
 *  - Shizuku 服务停止时,该进程也会被终止
 *
 * 安全:
 *  - 仅响应 AIDL Shell 命令调用,不处理 Intent
 *  - 命令执行无白名单过滤(由调用方 [ToolPermissionResolver] 负责硬边界校验)
 *  - 服务运行在 Shizuku 进程中,即使被恶意调用也仅能执行 shell 级操作(非 root)
 */
@Keep
class ShellService : IShellService.Stub() {

    companion object {
        private const val TAG = "ShellService"

        /** AIDL 返回值的字段分隔符(null char,shell 输出中不会出现)。 */
        private const val FIELD_SEPARATOR = '\u0000'

        /** 命令执行超时 ms。 */
        private const val TIMEOUT_MS = 10_000L
        private const val OUTPUT_DRAIN_TIMEOUT_MS = 30_000L

        /** Legacy String transaction cap; interactive device-tool output uses executeToPipe. */
        private const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024
    }

    private val streamExecutor = Executors.newCachedThreadPool()

    override fun execute(command: String): String {
        return try {
            Logger.d(TAG, "Executing command verb=${sanitizeShellCommandForLog(command)}")
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            // stdout/stderr 必须在 waitFor 的同时持续排空；否则 uiautomator/screencap
            // 等输出稍大的命令会把管道写满，子进程永远等不到退出而被误判超时。
            val stdout: Future<String> = streamExecutor.submit(Callable { readStream(proc.inputStream) })
            val stderr: Future<String> = streamExecutor.submit(Callable { readStream(proc.errorStream) })
            val finished = proc.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                stdout.cancel(true)
                stderr.cancel(true)
                return "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}Execution timed out (${TIMEOUT_MS / 1000}s)"
            }
            val out = stdout.get(1, TimeUnit.SECONDS)
            val err = stderr.get(1, TimeUnit.SECONDS)
            val code = proc.exitValue()
            buildString {
                append(code)
                append(FIELD_SEPARATOR)
                append(out)
                append(FIELD_SEPARATOR)
                append(err)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.e(TAG, "Shell execution interrupted: ${e.message}", e)
            "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}${e.message ?: "execution interrupted"}"
        } catch (e: IOException) {
            Logger.e(TAG, "Shell execution IO exception: ${e.message}", e)
            "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}${e.message ?: "IO exception"}"
        } catch (e: SecurityException) {
            Logger.e(TAG, "Shell execution permission exception: ${e.message}", e)
            "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}${e.message ?: "execution permission exception"}"
        } catch (e: IllegalArgumentException) {
            Logger.e(TAG, "Shell execution exception: ${e.message}", e)
            "-1${FIELD_SEPARATOR}${FIELD_SEPARATOR}${e.message ?: "execution exception"}"
        }
    }

    /**
     * Streams merged command output through a file descriptor so large results do not cross Binder
     * as a String transaction. The app process drains the pipe directly into its private output file.
     */
    override fun executeToPipe(command: String, output: ParcelFileDescriptor): Int {
        ParcelFileDescriptor.AutoCloseOutputStream(output).use { sink ->
            var process: Process? = null
            var outputCopy: Future<*>? = null
            return try {
                Logger.d(TAG, "Streaming command verb=${sanitizeShellCommandForLog(command)}")
                val running = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                process = running
                val copy = streamExecutor.submit(
                    Callable {
                        running.inputStream.use { input -> copyShellOutput(input, sink) }
                    },
                )
                outputCopy = copy
                val finished = running.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!finished) {
                    running.destroyForcibly()
                    running.waitFor(3, TimeUnit.SECONDS)
                }
                val drained = runCatching { copy.get(OUTPUT_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.isSuccess
                if (!drained) {
                    copy.cancel(true)
                    sink.write("\n[The output stream could not be closed; reading was terminated; the current output may be incomplete]".toByteArray(Charsets.UTF_8))
                    -1
                } else if (!finished) {
                    sink.write("\n[Timeout] Command execution timed out(${TIMEOUT_MS / 1000}s), terminated".toByteArray(Charsets.UTF_8))
                    -1
                } else {
                    running.exitValue()
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                process?.destroyForcibly()
                outputCopy?.cancel(true)
                -1
            } catch (error: Exception) {
                process?.destroyForcibly()
                outputCopy?.cancel(true)
                Logger.w(TAG, "Shell pipe execution failed: ${error.javaClass.simpleName}")
                -1
            }
        }
    }

    /**
     * 读取一条命令输出流，最多保留 [MAX_OUTPUT_BYTES] 字节。
     *
     * 注意：本方法按字节保留**头部**，超限时的尾部会被丢掉。命令类输出（报错、失败用例、
     * 退出状态都在最后几行）请走 `ToolOutputCapture`，它按字符保留尾部并能给出分段读取的引用；
     * 不要拿本方法去接命令输出，否则结论会先在这里被截掉。
     */
    private fun readStream(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var retained = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (retained < MAX_OUTPUT_BYTES) {
                val take = minOf(read, MAX_OUTPUT_BYTES - retained)
                output.write(buffer, 0, take)
                retained += take
            }
        }
        val text = output.toString(Charsets.UTF_8.name())
        return if (retained >= MAX_OUTPUT_BYTES) "$text\n[Output truncated]" else text
    }

    /** Shizuku 解绑/版本替换时调用的保留事务，必须结束 UserService 进程。 */
    override fun destroy() {
        Logger.i(TAG, "Shell UserService destroy")
        streamExecutor.shutdownNow()
        System.exit(0)
    }

    /** 应用侧可选的主动退出事务。 */
    override fun exit() = destroy()
}

internal fun copyShellOutput(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return
        output.write(buffer, 0, count)
    }
}
