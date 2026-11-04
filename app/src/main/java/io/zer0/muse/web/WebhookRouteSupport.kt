package io.zer0.muse.web

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.channel.ChannelInbox
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal suspend fun readLimitedRequestBody(call: ApplicationCall): WebhookBodyRead {
    val declaredLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    return if (declaredLength != null && declaredLength > MAX_CHANNEL_WEBHOOK_BODY_BYTES) {
        WebhookBodyRead.TooLarge
    } else {
        readWebhookBodyWithinLimit(call.receiveChannel())
    }
}

internal fun parseWebhookPayload(body: String): JsonObject? = resultOf {
    AppJson.parseToJsonElement(body).jsonObject
}.getOrNull()

internal fun recordWebhookInbound(source: ChannelInbox.Source, text: String?, rawPayload: String): Boolean? {
    return runCatching {
        ChannelInbox.record(source, text, rawPayload)
    }.onFailure { error ->
        Logger.e("ChannelWebhookRoutes", "Failed to record channel webhook inbound event", error)
    }.getOrNull()
}
