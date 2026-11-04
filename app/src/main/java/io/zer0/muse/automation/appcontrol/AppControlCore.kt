package io.zer0.muse.automation.appcontrol

/**
 * 设备应用控制 —— 纯逻辑核心（不含 Android 类型，便于单测）。
 *
 * ## 为什么单独抽一层
 *
 * "模型能对应用做什么"取决于当前授权通道：
 *  - **列出应用 / 启动 / 打开系统设置页**：只要有 Context 即可（应用自身权限，无需任何自动化授权）；
 *  - **强行停止 / 清空数据 / 卸载 / 改组件状态**：必须走 Shell(Shizuku·adb) 或 Root。
 *
 * 把这套决策放在纯 Kotlin 里，就能用单测锁死"缺权限时必须如实说明需要哪一层"，
 * 而不是让工具层各自判断、各自给出含糊失败。
 */

/** 一个已安装（可启动）应用的骨架信息。 */
data class InstalledAppInfo(
    /** 包名，唯一标识。 */
    val packageName: String,
    /** 用户可见名称（来自 PackageManager，已做 trim）。 */
    val label: String,
    /** 版本名，未知为空串。 */
    val versionName: String,
    /** 是否来自系统分区（卸载/清数据对系统应用通常是破坏性的）。 */
    val system: Boolean,
    /** 是否为 Muse 自身。 */
    val self: Boolean,
)

/** 应用控制动作。 */
enum class AppControlAction {
    /** 启动应用（前台）。 */
    LAUNCH,

    /** 强行停止应用。需要 Shell/Root。 */
    FORCE_STOP,

    /** 清空应用数据。需要 Shell/Root，且破坏性最强。 */
    CLEAR_DATA,

    /** 卸载应用。需要 Shell/Root（普通卸载会弹系统确认，走 Shell 才是静默的）。 */
    UNINSTALL,

    /** 打开该应用在系统设置里的详情页（无需自动化授权）。 */
    OPEN_APP_SETTINGS,
}

/** 一次应用控制请求的结果（供工具层翻译成人话）。 */
sealed interface AppActionResult {
    /** 执行成功（[via] 记录实际走的通道，供日志与口径核对）。 */
    data class Success(val via: ExecutionChannel) : AppActionResult

    /**
     * 该动作当前**不可能**完成（缺通道 / 目标不允许 / 包名非法）。
     * [action] 指出需要哪一种能力，模型据此向用户说明或改用别的方案。
     */
    data class Unavailable(val reason: UnavailableReason) : AppActionResult

    /** 已尝试执行但失败（通道在、命令失败）。 */
    data class Failed(val message: String) : AppActionResult
}

/** 执行通道。 */
enum class ExecutionChannel {
    /** 应用自身权限（PackageManager / Intent），不需要任何自动化授权。 */
    APP,

    /** Shizuku / adb shell。 */
    SHELL,

    /** Root(su)。 */
    ROOT,
}

/** 「做不到」的具体原因。 */
enum class UnavailableReason {
    /** 包名不合法（防注入：只允许 `[A-Za-z0-9_.]`）。 */
    INVALID_PACKAGE_NAME,

    /** 设备上找不到该包（或受 Android 11+ 包可见性限制看不见）。 */
    PACKAGE_NOT_FOUND,

    /** 需要 Shell 或 Root，但两者都没有。 */
    NEED_SHELL_OR_ROOT,

    /** 该动作不允许作用于系统应用。 */
    BLOCKED_SYSTEM_APP,

    /** 该动作不允许作用于 Muse 自身。 */
    BLOCKED_SELF,
}

/**
 * 当前可用通道（由装配方探测后传入，保持本层无 Android 依赖）。
 *
 * @property shell Shizuku/adb 是否就绪
 * @property root su 是否可用
 */
data class ChannelAvailability(val shell: Boolean, val root: Boolean) {
    val anyElevated: Boolean get() = shell || root

    /** 选中用于设备命令的通道：Shell 优先、Root 兜底（与 `AutomationManager.execTiered` 同口径）。 */
    fun preferredElevated(): ExecutionChannel = if (shell) ExecutionChannel.SHELL else ExecutionChannel.ROOT
}

/**
 * 应用控制的核心决策。
 *
 * 只做三件事：**校验包名 → 判断该动作需要哪条通道 → 给出可判定结果**。
 * 真正的执行由 [AndroidAppController]（应用层动作）与 `AutomationManager`（设备命令）承担。
 */
object AppControlCore {

    /**
     * 包名白名单：Android 允许字母、数字、下划线、点，且至少两段。
     *
     * 收紧到"仅这些字符"是为了**防命令注入**——包名最终会拼进 shell 命令
     * （`am force-stop <pkg>`），任何空格/分号/引号都必须在这里被挡住。
     */
    private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    /** 包名是否合法（可直接用于拼命令）。 */
    fun isValidPackageName(packageName: String): Boolean = PACKAGE_NAME.matches(packageName)

    /**
     * 判断某个动作此刻能否执行，以及需要哪条通道。
     *
     * @param target 目标应用（已知时传入；null 表示仅做"包名是否合法"的静态判定）
     */
    fun evaluate(
        action: AppControlAction,
        packageName: String,
        channels: ChannelAvailability,
        target: InstalledAppInfo? = null,
    ): AppActionResult {
        val result = when {
            // 包名最终会拼进 shell 命令，非法即拒（防注入）
            !isValidPackageName(packageName) ->
                AppActionResult.Unavailable(UnavailableReason.INVALID_PACKAGE_NAME)

            // 应用层动作与设备命令的分界：只有需要越过应用沙盒的动作才要 Shell/Root
            action == AppControlAction.LAUNCH || action == AppControlAction.OPEN_APP_SETTINGS ->
                AppActionResult.Success(ExecutionChannel.APP)

            // 让模型去卸载/清空 Muse 自己是没有意义的破坏性操作，直接挡住
            target?.self == true ->
                AppActionResult.Unavailable(UnavailableReason.BLOCKED_SELF)

            // 系统应用清数据/卸载基本等于把手机弄坏；强停仍是安全的可逆操作
            target?.system == true && action != AppControlAction.FORCE_STOP ->
                AppActionResult.Unavailable(UnavailableReason.BLOCKED_SYSTEM_APP)

            !channels.anyElevated ->
                AppActionResult.Unavailable(UnavailableReason.NEED_SHELL_OR_ROOT)

            else -> AppActionResult.Success(channels.preferredElevated())
        }
        return result
    }

    /** 需要 Shell/Root 的动作集合（工具层据此决定是否先探测通道）。 */
    fun actionsNeedingElevation(): Set<AppControlAction> = setOf(
        AppControlAction.FORCE_STOP,
        AppControlAction.CLEAR_DATA,
        AppControlAction.UNINSTALL,
    )

    /**
     * 构造设备命令。
     *
     * 只在这里拼包名——已过 [isValidPackageName] 白名单，返回值可直接交给 `execTiered`。
     * 新增动作必须同时在这里登记，避免在工具层散落拼串。
     */
    fun buildCommand(action: AppControlAction, packageName: String): String? = when (action) {
        AppControlAction.FORCE_STOP -> "am force-stop $packageName"
        AppControlAction.CLEAR_DATA -> "pm clear $packageName"
        AppControlAction.UNINSTALL -> "pm uninstall $packageName"
        AppControlAction.LAUNCH, AppControlAction.OPEN_APP_SETTINGS -> null
    }

    /**
     * 在候选列表里按"包名精确 → 包名忽略大小写 → 名称包含"逐级匹配。
     *
     * 模型给用户的往往是"微信"而不是 `com.tencent.mm`，所以名称匹配是必要能力；
     * 但顺序必须是确定性降级，避免同音应用被随机选中。
     */
    fun matchApp(apps: List<InstalledAppInfo>, query: String): InstalledAppInfo? {
        val q = query.trim()
        return if (q.isEmpty()) {
            null
        } else {
            apps.firstOrNull { it.packageName == q }
                ?: apps.firstOrNull { it.packageName.equals(q, ignoreCase = true) }
                ?: apps.firstOrNull { it.label.equals(q, ignoreCase = true) }
                ?: apps.firstOrNull { it.label.contains(q, ignoreCase = true) }
        }
    }

    /** 渲染应用列表（给模型看；按名称排序，标注系统应用与自身）。 */
    fun renderAppList(apps: List<InstalledAppInfo>, limit: Int = 200): String {
        if (apps.isEmpty()) return "No launchable app found (or listing is limited by system package visibility)."
        val sorted = apps.sortedBy { it.label.lowercase() }
        val shown = sorted.take(limit)
        return buildString {
            appendLine("${sorted.size} launchable apps in total${if (sorted.size > shown.size) " (only the first ${shown.size} listed)" else ""}:")
            shown.forEach { app ->
                append("- ${app.label} | ${app.packageName}")
                if (app.system) append(" | System app")
                if (app.self) append(" | This app")
                appendLine()
            }
        }.trimEnd()
    }
}
