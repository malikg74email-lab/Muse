package io.zer0.muse.automation.tools

import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.vdisplay.VirtualDisplayClient
import io.zer0.muse.automation.vdisplay.VirtualDisplayLeaseRegistry
import io.zer0.muse.automation.vdisplay.VirtualDisplayServerManager
import io.zer0.muse.tools.ToolOutcome
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.tools.WorkflowJournal
import java.util.UUID

/**
 * UI 自动化工具集 —— 把 [AutomationManager] 的能力注册为 AI 可调用的工具。
 *
 * 工具分两类:
 * - 感知类(读屏/截屏/查前台): 低风险,无需用户确认
 * - 操作类(点击/滑动/输入/按键/启动App): 高风险,受 ToolRiskLevel 管控
 *
 * AI 调用这些工具后可以实现跨 App 的任务自动化(类似豆包手机的系统级助手)。
 */
class AutomationTools(
    private val manager: AutomationManager,
    private val workflowJournal: WorkflowJournal? = null,
    private val virtualDisplayClient: VirtualDisplayClient? = null,
    private val virtualDisplayManager: VirtualDisplayServerManager? = null,
    private val nodeScriptExecutor: (suspend (String, Long) -> io.zer0.muse.tools.script.SkillEngineResult)? = null,
) {
    /** 所有 automation_workflow 工具调用共享的 lease 台账，按 run_id 隔离虚拟屏释放权。 */
    private val displayLeases = VirtualDisplayLeaseRegistry()

    fun register(registry: ToolRegistry) {
        registerWorkflow(registry)
        // ── 感知类 ──────────────────────────────────────────

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_read",
                description = "Read the current screen content. Returns a control-tree summary (text/buttons/input fields and their coordinates)," +
                    "used to judge what is on the interface and where to tap. Call this tool before any tap operation.",
                parameters = mapOf(
                    "query" to "Optional, only return controls containing this keyword",
                ),
                required = emptySet(),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val info = manager.readScreen()
            val query = args["query"]?.takeIf { q -> q.isNotBlank() }
            val nodes = if (query != null) {
                info.nodes.filter { n ->
                    (n.text?.contains(query, ignoreCase = true) == true) ||
                        (n.contentDescription?.contains(query, ignoreCase = true) == true)
                }
            } else {
                info.nodes
            }
            buildString {
                appendLine(info.toSummary(nodes.size.coerceAtMost(50)))
                if (nodes.isNotEmpty()) {
                    appendLine("---")
                    appendLine("Clickable key controls:")
                    nodes.filter { n -> n.isClickable || n.isEditable }
                        .take(15)
                        .forEachIndexed { i, n ->
                            appendLine("[$i] ${n.toShortString()}")
                        }
                }
            }
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_current_app",
                description = "Query the package name and interface name of the currently foreground app.",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { _ ->
            val info = manager.readScreen()
            "Package:  ${info.packageName ?: "Unknown"}\nInterface:  ${info.activityName ?: "Unknown"}\nResolution:  ${info.screenWidth}x${info.screenHeight}"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_back",
                description = "Press the system Back key to return to the previous interface.",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { _ ->
            if (manager.back()) "Returned" else "Back failed (accessibility or Shell permission may be required)"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_home",
                description = "Press the Home key to return to the launcher.",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { _ ->
            if (manager.home()) "Returned to launcher" else "Operation failed"
        }

        // ── 操作类 ──────────────────────────────────────────

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_tap",
                description = "Tap the given coordinates (x,y) on the screen. Coordinates come from the control center positions returned by screen_read." +
                    "Do not guess coordinates out of thin air; confirm with screen_read first.",
                parameters = mapOf(
                    "x" to "Required, the X coordinate of the tap position (pixels)",
                    "y" to "Required, the Y coordinate of the tap position (pixels)",
                ),
                required = setOf("x", "y"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val x = args["x"]?.toIntOrNull() ?: return@register "Error: x must be an integer"
            val y = args["y"]?.toIntOrNull() ?: return@register "Error: y must be an integer"
            if (manager.tap(x, y)) "Tapped ($x,$y)" else "Tap failed"
        }

        registry.registerOutcome(
            ToolRegistry.ToolDef(
                name = "screen_tap_text",
                description = "Find the control containing the given text on the screen and tap its center." +
                    "More convenient than screen_tap — no need to compute coordinates yourself; can scroll a limited number of times within long pages to find it, and can verify the text after tapping.",
                parameters = mapOf(
                    "text" to "Required, the button/text content to find (fuzzy matching supported)",
                    "max_swipes" to "Optional, maximum upward scrolls when not found (0-5, default 0)",
                    "verify_text" to "Optional, text to wait for after tapping; used to confirm the action really took effect",
                ),
                required = setOf("text"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val text = args["text"] ?: return@registerOutcome ToolOutcome.error("Error: missing text parameter")
            val maxSwipes = args["max_swipes"]?.toIntOrNull()?.coerceIn(0, 5) ?: 0
            val verifyText = args["verify_text"]?.trim()?.takeIf { it.isNotBlank() }
            val outcome = manager.tapByTextWithRetryDetailed(text, maxSwipes = maxSwipes, verifyText = verifyText)
            val details = mapOf(
                "attempts" to outcome.attempts,
                "matched" to outcome.matched,
                "verified" to outcome.verified,
                "matchedLabel" to outcome.matchedLabel,
                "verifyRequested" to !verifyText.isNullOrBlank(),
            )
            if (outcome.success) {
                ToolOutcome.ok("Tapped the control containing \"$text\"", details)
            } else {
                ToolOutcome.error("No control containing \"$text\" was found, or it was not verified after tapping", details)
            }
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_swipe",
                description = "Swipe on the screen from one point to another; can be used for scrolling/page turning.",
                parameters = mapOf(
                    "x1" to "Required, start X",
                    "y1" to "Required, start Y",
                    "x2" to "Required, end X",
                    "y2" to "Required, end Y",
                    "duration_ms" to "Optional, swipe duration (milliseconds), default 400",
                ),
                required = setOf("x1", "y1", "x2", "y2"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val x1 = args["x1"]?.toIntOrNull() ?: return@register "Error: x1 must be an integer"
            val y1 = args["y1"]?.toIntOrNull() ?: return@register "Error: y1 must be an integer"
            val x2 = args["x2"]?.toIntOrNull() ?: return@register "Error: x2 must be an integer"
            val y2 = args["y2"]?.toIntOrNull() ?: return@register "Error: y2 must be an integer"
            val dur = args["duration_ms"]?.toLongOrNull() ?: 400L
            if (manager.swipe(x1, y1, x2, y2, dur)) "Swiped" else "Swipe failed"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_pinch",
                description = "Two-finger pinch gesture (zoom in/out). The two fingers open/close symmetrically around (center_x, center_y)." +
                    "Requires the accessibility channel; Shizuku/Root input commands do not support multi-touch.",
                parameters = mapOf(
                    "center_x" to "Required, zoom center X coordinate",
                    "center_y" to "Required, zoom center Y coordinate",
                    "scale" to "Required, zoom scale (>1 zoom in, <1 zoom out), e.g. 2.0 / 0.5",
                    "duration_ms" to "Optional, gesture duration (milliseconds), default 300",
                ),
                required = setOf("center_x", "center_y", "scale"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val cx = args["center_x"]?.toIntOrNull() ?: return@register "Error: center_x must be an integer"
            val cy = args["center_y"]?.toIntOrNull() ?: return@register "Error: center_y must be an integer"
            val scale = args["scale"]?.toFloatOrNull() ?: return@register "Error: scale must be a number"
            if (scale <= 0f) return@register "Error: scale must be greater than 0"
            val dur = args["duration_ms"]?.toLongOrNull() ?: 300L
            // 以 300px 为基准指距,按 scale 换算起止指距(有界,防越界)
            val base = 300
            val end = (base * scale).toInt().coerceIn(60, 2000)
            if (manager.pinch(cx, cy, base, end, dur)) "Zoomed (scale=$scale)" else "Zoom failed (accessibility channel required)"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_swipe_path",
                description = "Multi-segment swipe: a single finger passes through multiple path points in order (at least 2), suitable for pattern unlock / complex drags.",
                parameters = mapOf(
                    "points" to "Required, path point list, in the format \"x1,y1;x2,y2;x3,y3\"(at least 2 points)",
                    "duration_ms" to "Optional, total duration (milliseconds), default 400",
                ),
                required = setOf("points"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val raw = args["points"] ?: return@register "Error: missing points"
            val points = raw.split(";").mapNotNull { seg ->
                val p = seg.split(",")
                if (p.size == 2) {
                    val x = p[0].trim().toIntOrNull() ?: return@mapNotNull null
                    val y = p[1].trim().toIntOrNull() ?: return@mapNotNull null
                    x to y
                } else {
                    null
                }
            }
            if (points.size < 2) return@register "Error: at least 2 valid points are required (format x,y;x,y)"
            val dur = args["duration_ms"]?.toLongOrNull() ?: 400L
            if (manager.swipePath(points, dur)) "Executed a ${points.size}-point path swipe" else "Path swipe failed"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_input",
                description = "Type text into the currently focused input field (Chinese input supported). Tap the input field first to focus it before typing.",
                parameters = mapOf(
                    "text" to "Required, the text to type",
                ),
                required = setOf("text"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val text = args["text"] ?: return@register "Error: missing text parameter"
            if (manager.inputText(text)) "Typed: $text" else "Input failed"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_launch_app",
                description = "Launch the app with the given package name. Can be combined with screen_current_app to confirm the package name.",
                parameters = mapOf(
                    "package" to "Required, the package name of the app to launch, e.g. com.tencent.mm",
                ),
                required = setOf("package"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val pkg = args["package"] ?: return@register "Error: missing package parameter"
            if (manager.launchApp(pkg)) "Launched $pkg" else "Launch failed (the package name may be incorrect)"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_open_notifications",
                description = "Open the system notification shade to view notifications.",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { _ ->
            if (manager.openNotifications()) "Notification shade opened" else "Operation failed"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_permission_status",
                description = "Query the enablement status of each UI automation permission layer (accessibility/Shell/Root)." +
                    "Call this tool before attempting any operation to confirm capabilities.",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { _ ->
            // v2.7: 实时快照 —— 无障碍可能刚开启而缓存未刷新（见 AutomationManager.liveSnapshot）。
            val state = manager.liveSnapshot()
            val level = manager.highestLevel()
            buildString {
                appendLine("UI automation permission status:")
                appendLine("- Accessibility: ${if (state.accessibilityEnabled) "Enabled" else "Not enabled"}")
                appendLine("- Shell: ${if (state.shellEnabled) "Ready" else state.shizukuMessage}")
                appendLine("- Root: ${if (state.rootEnabled) "Acquired" else "Not acquired"}")
                appendLine("- Highest available level: $level")
            }
        }

        // ── 等待原语（感知类）─────────────────────────────

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_wait",
                description = "Wait before the next action: idle = until the screen stops changing; " +
                    "text = until a text appears (or disappears with appear=false); " +
                    "window = until the foreground app/activity changes. " +
                    "Use after launching an app or navigating so clicks do not fire too early.",
                parameters = mapOf(
                    "mode" to "Optional. idle | text | window (default idle)",
                    "text" to "Required when mode=text. Substring to look for",
                    "appear" to "Optional. true=wait for appearance (default), false=disappearance",
                    "timeout_ms" to "Optional. Max wait ms, default 15000, cap 60000",
                ),
                required = emptySet(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val timeout = args["timeout_ms"]?.toLongOrNull()?.coerceIn(1_000L, 60_000L) ?: 15_000L
            val snapshotProvider: suspend () -> io.zer0.muse.automation.core.ScreenSnapshot = {
                io.zer0.muse.automation.core.ScreenSnapshot(info = manager.readScreen())
            }
            when (args["mode"]?.lowercase() ?: "idle") {
                "text" -> {
                    val text = args["text"]?.takeIf { it.isNotBlank() }
                    if (text == null) {
                        "error: text is required when mode=text"
                    } else {
                        val appear = args["appear"]?.lowercase() != "false"
                        val ok = io.zer0.muse.automation.core.WaitPrimitives.waitForText(
                            text = text,
                            appear = appear,
                            snapshotProvider = snapshotProvider,
                            timeoutMs = timeout,
                        )
                        if (ok) "text condition satisfied" else "timeout after ${timeout}ms"
                    }
                }
                "window" -> {
                    val before = manager.readScreen()
                    val ok = io.zer0.muse.automation.core.WaitPrimitives.waitForWindowChange(
                        initialPackage = before.packageName,
                        initialActivity = before.activityName,
                        snapshotProvider = snapshotProvider,
                        timeoutMs = timeout,
                    )
                    if (ok) "foreground window changed" else "timeout after ${timeout}ms"
                }
                else -> {
                    val ok = io.zer0.muse.automation.core.WaitPrimitives.waitForIdle(
                        snapshotProvider = snapshotProvider,
                        timeoutMs = timeout,
                    )
                    if (ok) "screen is idle" else "timeout after ${timeout}ms"
                }
            }
        }

        // ── 设备命令通道（自动化一期）─────────────────────────

        registry.register(
            ToolRegistry.ToolDef(
                name = "device_shell",
                description = "Execute a single device command (control the phone from the command line); requires Shizuku or Root authorization." +
                    "Supports: input (tap/swipe/text/keyevent/roll/draganddrop/press), am (start/broadcast/force-stop/startservice/kill)," +
                    "pm(list/path/dump/enable/disable/grant/revoke/clear)、settings(get/put/delete/list)、svc(power/wifi/data/bluetooth)、" +
                    "wm(size/density)、dumpsys、uiautomator dump、screencap、screendump、content(query/insert/update/delete)、cmd、getprop、date。" +
                    "Pipes/redirection/multi-command chaining are not supported — write one complete command each time. Complementary to the screen_* tools: screen_* are high-level actions, this tool offers command-line freedom.",
                parameters = mapOf(
                    "command" to "Required. A single device command, e.g. input tap 540 1200 / input swipe 540 1500 540 500 300 / " +
                        "am start -n com.tencent.mm/.ui.LauncherUI / settings get secure location_mode / uiautomator dump",
                ),
                required = setOf("command"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val command = args["command"]?.trim().orEmpty()
            when (val check = io.zer0.muse.automation.core.DeviceCommandPolicy.validate(command)) {
                is io.zer0.muse.automation.core.DeviceCommandPolicy.Check.Invalid ->
                    "Command rejected: ${check.reason}"
                io.zer0.muse.automation.core.DeviceCommandPolicy.Check.Valid -> {
                    val result = manager.execTiered(command)
                        ?: return@register "Device command channel unavailable: Shizuku or Root authorization is required (Settings → Permission setup wizard); neither tier is ready right now"
                    val (tier, detail) = result
                    buildString {
                        append("[").append(tier).append("] exit=").append(detail.exitCode).append('\n')
                        if (detail.output.isNotBlank()) {
                            append(detail.output)
                        } else {
                            append("(no output)")
                        }
                    }
                }
            }
        }
    }

    private fun registerWorkflow(registry: ToolRegistry) {
        registry.registerOutcome(
            ToolRegistry.ToolDef(
                name = "automation_workflow",
                description = "Execute a recoverable controlled phone workflow, running foreground semantic actions and virtual_* virtual-screen actions in order;" +
                    "Supports launch/tap_text/input_text/swipe/back/home/wait/read, virtual-screen operations, and node_script running Node scripts inside the app sandbox." +
                    "node_script can access app sandbox files, the network and subprocesses; script results are encrypted with the Keystore and saved in the recovery log. Execution stops at the first failing step; approval is required before execution.",
                parameters = mapOf(
                    "steps" to "Required JSON array, at most 20 steps; supports " +
                        "virtual_ensure/virtual_launch/virtual_tap/virtual_tap_text/virtual_swipe/" +
                        "virtual_text/virtual_key/virtual_wait/virtual_read/virtual_close；" +
                        "virtual_tap_text supports text/exact/maxSwipes/verifyText, bounded scrolling inside the virtual screen with tap verification;" +
                        "node_script steps use code/timeoutMs (1-300 seconds); failures are treated as unknown side effects",
                    "run_id" to "Optional. Resume a previously interrupted workflow; leave empty on the first run and a new runId is generated",
                    "retry_unknown" to "Optional. When a step crashes, its result may be unknown; set true only after confirming to retry that action",
                ),
                required = setOf("steps"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val json = args["steps"]?.trim()
                ?: return@registerOutcome ToolOutcome.error("Error: missing steps parameter")
            val steps = AutomationWorkflowParser.parse(json).getOrElse { error ->
                return@registerOutcome ToolOutcome.error("Error: steps invalid: ${error.message}")
            }
            val runId = args["run_id"]?.trim().takeIf { !it.isNullOrBlank() } ?: "automation-${UUID.randomUUID()}"
            val retryUnknown = args["retry_unknown"]?.equals("true", ignoreCase = true) == true
            AutomationWorkflow(
                manager,
                workflowJournal,
                virtualDisplayClient,
                virtualDisplayManager,
                nodeScriptExecutor,
                displayLeases,
            ).run(steps, runId, retryUnknown)
        }
    }

    /** 初始化时刷新权限状态。 */
    suspend fun initialize() {
        try {
            manager.refreshPermissions()
        } catch (e: Exception) {
            Logger.w(TAG, "initial permission refresh failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AutomationTools"
    }
}
