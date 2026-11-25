package com.coach.desktop.books

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class BookCitationWorkspaceTest {
    @Test fun returningFromCollectionCitationRestoresTheOriginBookAndPage() = runBlocking {
        val (controller, _) = setup()
        val library = controller.library
        val origin = controller.state.value.book!!
        val path = library.directory.resolveSibling("second.md")
        Files.writeString(path, "# Second book\n\nA distinct sentence for the citation target.")
        val target = library.importBook(path)
        controller.openBook(origin.id, targetPage = 2)
        val passage = target.sourceBlocks().first { it.isBodyParagraph }
        controller.jumpToCollectionCitation(CollectionQaCitation(
            CollectionQaVirtualCollections.READ_LIBRARY_ID, target.id, target.title, target.title,
            passage.page, quoteAnchor = passage.text,
        ))
        assertEquals(target.id, controller.state.value.book?.id)
        assertEquals(passage.page, controller.state.value.page)
        controller.back()
        val returned = controller.state.value
        assertEquals(BookScreen.READER, returned.screen)
        assertEquals(origin.id, returned.book?.id)
        assertEquals(2, returned.page)
    }

    @Test fun timeoutFallbackRequestsFocusOnFrameOwnerThread() {
        val calls = mutableListOf<Pair<String, Thread>>()
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                val fallback = remember { FocusRequester() }
                Box(Modifier.focusRequester(fallback).focusable().testTag("focus-fallback"))
                LaunchedEffect(Unit) {
                    withFrameNanos { calls += "owner" to Thread.currentThread() }
                    restoreCitationFocus(
                        tryCitation = { calls += "citation" to Thread.currentThread(); false },
                        fallback = { calls += "fallback" to Thread.currentThread(); fallback.requestFocus() },
                        finished = { calls += "finished" to Thread.currentThread() },
                        timeoutNanos = 80_000_000L,
                    )
                }
            }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            runOnIdle { assertTrue(calls.none { it.first == "fallback" }) }
            mainClock.advanceTimeBy(160)
            runOnIdle {
                val owner = calls.single { it.first == "owner" }.second
                assertTrue(calls.count { it.first == "citation" } >= 2)
                assertEquals(1, calls.count { it.first == "fallback" })
                assertEquals(1, calls.count { it.first == "finished" })
                assertTrue(calls.all { it.second === owner }, calls.toString())
            }
            onNodeWithTag("focus-fallback").assertIsFocused()
        }
    }

    private fun setup(): Pair<TextbookController, BookLearningSupport> {
        val root = Files.createTempDirectory("citation-workspace-")
        val file = root.resolve("synthetic.pdf")
        PDDocument().use { pdf ->
            repeat(2) { index ->
                val page = PDPage(); pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    out.beginText(); out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                    out.newLineAtOffset(50f, 710f)
                    if (index == 0) { out.showText("Chapter 1 Plants"); out.newLineAtOffset(0f, -48f) }
                    out.showText(if (index == 0) "Plants use light to produce energy and grow." else "Roots absorb water and minerals from the surrounding soil.")
                    out.endText()
                }
            }
            pdf.save(file.toFile())
        }
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(file)
        library.save(imported.copy(learning = true, readingPage = 1, questionMode = QuestionMode.SHORT_ANSWER))
        val controller = TextbookController(library)
        runBlocking { controller.load(); controller.learn(LearningPath.READ_AND_LEARN) }
        var calls = 0
        val support = object : BookLearningSupport {
            override suspend fun explain(request: BookExplanationRequest): BookLearningReply {
                calls++
                val book = library.book(request.book)
                val block = request.blocks.first { !it.heading && it.page == 2 }
                val valid = BookPassage.from(book, block)
                return BookLearningReply.Text("A synthetic explanation ($calls)", listOf(valid, valid.copy(page = 99)))
            }
            override suspend fun ask(request: BookPassageQuestion) = BookLearningReply.Unavailable("unused")
        }
        return controller to support
    }

    @Test fun citationOpensPageAndEscapeRestoresReplyDraftAndFocus() {
        val (controller, support) = setup()
        runComposeUiTest {
            setContent { TextbookWorkspace(controller, loadOnMount = false, support = support) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-source-stale-1").assertExists()
            onNodeWithTag("guided-start-questions").performScrollTo().performClick()
            onNodeWithTag("short-answer").performScrollTo().performTextInput("unfinished draft")
            waitUntil(timeoutMillis = 20_000) { controller.state.value.answer.text == "unfinished draft" }
            onNodeWithTag("book-source-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && controller.state.value.page == 2 && !controller.state.value.busy }
            onNodeWithTag("windows-textbooks").performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-source-0").assertIsFocused()
            onNodeWithTag("book-explanation-text").assertTextEquals("A synthetic explanation (1)")
            onNodeWithTag("short-answer").performScrollTo().assertTextContains("unfinished draft")
            assertEquals(1, controller.library.book(controller.state.value.book!!.id).readingPage)
        }
    }

    @Test fun sourceChangedWhileReadingReturnsWithoutTryingToFocusMissingButton() {
        val (controller, support) = setup()
        runComposeUiTest {
            setContent { TextbookWorkspace(controller, loadOnMount = false, support = support) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-source-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && controller.state.value.page == 2 && !controller.state.value.busy }
            val book = controller.state.value.book!!
            Files.write(controller.library.directory.resolve("${book.id}.${book.format}"), byteArrayOf(10), StandardOpenOption.APPEND)
            onNodeWithTag("windows-textbooks").performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-stale-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-source-stale-0").assertExists()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("open-lesson-source").fetchSemanticsNodes().any { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused) == true } }
            onNodeWithTag("open-lesson-source").assertIsFocused()
        }
    }

    @Test fun refusedCitationMovesFocusToNamedLessonSource() {
        val (controller, support) = setup()
        runComposeUiTest {
            setContent { TextbookWorkspace(controller, loadOnMount = false, support = support) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-source-0").performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
            onNodeWithTag("book-source-0").assertIsFocused()
            val book = controller.state.value.book!!
            Files.write(controller.library.directory.resolve("${book.id}.${book.format}"), byteArrayOf(10), StandardOpenOption.APPEND)
            onNodeWithTag("book-source-0").performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-stale-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("open-lesson-source").assertIsFocused().assertHasClickAction()
            assertEquals(BookScreen.LEARNING, controller.state.value.screen)
        }
    }

    @Test fun lateCitationForPreviousBookCannotNavigateCurrentBook() {
        val (controller, support) = setup()
        val original = controller.state.value.book!!
        val lesson = original.lessons[original.lessonIndex]
        val input = Files.createTempFile("citation-second-", ".pdf")
        Files.copy(controller.library.directory.resolve("${original.id}.${original.format}"), input, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Files.write(input, byteArrayOf(10), StandardOpenOption.APPEND)
        val second = controller.library.importBook(input).copy(learning = true)
        controller.library.save(second)
        assertNotEquals(original.id, second.id)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val resolver = object : BookSourceResolver(controller.library) {
            override fun resolve(citation: OwnedBookCitation, expectedQuote: String?): BookSourceResolution {
                val result = super.resolve(citation, expectedQuote)
                started.countDown()
                check(release.await(20, TimeUnit.SECONDS))
                returned.countDown()
                return result
            }
        }
        try {
            runComposeUiTest {
                setContent { TextbookWorkspace(controller, loadOnMount = false, support = support, sourceResolver = resolver) { null } }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("book-source-0").performScrollTo().performClick()
                assertTrue(started.await(20, TimeUnit.SECONDS))
                runOnIdle {
                    runBlocking { controller.select(second.id); controller.learn(LearningPath.READ_AND_LEARN) }
                    release.countDown()
                    assertTrue(returned.await(20, TimeUnit.SECONDS))
                }
                waitForIdle()
                assertEquals(second.id, controller.state.value.book!!.id)
                assertEquals(BookScreen.LEARNING, controller.state.value.screen)
                // Also exercise the lock-protected check directly, after a queued navigation reaches the controller.
                runBlocking { controller.citationSource(original.id, lesson.id, 2) }
                assertEquals(second.id, controller.state.value.book!!.id)
                assertEquals(BookScreen.LEARNING, controller.state.value.screen)
                assertNull(controller.state.value.source)
            }
        } finally { release.countDown() }
    }

    /**
     * 同一页有两句一模一样的原文：点第二条出处，打开的是被点的那一块。
     * 回退行 open-lesson-source 只按页打开、没有块编号，因此不会标出这条出处的范围；
     * 两条路径的标记不同，测试能区分「该块」和「回退行」。
     */
    @Test fun citationOpensTheCitedBlockWhenThePageRepeatsTheSameSentence() {
        val sentence = "Plants use light to make food."
        val root = Files.createTempDirectory("citation-block-")
        val file = root.resolve("duplicate-block.pdf")
        PDDocument().use { pdf ->
            val page = PDPage(); pdf.addPage(page)
            PDPageContentStream(pdf, page).use { out ->
                out.beginText(); out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                out.newLineAtOffset(50f, 710f); out.showText(sentence)
                out.newLineAtOffset(0f, -220f); out.showText(sentence)
                out.endText()
            }
            pdf.save(file.toFile())
        }
        val library = TextbookLibrary(root.resolve("library"))
        val imported = library.importBook(file)
        library.save(imported.copy(learning = true, readingPage = 1, questionMode = QuestionMode.SHORT_ANSWER))
        val controller = TextbookController(library)
        runBlocking { controller.load(); controller.learn(LearningPath.READ_AND_LEARN) }
        val book = controller.state.value.book!!
        val body = book.lessons.single().blocks.filter { !it.heading }.sortedBy { it.start }
        assertEquals(2, body.size)
        assertEquals(sentence, body[0].text)
        assertEquals(sentence, body[1].text)
        assertTrue(body[1].start >= body[0].end, "合成页的两处原文必须彼此分开")
        val cited = body[1]
        val support = object : BookLearningSupport {
            override suspend fun explain(request: BookExplanationRequest): BookLearningReply {
                val current = library.book(request.book)
                return BookLearningReply.Text("A synthetic explanation", listOf(BookPassage.from(current, cited)))
            }
            override suspend fun ask(request: BookPassageQuestion) = BookLearningReply.Unavailable("unused")
        }
        runComposeUiTest {
            setContent { TextbookWorkspace(controller, loadOnMount = false, support = support) { null } }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-source-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && controller.state.value.page == 1 && !controller.state.value.busy }
            // 文字视图标的是被点的第二块，不是这一页第一次出现的同一句。
            onNodeWithTag("immersive-more-menu").performClick()
            onNodeWithTag("view-mode-text").performClick()
            waitForIdle()
            val marked = onNodeWithTag("reader-text").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
            val sourceSpans = marked.spanStyles.filter { it.item.background != androidx.compose.ui.graphics.Color.Unspecified }
            assertEquals(1, sourceSpans.size)
            val span = sourceSpans.single()
            assertEquals(cited.start, span.start)
            assertEquals(cited.end, span.end)
            assertEquals(sentence, marked.text.substring(span.start, span.end))
            assertFalse(span.start <= body[0].start && span.end >= body[0].end, "标记不能落在第一处相同原文上")
            // 原页视图落在同一处，而不是提示「多处相同内容」或各处都不标。
            onNodeWithTag("immersive-more-menu").performClick()
            onNodeWithTag("view-mode-original").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("remote-highlight").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("remote-highlight").assertContentDescriptionEquals("原句：$sentence")
            onNodeWithText("无法确认原句：这一页有多处相同内容。").assertDoesNotExist()
            // 回退行没有块编号：同页打开不替用户猜是哪一处，也就不标任何范围。
            onNodeWithTag("immersive-back").performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.LEARNING && !controller.state.value.busy }
            onNodeWithTag("open-lesson-source").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { controller.state.value.screen == BookScreen.READER && controller.state.value.page == 1 && !controller.state.value.busy }
            onNodeWithTag("immersive-more-menu").performClick()
            onNodeWithTag("view-mode-text").performClick()
            waitForIdle()
            assertTrue(onNodeWithTag("reader-text").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().spanStyles.none { it.item.background != androidx.compose.ui.graphics.Color.Unspecified })
        }
    }
}
