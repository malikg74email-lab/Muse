package io.zer0.ai.core

/**
 * Builds a safe terminal event for a malformed SSE frame.
 *
 * The frame body may contain user prompts, tool arguments, or provider data, so
 * diagnostics expose only its length and keep the original throwable attached
 * for local logging/observability.
 */
internal fun malformedStreamFrameError(provider: String, data: String, throwable: Throwable?): ChatStreamEvent.Error =
    ChatStreamEvent.Error(
        message = "$provider streaming response format error (frameLength=${data.length}); this request has been terminated, please retry.",
        throwable = throwable,
    )
