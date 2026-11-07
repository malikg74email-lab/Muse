package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.runtime.MuseRuntime

/**
 * v2.x 扩展运行时：平台自检工具。
 *
 * 让助手能在聊天里自查"它所在的这个 App 平台"到底有哪些能力可用 ——
 * 覆盖内置 Node 运行时 + 各工具通道的授权/就绪状态,一次调用给出结构化清单。
 *
 * 用途:
 *  - 用户问"内置运行时能用吗""这些工具能不能用上"时直接调用;
 *  - 排查"某个工具调不动"时先自检,把"权限未授予"与"功能故障"区分开。
 *
 * 设计:
 *  - 只读检查,无副作用(除 Node 数据一次性解压,本就在应用初始化会做);
 *  - 通道状态来自运行环境授权提供器([ToolRegistry.permissionStatusProvider]),
 *    与工具执行前的预检同源,不会出现"自检说能用但实际调不动"的分叉;
 *  - 单点失败不影响其它项(每项独立 runCatching)。
 */
object PlatformSelfCheckTool {

    const val NAME = "platform_selfcheck"

    fun toolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME,
        description = "Self-check Shaheen 2's runtime platform: the built-in Node runtime state + each tool channel's (Accessibility/Shell/Root/Termux)" +
            "authorisation and readiness, returned as a structured checklist." +
            "Call it when the user asks about 'self-check', 'platform capability', 'can the tools be used' or 'runtime state', or when troubleshooting tools that will not run.",
        parameters = emptyMap(),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    /**
     * 执行自检。
     *
     * @param context 应用上下文
     * @param permissionStatusProvider 运行环境授权状态提供器(来自 ToolRegistry,与执行预检同源)
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    suspend fun execute(context: Context, permissionStatusProvider: (() -> ToolPermissionStatus?)?): String {
        val sb = StringBuilder("[Platform self-check]")

        // ── 1. 内置 Node 运行时 ──────────────────────────────────────────
        sb.append("\n■ Built-in Node runtime")
        val node = MuseRuntime.nodeBinary(context)
        sb.append("\n  - Node binary: ").append(
            if (node.isFile) "Present (${node.length() / 1024 / 1024}MB)" else "Missing",
        )
        val nodeV = runCatching { MuseRuntime.execNode(context, listOf("-v"), timeoutMs = 20_000) }.getOrNull()
        sb.append("\n  - node -v: ").append(
            when {
                nodeV == null -> "Execution exception"
                nodeV.exitCode == 0 -> "${nodeV.stdout} ✓"
                else -> "Failed (exit=${nodeV.exitCode})"
            },
        )
        val data = MuseRuntime.ensureData(context)
        sb.append("\n  - Runtime data: ").append(
            if (data.isSuccess) "Ready (${MuseRuntime.bundledDataVersion(context)})" else "Extraction failed",
        )
        val npmCli = MuseRuntime.npmCli(context)
        if (npmCli.isFile) {
            val npmV = runCatching {
                MuseRuntime.execNode(context, listOf(npmCli.absolutePath, "-v"), timeoutMs = 60_000)
            }.getOrNull()
            sb.append("\n  - npm -v: ").append(
                when {
                    npmV == null -> "Execution exception"
                    npmV.exitCode == 0 -> "${npmV.stdout} ✓"
                    else -> "Failed (exit=${npmV.exitCode})"
                },
            )
        } else {
            sb.append("\n  - npm: not ready")
        }
        sb.append("\n  - Conclusion: ").append(
            if (nodeV?.exitCode == 0 && MuseRuntime.isReady(context)) "Runtime ready ✓" else "Runtime incomplete",
        )

        // ── 2. 工具通道授权/就绪 ─────────────────────────────────────────
        sb.append("\n■ Tool channels")
        val status = runCatching { permissionStatusProvider?.invoke() }.getOrNull()
        if (status == null) {
            sb.append("\n  - Unable to get channel status (the authorisation provider is not ready)")
        } else {
            appendChannel(sb, "Accessibility (screen reading/tapping)", status.accessibility, "Once enabled, screen-reading and UI-tap tools can be executed")
            appendChannel(sb, "Shell / Root", status.shellTier, "Once enabled, command-class and system-level tools can be executed")
            appendChannel(sb, "Termux", status.termux, "Once enabled, the termux command channel can be executed")
        }

        // ── 3. 结论指引 ─────────────────────────────────────────────────
        sb.append("\n■ Conclusion")
        val allOk = status?.let { it.accessibility && it.shellTier } == true
        sb.append(
            if (allOk) {
                "\n  Main channels ready ✓. If a particular tool still will not run, it is most likely a problem with that tool itself; provide the specific tool name for further troubleshooting."
            } else {
                "\n  Some channels are not ready — tools belonging to a not-ready channel will be unavailable and only take effect after the user authorises them in system settings." +
                    "This is a permission not being granted, not a functional fault."
            },
        )
        return sb.toString()
    }

    private fun appendChannel(sb: StringBuilder, label: String, ready: Boolean, hint: String) {
        sb.append("\n  - ").append(label).append(": ")
            .append(if (ready) "Available ✓" else "Not granted")
        if (!ready) sb.append(" —— ").append(hint)
    }
}

/**
 * v2.x 扩展运行时：平台自检工具注册器(init 块自动注册到 ToolRegistry)。
 *
 * 通道状态通过 [ToolRegistry.permissionStatusProvider] 读取 —— 与工具执行前预检同源,
 * 避免"自检说可用、实际调不动"的分叉。
 */
class PlatformSelfCheckToolRegistrar(
    private val toolRegistry: ToolRegistry,
    private val context: Context,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(PlatformSelfCheckTool.toolDef()) { _ ->
            PlatformSelfCheckTool.execute(context) {
                toolRegistry.permissionStatusProvider?.invoke()
            }
        }
    }
}
