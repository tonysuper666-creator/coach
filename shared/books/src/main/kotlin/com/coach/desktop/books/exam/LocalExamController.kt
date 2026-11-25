package com.coach.desktop.books.exam

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import com.coach.desktop.books.*
import com.coach.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.math.BigDecimal
import kotlin.math.roundToLong

internal data class CourseMockHost(
    val generateCourseMock: suspend (JsonObject) -> CourseServerCallResult,
    val gradeAndDiagnose: suspend (JsonObject) -> PracticeGradeDiagnoseResult,
)

internal val LocalCourseMockHost = staticCompositionLocalOf<CourseMockHost?> { null }

internal data class LocalExamUi(
    val book: Textbook? = null,
    val reference: LocalPaperAvailability? = null,
    val courseId: String? = null,
    val courseTitle: String? = null,
    val papers: List<JsonObject> = emptyList(),
    val active: JsonObject? = null,
    val history: List<JsonObject> = emptyList(),
    val attempt: JsonObject? = null,
    val source: JsonObject? = null,
    val busy: Boolean = false,
    val saving: Boolean = false,
    val gradingInFlight: Boolean = false,
    val generatingCourseMock: Boolean = false,
    val courseGenerationFailed: Boolean = false,
    val gradingError: String? = null,
    val error: String? = null,
    val conflict: Boolean = false,
    val nowMillis: Long = 0,
)

/** Window-owned accepted writes survive course/tab detours and participate in the existing close guard. */
internal class LocalExamController(
    private val library: TextbookLibrary,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val store = LocalExamStore(library, clock = clock)
    private val mutable = MutableStateFlow(LocalExamUi())
    val state = mutable.asStateFlow()
    private val actions = Mutex()
    private data class Key(val book: String, val attempt: String)
    private data class Draft(val answers: JsonObject, val sequence: Long)
    private val queueLock = Any()
    private val pending = linkedMapOf<Key, Draft>()
    private var writer: Job? = null

    init {
        // Window-owned, so opening a source or returning to the course cannot pause the timer.
        scope.launch { while (isActive) { tick(); delay(250) } }
    }

    private fun scopeKeyFor(ui: LocalExamUi): String? = when {
        !ui.courseId.isNullOrBlank() -> courseMockScopeId(ui.courseId)
        ui.book != null -> ui.book.id
        else -> null
    }

    fun hasActiveCourseMock(courseId: String): Boolean =
        store.hasActive(courseMockScopeId(courseId))

    suspend fun tick() {
        val active = mutable.value.active ?: return
        if (active.str("timing") != "TIMED") return
        val now = clock()
        mutable.update { it.copy(nowMillis = now) }
        if (now < active.num("deadline") || !flush()) return
        action(showBusy = false) {
            val ui = mutable.value
            val scopeKey = scopeKeyFor(ui) ?: return@action
            if (ui.active?.str("mockId") != active.str("mockId")) return@action
            store.submit(scopeKey, active.str("mockId"), auto = true)
            refreshScope(scopeKey, ui.book, ui.courseId, ui.courseTitle, ui.attempt?.str("mockId"))
        }
    }

    private suspend fun action(showBusy: Boolean = true, block: suspend () -> Unit): Boolean = actions.withLock {
        mutable.update { it.copy(busy = showBusy, error = null, courseGenerationFailed = false) }
        try {
            withContext(Dispatchers.IO) { block() }
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            mutable.update {
                it.copy(
                    error = if (failure is java.sql.SQLException) {
                        "作答还没保存，请稍后重试。当前内容仍在本页。"
                    } else {
                        failure.message ?: "练习暂时无法保存，请保留本页内容后重试。"
                    },
                )
            }
            false
        } finally {
            mutable.update { it.copy(busy = false) }
        }
    }

    private fun refresh(book: Textbook, opened: String? = null) {
        mutable.update {
            it.copy(
                book = book,
                courseId = null,
                courseTitle = null,
                reference = if (it.book?.id == book.id) it.reference else null,
                papers = store.papers(book.id),
                active = store.active(book.id),
                history = store.history(book.id),
                attempt = opened?.let { id -> store.get(book.id, id) },
                source = null,
                gradingError = null,
                nowMillis = clock(),
            )
        }
    }

    private fun refreshCourse(courseId: String, courseTitle: String, opened: String? = null) {
        val scopeKey = courseMockScopeId(courseId)
        mutable.update {
            it.copy(
                book = null,
                reference = null,
                courseId = courseId,
                courseTitle = courseTitle,
                papers = store.papers(scopeKey),
                active = store.active(scopeKey),
                history = store.history(scopeKey),
                attempt = opened?.let { id -> store.get(scopeKey, id) },
                source = null,
                gradingError = null,
                nowMillis = clock(),
            )
        }
    }

    private fun refreshScope(
        scopeKey: String,
        book: Textbook?,
        courseId: String?,
        courseTitle: String?,
        opened: String? = null,
    ) {
        if (!courseId.isNullOrBlank()) {
            refreshCourse(courseId, courseTitle.orEmpty(), opened)
        } else if (book != null) {
            refresh(book, opened)
        } else {
            mutable.update {
                it.copy(
                    papers = store.papers(scopeKey),
                    active = store.active(scopeKey),
                    history = store.history(scopeKey),
                    attempt = opened?.let { id -> store.get(scopeKey, id) },
                    source = null,
                    nowMillis = clock(),
                )
            }
        }
    }

    suspend fun load(book: Textbook, prepareReference: Boolean = true) {
        val current = mutable.value
        if (current.courseId == null && current.book?.id == book.id && (!prepareReference || current.reference != null)) return
        if (flush()) action {
            // Restore active attempts on every tab so the timer and autosave still work.
            val ui = mutable.value
            if (ui.courseId != null || ui.book?.id != book.id) refresh(book)
            if (prepareReference && mutable.value.reference == null) {
                val reference = textbookReferencePaper(book)
                mutable.update { it.copy(reference = reference) }
            }
        }
    }

    suspend fun loadCourse(courseId: String, courseTitle: String) {
        val current = mutable.value
        if (current.courseId == courseId && current.courseTitle == courseTitle) {
            action(showBusy = false) {
                refreshCourse(courseId, courseTitle, current.attempt?.str("mockId"))
            }
            return
        }
        if (flush()) action {
            refreshCourse(courseId, courseTitle)
        }
    }

    suspend fun generateAndStart() {
        if (!flush()) return
        action {
            val book = checkNotNull(mutable.value.book)
            val paper = mutable.value.reference?.paper ?: error("这本教材还没有可用练习。")
            store.register(paper)
            val opened = store.start(book.id, paper.id)
            refresh(book, opened.str("mockId"))
        }
    }

    suspend fun generateAndStartCourseMock(
        detail: CourseDetailSnapshot,
        generator: suspend (JsonObject) -> CourseServerCallResult,
    ): Boolean {
        if (!flush()) return false
        return actions.withLock {
            mutable.update {
                it.copy(
                    busy = true,
                    generatingCourseMock = true,
                    courseGenerationFailed = false,
                    error = null,
                    gradingError = null,
                )
            }
            try {
                withContext(Dispatchers.IO) {
                    val scopeKey = courseMockScopeId(detail.courseId)
                    val payload = buildCourseMockRequest(detail)
                    val call = generator(payload)
                    val body = call.body ?: error(call.error ?: "暂时无法生成考前模拟卷，请稍后重试。")
                    val normalizedBody = if (body.str("bookId") == scopeKey && body.str("courseId") == detail.courseId) {
                        body
                    } else {
                        JsonObject(body + mapOf("bookId" to JsonPrimitive(scopeKey), "courseId" to JsonPrimitive(detail.courseId)))
                    }
                    val paper = LocalExamPaper(normalizedBody)
                    store.register(paper)
                    val duration = (paper.defaultDurationMinutes ?: detail.examProfile.durationMinutes).coerceIn(15, 240)
                    val opened = store.start(scopeKey, paper.id, duration)
                    refreshCourse(detail.courseId, detail.title, opened.str("mockId"))
                }
                true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                mutable.update {
                    it.copy(courseGenerationFailed = failure !is java.sql.SQLException,
                        error = failure.message ?: "暂时无法生成考前模拟卷，请稍后重试。")
                }
                false
            } finally {
                mutable.update { it.copy(busy = false, generatingCourseMock = false) }
            }
        }
    }

    private fun buildCourseMockRequest(detail: CourseDetailSnapshot): JsonObject {
        val gapByTopic = detail.countdownPlan?.rankedTopics?.associateBy { it.topicId }.orEmpty()
        val bookCache = LinkedHashMap<String, Textbook>()
        fun loadSafe(bookId: String): Textbook? = bookCache.getOrPut(bookId) {
            runCatching { library.book(bookId) }.getOrNull() ?: return null
        }

        val examinableJson = buildJsonArray {
            for (topic in detail.examinableTopics) {
                val pagesArray = buildJsonArray {
                    val orderedSources = topic.sources
                        .filter { !CourseClientGates.isPastExamMaterial(it.bookTitle, null) }
                        .sortedBy { it.authorityRank }
                    var collected = 0
                    for (src in orderedSources) {
                        if (collected >= 4) break
                        val loadedBook = loadSafe(src.bookId) ?: continue
                        if (CourseClientGates.isPastExamMaterial(loadedBook.title, null)) continue
                        val startPg = src.startPage.coerceAtLeast(1)
                        val endPg = src.endPage.coerceAtLeast(startPg)
                        for (pNo in startPg..endPg) {
                            if (collected >= 4) break
                            val pageObj = loadedBook.page(pNo) ?: continue
                            val text = pageObj.text.trim()
                            if (text.isBlank()) continue
                            add(
                                buildJsonObject {
                                    put("bookId", src.bookId)
                                    put("bookTitle", src.bookTitle.ifBlank { loadedBook.title })
                                    put("role", src.role.wire)
                                    put("authorityRank", src.authorityRank)
                                    put("pdfPage", pageObj.number)
                                    put("text", text.take(3_800))
                                },
                            )
                            collected++
                        }
                    }
                    // Fallback to course non-past-exam materials if linked range had no text
                    if (collected == 0) {
                        for (mat in detail.materials.sortedBy { it.authorityRank }) {
                            if (collected >= 3) break
                            if (CourseClientGates.isPastExamMaterial(mat.title, null)) continue
                            val loadedBook = loadSafe(mat.bookId) ?: continue
                            for (pg in loadedBook.pages) {
                                if (collected >= 3) break
                                val text = pg.text.trim()
                                if (text.isBlank()) continue
                                add(
                                    buildJsonObject {
                                        put("bookId", mat.bookId)
                                        put("bookTitle", mat.title)
                                        put("role", mat.role.wire)
                                        put("authorityRank", mat.authorityRank)
                                        put("pdfPage", pg.number)
                                        put("text", text.take(3_800))
                                    },
                                )
                                collected++
                            }
                        }
                    }
                }
                val gap = gapByTopic[topic.topicId]
                add(
                    buildJsonObject {
                        put("topicId", topic.topicId)
                        put("weekLabel", topic.weekLabel)
                        put("title", topic.title)
                        put("examStatus", topic.examStatus.wire)
                        put("examWeight", topic.examWeight ?: 1.0)
                        put("pMastery", gap?.pMastery ?: 0.15)
                        put("dominantErrorTags", buildJsonArray { gap?.dominantErrorTags?.forEach { add(JsonPrimitive(it)) } })
                        put("sourcePages", pagesArray)
                    },
                )
            }
        }

        return buildJsonObject {
            put("courseId", detail.courseId)
            put("courseTitle", detail.title)
            put(
                "examProfile",
                buildJsonObject {
                    put("examDate", detail.examProfile.examDate)
                    put("examFormat", detail.examProfile.examFormat)
                    put("examFormatTentative", detail.examProfile.examFormatTentative)
                    put("durationMinutes", detail.examProfile.durationMinutes)
                    put("closedBook", detail.examProfile.closedBook)
                    put("calculatorAllowed", detail.examProfile.calculatorAllowed)
                    put("weightingNote", detail.examProfile.weightingNote)
                },
            )
            put("examinableTopics", examinableJson)
            put("excludedTopicTitles", buildJsonArray { detail.excludedTopics.forEach { add(JsonPrimitive(it.title)) } })
            put("referenceExclusionTexts", buildJsonArray { })
        }
    }

    suspend fun start(paper: String, durationMinutes: Int? = null) {
        if (!flush()) return
        action {
            val ui = mutable.value
            val scopeKey = checkNotNull(scopeKeyFor(ui))
            val opened = store.start(scopeKey, paper, durationMinutes).str("mockId")
            refreshScope(scopeKey, ui.book, ui.courseId, ui.courseTitle, opened)
        }
    }

    suspend fun open(attempt: String) {
        if (!flush()) return
        action {
            val ui = mutable.value
            val scopeKey = checkNotNull(scopeKeyFor(ui))
            refreshScope(scopeKey, ui.book, ui.courseId, ui.courseTitle, attempt)
        }
    }

    suspend fun back() {
        if (flush()) action {
            val ui = mutable.value
            val scopeKey = scopeKeyFor(ui) ?: return@action
            refreshScope(scopeKey, ui.book, ui.courseId, ui.courseTitle, null)
        }
    }

    suspend fun submit(
        grader: (suspend (JsonObject) -> PracticeGradeDiagnoseResult)? = null,
        onMasteryUpdated: (suspend (String) -> Unit)? = null,
    ) {
        if (!flush()) return
        val uiBefore = mutable.value
        val scopeKey = scopeKeyFor(uiBefore) ?: return
        val attemptObj = uiBefore.attempt ?: return
        val id = attemptObj.str("mockId")
        var needsStructuredGrading = false

        val submittedOk = action {
            val submittedAttempt = store.submit(scopeKey, id)
            val paper = LocalExamPaper(submittedAttempt.getValue("paper").jsonObject)
            needsStructuredGrading = grader != null && paper.hasStructuredGrading
            refreshScope(scopeKey, uiBefore.book, uiBefore.courseId, uiBefore.courseTitle, id)
        }
        if (!submittedOk || !needsStructuredGrading || grader == null) return
        gradeSubmittedAttempt(scopeKey, id, uiBefore, grader, onMasteryUpdated)
    }

    suspend fun retryGrading(
        grader: suspend (JsonObject) -> PracticeGradeDiagnoseResult,
        onMasteryUpdated: (suspend (String) -> Unit)? = null,
    ) {
        val ui = mutable.value
        val scopeKey = scopeKeyFor(ui) ?: return
        val id = ui.attempt?.str("mockId") ?: return
        gradeSubmittedAttempt(scopeKey, id, ui, grader, onMasteryUpdated)
    }

    private suspend fun gradeSubmittedAttempt(
        scopeKey: String,
        attemptId: String,
        uiSnapshot: LocalExamUi,
        grader: suspend (JsonObject) -> PracticeGradeDiagnoseResult,
        onMasteryUpdated: (suspend (String) -> Unit)?,
    ) {
        actions.withLock {
            mutable.update { it.copy(gradingInFlight = true, gradingError = null, error = null) }
            try {
                val currentAttempt = withContext(Dispatchers.IO) { store.get(scopeKey, attemptId) }
                val paper = LocalExamPaper(currentAttempt.getValue("paper").jsonObject)
                val answers = currentAttempt.getValue("answers").jsonObject
                val baselineResult = currentAttempt["result"] as? JsonObject ?: paper.grade(attemptId, answers)
                val existingItems = (baselineResult["items"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
                    .associateBy { it.str("key") }

                val updatedGrades = mutableListOf<JsonObject>()
                for (item in paper.items) {
                    val prev = existingItems[item.key]
                    if (prev != null && prev.str("status") == "GRADED") {
                        updatedGrades += prev
                        continue
                    }
                    val answer = answers[item.key] as? JsonObject ?: json()
                    if (item.type == "mcq") {
                        val chosenIdx = (answer["choice"] as? JsonPrimitive)?.intOrNull
                        val correct = chosenIdx?.let { "o$it" } == item.correctOptionId
                        updatedGrades += json(
                            "key" to item.key,
                            "attemptId" to "$attemptId/${item.key}",
                            "counted" to true,
                            "max" to item.max,
                            "score" to if (correct) item.max else 0L,
                            "status" to "EXACT",
                            "key_state" to paper.keyState.name,
                            "source" to item.source,
                        )
                        continue
                    }

                    val studentDraft = buildStudentDraftText(item, answer)
                    if (studentDraft.isBlank()) {
                        val primaryBookId = item.source.str("docId")
                        val primaryPage = item.source.num("pdfPage").toInt().coerceAtLeast(1)
                        withContext(Dispatchers.IO) {
                            if (primaryBookId.isNotBlank()) {
                                library.recordMasteryEvidence(
                                    bookId = primaryBookId,
                                    tocNodeId = item.topicId.ifBlank { item.key },
                                    page = primaryPage,
                                    canonicalName = item.weekLabel.ifBlank { item.topicId }.ifBlank { item.key },
                                    signal = LearnerSignalType.RUBRIC_GRADE,
                                    scoreRatio = 0.0,
                                    errorClass = MisconceptionErrorClass.CONCEPT_CONFUSION,
                                    detailJson = buildMockLossDetailJson(paper.courseId, item.topicId, listOf(primaryPage)),
                                    conceptKeyOverride = item.conceptKey.takeIf { it.isNotBlank() },
                                    collectionId = paper.courseId.takeIf { it.isNotBlank() },
                                )
                            }
                        }
                        updatedGrades += json(
                            "key" to item.key,
                            "attemptId" to "$attemptId/${item.key}",
                            "counted" to true,
                            "max" to item.max,
                            "score" to 0L,
                            "status" to "GRADED",
                            "key_state" to paper.keyState.name,
                            "source" to item.source,
                        )
                        continue
                    }

                    val req = withContext(Dispatchers.IO) {
                        buildPracticeGradeRequest(paper, item, answer, studentDraft)
                    }
                    when (val callResult = grader(req)) {
                        is PracticeGradeDiagnoseResult.Failed -> {
                            mutable.update { it.copy(gradingError = callResult.message) }
                            return@withLock
                        }
                        is PracticeGradeDiagnoseResult.Ready -> {
                            val sanitizedResp = enforceClientStudentQuoteSafety(callResult.response, studentDraft)
                            check(sanitizedResp.rewrittenParagraphsEmitted == 0) { "批改返回未通过只给要点校验。" }
                            val respMax = sanitizedResp.maxMarks.takeIf { it > 0.0 }
                                ?: (item.max / 100.0)
                            val ratio = if (respMax > 0.0) {
                                (sanitizedResp.totalAwarded / respMax).coerceIn(0.0, 1.0)
                            } else {
                                0.0
                            }
                            val scoreHundredths = kotlin.math.round(item.max * ratio).roundToLong().coerceIn(0L, item.max)
                            val missedPages = sanitizedResp.rubricItems
                                .filter { it.verdict != "FULL" && it.pdfPage > 0 }
                                .map { it.pdfPage }
                                .distinct()
                                .ifEmpty {
                                    if (ratio < 0.99) listOf(item.source.num("pdfPage").toInt().coerceAtLeast(1)) else emptyList()
                                }
                            val primaryBookId = item.source.str("docId")
                            val primaryPage = missedPages.firstOrNull()
                                ?: item.source.num("pdfPage").toInt().coerceAtLeast(1)
                            val errClass = sanitizedResp.diagnosis?.errorClass?.takeIf { it != MisconceptionErrorClass.NONE }
                                ?: if (ratio < 0.99) MisconceptionErrorClass.CONCEPT_CONFUSION else MisconceptionErrorClass.NONE
                            withContext(Dispatchers.IO) {
                                if (primaryBookId.isNotBlank()) {
                                    library.recordMasteryEvidence(
                                        bookId = primaryBookId,
                                        tocNodeId = item.topicId.ifBlank { item.key },
                                        page = primaryPage,
                                        canonicalName = item.weekLabel.ifBlank { item.topicId }.ifBlank { item.key },
                                        signal = LearnerSignalType.RUBRIC_GRADE,
                                        scoreRatio = ratio,
                                        errorClass = errClass,
                                        detailJson = buildMockLossDetailJson(paper.courseId, item.topicId, missedPages),
                                        conceptKeyOverride = item.conceptKey.takeIf { it.isNotBlank() },
                                        collectionId = paper.courseId.takeIf { it.isNotBlank() },
                                    )
                                }
                            }
                            updatedGrades += buildJsonObject {
                                put("key", item.key)
                                put("attemptId", "$attemptId/${item.key}")
                                put("counted", true)
                                put("max", item.max)
                                put("score", scoreHundredths)
                                put("status", "GRADED")
                                put("key_state", paper.keyState.name)
                                put("source", item.source)
                                put("studentDraft", studentDraft)
                                put("gradingResponse", PracticeGradingJson.encodeResponse(sanitizedResp))
                            }
                        }
                    }
                }

                val pendingCount = updatedGrades.count { it.str("status") == "PENDING_REVIEW" }
                val totalKnown = updatedGrades.sumOf { it.num("score") }
                val gradedResult = json(
                    "score" to if (pendingCount == 0) totalKnown else null,
                    "knownScore" to totalKnown,
                    "max" to paper.max,
                    "pending" to pendingCount,
                    "key_state" to paper.keyState.name,
                    "items" to JsonArray(updatedGrades),
                )
                withContext(Dispatchers.IO) {
                    store.saveGradedResult(scopeKey, attemptId, gradedResult)
                    refreshScope(scopeKey, uiSnapshot.book, uiSnapshot.courseId, uiSnapshot.courseTitle, attemptId)
                }
                if (paper.courseId.isNotBlank()) {
                    onMasteryUpdated?.invoke(paper.courseId)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                mutable.update {
                    it.copy(gradingError = failure.message ?: "模考逐点批改暂时不可用，请点击重试。")
                }
            } finally {
                mutable.update { it.copy(gradingInFlight = false) }
            }
        }
    }

    private fun buildStudentDraftText(item: LocalExamItem, answer: JsonObject): String {
        if (item.subQuestions.isNotEmpty()) {
            val partsObj = answer["parts"] as? JsonObject
            val joinedParts = item.subQuestions.mapNotNull { sq ->
                val raw = partsObj?.str(sq.subId)?.trim().orEmpty()
                if (raw.isBlank()) null else "${sq.label} $raw"
            }.joinToString("\n")
            val extra = answer.str("text").trim()
            return listOf(joinedParts, extra).filter { it.isNotBlank() }.joinToString("\n")
        }
        return answer.str("text").trim()
    }

    private fun buildPracticeGradeRequest(
        paper: LocalExamPaper,
        item: LocalExamItem,
        answer: JsonObject,
        studentDraft: String,
    ): JsonObject {
        val partsObj = answer["parts"] as? JsonObject
        val docId = item.source.str("docId")
        val primaryPageNo = item.source.num("pdfPage").toInt().coerceAtLeast(1)
        val loadedBook = runCatching { library.book(docId) }.getOrNull()

        val rubricArray = buildJsonArray {
            val sourceCriteria = item.rubricCriteria.ifEmpty {
                listOf(
                    buildJsonObject {
                        put("cid", "${item.key}-C1")
                        put("slotLabel", "核心定义与关键结论：${item.stem.take(80)}")
                        put("maxMarks", item.max / 100.0)
                        put("sourceBookId", docId)
                        put("pdfPage", primaryPageNo)
                        put("quoteAnchor", item.source.str("quote"))
                        put("sourceExcerpt", item.source.str("pageText").take(650))
                    },
                )
            }
            for (cr in sourceCriteria) {
                val subId = cr.str("subId")
                val submittedVal = if (subId.isNotBlank()) partsObj?.str(subId)?.trim() else null
                val nk = cr["numericKey"] as? JsonObject
                add(
                    buildJsonObject {
                        cr.forEach { (k, v) -> put(k, v) }
                        if (nk != null && !submittedVal.isNullOrBlank()) {
                            put(
                                "numericKey",
                                JsonObject(nk + mapOf("submitted" to JsonPrimitive(submittedVal))),
                            )
                        }
                    },
                )
            }
        }

        val numericFactsArray = buildJsonArray {
            for (sq in item.subQuestions) {
                val math = sq.mathSpec ?: continue
                val expected = math.str("expected")
                if (expected.isBlank()) continue
                val subAnswer = partsObj?.str(sq.subId)?.trim()?.takeIf { it.isNotBlank() } ?: studentDraft
                add(
                    buildJsonObject {
                        put("cid", "${item.key}-${sq.subId}")
                        put("label", "${sq.label} ${sq.prompt.take(60)}")
                        put("expected", expected)
                        put("submitted", subAnswer)
                        put("absTolerance", math.str("absTolerance").ifBlank { "0.015" })
                        put("decimalPlaces", (math["decimalPlaces"] as? JsonPrimitive)?.intOrNull ?: 2)
                    },
                )
            }
        }

        val pagesByKey = LinkedHashMap<Int, Triple<Int, String, String>>()
        val primaryPageText = item.source.str("pageText").ifBlank {
            loadedBook?.page(primaryPageNo)?.text.orEmpty()
        }
        if (primaryPageText.isNotBlank()) {
            pagesByKey[primaryPageNo] = Triple(primaryPageNo, primaryPageText.take(6_000), item.source.str("bookTitle").ifBlank { loadedBook?.title.orEmpty() })
        }
        for (cr in item.rubricCriteria) {
            val crBookId = cr.str("sourceBookId").ifBlank { docId }
            val crPg = cr.num("pdfPage").toInt().takeIf { it > 0 } ?: continue
            if (crPg in pagesByKey) continue
            val crBook = if (crBookId == docId) loadedBook else runCatching { library.book(crBookId) }.getOrNull()
            val text = crBook?.page(crPg)?.text?.takeIf { it.isNotBlank() } ?: cr.str("sourceExcerpt")
            if (text.isNotBlank()) {
                pagesByKey[crPg] = Triple(crPg, text.take(6_000), crBook?.title.orEmpty())
            }
        }

        val stemWithParts = buildString {
            append(item.stem)
            for (sq in item.subQuestions) {
                append('\n').append(sq.label).append(' ').append(sq.prompt)
            }
        }

        return buildJsonObject {
            put("mode", "GRADE_AND_DIAGNOSE")
            put("bookId", docId.ifBlank { paper.book })
            put("chapterId", item.topicId.ifBlank { "course-mock" })
            put("sectionId", item.weekLabel.ifBlank { item.key })
            put("conceptKey", item.conceptKey.ifBlank { "${paper.courseId}:${item.key}" })
            put("questionId", item.key)
            put("questionStem", stemWithParts.take(7_800))
            put("studentAnswer", studentDraft.take(15_800))
            put("rubricCriteria", rubricArray)
            put(
                "sourcePages",
                buildJsonArray {
                    for ((pg, txt, title) in pagesByKey.values.take(6)) {
                        add(
                            buildJsonObject {
                                put("pdfPage", pg)
                                put("text", txt)
                                put("title", title)
                                put("authorityRank", 1)
                            },
                        )
                    }
                },
            )
            if (numericFactsArray.isNotEmpty()) put("numericFacts", numericFactsArray)
            put("generateVariant", true)
        }
    }

    private fun enforceClientStudentQuoteSafety(
        response: PracticeGradeDiagnoseResponse,
        studentDraft: String,
    ): PracticeGradeDiagnoseResponse {
        val fixedItems = response.rubricItems.map { item ->
            if (item.verdict == "ZERO" && item.awardedMarks <= 0.0) {
                item
            } else {
                val quote = item.studentQuote.trim()
                val quotePresent = quote.isNotBlank() && (
                    studentDraft.contains(quote, ignoreCase = true) ||
                        item.studentQuoteVerified
                    )
                if (quotePresent) {
                    item
                } else {
                    item.copy(
                        verdict = "ZERO",
                        awardedMarks = 0.0,
                        studentQuoteVerified = false,
                        quoteGateZeroed = true,
                    )
                }
            }
        }
        val fixedTotal = fixedItems.sumOf { it.awardedMarks }
        return response.copy(rubricItems = fixedItems, totalAwarded = fixedTotal)
    }

    private fun buildMockLossDetailJson(courseId: String, topicId: String, missedPages: List<Int>): String =
        buildJsonObject {
            put("source", "course_mock")
            put("courseId", courseId)
            put("topicId", topicId)
            put("missedPages", buildJsonArray { missedPages.distinct().forEach { add(JsonPrimitive(it)) } })
        }.toString()

    fun showSource(source: JsonObject) { mutable.update { it.copy(source = source) } }
    fun closeSource() { mutable.update { it.copy(source = null) } }

    fun answerSubPart(question: String, subId: String, text: String) = synchronized(queueLock) {
        val ui = mutable.value
        val attempt = ui.attempt ?: return@synchronized
        val prevAnswer = attempt.getValue("answers").jsonObject[question] as? JsonObject ?: json()
        val prevParts = prevAnswer["parts"] as? JsonObject ?: json()
        val nextParts = JsonObject(prevParts + (subId to JsonPrimitive(text)))
        val nextAnswer = LinkedHashMap<String, JsonElement>(prevAnswer)
        nextAnswer["parts"] = nextParts
        answer(question, JsonObject(nextAnswer))
    }

    fun answer(question: String, answer: JsonObject) = synchronized(queueLock) {
        val ui = mutable.value
        val attempt = ui.attempt ?: return@synchronized
        if (ui.busy || ui.conflict || attempt.str("status") != "active") return@synchronized
        if (attempt.str("timing") == "TIMED" && clock() >= attempt.num("deadline")) return@synchronized
        val key = Key(attempt.str("bookId"), attempt.str("mockId"))
        val answers = JsonObject(attempt.getValue("answers").jsonObject + (question to answer))
        val sequence = Math.addExact(attempt.num("answersSeq"), 1)
        pending[key] = Draft(answers, sequence)
        mutable.update {
            it.copy(
                attempt = JsonObject(attempt + json("answers" to answers, "answersSeq" to sequence)),
                saving = true,
                error = null,
            )
        }
        startWriter()
    }

    private fun startWriter() {
        if (writer?.isActive != true) writer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { drain() }
        }
    }

    private suspend fun drain() {
        while (true) {
            val (key, draft) = synchronized(queueLock) { pending.entries.firstOrNull()?.toPair() } ?: return
            if (!action(showBusy = false) {
                val reply = try {
                    store.save(key.book, key.attempt, draft.answers, draft.sequence)
                } catch (changed: IllegalStateException) {
                    mutable.update { it.copy(conflict = true) }
                    throw IllegalStateException("无法确认这次作答的已保存状态。本页内容仍保留，请确认后重新载入。", changed)
                }
                if (!reply.flag("saved")) {
                    mutable.update { it.copy(conflict = true) }
                    error(
                        if (reply.flag("expired")) {
                            "计时已结束，本页迟到内容没有写入成绩。请先复制需要保留的文字，再读取已保存作答。"
                        } else {
                            "另一个窗口已更新这次作答。本页内容仍保留，请确认后读取已保存作答。"
                        },
                    )
                }
                mutable.update { ui ->
                    if (ui.attempt?.str("mockId") == key.attempt && ui.attempt.num("answersSeq") <= reply.num("answersSeq")) {
                        ui.copy(attempt = reply, active = reply)
                    } else {
                        ui
                    }
                }
            }) return
            synchronized(queueLock) {
                if (pending[key] == draft) pending.remove(key)
                mutable.update { it.copy(saving = pending.isNotEmpty()) }
            }
        }
    }

    suspend fun reloadSaved() {
        if (!mutable.value.conflict) return
        synchronized(queueLock) { writer }?.join()
        action {
            val ui = mutable.value
            val scopeKey = checkNotNull(scopeKeyFor(ui))
            val id = checkNotNull(ui.attempt).str("mockId")
            // Read/validate the authoritative saved attempt before discarding this explicitly rejected draft.
            refreshScope(scopeKey, ui.book, ui.courseId, ui.courseTitle, id)
            synchronized(queueLock) {
                pending.remove(Key(scopeKey, id))
                mutable.update { it.copy(saving = pending.isNotEmpty(), conflict = false) }
            }
        }
    }

    suspend fun flush(): Boolean {
        if (mutable.value.conflict) return false
        val job = synchronized(queueLock) {
            if (pending.isNotEmpty()) startWriter()
            writer
        }
        job?.join()
        return synchronized(queueLock) { pending.isEmpty() }
    }

    companion object {
        fun courseMockScopeId(courseId: String): String = "course:$courseId"
    }
}

@Composable
internal fun rememberLocalExamController(library: TextbookLibrary): LocalExamController {
    val scope = rememberCoroutineScope()
    return remember(library, scope) { LocalExamController(library, scope) }
}
