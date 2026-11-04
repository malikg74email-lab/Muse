package io.zer0.muse.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/**
 * v1.134: 自动本地数据库备份 Worker。
 *
 * [AutoBackupHelper] 类已存在(v1.107)但从未接入调度系统(孤儿组件)。
 * 本 Worker 通过 WorkManager 每日周期任务拉起,执行:
 *  1. [AutoBackupHelper.backupNow] — WAL checkpoint + 复制 muse.db 到 backups/
 *  2. [AutoBackupHelper.trimOldBackups] — 保留最近 7 份,清理更旧的
 *
 * 设计取舍(与 [ScheduledTaskWorker] 一致):
 *  - 周期 24 小时(每日 1 次,符合 AutoBackupHelper 类注释约定)
 *  - 不使用 setExpedited:备份非紧急
 *  - 不要求网络约束:本地操作,无需网络
 *  - 备份失败返回 Result.retry(),让 WorkManager 对临时 I/O/数据库故障退避重试；成功后再清理旧备份
 *
 * Koin 拿依赖:WorkManager 默认跑在主进程,Worker 启动时 MuseApp.onCreate 已执行,
 * Koin 已初始化。若处于 Safe Mode(startKoin 失败),GlobalContext 为空,
 * 这里捕获异常返回 success 跳过本次,等下次启动恢复。
 */
class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        if (koin == null) {
            Logger.w(TAG, "Koin not initialized (Safe Mode?), skipping this Worker run")
            return Result.success()
        }
        // v1.xxx: 后台调度总控 — 关闭时跳过执行体,周期调度本身仍保留,重新打开即恢复
        val workEnabled =
            scheduleWorkEnabledOrFalse(
                resultOf { koin.get<io.zer0.muse.data.SettingsRepository>()?.scheduleWorkEnabledFlow?.first() }
                    .getOrNull(),
            )
        if (!workEnabled) {
            Logger.i(TAG, "Background scheduling master switch is off, skipping this run")
            return Result.success()
        }
        val helper = resultOf { koin.get<io.zer0.muse.data.stats.AutoBackupHelper>() }.getOrNull()
        if (helper == null) {
            Logger.w(TAG, "AutoBackupHelper resolution failed, skipping this Worker run")
            return Result.success()
        }
        val ok = resultOf { helper.backupNow() }
            .onError { msg, t -> Logger.w(TAG, "backupNow failed: ${t?.message ?: msg}") }
            .getOrNull() ?: false
        if (!ok) {
            // 日志已记录失败原因，但 WorkManager 仍需对临时 I/O/数据库故障退避重试。
            return Result.retry()
        }
        helper.trimOldBackups()
        return Result.success()
    }

    companion object {
        private const val TAG = "AutoBackupWorker"
        const val UNIQUE_WORK_NAME = "muse_auto_backup_worker"
    }
}
