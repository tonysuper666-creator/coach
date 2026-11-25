package com.coach.desktop.regression

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.coach.desktop.WindowsTextbookHost
import com.coach.desktop.books.BookScreen
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import java.nio.file.Files
import kotlin.test.Test

/**
 * TON-1849: an empty review shelf must say wrong answers appear here, not tomorrow.
 */
@OptIn(ExperimentalTestApi::class)
class R1849ReviewCopyTest {
    @Test
    fun emptyHomeSaysWrongAnswersAppearHere() {
        val root = Files.createTempDirectory("coach-r1849-review-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val controller = TextbookController(library)
            runSkikoComposeUiTest(size = Size(1280f, 820f), density = Density(1f)) {
                setContent { WindowsTextbookHost(controller) { null } }
                waitUntil(timeoutMillis = 20_000) {
                    controller.state.value.initialized &&
                        !controller.state.value.busy &&
                        controller.state.value.screen == BookScreen.HOME
                }
                waitForIdle()
                onNodeWithTag("home-review-done")
                    .assertIsDisplayed()
                    .assertTextEquals("还没有要复习的。做错的题会在这里出现。")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
