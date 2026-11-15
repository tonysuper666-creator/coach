package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.SourcePage
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * A paired client's own book: it sends the selected passage plus a few neighbouring source blocks, each with
 * its block id (`<bookHash>:p<page>:<index>`). The explanation may cite only those blocks, and the answer gives
 * those ids back, so the client opens the original text from its own copy. Nothing is stored or registered here.
 */
internal class LibraryExplainRequest(val quote: String, val pages: List<SourcePage>)

private val blockId = Regex("[A-Za-z0-9._-]{1,80}:p([0-9]{1,5}):[0-9]{1,9}")

internal fun libraryExplainRequest(input: JsonObject): LibraryExplainRequest {
    fun bad(): Nothing = throw MockError(HttpStatusCode.BadRequest, "选中追问格式不对。")
    val quote = (input["selection"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
    if (quote.isEmpty() || quote.length > 4_000) bad()
    val blocks = input["blocks"] as? JsonArray ?: bad()
    if (blocks.size !in 1..4) bad()
    val seen = HashSet<String>()
    var total = 0
    val pages = blocks.map { value ->
        val block = value as? JsonObject ?: bad()
        val id = (block["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad()
        val text = (block["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: bad()
        val match = blockId.matchEntire(id) ?: bad()
        if (text.isEmpty() || text.length > 8_000 || !seen.add(id)) bad()
        total += text.length
        SourcePage(id, match.groupValues[1].toInt(), text)
    }
    if (total > 24_000) bad()
    // The selection must come from the blocks that are sent; whitespace may differ.
    fun squash(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    if (pages.none { squash(it.text).contains(squash(quote)) }) bad()
    return LibraryExplainRequest(quote, pages)
}

fun Application.libraryExplainRoutes(actor: LedgerActor, reader: ReaderExplanation?) {
    routing {
        post("/v1/library/explain") { call.withReaderConnection {
            try {
                val input = LedgerJson.parseToJsonElement(call.receiveText()).jsonObject
                val request = libraryExplainRequest(input)
                val body = reader?.explain(call.attributes[observedDevice], "library", input, request.quote, request.pages)
                    ?: json("text" to "讲解服务暂不可用，无法确认。", "sources" to JsonArray(emptyList()), "elapsedMs" to 0)
                call.respondText(body.toString(), ContentType.Application.Json)
            } catch (e: MockError) {
                call.respondText(json("error" to e.message).toString(), ContentType.Application.Json, e.status)
            } catch (e: IllegalArgumentException) {
                call.respondText(json("error" to "选中追问格式不对。").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            } catch (e: kotlinx.serialization.SerializationException) {
                call.respondText(json("error" to "选中追问格式不对。").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        } }
        post("/v1/library/explain/cancel") {
            val input = runCatching { LedgerJson.parseToJsonElement(call.receiveText()).jsonObject }.getOrNull()
            reader?.cancel(call.attributes[observedDevice], "library", input?.str("requestId").orEmpty())
            call.respondText("{}", ContentType.Application.Json)
        }
    }
}
