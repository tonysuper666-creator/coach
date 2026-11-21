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
                    controller.chooseLearningPath(LearningPath.QUESTION_FIRST)
                    controller.enableLearning()
                    val book = controller.state.value.book!!
                    val practice = StudyCoachCourse.practice(book, book.lessons.first(), QuestionMode.CHOICE)!!
                    val wrong = practice.options.first { it != practice.answer }
                    controller.answer(PracticeAnswer(wrong, revealed = true))
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
                    fun nav(label: String) = onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText(label))
                    fun savePng(name: String) {
                        waitForIdle()
                        ImageIO.write(captureToImage().toAwtImage(), "png", outDir.resolve("$theme-$name.png").toFile())
                    }

                    // 1. 今天页：主卡居中、宽度不超阅读栏上限、三级字号排开、仅 1 枚主按钮（复习改纸面按钮）
                    nav("今天").performClick()
                    onNodeWithTag("host-today-book").assertExists()
                    onNodeWithTag("host-today-review").assertExists()
                    val hostRect = onNodeWithTag("windows-host").fetchSemanticsNode().boundsInRoot
                    val todaySheet = onNodeWithTag("host-sheet").fetchSemanticsNode().boundsInRoot
                    val maxSheetPx = HostSheetMaxWidth.value * 1.5f + 2f
                    assertTrue(todaySheet.width <= maxSheetPx, "今天页主卡宽度不应超过阅读栏上限：${todaySheet.width} > $maxSheetPx")
                    assertTrue(todaySheet.top > 120f, "今天页主卡应在主区域内垂直居中，不能贴顶拉伸：top=${todaySheet.top}")
                    assertTrue(todaySheet.height < hostRect.height * 0.7f, "今天页主卡应按内容收束，不拉成满高大白板：${todaySheet.height}/${hostRect.height}")

                    val kickerLayout = mutableListOf<TextLayoutResult>()
                    val titleLayout = mutableListOf<TextLayoutResult>()
                    val progressLayout = mutableListOf<TextLayoutResult>()
                    onNodeWithTag("host-today-kicker").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(kickerLayout) }
                    onNodeWithTag("host-today-title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayout) }
                    onNodeWithTag("host-today-progress").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(progressLayout) }
                    assertTrue(titleLayout.single().layoutInput.style.fontSize.value > progressLayout.single().layoutInput.style.fontSize.value,
                        "书名应大于进度说明")
                    assertTrue(progressLayout.single().layoutInput.style.fontSize.value >= kickerLayout.single().layoutInput.style.fontSize.value,
                        "进度说明应不小于页眉小标")
                    val titleRect = onNodeWithTag("host-today-title").fetchSemanticsNode().boundsInRoot
                    val progressRect = onNodeWithTag("host-today-progress").fetchSemanticsNode().boundsInRoot
                    val readRect = onNodeWithTag("host-today-read").fetchSemanticsNode().boundsInRoot
                    val reviewRect = onNodeWithTag("host-today-review").fetchSemanticsNode().boundsInRoot
                    assertTrue(titleRect.bottom <= progressRect.top && progressRect.bottom <= readRect.top,
                        "书名 → 进度 → 下一步应自上而下排开")
                    savePng("today")

                    // 像素核对 A13：主按钮（接着读）是深色/高对比实底，复习按钮与继续学按钮同为纸面底色
                    val todayImage = captureToImage().toAwtImage()
                    fun sampleEdgeRgb(rect: androidx.compose.ui.geometry.Rect): Int {
                        val x = (rect.left + 6f).toInt().coerceIn(0, todayImage.width - 1)
                        val y = (rect.top + rect.height / 2f).toInt().coerceIn(0, todayImage.height - 1)
                        return todayImage.getRGB(x, y) and 0xFFFFFF
                    }
                    val learnRect = onNodeWithTag("host-today-learn").fetchSemanticsNode().boundsInRoot
                    val readRgb = sampleEdgeRgb(readRect)
                    val learnRgb = sampleEdgeRgb(learnRect)
                    val reviewRgb = sampleEdgeRgb(reviewRect)
                    assertEquals(learnRgb, reviewRgb, "复习按钮与继续学应同为 PaperButton 纸面底色")
                    assertNotEquals(readRgb, reviewRgb, "今天页只留接着读一枚 PrimaryButton，复习不能同为主按钮实底")

                    // 2. 课程页：主卡居中、宽度与阅读栏一致
                    nav("课程").performClick()
                    onNodeWithTag("host-course-book").assertExists()
                    val courseSheet = onNodeWithTag("host-sheet").fetchSemanticsNode().boundsInRoot
                    assertTrue(courseSheet.width <= maxSheetPx, "课程页主卡宽度不应超过阅读栏上限：${courseSheet.width} > $maxSheetPx")
                    val sheetCenterX = (courseSheet.left + courseSheet.right) / 2f
                    val contentCenterX = (76f * 1.5f + hostRect.right) / 2f
                    assertTrue(abs(sheetCenterX - contentCenterX) < 12f,
                        "课程页主卡应在主区域内水平居中：sheet=$sheetCenterX content=$contentCenterX")
                    savePng("course")

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
