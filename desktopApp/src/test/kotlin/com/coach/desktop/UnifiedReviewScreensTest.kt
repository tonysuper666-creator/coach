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
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** Public generated book, real host/controller/SQLite; no original-text page is captured. */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class UnifiedReviewScreensTest {
    @Test fun reviewListInBothV7Themes() {
        val previous = System.getProperty("coach.windows.theme")
        try {
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                System.setProperty("coach.windows.theme", theme)
                val root = Files.createTempDirectory("unified-review-screen-")
                val c = TextbookController(TextbookLibrary(root.resolve("library")))
                runBlocking {
                    c.importBook(syntheticTextbook(root.resolve("synthetic")))
                    c.chooseLearningPath(LearningPath.QUESTION_FIRST)
                    c.enableLearning()
                    val book = c.state.value.book!!
                    val practice = StudyCoachCourse.practice(book, book.lessons.first(), QuestionMode.CHOICE)!!
                    c.answer(PracticeAnswer(practice.options.first { it != practice.answer }, true))
                }
                runSkikoComposeUiTest(size = Size(1440f, 1000f), density = Density(1.5f)) {
                    setContent {
                        CompositionLocalProvider(LocalSystemTheme provides if (dark) SystemTheme.Dark else SystemTheme.Light) {
                            WindowsTextbookHost(c) { null }
                        }
                    }
                    waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && c.state.value.book != null }
                    runBlocking { c.showReviews() }
                    waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && c.state.value.screen == BookScreen.REVIEWS }
                    onNodeWithTag("local-review-item-0").assertExists()
                    waitForIdle()
                    val output = Path.of("build/screens/unified-review")
                    Files.createDirectories(output)
                    assertTrue(ImageIO.write(onNodeWithTag("windows-host").captureToImage().toAwtImage(),
                        "png", output.resolve("after-$theme.png").toFile()))
                }
            }
        } finally {
            if (previous == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", previous)
        }
    }
}
