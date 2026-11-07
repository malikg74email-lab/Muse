package io.zer0.muse.livecall

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import io.zer0.ai.core.ProxyProvider
import io.zer0.common.Logger
import io.zer0.muse.asr.AsrAudioCapture
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Shaheen 2 Fix Phase 2: 真正的 Gemini Live 通话引擎(单次通话一个实例)。
 *
 * 全双工:麦克风 16kHz PCM 以 100ms 小包持续上行,模型 24kHz PCM 音频流式下行播放,
 * 两条路同时跑;服务端 VAD 开着,Malik 插话时服务端发 interrupted,这里立刻清空
 * 播放队列(barge-in)。工具调用走注入的 [LiveToolExecutor](控制器接到 ToolRegistry),
 * 与聊天同一条执行链。
 *
 * 生命周期:start() 建 socket → onOpen 发 setup → setupComplete 后开始采集并触发问候
 * → end() 主动收尾。任何非主动断开都落到 ERROR 并带一条纯文本原因,界面负责展示与 Retry。
 *
 * 诚实边界:本类只能在 Malik 的手机上被真实验证(他的 key、他的麦克风、MIUI 前台
 * 行为都不在本机),离线能证明的只有协议/状态/分帧等纯逻辑(见 livecall 单测)。
 */
class GeminiLiveSession(
    context: Context,
    private val apiKey: String,
    private val toolDeclarations: List<JsonObject>,
    private val toolExecutor: LiveToolExecutor,
    sharedClient: OkHttpClient? = null,
) {

    companion object {
        private const val TAG = "GeminiLiveSession"
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMachine = LiveCallStateMachine()
    private val transcriptAccumulator = LiveTranscriptAccumulator()
    private val cancelledToolCallIds = mutableSetOf<String>()
    private val endingIntentionally = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    private val client: OkHttpClient = sharedClient ?: ProxyProvider.applyTo(OkHttpClient.Builder())
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var captureJob: Job? = null
    private var captureSession: AsrAudioCapture.Session? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private val player = LiveAudioPlayer()

    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerphone: Boolean = false

    // ── 对外状态 ──────────────────────────────────────────────────────────

    private val _phase = MutableStateFlow(LiveCallPhase.IDLE)
    val phase: StateFlow<LiveCallPhase> = _phase.asStateFlow()

    private val _transcript = MutableStateFlow<List<LiveTranscriptLine>>(emptyList())
    val transcript: StateFlow<List<LiveTranscriptLine>> = _transcript.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Shaheen 正在说话(有音频在播)—— 界面据此切换「聆听中 / 回答中」。 */
    private val _modelSpeaking = MutableStateFlow(false)
    val modelSpeaking: StateFlow<Boolean> = _modelSpeaking.asStateFlow()

    /** 0..1 的音量电平(麦克风与播放取大者),驱动通话圆环呼吸幅度。 */
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    /** setupComplete 的时刻(通知秒表 / 界面计时都以此为起点);未接通时为 null。 */
    @Volatile
    var connectedAtMillis: Long? = null
        private set

    // ── 生命周期 ──────────────────────────────────────────────────────────

    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (!transition(LiveCallPhase.CONNECTING)) return
        enterCallAudioMode()
        val request = Request.Builder()
            .url(LiveCallConfig.webSocketUrl(apiKey))
            .build()
        webSocket = client.newWebSocket(request, SessionSocketListener())
        Logger.i(TAG, "Live call connecting (model=${LiveCallConfig.LIVE_MODEL_ID})")
        armSetupWatchdog()
    }

    /**
     * setup 看门狗(验收审计 D1):socket 连上但服务端迟迟不回 setupComplete 时,
     * 原实现会让界面永远停在「连接中」。这里在时限到达后复查阶段,仍是
     * CONNECTING 就落到带纯文本原因的 ERROR(界面展示 + Retry 真重拨);
     * 已接通/已结束则什么都不做。end() 会 cancel 整个 scope,看门狗随之消失。
     */
    private fun armSetupWatchdog() {
        val startedAtMillis = System.currentTimeMillis()
        scope.launch {
            delay(LiveCallConfig.SETUP_TIMEOUT_MS)
            val elapsed = System.currentTimeMillis() - startedAtMillis
            if (LiveSetupWatchdog.shouldFail(stateMachine.phase, elapsed)) {
                Logger.w(TAG, "Setup watchdog fired after ${elapsed}ms without setupComplete")
                fail(
                    "The voice service did not answer in time. " +
                        "Check your internet connection, then tap Retry.",
                )
            }
        }
    }

    /** 静音开关:静音时停发麦克风帧(采集不中断,避免重启 AudioRecord 的竞态)。 */
    fun setMuted(muted: Boolean) {
        _muted.value = muted
    }

    /** 主动结束通话(界面挂断 / 通知 End Call / 服务收尾都走这条)。 */
    fun end() {
        if (!endingIntentionally.compareAndSet(false, true)) return
        transition(LiveCallPhase.ENDED)
        teardownAudio()
        runCatching { webSocket?.close(1000, "Call ended") }
        webSocket = null
        exitCallAudioMode()
        scope.cancel()
        Logger.i(TAG, "Live call ended by user side")
    }

    // ── WebSocket 回调 ────────────────────────────────────────────────────

    private inner class SessionSocketListener : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            val setup = LiveCallConfig.buildSetupMessage(toolDeclarations = toolDeclarations)
            ws.send(setup)
            Logger.i(TAG, "Socket open, setup sent (${toolDeclarations.size} tool declarations)")
        }

        override fun onMessage(ws: WebSocket, text: String) {
            val events = runCatching { LiveCallProtocol.decodeServerMessage(text) }
                .getOrElse {
                    Logger.w(TAG, "Undecodable server frame ignored: ${it.message}")
                    emptyList()
                }
            events.forEach { handleEvent(ws, it) }
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            // Live 的控制/音频帧都走 JSON 文本帧;二进制帧不是本协议的一部分,忽略即可。
            Logger.d(TAG, "Binary frame ignored (${bytes.size} bytes)")
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            if (endingIntentionally.get()) return
            val message = when {
                response != null ->
                    "Live call connection failed (HTTP ${response.code}). " +
                        "Check the Gemini API key and your internet connection, then tap Retry."
                else ->
                    "Live call connection failed. Check your internet connection, then tap Retry."
            }
            Logger.w(TAG, "Socket failure: ${t.message} (http=${response?.code})")
            fail(message)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            if (endingIntentionally.get()) return
            val detail = reason.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            Logger.w(TAG, "Socket closed by server: code=$code reason=$reason")
            fail("The call ended unexpectedly$detail. Tap Retry to reconnect.")
        }
    }

    private fun handleEvent(ws: WebSocket, event: LiveServerEvent) {
        when (event) {
            LiveServerEvent.SetupComplete -> {
                connectedAtMillis = System.currentTimeMillis()
                if (transition(LiveCallPhase.LIVE)) {
                    player.start(scope)
                    startCapture()
                    // 开场问候:让 Shaheen 先开口,像真通话被接通。
                    ws.send(LiveCallProtocol.encodeClientTextTurn(LiveCallConfig.GREETING_KICKOFF_TEXT))
                    Logger.i(TAG, "Live call connected")
                }
            }
            is LiveServerEvent.AudioData -> {
                _modelSpeaking.value = true
                updateLevel(event.pcm)
                player.enqueue(event.pcm)
            }
            is LiveServerEvent.InputTranscript -> {
                transcriptAccumulator.appendDelta(LiveSpeaker.USER, event.text)
                publishTranscript()
            }
            is LiveServerEvent.OutputTranscript -> {
                // 累加器内部会在说话人切换时自动定稿上一行,这里只管追加。
                transcriptAccumulator.appendDelta(LiveSpeaker.SHAHEEN, event.text)
                publishTranscript()
            }
            LiveServerEvent.Interrupted -> {
                // barge-in:立刻清空待播音频,正在嘴里的话也停掉。
                player.flush()
                _modelSpeaking.value = false
                transcriptAccumulator.finalizeCurrent()
                publishTranscript()
                Logger.d(TAG, "Playback interrupted by user speech (barge-in)")
            }
            LiveServerEvent.TurnComplete -> {
                _modelSpeaking.value = false
                transcriptAccumulator.finalizeCurrent()
                publishTranscript()
            }
            is LiveServerEvent.ToolCalls -> executeToolCalls(ws, event.calls)
            is LiveServerEvent.ToolCallCancellation -> {
                synchronized(cancelledToolCallIds) { cancelledToolCallIds += event.ids }
            }
            LiveServerEvent.GoAway -> {
                Logger.w(TAG, "Server sent goAway; treating as end of session")
                fail("The voice service ended the call. Tap Retry to reconnect.")
            }
            is LiveServerEvent.ServerError -> {
                Logger.w(TAG, "Server error frame: ${event.message}")
                fail("The voice service said: ${event.message}")
            }
        }
    }

    private fun executeToolCalls(ws: WebSocket, calls: List<LiveToolCallRequest>) {
        scope.launch {
            val responses = mutableListOf<LiveToolResponse>()
            for (call in calls) {
                val cancelled = synchronized(cancelledToolCallIds) { call.id in cancelledToolCallIds }
                if (cancelled) continue
                Logger.i(TAG, "Executing live tool call: ${call.name}")
                val result = runCatching {
                    toolExecutor.execute(call.name, call.args.toString())
                }.getOrElse { t ->
                    Logger.w(TAG, "Live tool call failed: ${call.name}: ${t.message}")
                    "Tool ${call.name} failed: ${t.message ?: "unknown error"}"
                }
                responses += LiveToolResponse(id = call.id, name = call.name, rawResult = result)
            }
            // 发送前再查一次取消集:执行途中到达的取消同样要丢掉对应结果(D4)。
            val cancelledNow = synchronized(cancelledToolCallIds) { cancelledToolCallIds.toSet() }
            val sendable = LiveCallProtocol.dropCancelledResponses(responses, cancelledNow)
            // 通话若已在执行途中落到 ERROR/ENDED,结果不再往死掉的 socket 里发。
            if (sendable.isNotEmpty() && !endingIntentionally.get() &&
                stateMachine.phase == LiveCallPhase.LIVE
            ) {
                ws.send(LiveCallProtocol.encodeToolResponses(sendable))
            }
        }
    }

    // ── 采集 ──────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startCapture() {
        captureJob = scope.launch {
            val session = AsrAudioCapture.create(LiveCallConfig.INPUT_SAMPLE_RATE, TAG)
            if (session == null) {
                fail("The microphone could not be opened. Check the microphone permission, then tap Retry.")
                return@launch
            }
            captureSession = session
            attachAudioEffects(session.recorder.audioSessionId)
            val accumulator = PcmFrameAccumulator(LiveCallConfig.INPUT_FRAME_BYTES)
            val readBuffer = ByteArray(session.bufferSize)
            try {
                session.recorder.startRecording()
                while (isActive && !endingIntentionally.get()) {
                    val read = session.recorder.read(readBuffer, 0, readBuffer.size)
                    if (read > 0) {
                        if (!_muted.value) {
                            updateLevel(readBuffer, read)
                            val ws = webSocket ?: break
                            accumulator.offer(readBuffer, read).forEach { frame ->
                                ws.send(LiveCallProtocol.encodeAudioChunk(frame))
                            }
                        }
                    } else if (read < 0) {
                        Logger.w(TAG, "AudioRecord.read returned $read; capture stopped")
                        break
                    }
                }
            } catch (t: Throwable) {
                if (!endingIntentionally.get()) {
                    Logger.w(TAG, "Capture loop failed: ${t.message}", t)
                    fail("Microphone capture failed. Tap Retry to try again.")
                }
            } finally {
                releaseCapture()
            }
        }
    }

    private fun attachAudioEffects(audioSessionId: Int) {
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
            }
        }.onFailure { Logger.w(TAG, "Audio effects unavailable: ${it.message}") }
    }

    private fun releaseCapture() {
        runCatching { captureSession?.recorder?.stop() }
        runCatching { captureSession?.recorder?.release() }
        captureSession = null
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
    }

    // ── 音频模式(通话路由) ────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun enterCallAudioMode() {
        runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            previousAudioMode = am.mode
            @Suppress("DEPRECATION")
            previousSpeakerphone = am.isSpeakerphoneOn
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            // 智能体通话默认免提:Malik 不需要把手机贴在耳边。
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        }.onFailure { Logger.w(TAG, "Setting call audio mode failed: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun exitCallAudioMode() {
        runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = previousAudioMode
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = previousSpeakerphone
        }.onFailure { Logger.w(TAG, "Restoring audio mode failed: ${it.message}") }
    }

    // ── 杂项 ──────────────────────────────────────────────────────────────

    private fun transition(target: LiveCallPhase): Boolean {
        val changed = stateMachine.transitionTo(target)
        if (changed) _phase.value = stateMachine.phase
        return changed
    }

    private fun publishTranscript() {
        _transcript.value = transcriptAccumulator.lines
    }

    private fun updateLevel(pcm: ByteArray, length: Int = pcm.size) {
        if (length <= 0) return
        var sum = 0.0
        var i = 0
        while (i + 1 < length) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            sum += sample.toDouble() * sample.toDouble()
            i += 2
        }
        val rms = sqrt(sum / (length / 2).coerceAtLeast(1))
        _audioLevel.value = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    private fun teardownAudio() {
        captureJob?.cancel()
        captureJob = null
        releaseCapture()
        player.release()
        _modelSpeaking.value = false
    }

    private fun fail(message: String) {
        if (endingIntentionally.get()) return
        _errorMessage.value = message
        if (transition(LiveCallPhase.ERROR)) {
            teardownAudio()
            runCatching { webSocket?.close(1000, "Error") }
            webSocket = null
            exitCallAudioMode()
        }
    }
}

/**
 * 24kHz 流式播放器:音频块进队列,写线程顺序写进 AudioTrack。
 * [flush] 是 barge-in 的落点 —— 丢掉全部待播块并清空硬件缓冲,让 Shaheen 立刻闭嘴。
 */
private class LiveAudioPlayer {

    private val queue = Channel<ByteArray>(Channel.UNLIMITED)
    private var track: AudioTrack? = null
    private var writerJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (track != null) return
        val minBuffer = AudioTrack.getMinBufferSize(
            LiveCallConfig.OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(LiveCallConfig.OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = audioTrack
        audioTrack.play()
        writerJob = scope.launch {
            for (chunk in queue) {
                runCatching { audioTrack.write(chunk, 0, chunk.size) }
            }
        }
    }

    fun enqueue(pcm: ByteArray) {
        queue.trySend(pcm)
    }

    fun flush() {
        // 丢掉队列里全部待播块,再清硬件缓冲 —— 两步都做,Shaheen 才会立刻闭嘴。
        while (queue.tryReceive().isSuccess) {
            // 丢弃全部待播块
        }
        runCatching {
            track?.pause()
            track?.flush()
            track?.play()
        }
    }

    fun release() {
        writerJob?.cancel()
        writerJob = null
        while (queue.tryReceive().isSuccess) {
            // 丢弃
        }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }
}
