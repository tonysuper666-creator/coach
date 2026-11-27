package com.coach.desktop.books

import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExamPointDrillLocalTest {
    private val quote = "Supply shifts when input costs fall."
    private val citation = CourseCitationRef(
        bookId = "tb",
        bookTitle = "Textbook",
        role = CourseMaterialRole.CORE_TEXTBOOK,
        page = 1,
        startUtf16 = 0,
        endUtf16 = quote.length,
        quote = quote,
    )

    @Test
    fun packWithoutDrillOmitsTheKeyAndRoundTripsWhenPresent() {
        val plain = CourseTopicPack(topicId = "p", summary = "s", authorityNotice = "n")
        val encoded = CourseJson.encodeTopicPack(plain)
        assertFalse(encoded.contains("\"drill\""))
        val restored = CourseJson.decodeTopicPack(encoded)
        assertNull(restored?.drill)

        val drill = sample(choices = 3, missing = 0)
        val withDrill = plain.copy(drill = drill)
        val again = CourseJson.decodeTopicPack(CourseJson.encodeTopicPack(withDrill))
        assertEquals(3, again?.drill?.choices?.size)
        assertEquals(0, again?.drill?.missingChoices)
        assertEquals("c1", again?.drill?.choices?.first()?.id)
        assertEquals(quote, again?.drill?.choices?.first()?.options?.first()?.citation?.quote)
        assertEquals("3 道单选 · 有论述提纲", ExamPointDrillLocal.statusLine(again?.drill))
    }

    @Test
    fun statusLineStatesTheGapOnlyWhenADrillExists() {
        assertNull(ExamPointDrillLocal.statusLine(null))
        val short = sample(choices = 2, missing = 1).copy(essay = null, essayMissing = true)
        assertEquals("缺 1 题 · 论述提纲未通过核对", ExamPointDrillLocal.statusLine(short))
        val empty = sample(choices = 0, missing = 3).copy(essay = null, emptyReason = "只有往年题，不能进题库")
        assertEquals("只有往年题，不能进题库 · 缺 3 题", ExamPointDrillLocal.statusLine(empty))
    }

    @Test
    fun localVerifyDropsACitationThatIsNotOnThePage() {
        val pages = mapOf("tb" to listOf(BookPage(1, "Supply shifts when input costs fall. Demand slopes down.")))
        val bad = citation.copy(quote = "this sentence is not on the page")
        val drill = sample(choices = 1, missing = 2).copy(
            choices = listOf(sample(1, 2).choices.first().let { choice ->
                choice.copy(options = choice.options.mapIndexed { index, opt ->
                    if (index == 0) opt.copy(citation = bad) else opt
                })
            }),
        )
        val verified = ExamPointDrillLocal.verify(drill, pages)
        assertTrue(verified.choices.isEmpty())
        assertEquals(3, verified.missingChoices)
        assertEquals(1, verified.discards["source_mismatch"])
        assertEquals("引用对不上原文，这些题没有留下", verified.emptyReason)
    }

    @Test
    fun reportCountsPointsAgreementAndZeroQuestionReasons() {
        val ready = point("a", sample(3, 0).copy(checked = 3, agreed = 3))
        val empty = point("b", sample(0, 3).copy(checked = 2, agreed = 0, emptyReason = "只有往年题，不能进题库", discards = mapOf("source_mismatch" to 1)))
        val report = ExamPointDrillLocal.report(listOf(ready, empty))
        assertEquals(2, report.pointCount)
        assertEquals(listOf(3, 0), report.questionsPerPoint)
        assertEquals(5, report.checked)
        assertEquals(3, report.agreed)
        assertEquals(1, report.discards["source_mismatch"])
        assertEquals(listOf("第2个：只有往年题，不能进题库"), report.zeroNotes)
        assertFalse(report.summary().contains(quote))
        assertTrue(report.summary().contains("一致3/5"))
    }

    @Test
    fun refreshKeepsAnExistingDrillWhenTheNewPackHasNone() {
        val previous = CourseTopicPack(topicId = "p", summary = "old", authorityNotice = "", drill = sample(3, 0))
        val refreshed = CourseTopicPack(topicId = "p", summary = "new", authorityNotice = "")
        val kept = ExamPointDrillLocal.preserve(refreshed, previous)
        assertEquals(3, kept.drill?.choices?.size)
        assertEquals("new", kept.summary)
        val serverBody = Json.parseToJsonElement(ExamPointDrillLocal.encode(sample(4, 0)).toString()).jsonObject
        assertEquals(4, ExamPointDrillLocal.decode(serverBody)?.choices?.size)
    }

    @Test
    fun verifyPreservesTruthfulEmptyReasonFromServer() {
        val pages = mapOf("tb" to listOf(BookPage(1, "Supply shifts when input costs fall.")))
        val reasons = listOf(
            "模型没有写出可用的题",
            "材料只有目录页，无法出题",
            "核对全部拒掉（答案不一致）",
        )
        for (reason in reasons) {
            val empty = sample(choices = 0, missing = 3).copy(
                essay = null,
                discards = mapOf("malformed" to 1),
                emptyReason = reason,
            )
            val verified = ExamPointDrillLocal.verify(empty, pages)
            assertEquals(reason, verified.emptyReason)
        }
        // Even if emptyReason was omitted when checked > 0, never mislabel as citation mismatch
        val rejectedNoReason = sample(choices = 0, missing = 3).copy(
            essay = null,
            checked = 5,
            agreed = 0,
            discards = mapOf("answer_mismatch" to 5),
            emptyReason = null,
        )
        assertEquals("核对全部拒掉", ExamPointDrillLocal.verify(rejectedNoReason, pages).emptyReason)
    }

    @Test
    fun generateExamPointDrillsRunsInParallelReportsProgressAndResumesAfterInterruption() = runBlocking {
        val dir = Files.createTempDirectory("coach-drill-parallel-")
        try {
            val libDir = dir.resolve("lib")
            val library = TextbookLibrary(libDir, lazyPdfExtras = false)
            val terms = listOf(
                "Tidal locking", "Lunar phase", "Axial tilt",
                "Solar wind", "Orbital period", "Escape velocity",
            )
            val tbFile = dir.resolve("Synthetic-Textbook.md")
            Files.writeString(
                tbFile,
                terms.joinToString("\n\n") { "$it: A synthetic textbook explanation of $it across orbital systems." },
            )
            val slidesFile = dir.resolve("Week1-Slides.md")
            Files.writeString(
                slidesFile,
                terms.joinToString("\n\n") { "Week 1 discussion applies $it in a synthetic scenario." },
            )
            val tbBook = library.stageBookImport(tbFile).book
            val slideBook = library.stageBookImport(slidesFile).book
            val courseId = library.withCourseDatabase { db ->
                val (cid, _) = CourseStore.createCourse(
                    db,
                    "Synthetic Astronomy",
                    CourseExamProfile(examEnabled = true),
                    listOf(tbBook.id, slideBook.id),
                )
                CourseAutoAligner.alignLocally(db, cid)
                cid
            }

            val active = AtomicInteger(0)
            val peak = AtomicInteger(0)
            val completedBeforeCancel = AtomicInteger(0)
            val calledTopics = ConcurrentHashMap.newKeySet<String>()

            fun fakeExplainer(cancelAfterTwo: Boolean): BookExplainer = object : BookExplainer(library = library) {
                init {
                    identity = DesktopIdentity("http://127.0.0.1:18791", PairedCredentials("dev", "tok"))
                    loaded = true
                }
                override suspend fun load() { loaded = true }
                override suspend fun examPointDrill(input: JsonObject): CourseServerCallResult {
                    val topicId = (input["topicId"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    val title = (input["topicTitle"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    calledTopics += topicId
                    val cur = active.incrementAndGet()
                    peak.accumulateAndGet(cur, ::maxOf)
                    try {
                        delay(80)
                        if (cancelAfterTwo && completedBeforeCancel.incrementAndGet() > 2) {
                            delay(500)
                        }
                        val quoteText = "$title: A synthetic textbook explanation of $title across orbital systems."
                        val cit = CourseCitationRef(tbBook.id, tbBook.title, CourseMaterialRole.CORE_TEXTBOOK, 1, 0, quoteText.length, quote = quoteText)
                        val drill = sample(choices = 3, missing = 0).copy(
                            choices = (1..3).map { i ->
                                TeachingChoice("c$i", "Question $i about $title?", listOf("A", "B", "C", "D").map { l ->
                                    TeachingChoiceOption(l, "Opt $l", l == "A", "Reason $l", cit)
                                })
                            },
                            essay = null,
                        )
                        return CourseServerCallResult(ExamPointDrillLocal.encode(drill), null)
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }

            // Pass 1: run with concurrency = 3, then cancel mid-way after 2 points finish
            val controller1 = TextbookController(library)
            controller1.openCourse(courseId)
            val job = launch {
                controller1.generateExamPointDrills(courseId, fakeExplainer(cancelAfterTwo = true), concurrency = 3)
            }
            var waited = 0
            while (waited < 4000) {
                val done = library.courseDetail(courseId).examinableTopics
                    .filter { it.weekLabel == CourseExamPoints.LABEL }
                    .count { ExamPointDrillLocal.isCompleted(it) }
                if (done >= 2) break
                delay(30)
                waited += 30
            }
            job.cancel(CancellationException("simulated close"))
            job.join()

            assertTrue(peak.get() >= 2, "Expected parallel execution (>=2 concurrent), got ${peak.get()}")
            val persistedAfterClose = library.courseDetail(courseId).examinableTopics
                .filter { it.weekLabel == CourseExamPoints.LABEL && ExamPointDrillLocal.isCompleted(it) }
                .map { it.topicId }
                .toSet()
            assertTrue(persistedAfterClose.size in 2..5, "Expected partial persisted progress, got ${persistedAfterClose.size}")

            // Pass 2: reopen fresh library & controller -> resume skips already-saved points!
            val reopenedLibrary = TextbookLibrary(libDir, lazyPdfExtras = false)
            val controller2 = TextbookController(reopenedLibrary)
            controller2.openCourse(courseId)
            calledTopics.clear()
            controller2.generateExamPointDrills(courseId, fakeExplainer(cancelAfterTwo = false), concurrency = 3)

            // Already-persisted points must NOT be regenerated!
            for (already in persistedAfterClose) {
                assertFalse(already in calledTopics, "Resumed run must skip already-completed topic $already")
            }
            assertEquals(6 - persistedAfterClose.size, calledTopics.size)

            val finalPoints = reopenedLibrary.courseDetail(courseId).examinableTopics
                .filter { it.weekLabel == CourseExamPoints.LABEL }
            assertEquals(6, finalPoints.count { ExamPointDrillLocal.isCompleted(it) })
            val progress = controller2.state.value.examDrillProgress
            assertEquals(6, progress?.completed)
            assertEquals(6, progress?.total)
            assertEquals(false, progress?.running)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun sample(choices: Int, missing: Int): ExamPointDrill {
        val rows = (1..choices).map { index ->
            TeachingChoice(
                id = "c$index",
                stem = "Why does a lower input cost move the curve $index?",
                options = listOf("A", "B", "C", "D").map { letter ->
                    TeachingChoiceOption(letter, "option $letter", letter == "A", "because $letter", citation)
                },
            )
        }
        return ExamPointDrill(
            choices = rows,
            essay = EssayOutline(
                thesis = "Cheaper inputs change the amount offered.",
                paragraphs = listOf(
                    EssayOutlineParagraph("Firms offer more when an input gets cheaper.", citation),
                    EssayOutlineParagraph("Buyers still want less at a higher price.", citation),
                ),
            ),
            missingChoices = missing,
            essayMissing = false,
            discards = emptyMap(),
            checked = choices,
            agreed = choices,
            emptyReason = null,
        )
    }

    private fun point(id: String, drill: ExamPointDrill) = CourseTopicEntry(
        topicId = id,
        collectionId = "course",
        weekLabel = CourseExamPoints.LABEL,
        weekNum = null,
        ord = 1,
        title = "point",
        examStatus = CourseTopicStatus.EXAMINABLE,
        userLocked = false,
        pack = CourseTopicPack(topicId = id, summary = "s", authorityNotice = "", drill = drill),
    )
}
