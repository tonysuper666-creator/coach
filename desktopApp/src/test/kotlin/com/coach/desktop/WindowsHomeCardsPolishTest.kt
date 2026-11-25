package com.coach.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import coach.design.tokens.CoachV7
import com.coach.desktop.books.*
import com.coach.ui.CoachTheme
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class WindowsHomeCardsPolishTest {
    @Test fun formatLibraryPathAbbreviatesUserHomeAndPreservesExternalPaths() {
        val root = Files.createTempDirectory("coach-path-fmt-")
        try {
            val home = root.resolve("Users").resolve("reader")
            val sep = home.fileSystem.separator
            assertEquals("用户文件夹${sep}Coach${sep}books", formatLibraryPathForDisplay(home.resolve("Coach").resolve("books"), userHome = home))
            assertEquals("用户文件夹", formatLibraryPathForDisplay(home, userHome = home))
            val outside = root.resolve("External").resolve("Coach")
            assertEquals(outside.toAbsolutePath().normalize().toString(), formatLibraryPathForDisplay(outside, userHome = home))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun settingsPathDemotesVisualStyleWhileKeepingFullPathCopyableAndSelectable() {
        val root = Files.createTempDirectory("coach-settings-b6-")
        try {
            val home = Path.of(System.getProperty("user.home"))
            val sampleDir = home.resolve("Coach").resolve("books")
            val expectedDisplay = formatLibraryPathForDisplay(sampleDir, userHome = home)
            val expectedFull = sampleDir.toAbsolutePath().normalize().toString()
            var copied: AnnotatedString? = null
            val fakeClipboard = object : ClipboardManager {
                override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
                override fun getText(): AnnotatedString? = copied
            }
            var expectedSecondary: androidx.compose.ui.graphics.Color? = null
            runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(1.5f)) {
                setContent {
                    CoachTheme(false) {
                        expectedSecondary = com.coach.ui.theme.CoachTheme.colors.fgSecondary
                        CompositionLocalProvider(
                            LocalClipboardManager provides fakeClipboard,
                            LocalSettingsDirectoryOverride provides sampleDir,
                        ) {
                            HostSettings(directory = root.resolve("ignored-temp"), onClose = {})
                        }
                    }
                }
                onNodeWithTag("settings-library-directory").assertTextEquals(expectedDisplay)
                val layout = mutableListOf<TextLayoutResult>()
                onNodeWithTag("settings-library-directory").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
                assertEquals(expectedSecondary, layout.single().layoutInput.style.color, "路径应降为 fgSecondary")
                assertTrue(layout.single().layoutInput.style.fontSize.value <= 13.5f, "路径应使用小字")

                onNodeWithTag("settings-library-copy").performClick()
                assertEquals(expectedFull, copied?.text, "点击复制必须拿到完整路径")

                onNodeWithTag("settings-library-full-path").assertDoesNotExist()
                onNodeWithTag("settings-library-toggle-full").performClick()
                onNodeWithTag("settings-library-full-path").assertTextEquals(expectedFull)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun todayAndCourseCardsCenterWithinReadingColumnAndKeepSinglePrimaryButton() {
        val outDir = Path.of(System.getProperty("coach.ton1735.out") ?: "build/screens/ton1735")
        Files.createDirectories(outDir)
        val settingsSample = Path.of(System.getProperty("user.home"), "Coach", "books")
        val sep = settingsSample.fileSystem.separator

        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            val root = Files.createTempDirectory("coach-ton1735-$theme-")
            try {
                val sample = root.resolve("经济学导论.md")
                Files.writeString(sample, """## 第一章 需求与供给

价格机制通过边际成本与边际收益的平衡引导资源配置。理解供需曲线的移动与沿曲线移动的区别，是分析市场均衡变化的基础。

## 第二章 市场失灵与外部性

当市场交易的成本或收益外溢到第三方时，完全竞争均衡不再实现帕累托最优，需要考察外部性内部化与公共品供给。
""")
                val library = TextbookLibrary(root.resolve("library"))
                val controller = TextbookController(library)
                runBlocking {
                    controller.importBook(sample)
                    val lesson = controller.state.value.book!!.lessons.first()
                    val practice = Practice("合成复习题", lesson.text, "甲", listOf("甲", "乙"))
                    controller.recordSectionQuizAnswer(lesson, 0, practice, PracticeAnswer("乙", revealed = true))
                    controller.back()
                    controller.refreshReviews()
                }

                runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(1.5f)) {
                    setContent {
                        CompositionLocalProvider(
                            LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light,
                            LocalSettingsDirectoryOverride provides settingsSample,
                        ) {
                            WindowsTextbookHost(controller, learningSupport = UnconnectedBookLearningSupport) { null }
                        }
                    }
                    fun savePng(name: String) {
                        waitForIdle()
                        assertTrue(ImageIO.write(captureToImage().toAwtImage(), "png", outDir.resolve("$theme-$name.png").toFile()))
                    }
                    waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("home-review-card").fetchSemanticsNodes().isNotEmpty() }
                    fun bounds(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                    val host = bounds("windows-host")
                    val sheet = bounds("shelf-sheet")
                    assertTrue(sheet.width <= 720 * 1.5f + 1)
                    for (tag in listOf("shelf-sheet", "home-focus-card", "home-review-card")) {
                        assertTrue(abs(bounds(tag).center.x - host.center.x) <= 1f, "$tag 应居中")
                        assertTrue(bounds(tag).width <= sheet.width)
                    }
                    fun fontSize(tag: String): Float {
                        val layouts = mutableListOf<TextLayoutResult>()
                        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        return layouts.single().layoutInput.style.fontSize.value
                    }
                    assertTrue(fontSize("home-focus-title") > fontSize("home-focus-meta"))
                    onAllNodesWithTag("home-read").assertCountEquals(1)
                    val image = captureToImage().toAwtImage()
                    fun fill(tag: String): Int {
                        val rect = bounds(tag)
                        return image.getRGB((rect.left + 8f).toInt(), rect.center.y.toInt())
                    }
                    val primary = fill("home-read")
                    for (secondary in listOf("home-mode-trigger", "home-review-start")) {
                        assertNotEquals(primary, fill(secondary), "$secondary 不应使用主按钮底色")
                    }
                    savePng("home")
                    // 3. 设置弹窗：真实形状路径样例，显示「用户文件夹\Coach\books」
                    onNodeWithTag("open-settings").performClick()
                    onNodeWithTag("settings-library-directory").assertTextEquals("用户文件夹${sep}Coach${sep}books")
                    savePng("settings")
                    onNodeWithTag("close-settings").performClick()
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }
}
