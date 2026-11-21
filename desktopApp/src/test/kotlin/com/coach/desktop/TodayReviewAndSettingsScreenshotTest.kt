package com.coach.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import com.coach.ui.CoachTheme
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * Light and dark screenshot evidence for TON-1790:
 * 1. Empty state single-column home (6 zero-dead-end controls)
 * 2. Daily single-column home (single PrimaryButton "继续", conditional Today Review card, courses/booklists/books)
 * 3. Today's single-card review self-rating ("看答案" -> "没记住 / 记住了", 1 / N)
 * 4. One-page four-section settings
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class TodayReviewAndSettingsScreenshotTest {
    private val out = Path.of("build/screens/ton1790")

    private fun SkikoComposeUiTest.shot(name: String) {
        Files.createDirectories(out)
        assertTrue(ImageIO.write(captureToImage().toAwtImage(), "png", out.resolve("$name.png").toFile()))
    }

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    private fun emptyHome(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val root = Files.createTempDirectory("empty-home-shot-")
        try {
            val library = TextbookLibrary(root.resolve("library"), ReviewTestClock())
            val c = TextbookController(library)
            runSkikoComposeUiTest(size = Size(1440f, 960f), density = Density(1f)) {
                setContent {
                    CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) {
                        CoachTheme(dark) {
                            Surface(Modifier.fillMaxSize(), color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                                WindowsTextbookHost(c) { null }
                            }
                        }
                    }
                }
                ready(c) { it.initialized }
                onNodeWithTag("upload-textbook").assertIsDisplayed()
                onNodeWithTag("empty-route-course").assertIsDisplayed()
                onNodeWithTag("empty-route-single").assertIsDisplayed()
                onNodeWithTag("empty-route-booklist").assertIsDisplayed()
                shot("01-empty-home-$mode")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun emptyHomeLight() = emptyHome(dark = false)
    @Test fun emptyHomeDark() = emptyHome(dark = true)

    private fun dailyHomeAndReviewFlow(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val root = Files.createTempDirectory("daily-review-shot-")
        try {
            val library = TextbookLibrary(root.resolve("library"), ReviewTestClock())
            val c = TextbookController(library)
            runBlocking {
                c.importBook(syntheticTextbook(root.resolve("synthetic")))
                c.back()
            }
            val book = c.state.value.book!!
            library.upsertCollection(
                MaterialCollectionRecord(
                    id = "course-demo",
                    kind = CollectionKind.COURSE,
                    title = "微观经济学备考",
                    examDate = "2027-01-18",
                ),
            )
            library.attachMaterial(CollectionMaterialRecord("course-demo", book.id, role = "CORE_TEXTBOOK", ordinal = 0))
            library.upsertCollection(
                MaterialCollectionRecord(
                    id = "list-demo",
                    kind = CollectionKind.BOOKLIST,
                    title = "信息与激励",
                ),
            )
            library.attachMaterial(CollectionMaterialRecord("list-demo", book.id, role = "LIST_BOOK", ordinal = 0))
            val lessons = book.lessons.filter { it.text.isNotBlank() }.take(3)
            for (l in lessons) {
                library.saveAnswer(book.id, l.id, QuestionMode.SHORT_ANSWER, PracticeAnswer("上次不太确定", true, false))
            }
            runBlocking { c.load() }

            runSkikoComposeUiTest(size = Size(1440f, 960f), density = Density(1f)) {
                setContent {
                    CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) {
                        CoachTheme(dark) {
                            Surface(Modifier.fillMaxSize(), color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                                WindowsTextbookHost(c) { null }
                            }
                        }
                    }
                }
                ready(c) { it.screen == BookScreen.HOME && it.book != null }
                onNodeWithTag("home-focus-card").assertIsDisplayed()
                onNodeWithTag("home-read").assertTextContains("继续")
                onNodeWithTag("home-review-start").assertTextContains("开始复习")
                shot("02-daily-home-$mode")

                onNodeWithTag("home-review-start").performClick()
                ready(c) { it.screen == BookScreen.REVIEWS }
                onNodeWithTag("local-review-start").performScrollTo().performClick()
                ready(c) { it.screen == BookScreen.REVIEWING }
                onNodeWithTag("local-review-position").assertTextContains("1 / ${lessons.size}")
                onNodeWithTag("review-show-answer").performScrollTo().performClick()
                waitForIdle()
                onNodeWithTag("review-rate-again").performScrollTo().assertIsDisplayed()
                onNodeWithTag("review-rate-good").performScrollTo().assertIsDisplayed()
                shot("03-review-self-rate-$mode")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun dailyHomeAndReviewLight() = dailyHomeAndReviewFlow(dark = false)
    @Test fun dailyHomeAndReviewDark() = dailyHomeAndReviewFlow(dark = true)

    private fun settings(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val root = Files.createTempDirectory("settings-shot-")
        try {
            Files.createDirectories(root.resolve("library"))
            val prefs = ReadingSettings(root.resolve("library"))
            runSkikoComposeUiTest(size = Size(1440f, 960f), density = Density(1f)) {
                setContent {
                    CompositionLocalProvider(
                        LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light,
                        LocalSettingsDirectoryOverride provides Path.of("D:/Coach/教材库"),
                    ) {
                        CoachTheme(dark) {
                            Surface(Modifier.fillMaxSize(), color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                                HostSettings(directory = root.resolve("library"), onPersonalCourse = {}, preferences = prefs, onClose = {})
                            }
                        }
                    }
                }
                waitForIdle()
                for (tag in listOf("settings-section-account", "settings-section-reading", "settings-section-learning", "settings-section-data")) {
                    onNodeWithTag(tag).assertExists()
                }
                shot("04-settings-$mode")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun settingsLight() = settings(dark = false)
    @Test fun settingsDark() = settings(dark = true)
}
