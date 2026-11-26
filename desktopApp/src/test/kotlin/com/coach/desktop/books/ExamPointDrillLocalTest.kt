package com.coach.desktop.books

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
