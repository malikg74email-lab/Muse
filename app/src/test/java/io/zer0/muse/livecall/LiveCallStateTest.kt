package io.zer0.muse.livecall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 Fix Phase 2: 通话状态机 / 字幕累加 / PCM 分帧的纯逻辑测试。
 */
class LiveCallStateTest {

    // ── 状态迁移 ──────────────────────────────────────────────────────

    @Test
    fun `happy path transitions are allowed`() {
        val sm = LiveCallStateMachine()
        assertTrue(sm.transitionTo(LiveCallPhase.CONNECTING))
        assertTrue(sm.transitionTo(LiveCallPhase.LIVE))
        assertTrue(sm.transitionTo(LiveCallPhase.ENDED))
        // 再打一通
        assertTrue(sm.transitionTo(LiveCallPhase.CONNECTING))
    }

    @Test
    fun `error and retry transitions are allowed`() {
        val sm = LiveCallStateMachine()
        sm.transitionTo(LiveCallPhase.CONNECTING)
        assertTrue(sm.transitionTo(LiveCallPhase.ERROR))
        // Retry = 真正重拨
        assertTrue(sm.transitionTo(LiveCallPhase.CONNECTING))
        assertTrue(sm.transitionTo(LiveCallPhase.LIVE))
        assertTrue(sm.transitionTo(LiveCallPhase.ERROR))
        // 错误屏关闭 → 回 IDLE
        assertTrue(sm.transitionTo(LiveCallPhase.IDLE))
    }

    @Test
    fun `illegal transitions are refused so late socket callbacks cannot resurrect a call`() {
        val sm = LiveCallStateMachine()
        // IDLE 不能直达 LIVE
        assertFalse(sm.transitionTo(LiveCallPhase.LIVE))
        assertEquals(LiveCallPhase.IDLE, sm.phase)
        sm.transitionTo(LiveCallPhase.CONNECTING)
        sm.transitionTo(LiveCallPhase.LIVE)
        sm.transitionTo(LiveCallPhase.ENDED)
        // 已挂断的通话不能被迟到的 setupComplete 复活成 LIVE
        assertFalse(sm.transitionTo(LiveCallPhase.LIVE))
        assertEquals(LiveCallPhase.ENDED, sm.phase)
        // 也不能从 ENDED 直接跳 ERROR
        assertFalse(sm.transitionTo(LiveCallPhase.ERROR))
    }

    @Test
    fun `hangup during connecting is allowed`() {
        val sm = LiveCallStateMachine()
        sm.transitionTo(LiveCallPhase.CONNECTING)
        assertTrue(sm.transitionTo(LiveCallPhase.ENDED))
    }

    // ── 字幕累加 ──────────────────────────────────────────────────────

    @Test
    fun `transcript deltas grow one line per speaker turn`() {
        val acc = LiveTranscriptAccumulator()
        acc.appendDelta(LiveSpeaker.USER, "You")
        acc.appendDelta(LiveSpeaker.USER, "Tube kholo")
        acc.appendDelta(LiveSpeaker.SHAHEEN, "Ji ")
        acc.appendDelta(LiveSpeaker.SHAHEEN, "Malik")
        val lines = acc.lines
        assertEquals(2, lines.size)
        assertEquals("YouTube kholo", lines[0].text)
        assertEquals(LiveSpeaker.USER, lines[0].speaker)
        assertEquals("Ji Malik", lines[1].text)
        // 说话人一切换,上一行即被自动定稿。
        assertTrue(lines[0].finalized)
        assertFalse(lines[1].finalized)
    }

    @Test
    fun `finalize seals the current line so the next delta opens a new one`() {
        val acc = LiveTranscriptAccumulator()
        acc.appendDelta(LiveSpeaker.SHAHEEN, "Pehli baat")
        acc.finalizeCurrent()
        acc.appendDelta(LiveSpeaker.SHAHEEN, "Doosri baat")
        val lines = acc.lines
        assertEquals(2, lines.size)
        assertTrue(lines[0].finalized)
        assertEquals("Doosri baat", lines[1].text)
    }

    @Test
    fun `empty deltas never create blank lines`() {
        val acc = LiveTranscriptAccumulator()
        acc.appendDelta(LiveSpeaker.USER, "")
        assertTrue(acc.lines.isEmpty())
    }

    // ── PCM 分帧 ──────────────────────────────────────────────────────

    @Test
    fun `accumulator emits only full frames and keeps the remainder`() {
        val acc = PcmFrameAccumulator(frameBytes = 4)
        val first = acc.offer(byteArrayOf(1, 2, 3))
        assertTrue(first.isEmpty())
        assertEquals(3, acc.pendingBytes)
        val second = acc.offer(byteArrayOf(4, 5, 6, 7, 8))
        assertEquals(2, second.size)
        assertTrue(second[0].contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertTrue(second[1].contentEquals(byteArrayOf(5, 6, 7, 8)))
        assertEquals(0, acc.pendingBytes)
    }

    @Test
    fun `accumulator respects the read length parameter`() {
        val acc = PcmFrameAccumulator(frameBytes = 2)
        val frames = acc.offer(byteArrayOf(1, 2, 3, 4), length = 2)
        assertEquals(1, frames.size)
        assertTrue(frames[0].contentEquals(byteArrayOf(1, 2)))
    }

    @Test
    fun `a 100ms live frame is 3200 bytes at the configured rate`() {
        val acc = PcmFrameAccumulator(frameBytes = LiveCallConfig.INPUT_FRAME_BYTES)
        val frames = acc.offer(ByteArray(10_000))
        assertEquals(3, frames.size)
        assertTrue(frames.all { it.size == 3_200 })
        assertEquals(400, acc.pendingBytes)
    }
}
