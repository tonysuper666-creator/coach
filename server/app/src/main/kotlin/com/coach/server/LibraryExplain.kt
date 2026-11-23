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

fun Application.libraryExplainRoutes(
    actor: LedgerActor,
    reader: ReaderExplanation?,
    library: ReaderLibrary? = null,
    imports: ImportedBookSources? = null,
    friends: FriendAccess? = null,
    companionPipeline: SectionConcurrentCompanionPipeline? = null,
    tokenLedger: BookTokenLedger? = null,
    courseAlignPipeline: CourseAlignmentPipeline? = null,
    courseTopicPipeline: CourseTopicSynthesisPipeline? = null,
    courseVisionService: CourseVisionService? = null,
    unifiedBooks: UnifiedBookPipeline? = null,
) {
    val companionJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
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
                val device = call.attributes[observedDevice]
                val unifiedScope = if (unifiedBooks?.enabled == true) UnifiedBookScope.fromCall(device, friend) else null
                val unifiedAuthorized = unifiedScope != null && parsed.isLocalBook && withContext(Dispatchers.IO) {
                    runCatching { unifiedBooks!!.hasVerifiedShelfBook(unifiedScope, parsed.bookId) }.getOrDefault(false)
                }
                val personal = input["localSource"] != null && parsed.isLocalBook && (friend != null || unifiedAuthorized || withContext(Dispatchers.IO) {
                    // Unreadable catalogs fail closed, including while generating.
                    runCatching { library?.reservesVersion(parsed.bookId) != true }.getOrDefault(false)
                }
                )
                val receipt = if (personal) withContext(Dispatchers.IO) {
                    (importStore ?: throw MockError(HttpStatusCode.ServiceUnavailable, "服务还未接通新书讲解，请更新服务后重试。"))
                        .register(device, input, parsed)
                } else null
                val binding = if (receipt == null && friend == null && parsed.isLocalBook && !parsed.isSectionScoped)
                    library?.explanationSource(parsed.bookId, parsed.pages.single().pdfPage) else null
                val request = if (receipt == null) actor.execute { parsed.authorize(this, binding) } else null
                val scope = request?.scope ?: VisibleScope.learner().narrowToDocs(*parsed.pages.map { it.docId }.toTypedArray())
                    .limitPages(parsed.pages.associate { it.docId to it.pdfPage..it.pdfPage })
                if (friend != null && reader == null) throw FriendServiceError()
                val current: suspend () -> Boolean = {
                    if (receipt != null) withContext(Dispatchers.IO) {
                        importStore!!.isCurrent(receipt) && (if (friend != null) actor.execute {
                            authorize(device, call.request.header("Authorization")!!.removePrefix("Bearer ")) &&
                                friendName(device) == friend
                        } && (!unifiedAuthorized || runCatching { unifiedBooks!!.hasVerifiedShelfBook(unifiedScope, parsed.bookId) }.getOrDefault(false))
                        else if (unifiedAuthorized) runCatching { unifiedBooks!!.hasVerifiedShelfBook(unifiedScope, parsed.bookId) }.getOrDefault(false)
                        else runCatching { library?.reservesVersion(parsed.bookId) != true }.getOrDefault(false))
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
        post("/v1/library/companion/chapter") { call.withReaderConnection {
            val friend = call.attributes.getOrNull(observedFriend)
            val device = call.attributes[observedDevice]
            val bearer = call.request.header("Authorization")?.removePrefix("Bearer ").orEmpty()
            val diagnostics = friend?.let { FriendReaderDiagnostics() }
            var requestId = ""
            val pipeline = companionPipeline
                ?: return@withReaderConnection call.respondText(
                    json("error" to "章节伴读管线暂未开启，请稍后重试。").toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
            try {
                val channel = call.receiveChannel()
                val bytes = ByteArray(524_289)
                var size = 0
                while (size < bytes.size) {
                    val n = channel.readAvailable(bytes, size, bytes.size - size)
                    if (n < 0) break
                    size += n
                }
                if (size > 524_288) throw MockError(HttpStatusCode.PayloadTooLarge, "章节请求体过大，请按节拆开后重试。")
                val input = LedgerJson.parseToJsonElement(bytes.decodeToString(0, size)).jsonObject
                requestId = (input["requestId"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                val (parsedReq, bookPages) = parseCompanionChapterRequest(input)

                // Fail closed identically to /v1/library/explain: require personal localSource receipt,
                // verify book ownership / non-catalog status, register verified blocks, and check isCurrent.
                if (friend != null && input["localSource"] == null) {
                    throw MockError(HttpStatusCode.Forbidden, "请先导入自己的书，再生成章节伴读。")
                }
                if (friend == null && input["localSource"] == null) {
                    throw MockError(HttpStatusCode.Conflict, "整章伴读只支持个人导入的书，请携带有效导入回执。")
                }
                val importStore = if (friend == null) imports else (friends ?: throw FriendServiceError()).imports(friend)
                val unifiedScope = if (unifiedBooks?.enabled == true) UnifiedBookScope.fromCall(device, friend) else null
                val unifiedAuthorized = unifiedScope != null && withContext(Dispatchers.IO) {
                    runCatching { unifiedBooks!!.hasVerifiedShelfBook(unifiedScope, parsedReq.bookId) }.getOrDefault(false)
                }
                if (friend == null) {
                    val allowedPersonal = unifiedAuthorized || withContext(Dispatchers.IO) {
                        runCatching { library?.reservesVersion(parsedReq.bookId) != true }.getOrDefault(false)
                    }
                    if (!allowedPersonal) {
                        throw MockError(HttpStatusCode.Conflict, "这本书的来源或可见范围尚未核实，暂时无法生成伴读。")
                    }
                } else {
                    val quota = withContext(Dispatchers.IO) { friends!!.status(friend, device) }
                    val callsRem = quota["callsRemaining"]?.jsonPrimitive?.longOrNull
                    val tokensRem = quota["tokensRemaining"]?.jsonPrimitive?.longOrNull
                    if ((callsRem != null && callsRem <= 0L) || (tokensRem != null && tokensRem <= 0L)) {
                        throw FriendServiceError("daily_quota")
                    }
                }
                val resolvedImports = importStore
                    ?: throw MockError(HttpStatusCode.ServiceUnavailable, "服务还未接通新书伴读，请更新服务后重试。")
                val receipt = withContext(Dispatchers.IO) {
                    resolvedImports.registerCompanion(device, input, parsedReq, bookPages)
                }

                // Rebind sections strictly to receipt-verified blocks (never untrusted rawPages).
                val receiptBlocksByKey = receipt.blocks.associateBy { Triple(it.page, it.start, it.text) }
                val verifiedReq = parsedReq.copy(
                    sections = parsedReq.sections.map { sec ->
                        sec.copy(
                            blocks = sec.blocks.map { blk ->
                                val verified = receiptBlocksByKey[Triple(blk.pdfPage, blk.startUtf16, blk.text)]
                                    ?: throw MockError(HttpStatusCode.Conflict, "这本书的保存页已变化，请重新导入并核对原文。")
                                SectionBlockInput(
                                    id = "${parsedReq.bookId}:p${verified.page}:${verified.start}",
                                    pdfPage = verified.page,
                                    startUtf16 = verified.start,
                                    text = verified.text,
                                )
                            },
                        )
                    },
                    rawPages = emptyMap(),
                )
                val current: suspend () -> Boolean = {
                    withContext(Dispatchers.IO) {
                        resolvedImports.isCurrent(receipt) && (if (friend != null) actor.execute {
                            authorize(device, bearer) && friendName(device) == friend
                        } && (!unifiedAuthorized || runCatching { unifiedBooks!!.hasVerifiedShelfBook(unifiedScope, parsedReq.bookId) }.getOrDefault(false))
                        else if (unifiedAuthorized) runCatching { unifiedBooks!!.hasVerifiedShelfBook(unifiedScope, parsedReq.bookId) }.getOrDefault(false)
                        else runCatching { library?.reservesVersion(parsedReq.bookId) != true }.getOrDefault(false))
                    }
                }
                if (!current()) {
                    throw MockError(HttpStatusCode.Conflict, "这本书的保存页或设备授权已失效，无法生成伴读。")
                }
                val cacheScope = friend?.let { "friend:${friendId(it)}" } ?: "owner:$device"
                val bypassCache = (input["bypassCache"] as? JsonPrimitive)?.booleanOrNull == true
                val pack = pipeline.generateChapter(
                    verifiedReq,
                    bypassCache = bypassCache,
                    cacheScope = cacheScope,
                    currentCheck = current,
                )
                val body = ChapterCompanionCodec.encode(pack)
                if (friend != null) withContext(Dispatchers.IO) {
                    try { friends!!.record(friend, device, requestId, "complete", body, diagnostics) }
                    catch (_: Exception) { throw FriendServiceError() }
                }
                call.respondText(body.toString(), ContentType.Application.Json)
            } catch (e: CompanionRevokedException) {
                if (friend != null) withContext(Dispatchers.IO) {
                    runCatching { friends?.record(friend, device, requestId, "revoked", diagnostics = diagnostics) }
                }
                call.respondText(json("error" to "这本书的保存页或设备授权已失效，已撤回本次生成。").toString(), ContentType.Application.Json, HttpStatusCode.Conflict)
            } catch (e: FriendServiceError) {
                if (friend != null) withContext(Dispatchers.IO) {
                    runCatching { friends?.record(friend, device, requestId, e.code, diagnostics = diagnostics) }
                }
                call.respondText(e.body.toString(), ContentType.Application.Json, e.status)
            } catch (e: MockError) {
                call.respondText(json("error" to e.message).toString(), ContentType.Application.Json, e.status)
            } catch (e: IllegalArgumentException) {
                call.respondText(json("error" to (e.message ?: "章节伴读请求格式不对。")).toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            } catch (_: kotlinx.serialization.SerializationException) {
                call.respondText(json("error" to "章节伴读请求格式不对。").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            } catch (_: Exception) {
                call.respondText(json("error" to "章节伴读生成暂时不可用，请稍后重试。").toString(), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            }
        } }
        get("/v1/library/companion/ledger") {
            if (call.attributes.getOrNull(observedFriend) != null) {
                return@get call.respondText(json("error" to "无权读取全站账本。").toString(), ContentType.Application.Json, HttpStatusCode.Forbidden)
            }
            val ledger = tokenLedger
                ?: return@get call.respondText(json("error" to "账本尚未初始化。").toString(), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            val bookId = call.request.queryParameters["bookId"]?.trim().orEmpty()
            if (!Regex("[0-9a-f]{64}|[A-Za-z0-9._-]{1,80}").matches(bookId)) {
                return@get call.respondText(json("error" to "缺少有效的书籍编号。").toString(), ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
            call.respondText(ledger.bookSummary(bookId).toJson().toString(), ContentType.Application.Json)
        }
        courseRoutes(
            actor = actor,
            library = library,
            friends = friends,
            alignPipeline = courseAlignPipeline,
            topicPipeline = courseTopicPipeline,
            visionService = courseVisionService,
        )
    }
}

private fun parseCompanionChapterRequest(input: JsonObject): Pair<ChapterCompanionRequest, Int?> {
    val bookId = input.str("bookId").trim()
    val chapterId = input.str("chapterId").trim()
    val chapterTitle = input.str("chapterTitle").trim()
    require(Regex("[0-9a-f]{64}").matches(bookId) && tutorSourceAllowed(bookId)) { "书籍编号格式无效。" }
    require(chapterId.length in 1..120 && chapterTitle.length in 1..300) { "章节信息不完整。" }
    val bookPages = (input["bookPages"] as? JsonPrimitive)?.intOrNull?.also {
        require(it in 1..100_000) { "书籍总页数无效。" }
    }
    val rawSections = input["sections"] as? JsonArray ?: throw IllegalArgumentException("缺少章节小节内容。")
    require(rawSections.size in 1..48) { "章节小节数量超出上限。" }
    val seenBlockIds = HashSet<String>()
    val sections = rawSections.map { el ->
        val obj = el as? JsonObject ?: throw IllegalArgumentException("小节格式不对。")
        val sid = obj.str("sectionId").trim()
        val title = obj.str("title").trim()
        val isEx = (obj["isExerciseSection"] as? JsonPrimitive)?.booleanOrNull ?: false
        require(sid.length in 1..120 && title.length in 1..300) { "小节标题格式不对。" }
        val blocksArr = obj["blocks"] as? JsonArray ?: throw IllegalArgumentException("小节缺少文本块。")
        require(blocksArr.isNotEmpty()) { "小节缺少文本块。" }
        val blocks = blocksArr.map { bEl ->
            val bObj = bEl as? JsonObject ?: throw IllegalArgumentException("文本块格式不对。")
            val id = bObj.str("id")
            val match = lessonBlockId.matchEntire(id) ?: throw IllegalArgumentException("文本块编号格式不对。")
            require(id.substringBefore(":p") == bookId) { "文本块与书籍编号不一致。" }
            require(seenBlockIds.add(id)) { "文本块编号重复。" }
            val idPage = match.groupValues[1].toInt()
            val idStart = id.substringAfterLast(':').toIntOrNull() ?: throw IllegalArgumentException("文本块偏移量无效。")
            val page = (bObj["pdfPage"] as? JsonPrimitive)?.intOrNull ?: idPage
            val start = (bObj["startUtf16"] as? JsonPrimitive)?.intOrNull ?: idStart
            require(page == idPage && start == idStart) { "文本块页码或偏移量与编号不一致。" }
            val text = bObj.str("text")
            require(page in 1..100_000 && (bookPages == null || page <= bookPages) && start >= 0 && text.isNotBlank() && text.length <= 24_000) { "文本块内容无效。" }
            SectionBlockInput(id = id, pdfPage = page, startUtf16 = start, text = text)
        }
        SectionBatchInput(sectionId = sid, title = title, isExerciseSection = isEx, blocks = blocks)
    }
    return ChapterCompanionRequest(
        bookId = bookId,
        chapterId = chapterId,
        chapterTitle = chapterTitle,
        sections = sections,
    ) to bookPages
}
