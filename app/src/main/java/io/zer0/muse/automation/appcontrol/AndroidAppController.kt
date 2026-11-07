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
                AppControlAction.LAUNCH -> launchAndVerify(packageName)
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

    /**
     * 轮询前台包名（[OpenRetryPolicy.VERIFY_WAITS_MS] 梯度），返回最后观测值。
     *
     * 观测不到（所有通道都读不出）返回 null。Phase 1 的 launch 验证与
     * Deep Links 的落点验证共用这一份，避免两套口径。
     */
    private suspend fun pollForeground(packageName: String): String? {
        var lastObserved: String? = null
        for (waitMs in OpenRetryPolicy.VERIFY_WAITS_MS) {
            kotlinx.coroutines.delay(waitMs)
            lastObserved = runCatching { manager.currentPackage() }
                .onFailure { Logger.w(TAG, "Foreground check failed: ${it.message}") }
                .getOrNull() ?: lastObserved
            if (lastObserved == packageName) break
        }
        return lastObserved
    }

    /**
     * Shaheen 2 Fix Phase 1 (§5 Fix 1 item 6): launch ke baad foreground ki tasdeeq.
     *
     * Pehle `startActivity()` ka nah phatna hi "Success" tha — app asal mein
     * samne aye ya na aye, report kamyabi ki banti thi (Malik: "jhoot bolta hai").
     * Ab launch ke baad maujooda channels (accessibility/Shell/Root) se foreground
     * package parha jata hai:
     *  - target hi foreground → verified Success;
     *  - foreground koi aur package → Failed (plain alfaz mein, kamyabi ka daawa nahi);
     *  - koi channel foreground dekh hi na sake → Success lekin
     *    `verifiedForeground=false`, taake tool result khud kahe ke tasdeeq nahi hui.
     *
     * Shaheen 2 Deep Links & Shortcuts（architecture §6a）：目标没进前台时
     * 按 [OpenRetryPolicy] 有界重试（总共至多 [OpenRetryPolicy.MAX_ATTEMPTS]
     * 次），实际次数写进 [AppActionResult.Success.attempts] / 失败文案；
     * 观测不到前台时不盲重试（重试也验证不了），直接按未证实成功返回。
     */
    private suspend fun launchAndVerify(packageName: String): AppActionResult {
        var attempts = 0
        var lastObserved: String? = null
        var lastVia = ExecutionChannel.APP
        while (attempts < OpenRetryPolicy.MAX_ATTEMPTS) {
            attempts++
            val launched = launchViaIntent(packageName)
            if (launched !is AppActionResult.Success) return launched
            lastVia = launched.via
            lastObserved = pollForeground(packageName) ?: lastObserved
            when (val verdict = AppControlCore.foregroundVerdict(packageName, lastObserved)) {
                true -> return AppActionResult.Success(lastVia, verifiedForeground = true, attempts = attempts)
                false -> return AppActionResult.Success(lastVia, verifiedForeground = false, attempts = attempts)
                else -> {
                    // 前台是别的包：慢启动可能还没切过来——次数没用完就再启一次
                    if (!OpenRetryPolicy.shouldRetry(attempts, landed = false)) break
                    Logger.i(TAG, "Launch attempt $attempts: foreground is $lastObserved, retrying $packageName")
                }
            }
        }
        return AppActionResult.Failed(
            "Launch was issued $attempts time(s), but $packageName did not come to the foreground " +
                "(foreground is ${lastObserved ?: "unknown"}). Do not report it as opened.",
        )
    }

    /** VIEW 意图的发射结果。 */
    private enum class FireResult { FIRED, REJECTED, FAILED }

    /** 发射一个 VIEW 意图（可定向到包）；REJECTED = 没有任何组件接这个链接。 */
    private fun fireViewIntent(uri: String, packageName: String?): FireResult {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (packageName != null) setPackage(packageName)
        }
        return try {
            context.startActivity(intent)
            FireResult.FIRED
        } catch (e: android.content.ActivityNotFoundException) {
            FireResult.REJECTED
        } catch (e: Exception) {
            Logger.w(TAG, "Deep link fire failed ($uri): ${e.message}")
            FireResult.FAILED
        }
    }

    /** 落地后期望文本检查：无 hint 返回 null（无从校验）；读屏失败也返回 null。 */
    private suspend fun screenHintMatched(hint: String?): Boolean? {
        if (hint.isNullOrBlank()) return null
        return runCatching { manager.readScreen().findBestTextNode(hint) != null }
            .onFailure { Logger.w(TAG, "Screen hint check failed: ${it.message}") }
            .getOrNull()
    }

    /**
     * Shaheen 2 Deep Links & Shortcuts：按注册表顺序打开一个 deep-link 目标。
     *
     * 顺序（architecture §3 / §5）：**deep link 先行 → 验证落点 → 失败才
     * fallback 到普通启动 + 无障碍导航提示**，每一步如实记账：
     *  1. 定向到目标包的链接，按 [OpenRetryPolicy] 有界重试；
     *  2. 不定向的网页链接试一次（应用可能不靠定向也会拦截，或浏览器接住）；
     *  3. 仍未落进目标应用 → [perform] 普通启动，附导航提示；
     *  通用链接（无目标包）只走第 2 步，落到哪算哪，如实报告。
     */
    suspend fun openDeepLink(target: DeepLinkTarget): DeepLinkOutcome {
        val pkg = target.packageName
        var attempts = 0
        var linkLandedIn: String? = null

        if (pkg != null) {
            // ── 第 1 步：定向 deep link ─────────────────────────────
            while (attempts < OpenRetryPolicy.MAX_ATTEMPTS) {
                attempts++
                when (fireViewIntent(target.uri, pkg)) {
                    FireResult.REJECTED -> break // 目标应用没装/不接此链接 → 直接第 2 步
                    FireResult.FAILED -> {
                        if (!OpenRetryPolicy.shouldRetry(attempts, landed = false)) break
                    }
                    FireResult.FIRED -> {
                        val observed = pollForeground(pkg)
                        when {
                            observed == pkg -> return DeepLinkOutcome.Landed(
                                via = DeepLinkOutcome.Via.DEEP_LINK,
                                attempts = attempts,
                                hintMatched = screenHintMatched(target.screenHint),
                            )
                            observed == null -> return DeepLinkOutcome.LandedUnverified(
                                via = DeepLinkOutcome.Via.DEEP_LINK,
                                attempts = attempts,
                            )
                            else -> {
                                linkLandedIn = observed
                                if (!OpenRetryPolicy.shouldRetry(attempts, landed = false)) break
                            }
                        }
                    }
                }
            }

            // ── 第 2 步：不定向网页链接 ─────────────────────────────
            attempts++
            when (fireViewIntent(target.uri, null)) {
                FireResult.REJECTED -> Unit // 没人接 → 第 3 步
                FireResult.FAILED -> Unit
                FireResult.FIRED -> {
                    val observed = pollForeground(pkg)
                    when {
                        observed == pkg -> return DeepLinkOutcome.Landed(
                            via = DeepLinkOutcome.Via.WEB_LINK,
                            attempts = attempts,
                            hintMatched = screenHintMatched(target.screenHint),
                        )
                        observed == null -> return DeepLinkOutcome.LandedUnverified(
                            via = DeepLinkOutcome.Via.WEB_LINK,
                            attempts = attempts,
                        )
                        else -> linkLandedIn = observed
                    }
                }
            }

            // ── 第 3 步：退回普通启动 + 导航提示 ─────────────────────
            val launch = perform(AppControlAction.LAUNCH, pkg)
            if (launch is AppActionResult.Unavailable &&
                launch.reason == UnavailableReason.PACKAGE_NOT_FOUND
            ) {
                return DeepLinkOutcome.Failed(
                    attempts = attempts,
                    reason = "${target.appLabel} ($pkg) is not installed on this device.",
                )
            }
            return DeepLinkOutcome.FellBackToAppLaunch(
                attempts = attempts,
                linkLandedIn = linkLandedIn,
                launch = launch,
                navHint = target.fallbackNavHint,
            )
        }

        // ── 通用链接：无目标包，只发一次不定向链接 ───────────────────
        attempts++
        return when (fireViewIntent(target.uri, null)) {
            FireResult.REJECTED -> DeepLinkOutcome.Failed(
                attempts = attempts,
                reason = "no app or browser on this device could open this link.",
            )
            FireResult.FAILED -> DeepLinkOutcome.Failed(
                attempts = attempts,
                reason = "the system refused to open this link.",
            )
            FireResult.FIRED -> {
                // 没有目标包可等，只能给系统一点时间再读一次前台，如实报告落点
                kotlinx.coroutines.delay(600L)
                val first = runCatching { manager.currentPackage() }.getOrNull()
                kotlinx.coroutines.delay(600L)
                val second = runCatching { manager.currentPackage() }.getOrNull()
                DeepLinkOutcome.OpenedUnpinned(
                    foregroundPackage = second ?: first,
                    attempts = attempts,
                )
            }
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
