package io.zer0.muse.crash

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.zer0.common.Logger
import io.zer0.common.Perf
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.audit.AuditLogger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * ANR(应用无响应)检测器。
 *
 * 检测机制(v2.3.2 起,判定细节见 [AnrDetector]):
 *  - 独立守护线程 "AnrWatcher" 每 [CHECK_INTERVAL_MS] 向主线程 Handler 投递一个 Ping
 *  - Ping 在主线程执行时把 [pongCount] 加一;**判定只看这个计数有没有前进**
 *    (不再用"距上次响应的时间戳"做减法:看门狗线程自身被挂起或饿死时,那种算法会把
 *    那段时间错算成主线程阻塞 —— 这是 2026-09-27~29 五份 anr_*.txt 误报的直接原因)
 *  - 主线程连续 [ANR_TIMEOUT_MS] 未执行 Ping 即判定 ANR
 *  - 本轮实际耗时远超预期间隔时(overshoot > [SUSPEND_TOLERANCE_MS]),说明看门狗自己都没能
 *    按时醒来(进程被冻结/深度 Doze)→ 视为"本轮无法判定",重置基线而不报 ANR
 *
 * ANR 时采集:
 *  - 全部线程堆栈(主线程 + 所有其他线程)
 *  - 内存状态(Runtime.totalMemory / freeMemory / maxMemory + 堆使用率)
 *  - 最近 Perf 埋点记录([Perf.snapshotRecent])
 *
 * ANR 日志写入 filesDir/crash/anr_{timestamp}.txt(与 [MuseCrashHandler] 同目录),
 * 并复用 [MuseCrashHandler.redactSensitive] 脱敏堆栈(避免 token/apiKey 泄露);
 * 同时记录到 [AuditLogger](若可用)与 [Logger.e]。
 *
 * 安全设计(避免自身导致 ANR):
 *  - 独立守护线程,不依赖主线程 Looper,自身不会阻塞主线程
 *  - 主线程 Handler 用**强引用**持有:它只持有 Looper/MessageQueue,不持有 Context/Activity,
 *    不会泄漏;早期版本用 WeakReference 持有会被 GC 回收,导致 post 静默失效、永久误判
 *    (原因与证据见 [mainHandler] 注释)
 *  - 检测线程优先级 [Thread.MIN_PRIORITY],减少对正常调度的影响
 *  - ANR 后由 [AnrDetector] 去重,避免日志风暴
 *  - 循环内全部 try-catch,任何异常都不能让检测线程意外退出
 *
 * 可配置开关:[SettingsRepository.anrDetectionCache](默认 true),通过 [SettingsRepository] 同步缓存读取,
 * 支持运行时切换(用户在设置页关闭后,下一个检测周期即停止判定)。
 *
 * @param appContext 应用上下文(用于定位 filesDir/crash)
 * @param settings 设置仓库(读取 anrDetection 开关)
 * @param auditLogger 审计日志记录器(可选,为 null 时跳过审计记录)
 */
class AnrWatcher(
    private val appContext: Context,
    private val settings: SettingsRepository,
    private val auditLogger: AuditLogger? = null,
) {
    // 主线程 Handler。
    //
    // v2.3.2 修复:此前用 WeakReference 持有它,而该 Handler 没有任何强引用
    // (原注释"主线程 Looper 随进程生命周期,实际不会被回收"混淆了 Looper 与 Handler ——
    //  进程级的是 Looper,Handler 只是个普通对象),两次 ping 之间消息队列里没有指向它的
    // Message 时就会被 GC 回收;之后 post 静默失效、响应时间戳永久冻结,每个进程误报一次 ANR。
    // Handler 只持有 Looper/MessageQueue,不持有 Context/Activity,强引用不会泄漏。
    private val mainHandler = Handler(Looper.getMainLooper())

    // 主线程已执行的 ping 次数(单调递增)。
    // v2.3.2: 判定改为"计数有没有前进",不再用"距上次响应的时间戳"做减法 ——
    // 后者在看门狗线程自身被挂起/饿死时会把那段时间错算成主线程阻塞。
    private val pongCount = AtomicLong(0)

    // 运行标志(stop 时置 false,通知检测线程退出)
    @Volatile
    private var running: Boolean = false

    // 守护线程引用(stop 时 interrupt + join)
    private var watcherThread: Thread? = null

    // Ping:投递到主线程,能被执行即说明主线程未阻塞(计数由 [AnrDetector] 比对)
    private val ping = Runnable { pongCount.incrementAndGet() }

    /**
     * 启动 ANR 检测。
     *
     * 若 [SettingsRepository.anrDetectionCache] == false 则直接返回不启动。
     * 守护线程会持续运行直到 [stop] 被调用或进程结束。
     */
    fun start() {
        if (!settings.anrDetectionCache) {
            Logger.i(TAG, "ANR detection is disabled in settings; not starting")
            return
        }
        if (running) return
        running = true
        watcherThread = Thread({ watchLoop() }, THREAD_NAME).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY // 低优先级,减少对正常调度的影响
            start()
        }
        Logger.i(TAG, "ANR detection started (check=${CHECK_INTERVAL_MS}ms, timeout=${ANR_TIMEOUT_MS}ms)")
    }

    /** 停止 ANR 检测(中断守护线程)。 */
    fun stop() {
        running = false
        watcherThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        watcherThread = null
        Logger.i(TAG, "ANR detection stopped")
    }

    /**
     * 检测循环:每 [CHECK_INTERVAL_MS] 投递 Ping 并按 [AnrDetector] 的结论判定是否 ANR。
     *
     * v2.3.2: 判定输入改为(ticket 计数, 本轮实际耗时),不再用时间戳做减法 ——
     * 详见 [AnrDetector] 与 [mainHandler] 注释。
     *
     * 循环内全部 try-catch,任何异常都不能让检测线程意外退出(否则 ANR 检测静默失效)。
     */
    private fun watchLoop() {
        val detector =
            AnrDetector(
                timeoutMs = ANR_TIMEOUT_MS,
                expectedIntervalMs = CHECK_INTERVAL_MS,
                suspendToleranceMs = SUSPEND_TOLERANCE_MS,
            )
        while (running) {
            try {
                // 双重检查开关:支持运行时通过设置切换
                if (!settings.anrDetectionCache) {
                    Thread.sleep(CHECK_INTERVAL_MS)
                    continue
                }
                // 投递 Ping 到主线程(非阻塞,立即返回)。post 失败说明主线程消息队列不可用,
                // 本轮不做判定(否则会把"ping 根本没投出去"误算成主线程无响应)。
                val postedAt = SystemClock.elapsedRealtime()
                val posted = mainHandler.post(ping)
                if (!posted) {
                    Logger.w(TAG, "Ping delivery failed (main thread message queue unavailable); skipping judgement this round")
                }
                // 等待一个检测周期
                Thread.sleep(CHECK_INTERVAL_MS)
                if (posted) {
                    handleDecision(detector.onCheck(SystemClock.elapsedRealtime(), postedAt, pongCount.get()))
                }
            } catch (e: InterruptedException) {
                // stop() 触发的中断,正常退出循环
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                // 任何异常都不能让检测线程退出(否则 ANR 检测静默失效)
                Logger.w(TAG, "ANR detection loop exception: ${t.message}", t)
                try {
                    Thread.sleep(CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    /**
     * 处理一轮检测结论(v2.3.2: 从 [watchLoop] 抽出来,避免循环内分支过多触发复杂度门禁)。
     *
     * [AnrDetector.Decision.Blocked] 时按 v1.0.53 的口径决定是否落盘:
     * App 在后台时主线程被系统冻结/Doze,长时间不响应属正常行为,不记 ANR
     * (冻结期间"回前台首轮"的误报另由 [AnrDetector] 的挂起判定拦掉)。
     */
    private fun handleDecision(decision: AnrDetector.Decision) {
        when (decision) {
            is AnrDetector.Decision.Blocked -> {
                if (isAppInForeground()) {
                    onAnrDetected(decision.silenceMs)
                } else {
                    Logger.d(TAG, "Main thread unresponsive for ${decision.silenceMs}ms (app is in the background; not recorded as ANR)")
                }
            }
            AnrDetector.Decision.Suspended -> {
                Logger.d(TAG, "The detection thread was suspended this round (process frozen/Doze); ANR baseline reset")
            }
            AnrDetector.Decision.Suppressed, AnrDetector.Decision.Healthy -> Unit
        }
    }

    /**
     * ANR 检测到时的处理。
     *
     * 采集线程堆栈 + 内存 + Perf 记录,写入 anr_{timestamp}.txt,
     * 并记录到 AuditLogger(若可用)与 Logger.e。
     *
     * @param silenceMs 主线程无响应时长(毫秒)
     */
    private fun onAnrDetected(silenceMs: Long) {
        val anrTime = System.currentTimeMillis()
        Logger.e(TAG, "ANR detected! Main thread unresponsive for ${silenceMs}ms")
        // 写入 ANR 日志文件(脱敏后落盘,失败不抛出)
        resultOf { writeAnrLog(anrTime, silenceMs) }
            .onError { msg, t -> Logger.e(TAG, "Writing the ANR log failed: $msg", t) }
        // 记录到审计日志(若可用,fire-and-forget)
        resultOf {
            auditLogger?.log(
                category = "system",
                action = "anr_detected",
                target = "",
                detail = mapOf(
                    "silence_ms" to silenceMs,
                    "time" to anrTime,
                ),
                success = false,
            )
        }
    }

    /**
     * 写 ANR 日志到 filesDir/crash/anr_{timestamp}.txt。
     *
     * 采集内容:
     *  - 设备信息(Brand/Model/Android/ABI)
     *  - 内存状态(堆 Used/Free/Total/Max/Usage%)
     *  - 主线程堆栈(name/id/state + stackTrace)
     *  - 全部线程堆栈(Thread.getAllStackTraces)
     *  - 最近 Perf 埋点记录([Perf.snapshotRecent])
     *
     * 写盘后复用 [MuseCrashHandler.redactSensitive] 脱敏,避免堆栈中 token/apiKey 泄露;
     * 并清理旧 ANR 日志(保留最近 [MAX_ANR_LOGS] 份)。
     *
     * @param anrTime ANR 发生时间戳
     * @param silenceMs 主线程无响应时长(毫秒)
     */
    private fun writeAnrLog(anrTime: Long, silenceMs: Long) {
        // v1.0.53: 优先写 externalFilesDir(/sdcard/Android/data/io.zer0.muse/files/crash/),
        //   adb 与文件管理器可直接访问;external 不可用时回退私有 filesDir。
        //   私有目录(/data/user/0/...)在 Android 文件管理器不可见,真机排查困难。
        val externalCrashDir = appContext.getExternalFilesDir("crash")
        val crashDir = (externalCrashDir ?: File(appContext.filesDir, "crash")).apply { mkdirs() }
        val fmt = SimpleDateFormat(TIME_FMT, Locale.US)
        val fileName = "anr_${fmt.format(Date(anrTime))}.txt"
        val logFile = File(crashDir, fileName)

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("===== muse ANR log =====")
            pw.println("Time: ${Date(anrTime)}")
            pw.println("Silence: ${silenceMs}ms (main thread unresponsive duration)")
            // v2.3.2: 记录判定口径,便于事后判断报告可信度(旧报告的 5~133s "Silence" 多为
            // 进程冻结期间的误报,见 AnrDetector 注释)
            pw.println(
                "Detector: ticket(v2.3.2) interval=${CHECK_INTERVAL_MS}ms " +
                    "timeout=${ANR_TIMEOUT_MS}ms suspendTolerance=${SUSPEND_TOLERANCE_MS}ms",
            )
            pw.println("Note: suspensions caused by freezing/Doze do not count toward Silence; the stack below was sampled at decision time")
            pw.println()
            pw.println("----- Device Info -----")
            pw.println("Brand: ${Build.BRAND}")
            pw.println("Model: ${Build.MODEL}")
            pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            pw.println("ABIs: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            pw.println()
            pw.println("----- Memory Status -----")
            val rt = Runtime.getRuntime()
            val total = rt.totalMemory()
            val free = rt.freeMemory()
            val used = total - free
            val max = rt.maxMemory()
            pw.println("Heap Used: ${used / 1024} KB")
            pw.println("Heap Free: ${free / 1024} KB")
            pw.println("Heap Total: ${total / 1024} KB")
            pw.println("Heap Max: ${max / 1024} KB")
            pw.println("Heap Usage: ${if (max > 0) used * 100 / max else -1}%")
            pw.println()
            pw.println("----- Main Thread Stack -----")
            val mainThread = Looper.getMainLooper().thread
            pw.println("Thread: ${mainThread.name} (id=${mainThread.id}, state=${mainThread.state})")
            mainThread.stackTrace.forEach { pw.println("\tat $it") }
            pw.println()
            pw.println("----- All Threads Stacks -----")
            val allStacks = Thread.getAllStackTraces()
            pw.println("Total threads: ${allStacks.size}")
            // 按 thread id 倒序排列,输出每个线程的堆栈
            allStacks.entries.sortedByDescending { it.key.id }.forEach { (thread, stack) ->
                pw.println()
                pw.println("Thread: ${thread.name} (id=${thread.id}, state=${thread.state})")
                stack.forEach { pw.println("\tat $it") }
            }
            pw.println()
            pw.println("----- Recent Perf Records -----")
            val perfRecords = Perf.snapshotRecent()
            if (perfRecords.isEmpty()) {
                pw.println("(No Perf instrumentation records)")
            } else {
                perfRecords.forEach { rec ->
                    pw.println("${rec.name}: ${rec.elapsedMs}ms @ ${Date(rec.timestamp)}")
                }
            }
        }
        // 脱敏后写盘(复用 MuseCrashHandler 的脱敏逻辑,避免堆栈中的 token/apiKey 泄露)
        val redacted = MuseCrashHandler.redactSensitive(sw.toString())
        logFile.writeText(redacted)

        // 清理旧 ANR 日志(保留最近 MAX_ANR_LOGS 份,仅清理 anr_ 前缀,不影响 crash_ 日志)
        crashDir.listFiles { f -> f.name.startsWith("anr_") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_ANR_LOGS)
            ?.forEach { it.delete() }

        Logger.e(TAG, "ANR log written: ${logFile.absolutePath}")
    }

    companion object {
        private const val TAG = "AnrWatcher"
        private const val THREAD_NAME = "AnrWatcher"

        // 检测间隔:主线程 Ping 投递周期(2s)
        private const val CHECK_INTERVAL_MS = 2_000L

        // ANR 判定阈值:主线程无响应超过此时长即判定 ANR(5s)
        private const val ANR_TIMEOUT_MS = 5_000L

        // v2.3.2: 挂起容差 — 本轮实际耗时超出检测间隔这么多,即认为看门狗自身被挂起
        // (进程冻结/深度 Doze),本轮不做判定并重置基线,避免把冻结时长误报成 ANR
        private const val SUSPEND_TOLERANCE_MS = 3_000L

        // ANR 日志文件名时间戳格式
        private const val TIME_FMT = "yyyyMMdd-HHmmss"

        // 保留最近 ANR 日志份数(与 MuseCrashHandler.MAX_CRASH_LOGS 对齐)
        private const val MAX_ANR_LOGS = 5
    }

    /**
     * v1.0.53: 判断应用是否在前台(RESUMED)。
     *
     * 后台冻结/Doze 时主线程长时间不响应是系统正常行为,
     * 此前会误报大量 ANR(最高 741 秒),以此过滤。
     * ProcessLifecycleOwner 的 state 是 @Volatile 线程安全读。
     */
    private fun isAppInForeground(): Boolean = try {
        androidx.lifecycle.ProcessLifecycleOwner.get()
            .lifecycle.currentState
            .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    } catch (t: Throwable) {
        // 拿不到状态时保守按前台处理(宁可记录,不漏真 ANR)
        true
    }
}
