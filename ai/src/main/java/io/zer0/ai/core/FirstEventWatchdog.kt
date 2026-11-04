package io.zer0.ai.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R-AI-04: 深度推理/超长上下文模型放宽首事件超时,普通模型保持默认。
 *
 * v2.x: 默认 15s→45s、推理/超长上下文档 60s→90s。15s 对慢队列/冷启动模型过短 —
 * 首 token 稍慢就被误判为死流、取消流式并重发非流式请求:用户被迫等待双倍时间,
 * 且回复以“整段一次性弹出”的方式出现(用户反馈)。
 */
internal fun Model.firstEventTimeoutMs(defaultMs: Long = 45_000L): Long =
    if (supportsReasoning() || (contextWindow ?: 0) > 200_000) 90_000L else defaultMs

/**
 * B3-01: 首事件看门狗。
 *
 * SSE 连接建立后若 [timeoutMs] 内没有收到任何有效事件,自动取消上游流,
 * 改用非流式 [fallback] 重试一次,并向 UI 发出 [ChatStreamEvent.FallbackNotice]。
 *
 * 审查修复 (2.0 B-25): callbackFlow → channelFlow — callbackFlow 内部 channel 容量
 * 固定 64,快速生产/慢消费(如 UI 节流 50ms 自适应切片)时 trySend 返回 false 静默丢片,
 * 且下游 `.buffer(UNLIMITED)` 无法改变其内部容量;channelFlow 内部 channel 恒为
 * UNLIMITED,与 OpenAIProvider 的 C-35 修复同一方案。
 */
fun Flow<ChatStreamEvent>.withFirstEventWatchdog(
    timeoutMs: Long = 15_000L,
    fallback: suspend () -> ChatCompletion,
    // 审计修复 (7.8): 可选取消检查 — 调用方传入"用户是否已停止"的判断,
    // fallback 触发前检查,避免用户停止后仍发一次计费请求。
    abortCheck: () -> Boolean = { false },
): Flow<ChatStreamEvent> = channelFlow {
    val firstEventReceived = AtomicBoolean(false)
    val finished = AtomicBoolean(false)
    val fallbackStarted = AtomicBoolean(false)
    val meaningfulEventReceived = AtomicBoolean(false)
    lateinit var upstreamJob: Job

    suspend fun emitFallback(notice: String, cancelUpstream: Boolean) {
        if (abortCheck() || !fallbackStarted.compareAndSet(false, true)) return
        finished.set(true)
        if (cancelUpstream) upstreamJob.cancel()
        trySend(ChatStreamEvent.FallbackNotice(notice))
        try {
            val completion = fallback()
            completion.reasoningContent?.takeIf { it.isNotBlank() }?.let {
                trySend(ChatStreamEvent.ReasoningDelta(it))
            }
            // v1.0.90 (S-6): 已收到过流式内容时,回退的完整文本不再追加 —
            // 此前无条件 trySend(ContentDelta(completion.text)),若流式已输出部分内容
            // (如 GLM-4-9B 等模型流式行为异常,先输出片段),完整文本与之拼接,
            // 消息变成"片段 + 完整回复"(用户反馈的严重重叠)。
            // 此时只保留已显示内容,发 FallbackNotice 提示(UI 可提示用户重试)。
            if (completion.text.isNotBlank() && !meaningfulEventReceived.get()) {
                trySend(ChatStreamEvent.ContentDelta(completion.text))
            } else if (completion.text.isNotBlank()) {
                trySend(
                    ChatStreamEvent.Error(
                        "Streaming produced content but did not finish; the current content has been kept (generation can be retried)",
                        null,
                    ),
                )
            }
            if (completion.citationUrls.isNotEmpty()) {
                trySend(ChatStreamEvent.CitationDelta(completion.citationUrls))
            }
            completion.toolCalls?.forEachIndexed { index, tc ->
                trySend(
                    ChatStreamEvent.ToolCallDelta(
                        index = index,
                        id = tc.id,
                        name = tc.name,
                        argumentsDelta = tc.arguments,
                    ),
                )
            }
            trySend(ChatStreamEvent.Done(completion.finishReason))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            close(cancelled)
            throw cancelled
        } catch (t: Throwable) {
            trySend(ChatStreamEvent.Error(t.message ?: "Non-streaming fallback failed", t))
        } finally {
            close()
        }
    }

    upstreamJob = launch {
        try {
            collect { event ->
                if (finished.get()) return@collect
                if (!firstEventReceived.get() &&
                    event !is ChatStreamEvent.Error &&
                    event !is ChatStreamEvent.StreamInterrupted
                ) {
                    firstEventReceived.set(true)
                }
                when (event) {
                    is ChatStreamEvent.ContentDelta -> {
                        if (event.delta.isNotEmpty()) meaningfulEventReceived.set(true)
                        trySend(event)
                    }
                    is ChatStreamEvent.ReasoningDelta -> {
                        if (event.delta.isNotEmpty()) meaningfulEventReceived.set(true)
                        trySend(event)
                    }
                    is ChatStreamEvent.ImageDelta,
                    is ChatStreamEvent.ToolCallDelta,
                    is ChatStreamEvent.CitationDelta,
                    -> {
                        meaningfulEventReceived.set(true)
                        trySend(event)
                    }
                    is ChatStreamEvent.Done -> {
                        // 部分 OpenAI 兼容中转会建立 SSE 后只返回一个空 Done。
                        // 这不是可展示的正常回复,立即走一次非流式请求,避免用户看到空消息并长时间等待。
                        if (!meaningfulEventReceived.get()) {
                            emitFallback("Streaming response was empty, switching the request method", cancelUpstream = false)
                        } else {
                            trySend(event)
                            finished.set(true)
                            close()
                        }
                    }
                    is ChatStreamEvent.Error,
                    is ChatStreamEvent.StreamInterrupted,
                    is ChatStreamEvent.FallbackNotice,
                    is ChatStreamEvent.UsageDelta,
                    -> {
                        trySend(event)
                        if (event is ChatStreamEvent.Error ||
                            event is ChatStreamEvent.StreamInterrupted
                        ) {
                            finished.set(true)
                            close()
                        }
                    }
                }
            }
            // 上游流“无任何事件即结束”（如空流 / SSE 立即断开）已经确认不会再产生内容，
            // 不应继续等待 45s/90s 的首事件看门狗。立即复用非流式回退，避免用户看到长时间
            // loading；仍保持打开的 SSE 不会进入这里，而用户停止/显式错误路径会先把 finished 置 true。
            if (!meaningfulEventReceived.get() && !finished.get()) {
                emitFallback("Streaming response was empty, switching the request method", cancelUpstream = false)
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                // 取消 / abort：标记结束并向上传播，不把取消当成 stream failed，
                // 也阻止 watchdog 在取消后继续触发回退。
                finished.set(true)
                throw t
            }
            if (!finished.get()) {
                finished.set(true)
                trySend(ChatStreamEvent.Error(t.message ?: "stream failed", t))
                close()
            }
        }
    }

    val watchdogJob = launch {
        delay(timeoutMs)
        if (!firstEventReceived.get() && !finished.get()) {
            // 审计修复 (7.8): 用户已停止时不再发 fallback,省一次计费请求。
            emitFallback("Network is slow, switched the request method", cancelUpstream = true)
        }
    }

    awaitClose {
        upstreamJob.cancel()
        watchdogJob.cancel()
    }
}
