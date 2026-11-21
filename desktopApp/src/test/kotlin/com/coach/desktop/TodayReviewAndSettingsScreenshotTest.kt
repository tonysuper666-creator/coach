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

/** Light and dark evidence for today's single-card review flow and the one-page settings (TON-1790). */
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

    private fun reviewFlow(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val root = Files.createTempDirectory("today-review-shot-")
        try {
            val library = TextbookLibrary(root.resolve("library"), ReviewTestClock())
            val c = TextbookController(library)
            runBlocking { c.importBook(syntheticTextbook(root.resolve("synthetic"))) }
            val book = c.state.value.book!!
            val lessons = book.lessons.filter { it.text.isNotBlank() }.take(3)
            for (l in lessons) library.saveAnswer(book.id, l.id, QuestionMode.SHORT_ANSWER, PracticeAnswer("Not yet", true, false))
            runBlocking { c.showReviews() }
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
                ready(c) { it.screen == BookScreen.REVIEWS }
                onNodeWithTag("local-review-summary").assertTextContains("今日 ${lessons.size} 题")
                onNodeWithText("1 天、3 天、7 天", substring = true).assertDoesNotExist()
                shot("01-today-queue-$mode")
                onNodeWithTag("local-review-start").performScrollTo().performClick()
                ready(c) { it.screen == BookScreen.REVIEWING }
                onNodeWithTag("local-review-position").assertTextContains("1 / ${lessons.size}")
                shot("02-single-card-$mode")
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun todayReviewLight() = reviewFlow(dark = false)
    @Test fun todayReviewDark() = reviewFlow(dark = true)

    private fun settings(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val root = Files.createTempDirectory("settings-shot-")
        try {
            Files.createDirectories(root.resolve("library"))
            val prefs = ReadingSettings(root.resolve("library"))
            runSkikoComposeUiTest(size = Size(1440f, 960f), density = Density(1f)) {
                setContent {
                    CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light,
                        LocalSettingsDirectoryOverride provides Path.of("D:/Coach/教材库")) {
                        CoachTheme(dark) {
                            Surface(Modifier.fillMaxSize(), color = com.coach.ui.theme.CoachTheme.colors.bgCanvas) {
                                HostSettings(directory = root.resolve("library"), onPersonalCourse = {}, preferences = prefs, onClose = {})
                            }
                        }
                    }
                }
                waitForIdle()
                for (tag in listOf("settings-section-account", "settings-section-reading", "settings-section-learning", "settings-section-data"))
                    onNodeWithTag(tag).assertExists()
                shot("03-settings-$mode")
                onNodeWithTag("settings-library-copy").performScrollTo()
                waitForIdle()
                shot("04-settings-bottom-$mode")
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun settingsLight() = settings(dark = false)
    @Test fun settingsDark() = settings(dark = true)
}
