package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.muse.runtime.MuseRuntime
import io.zer0.muse.runtime.PythonRuntime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * v2.x 扩展运行时（P0 打样）：内置 Node 沙盒自检工具。
 *
 * 用户可在聊天里让助手调用本工具，验证内置运行时（libmuse_node.so + npm 数据）
 * 在真机上的可用性。除一次性数据解压外均为只读检查，无其它副作用。
 */
object RuntimeSelfCheckTool {

    const val NAME = "runtime_selfcheck"

    fun toolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME,
        description = "Check the status of Shaheen 2's built-in Node runtime (the runtime environment inside the app sandbox): node version, npm version, and data extraction state." +
            "Call it when the user asks about the 'built-in runtime', 'runtime self-check', 'Node sandbox' or 'MCP runtime environment', or when troubleshooting built-in runtime problems." +
            "Returns: the itemised check-result text.",
        parameters = emptyMap(),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    suspend fun execute(
        context: Context,
        /** v2.6.7: 可选注入。有则补「设备自动化三档通道」段 —— 自检要一次说完。 */
        automation: io.zer0.muse.automation.core.AutomationManager? = null,
    ): String {
        val sb = StringBuilder("[Built-in runtime self-check]")

        val node = MuseRuntime.nodeBinary(context)
        sb.append("\n- Node binary: ").append(
            if (node.isFile) {
                "Present (${node.length() / 1024 / 1024}MB)"
            } else {
                "Missing (${MuseRuntime.nativeLibDir(context)})"
            },
        )

        val nodeV = runCatching { MuseRuntime.execNode(context, listOf("-v"), timeoutMs = 20_000) }.getOrNull()
        sb.append("\n- node -v: ").append(
            when {
                nodeV == null -> "Execution exception"
                nodeV.exitCode == 0 -> "${nodeV.stdout} ✓"
                else -> {
                    val detail = if (nodeV.stderr.isNotBlank()) " ${nodeV.stderr.take(120)}" else ""
                    "Failed (exit=${nodeV.exitCode}$detail)"
                }
            },
        )

        val data = MuseRuntime.ensureData(context)
        sb.append("\n- Runtime data: ").append(
            if (data.isSuccess) {
                "Ready (${MuseRuntime.bundledDataVersion(context)})"
            } else {
                "Extraction failed (${data.exceptionOrNull()?.message})"
            },
        )

        val npmCli = MuseRuntime.npmCli(context)
        if (npmCli.isFile) {
            val npmV = runCatching {
                MuseRuntime.execNode(context, listOf(npmCli.absolutePath, "-v"), timeoutMs = 60_000)
            }.getOrNull()
            sb.append("\n- npm -v: ").append(
                when {
                    npmV == null -> "Execution exception"
                    npmV.exitCode == 0 -> "${npmV.stdout} ✓"
                    else -> "Failed (exit=${npmV.exitCode})"
                },
            )
        } else {
            sb.append("\n- npm: not ready")
        }

        sb.append(buildCpuSection(probeCpu(context)))

        sb.append("\n- Conclusion: ").append(
            if (nodeV?.exitCode == 0 && MuseRuntime.isReady(context)) {
                "Runtime ready ✓"
            } else {
                "Runtime incomplete; see the details above"
            },
        )
        sb.append("\n\n")
        appendPythonSection(context, sb)
        // v2.6.7: 补三档通道 —— 助手自审：「runtime_selfcheck 只报了 Node/Python，
        // 没有报 UI 自动化三通道，自检不自检，得调两次」。现在一次报全。
        automation?.let { appendChannelSection(it, sb) }
        return sb.toString()
    }

    /**
     * CPU 探测：内置 Node 的 `os.cpus()` / `os.availableParallelism()` 是否取到核数。
     *
     * 沙盒环境确实可能拿不到（libuv 读不到 CPU 列表），所以只报 warn，不参与整体结论。
     */
    private suspend fun probeCpu(context: Context): CpuProbe? {
        val r = runCatching { MuseRuntime.execNode(context, listOf("-e", CPU_PROBE_SCRIPT), timeoutMs = 20_000) }.getOrNull()
        if (r == null || r.exitCode != 0) return null
        val line = r.stdout.lineSequence().lastOrNull { it.trim().startsWith("{") }?.trim() ?: return null
        return runCatching {
            val obj = AppJson.parseToJsonElement(line) as? JsonObject ?: return@runCatching null
            CpuProbe(
                entries = (obj["cpus"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                parallelism = (obj["ap"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                envCount = (obj["env"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            )
        }.getOrNull()
    }

    /** 渲染 CPU 探测项（纯函数，便于单测）。取不到值时报 warn，不影响整体结论。 */
    internal fun buildCpuSection(probe: CpuProbe?): String {
        val prefix = "\n- CPU probe: "
        if (probe == null) return prefix + "warn (the probe did not run successfully; not getting the core count inside the sandbox does not affect the overall conclusion)"
        val entries = probe.entries ?: 0
        val parallelism = probe.parallelism ?: 0
        val detail =
            "os.cpus().length=$entries, os.availableParallelism()=$parallelism, " +
                "MUSE_CPU_COUNT=${probe.envCount ?: "not injected"}"
        return if (entries > 0 && parallelism > 0) {
            prefix + detail + " ✓"
        } else {
            prefix + detail + " warn (core count unavailable; scripts that depend on the CPU count will degrade, but this does not affect the overall conclusion)"
        }
    }

    /** 内置 Node 侧 CPU 探测结果（字段缺失时为 null）。 */
    internal data class CpuProbe(val entries: Int?, val parallelism: Int?, val envCount: Int?)

    private const val CPU_PROBE_SCRIPT =
        "const os=require('node:os');" +
            "console.log(JSON.stringify({cpus:os.cpus().length," +
            "ap:os.availableParallelism(),env:process.env.MUSE_CPU_COUNT||null}))"

    /** 设备控制通道段（无障碍 / Shizuku / Root + 单项运行时权限提示）。 */
    private suspend fun appendChannelSection(automation: io.zer0.muse.automation.core.AutomationManager, sb: StringBuilder) {
        val state = automation.refreshPermissions()
        sb.append("\n\n[Device control channel self-check]")
        sb.append("\n- Accessibility: ").append(if (state.accessibilityEnabled) "Enabled ✓" else "Not enabled")
        sb.append("\n- Shizuku/ADB: ").append(
            if (state.shellEnabled) "Ready ✓" else "Unavailable (${state.shizukuMessage})",
        )
        sb.append("\n- Root: ").append(if (state.rootEnabled) "Obtained ✓" else "Not obtained")
        sb.append("\n- Highest available tier: ").append(state.highestLevelName())
        sb.append("\n- Conclusion: ").append(
            if (state.anyEnabled) {
                "Device operations can be executed; call tool_status to see which tools are available"
            } else {
                "No device control channel at all; screen/app/shell tools are all unavailable; only in-app operations are possible"
            },
        )
    }

    /** v2.7: 追加内置 Python 运行时自检段（拆出以控制单函数长度与复杂度）。 */
    private suspend fun appendPythonSection(context: Context, sb: StringBuilder) {
        sb.append("[Built-in Python runtime self-check]")
        val pyBin = PythonRuntime.pythonBinary(context)
        sb.append("\n- Python stub: ").append(
            if (pyBin.isFile) "Present (${pyBin.length() / 1024}KB)" else "Missing (${PythonRuntime.nativeLibDir(context)})",
        )
        val pyV = runCatching { PythonRuntime.execPython(context, listOf("-V"), timeoutMs = 30_000) }.getOrNull()
        sb.append("\n- python -V: ").append(
            when {
                pyV == null -> "Execution exception"
                pyV.exitCode == 0 -> "${(pyV.stdout + pyV.stderr).trim()} ✓"
                else -> "Failed (exit=${pyV.exitCode} ${pyV.stderr.take(120)})"
            },
        )
        val pyData = PythonRuntime.ensureData(context)
        sb.append("\n- Python data: ").append(
            if (pyData.isSuccess) "Ready (${PythonRuntime.PYTHON_DATA_VERSION})" else "Extraction failed (${pyData.exceptionOrNull()?.message})",
        )
        if (pyV?.exitCode == 0 && pyData.isSuccess) {
            val probe = runCatching {
                PythonRuntime.execPython(
                    context,
                    listOf("-c", "import ssl,sqlite3,zlib,json;print('ext-ok')"),
                    timeoutMs = 30_000,
                )
            }.getOrNull()
            sb.append("\n- Standard library extensions: ").append(
                if (probe?.exitCode == 0 && probe.stdout.contains("ext-ok")) "ssl/sqlite3/zlib/json ✓" else "Loading exception",
            )
        }
        sb.append("\n- Python conclusion: ").append(
            if (pyV?.exitCode == 0 && PythonRuntime.isReady(context)) "Runtime ready ✓" else "Runtime incomplete; see the details above",
        )
    }
}

/**
 * v2.x 扩展运行时（P0）：自检工具注册器（init 块自动注册到 ToolRegistry）。
 */
class RuntimeSelfCheckToolRegistrar(
    private val toolRegistry: ToolRegistry,
    private val context: Context,
    /** v2.6.7: 注入后自检会一并上报三档通道状态。 */
    private val automationManager: io.zer0.muse.automation.core.AutomationManager? = null,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(RuntimeSelfCheckTool.toolDef()) { _ ->
            RuntimeSelfCheckTool.execute(context, automationManager)
        }
    }
}
