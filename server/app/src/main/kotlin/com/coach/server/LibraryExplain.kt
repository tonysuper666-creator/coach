package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.SourcePage
import com.coach.store.tutorSourceAllowed
import com.coach.domain.scope.VisibleScope
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * Paired, explicitly submitted local-book text is user input, not a course-source lookup.
 * A client block id grants no access to the ledger, reader catalog or other blocks.
 * The server's ceiling is the selected text alone; neighbours (including unread pages)
 * are never made visible. Course questions use their separate, server-validated path.
 */
internal class LibraryExplainRequest private constructor(val quote: String, val pages: List<SourcePage>, val scope: VisibleScope) {
    companion object {
        fun selected(quote: String, page: SourcePage): LibraryExplainRequest {
            val scope = VisibleScope.learner().narrowToPages(page.docId, page.pdfPage..page.pdfPage)
            return LibraryExplainRequest(quote, listOf(page.copy(text = quote)), scope)
        }
    }
}

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
        if (text.isEmpty() || text.length > 8_000 || !seen.add(id) || match.groupValues[1].toInt() < 1) bad()
        total += text.length
        SourcePage(id, match.groupValues[1].toInt(), text)
    }
    if (total > 24_000) bad()
    // One local book per request. No client "scope" / visibility / course metadata is trusted.
    val books = pages.map { it.docId.substringBefore(":p") }.toSet()
    if (books.size != 1 || !tutorSourceAllowed(books.single())) bad()
    val selected = pages.firstOrNull { containsReaderSelection(it.text, quote) } ?: bad()
    return LibraryExplainRequest.selected(quote, selected)
}

fun Application.libraryExplainRoutes(actor: LedgerActor, reader: ReaderExplanation?) {
    routing {
        post("/v1/library/explain") { call.withReaderConnection {
            try {
                val input = LedgerJson.parseToJsonElement(call.receiveText()).jsonObject
                val request = libraryExplainRequest(input)
                val body = reader?.explain(call.attributes[observedDevice], "library", input, request.quote, request.pages, request.scope)
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
