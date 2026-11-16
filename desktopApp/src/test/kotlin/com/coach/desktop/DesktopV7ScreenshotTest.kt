package com.coach.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * TON-1650：Windows 桌面每个主要界面按 V7「案头」对照拍一遍。
 *
 * 两种 Windows 常见屏（1920×1080、2560×1440）都按 150% 缩放拍，浅色、深色各一遍；
 * 截图放 `build/screens/v7-desktop/<屏>/<明暗>/<界面>.png`。设计包参考图在场时（winbox 上的
 * `D:/agent/design/ui-v7/shots`，或 `-Dcoach.v7.refs=` 指定），再拼一张左右对照图放 `compare/`：
 * 左边是桌面原图不缩放，右边是最接近的 V7 参考（Fold8 内屏 2448×1848）等比缩到同高。
 * 设计包没有桌面专用参考，对照只看颜色、字体、纸面与留白这类体系问题，不比像素位置。
 *
 * 教材是测试里自造的六节中文短文（[knowledgeRecapTextbook]），讲解走本地假通道，不联网、不碰 Mac。
 * 这条测试只负责拍全、拍对尺寸；偏差判断写在 docs/acceptance/TON-1650-desktop-v7.md。
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class DesktopV7ScreenshotTest {
    private data class Screen(val name: String, val width: Int, val height: Int)

    private val screens = listOf(Screen("1920x1080", 1920, 1080), Screen("2560x1440", 2560, 1440))
    private val scale = 1.5f
    private val out: Path = Path.of("build/screens/v7-desktop")
    private val refs: Path = Path.of(System.getProperty("coach.v7.refs") ?: "D:/agent/design/ui-v7/shots")

    /** 每个桌面界面对照设计包里最接近的那一屏：周览对首页三处，读书对阅读器，做题对做题后的一串卡。 */
    private val reference = linkedMapOf(
        "bookshelf" to "inner-week", "today" to "inner-week", "course" to "inner-week",
        "reader" to "inner-read", "quiz" to "inner-quiz", "explain" to "inner-quiz",
        "summary" to "inner-quiz", "recap" to "inner-quiz", "reviews" to "inner-quiz", "review-question" to "inner-quiz",
    )

    @Test fun everyDesktopSceneInLightAndDarkAtWindowsScaling() {
        val previous = System.getProperty("coach.windows.theme")
        val shots = mutableListOf<Shot>()
        try {
            for (screen in screens) for (dark in listOf(false, true)) {
                System.setProperty("coach.windows.theme", if (dark) "dark" else "light")
                shots += walk(screen, dark)
            }
        } finally {
            if (previous == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", previous)
        }
        assertEquals(reference.size * screens.size * 2, shots.size)
        assertEquals(reference.keys, shots.map { it.scene }.toSet())
        if (Files.isDirectory(refs)) {
            val composed = shots.count { compose(it) }
            assertEquals(shots.size, composed, "每张桌面截图都要有一张对照图")
            println("V7_DESKTOP compare=${out.resolve("compare").toAbsolutePath()} images=$composed")
        } else {
            println("V7_DESKTOP 参考图不在 $refs，这次只出桌面截图，不拼对照")
        }
    }

    private class Shot(val scene: String, val screen: String, val dark: Boolean, val file: Path)

    private fun walk(screen: Screen, dark: Boolean): List<Shot> {
        val root = Files.createTempDirectory("coach-v7-desktop-")
        val directory = root.resolve("library")
        val library = TextbookLibrary(directory)
        val consent = ExplainConsent(directory)
        val controller = TextbookController(library)
        // 先用控制器把书走到第六节之前：五节答过、其中第三节答错进了复习，第六节等着做。
        runBlocking {
            controller.importBook(knowledgeRecapTextbook(root))
            controller.chooseLearningPath(LearningPath.QUESTION_FIRST); controller.enableLearning()
            for (index in 0 until 5) {
                controller.lesson(index)
                val book = controller.state.value.book!!
                val practice = StudyCoachCourse.practice(book, book.lessons[index], QuestionMode.CHOICE)!!
                val pick = if (index == 2) practice.options.first { it != practice.answer } else practice.answer
                controller.answer(PracticeAnswer(pick, revealed = true))
            }
            controller.lesson(5)
            controller.back()
        }
        val book = controller.state.value.book!!
        consent.allow(book.id)
        val gate = AnswerExplain(consent, load = {}, ready = { true }, paired = { true },
            explain = { chosen, _ ->
                ExplainResult.Done(Explanation(
                    "这一段只讲一件事：核糖体是细胞里合成蛋白质的地方。题目问「在哪儿做」，答案就落在「场所」两个字上；" +
                        "后半句说它常附着在内质网上，是在告诉你去哪儿找它。【c1】",
                    listOf(Citation("c1", chosen.id, chosen.page)), 12))
            })
        val shots = mutableListOf<Shot>()
        runSkikoComposeUiTest(size = Size(screen.width.toFloat(), screen.height.toFloat()), density = Density(scale)) {
            setContent {
                CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) {
                    WindowsTextbookHost(controller, answerExplain = gate) { null }
                }
            }
            fun nav(label: String) = onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText(label))
            fun shot(scene: String) { shots += capture(scene, screen, dark) }

            ready(controller) { it.screen == BookScreen.HOME }
            onNodeWithTag("library-title").assertExists()
            shot("bookshelf")

            nav("今天").performClick(); waitForIdle()
            onNodeWithTag("host-today-book").assertExists()
            shot("today")

            nav("课程").performClick(); waitForIdle()
            onNodeWithTag("host-course-book").assertExists()
            shot("course")

            nav("今天").performClick(); waitForIdle()
            onNodeWithTag("host-today-read").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.prompt == null }
            onNodeWithTag("textbook-reader").assertExists()
            // 阅读器（D5、D6）：按钮不夹英文；Markdown 标题的井号隐去，但原文字符一个不少，出处偏移照旧对得上。
            onAllNodesWithText("learning", substring = true, ignoreCase = true).assertCountEquals(0)
            val readerText = onNodeWithTag("reader-text").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text].single()
            val hashes = readerText.text.indices.filter { readerText.text[it] == '#' }
            assertTrue(hashes.isNotEmpty(), "这本示例书的正文带 Markdown 标题")
            assertTrue(hashes.all { i -> readerText.spanStyles.any {
                it.item.color == androidx.compose.ui.graphics.Color.Transparent && i in it.start until it.end } }, "标题井号要隐去")
            shot("reader")

            nav("课程").performClick(); waitForIdle()
            onNodeWithTag("host-course-learn").performClick()
            ready(controller) { it.screen == BookScreen.LEARNING && it.book?.lessonIndex == 5 && it.questionsOpen }
            val current = controller.state.value.book!!
            val practice = StudyCoachCourse.practice(current, current.lessons[5], QuestionMode.CHOICE)!!
            onNodeWithTag("choice-0").assertExists()
            shot("quiz")

            onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
            ready(controller) { it.answer.revealed }
            waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-explanation-text").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-lesson-explanation").performScrollTo()
            waitForIdle()
            shot("explain")

            onNodeWithTag("book-summary-card").performScrollTo(); waitForIdle()
            shot("summary")

            ready(controller) { it.ladder.recapOpen }
            onNodeWithTag("book-knowledge-recap").performScrollTo(); waitForIdle()
            shot("recap")

            nav("今天").performClick(); waitForIdle()
            onNodeWithTag("host-today-review").performClick()
            ready(controller) { it.screen == BookScreen.REVIEWS && it.reviews.isNotEmpty() }
            onNodeWithTag("local-review-item-0").assertExists()
            shot("reviews")

            onNodeWithTag("local-review-item-0").performClick()
            ready(controller) { it.screen == BookScreen.REVIEWING }
            onNodeWithTag("local-review-question").assertExists()
            shot("review-question")
        }
        return shots
    }

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.capture(scene: String, screen: Screen, dark: Boolean): Shot {
        val theme = if (dark) "dark" else "light"
        val file = out.resolve(screen.name).resolve(theme).resolve("$scene.png")
        Files.createDirectories(file.parent)
        val image = onNodeWithTag("windows-host").captureToImage().toAwtImage()
        assertTrue(ImageIO.write(image, "png", file.toFile()))
        val saved = assertNotNull(ImageIO.read(file.toFile()))
        assertEquals(screen.width, saved.width, "$scene 宽度要等于 ${screen.name} 的物理像素")
        assertEquals(screen.height, saved.height, "$scene 高度要等于 ${screen.name} 的物理像素")
        // 角落两处底色记下来给偏差清单对 token：右下是正文区底，左上是顶栏。
        println("V7_DESKTOP ${screen.name} $theme $scene canvas=${hex(saved.getRGB(saved.width - 4, saved.height - 4))} " +
            "header=${hex(saved.getRGB(4, 4))}")
        return Shot(scene, screen.name, dark, file)
    }

    /** 左：桌面原图不缩放；右：参考图等比缩到同高。上方一行写清两边是什么，免得对照时认错。 */
    private fun compose(shot: Shot): Boolean {
        val theme = if (shot.dark) "dark" else "light"
        val name = "${reference.getValue(shot.scene)}-$theme.png"
        val refFile = refs.resolve(name).toFile()
        if (!refFile.isFile) { println("V7_DESKTOP 缺参考图 $name"); return false }
        val left = ImageIO.read(shot.file.toFile())
        val ref = ImageIO.read(refFile)
        val h = left.height
        val w = (ref.width.toDouble() * h / ref.height).toInt()
        val bar = (40 * scale).toInt()
        val gap = (16 * scale).toInt()
        val canvas = BufferedImage(left.width + gap + w, h + bar, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color(0x808080); g.fillRect(0, 0, canvas.width, canvas.height)
            g.drawImage(left, 0, bar, null)
            g.drawImage(ref, left.width + gap, bar, w, h, null)
            g.color = Color.WHITE
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, (16 * scale).toInt())
            val y = (bar * 0.68).toInt()
            g.drawString("Windows ${shot.scene} · $theme · ${shot.screen} @150% (1:1)", gap, y)
            g.drawString("V7 ${name.removeSuffix(".png")} · Fold8 inner ${ref.width}x${ref.height} → height $h", left.width + gap * 2, y)
        } finally {
            g.dispose()
        }
        val target = out.resolve("compare").resolve("${shot.screen}-$theme-${shot.scene}.png")
        Files.createDirectories(target.parent)
        return ImageIO.write(canvas, "png", target.toFile())
    }

    private fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
}
