package com.coach.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.client.PairedCredentials
import com.coach.desktop.books.*
import com.coach.desktop.books.exam.*
import com.coach.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
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

    private val reference = mapOf(
        "home-empty" to "inner-week", "home" to "inner-week", "settings" to "inner-week",
        "reader-small" to "inner-read", "reader-medium" to "inner-read", "reader-large" to "inner-read",
        "reader-settings" to "inner-read", "companion" to "inner-read",
        "notes-export" to "inner-week", "notes-export-point" to "inner-week", "notes-export-week-menu" to "inner-week",
        "notes-export-unchecked" to "inner-week", "notes-export-done" to "inner-week", "notes-export-failed" to "inner-week",
    )

    @Test fun ton1666ReaderComparison() {
        for (screen in screens) for (dark in listOf(false, true)) {
            val root = Files.createTempDirectory("coach-ton1666-isolated-")
            val sample = root.resolve("paragraphs.md")
            Files.writeString(sample, """## 一节　阅读与思考

阅读是一种缓慢而主动的工作。人们沿着文字前行，把新的知识和已有的经验联系起来；每当遇到需要停下来思考的地方，都可以回到原文，重新检查前后两句之间的关系。这一段故意写得长一些，让段落在阅读栏中自然换行，观察标点和行尾。

另一段从这里开始，段与段之间仍然相互独立。书页不需要额外插入一个空白行，也不需要改变任何一个原文字符。无论窗口大小如何改变，出处和批注都应当指向原来的位置。

English readers follow the same page with a different convention. The first line after a heading begins at the margin, and subsequent paragraphs use a small indent.

A second English paragraph lets us inspect the space between paragraphs and the natural shape of a left aligned margin.
""")
            val controller = TextbookController(TextbookLibrary(root.resolve("library")))
            runBlocking {
                controller.importBook(sample)
                controller.chooseLearningPath(LearningPath.QUESTION_FIRST)
                controller.enableLearning()
                controller.back()
                controller.read()
            }
            runSkikoComposeUiTest(size = Size(screen.width.toFloat(), screen.height.toFloat()), density = Density(scale)) {
                setContent {
                    CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) {
                        WindowsTextbookHost(controller) { null }
                    }
                }
                ready(controller) { it.screen == BookScreen.READER && it.prompt == null }
                onNodeWithTag("reader-text").assertExists()
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                onNodeWithTag("reader-text").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                val laid = layouts.single()
                val stored = controller.state.value.book!!.pages.first().text
                val node = onNodeWithTag("reader-text").fetchSemanticsNode()
                val sources = node.config[com.coach.desktop.books.BookPageSources]
                val visible = sources.joinToString("") { piece ->
                    controller.state.value.book!!.page(piece.importPage)!!.text.substring(piece.startUtf16, piece.endUtf16)
                }
                kotlin.test.assertEquals(visible, node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text)
                kotlin.test.assertEquals(visible.length, laid.layoutInput.text.length)
                val paragraphs = com.coach.desktop.books.bookParagraphs(visible, false).paragraphs
                println("ACTUAL paragraphs=$paragraphs styles=${laid.layoutInput.text.paragraphStyles}")
                paragraphs.forEach { println("ACTUAL start=${it.start} left=${laid.getBoundingBox(it.start).left} top=${laid.getBoundingBox(it.start).top}") }
                capture("reader-ton1666", screen, dark)
            }
        }
    }

    @Test fun everyDesktopSceneInLightAndDarkAtWindowsScaling() {
        val previous = System.getProperty("coach.windows.theme")
        val shots = mutableListOf<Shot>()
        try {
            for (screen in screens) for (dark in listOf(false, true)) {
                System.setProperty("coach.windows.theme", if (dark) "dark" else "light")
                shots += walk(screen, dark)
                shots += notesWalk(screen, dark)
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
        val library = TextbookLibrary(root.resolve("library"))
        val controller = TextbookController(library)
        val sample = knowledgeRecapTextbook(root)
        val local = BookExplainer(DesktopConnectionSettings(root, { null }, mac = false, property = { null }), library)
        val shots = mutableListOf<Shot>()
        runSkikoComposeUiTest(size = Size(screen.width.toFloat(), screen.height.toFloat()), density = Density(scale)) {
            setContent {
                CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) {
                    WindowsTextbookHost(controller, bookExplainer = local, learningSupport = UnconnectedBookLearningSupport) { sample }
                }
            }
            fun shot(scene: String) { waitForIdle(); shots += capture(scene, screen, dark, whole = true) }
            ready(controller) { it.initialized }
            onNodeWithTag("home-empty-hub").assertIsDisplayed()
            shot("home-empty")
            onNodeWithTag("upload-textbook").performClick()
            ready(controller) { it.screen == BookScreen.READER }
            revealReaderHud()
            onNodeWithTag("immersive-back").performClick()
            ready(controller) { it.screen == BookScreen.HOME }
            onNodeWithTag("home-focus-card").assertIsDisplayed()
            shot("home")
            onNodeWithTag("open-settings").performClick()
            onNodeWithTag("settings-library-directory").assertExists()
            shot("settings")
            onNodeWithTag("close-settings").performClick()
            onNodeWithTag("home-read").performClick()
            ready(controller) { it.screen == BookScreen.READER }
            val renderedSizes = mutableListOf<Float>()
            for (size in ReadingSize.entries) {
                revealReaderHud()
                onNodeWithTag("immersive-more-menu").performClick()
                onNodeWithTag("v7-type-menu").performClick()
                val option = onNodeWithTag("reading-size-${size.name}")
                option.performClick()
                option.assertIsSelected()
                if (size == ReadingSize.MEDIUM) shot("reader-settings")
                onNodeWithTag("v7-type-menu").performMouseInput { click() }
                onNodeWithTag("immersive-more-menu").performClick()
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                onAllNodesWithTag("reader-text")[0].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                renderedSizes += layouts.single().layoutInput.style.fontSize.value
                shot("reader-${size.name.lowercase()}")
            }
            assertTrue(renderedSizes.zipWithNext().all { (a, b) -> a < b }, "实际排版字号：$renderedSizes")
            if (onAllNodesWithTag("single-book-companion").fetchSemanticsNodes().isEmpty()) onNodeWithTag("immersive-companion-toggle").performClick()
            onNodeWithTag("single-book-companion").assertIsDisplayed()
            shot("companion")
        }
        return shots
    }

    private fun notesWalk(screen: Screen, dark: Boolean): List<Shot> {
        val points = listOf(WeekPoint("kp-cell", "细胞是基本单位", setOf(1, 2)), WeekPoint("kp-membrane", "细胞膜控制物质进出", setOf(2)),
            WeekPoint("kp-nucleus", "细胞核里有遗传信息", setOf(3)))
        val state = NotesExportState(listOf(1, 2, 3), points, 1, null)
        val shots = mutableListOf<Shot>()
        runSkikoComposeUiTest(size = Size(screen.width.toFloat(), screen.height.toFloat()), density = Density(scale)) {
            setContent {
                com.coach.ui.CoachTheme(dark) {
                    val colors = com.coach.ui.theme.CoachTheme.colors
                    Surface(Modifier.fillMaxSize().testTag("windows-host"), color = colors.bgCanvas) {
                        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Box(Modifier.size(600.dp, 610.dp).border(1.dp, colors.borderSubtle)) {
                                NotesExportPanel(state, onPickDirectory = { state.directory = Path.of("D:/学习/复习提纲") },
                                    onExport = {}, onClose = {}, onOpen = {})
                            }
                        }
                    }
                }
            }
            fun shot(scene: String) { waitForIdle(); shots += capture(scene, screen, dark, whole = true) }
            onNodeWithText("尚未选择保存位置").assertExists()
            shot("notes-export")

            // 第 18 刀：PDF 勾选项换成 V7 小方框，整行仍是一个开关；两态各拍一张（勾上是松绿实底配纸色勾，没勾是细线框）。
            onNodeWithTag("notes-pdf").assertIsOn()
            onNodeWithTag("notes-pdf").performClick(); waitForIdle()
            assertFalse(state.includePdf); onNodeWithTag("notes-pdf").assertIsOff()
            shot("notes-export-unchecked")
            onNodeWithTag("notes-pdf").performClick(); waitForIdle()
            assertTrue(state.includePdf)

            // 第 14 刀：周次下拉的展开态，当前是第 1 周；点第 2 周后菜单收起、选中改成第 2 周，再改回第 1 周。
            fun menuItem(label: String) = onNode(hasText(label) and hasAnyAncestor(isPopup()))
            onNodeWithTag("notes-picker").performClick(); waitForIdle()
            menuItem("第 3 周").assertExists()
            shot("notes-export-week-menu")
            onNodeWithTag("notes-week-1").assertIsSelected(); onNodeWithTag("notes-week-2").assertIsNotSelected()
            menuItem("第 2 周").performClick(); waitForIdle()
            assertEquals(2, state.week); assertFalse(state.expanded)
            onNodeWithTag("notes-picker").performClick(); waitForIdle()
            menuItem("第 1 周").performClick(); waitForIdle()
            assertEquals(1, state.week); assertFalse(state.expanded)

            onNodeWithText("按知识点", substring = true).performClick(); waitForIdle()
            onNodeWithText("细胞是基本单位", substring = true).performClick(); waitForIdle()
            onNodeWithText("细胞膜控制物质进出").assertExists()
            shot("notes-export-point")
            onNodeWithTag("notes-point-kp-cell").assertIsSelected()
            onNodeWithText("细胞膜控制物质进出").performClick(); waitForIdle()

            onNodeWithText("选择本机文件夹…").performClick(); waitForIdle()
            state.saved = Path.of("D:/学习/复习提纲/coach-notes-1")
            state.status = "已导出：5 条待练错题。"
            shot("notes-export-done")

            state.saved = null; state.failed = true
            state.status = "目标文件夹已存在同名导出，未覆盖。请换一个文件夹后重试。"
            shot("notes-export-failed")
        }
        return shots
    }

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    private fun ComposeUiTest.capture(scene: String, screen: Screen, dark: Boolean, whole: Boolean = false): Shot {
        val theme = if (dark) "dark" else "light"
        val file = out.resolve(screen.name).resolve(theme).resolve("$scene.png")
        Files.createDirectories(file.parent)
        // 弹窗、下拉菜单在另一层，只拍 windows-host 会漏掉；whole 拍整个场景。
        val image = (if (whole) (this as SkikoComposeUiTest).captureToImage() else onNodeWithTag("windows-host").captureToImage()).toAwtImage()
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
