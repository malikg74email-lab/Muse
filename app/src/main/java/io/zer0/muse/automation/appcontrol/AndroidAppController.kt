package io.zer0.muse.automation.appcontrol

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutomationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用控制的 Android 实现：把 [AppControlCore] 的决策落到 PackageManager / Intent / 设备命令上。
 *
 * 分工：
 *  - 需要越过应用沙盒的动作（强停 / 清数据 / 卸载）→ `AutomationManager.execTiered`
 *    （Shizuku 优先、Root 兜底，与设备命令同一条路径，不在工具层拼 shell）；
 *  - 其余动作（列出 / 启动 / 打开设置页）→ PackageManager 与 Intent，**不需要任何自动化授权**，
 *    这也是"用户不装任何额外能力时 Agent 仍能干活"的部分。
 */
class AndroidAppController(
    private val context: Context,
    private val manager: AutomationManager,
) {
    /**
     * 列出可启动应用。
     *
     * 用 `queryIntentActivities(MAIN/LAUNCHER)` 而不是 `getInstalledApplications`：
     * 前者给出的是**用户真正能打开的应用**（正是 Agent 需要的集合），且不依赖
     * Android 11+ 的包可见性豁免；后者在未声明 `QUERY_ALL_PACKAGES` 时会被系统阉割。
     */
    suspend fun listLaunchableApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            // 两个重载的返回类型无法在同一个 if 表达式里统一（Kotlin 会推成 Any），
            // 因此分支各自完成查询后再合并。
            val resolved: List<android.content.pm.ResolveInfo> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(
                        intent,
                        android.content.pm.PackageManager.ResolveInfoFlags.of(0L),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
            resolved.mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val appInfo = runCatching { info.activityInfo.applicationInfo }.getOrNull()
                InstalledAppInfo(
                    packageName = pkg,
                    label = runCatching { pm.getApplicationLabel(appInfo!!).toString().trim() }
                        .getOrDefault(pkg),
                    versionName = runCatching {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(pkg, 0).versionName.orEmpty()
                    }.getOrDefault(""),
                    system = (appInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
                    self = pkg == context.packageName,
                )
            }.distinctBy { it.packageName }
        }.onFailure { Logger.w(TAG, "Failed to list apps: ${it.message}") }.getOrDefault(emptyList())
    }

    /** 按包名或用户可见名称定位应用。 */
    suspend fun findApp(query: String): InstalledAppInfo? = AppControlCore.matchApp(listLaunchableApps(), query)

    /** 当前通道可用性（探测失败一律视为不可用，不谎报能力）。 */
    suspend fun channels(): ChannelAvailability = runCatching {
        val state = manager.permissionState.value.let { cached ->
            if (cached.shellEnabled || cached.rootEnabled) {
                cached
            } else {
                manager.refreshPermissions()
            }
        }
        ChannelAvailability(shell = state.shellEnabled, root = state.rootEnabled)
    }.getOrElse {
        Logger.w(TAG, "Channel probe failed: ${it.message}")
        ChannelAvailability(shell = false, root = false)
    }

    /**
     * 执行一次应用控制动作。
     *
     * @param query 包名或应用名
     */
    suspend fun perform(action: AppControlAction, query: String): AppActionResult {
        val channels = channels()
        // 先按包名原样判定；不是合法包名时再尝试按名称解析（模型常给中文名）。
        val direct = if (AppControlCore.isValidPackageName(query)) query else null
        val target = if (direct != null) {
            listLaunchableApps().firstOrNull { it.packageName == direct }
        } else {
            findApp(query)
        }
        val packageName = direct ?: target?.packageName
        if (packageName == null) {
            return AppActionResult.Unavailable(UnavailableReason.PACKAGE_NOT_FOUND)
        }

        // evaluate 不通过时原样返回（含"缺通道"与"系统应用/自身拦截"等可解释原因）；
        // 通过后 local 变量已非空，可直接进动作分发。
        val decision = AppControlCore.evaluate(action, packageName, channels, target)
        return if (decision !is AppActionResult.Success) {
            decision
        } else {
            when (action) {
                AppControlAction.LAUNCH -> launchViaIntent(packageName)
                AppControlAction.OPEN_APP_SETTINGS -> openAppSettings(packageName)
                AppControlAction.FORCE_STOP,
                AppControlAction.CLEAR_DATA,
                AppControlAction.UNINSTALL,
                -> runDeviceCommand(action, packageName)
            }
        }
    }

    /** 启动应用：优先应用自身 Intent（无需授权），失败再退到无障碍/通道。 */
    private suspend fun launchViaIntent(packageName: String): AppActionResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            val ok = runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }.onFailure { Logger.w(TAG, "Intent launch failed: ${it.message}") }.getOrDefault(false)
            if (ok) return AppActionResult.Success(ExecutionChannel.APP)
        }
        // 无启动 Intent（如被隐藏的应用）→ 交给分层通道
        return if (manager.launchApp(packageName)) {
            AppActionResult.Success(ExecutionChannel.APP)
        } else {
            AppActionResult.Failed("Could not launch $packageName (no launch entry and the automation channel is unavailable)")
        }
    }

    /** 打开该应用的系统设置详情页（无需自动化授权，用户可自行改权限/清数据）。 */
    private suspend fun openAppSettings(packageName: String): AppActionResult {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }
            .map { AppActionResult.Success(ExecutionChannel.APP) }
            .getOrElse {
                Logger.w(TAG, "Failed to open the app settings page: ${it.message}")
                AppActionResult.Failed("Could not open the settings page of $packageName")
            }
    }

    /** 越过应用沙盒的动作：统一走 execTiered（Shizuku 优先、Root 兜底）。 */
    private suspend fun runDeviceCommand(action: AppControlAction, packageName: String): AppActionResult {
        val command = AppControlCore.buildCommand(action, packageName)
        val executed = command?.let { manager.execTiered(it) }
        return when {
            command == null -> AppActionResult.Failed("Internal error: action $action has no registered device command")
            // 两档通道都不可用：如实回报缺少能力，让模型向用户说明
            executed == null -> AppActionResult.Unavailable(UnavailableReason.NEED_SHELL_OR_ROOT)
            else -> {
                val (channelName, detail) = executed
                val via = if (channelName == "Root") ExecutionChannel.ROOT else ExecutionChannel.SHELL
                // ExecDetail.output 是 stdout+stderr 合并文本（没有 isSuccess()，以 exitCode 为准）；
                // pm clear / pm uninstall 成功回 "Success"，am force-stop 成功无输出。
                val ok = detail.exitCode == 0 || detail.output.contains("Success", ignoreCase = true)
                if (ok) {
                    AppActionResult.Success(via)
                } else {
                    AppActionResult.Failed(detail.output.ifBlank { "Command failed (exit code ${detail.exitCode})" })
                }
            }
        }
    }

    private companion object {
        const val TAG = "AndroidAppController"
    }
}
