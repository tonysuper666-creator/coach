package com.coach.server

import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.utils.io.*
import kotlinx.serialization.json.*

/** Serialize only the final, source-checked route result, after authorization and friend accounting. */
internal fun Application.installReaderAnswerStream() {
    sendPipeline.intercept(ApplicationSendPipeline.Transform) { message ->
        if (call.request.path() != "/v1/library/explain" ||
            call.request.header(HttpHeaders.Accept) != "application/x-ndjson" ||
            message !is TextContent || !message.contentType.match(ContentType.Application.Json) ||
            (message.status ?: call.response.status() ?: HttpStatusCode.OK) != HttpStatusCode.OK) return@intercept
        val body = Json.parseToJsonElement(message.text).jsonObject
        val text = (body["text"] as? JsonPrimitive)?.contentOrNull ?: return@intercept
        if ((body["sources"] as? JsonArray).isNullOrEmpty() || text.isBlank()) return@intercept
        proceedWith(object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.parse("application/x-ndjson")
            override suspend fun writeTo(channel: ByteWriteChannel) {
                suspend fun frame(data: JsonObject) {
                    channel.writeStringUtf8(data.toString() + "\n")
                    channel.flush()
                }
                frame(buildJsonObject { put("meta", JsonObject(body - "text")) })
                var offset = 0
                while (offset < text.length) {
                    var end = minOf(offset + 128, text.length)
                    if (end < text.length && text[end - 1].isHighSurrogate()) end--
                    frame(buildJsonObject { put("delta", text.substring(offset, end)) })
                    offset = end
                }
                frame(buildJsonObject { put("done", true) })
            }
        })
    }
}
