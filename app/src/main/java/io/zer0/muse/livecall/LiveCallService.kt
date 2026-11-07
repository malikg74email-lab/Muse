package io.zer0.muse.livecall

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.notification.MuseNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Shaheen 2 Fix Phase 2: 通话前台服务(microphone 型)。
 *
 * 存在的理由只有两个,都是 Malik 的硬性要求:
 *  1. 通话期间进程保持前台优先级 —— 切到别的 App(多任务)通话不被系统杀掉;
 *  2. 常驻一条 WhatsApp 式通话通知,带 End Call 按钮 —— 不在通话界面也能挂断。
 *
 * 音频与 socket 本体由 [LiveCallController] 持有的 [GeminiLiveSession] 负责;
 * 本服务只负责「钉住进程 + 通知」,并观察通话状态:通话一旦结束/出错就自己退出。
 */
class LiveCallService : Service() {

    companion object {
        private const val TAG = "LiveCallService"

        const val ACTION_START = "io.zer0.muse.action.LIVE_CALL_START"
        const val ACTION_END_CALL = "io.zer0.muse.action.LIVE_CALL_END"

        private const val NOTIFICATION_ID = 31002
        private const val REQUEST_CODE_CONTENT = 31021
        private const val REQUEST_CODE_END_CALL = 31022

        /** 通话开始时拉起(重复调用无副作用)。 */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, LiveCallService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { t ->
                Logger.w(TAG, "Starting the live call service failed: ${t.message}")
            }
        }

        /** 通话结束时停掉(服务也会因状态观察自停,这里是双保险)。 */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, LiveCallService::class.java))
            }.onFailure { t ->
                Logger.w(TAG, "Stopping the live call service failed: ${t.message}")
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat(connectedAtMillis = null)
        observeCallState()
        Logger.i(TAG, "Live call service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_END_CALL -> {
                Logger.i(TAG, "End Call from notification")
                runCatching {
                    GlobalContext.get().get<LiveCallController>().endCall()
                }.onFailure { Logger.w(TAG, "End call via controller failed: ${it.message}") }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 每次 START 都刷新一次通知(带上接通时间,秒表从接通时刻走)。
                startForegroundCompat(connectedAtMillis = currentConnectedAt())
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    /** 通话落到终局(ENDED / ERROR / 回到 IDLE)时服务自停,避免空通知常驻。 */
    private fun observeCallState() {
        val controller = runCatching {
            GlobalContext.get().get<LiveCallController>()
        }.getOrNull() ?: return
        var sawActiveCall = false
        // uiState 会随音量电平高频发射;通知只在阶段/接通时刻变化时才重挂,
        // 否则每秒重挂十几次通知既费电又可能被系统限流。
        var postedPhase: LiveCallPhase? = null
        var postedConnectedAt: Long? = null
        stateJob = serviceScope.launch {
            controller.uiState.collect { state ->
                when (state.phase) {
                    LiveCallPhase.CONNECTING, LiveCallPhase.LIVE -> {
                        sawActiveCall = true
                        if (postedPhase != state.phase || postedConnectedAt != state.connectedAtMillis) {
                            postedPhase = state.phase
                            postedConnectedAt = state.connectedAtMillis
                            startForegroundCompat(state.connectedAtMillis)
                        }
                    }
                    LiveCallPhase.ENDED, LiveCallPhase.ERROR, LiveCallPhase.IDLE -> {
                        if (sawActiveCall || state.phase == LiveCallPhase.ENDED) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
        }
    }

    private fun currentConnectedAt(): Long? = runCatching {
        GlobalContext.get().get<LiveCallController>().uiState.value.connectedAtMillis
    }.getOrNull()

    private fun startForegroundCompat(connectedAtMillis: Long?) {
        MuseNotificationManager(this).ensureChannels()
        val notification = buildNotification(connectedAtMillis)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { t ->
            Logger.w(TAG, "startForeground failed: ${t.message}")
        }
    }

    private fun buildNotification(connectedAtMillis: Long?): Notification {
        val builder = NotificationCompat.Builder(this, MuseNotificationManager.CHANNEL_LIVE_CALL)
            .setSmallIcon(R.drawable.ic_muse_logo)
            .setContentTitle(getString(R.string.live_call_notif_title))
            .setContentText(getString(R.string.live_call_notif_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setShowWhen(connectedAtMillis != null)
        if (connectedAtMillis != null) {
            builder.setWhen(connectedAtMillis).setUsesChronometer(true)
        }
        // 点击通知回到应用(通话界面)。
        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        REQUEST_CODE_CONTENT,
                        launch,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        }
        // End Call 动作:回到本服务处理,真正结束整通通话。
        val endIntent = Intent(this, LiveCallService::class.java).setAction(ACTION_END_CALL)
        val endPending = PendingIntent.getService(
            this,
            REQUEST_CODE_END_CALL,
            endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(
            R.drawable.ic_quick_capture_close,
            getString(R.string.live_call_end_call),
            endPending,
        )
        return builder.build()
    }
}
