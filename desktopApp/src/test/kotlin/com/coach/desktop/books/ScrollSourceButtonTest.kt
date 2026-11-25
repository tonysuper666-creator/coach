package com.coach.desktop.books

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * TON-1671：从真实的「出处」按钮（讲解里的 book-source-0）进入阅读器，再切到连续滚动，
 * 浅色、深色下被引原句都在视口内、被引范围的可见部分都带高亮背景。自写材料、独立临时书库、离线。
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class ScrollSourceButtonTest {
    private fun longParagraphBook(root: Path): Path {
        val sentences = StringBuilder()
        var i = 1
        while (sentences.length < 9_000) sentences.append("第${i++}句：这是一整段合成长文，用来核对正文栏收窄以后仍然能从头读到尾。")
        sentences.append("全段最后一句在这里结束。")
        val body = listOf("# 长段落合成样例", "", sentences.toString(), "").joinToString(System.lineSeparator())
        return root.resolve("long-paragraph.md").also { Files.writeString(it, body) }
    }

    private fun ComposeUiTest.ready(controller: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && condition(controller.state.value) }
        assertNull(controller.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.visible() = listOf("reader-text", "reader-text-right").flatMap { onAllNodesWithTag(it).fetchSemanticsNodes() }

    private fun check(dark: Boolean) {
        val root = Files.createTempDirectory("scroll-source-button-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val imported = library.importBook(longParagraphBook(root))
            library.save(imported.copy(learning = true, readingPage = 1, questionMode = QuestionMode.SHORT_ANSWER))
            val controller = TextbookController(library)
            runBlocking { controller.load(); controller.learn(LearningPath.READ_AND_LEARN) }
            val cited = StudyCoachCourse.blocks(imported.id, imported.pages).filter { !it.heading && it.text.length > 40 }.maxBy { it.start }
            val support = object : BookLearningSupport {
                override suspend fun explain(request: BookExplanationRequest): BookLearningReply =
                    BookLearningReply.Text("A synthetic explanation", listOf(BookPassage.from(library.book(request.book), cited)))
                override suspend fun ask(request: BookPassageQuestion) = BookLearningReply.Unavailable("unused")
            }
            runSkikoComposeUiTest(size = Size(1600f, 1440f), density = Density(1.5f)) {
                setContent {
                    CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) {
                        WindowsTextbookHost(controller, learningSupport = support) { null }
                    }
                }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-source-0").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("book-source-0").performScrollTo().performClick() // 真实的「出处」按钮
                ready(controller) { it.screen == BookScreen.READER }
                onNodeWithTag("immersive-more-menu").performClick()
                onNodeWithTag("v7-type-menu").performClick()
                onNodeWithTag("text-mode-scroll").performClick()
                waitForIdle()
                onNodeWithTag("book-scroll-read").assertExists()
                var landed = ""
                var found = false
                val page = onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
                for (node in visible()) {
                    var local = 0
                    for (source in node.config[BookPageSources]) {
                        if (source.importPage == cited.page && cited.start in source.startUtf16 until source.endUtf16) {
                            val offset = local + cited.start - source.startUtf16
                            val text = node.config[SemanticsProperties.Text].single()
                            val layouts = mutableListOf<TextLayoutResult>()
                            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                            val laid = layouts.single()
                            val top = node.positionInRoot.y + laid.getLineTop(laid.getLineForOffset(offset))
                            val bottom = node.positionInRoot.y + laid.getLineBottom(laid.getLineForOffset(offset))
                            assertTrue(top >= page.top && bottom <= page.bottom, "cited start line inside the viewport (${if (dark) "dark" else "light"}): $top..$bottom in $page")
                            assertTrue(text.spanStyles.any { it.item.background != androidx.compose.ui.graphics.Color.Unspecified && offset in it.start until it.end },
                                "cited start highlighted (${if (dark) "dark" else "light"})")
                            // 可见的被引范围逐字都带高亮
                            val marked = text.spanStyles.filter { it.item.background != androidx.compose.ui.graphics.Color.Unspecified }
                            var localAt = 0
                            for (s in node.config[BookPageSources]) {
                                val a = maxOf(s.startUtf16, cited.start); val b = minOf(s.endUtf16, cited.end)
                                if (s.importPage == cited.page && a < b) for (o in a until b) {
                                    val at = localAt + o - s.startUtf16
                                    if (at < laid.layoutInput.text.length) {
                                        val y = node.positionInRoot.y + laid.getBoundingBox(at).top
                                        if (y >= page.top && y < page.bottom) assertTrue(marked.any { at in it.start until it.end }, "visible cited offset $o highlighted")
                                    }
                                }
                                localAt += s.endUtf16 - s.startUtf16
                            }
                            landed = "line ${(top - page.top).toInt()}px of ${page.height.toInt()}px from the top of the reading area"
                            found = true
                        }
                        local += source.endUtf16 - source.startUtf16
                    }
                }
                assertTrue(found, "the cited sentence must be laid out in continuous scroll")
                println("SCROLLSRC ${if (dark) "dark" else "light"}: $landed")
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun lightTheme() = check(false)
    @Test fun darkTheme() = check(true)
}
