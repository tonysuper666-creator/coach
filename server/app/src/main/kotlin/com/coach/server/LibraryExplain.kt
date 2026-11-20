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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.utils.io.readAvailable

/**
 * A local block id and its text are untrusted claims, including after pairing.
 * A file hash needs an explicit private-catalog binding to a learner source. Its stored
 * course, classification, text and learning record authorize the selection.
 * Unregistered local books fail closed. Explicit device-owned import receipts below
 * are separate from this course authorization; they cannot create learned permissions.
 */
internal class LibraryExplainRequest private constructor(
    val quote: String,
    val pages: List<SourcePage>,
    val scope: VisibleScope,
    private val claimedBlock: String,
    val bookPages: Int? = null,
    val isGuide: Boolean = false,
    val isStructure: Boolean = false,
    val hasSectionContext: Boolean = false,
) {
    val isLesson: Boolean get() = bookPages != null
    val isSectionScoped: Boolean get() = isLesson || isGuide || isStructure || hasSectionContext
    val bookId: String get() = pages.first().docId.substringBefore(":p")
    val isLocalBook: Boolean get() = Regex("[0-9a-f]{64}").matches(bookId)
    fun authorize(store: LedgerStore, binding: ReaderDocument? = null): AuthorizedLibrarySelection {
        if (isSectionScoped) throw MockError(HttpStatusCode.Conflict, "整节讲解只支持个人导入的书。")
        val claimed = pages.single()
        if (isLocalBook && (binding == null || binding.version != bookId || binding.explainSourceId == null || claimed.pdfPage !in 1..binding.pages))
            throw MockError(HttpStatusCode.Conflict, "这段原文的来源或可见范围尚未核实，暂时无法讲解。")
        val saved = store.learnerSourcePage(binding?.explainSourceId ?: bookId, claimed.pdfPage)
        if (saved == null || (binding != null && saved.course != binding.course) ||
            (binding?.savedPage != null && (saved.page.text != binding.savedPage || !matchesSavedBlock(binding.savedPage, claimed.docId, claimedBlock))) ||
            !(containsReaderSelection(saved.page.text, quote) ||
                (isLocalBook && containsBoundBookSelection(saved.page.text, quote))) || !saved.inLearnedScope(store))
            throw MockError(HttpStatusCode.Conflict, "这段原文的来源或可见范围尚未核实，暂时无法讲解。")
        // The alias is for the existing local reader's citation navigation only.
        // It is minted after the real document/page passed the trusted ceiling.
        return AuthorizedLibrarySelection(quote, pages, VisibleScope.learner()
            .narrowToPages(claimed.docId, claimed.pdfPage..claimed.pdfPage), saved)
    }
    companion object {
        fun selected(quote: String, page: SourcePage): LibraryExplainRequest {
            return LibraryExplainRequest(quote, listOf(page.copy(text = quote)), VisibleScope.NONE, page.text)
        }
        fun selectedInSection(quote: String, pages: List<SourcePage>): LibraryExplainRequest {
            return LibraryExplainRequest(quote, pages, VisibleScope.NONE, "", hasSectionContext = true)
        }
        fun lesson(pages: List<SourcePage>, bookPages: Int, guide: Boolean = false) =
            LibraryExplainRequest("", pages, VisibleScope.NONE, "", bookPages, isGuide = guide)
        fun structure(pages: List<SourcePage>, bookPages: Int) =
            LibraryExplainRequest("", pages, VisibleScope.NONE, "", bookPages, isStructure = true)
    }
}

/** The importer's page text is never re-extracted or normalized: offsets are Java/Kotlin UTF-16 indices. */
private fun matchesSavedBlock(page: String, id: String, block: String): Boolean {
    val start = id.substringAfterLast(':').toIntOrNull() ?: return false
    return start >= 0 && start <= page.length && block.length <= page.length - start &&
        page.regionMatches(start, block, 0, block.length)
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
private val lessonBlockId = Regex("[a-f0-9]{64}:p([0-9]{1,6}):[0-9]{1,9}")

internal fun libraryExplainRequest(input: JsonObject): LibraryExplainRequest {
    fun bad(): Nothing = throw MockError(HttpStatusCode.BadRequest, "选中追问格式不对。")
    val kind = input.str("kind")
    val guide = kind == "guide"
    val structure = kind == "structure"
    val lesson = kind == "lesson" || guide || structure
    val hasSectionContext = input["sectionContext"] is JsonObject
    val sectionScoped = lesson || hasSectionContext
    fun tooLong(): Nothing = throw MockError(HttpStatusCode.PayloadTooLarge, "这一节太长，先选一段问。")
    val quote = (input["selection"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
    if (!lesson && (quote.isEmpty() || quote.length > 4_000)) bad()
    val bookPages = if (lesson) input.num("bookPages").takeIf { it in 1L..100_000L }?.toInt() ?: bad() else null
    val blocks = input["blocks"] as? JsonArray ?: bad()
    if (sectionScoped && blocks.size > 128) tooLong()
    if (blocks.isEmpty() || (!sectionScoped && blocks.size > 4)) bad()
    val seen = HashSet<String>()
    var total = 0
    val pages = blocks.map { value ->
        val block = value as? JsonObject ?: bad()
        val id = (block["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad()
        val raw = (block["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad()
        val text = if (sectionScoped) raw else raw.trim()
        val match = (if (sectionScoped) lessonBlockId else blockId).matchEntire(id) ?: bad()
        if (sectionScoped && text.length > 8_000) tooLong()
        val page = match.groupValues[1].toInt()
        if (text.isBlank() || text.length > 8_000 || !seen.add(id) || page < 1 || (bookPages != null && page > bookPages)) bad()
        total += text.length
        SourcePage(id, match.groupValues[1].toInt(), text)
    }
    if (total > 24_000) { if (sectionScoped) tooLong() else bad() }
    // One local book per request. No client "scope" / visibility / course metadata is trusted.
    val books = pages.map { it.docId.substringBefore(":p") }.toSet()
    if (books.size != 1 || !tutorSourceAllowed(books.single())) bad()
    if (lesson) {
        if (!books.single().matches(Regex("[0-9a-f]{64}"))) bad()
        return if (structure) LibraryExplainRequest.structure(pages, checkNotNull(bookPages))
        else LibraryExplainRequest.lesson(pages, checkNotNull(bookPages), guide = guide)
    }
    val selected = pages.firstOrNull { containsReaderSelection(it.text, quote) } ?: bad()
    if (hasSectionContext) {
        if (!books.single().matches(Regex("[0-9a-f]{64}"))) bad()
        return LibraryExplainRequest.selectedInSection(quote, pages)
    }
    return LibraryExplainRequest.selected(quote, selected)
}

fun Application.libraryExplainRoutes(actor: LedgerActor, reader: ReaderExplanation?, library: ReaderLibrary? = null,
    imports: ImportedBookSources? = null, friends: FriendAccess? = null) {
    routing {
        post("/v1/library/explain") { call.withReaderConnection {
            val friend = call.attributes.getOrNull(observedFriend)
            val diagnostics = friend?.let { FriendReaderDiagnostics() }
            var requestId = ""
            try {
                val channel = call.receiveChannel()
                val bytes = ByteArray(131_073)
                var size = 0
                while (size < bytes.size) {
                    val n = channel.readAvailable(bytes, size, bytes.size - size)
                    if (n < 0) break
                    size += n
                }
                if (size > 131_072) throw MockError(HttpStatusCode.PayloadTooLarge, "选中的原文过长，请缩短后重试。")
                val input = LedgerJson.parseToJsonElement(bytes.decodeToString(0, size)).jsonObject
                val parsed = libraryExplainRequest(input)
                requestId = input.str("requestId")
                if (friend != null && (!parsed.isLocalBook || input["localSource"] == null))
                    throw MockError(HttpStatusCode.Forbidden, "请先导入自己的书，再选择原文讲解。")
                val importStore = if (friend == null) imports else (friends ?: throw FriendServiceError()).imports(friend)
                val binding = if (friend == null && parsed.isLocalBook && !parsed.isSectionScoped) library?.explanationSource(parsed.bookId, parsed.pages.single().pdfPage) else null
                val personal = input["localSource"] != null && parsed.isLocalBook && (friend != null || withContext(Dispatchers.IO) {
                    // Unreadable catalogs fail closed, including while generating.
                    runCatching { library?.reservesVersion(parsed.bookId) != true }.getOrDefault(false)
                }
                )
                val receipt = if (personal) withContext(Dispatchers.IO) {
                    (importStore ?: throw MockError(HttpStatusCode.ServiceUnavailable, "服务还未接通新书讲解，请更新服务后重试。"))
                        .register(call.attributes[observedDevice], input, parsed)
                } else null
                val request = if (receipt == null) actor.execute { parsed.authorize(this, binding) } else null
                val scope = request?.scope ?: VisibleScope.learner().narrowToDocs(*parsed.pages.map { it.docId }.toTypedArray())
                    .limitPages(parsed.pages.associate { it.docId to it.pdfPage..it.pdfPage })
                if (friend != null && reader == null) throw FriendServiceError()
                val current: suspend () -> Boolean = {
                    if (receipt != null) withContext(Dispatchers.IO) {
                        importStore!!.isCurrent(receipt) && (if (friend != null) actor.execute {
                            authorize(call.attributes[observedDevice], call.request.header("Authorization")!!.removePrefix("Bearer ")) &&
                                friendName(call.attributes[observedDevice]) == friend
                        } else runCatching { library?.reservesVersion(parsed.bookId) != true }.getOrDefault(false))
                    } else (binding == null || library?.explanationSource(parsed.bookId, parsed.pages.single().pdfPage) == binding) &&
                        actor.execute { request!!.isCurrent(this) }
                }
                val body = (if (parsed.isLesson) reader?.explainLesson(call.attributes[observedDevice], input, parsed.pages, scope,
                    friend?.let(::friendId), diagnostics, current)
                else reader?.explain(call.attributes[observedDevice], "library", input, parsed.quote, parsed.pages, scope,
                    friend?.let(::friendId), diagnostics, current))
                    ?: json("text" to "讲解服务暂不可用，无法确认。", "sources" to JsonArray(emptyList()), "elapsedMs" to 0)
                if (friend != null) withContext(Dispatchers.IO) {
                    try { friends!!.record(friend, call.attributes[observedDevice], requestId, "complete", body, diagnostics) }
                    catch (_: Exception) { throw FriendServiceError() }
                }
                call.respondText(body.toString(), ContentType.Application.Json)
            } catch (e: FriendServiceError) {
                if (friend != null) withContext(Dispatchers.IO) {
                    runCatching { friends?.record(friend, call.attributes[observedDevice], requestId, e.code, diagnostics = diagnostics) }
                }
                call.respondText(e.body.toString(), ContentType.Application.Json, e.status)
            } catch (e: MockError) {
                call.respondText(json("error" to e.message).toString(), ContentType.Application.Json, e.status)
            } catch (e: IllegalArgumentException) {
                call.respondText(json("error" to "选中追问格式不对。").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            } catch (e: kotlinx.serialization.SerializationException) {
                call.respondText(json("error" to "选中追问格式不对。").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        } }
        post("/v1/library/explain/cancel") {
            val bytes = ByteArray(1025)
            val channel = call.receiveChannel()
            var size = 0
            while (size < bytes.size) {
                val count = channel.readAvailable(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            if (size > 1024) return@post call.respond(HttpStatusCode.PayloadTooLarge)
            val input = runCatching { LedgerJson.parseToJsonElement(bytes.decodeToString(0, size)).jsonObject }.getOrNull()
            reader?.cancel(call.attributes[observedDevice], "library", input?.str("requestId").orEmpty())
            call.respondText("{}", ContentType.Application.Json)
        }
    }
}
