package com.coach.desktop.books

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadingMethodTest {
    @Test
    fun tenBookBaselinePicksTheMethodAReaderWouldExpect() {
        val argument = "这是按章展开的论述，没有成套习题，读到一节就停下来，用自己的话说。"
        val literature = "这是文学作品，读到一节就停一下，用自己的话说刚刚读到的意思。"
        val formula = "这本有公式，但没有章末习题，先不按先做后讲，读到一节停下来用自己的话说。"
        val generic = "这本看不出成套的习题和公式，读到一节就停下来，用自己的话说。"
        val textbook = "这本书有习题，也有公式，先做几道再读，读完对照。"
        val cases = listOf(
            Case("Health-Economics-620p.pdf", sig(exercise = 23, formula = 72, chapters = 24, pages = 620, format = "pdf"), ReadingMethod.BEFORE, textbook),
            Case("Wealth-of-Nations-Smith.epub", sig(chapters = 32, pages = 590, format = "epub", argument = 1), ReadingMethod.DURING, argument),
            Case("Nahan-LuXun.epub", sig(chapters = 14, pages = 35, format = "epub", literature = 1), ReadingMethod.DURING, literature),
            Case("Principle-of-Population-Malthus.epub", sig(chapters = 19, pages = 88, format = "epub", argument = 1), ReadingMethod.DURING, argument),
            Case("Principles-of-Political-Economy-Ricardo.epub", sig(chapters = 31, pages = 178, format = "epub", formula = 8), ReadingMethod.DURING, formula),
            Case("Nahan-LuXun-utf8.txt", sig(chapters = 9, pages = 33, format = "txt"), ReadingMethod.DURING, literature),
            Case("P5-Nahan-GBK.txt", sig(chapters = 0, pages = 21, format = "txt"), ReadingMethod.DURING, literature),
            Case("ECNM10119-Week1-Lecture-Slides.pdf", sig(chapters = 1, pages = 60, format = "pdf"), ReadingMethod.DURING, generic),
            Case("P5-scanned-Nahan-24p.pdf", sig(chapters = 0, pages = 24, format = "pdf"), ReadingMethod.DURING, generic),
            Case("Outline-of-Science-Vol1-illustrated.epub", sig(chapters = 8, pages = 409, format = "epub"), ReadingMethod.DURING, argument),
        )
        assertEquals(10, cases.size)
        cases.forEach { item ->
            val choice = ReadingMethodSelector.choose(item.signals)
            assertEquals(item.method, choice.method, item.file)
            assertEquals(item.reason, choice.reason, item.file)
        }
        val reread = ReadingMethodSelector.choose(cases.first().signals.copy(alreadyRead = true))
        assertEquals(ReadingMethod.AFTER, reread.method)
        assertEquals("你标了读过了，这一遍先回忆要点再做题，错的地方回书看。", reread.reason)
        val review = ReadingMethodSelector.choose(ReadingMethodSignals(forReview = true))
        assertEquals("这本是拿来复习的，先回忆要点再做题，错的地方回书看。", review.reason)
    }

    @Test
    fun manualSwitchKeepsTheOtherMethodAndRoundTrips() {
        val started = ReadingMethodBookState(
            active = ReadingMethod.BEFORE,
            userLocked = false,
            reason = "auto",
            alreadyRead = false,
            forReview = false,
        ).withProgress("ch1", ReadingMethod.BEFORE, ChapterMethodProgress(step = "READ", answers = mapOf("q1" to "A")))
        val switched = started.switched(ReadingMethod.DURING)
        assertEquals(ReadingMethod.DURING, switched.active)
        assertTrue(switched.userLocked)
        assertEquals("A", switched.progress("ch1", ReadingMethod.BEFORE).answers["q1"])
        assertEquals("READ", switched.progress("ch1", ReadingMethod.DURING).step)
        val restored = ReadingMethodBookState.decode(switched.encode())
        assertEquals(ReadingMethod.DURING, restored?.active)
        assertEquals("A", restored?.progress("ch1", ReadingMethod.BEFORE)?.answers?.get("q1"))
        val locked = ReadingMethodBookState.resolve(
            restored,
            ReadingMethodSignals(exerciseSections = 4, formulaMarks = 8),
        )
        assertEquals(ReadingMethod.DURING, locked.active)
        val again = switched.switched(ReadingMethod.AFTER).switched(ReadingMethod.BEFORE)
        assertEquals(ReadingMethod.BEFORE, again.active)
        assertEquals("A", again.progress("ch1", ReadingMethod.BEFORE).answers["q1"])
    }

    @Test
    fun absorbKeepsDiskProgressTheSessionHasNotTouched() {
        val memory = ReadingMethodBookState(ReadingMethod.BEFORE, true, "locked", false, false)
            .withProgress("a", ReadingMethod.BEFORE, ChapterMethodProgress("READ", answers = mapOf("q" to "A")))
        val disk = ReadingMethodBookState(ReadingMethod.DURING, false, "old", false, false)
            .withProgress("b", ReadingMethod.DURING, ChapterMethodProgress("PAUSE", sectionIndex = 1))
        val merged = memory.absorb(disk)
        assertEquals("A", merged.progress("a", ReadingMethod.BEFORE).answers["q"])
        assertEquals(1, merged.progress("b", ReadingMethod.DURING).sectionIndex)
        assertEquals(ReadingMethod.BEFORE, merged.active)
    }

    @Test
    fun flowsAdvanceOnlyWhenTheStepIsDoneAndReturnWrongItems() {
        val plan = ReadingChapterPlan(
            "ch",
            "章",
            listOf(ReadingSectionRef("s1", "第一节", 2), ReadingSectionRef("s2", "第二节", 3)),
        )
        val pretest = ReadingMethodFlow.beforeChecks(plan, emptyList())
        assertTrue(pretest.size in 3..5)
        var before = ChapterMethodProgress("PRETEST")
        assertEquals("PRETEST", ReadingMethodFlow.finishBeforePretest(before, pretest).step)
        pretest.forEach { before = ReadingMethodFlow.recordAnswer(before, it.id, "一句") }
        before = ReadingMethodFlow.finishBeforePretest(before, pretest)
        assertEquals("READ", before.step)
        assertEquals("DONE", ReadingMethodFlow.finishBeforeCompare(ReadingMethodFlow.finishBeforeRead(before)).step)

        val graded = (1..4).map {
            ReadingCheck("g$it", "题$it", ownWords = false, options = listOf("甲", "乙"), correct = "甲")
        }
        assertEquals(4, ReadingMethodFlow.beforeChecks(plan, graded).size)

        var during = ChapterMethodProgress("READ")
        during = ReadingMethodFlow.finishDuringPause(ReadingMethodFlow.finishDuringRead(during), 2)
        assertEquals("READ", during.step)
        assertEquals(1, during.sectionIndex)
        during = ReadingMethodFlow.finishDuringPause(ReadingMethodFlow.finishDuringRead(during), 2)
        assertEquals("DONE", during.step)
        assertEquals("pause-s2", ReadingMethodFlow.duringCheck(plan.sections[1], emptyList()).id)

        var after = ChapterMethodProgress("RECALL")
        assertEquals("RECALL", ReadingMethodFlow.finishRecall(after).step)
        after = ReadingMethodFlow.finishRecall(after.copy(recall = "要点"))
        assertEquals("QUIZ", after.step)
        val quiz = listOf(
            ReadingCheck("g", "题", ownWords = false, options = listOf("对", "错"), correct = "对", page = 4),
            ReadingCheck("o", "说", ownWords = true, page = 5),
        )
        after = ReadingMethodFlow.recordAnswer(after, "g", "错")
        after = ReadingMethodFlow.recordAnswer(after, "o", ReadingMethodFlow.NEED_BOOK)
        after = ReadingMethodFlow.finishQuiz(after, quiz)
        assertEquals(listOf("g", "o"), after.wrongIds)
        assertEquals("RETURN", after.step)
        after = ReadingMethodFlow.markReturned(after, "g")
        assertEquals("RETURN", after.step)
        after = ReadingMethodFlow.markReturned(after, "o")
        assertEquals("DONE", after.step)

        val passed = ReadingMethodFlow.finishQuiz(
            ChapterMethodProgress("QUIZ", answers = mapOf("g" to "对", "o" to "还记得")),
            quiz,
        )
        assertEquals("DONE", passed.step)
        assertTrue(passed.wrongIds.isEmpty())
    }

    @Test
    fun draftJsonKeepsMethodProgressBesideExistingAnswers() {
        val blank = CompanionJson.encodeDraft(ChapterPracticeDraft())
        assertFalse(blank.containsKey("readingMethod"))
        val choiceOnly = ReadingMethodBookState(ReadingMethod.DURING, false, "原因", false, false)
        assertFalse(ChapterPracticeDraft(readingMethodJson = choiceOnly.encode()).hasMeaningfulDraft())
        val worked = choiceOnly.withProgress(
            "ch",
            ReadingMethod.DURING,
            ChapterMethodProgress("PAUSE", answers = mapOf("pause-s" to "一句")),
        )
        assertTrue(ChapterPracticeDraft(readingMethodJson = worked.encode()).hasMeaningfulDraft())
        val draft = ChapterPracticeDraft(
            bookId = "b",
            tocNodeId = "ch",
            pretestSelections = mapOf("q" to "甲"),
            readingMethodJson = worked.encode(),
        )
        val encoded = CompanionJson.encodeDraft(draft)
        val decoded = CompanionJson.decodeDraft(encoded.toString())
        assertEquals("甲", decoded.pretestSelections["q"])
        val method = ReadingMethodBookState.decode(decoded.readingMethodJson)
        assertEquals("一句", method?.progress("ch", ReadingMethod.DURING)?.answers?.get("pause-s"))
        assertFalse(ChapterPracticeDraft(readingMethodJson = "not-json").hasMeaningfulDraft())
    }

    @Test
    fun fromBookUsesBodyStartTitlesAndFormulaMarks() {
        val front = BookPage(1, "a = 1\nb = 2\nc = 3\nd = 4\ne = 5\n史")
        val body = BookPage(
            10,
            "Exercises\na = 1\nb = 2\nc = 3\nThe problems of population are discussed across a sentence that is definitely longer than eighty characters.",
            illustrations = listOf(
                BookIllustration(page = 10, anchorOffset = 0, imageSha256 = "abc", extension = "png", width = 1, height = 1, kind = PageVisualKind.FORMULA),
            ),
        )
        val structure = BookStructure(
            pageRoles = emptyMap(),
            sections = emptyList(),
            source = HeadingSource.PDF_OUTLINE.name,
            tocNodes = listOf(
                BookTocNode("ch", null, 0, 1, TocNodeKind.CHAPTER, "1", "Chapter 1", 10, 12, source = HeadingSource.PDF_OUTLINE),
            ),
            nativeBodyStart = BookPageAnchor(10),
        )
        val book = Textbook(id = "b", title = "Sample", format = "pdf", pages = listOf(front, body), structure = structure)
        val signals = ReadingMethodSignalsReader.fromBook(book)
        assertEquals(1, signals.exerciseSections)
        assertEquals(4, signals.formulaMarks)
        assertEquals(0, signals.historyCues)
        assertEquals(ReadingMethod.BEFORE, ReadingMethodSelector.choose(signals).method)

        val story = Textbook(
            id = "story",
            title = "小说",
            format = "txt",
            pages = listOf(BookPage(1, "史")),
            structure = BookStructure(
                pageRoles = emptyMap(),
                sections = emptyList(),
                source = HeadingSource.MANUAL.name,
                tocNodes = listOf(
                    BookTocNode("c", null, 0, 1, TocNodeKind.CHAPTER, null, "一", 1, 1, source = HeadingSource.MANUAL),
                ),
            ),
        )
        val storySignals = ReadingMethodSignalsReader.fromBook(story)
        assertEquals(0, storySignals.historyCues)
        assertTrue(storySignals.literatureCues > 0)
        assertEquals("这是文学作品，读到一节就停一下，用自己的话说刚刚读到的意思。", ReadingMethodSelector.choose(storySignals).reason)

        val slides = ReadingMethodSignalsReader.fromBook(book, materialKind = "LECTURE_SLIDES")
        assertTrue(slides.lecture)
    }

    private data class Case(val file: String, val signals: ReadingMethodSignals, val method: ReadingMethod, val reason: String)

    private fun sig(
        exercise: Int = 0,
        formula: Int = 0,
        chapters: Int = 0,
        pages: Int = 0,
        format: String = "",
        argument: Int = 0,
        literature: Int = 0,
        history: Int = 0,
    ) = ReadingMethodSignals(
        exerciseSections = exercise,
        formulaMarks = formula,
        historyCues = history,
        argumentCues = argument,
        literatureCues = literature,
        chapterCount = chapters,
        pageCount = pages,
        format = format,
    )
}
