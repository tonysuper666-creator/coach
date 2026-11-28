package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.coach.ui.CoachTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConceptMasteryMapTest {
    @Test fun answersLandOnConceptsWhoseSentenceIsInThatChapter() {
        val root = Files.createTempDirectory("coach-concept-map-")
        try {
            val lib = TextbookLibrary(root.resolve("library"))
            val book = lib.importBook(syntheticTextbook(root.resolve("synthetic")))
            val page = book.pages.first()
            val sentence = page.text.lineSequence().map { it.trim() }.firstOrNull { it.length >= 24 }
            assertNotNull(sentence, "imported page should contain a sentence long enough to anchor")
            val chapter = assertNotNull(BookChapterResolver.chapterForPage(book, page.number))
            val left = sentence.take(24)
            val right = sentence.takeLast(24)
            assertTrue(left in page.text && right in page.text)

            val written = lib.recordConceptAnswers(
                book = book,
                expectedChapterId = chapter.tocNodeId,
                candidates = listOf(
                    ConceptQuoteCandidate("photosynthesis", page.number, left, scoreRatio = 1.0),
                    ConceptQuoteCandidate("stored sugars", page.number, right, scoreRatio = 0.0),
                ),
                kind = ConceptAnswerKind.MCQ,
                signal = LearnerSignalType.POST_QUIZ_T1,
                guessed = false,
                questionId = "q-two",
                prompt = "Which process makes sugars?",
            )
            assertEquals(2, written, "one question covering two concepts records both")

            repeat(20) { n ->
                assertEquals(
                    1,
                    lib.recordConceptAnswers(
                        book = book,
                        expectedChapterId = chapter.tocNodeId,
                        candidates = listOf(
                            ConceptQuoteCandidate("photosynthesis", page.number, left, scoreRatio = 1.0),
                        ),
                        kind = ConceptAnswerKind.CHAPTER_END,
                        signal = LearnerSignalType.POST_QUIZ_T1,
                        guessed = false,
                        questionId = "q-$n",
                    ),
                )
            }

            assertEquals(
                0,
                lib.recordConceptAnswers(
                    book = book,
                    expectedChapterId = chapter.tocNodeId,
                    candidates = listOf(ConceptQuoteCandidate("missing", page.number, "this sentence is not in the book", scoreRatio = 1.0)),
                    kind = ConceptAnswerKind.RUBRIC,
                    signal = LearnerSignalType.RUBRIC_GRADE,
                    guessed = false,
                    questionId = "q-missing",
                ),
            )
            val otherChapter = book.chapterSpans.firstOrNull { it.tocNodeId != chapter.tocNodeId }?.tocNodeId ?: "not-this-chapter"
            assertEquals(
                0,
                lib.recordConceptAnswers(
                    book = book,
                    expectedChapterId = otherChapter,
                    candidates = listOf(ConceptQuoteCandidate("wrong chapter", page.number, left, scoreRatio = 1.0)),
                    kind = ConceptAnswerKind.MCQ,
                    signal = LearnerSignalType.POST_QUIZ_T1,
                    guessed = false,
                    questionId = "q-wrong-chapter",
                ),
            )

            val map = lib.conceptMasteryMap(book)
            val nodeChapter = map.single { it.chapterId == chapter.tocNodeId }
            assertEquals(2, nodeChapter.concepts.size)
            assertTrue(nodeChapter.concepts.none { it.name == "missing" || it.name == "wrong chapter" })
            val photo = nodeChapter.concepts.single { it.name == "photosynthesis" }
            assertEquals(21, photo.evidenceCount)
            assertEquals("会了", photo.levelLabel)
            photo.evidence.forEach { line ->
                val located = ConceptMasteryMap.locateQuote(book.page(line.page)!!.text, line.quote, line.startUtf16)
                assertNotNull(located)
                val owner = BookChapterResolver.chapterForPage(book, line.page)
                assertEquals(chapter.tocNodeId, owner?.tocNodeId)
                val anchor = ConceptMasteryMap.anchorFor(line)
                val pageText = book.page(anchor.pdfPage)!!.text
                assertEquals(anchor.quoteAnchor, pageText.substring(anchor.startUtf16, anchor.endUtf16))
            }

            val reopened = TextbookLibrary(lib.directory).let { fresh ->
                fresh.conceptMasteryMap(fresh.book(book.id))
            }
            val restored = reopened.single { it.chapterId == chapter.tocNodeId }.concepts.single { it.name == "photosynthesis" }
            assertEquals(photo.evidenceCount, restored.evidenceCount)
            assertEquals(photo.levelLabel, restored.levelLabel)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun guessedAnswerDoesNotCountAsMastered() {
        val root = Files.createTempDirectory("coach-concept-guess-")
        try {
            val lib = TextbookLibrary(root.resolve("library"))
            val book = lib.importBook(syntheticTextbook(root.resolve("synthetic")))
            val page = book.pages.first()
            val sentence = page.text.lineSequence().map { it.trim() }.first { it.length >= 24 }
            val chapter = assertNotNull(BookChapterResolver.chapterForPage(book, page.number))
            lib.recordConceptAnswers(
                book = book,
                expectedChapterId = chapter.tocNodeId,
                candidates = listOf(ConceptQuoteCandidate("photosynthesis", page.number, sentence.take(24), scoreRatio = 1.0)),
                kind = ConceptAnswerKind.MCQ,
                signal = LearnerSignalType.PRETEST_T0,
                guessed = true,
                questionId = "q-guess",
            )
            val node = lib.conceptMasteryMap(book).single().concepts.single()
            assertEquals("还没测过", node.levelLabel)
            assertTrue(node.evidence.single().label.contains("猜"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun evidenceLabelsFollowQuestionSourceInsteadOfMislabelingPracticeAsChapterEnd() {
        val root = Files.createTempDirectory("coach-concept-source-label-")
        try {
            val lib = TextbookLibrary(root.resolve("library"))
            val imported = lib.importBook(syntheticTextbook(root.resolve("synthetic")))
            val page = imported.pages.first()
            val sentence = page.text.lineSequence().map { it.trim() }.first { it.length >= 24 }
            val quote = sentence.take(24)
            val chapter = assertNotNull(BookChapterResolver.chapterForPage(imported, page.number))
            val bodySection = imported.lessons.first { page.number in it.firstPage..it.lastPage }

            val practiceQuiz = BookCompanionQuiz(
                id = "${bodySection.id}:prac:0:0",
                sectionId = bodySection.id,
                stem = "Which mechanism explains this concept?",
                options = listOf("Alpha", "Beta"),
                correctOption = "Alpha",
                anchor = BookCompanionAnchor(page.number, "b1", 0, quote.length, quote),
            )
            val pretestQuiz = practiceQuiz.copy(id = "${bodySection.id}:pre:0:0", stem = "Pretest check?")
            val sectionCheckQuiz = practiceQuiz.copy(id = "${bodySection.id}:sec:0:0", stem = "Section summary check?")

            assertEquals(
                ConceptAnswerKind.PRACTICE,
                ConceptMasteryMap.kindForQuiz(imported, practiceQuiz, ConceptAnswerKind.PRACTICE),
            )
            assertEquals(
                ConceptAnswerKind.PRETEST,
                ConceptMasteryMap.kindForQuiz(imported, pretestQuiz, ConceptAnswerKind.PRETEST),
            )
            assertEquals(
                ConceptAnswerKind.SECTION_CHECK,
                ConceptMasteryMap.kindForQuiz(imported, sectionCheckQuiz, ConceptAnswerKind.SECTION_CHECK),
            )
            assertEquals(
                ConceptAnswerKind.PRACTICE_RUBRIC,
                ConceptMasteryMap.kindForQuiz(imported, practiceQuiz, ConceptAnswerKind.PRACTICE_RUBRIC),
            )

            // Explicit chapter-end quizzes or generic quizzes belonging to a dedicated exercise section are labeled 章末题.
            val baseStruct = assertNotNull(imported.structure)
            val exerciseSec = BookSection(
                id = "ch1-review",
                title = "1.5 Review Questions",
                breadcrumb = "${bodySection.breadcrumb} › 1.5 Review Questions",
                level = 2,
                startPage = page.number,
                endPage = page.number,
                blockIds = bodySection.blocks.map { it.id },
            )
            val updatedStruct = baseStruct.copy(sections = baseStruct.sections + exerciseSec).also { s ->
                s.deferResolvedLessons { s.toLessons(imported.id, imported.pages) }
            }
            val bookWithExercises = imported.copy(structure = updatedStruct)
            val chapterEndQuiz = practiceQuiz.copy(
                id = "ch1-review:eoc:0",
                sectionId = "ch1-review",
                stem = "Chapter review?",
                kind = "CHAPTER_END",
            )
            assertEquals(
                ConceptAnswerKind.CHAPTER_END,
                ConceptMasteryMap.kindForQuiz(bookWithExercises, chapterEndQuiz, ConceptAnswerKind.PRACTICE),
            )
            val genericExerciseQuiz = practiceQuiz.copy(
                id = "ch1-review:q0",
                sectionId = "ch1-review",
                stem = "End-of-chapter generic MCQ?",
                kind = "MCQ",
            )
            assertEquals(
                ConceptAnswerKind.CHAPTER_END,
                ConceptMasteryMap.kindForQuiz(bookWithExercises, genericExerciseQuiz, ConceptAnswerKind.MCQ),
            )

            lib.recordConceptAnswers(
                book = imported,
                expectedChapterId = chapter.tocNodeId,
                candidates = listOf(ConceptQuoteCandidate("practice concept", page.number, quote, scoreRatio = 1.0)),
                kind = ConceptMasteryMap.kindForQuiz(imported, practiceQuiz, ConceptAnswerKind.PRACTICE),
                signal = LearnerSignalType.POST_QUIZ_T1,
                guessed = false,
                questionId = practiceQuiz.id,
                prompt = practiceQuiz.stem,
            )
            lib.recordConceptAnswers(
                book = imported,
                expectedChapterId = chapter.tocNodeId,
                candidates = listOf(ConceptQuoteCandidate("pretest concept", page.number, quote, scoreRatio = 0.0)),
                kind = ConceptMasteryMap.kindForQuiz(imported, pretestQuiz, ConceptAnswerKind.PRETEST),
                signal = LearnerSignalType.PRETEST_T0,
                guessed = false,
                questionId = pretestQuiz.id,
                prompt = pretestQuiz.stem,
            )
            lib.recordConceptAnswers(
                book = imported,
                expectedChapterId = chapter.tocNodeId,
                candidates = listOf(ConceptQuoteCandidate("section check concept", page.number, quote, scoreRatio = 1.0)),
                kind = ConceptMasteryMap.kindForQuiz(imported, sectionCheckQuiz, ConceptAnswerKind.SECTION_CHECK),
                signal = LearnerSignalType.POST_QUIZ_T1,
                guessed = false,
                questionId = sectionCheckQuiz.id,
                prompt = sectionCheckQuiz.stem,
            )
            lib.recordConceptAnswers(
                book = imported,
                expectedChapterId = chapter.tocNodeId,
                candidates = listOf(ConceptQuoteCandidate("chapter end concept", page.number, quote, scoreRatio = 1.0)),
                kind = ConceptMasteryMap.kindForQuiz(bookWithExercises, chapterEndQuiz, ConceptAnswerKind.PRACTICE),
                signal = LearnerSignalType.POST_QUIZ_T1,
                guessed = false,
                questionId = chapterEndQuiz.id,
                prompt = chapterEndQuiz.stem,
            )
            // Legacy stored rows recorded before v=2 with kind=CHAPTER_END and `:prac:` resolve to 练习题 even on shared pages,
            // while true lesson exercise answers (`:CHOICE` without `:prac:`) stay 章末题.
            assertEquals(
                ConceptAnswerKind.PRACTICE,
                ConceptMasteryMap.resolveStoredKind(
                    book = bookWithExercises,
                    page = page.number,
                    signal = LearnerSignalType.POST_QUIZ_T1,
                    rawKind = ConceptAnswerKind.CHAPTER_END,
                    questionId = "${bodySection.id}:prac:0:9",
                ),
            )
            assertEquals(
                ConceptAnswerKind.CHAPTER_END,
                ConceptMasteryMap.resolveStoredKind(
                    book = bookWithExercises,
                    page = page.number,
                    signal = LearnerSignalType.POST_QUIZ_T1,
                    rawKind = ConceptAnswerKind.CHAPTER_END,
                    questionId = "ch1-review:CHOICE",
                ),
            )

            val byName = lib.conceptMasteryMap(bookWithExercises)
                .single { it.chapterId == chapter.tocNodeId }
                .concepts
                .associateBy { it.name }

            val practiceLabel = assertNotNull(byName["practice concept"]).evidence.single().label
            assertTrue(practiceLabel.startsWith("练习题答对"), "expected 练习题答对, got: $practiceLabel")
            assertTrue(!practiceLabel.contains("章末题"), "practice question must not be labeled 章末题: $practiceLabel")

            val pretestLabel = assertNotNull(byName["pretest concept"]).evidence.single().label
            assertTrue(pretestLabel.startsWith("读前自测答错"), "expected 读前自测答错, got: $pretestLabel")

            val sectionLabel = assertNotNull(byName["section check concept"]).evidence.single().label
            assertTrue(sectionLabel.startsWith("节末自测答对"), "expected 节末自测答对, got: $sectionLabel")

            val chapterEndLabel = assertNotNull(byName["chapter end concept"]).evidence.single().label
            assertTrue(chapterEndLabel.startsWith("章末题答对"), "expected 章末题答对, got: $chapterEndLabel")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun companionUiRecordsPretestAndPracticeWithSourceLabelsInMasteryPanel() {
        val root = Files.createTempDirectory("coach-companion-mastery-ui-")
        try {
            val lib = TextbookLibrary(root.resolve("library"))
            val book = lib.importBook(syntheticTextbook(root.resolve("synthetic")))
            val page = book.pages.first()
            val sentence = page.text.lineSequence().map { it.trim() }.first { it.length >= 24 }
            val quote = sentence.take(24)
            val start = page.text.indexOf(quote)
            val group = assertNotNull(ChapterCompanionGrouping.groupForSection(book, 0))
            val sec = group.sections.first()
            val anchor = BookCompanionAnchor(page.number, "${book.id}:p${page.number}:0", start, start + quote.length, quote)
            val pack = BookCompanionPack(
                bookId = book.id,
                chapterId = group.chapterId,
                chapterTitle = group.chapterTitle,
                keyPoints = listOf(BookCompanionKeyPoint("kp-1", sec.id, "Core biological process", anchor)),
                pretest = listOf(
                    BookCompanionQuiz(
                        id = "${sec.id}:pre:0:0",
                        sectionId = sec.id,
                        stem = "Which process captures light energy?",
                        options = listOf("Photosynthesis", "Respiration"),
                        correctOption = "Photosynthesis",
                        anchor = anchor,
                    ),
                ),
                optionalPractice = listOf(
                    BookCompanionQuiz(
                        id = "${sec.id}:prac:0:0",
                        sectionId = sec.id,
                        stem = "What converts solar energy into sugars?",
                        options = listOf("Photosynthesis", "Fermentation"),
                        correctOption = "Photosynthesis",
                        anchor = anchor,
                    ),
                ),
            )
            lib.saveChapterCompanion(book.id, group.chapterId, pack, book)

            runComposeUiTest {
                var draft by mutableStateOf(ChapterPracticeDraft(bookId = book.id, tocNodeId = group.chapterId, page = page.number))
                setContent {
                    CoachTheme(false) {
                        Box(Modifier.size(640.dp, 820.dp)) {
                            SingleBookCompanionPanel(
                                book = book,
                                currentPage = page.number,
                                currentSectionIndex = 0,
                                explainer = BookExplainer(library = lib),
                                library = lib,
                                draft = draft,
                                onDraftChange = { draft = it },
                                onOpenAnchor = {},
                                onCommitQuizAnswer = { _, _, _, _ -> },
                                onAskAboutText = {},
                                onOpenLogin = {},
                            )
                        }
                    }
                }

                onNodeWithTag("companion-mod-pretest").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("pretest-opt-0-0").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("pretest-opt-0-0").performClick()
                onNodeWithTag("pretest-submit-0").performClick()

                onNodeWithTag("companion-mod-practice").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("practice-opt-0-0").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("practice-opt-0-0").performClick()
                onNodeWithTag("practice-submit-0").performClick()

                onNodeWithTag("companion-mod-mastery").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("concept-mastery-row-0").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("Core biological process").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("练习题答对", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("练习题答对", substring = true).assertIsDisplayed()
                onNodeWithText("读前自测答对", substring = true).assertIsDisplayed()
                assertTrue(
                    onAllNodesWithText("章末题答对", substring = true).fetchSemanticsNodes().isEmpty(),
                    "Body practice question must not display 章末题答对 in mastery panel",
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun optionalPracticeSharingPageWithChapterEndHeadingsKeepsPracticeSource() {
        val root = Files.createTempDirectory("coach-concept-shared-page-")
        try {
            val lib = TextbookLibrary(root.resolve("library"))
            val imported = lib.importBook(syntheticTextbook(root.resolve("synthetic")))
            val sharedPage = imported.pages.last()
            val sentences = sharedPage.text.lineSequence().map { it.trim() }.filter { it.length >= 24 }.toList()
            val firstQuote = sentences.first().take(24)
            val secondQuote = (sentences.getOrNull(1) ?: sentences.first()).takeLast(24)
            val chapter = assertNotNull(BookChapterResolver.chapterForPage(imported, sharedPage.number))
            val bodySec = imported.lessons.first { sharedPage.number in it.firstPage..it.lastPage }
            val baseStruct = assertNotNull(imported.structure)

            val critSec = BookSection(
                id = "${imported.id}:p${sharedPage.number}:41",
                title = "Critical Thinking Questions",
                breadcrumb = "${bodySec.breadcrumb} › Critical Thinking Questions",
                level = 2,
                startPage = sharedPage.number,
                endPage = sharedPage.number,
                blockIds = bodySec.blocks.take(1).map { it.id },
            )
            val appSec = BookSection(
                id = "${imported.id}:p${sharedPage.number}:1036",
                title = "Personal Application Questions",
                breadcrumb = "${bodySec.breadcrumb} › Personal Application Questions",
                level = 2,
                startPage = sharedPage.number,
                endPage = sharedPage.number,
                blockIds = bodySec.blocks.takeLast(1).map { it.id },
            )
            val sharedToc = baseStruct.tocNodes + listOf(
                BookTocNode(
                    id = "${imported.id}:toc:crit",
                    parentId = chapter.tocNodeId,
                    ordinal = 90,
                    level = 2,
                    kind = TocNodeKind.SECTION,
                    numberLabel = null,
                    title = "Critical Thinking Questions",
                    startPage = sharedPage.number,
                    endPage = sharedPage.number,
                    source = HeadingSource.PDF_OUTLINE,
                ),
                BookTocNode(
                    id = "${imported.id}:toc:app",
                    parentId = chapter.tocNodeId,
                    ordinal = 91,
                    level = 2,
                    kind = TocNodeKind.SECTION,
                    numberLabel = null,
                    title = "Personal Application Questions",
                    startPage = sharedPage.number,
                    endPage = sharedPage.number,
                    source = HeadingSource.PDF_OUTLINE,
                ),
            )
            val sharedStruct = baseStruct.copy(
                sections = baseStruct.sections + listOf(critSec, appSec),
                tocNodes = sharedToc,
            ).also { s ->
                s.deferResolvedLessons { s.toLessons(imported.id, imported.pages) }
            }
            val sharedBook = imported.copy(structure = sharedStruct)

            // 1. Body section question citing a sentence on the same PDF page as deeper chapter-end TOC headings.
            val bodySharedPractice = BookCompanionQuiz(
                id = "${bodySec.id}:prac:0:0",
                sectionId = bodySec.id,
                stem = "Which statement summarizes the body paragraph on the shared page?",
                options = listOf("Option A", "Option B"),
                correctOption = "Option A",
                kind = "MCQ",
                anchor = BookCompanionAnchor(sharedPage.number, "b-shared-1", 0, firstQuote.length, firstQuote),
            )
            val bodySharedGeneric = bodySharedPractice.copy(id = "generic-body-shared")
            assertEquals(
                ConceptAnswerKind.PRACTICE,
                ConceptMasteryMap.kindForQuiz(sharedBook, bodySharedPractice, ConceptAnswerKind.PRACTICE),
            )
            assertEquals(
                ConceptAnswerKind.MCQ,
                ConceptMasteryMap.kindForQuiz(sharedBook, bodySharedGeneric, ConceptAnswerKind.MCQ),
            )

            // 2. Companion optionalPractice MCQs (#21/#22 pattern on shared final chapter page) preserve PRACTICE source.
            val prac21 = bodySharedPractice.copy(
                id = "${critSec.id}:prac:0:0",
                sectionId = critSec.id,
                anchor = BookCompanionAnchor(sharedPage.number, "b-shared-21", 0, firstQuote.length, firstQuote),
            )
            val prac22 = bodySharedPractice.copy(
                id = "${appSec.id}:prac:0:0",
                sectionId = appSec.id,
                anchor = BookCompanionAnchor(sharedPage.number, "b-shared-22", 0, secondQuote.length, secondQuote),
            )
            for (quiz in listOf(bodySharedPractice, prac21, prac22)) {
                val classified = ConceptMasteryMap.kindForQuiz(sharedBook, quiz, ConceptAnswerKind.PRACTICE)
                assertEquals(ConceptAnswerKind.PRACTICE, classified, "quiz ${quiz.id} must stay PRACTICE")
                lib.recordConceptAnswers(
                    book = sharedBook,
                    expectedChapterId = chapter.tocNodeId,
                    candidates = listOf(ConceptQuoteCandidate("shared-${quiz.id.takeLast(8)}", sharedPage.number, quiz.anchor!!.quoteAnchor, scoreRatio = 1.0)),
                    kind = classified,
                    signal = LearnerSignalType.POST_QUIZ_T1,
                    guessed = false,
                    questionId = quiz.id,
                    prompt = quiz.stem,
                )
            }

            val allLabels = lib.conceptMasteryMap(sharedBook)
                .flatMap { it.concepts }
                .flatMap { it.evidence }
                .map { it.label }
            assertEquals(3, allLabels.size)
            assertTrue(allLabels.all { it.startsWith("练习题答对") }, "expected all 练习题答对, got: $allLabels")
            assertTrue(allLabels.none { "章末题" in it }, "shared-page optional practice must never emit 章末题: $allLabels")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
