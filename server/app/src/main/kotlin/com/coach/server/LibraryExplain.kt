package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.SourcePage
import com.coach.store.LedgerStore
import com.coach.store.LearnerSourcePage
import com.coach.store.tutorSourceAllowed
import com.coach.domain.scope.VisibleScope
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

/**
 * A local block id and its text are untrusted claims, including after pairing.
 * The book id must name an existing server-registered learner source. Its stored
 * course, classification, text and learning record authorize the selection.
 * Unregistered local books fail closed; this endpoint never creates a registration.
 */
internal class LibraryExplainRequest private constructor(val quote: String, val pages: List<SourcePage>, val scope: VisibleScope) {
    fun authorize(store: LedgerStore): AuthorizedLibrarySelection {
        val claimed = pages.single()
        val saved = store.learnerSourcePage(claimed.docId.substringBefore(":p"), claimed.pdfPage)
        if (saved == null || !containsReaderSelection(saved.page.text, quote) || !saved.inLearnedScope(store))
            throw MockError(HttpStatusCode.Conflict, "这段原文的来源或可见范围尚未核实，暂时无法讲解。")
        // The alias is for the existing local reader's citation navigation only.
        // It is minted after the real document/page passed the trusted ceiling.
        return AuthorizedLibrarySelection(quote, pages, VisibleScope.learner()
            .narrowToPages(claimed.docId, claimed.pdfPage..claimed.pdfPage), saved)
    }
    companion object {
        fun selected(quote: String, page: SourcePage): LibraryExplainRequest {
            return LibraryExplainRequest(quote, listOf(page.copy(text = quote)), VisibleScope.NONE)
        }
    }
}

internal class AuthorizedLibrarySelection(val quote: String, val pages: List<SourcePage>, val scope: VisibleScope,
    private val saved: LearnerSourcePage) {
    fun isCurrent(store: LedgerStore): Boolean =
        store.learnerSourcePage(saved.page.docId, saved.page.pdfPage) == saved && saved.inLearnedScope(store)
}

private fun LearnerSourcePage.inLearnedScope(store: LedgerStore): Boolean = store.learnedThrough(
    course, VisibleScope.learner(), store.todayProgress(course).currentKpId
).permits(page.docId, page.pdfPage, page.learnerVisible)

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
                val request = actor.execute { libraryExplainRequest(input).authorize(this) }
                val body = reader?.explain(call.attributes[observedDevice], "library", input, request.quote, request.pages, request.scope) {
                    actor.execute { request.isCurrent(this) }
                }
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
