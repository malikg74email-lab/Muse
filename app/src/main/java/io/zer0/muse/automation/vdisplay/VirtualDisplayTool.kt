package io.zer0.muse.automation.vdisplay

import android.content.Context
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.core.DeviceCommandPolicy
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import kotlinx.coroutines.delay
import java.io.File

/**
 * v2.2.1 虚拟屏:AI 工具 `virtual_screen`。
 *
 * 把虚拟屏能力注册为工具:ensure(建屏)/ open(屏内开应用)/ read(控件树)/ shot(截图)/
 * close(销毁)/ status(状态)。输入动作另注册为高风险 `virtual_screen_input`,
 * 由本类统一构造并校验 `input -d <displayId> ...`，避免 Agent 退回 device_shell
 * 自己拼接命令。
 */
class VirtualDisplayTool(
    private val context: Context,
    private val client: VirtualDisplayClient,
    private val manager: VirtualDisplayServerManager,
    private val automationManager: AutomationManager,
) {
    fun register(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "virtual_screen",
                description =
                "Virtual screen (background hidden screen): run apps on a separate display; screenshots and actions do not occupy the foreground or disturb the user." +
                    "action=ensure creates or reuses a virtual screen (returns displayId); action=open opens an app on the virtual screen (needs package);" +
                    "action=shot captures the virtual screen image (saved as a JPEG file, returns the path); action=close destroys the virtual screen;" +
                    "action=status queries the status. To tap/swipe/press keys on the virtual screen, use device_shell to run " +
                    "`input -d <displayId> tap/swipe/keyevent ...`. Requires a Shizuku or Root permission channel (Settings → Permission wizard).",
                parameters =
                mapOf(
                    "action" to "Required: ensure | open | read | shot | close | status",
                    "package" to "The app package name for open (e.g. com.android.settings)",
                    "width" to "Optional: virtual screen width (default 720)",
                    "height" to "Optional: virtual screen height (default 1280)",
                    "dpi" to "Optional: virtual screen density (default 320)",
                    "display" to "Optional: specify displayId (default: the most recent ensure)",
                ),
                required = setOf("action"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            when (val action = args["action"]?.trim()?.lowercase()) {
                "ensure" -> handleEnsure(args)
                "open" -> handleOpen(args)
                "read" -> handleRead(args)
                "shot" -> handleShot(args)
                "close" -> handleClose(args)
                "status" -> handleStatus()
                null, "" -> "Error: missing action parameter (ensure/open/read/shot/close/status)"
                else -> "Error: unknown action '$action' (ensure/open/read/shot/close/status)"
            }
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "virtual_screen_input",
                description = "Execute one input action inside the background virtual screen. First confirm the picture with virtual_screen shot," +
                    "then call tap/swipe/text/key/wait; the action only affects the specified display and does not switch the user's foreground." +
                    "Requires Shizuku or Root; this is a high-risk cross-app operation and needs approval before each execution.",
                parameters = mapOf(
                    "action" to "Required: tap | swipe | text | key | wait",
                    "display" to "Optional: displayId, default the virtual screen from the most recent ensure",
                    "x" to "X coordinate for tap",
                    "y" to "Y coordinate for tap",
                    "x1" to "Swipe start X",
                    "y1" to "Swipe start Y",
                    "x2" to "Swipe end X",
                    "y2" to "Swipe end Y",
                    "duration_ms" to "Swipe duration in milliseconds, default 400",
                    "text" to "Text input content, at most 500 characters",
                    "key" to "Key name or number, e.g. BACK/HOME/ENTER/KEYCODE_4",
                    "ms" to "Wait milliseconds, 50-10000",
                ),
                required = setOf("action"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> handleInput(args) }
    }

    private suspend fun handleEnsure(args: Map<String, String>): String {
        val width = args["width"]?.toIntOrNull() ?: 720
        val height = args["height"]?.toIntOrNull() ?: 1280
        val dpi = args["dpi"]?.toIntOrNull() ?: 320
        if (width !in 320..1920 || height !in 320..2560) {
            return "Error: size out of range (width 320-1920, height 320-2560)"
        }
        return client.ensureDisplay(width, height, dpi).fold(
            onSuccess = {
                "Virtual screen ready: displayId=${it.displayId} (${it.width}x${it.height})." +
                    "Open an app with virtual_screen open; do input with device_shell `input -d ${it.displayId} ...`."
            },
            onFailure = { "Virtual screen creation failed: ${it.message}" },
        )
    }

    private suspend fun handleOpen(args: Map<String, String>): String {
        val pkg = args["package"]?.trim().orEmpty()
        if (pkg.isBlank()) return "Error: open requires the package parameter"
        val explicitDisplayId = args["display"]?.toIntOrNull()
        val displayId = explicitDisplayId ?: manager.lastDisplayId
        if (displayId < 0) {
            val ensured = handleEnsure(args)
            if (!ensured.startsWith("Virtual screen ready")) return ensured
        }
        val id = if (displayId >= 0) displayId else manager.lastDisplayId
        return if (client.openApp(pkg, id)) {
            val actualId = explicitDisplayId ?: manager.lastDisplayId
            "Opened $pkg on virtual screen $actualId (wait 1-2 seconds for rendering, then you can screenshot with shot)"
        } else {
            "Open failed: unable to resolve $pkg's launch activity (app not installed or restricted by the system)"
        }
    }

    private suspend fun handleShot(args: Map<String, String>): String {
        val displayId = args["display"]?.toIntOrNull() ?: manager.lastDisplayId
        val bytes =
            client.screenshot(displayId)
                ?: return "Screenshot failed: the virtual screen is not created or the server is not ready (run action=ensure first)"
        return runCatching {
            val dir = File(context.filesDir, "vd").apply { mkdirs() }
            val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
            file.writeBytes(bytes)
            "Screenshot saved: ${file.absolutePath} (${bytes.size / 1024}KB, displayId=$displayId)"
        }.getOrElse { "Screenshot save failed: ${it.message}" }
    }

    private suspend fun handleRead(args: Map<String, String>): String {
        val displayId = args["display"]?.toIntOrNull() ?: manager.lastDisplayId
        val info = automationManager.readScreenOnDisplay(displayId)
            ?: return "Read failed: virtual screen semantic reading requires a Shizuku or Root channel"
        return buildString {
            appendLine(info.toSummary(50))
            if (info.nodes.isNotEmpty()) {
                appendLine("---")
                appendLine("Interactive controls:")
                info.nodes.filter { it.isClickable || it.isEditable }.take(20).forEachIndexed { index, node ->
                    appendLine("[$index] ${node.toShortString()}")
                }
            }
        }
    }

    private suspend fun handleClose(args: Map<String, String>): String {
        val displayId = args["display"]?.toIntOrNull() ?: manager.lastDisplayId
        return if (client.destroy(displayId)) "Virtual screen $displayId destroyed" else "No virtual screen to destroy"
    }

    private suspend fun handleStatus(): String {
        val proxy = manager.existingLiveProxy()
        val channel = manager.channelState()
        return buildString {
            appendLine("Permission channel: $channel")
            appendLine("Server: ${if (proxy != null) "online" else "not running (auto-starts on first call)"}")
            append("Virtual screen: ${if (manager.lastDisplayId >= 0) "displayId=${manager.lastDisplayId}" else "not created"}")
        }
    }

    private suspend fun handleInput(args: Map<String, String>): String {
        val displayId = args["display"]?.toIntOrNull() ?: run {
            client.ensureCompatibilityDisplay().getOrElse {
                return "Error: virtual screen refresh failed: ${it.message ?: "server not ready"}"
            }.displayId
        }
        if (displayId < 0) {
            return "Error: virtual screen not created; please run virtual_screen action=ensure first"
        }
        val action = args["action"]?.trim()?.lowercase().orEmpty()
        val command = VirtualDisplayInputCommand.build(displayId, action, args)
        return when {
            command == null -> "Error: invalid input parameters (action=$action)"
            command.waitMs != null -> {
                delay(command.waitMs)
                "Virtual screen $displayId waited ${command.waitMs}ms"
            }
            else -> executeInputCommand(displayId, command)
        }
    }

    private suspend fun executeInputCommand(displayId: Int, command: VirtualDisplayInputCommand): String {
        return when (val check = DeviceCommandPolicy.validate(command.shellCommand)) {
            is DeviceCommandPolicy.Check.Invalid -> "Error: input command rejected by policy: ${check.reason}"
            DeviceCommandPolicy.Check.Valid -> {
                val result = manager.exec(command.shellCommand)
                if (result.exitCode == 0) {
                    "Virtual screen $displayId ${command.description} succeeded"
                } else {
                    "Virtual screen $displayId ${command.description} failed (exit=${result.exitCode}): ${result.output.take(500)}"
                }
            }
        }
    }
}

internal data class VirtualDisplayInputCommand(
    val shellCommand: String,
    val description: String,
    val waitMs: Long? = null,
) {
    companion object {
        private val KEY_REGEX = Regex("^[A-Za-z0-9_]+$")

        fun build(displayId: Int, action: String, args: Map<String, String>): VirtualDisplayInputCommand? {
            return when (action) {
                "tap" -> buildTap(displayId, args)
                "swipe" -> buildSwipe(displayId, args)
                "text" -> buildText(displayId, args)
                "key" -> buildKey(displayId, args)
                "wait" -> buildWait(args)
                else -> null
            }
        }

        private fun buildTap(displayId: Int, args: Map<String, String>): VirtualDisplayInputCommand? {
            val x = args["x"]?.toIntOrNull()
            val y = args["y"]?.toIntOrNull()
            return if (x != null && y != null) command(displayId, "tap $x $y", "Tap ($x,$y)") else null
        }

        private fun buildSwipe(displayId: Int, args: Map<String, String>): VirtualDisplayInputCommand? {
            val x1 = args["x1"]?.toIntOrNull()
            val y1 = args["y1"]?.toIntOrNull()
            val x2 = args["x2"]?.toIntOrNull()
            val y2 = args["y2"]?.toIntOrNull()
            val duration = args["duration_ms"]?.toLongOrNull()?.coerceIn(50L, 5_000L) ?: 400L
            return if (listOf(x1, y1, x2, y2).any { it == null }) {
                null
            } else {
                command(displayId, "swipe ${x1!!} ${y1!!} ${x2!!} ${y2!!} $duration", "Swipe")
            }
        }

        private fun buildText(displayId: Int, args: Map<String, String>): VirtualDisplayInputCommand? {
            val text = args["text"]?.takeIf { it.isNotBlank() }?.takeIf { it.length <= 500 } ?: return null
            val unsafe = text.any { it in SHELL_META || it == '\n' || it == '\r' || it == '\\' || it == '"' || it == '\'' }
            return if (unsafe) null else command(displayId, "text ${text.replace(" ", "%s")}", "Input text")
        }

        private fun buildKey(displayId: Int, args: Map<String, String>): VirtualDisplayInputCommand? {
            val key = args["key"]?.trim()?.uppercase()?.takeIf { KEY_REGEX.matches(it) } ?: return null
            return command(displayId, "keyevent $key", "Key press ($key)")
        }

        private fun buildWait(args: Map<String, String>): VirtualDisplayInputCommand? {
            val ms = args["ms"]?.toLongOrNull()?.coerceIn(50L, 10_000L) ?: return null
            return VirtualDisplayInputCommand("", "", waitMs = ms)
        }

        private fun command(displayId: Int, input: String, description: String) =
            VirtualDisplayInputCommand("input -d $displayId $input", description)

        private val SHELL_META = setOf(';', '|', '&', '>', '<', '`', '$', '(', ')', '{', '}')
    }
}
