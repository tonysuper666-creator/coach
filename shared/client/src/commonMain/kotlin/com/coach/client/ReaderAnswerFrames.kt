package com.coach.client

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal suspend fun readReaderAnswer(channel: ByteReadChannel, onPartial: suspend (JsonObject) -> Unit): JsonObject {
    suspend fun next() = Json.parseToJsonElement(requireNotNull(channel.readUTF8Line(131072))).jsonObject
    val meta = next()["meta"] as? JsonObject ?: error("Missing answer metadata")
    require(!(meta["sources"] as? JsonArray).isNullOrEmpty())
    val text = StringBuilder()
    fun snapshot() = JsonObject(meta + ("text" to JsonPrimitive(text.toString())))
    while (true) {
        val frame = next()
        if (frame["done"] == JsonPrimitive(true)) {
            require(text.isNotBlank())
            return snapshot()
        }
        val delta = frame["delta"] as? JsonPrimitive ?: error("Missing answer fragment")
        require(delta.isString && delta.content.isNotEmpty() && text.length + delta.content.length <= 131072)
        text.append(delta.content)
        if (text.isNotBlank()) {
            try {
                onPartial(snapshot())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The sink only paints progress. A failure there must not drop a stream that still finishes.
            }
        }
    }
}
