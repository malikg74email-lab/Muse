package io.zer0.muse.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.zer0.muse.automation.core.AutomationManager

internal fun formatSettingsPutResult(tier: String, namespace: String, name: String): String = "[$tier] Setting '$namespace:$name' updated"

internal fun formatInputInjectResult(tier: String, textLength: Int, viaClipboard: Boolean): String =
    "[$tier] Text injected${if (viaClipboard) " via clipboard paste" else ""} ($textLength chars)"

/**
 * v2.x 自动化一期:设备级工具注册器(原 Root-level,现改为分层执行)。
 *
 * 暴露高权限设备能力为 AI 可调用工具;执行统一走 [AutomationManager.execTiered] ——
 * **Shizuku 优先、Root 兜底**,两档均未授权时返回明确错误。
 * 所有工具 [ToolRiskLevel.HIGH],走既有审批链与审计基础设施。
 *
 * Tools:
 * - settings_get / settings_put — 读写系统设置
 * - network_toggle — svc 开关 wifi/数据
 * - am_start — 启动 Activity(含 extras)
 * - list_packages — 列出已安装包
 * - logcat_tail — 读取最近日志
 * - input_inject — 向焦点输入框注入文本(带剪贴板粘贴兜底)
 */
class RootToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val manager: AutomationManager,
    private val context: Context,
) {
    private val builder get() = manager.root

    init {
        registerAll()
    }

    private fun tierUnavailable(): String = "Device command channel unavailable: requires Shizuku or Root authorisation (Settings → Permission configuration wizard); neither tier is ready right now"

    /** 执行一条设备命令;[tier] 前缀便于模型与用户区分走的是哪档。 */
    private suspend fun runCommand(command: String): Pair<String, io.zer0.muse.automation.executors.ShellExecutor.ExecDetail>? =
        manager.execTiered(command)

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "settings_get",
                description = "Read Android system settings by name (system / secure / global)." +
                    "Can query airplane mode, Wi-Fi state, location mode, etc." +
                    "Format is namespace:name, e.g. secure:location_mode." +
                    "Requires Shizuku or Root authorisation.",
                parameters = mapOf(
                    "name" to
                        "Required. Setting name, may carry a namespace prefix (global / secure / system)," +
                        "e.g. secure:location_mode",
                ),
                required = setOf("name"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val name = args["name"]?.takeIf { it.isNotBlank() } ?: return@register "Error: name is required"
            val cmd = builder.buildSettingsGet(name) ?: return@register "Error: invalid setting name '$name'"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode == 0) {
                "[$tier] $name = ${detail.output.trim()}"
            } else {
                formatRootFailureOutput(tier, "Read failed", detail.output, detail.exitCode)
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "network_toggle",
                description = "Toggle Wi-Fi or mobile data via the svc command." +
                    "Requires Shizuku or Root authorisation. Use with care; it may interrupt the network connection.",
                parameters = mapOf(
                    "service" to "Required. Service to toggle: wifi or data",
                    "enabled" to "Required. true to enable, false to disable",
                ),
                required = setOf("service", "enabled"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val service = args["service"]?.takeIf { it.isNotBlank() } ?: return@register "Error: service is required"
            val enabled = args["enabled"]?.trim()?.lowercase()?.toBooleanStrictOrNull()
                ?: return@register "Error: enabled must be true or false"
            val cmd = builder.buildSvcToggle(service, enabled)
                ?: return@register "Error: unsupported service '$service' (only wifi/data)"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode == 0) {
                "[$tier] $service ${if (enabled) "enabled" else "disabled"}"
            } else {
                formatRootFailureOutput(tier, "Toggle failed", detail.output, detail.exitCode)
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "settings_put",
                description = "Write Android system settings (system / secure / global)." +
                    "Requires Shizuku or Root authorisation. Use with care; it changes device behaviour.",
                parameters = mapOf(
                    "namespace" to "Required. Setting namespace: global, secure, or system",
                    "name" to "Required. Setting name",
                    "value" to "Required. Value to set",
                ),
                required = setOf("namespace", "name", "value"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val namespace = args["namespace"]?.takeIf { it.isNotBlank() } ?: return@register "Error: namespace is required"
            val name = args["name"]?.takeIf { it.isNotBlank() } ?: return@register "Error: name is required"
            val value = args["value"]?.takeIf { it.isNotBlank() } ?: return@register "Error: value is required"
            val cmd = builder.buildSettingsPut(namespace, name, value)
                ?: return@register "Error: invalid settings parameters"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode == 0) {
                formatSettingsPutResult(tier, namespace, name)
            } else {
                formatRootFailureOutput(tier, "Write failed", detail.output, detail.exitCode)
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "am_start",
                description = "Launch an Activity via the am start command." +
                    "extras use the key=value form, multiple items separated by |." +
                    "Requires Shizuku or Root authorisation.",
                parameters = mapOf(
                    "package" to "Required. Package name, e.g., com.android.settings",
                    "class" to "Optional. Activity class name, e.g., .Settings\$WifiSettingsActivity",
                    "extras" to "Optional. Key-value extras separated by |, e.g., title=Hello|count=5",
                ),
                required = setOf("package"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val pkg = args["package"]?.takeIf { it.isNotBlank() } ?: return@register "Error: package is required"
            val cls = args["class"]
            val extras = args["extras"]
            val cmd = builder.buildAmStart(pkg, cls, extras) ?: return@register "Error: invalid package/class/extras"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode == 0) {
                "[$tier] Activity started: $pkg${cls?.let { "/$it" }.orEmpty()}"
            } else {
                formatRootFailureOutput(tier, "Launch failed", detail.output, detail.exitCode)
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "list_packages",
                description = "List installed package names, filterable by substring. Used to find an app's package name." +
                    "Requires Shizuku or Root authorisation.",
                parameters = mapOf(
                    "filter" to "Optional. Substring to filter package names",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val filter = args["filter"]
            val cmd = builder.buildListPackages(filter) ?: return@register "Error: invalid filter"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode != 0) {
                formatRootFailureOutput(tier, "Listing failed", detail.output, detail.exitCode)
            } else {
                val packages = detail.output.lineSequence()
                    .mapNotNull { line -> Regex("package:(.+)").find(line)?.groupValues?.get(1) }
                    .distinct()
                    .sorted()
                    .toList()
                if (packages.isEmpty()) {
                    "No packages found"
                } else {
                    formatRootPackageListOutput(tier, packages)
                }
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "logcat_tail",
                description = "Read the most recent N lines of logcat. Used to troubleshoot app crashes or system events." +
                    "Requires Shizuku or Root authorisation.",
                parameters = mapOf(
                    "lines" to "Optional. Number of lines to read, default 100",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val lines = (args["lines"]?.toIntOrNull() ?: 100).coerceIn(1, 2_000)
            val result = runCommand(builder.buildLogcatTail(lines)) ?: return@register tierUnavailable()
            val (tier, detail) = result
            formatRootLogcatOutput(tier, detail.output)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "input_inject",
                description = "Inject text into the currently focused input field." +
                    "More reliable than accessibility input when special characters are involved." +
                    "Requires Shizuku or Root authorisation.",
                parameters = mapOf(
                    "text" to "Required. Text to inject",
                ),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val text = args["text"] ?: return@register "Error: text is required"
            val cmd = builder.buildInputInject(text) ?: return@register "Error: empty text"
            val result = runCommand(cmd) ?: return@register tierUnavailable()
            val (tier, detail) = result
            if (detail.exitCode == 0) {
                formatInputInjectResult(tier, text.length, viaClipboard = false)
            } else {
                // 兜底:写入剪贴板 + PASTE 按键(部分 ROM 的 input text 对特殊字符不可靠)
                val pasteResult = runCatching {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("muse-inject", text))
                    manager.execTiered("input keyevent 279")
                }.getOrNull()
                if (pasteResult != null && pasteResult.second.exitCode == 0) {
                    formatInputInjectResult(tier, text.length, viaClipboard = true)
                } else {
                    formatRootFailureOutput(tier, "Injection failed", detail.output, detail.exitCode)
                }
            }
        }
    }
}

internal fun formatRootLogcatOutput(tier: String, output: String): String = "[$tier] Logcat output (${output.length} chars):\n$output"

internal fun formatRootPackageListOutput(tier: String, packages: List<String>): String =
    "[$tier] Found ${packages.size} packages:\n${packages.joinToString("\n")}"

internal fun formatRootFailureOutput(tier: String, action: String, output: String, exitCode: Int): String =
    "[$tier] $action: ${output.ifBlank { "exit=$exitCode" }}"
