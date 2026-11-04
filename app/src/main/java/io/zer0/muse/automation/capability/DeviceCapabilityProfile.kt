package io.zer0.muse.automation.capability

import android.os.Build
import io.zer0.muse.automation.core.PermissionLevel

/**
 * 设备能力档案 —— 「这台手机上 Agent 到底能做到什么」的唯一事实来源。
 *
 * ## 为什么需要它
 *
 * 三层控制通道（无障碍 / Shizuku·adb / Root）各自只覆盖一部分能力，且高度依赖用户
 * 授权状态。此前模型只能靠试错：先调一次工具、失败了再换一个，既浪费轮次，也容易把
 * "没有权限"误报成"功能不存在"。
 *
 * 档案把可用通道 + 关键能力边界一次性交给模型，让它在第一步就能选出可行方案。
 *
 * ## 能力边界（与实现对齐，勿凭印象改）
 *
 * - **无障碍**：读控件树 + dispatchGesture 手势注入 + `inputText`/`pressKey`。
 *   **不能**截屏，**不能**操作系统界面（状态栏/通知栏/关机菜单等 system uid 窗口），
 *   **不能**安装应用或改系统设置。
 * - **Shizuku / adb shell**：`input`、`screencap`、`pm`/`am`、`settings`、`content` 等
 *   shell 命令；可截屏、可操作系统界面、可静默安装。
 * - **Root**：以上全部 + 直接读写系统分区/数据库。
 * - **虚拟屏**：把应用启动到独立 display 上，不干扰用户当前前台（多步骤自动化首选）。
 * - **内置 Node**：数据处理/批处理/脚本/MCP stdio server，不消耗模型 token。
 *
 * @property accessibility 无障碍服务是否已连接
 * @property shizuku 是否已获得 shell 级（Shizuku/adb）授权
 * @property root 是否已获得 su
 * @property virtualDisplay 虚拟屏服务端是否可用
 * @property nodeRuntime 内置 Node 运行时是否就绪
 * @property sandboxAvailable 预留：应用内沙盒文件执行是否可用
 * @property details 面向日志/设置页的可读明细（不直接进提示词）
 */
data class DeviceCapabilityProfile(
    val accessibility: Boolean = false,
    val shizuku: Boolean = false,
    val root: Boolean = false,
    val virtualDisplay: Boolean = false,
    val nodeRuntime: Boolean = false,
    val sandboxAvailable: Boolean = false,
    val details: Map<String, String> = emptyMap(),
) {
    /** 最高可用层级（无任何通道时为 [PermissionLevel.NONE]）。 */
    val highestLevel: PermissionLevel
        get() = when {
            root -> PermissionLevel.ROOT
            shizuku -> PermissionLevel.SHELL
            accessibility -> PermissionLevel.ACCESSIBILITY
            else -> PermissionLevel.NONE
        }

    /** 是否存在任何能"操作手机"的通道（Node/虚拟屏单独不算控制通道）。 */
    val hasControlChannel: Boolean
        get() = accessibility || shizuku || root

    /** Android 14+ 无障碍服务可直接调用 AccessibilityService.takeScreenshot。 */
    val accessibilityScreenshot: Boolean
        get() = accessibility && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** 能否截屏（Shell/Root 或 Android 14+ 无障碍均可）。 */
    val canScreenshot: Boolean
        get() = shizuku || root || accessibilityScreenshot

    /**
     * 渲染成给模型看的紧凑档案块。
     *
     * 保持短小：进入 system prompt 的每一行都在花用户的钱，只写"影响决策"的事实。
     */
    fun renderForModel(): String = buildString {
        appendLine("## Device capability profile (current real availability)")
        appendLine(
            "- Accessibility (screen reading/gestures/input): " +
                if (accessibility) "Available" else "Unavailable (the Shaheen 2 accessibility service is not enabled)",
        )
        appendLine(
            "- Shell channel (Shizuku/adb: input/screencap/pm/am/settings): " +
                if (shizuku) "Available" else "Unavailable (Shizuku not installed or not authorised)",
        )
        appendLine(
            "- Root (su: system partition read/write/full control): " +
                if (root) "Available" else "Unavailable",
        )
        appendLine(
            "- Screenshot capability: " +
                if (canScreenshot) {
                    if (accessibilityScreenshot && !shizuku && !root) {
                        "Available (via Android 14+ accessibility screenshots)"
                    } else {
                        "Available (via Shell/Root)"
                    }
                } else {
                    "Unavailable (requires Android 14+ accessibility, Shizuku/adb or Root)"
                },
        )
        appendLine(
            "- Virtual screen (launch apps onto a separate screen without disturbing the user's current interface): " +
                if (virtualDisplay) "Available" else "Unavailable",
        )
        appendLine(
            "- Built-in Node runtime (run scripts/data processing/MCP stdio): " +
                if (nodeRuntime) "Available" else "Unavailable (runtime not ready)",
        )
        append(
            "- Current highest permission tier: " + when (highestLevel) {
                PermissionLevel.ROOT -> "Root"
                PermissionLevel.SHELL -> "Shell(Shizuku/adb)"
                PermissionLevel.ACCESSIBILITY -> "Accessibility"
                PermissionLevel.NONE -> "None (can only operate inside the app; cannot control other apps)"
            },
        )
    }

    /**
     * 渲染操作决策规程：把"先探测、再选层、后验证"的既定做法写进提示词。
     *
     * 这些不是建议而是硬约束（每条都对应一次真实踩坑），因此与能力档案一起注入。
     */
    fun renderOperatingGuide(): String = buildString {
        appendLine("## Decision procedure for operating the phone")
        appendLine("1. Confirm capabilities before acting: the table above lists the **current** real availability; do not keep retrying a channel marked 'unavailable' — switch approach or tell the user which permission is missing.")
        appendLine("2. Choose the channel by the lowest sufficient tier:")
        appendLine("   - App layer (**needs no automation authorisation at all**, always try this layer first): `app_list` to look up names and package names, `app_launch` to launch an app, `app_settings` to open an app's system settings page (letting the user change permissions/clear data themselves).")
        appendLine("   - Interface interaction: prefer accessibility (can read the control tree and inject gestures); more reliable than blind coordinate taps.")
        appendLine("   - Only when you need screenshots, system UI, app installation, system-settings changes, or force-stop/clear-data/uninstall, use Shell/Root (`app_force_stop`/`app_clear_data`/`app_uninstall`/`device_shell`).")
        appendLine("3. Verify every step: after issuing an action, read the screen/state once more to confirm the result (whether the text changed, whether the page switched); do not fire several blind operations in a row.")
        appendLine("4. When the interface fails, look at the structure before switching means: first locate the control from the screen-reading result (text/description/hierarchy); if not found, consider coordinate taps; when coordinates are also unreliable, switch to a Shell/Root command-level approach.")
        appendLine("5. Prefer scripts for repetitive/long processes: use the built-in Node for loops, JSON/regex processing and batch requests; cheaper in time and tokens than step-by-step reasoning; once stable, settle it into a skill.")
        appendLine(
            "6. Do not disturb the user: when calling `ui_agent`, prefer display=auto and provide package_name;" +
                "it will try to create a dedicated virtual screen for this run, and clearly degrade to the foreground when Shell/Root or the app does not support it." +
                "Specify display=virtual only when the capability profile shows the virtual screen is available; do not assume the compatible virtual screen and the Agent's separate screen are the same screen.",
        )
        appendLine("7. For high-risk actions (sending messages, paying, deleting, changing system settings), first explain what you are about to do, and execute only after the user confirms.")
        appendLine("8. When a permission is missing, say so truthfully: state 'which one is needed' (e.g. Shizuku/Root/accessibility); do not substitute simulated results for real execution, and do not falsely report success.")
        appendLine(
            "9. Check the map before acting: before operating the device (tapping/swiping/reading the screen/starting or stopping apps/shell), first call `tool_status`\n" +
                "   to see what is really available. It divides tools into available and permission-blocked groups and tells you what is missing.\n" +
                "   Do not promise 'I'll tap it for you' first and then hit a wall — that hurts more than saying at the start that it cannot be done.",
        )
    }

    companion object {
        /**
         * 从三层授权标志构造（最常用入口）。
         *
         * 参数多于 detekt 默认阈值（6）是刻意的：这些标志彼此独立、都要显式给出，
         * 用默认值会让"忘了传某一层"变成静默的假能力声明。调用方一律具名传参。
         */
        @Suppress("LongParameterList")
        fun fromFlags(
            accessibility: Boolean,
            shizuku: Boolean,
            root: Boolean,
            virtualDisplay: Boolean = false,
            nodeRuntime: Boolean = false,
            sandboxAvailable: Boolean = false,
            details: Map<String, String> = emptyMap(),
        ): DeviceCapabilityProfile = DeviceCapabilityProfile(
            accessibility = accessibility,
            shizuku = shizuku,
            root = root,
            virtualDisplay = virtualDisplay,
            nodeRuntime = nodeRuntime,
            sandboxAvailable = sandboxAvailable,
            details = details,
        )
    }
}
