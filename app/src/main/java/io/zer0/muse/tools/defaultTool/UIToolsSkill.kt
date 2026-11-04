package io.zer0.muse.tools.defaultTool

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.tools.system.AccessibilityClient
import java.io.File

/**
 * v1.0.52 P3-3: UI 自动化工具集 — 暴露给 AI 的设备操控能力。
 *
 * 依赖 [AccessibilityClient](无障碍服务通道),提供 10 个工具:
 *  - [getPageInfo]: 获取当前页面 UI 层级(节点树,含坐标/文本/可点击性)
 *  - [click] / [longPress]: 在坐标点击/长按
 *  - [swipe]: 滑动手势(指定起止坐标 + 时长)
 *  - [setText]: 在指定节点(通过 path 标识)输入文本
 *  - [screenshot]: 截图保存到文件(需 Android 14+)
 *  - [back] / [home]: 全局动作快捷键
 *  - [globalAction]: 执行任意全局无障碍动作
 *  - [getCurrentApp]: 查询当前前台应用
 *
 * 安全:
 *  - 全部 HIGH 风险等级(直接操控用户设备,可能触发不可逆操作)
 *  - 需无障碍服务已启用(ACCESSIBILITY 及以上等级)
 *  - 会话权限体系会要求审批(非 TRUSTED 模式)
 *
 * 返回格式:
 *  - 成功: 可读文本(JSON 或结构化文本)
 *  - 失败: "[错误] ..." 形式
 */
object UIToolsSkill {

    private const val TAG = "UIToolsSkill"

    // ── 工具名常量 ────────────────────────────────────────────────────────────

    const val GET_PAGE_INFO = "ui_get_page_info"
    const val CLICK = "ui_click"
    const val LONG_PRESS = "ui_long_press"
    const val SWIPE = "ui_swipe"
    const val SET_TEXT = "ui_set_text"
    const val SCREENSHOT = "ui_screenshot"
    const val BACK = "ui_back"
    const val HOME = "ui_home"
    const val GLOBAL_ACTION = "ui_global_action"
    const val GET_CURRENT_APP = "ui_get_current_app"

    // ── 工具定义 ──────────────────────────────────────────────────────────────

    fun toolDefs(): List<ToolRegistry.ToolDef> = listOf(
        ToolRegistry.ToolDef(
            name = GET_PAGE_INFO,
            description = "Get the current screen's UI hierarchy (node tree). Each node includes a path id (e.g. 0.1.2)," +
                "class, text, bounds (coordinate range), and clickable/editable/focused state." +
                "Used to learn which interactive elements the current page has and their coordinates. The accessibility service must be enabled first.",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = CLICK,
            description = "Tap at the specified screen coordinates. Coordinates are absolute screen pixels." +
                "It is recommended to first get the target element's bounds with ui_get_page_info and tap its centre point.",
            parameters = mapOf(
                "x" to "Required, the x coordinate to tap (screen pixels)",
                "y" to "Required, the y coordinate to tap (screen pixels)",
            ),
            required = setOf("x", "y"),
            category = "built-in",
            parameterTypes = mapOf("x" to "integer", "y" to "integer"),
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = LONG_PRESS,
            description = "Long-press at the specified screen coordinates (hold 500ms). Often used to pop up a context menu.",
            parameters = mapOf(
                "x" to "Required, the x coordinate to long-press",
                "y" to "Required, the y coordinate to long-press",
            ),
            required = setOf("x", "y"),
            category = "built-in",
            parameterTypes = mapOf("x" to "integer", "y" to "integer"),
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = SWIPE,
            description = "Perform a swipe gesture on the screen, from the start point to the end point; duration controls the speed (longer is slower)." +
                "Swipe up to turn a page: startY larger, endY smaller; the reverse for swiping down.",
            parameters = mapOf(
                "start_x" to "Required, start point x coordinate",
                "start_y" to "Required, start point y coordinate",
                "end_x" to "Required, end point x coordinate",
                "end_y" to "Required, end point y coordinate",
                "duration" to "Optional, swipe duration (milliseconds), default 300, larger is slower",
            ),
            required = setOf("start_x", "start_y", "end_x", "end_y"),
            category = "built-in",
            parameterTypes = mapOf(
                "start_x" to "integer",
                "start_y" to "integer",
                "end_x" to "integer",
                "end_y" to "integer",
                "duration" to "integer",
            ),
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = SET_TEXT,
            description = "Set text on the specified node (via ACTION_SET_TEXT)." +
                "node_id comes from the path id returned by ui_get_page_info (e.g. 0.1.2) or the focused node in ui_get_page_info." +
                "Used to fill content into input fields.",
            parameters = mapOf(
                "node_id" to "Required, target node path (the [path] from ui_get_page_info)",
                "text" to "Required, the text to input",
            ),
            required = setOf("node_id", "text"),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = SCREENSHOT,
            description = "Capture the current screen and save it as an image. Requires Android 14+ (API 34)." +
                "Returns the save path. When path is not given, it saves to the app cache directory.",
            parameters = mapOf(
                "path" to "Optional, save path (absolute path); default saves to the cache directory",
                "format" to "Optional, image format PNG (default) or JPEG",
            ),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = BACK,
            description = "Simulate pressing the Back key (global action GLOBAL_ACTION_BACK).",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = HOME,
            description = "Simulate pressing the Home key (return to the launcher, GLOBAL_ACTION_HOME).",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = GLOBAL_ACTION,
            description = "Execute a global accessibility action. Common action_id values: 1=BACK, 2=HOME, 3=RECENTS (recent tasks)," +
                " 4=NOTIFICATIONS, 5=QUICK_SETTINGS, 6=POWER_DIALOG, 7=TOGGLE_NOTIFICATION, 10=DISMISS_NOTIFICATION_SHADE。",
            parameters = mapOf(
                "action_id" to "Required, global action ID (e.g. 1=Back, 2=HOME)",
            ),
            required = setOf("action_id"),
            category = "built-in",
            parameterTypes = mapOf("action_id" to "integer"),
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = GET_CURRENT_APP,
            description = "Get the component name of the current foreground app/Activity (e.g. com.android.settings/.Settings)." +
                "Used to learn which app the user is currently in.",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
    )

    // ── 执行逻辑 ──────────────────────────────────────────────────────────────

    suspend fun getPageInfo(client: AccessibilityClient): String {
        if (!ensureConnected(client)) return "Error: accessibility service is not enabled"
        val info = client.getPageInfo()
        return info.ifBlank { "Error: unable to get the UI hierarchy (possibly no active window)" }
    }

    suspend fun click(client: AccessibilityClient, x: Int, y: Int): String {
        if (!ensureConnected(client)) return "Error: accessibility service is not enabled"
        val ok = client.click(x, y)
        return if (ok) "Success: tapped ($x, $y)" else "Error: tap did not complete (coordinates out of bounds or the gesture was cancelled)"
    }

    suspend fun longPress(client: AccessibilityClient, x: Int, y: Int): String {
        if (!ensureConnected(client)) return "Error: accessibility service is not enabled"
        val ok = client.longPress(x, y)
        return if (ok) "Success: long-pressed ($x, $y)" else "Error: long-press did not complete"
    }

    suspend fun swipe(client: AccessibilityClient, startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): String {
        if (!ensureConnected(client)) return "Error: accessibility service is not enabled"
        val ok = client.swipe(startX, startY, endX, endY, duration)
        return if (ok) "Success: swiped ($startX,$startY) -> ($endX,$endY)" else "Error: swipe did not complete"
    }

    suspend fun setText(client: AccessibilityClient, nodeId: String, text: String): String {
        if (!ensureConnected(client)) return "Error: accessibility service is not enabled"
        if (nodeId.isBlank()) return "Error: missing node_id parameter"
        val ok = client.setText(nodeId, text)
        return if (ok) "Success: text entered on node $nodeId" else "Error: setting text failed (node not editable or does not exist)"
    }

    suspend fun screenshot(client: AccessibilityClient, context: Context, path: String?, format: String): String {
        if (!ensureConnected(client)) return "Error: accessibility service is not enabled"
        val targetPath = path?.takeIf { it.isNotBlank() } ?: run {
            val ext = if (format.equals("JPEG", ignoreCase = true)) ".jpg" else ".png"
            File(context.cacheDir, "ui_screenshot_${System.currentTimeMillis()}$ext").absolutePath
        }
        val ok = client.screenshot(targetPath, format)
        if (ok) return "Success: screenshot saved: $targetPath"
        // v2.6.13: 区分“能力没拿到”与“拿到了但这次失败”，否则模型只能盲猜原因。
        val hint = if (!client.canTakeScreenshot()) {
            "The service has not obtained screenshot capability; turn Shaheen 2's accessibility service off and back on in system settings"
        } else {
            "Requires Android 14+, or the current page forbids screenshots (e.g. payment/password pages)"
        }
        return "Error: screenshot failed ($hint)"
    }

    suspend fun back(client: AccessibilityClient): String {
        if (!ensureConnected(client)) return "Error: accessibility service is not enabled"
        val ok = client.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        return if (ok) "Success: went back" else "Error: Back action failed"
    }

    suspend fun home(client: AccessibilityClient): String {
        if (!ensureConnected(client)) return "Error: accessibility service is not enabled"
        val ok = client.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        return if (ok) "Success: returned to the launcher" else "Error: HOME action failed"
    }

    suspend fun globalAction(client: AccessibilityClient, actionId: Int): String {
        if (!ensureConnected(client)) return "Error: accessibility service is not enabled"
        val ok = client.globalAction(actionId)
        return if (ok) "Success: global action $actionId executed" else "Error: global action $actionId failed"
    }

    suspend fun getCurrentApp(client: AccessibilityClient): String {
        if (!ensureConnected(client)) return "Error: accessibility service is not enabled"
        val name = client.currentActivityName()
        return name.ifBlank { "Note: cannot determine the foreground app right now" }
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    private suspend fun ensureConnected(client: AccessibilityClient): Boolean {
        // P3-3: 静态实例方案下,连接由系统管理 — 服务启用后 instance 自动可用,
        // app 无法主动 bindService(原 AIDL 方案的 connect() 已移除)。
        // 这里仅做状态检查:已连接则放行,否则让调用方返回"未启用"错误。
        return client.isConnected()
    }
}

/**
 * UI 工具注册器 — 把 10 个 UI 工具注册到 [ToolRegistry]。
 *
 * 依赖 ToolRegistry + AccessibilityClient + Context。
 * 在 AppKoinModule 初始化时创建(无障碍服务启用后工具才可用,但注册始终进行,
 * 执行时由 [UIToolsSkill.ensureConnected] 检查连接状态)。
 */
class UIToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val accessibilityClient: AccessibilityClient,
    private val context: Context,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        val client = accessibilityClient
        // 工具名 -> 执行 lambda 的映射,统一参数解析
        val executors: List<Pair<ToolRegistry.ToolDef, suspend (Map<String, String>) -> String>> = listOf(
            UIToolsSkill.toolDefs()[0] to { UIToolsSkill.getPageInfo(client) },
            UIToolsSkill.toolDefs()[1] to { args ->
                val x = args["x"]?.toIntOrNull() ?: return@to "Error: missing or invalid x parameter"
                val y = args["y"]?.toIntOrNull() ?: return@to "Error: missing or invalid y parameter"
                UIToolsSkill.click(client, x, y)
            },
            UIToolsSkill.toolDefs()[2] to { args ->
                val x = args["x"]?.toIntOrNull() ?: return@to "Error: missing or invalid x parameter"
                val y = args["y"]?.toIntOrNull() ?: return@to "Error: missing or invalid y parameter"
                UIToolsSkill.longPress(client, x, y)
            },
            UIToolsSkill.toolDefs()[3] to { args ->
                val sx = args["start_x"]?.toIntOrNull() ?: return@to "Error: missing start_x"
                val sy = args["start_y"]?.toIntOrNull() ?: return@to "Error: missing start_y"
                val ex = args["end_x"]?.toIntOrNull() ?: return@to "Error: missing end_x"
                val ey = args["end_y"]?.toIntOrNull() ?: return@to "Error: missing end_y"
                val dur = args["duration"]?.toLongOrNull() ?: 300L
                UIToolsSkill.swipe(client, sx, sy, ex, ey, dur)
            },
            UIToolsSkill.toolDefs()[4] to { args ->
                val nodeId = args["node_id"] ?: return@to "Error: missing node_id"
                val text = args["text"] ?: return@to "Error: missing text"
                UIToolsSkill.setText(client, nodeId, text)
            },
            UIToolsSkill.toolDefs()[5] to { args ->
                val path = args["path"]
                val format = args["format"]?.takeIf { it.isNotBlank() } ?: "PNG"
                UIToolsSkill.screenshot(client, context, path, format)
            },
            UIToolsSkill.toolDefs()[6] to { UIToolsSkill.back(client) },
            UIToolsSkill.toolDefs()[7] to { UIToolsSkill.home(client) },
            UIToolsSkill.toolDefs()[8] to { args ->
                val actionId = args["action_id"]?.toIntOrNull() ?: return@to "Error: missing or invalid action_id"
                UIToolsSkill.globalAction(client, actionId)
            },
            UIToolsSkill.toolDefs()[9] to { UIToolsSkill.getCurrentApp(client) },
        )
        executors.forEach { (def, fn) ->
            toolRegistry.register(def, fn)
        }
        Logger.i("UIToolsRegistrar", "Registered ${executors.size} UI tools")
    }
}
