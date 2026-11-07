package io.zer0.muse.livecall

/**
 * Shaheen 2 Fix Phase 2: 通话阶段状态机(纯逻辑,JVM 可测)。
 *
 * 允许的状态迁移:
 *  - IDLE → CONNECTING(用户发起通话)
 *  - CONNECTING → LIVE(setupComplete 到达)/ ERROR(连接失败)/ ENDED(连接中挂断)
 *  - LIVE → ERROR(通话中 socket 断开)/ ENDED(挂断)
 *  - ERROR → CONNECTING(Retry 真正重拨)/ ENDED(关闭错误屏)
 *  - ENDED → CONNECTING(再打一通)
 * 其余迁移一律拒绝(保持原状态),避免迟到的 socket 回调把已结束的通话“复活”。
 */
enum class LiveCallPhase {
    IDLE,
    CONNECTING,
    LIVE,
    ERROR,
    ENDED,
}

object LiveCallTransitions {

    private val allowed: Map<LiveCallPhase, Set<LiveCallPhase>> = mapOf(
        LiveCallPhase.IDLE to setOf(LiveCallPhase.CONNECTING),
        LiveCallPhase.CONNECTING to setOf(LiveCallPhase.LIVE, LiveCallPhase.ERROR, LiveCallPhase.ENDED),
        LiveCallPhase.LIVE to setOf(LiveCallPhase.ERROR, LiveCallPhase.ENDED),
        LiveCallPhase.ERROR to setOf(LiveCallPhase.CONNECTING, LiveCallPhase.ENDED, LiveCallPhase.IDLE),
        LiveCallPhase.ENDED to setOf(LiveCallPhase.CONNECTING, LiveCallPhase.IDLE),
    )

    fun isAllowed(from: LiveCallPhase, to: LiveCallPhase): Boolean =
        from == to || allowed[from]?.contains(to) == true

    /** 尝试迁移:合法则返回目标状态,非法则保持原状态(不抛异常,迟到回调安全)。 */
    fun next(from: LiveCallPhase, to: LiveCallPhase): LiveCallPhase =
        if (isAllowed(from, to)) to else from
}

/** 状态机持有者:所有阶段变更都必须过 [transitionTo],非法迁移直接忽略。 */
class LiveCallStateMachine(initial: LiveCallPhase = LiveCallPhase.IDLE) {

    var phase: LiveCallPhase = initial
        private set

    /**
     * @return 是否真的发生了迁移。
     * 同步:迁移会从 socket 线程、界面线程、看门狗协程并发到达,读-改-写必须
     * 原子化,否则「挂断」与「setupComplete」赛跑时后写者可能把已结束的通话
     * 又写成 LIVE(幽灵通话)。
     */
    @Synchronized
    fun transitionTo(target: LiveCallPhase): Boolean {
        val next = LiveCallTransitions.next(phase, target)
        val changed = next != phase
        phase = next
        return changed
    }

    /** 回到 IDLE(错误屏/结束屏被关闭后复位)。 */
    @Synchronized
    fun reset() {
        phase = LiveCallPhase.IDLE
    }
}

/**
 * setup 看门狗的纯决策(验收审计 D1):socket 已打开但 setupComplete 迟迟不来时,
 * 会话不能永远停在 CONNECTING —— 只有「仍在 CONNECTING 且已超过时限」才判失败;
 * 已接通(LIVE)/已结束的通话永不受看门狗影响。
 */
object LiveSetupWatchdog {

    fun shouldFail(
        phase: LiveCallPhase,
        elapsedMillis: Long,
        timeoutMillis: Long = LiveCallConfig.SETUP_TIMEOUT_MS,
    ): Boolean = phase == LiveCallPhase.CONNECTING && elapsedMillis >= timeoutMillis
}

/** 字幕说话人。 */
enum class LiveSpeaker {
    USER,
    SHAHEEN,
}

/** 一行字幕(转写增量会不断增长同一行的 [text],turn 结束才定稿)。 */
data class LiveTranscriptLine(
    val speaker: LiveSpeaker,
    val text: String,
    val finalized: Boolean = false,
)

/**
 * 转写累加器(纯逻辑):把服务端回传的 input/output transcription 增量折叠成行。
 *
 * 规则:同一说话人的增量追加到当前未定稿行;说话人切换或 turn 定稿时封口当前行;
 * 空白增量忽略。这样屏幕上呈现的是逐词生长的字幕行,而不是一闪而过的碎片。
 */
class LiveTranscriptAccumulator {

    private val _lines = mutableListOf<LiveTranscriptLine>()
    val lines: List<LiveTranscriptLine> get() = _lines.toList()

    fun appendDelta(speaker: LiveSpeaker, delta: String) {
        if (delta.isEmpty()) return
        val last = _lines.lastOrNull()
        if (last != null && last.speaker == speaker && !last.finalized) {
            _lines[_lines.lastIndex] = last.copy(text = last.text + delta)
        } else {
            // 说话人切换 = 上一行天然定稿,先封口再开新行(否则同一句会被增量切成多行)。
            if (last != null && !last.finalized) {
                _lines[_lines.lastIndex] = last.copy(finalized = true)
            }
            _lines += LiveTranscriptLine(speaker = speaker, text = delta)
        }
    }

    /** 定稿当前未定稿行(turnComplete / 说话人切换时调用)。 */
    fun finalizeCurrent() {
        val last = _lines.lastOrNull() ?: return
        if (!last.finalized) {
            _lines[_lines.lastIndex] = last.copy(finalized = true)
        }
    }

    fun clear() {
        _lines.clear()
    }
}

/**
 * PCM 分帧累加器(纯逻辑):AudioRecord 读到的数据长度不规整,这里攒满一帧
 * ([frameBytes])才吐出,保证发往服务端的每个音频包都是规整的 100ms 小包;
 * 不足一帧的尾巴留在缓冲里等下一读,通话结束时由 [flush] 决定丢弃(不补零造假音频)。
 */
class PcmFrameAccumulator(private val frameBytes: Int) {

    init {
        require(frameBytes > 0) { "frameBytes must be positive" }
    }

    private var pending = ByteArray(0)

    /** 喂入一段新 PCM,返回本次凑满的完整帧(可能为 0..n 个)。 */
    fun offer(data: ByteArray, length: Int = data.size): List<ByteArray> {
        if (length <= 0) return emptyList()
        val merged = ByteArray(pending.size + length)
        pending.copyInto(merged, 0)
        data.copyInto(merged, pending.size, 0, length)
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (merged.size - offset >= frameBytes) {
            frames += merged.copyOfRange(offset, offset + frameBytes)
            offset += frameBytes
        }
        pending = merged.copyOfRange(offset, merged.size)
        return frames
    }

    /** 当前未成帧的尾巴字节数。 */
    val pendingBytes: Int get() = pending.size

    /** 清空缓冲(挂断 / 静音切换时用)。 */
    fun clear() {
        pending = ByteArray(0)
    }
}
