package com.coach.desktop.books

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
}
