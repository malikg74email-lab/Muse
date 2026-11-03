package io.zer0.muse.update

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * 应用更新通知器 — 协调 [UpdateChecker] 与 [SettingsRepository]/[MuseNotificationManager]。
 *
 * 行为:
 *  - 从 [SettingsRepository.lastUpdateCheckTimeFlow] 读上次检查时间,间隔不足 24 小时则跳过
 *    (forceCheck=true 时强制)
 *  - 调用 [UpdateChecker.checkLatestRelease] 拉取最新 Release
 *  - 与当前 versionName 语义比较,发现新版本时:
 *      · 把 [UpdateChecker.ReleaseInfo] 序列化为 JSON 缓存到
 *        [SettingsRepository.latestReleaseInfoFlow](供 UI Banner 读取)
 *      · 通过 [MuseNotificationManager] 已有渠道发通知(复用 CHANNEL_CHAT_COMPLETED)
 *  - 检查完毕更新 lastUpdateCheckTime
 *
 * @param settings 全局配置仓库
 * @param checker GitHub Releases 检查器
 */
sealed interface UpdateCheckResult {
    data class NewVersion(val release: UpdateChecker.ReleaseInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
    data object Skipped : UpdateCheckResult
}

/** 将网络查询结果转换为 UI 可消费的明确状态；纯逻辑便于测试。 */
internal fun classifyUpdateResult(currentVersion: String, result: io.zer0.common.Result<UpdateChecker.ReleaseInfo>): UpdateCheckResult {
    val release = result.getOrNull() ?: return UpdateCheckResult.Failed(
        (result as? io.zer0.common.Result.Error)?.message ?: "unknown",
    )
    return if (UpdateChecker.compareVersions(currentVersion, release.tagName) >= 0) {
        UpdateCheckResult.UpToDate
    } else {
        UpdateCheckResult.NewVersion(release)
    }
}

class UpdateNotifier(
    private val settings: SettingsRepository,
    private val checker: UpdateChecker,
) {

    /**
     * 检查并通知更新。
     *
     * @param context 用于读取 versionName + 发通知
     * @param forceCheck true 时跳过 24 小时间隔检查(用户手动触发时使用)
     */
    suspend fun checkAndNotify(context: Context, forceCheck: Boolean = false): UpdateCheckResult {
        // Shaheen 2: upstream update checks are permanently disabled. Every check
        // path (auto at startup, manual from Settings) funnels through this
        // function, so returning Skipped here means the app never polls
        // museai.ltd or the Zer0Qing/Muse releases, and never shows an upstream
        // update prompt, home banner, or notification. Shaheen 2 updates are
        // delivered manually from Malik's own fork
        // (https://github.com/malikg74email-lab/Muse). The original logic below
        // is intentionally left in place but unreachable.
        return UpdateCheckResult.Skipped
        // 用户可在设置中关闭自动检查;手动检查(forceCheck=true)仍允许
        val enabled = settings.updateCheckEnabledFlow.firstSafe() ?: true
        val allowedByInterval = forceCheck || (enabled && shouldCheck())
        if (!forceCheck && !enabled) {
            Logger.i(TAG, "update check disabled, skip")
        } else if (!allowedByInterval) {
            Logger.d(TAG, "update check interval not elapsed, skip")
        }
        return if ((!enabled && !forceCheck) || !allowedByInterval) {
            UpdateCheckResult.Skipped
        } else {
            performCheck(context)
        }
    }

    /** 两个手动入口共用此方法，统一缓存写入与预期网络/持久化错误映射。 */
    suspend fun checkManually(context: Context): UpdateCheckResult = try {
        checkAndNotify(context, forceCheck = true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        Logger.w(TAG, "manual update check failed: ${error.message}", error)
        UpdateCheckResult.Failed(error.message ?: "network error")
    }

    private suspend fun performCheck(context: Context): UpdateCheckResult {
        // 记录检查时间(无论是否成功,避免失败时反复重试导致打 GitHub API 频次过高)
        settings.saveLastUpdateCheckTime(System.currentTimeMillis())
        val currentVersion = getCurrentVersionName(context)
        val outcome = classifyUpdateResult(currentVersion, checker.checkLatestRelease())
        return when (outcome) {
            is UpdateCheckResult.Failed -> {
                Logger.w(TAG, "checkLatestRelease failed: ${outcome.message}")
                outcome
            }
            UpdateCheckResult.Skipped -> outcome
            UpdateCheckResult.UpToDate -> {
                // 当前版本 >= 最新版本,清空缓存的 ReleaseInfo(Banner 不再展示)
                settings.saveLatestReleaseInfo(null)
                Logger.i(TAG, "already up to date: current=$currentVersion")
                outcome
            }
            is UpdateCheckResult.NewVersion -> {
                val release = outcome.release
                Logger.i(TAG, "new version found: current=$currentVersion, latest=${release.tagName}")
                // 缓存 ReleaseInfo,UI 通过 latestReleaseInfoFlow 渲染 Banner
                settings.saveLatestReleaseInfo(
                    AppJson.encodeToString(UpdateChecker.ReleaseInfo.serializer(), release),
                )
                notifyNewVersion(context, release)
                outcome
            }
        }
    }

    /**
     * 判断距离上次检查是否已超过 24 小时。
     */
    private suspend fun shouldCheck(): Boolean {
        val lastTime = settings.lastUpdateCheckTimeFlow.firstSafe() ?: 0L
        if (lastTime <= 0L) return true
        val elapsed = System.currentTimeMillis() - lastTime
        return elapsed >= CHECK_INTERVAL_MILLIS
    }

    /**
     * 显示"发现新版本"通知 — 复用 MuseNotificationManager 的 CHANNEL_CHAT_COMPLETED 渠道。
     *
     * 通知标题:"发现新版本"
     * 通知正文:"<tagName> 已发布,点击查看详情"
     * 点击跳转 MainActivity(由用户在应用内点 Banner 完成 APK 下载/查看详情)。
     */
    private fun notifyNewVersion(context: Context, release: UpdateChecker.ReleaseInfo) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val title = context.getString(R.string.update_notif_title)
        val text = context.getString(R.string.update_notif_text, release.tagName)
        val notif = NotificationCompat.Builder(context, MuseNotificationManager.CHANNEL_CHAT_COMPLETED)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(
                MuseNotificationManager(context).buildMainActivityPendingIntent(
                    MuseNotificationTarget.SettingsAbout,
                ),
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        resultOf { nm.notify(NOTIF_ID_NEW_VERSION, notif) }
            .onError { msg, _ -> Logger.w(TAG, "notify new version failed: $msg") }
    }

    companion object {
        private const val TAG = "UpdateNotifier"

        /** 通知 ID(避免与 MuseNotificationManager 现有 ID 冲突,选 9000 段)。 */
        private const val NOTIF_ID_NEW_VERSION = 9001

        /** 自动检查最小间隔:24 小时。 */
        private const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

        /**
         * 便捷方法:语义版本比较 — 委托给 [UpdateChecker.compareVersions]。
         */
        fun compareVersions(current: String, latest: String): Int = UpdateChecker.compareVersions(current, latest)

        /**
         * v1.0.72: 读取当前应用 versionName(从 private 提升为 companion,供 UI 静态调用)。
         * 读取失败返回 "0",保证后续比较可执行。
         */
        fun getCurrentVersionName(context: Context): String {
            return resultOf {
                val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        android.content.pm.PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                packageInfo.versionName ?: "0"
            }.getOrNull() ?: "0"
        }

        /**
         * 官网下载页 — 应用内所有下载/更新出口统一指向这里。
         *
         * 「立即下载」为什么先落到官网而不是直接给 APK 直链：官网会用当前 Release 的资产列表
         * 按机型给出对应架构的按钮，用户点一下即从 GitHub 资产直链下载；应用内自己判断架构
         * 反而容易选错包（尤其 32/64 位混杂的机器）。官网下载页不自己存安装包。
         */
        const val OFFICIAL_DOWNLOAD_PAGE = "https://museai.ltd/download/"

        /**
         * 构造用于打开官网下载页的 [Intent](ACTION_VIEW)。
         * 调用方负责 startActivity / chooser。
         */
        fun buildOfficialDownloadIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_DOWNLOAD_PAGE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Flow.first() 的便捷包装 — 失败时返回 null,避免上游异常传播(用于读取设置项的简单场景)。
 * CancellationException 仍向上抛(协程取消不可吞)。
 */
private suspend fun <T> Flow<T>.firstSafe(): T? = try {
    first()
} catch (t: Throwable) {
    if (t is kotlin.coroutines.cancellation.CancellationException) throw t
    Logger.w("UpdateNotifier", "Flow.first() failed: ${t.message}")
    null
}
