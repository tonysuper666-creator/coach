package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.ui.theme.CoachColors
import com.coach.ui.theme.CoachTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * TON-1659 第 4 项：讲解长过一屏时，右侧要有一道看得见的 V7 细滚动条。
 * 它跟着讲解面板的那个 ScrollState 走：滑块比轨道短，读者知道下面还有内容；拖到底能读到最后的出处。
 * 短讲解不需要滚动条，不占地方。
 */
@OptIn(ExperimentalTestApi::class)
class ExplainScrollbarTest {
    @Test fun longExplanationShowsAShorterThumbAndDragsToTheLastLineInBothThemes() {
        for (dark in listOf(false, true)) withPanel(dark, long = true) { dark ->
            val bar = onNodeWithTag("explain-scrollbar").fetchSemanticsNode().boundsInRoot
            val answerTop = onNodeWithTag("explain-text").fetchSemanticsNode().boundsInRoot.top

            // 滑块比整条轨道短：读者看得出下方还有内容。
            val track = thumbLength(bar, dark)
            assertTrue(track < bar.height - 20f, "滑块应比轨道短：thumb=${track}px bar=${bar.height}px（dark=$dark）")

            // 按住滑块拖到底：正文跟着走，最后一条出处进到可见区。
            onNodeWithTag("explain-scrollbar").performMouseInput {
                moveTo(Offset(width / 2f, 8f))
                press()
                moveTo(Offset(width / 2f, height * 3f), 300)
                release()
            }
            waitForIdle()
            val last = onAllNodesWithTag("explain-citation").fetchSemanticsNodes().maxBy { it.boundsInRoot.top }
            assertTrue(last.boundsInRoot.height > 0f && last.boundsInRoot.bottom <= bar.bottom + 1f,
                "拖到底后最后一条出处应当看得见：bounds=${last.boundsInRoot} bar=${bar.bottom}（dark=$dark）")
            assertTrue(onNodeWithTag("explain-text").fetchSemanticsNode().boundsInRoot.top < answerTop,
                "拖动滑块时正文跟着走（dark=$dark）")
            val stillBar = onNodeWithTag("explain-scrollbar").fetchSemanticsNode().boundsInRoot
            assertEquals(bar, stillBar, "滚动条本身不挪位（dark=$dark）")
        }
    }

    @Test fun shortExplanationHasNoScrollbar() = withPanel(false, long = false) { _ ->
        waitForIdle()
        assertEquals(0, onAllNodesWithTag("explain-scrollbar").fetchSemanticsNodes().size, "一屏讲得完就不该多出一条滚动条")
    }

    /** 沿轨道中线数出滑块的像素长度：和轨道底色不同的那一段。 */
    private fun ComposeUiTest.thumbLength(bar: androidx.compose.ui.geometry.Rect, dark: Boolean): Float {
        val pixels = onRoot().captureToImage().toPixelMap()
        val x = bar.center.x.toInt()
        val bg = colors(dark).surface1
        var count = 0
        var y = bar.top.toInt()
        while (y < bar.bottom.toInt()) {
            if (pixels[x, y] != bg) count++
            y++
        }
        return count.toFloat()
    }

    private fun withPanel(dark: Boolean, long: Boolean, body: ComposeUiTest.(Boolean) -> Unit) {
        val root = Files.createTempDirectory("explain-scroll-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(Files.writeString(root.resolve("light.md"), TEXT))
        val blocks = StudyCoachCourse.blocks(book.id, book.pages)
        val page = blocks.first { !it.heading }.page
        val chosen = blocks.first { it.page == page && !it.heading }
        val consent = ExplainConsent(library.directory).apply { allow(book.id) }
        val mac = FakeMac(root.resolve("home"), if (long) LONG else SHORT, chosen)
        runSkikoComposeUiTest(size = Size(900f, 520f), density = Density(1f)) {
            setContent {
                CoachTheme(dark) {
                    Box(Modifier.fillMaxSize().background(colors(dark).surface1)) {
                        BookExplainPanel(book, page, consent, mac, selection = chosen)
                    }
                }
            }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-button").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("explain-button").performClick()
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-answer").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            body(dark)
        }
        root.toFile().deleteRecursively()
    }

    private fun colors(dark: Boolean) = if (dark) CoachColors.dark() else CoachColors.light()

    private class FakeMac(home: Path, private val answer: String, private val chosen: SourceBlock) :
        BookExplainer(DesktopConnectionSettings(home = home, environment = { null })) {
        override suspend fun load() {
            identity = DesktopIdentity("http://mac.test:18791", PairedCredentials("test-device", "test-token"))
            loaded = true
        }
        override suspend fun explain(chosen: SourceBlock, blocks: List<SourceBlock>, requestId: String): ExplainResult =
            ExplainResult.Done(Explanation(answer, listOf(Citation("S1", this.chosen.id, this.chosen.page)), 12))
    }

    private companion object {
        val TEXT = """
            # 1 Light

            Plants use light to make sugars through photosynthesis inside chloroplasts.
        """.trimIndent()

        val SHORT = "这一段在说光被叶绿体用掉。【S1】"

        /** 六十句，900×520 的窗口一屏放不下；最后一行是出处，拖到底才能看见。 */
        val LONG = buildString {
            repeat(60) { i ->
                append("第 ${i + 1} 句：叶绿体把光能变成糖，氧气从气孔散出去，整个过程受光照、温度和水分影响。")
            }
            append("【S1】")
        }
    }
}
