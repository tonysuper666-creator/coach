package com.coach.desktop.books

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal data class PracticeRubricItemGrade(
    val cid: String,
    val verdict: String, // FULL | PARTIAL | ZERO
    val awardedMarks: Double = 0.0,
    val maxMarks: Double = 0.0,
    val studentQuote: String = "",
    val studentQuoteVerified: Boolean = false,
    val quoteGateZeroed: Boolean = false,
    val feedbackNoteZh: String = "",
    val pdfPage: Int = 0,
    val quoteAnchor: String = "",
    val anchorVerified: Boolean = false,
    val missCause: String = "",
    val slotLabel: String = "",
    val bookId: String = "",
    val pointKind: String = "",
    val usage: String = "",
)

internal data class PracticeMisconceptionDiagnosis(
    val isEquivalentValid: Boolean = true,
    val errorClass: MisconceptionErrorClass = MisconceptionErrorClass.NONE,
    val studentErrorSpan: String = "",
    val diagnosisZh: String = "",
)

internal data class PracticeIsomorphicVariant(
    val variantId: String,
    val stem: String,
    val options: List<String>,
    val correctOption: String,
    val targetedTrapExplanation: String = "",
    val pdfPage: Int = 0,
    val quoteAnchor: String = "",
    val conceptKey: String = "",
    val targetErrorClass: MisconceptionErrorClass = MisconceptionErrorClass.NONE,
    val anchorVerified: Boolean = false,
)

internal data class PracticeGradeDiagnoseResponse(
    val mode: String = "GRADE_AND_DIAGNOSE",
    val rubricItems: List<PracticeRubricItemGrade> = emptyList(),
    val totalAwarded: Double = 0.0,
    val maxMarks: Double = 0.0,
    val summaryMetaLine: String = "",
    val diagnosis: PracticeMisconceptionDiagnosis? = null,
    val variant: PracticeIsomorphicVariant? = null,
    val rewrittenParagraphsEmitted: Int = 0,
)

internal sealed interface PracticeGradeDiagnoseResult {
    data class Ready(val response: PracticeGradeDiagnoseResponse) : PracticeGradeDiagnoseResult
    data class Failed(val message: String, val trouble: ExplainTrouble = ExplainTrouble.Other) : PracticeGradeDiagnoseResult
}

internal data class PracticeGradingHost(
    val library: TextbookLibrary? = null,
    val gradeAndDiagnose: suspend (JsonObject) -> PracticeGradeDiagnoseResult,
)

internal val LocalPracticeGradingHost = staticCompositionLocalOf<PracticeGradingHost?> { null }

internal object PracticeGradingJson {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.s(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    fun decodeResponse(raw: String): PracticeGradeDiagnoseResponse =
        decodeResponse(json.parseToJsonElement(raw).jsonObject)

    fun decodeResponse(obj: JsonObject): PracticeGradeDiagnoseResponse {
        val items = (obj["rubricItems"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val cid = o.s("cid")
            if (cid.isBlank()) return@mapNotNull null
            PracticeRubricItemGrade(
                cid = cid,
                verdict = o.s("verdict").ifBlank { "ZERO" },
                awardedMarks = (o["awardedMarks"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                maxMarks = (o["maxMarks"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                studentQuote = o.s("studentQuote"),
                studentQuoteVerified = (o["studentQuoteVerified"] as? JsonPrimitive)?.booleanOrNull ?: false,
                quoteGateZeroed = (o["quoteGateZeroed"] as? JsonPrimitive)?.booleanOrNull ?: false,
                feedbackNoteZh = o.s("feedbackNoteZh"),
                pdfPage = (o["pdfPage"] as? JsonPrimitive)?.intOrNull ?: 0,
                quoteAnchor = o.s("quoteAnchor"),
                anchorVerified = (o["anchorVerified"] as? JsonPrimitive)?.booleanOrNull ?: false,
                missCause = o.s("missCause"),
                slotLabel = o.s("slotLabel"),
                bookId = o.s("bookId"),
                pointKind = o.s("pointKind"),
                usage = o.s("usage"),
            )
        }
        val diagObj = obj["diagnosis"] as? JsonObject
        val diagnosis = diagObj?.let { d ->
            val cls = MisconceptionErrorClass.fromWire(d.s("errorClass")) ?: MisconceptionErrorClass.NONE
            PracticeMisconceptionDiagnosis(
                isEquivalentValid = (d["isEquivalentValid"] as? JsonPrimitive)?.booleanOrNull ?: (cls == MisconceptionErrorClass.NONE),
                errorClass = cls,
                studentErrorSpan = d.s("studentErrorSpan"),
                diagnosisZh = d.s("diagnosisZh"),
            )
        }
        val varObj = obj["variant"] as? JsonObject
        val variant = varObj?.let { v ->
            val stem = v.s("stem")
            val opts = (v["options"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (stem.isBlank() || opts.size < 2) null else PracticeIsomorphicVariant(
                variantId = v.s("variantId").ifBlank { "var-local" },
                stem = stem,
                options = opts,
                correctOption = v.s("correctOption"),
                targetedTrapExplanation = v.s("targetedTrapExplanation"),
                pdfPage = (v["pdfPage"] as? JsonPrimitive)?.intOrNull ?: 0,
                quoteAnchor = v.s("quoteAnchor"),
                conceptKey = v.s("conceptKey"),
                targetErrorClass = MisconceptionErrorClass.fromWire(v.s("targetErrorClass")) ?: MisconceptionErrorClass.NONE,
                anchorVerified = (v["anchorVerified"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
        return PracticeGradeDiagnoseResponse(
            mode = obj.s("mode").ifBlank { "GRADE_AND_DIAGNOSE" },
            rubricItems = items,
            totalAwarded = (obj["totalAwarded"] as? JsonPrimitive)?.doubleOrNull ?: items.sumOf { it.awardedMarks },
            maxMarks = (obj["maxMarks"] as? JsonPrimitive)?.doubleOrNull ?: items.sumOf { it.maxMarks },
            summaryMetaLine = obj.s("summaryMetaLine"),
            diagnosis = diagnosis,
            variant = variant,
            rewrittenParagraphsEmitted = (obj["rewrittenParagraphsEmitted"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    fun encodeResponse(response: PracticeGradeDiagnoseResponse): JsonObject = buildJsonObject {
        put("mode", response.mode)
        put("totalAwarded", response.totalAwarded)
        put("maxMarks", response.maxMarks)
        put("summaryMetaLine", response.summaryMetaLine)
        put("rewrittenParagraphsEmitted", response.rewrittenParagraphsEmitted)
        put(
            "rubricItems",
            buildJsonArray {
                for (item in response.rubricItems) {
                    add(
                        buildJsonObject {
                            put("cid", item.cid)
                            put("verdict", item.verdict)
                            put("awardedMarks", item.awardedMarks)
                            put("maxMarks", item.maxMarks)
                            put("studentQuote", item.studentQuote)
                            put("studentQuoteVerified", item.studentQuoteVerified)
                            put("quoteGateZeroed", item.quoteGateZeroed)
                            put("feedbackNoteZh", item.feedbackNoteZh)
                            put("pdfPage", item.pdfPage)
                            put("quoteAnchor", item.quoteAnchor)
                            put("anchorVerified", item.anchorVerified)
                            if (item.missCause.isNotBlank()) put("missCause", item.missCause)
                            if (item.slotLabel.isNotBlank()) put("slotLabel", item.slotLabel)
                            if (item.bookId.isNotBlank()) put("bookId", item.bookId)
                            if (item.pointKind.isNotBlank()) put("pointKind", item.pointKind)
                            if (item.usage.isNotBlank()) put("usage", item.usage)
                        },
                    )
                }
            },
        )
        response.diagnosis?.let { d ->
            put(
                "diagnosis",
                buildJsonObject {
                    put("isEquivalentValid", d.isEquivalentValid)
                    put("errorClass", d.errorClass.name)
                    put("studentErrorSpan", d.studentErrorSpan)
                    put("diagnosisZh", d.diagnosisZh)
                },
            )
        }
        response.variant?.let { v ->
            put(
                "variant",
                buildJsonObject {
                    put("variantId", v.variantId)
                    put("stem", v.stem)
                    put("options", buildJsonArray { v.options.forEach { add(JsonPrimitive(it)) } })
                    put("correctOption", v.correctOption)
                    put("targetedTrapExplanation", v.targetedTrapExplanation)
                    put("pdfPage", v.pdfPage)
                    put("quoteAnchor", v.quoteAnchor)
                    put("conceptKey", v.conceptKey)
                    put("targetErrorClass", v.targetErrorClass.name)
                    put("anchorVerified", v.anchorVerified)
                },
            )
        }
    }

    fun buildSingleBookRequest(
        book: Textbook,
        section: BookLesson?,
        quiz: BookCompanionQuiz,
        studentAnswer: String,
        mode: String = "GRADE_AND_DIAGNOSE",
        mcqSelected: String = "",
    ): JsonObject {
        val targetPages = buildList {
            quiz.anchor?.pdfPage?.let(::add)
            if (section != null) {
                for (p in section.firstPage..minOf(section.lastPage, section.firstPage + 3)) add(p)
            }
        }.distinct().take(4)
        val sourcePages = targetPages.mapNotNull { pg ->
            val text = book.page(pg)?.text?.trim().orEmpty()
            if (text.isBlank()) null else Triple(pg, text.take(4_000), 2)
        }.ifEmpty {
            book.pages.take(2).map { Triple(it.number, it.text.take(4_000), 2) }
        }
        val anchorPage = quiz.anchor?.pdfPage ?: section?.firstPage ?: 1
        val anchorQuote = quiz.anchor?.quoteAnchor.orEmpty()
        val conceptKey = MasteryEngine.conceptKeyFor(book.id, section?.id ?: quiz.sectionId)
        return buildJsonObject {
            put("mode", mode)
            put("bookId", book.id)
            put("chapterId", section?.id ?: quiz.sectionId)
            put("sectionId", quiz.sectionId.ifBlank { section?.id.orEmpty() })
            put("conceptKey", conceptKey)
            put("questionId", quiz.id)
            put("questionStem", quiz.stem)
            put("studentAnswer", studentAnswer)
            put("generateVariant", true)
            if (mcqSelected.isNotBlank()) {
                put("mcqSelectedOption", mcqSelected)
                put("mcqCorrectOption", quiz.correctOption)
            }
            if (mode != "DIAGNOSE_ONLY") {
                put("rubricCriteria", buildJsonArray {
                    add(buildJsonObject {
                        put("cid", "${quiz.id}-C1")
                        put("slotLabel", "核心定义与关键关系：${quiz.stem.take(90)}")
                        put("pdfPage", anchorPage)
                        if (anchorQuote.isNotBlank()) put("quoteAnchor", anchorQuote)
                    })
                    if (quiz.explanation.isNotBlank()) {
                        add(buildJsonObject {
                            put("cid", "${quiz.id}-C2")
                            put("slotLabel", "推导步骤、符号方向或边界条件校验")
                            put("pdfPage", anchorPage)
                            if (anchorQuote.isNotBlank()) put("quoteAnchor", anchorQuote)
                        })
                    }
                })
            }
            put("sourcePages", buildJsonArray {
                for ((pg, txt, rank) in sourcePages) {
                    add(buildJsonObject {
                        put("pdfPage", pg)
                        put("text", txt)
                        put("title", book.title)
                        put("authorityRank", rank)
                    })
                }
            })
        }
    }

    fun buildCourseEssayRequest(
        courseContext: CourseReaderContext,
        activeBookId: String,
        activePage: Int,
        draftText: String,
        library: TextbookLibrary?,
    ): JsonObject {
        if (courseContext.weekLabel == CourseExamPoints.LABEL) {
            return buildExamPointEssayRequest(courseContext, activeBookId, draftText, library)
        }
        val pack = courseContext.pack
        val stem = pack?.practicePrompt?.stem
            ?: "请围绕「${courseContext.topicTitle}」写出核心论点、适用条件与关键差异对比。"
        val criteria = buildList {
            val promptPoints = pack?.practicePrompt?.points.orEmpty()
            if (promptPoints.isNotEmpty()) {
                promptPoints.forEachIndexed { idx, pt ->
                    val cit = pack?.practicePrompt?.citations?.getOrNull(idx)
                        ?: pack?.citations?.getOrNull(idx)
                    add(Triple("E-${idx + 1}", pt, cit))
                }
            } else if (pack != null && pack.keyPoints.isNotEmpty()) {
                pack.keyPoints.take(5).forEachIndexed { idx, kp ->
                    add(Triple("E-${idx + 1}", "${kp.title}：${kp.explanation.take(70)}", kp.citations.firstOrNull()))
                }
            } else {
                add(Triple("E-1", "核心概念定义与基本模型设定", null))
                add(Triple("E-2", "适用前提条件与机制推导", null))
                add(Triple("E-3", "关键边界对比与结论", null))
            }
        }
        val sourcePages = buildList {
            if (library != null) {
                for (src in courseContext.orderedSources.take(3)) {
                    val opened = runCatching { library.book(src.bookId) }.getOrNull() ?: continue
                    val startP = src.startPage.coerceAtLeast(1)
                    val endP = minOf(src.endPage.coerceAtLeast(startP), startP + 2)
                    for (p in startP..endP) {
                        val txt = opened.page(p)?.text?.trim().orEmpty()
                        if (txt.isNotBlank()) {
                            add(Triple(p, txt.take(3_600), src.authorityRank))
                        }
                    }
                }
            }
        }.ifEmpty {
            val fallbackText = pack?.keyPoints?.joinToString("\n") { "${it.title}: ${it.explanation}" }.orEmpty()
            if (fallbackText.isNotBlank()) listOf(Triple(activePage.coerceAtLeast(1), fallbackText.take(3_600), 1))
            else emptyList()
        }
        val conceptKey = MasteryEngine.conceptKeyFor(activeBookId, courseContext.topicId)
        return buildJsonObject {
            put("mode", "GRADE_AND_DIAGNOSE")
            put("bookId", activeBookId)
            put("chapterId", courseContext.topicId)
            put("sectionId", courseContext.topicId)
            put("conceptKey", conceptKey)
            put("questionId", pack?.practicePrompt?.promptId ?: "essay-${courseContext.topicId}")
            put("questionStem", stem)
            put("studentAnswer", draftText)
            put("generateVariant", true)
            put("rubricCriteria", buildJsonArray {
                for ((cid, label, cit) in criteria) {
                    add(buildJsonObject {
                        put("cid", cid)
                        put("slotLabel", label)
                        put("pdfPage", cit?.page ?: activePage.coerceAtLeast(1))
                        val citQuote = cit?.quote
                        if (!citQuote.isNullOrBlank()) put("quoteAnchor", citQuote)
                    })
                }
            })
            put("sourcePages", buildJsonArray {
                for ((pg, txt, rank) in sourcePages.take(6)) {
                    add(buildJsonObject {
                        put("pdfPage", pg)
                        put("text", txt)
                        put("authorityRank", rank)
                    })
                }
            })
        }
    }

    private fun buildExamPointEssayRequest(
        courseContext: CourseReaderContext,
        activeBookId: String,
        draftText: String,
        library: TextbookLibrary?,
    ): JsonObject {
        val pack = courseContext.pack
        val stem = pack?.practicePrompt?.stem
            ?: "请围绕「${courseContext.topicTitle}」写出核心论点、适用条件与关键差异对比。"
        val teachingRoles = setOf(
            CourseMaterialRole.CORE_TEXTBOOK,
            CourseMaterialRole.LECTURE_SLIDES,
            CourseMaterialRole.HANDOUT,
        )
        data class PageSlice(
            val bookId: String,
            val title: String,
            val role: String,
            val rank: Int,
            val page: Int,
            val text: String,
        )
        val slices = buildList {
            if (library != null) {
                for (src in courseContext.orderedSources) {
                    if (src.role !in teachingRoles) continue
                    val opened = runCatching { library.book(src.bookId) }.getOrNull() ?: continue
                    val startP = src.startPage.coerceAtLeast(1)
                    val endP = minOf(src.endPage.coerceAtLeast(startP), startP + 2)
                    for (p in startP..endP) {
                        if (size >= 12) break
                        val txt = opened.page(p)?.text?.trim().orEmpty()
                        if (txt.isBlank()) continue
                        add(PageSlice(src.bookId, src.bookTitle, src.role.wire, src.authorityRank, p, txt.take(12_000)))
                    }
                    if (size >= 12) break
                }
            }
        }
        val conceptKey = MasteryEngine.conceptKeyFor(activeBookId, courseContext.topicId)
        return buildJsonObject {
            put("mode", "COURSE_ESSAY")
            put("bookId", activeBookId)
            put("chapterId", courseContext.topicId)
            put("sectionId", courseContext.topicId)
            put("conceptKey", conceptKey)
            put("questionId", pack?.practicePrompt?.promptId ?: "essay-${courseContext.topicId}")
            put("questionStem", stem)
            put("studentAnswer", draftText)
            put("generateVariant", false)
            put("topicTitle", courseContext.topicTitle)
            put("sourcePages", buildJsonArray {
                for (slice in slices) {
                    add(buildJsonObject {
                        put("pdfPage", slice.page)
                        put("text", slice.text)
                        put("title", slice.title)
                        put("authorityRank", slice.rank)
                        put("bookId", slice.bookId)
                        put("role", slice.role)
                    })
                }
            })
        }
    }

    fun buildReviewDiagnoseRequest(
        review: LocalBookReview,
        studentAnswer: String,
        library: TextbookLibrary?,
        mode: String = "DIAGNOSE_ONLY",
    ): JsonObject {
        val book = library?.let { runCatching { it.book(review.book) }.getOrNull() }
        val baseLessonId = review.lesson.substringBefore("#q")
        val lesson = book?.lessons?.firstOrNull { it.id == baseLessonId }
        val pages = buildList<Pair<Int, String>> {
            if (book != null && lesson != null) {
                for (p in lesson.firstPage..minOf(lesson.lastPage, lesson.firstPage + 2)) {
                    val txt = book.page(p)?.text?.trim().orEmpty()
                    if (txt.isNotBlank()) add(p to txt.take(3_600))
                }
            }
            if (isEmpty() && review.practice.reference.isNotBlank()) {
                add(1 to review.practice.reference.take(3_600))
            }
        }
        val conceptKey = MasteryEngine.conceptKeyFor(review.book, baseLessonId)
        return buildJsonObject {
            put("mode", mode)
            put("bookId", review.book)
            put("chapterId", baseLessonId)
            put("sectionId", baseLessonId)
            put("conceptKey", conceptKey)
            put("questionId", review.lesson)
            put("questionStem", review.practice.prompt)
            put("studentAnswer", studentAnswer.ifBlank { review.originalAnswer.ifBlank { "未作答" } })
            put("generateVariant", true)
            if (review.mode == QuestionMode.CHOICE) {
                put("mcqSelectedOption", studentAnswer)
                put("mcqCorrectOption", review.practice.answer)
            }
            if (mode != "DIAGNOSE_ONLY") {
                put("rubricCriteria", buildJsonArray {
                    add(buildJsonObject {
                        put("cid", "REV-C1")
                        put("slotLabel", "核心定义与关键结论：${review.practice.prompt.take(80)}")
                        put("pdfPage", lesson?.firstPage ?: 1)
                    })
                })
            }
            put("sourcePages", buildJsonArray {
                for ((pg, txt) in pages) {
                    add(buildJsonObject {
                        put("pdfPage", pg)
                        put("text", txt)
                        put("authorityRank", 2)
                    })
                }
            })
        }
    }
}

/**
 * Compact in-place point-by-point annotation sheet (zero ghostwriting):
 * - Single top `CoachCardMeta` line
 * - Student draft rendered with soft pine-green background on verified awarded quote spans
 * - Per-criterion concise note + clickable page pill (1-click flips the left reader pane back to that page)
 */
@Composable
internal fun RubricAnnotationSheet(
    studentDraft: String,
    response: PracticeGradeDiagnoseResponse,
    onJumpPage: (pdfPage: Int, quoteAnchor: String) -> Unit,
    modifier: Modifier = Modifier,
    onJumpSource: (bookId: String, pdfPage: Int, quoteAnchor: String) -> Unit = { _, page, quote -> onJumpPage(page, quote) },
) {
    val colors = CoachTheme.colors
    val pine = v7Pine()
    val pineHighlight = pine.copy(alpha = 0.16f)

    PaperCard(
        modifier = modifier.fillMaxWidth().testTag("rubric-annotation-sheet"),
        contentPadding = PaddingValues(12.dp),
        contentSpacing = 10.dp,
    ) {
        CoachCardMeta(
            text = response.summaryMetaLine.ifBlank { "已核对 ${response.rubricItems.size} 个采分要点。" },
            modifier = Modifier.testTag("rubric-annotation-meta"),
        )

        val awardedQuotes = remember(response.rubricItems) {
            response.rubricItems
                .filter { it.verdict != "ZERO" && it.studentQuoteVerified && it.studentQuote.isNotBlank() }
                .map { it.studentQuote }
        }
        if (studentDraft.isNotBlank() && awardedQuotes.isNotEmpty()) {
            val annotated = remember(studentDraft, awardedQuotes, pineHighlight) {
                buildHighlightedDraft(studentDraft, awardedQuotes, pineHighlight)
            }
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("rubric-highlighted-draft"),
                shape = RoundedCornerShape(CoachV7.Radius.s),
                color = colors.surface1,
                border = BorderStroke(1.dp, colors.borderSubtle),
            ) {
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgPrimary,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        if (response.rubricItems.isNotEmpty()) {
            HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.6f))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                response.rubricItems.forEachIndexed { idx, item ->
                    val statusColor = when (item.verdict) {
                        "FULL" -> pine
                        "PARTIAL" -> colors.actionPrimary
                        else -> v7Alert()
                    }
                    val chapterPoint = response.mode == "CHAPTER_OPEN" || item.missCause.isNotBlank()
                    val statusLabel = if (chapterPoint) {
                        when (item.verdict) {
                            "FULL" -> "答到"
                            "PARTIAL" -> "部分"
                            else -> "没答到"
                        }
                    } else when (item.verdict) {
                        "FULL" -> "得分"
                        "PARTIAL" -> "部分得分"
                        else -> "待补齐"
                    }
                    val causeLabel = when (item.missCause) {
                        "NOT_READ" -> "没读到"
                        "READ_NOT_UNDERSTOOD" -> "读了没懂"
                        "CONCEPT_CONFUSION" -> "混淆了别的概念"
                        "UNCLEAR_EXPRESSION" -> "表达不清"
                        else -> ""
                    }
                    val coursePoint = item.usage.isNotBlank()
                    Column(
                        modifier = Modifier.fillMaxWidth().testTag("rubric-item-$idx"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (coursePoint) {
                            val missingPoint = item.usage == "MISSING"
                            val usedLabel = if (missingPoint) "没用到" else "用到了"
                            val rightLabel = when {
                                missingPoint -> ""
                                item.usage == "USED" && item.verdict == "FULL" -> "用对了"
                                else -> "用偏了"
                            }
                            val frameworkPoint = item.pointKind == "FRAMEWORK"
                            val usageTag = if (frameworkPoint) "essay-framework-usage-$idx" else "essay-point-used-$idx"
                            val rereadTag = if (frameworkPoint) "essay-framework-reread-$idx" else "essay-reread-$idx"
                            Text(
                                text = item.quoteAnchor.takeIf { it.endsWith('.') || it.endsWith('。') }
                                    ?.replace(Regex("""\s+"""), " ") ?: item.slotLabel.ifBlank { "要点" },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.fgPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = usedLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                modifier = Modifier.testTag(usageTag),
                            )
                            if (rightLabel.isNotBlank()) {
                                Text(
                                    text = rightLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor,
                                )
                            }
                            Text(
                                text = "差在哪：${item.feedbackNoteZh.ifBlank { "请回看这一页的原句。" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.fgPrimary,
                                modifier = Modifier.testTag("rubric-note-$idx"),
                            )
                            if (item.pdfPage > 0) {
                                RubricPagePill(
                                    pdfPage = item.pdfPage,
                                    onClick = { onJumpSource(item.bookId, item.pdfPage, item.quoteAnchor) },
                                    label = "回书补读 · 第 ${item.pdfPage} 页",
                                    tag = rereadTag,
                                )
                            }
                            return@Column
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (item.maxMarks > 0.0) {
                                    "$statusLabel · ${formatCompactMark(item.awardedMarks)}/${formatCompactMark(item.maxMarks)}"
                                } else statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.weight(1f))
                            if (item.pdfPage > 0) {
                                RubricPagePill(
                                    pdfPage = item.pdfPage,
                                    onClick = { onJumpPage(item.pdfPage, item.quoteAnchor) },
                                    label = if (chapterPoint && item.verdict == "ZERO") "回到这一句" else "课本第 ${item.pdfPage} 页",
                                )
                            }
                        }
                        if (causeLabel.isNotBlank()) {
                            Text(
                                text = "原因：$causeLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.fgSecondary,
                                modifier = Modifier.testTag("rubric-cause-$idx"),
                            )
                        }
                        Text(
                            text = item.feedbackNoteZh.ifBlank { "请回看课本对应页核对本步要点。" },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.fgPrimary,
                            modifier = Modifier.testTag("rubric-note-$idx"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Embedded inside wrong-answer feedback cards:
 * - Plain-Chinese misconception explanation
 * - Bottom-right subtle `PaperButton("练一道同类变式")`
 * - In-place targeted isomorphic variant MCQ; answering correctly clears `dominant_error_tag` in `concept_mastery_ledger`
 */
@Composable
internal fun MisconceptionFeedbackCard(
    diagnosis: PracticeMisconceptionDiagnosis,
    variant: PracticeIsomorphicVariant?,
    onJumpPage: (pdfPage: Int, quoteAnchor: String) -> Unit,
    onVariantMastered: (PracticeIsomorphicVariant) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (diagnosis.errorClass == MisconceptionErrorClass.NONE) return
    val colors = CoachTheme.colors
    val pine = v7Pine()
    var showVariant by remember(variant?.variantId) { mutableStateOf(false) }
    var selectedOpt by remember(variant?.variantId) { mutableStateOf<String?>(null) }
    var submitted by remember(variant?.variantId) { mutableStateOf(false) }
    var cleared by remember(variant?.variantId) { mutableStateOf(false) }

    PaperCard(
        modifier = modifier.fillMaxWidth().testTag("misconception-feedback-card"),
        contentPadding = PaddingValues(12.dp),
        contentSpacing = 8.dp,
    ) {
        Text(
            text = "${diagnosis.errorClass.labelZh}：${diagnosis.diagnosisZh}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.fgPrimary,
            modifier = Modifier.testTag("misconception-diagnosis-text"),
        )
        if (variant != null && !showVariant) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PaperButton(
                    onClick = { showVariant = true },
                    modifier = Modifier.testTag("practice-variant-btn"),
                ) {
                    Text("练一道同类变式")
                }
            }
        }
        if (variant != null && showVariant) {
            HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.7f))
            Column(
                modifier = Modifier.fillMaxWidth().testTag("practice-variant-card"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CoachCardMeta("同类变式巩固 · 针对${variant.targetErrorClass.labelZh}")
                Text(variant.stem, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
                variant.options.forEachIndexed { idx, opt ->
                    val chosen = selectedOpt == opt
                    val shape = RoundedCornerShape(CoachV7.Radius.s)
                    val borderColor = when {
                        submitted && opt == variant.correctOption -> pine
                        submitted && chosen && opt != variant.correctOption -> v7Alert()
                        chosen -> colors.actionPrimary
                        else -> colors.borderSubtle
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(if (chosen) colors.actionPrimary.copy(alpha = 0.08f) else colors.surface1)
                            .border(1.dp, borderColor, shape)
                            .clickable(enabled = !submitted) { selectedOpt = opt }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("practice-variant-opt-$idx"),
                    ) {
                        Text(opt, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
                    }
                }
                if (!submitted) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        PrimaryButton(
                            onClick = {
                                submitted = true
                                if (selectedOpt == variant.correctOption) {
                                    cleared = true
                                    onVariantMastered(variant)
                                }
                            },
                            enabled = !selectedOpt.isNullOrBlank(),
                            modifier = Modifier.testTag("practice-variant-submit"),
                        ) {
                            Text("核对变式")
                        }
                    }
                } else {
                    val ok = selectedOpt == variant.correctOption
                    Text(
                        text = if (ok && cleared) "变式做对了，已清除该概念的错因标记。" else "这道变式还没稳，再对照原页看一遍。",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) pine else v7Alert(),
                        modifier = Modifier.testTag(if (ok) "practice-variant-cleared" else "practice-variant-retry-hint"),
                    )
                    if (variant.targetedTrapExplanation.isNotBlank()) {
                        Text(variant.targetedTrapExplanation, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                    }
                    if (variant.pdfPage > 0) {
                        RubricPagePill(
                            pdfPage = variant.pdfPage,
                            onClick = { onJumpPage(variant.pdfPage, variant.quoteAnchor) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun RubricPagePill(
    pdfPage: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "课本第 $pdfPage 页",
    tag: String? = null,
) {
    val shape = RoundedCornerShape(CoachV7.Radius.s)
    val pine = v7Pine()
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = LocalV7Fonts.current.numbers,
        color = pine,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(shape)
            .background(pine.copy(alpha = 0.08f))
            .border(1.dp, pine.copy(alpha = 0.30f), shape)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(tag ?: "rubric-page-pill-$pdfPage"),
    )
}

internal fun buildHighlightedDraft(
    draft: String,
    quotes: List<String>,
    highlightColor: androidx.compose.ui.graphics.Color,
) = buildAnnotatedString {
    append(draft)
    for (q in quotes) {
        val clean = q.trim()
        if (clean.length < 3) continue
        val idx = draft.indexOf(clean, ignoreCase = true)
        if (idx >= 0) {
            addStyle(SpanStyle(background = highlightColor), idx, (idx + clean.length).coerceAtMost(draft.length))
        }
    }
}

private fun formatCompactMark(v: Double): String {
    val r = kotlin.math.round(v * 10.0) / 10.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else "%.1f".format(r)
}
