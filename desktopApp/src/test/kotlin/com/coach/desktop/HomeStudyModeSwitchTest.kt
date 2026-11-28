package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

/**
 * TON-1985 第 1 条：首页卡上的学法切换只换偏好、就地重画，不把人扔进阅读器。
 */
@OptIn(ExperimentalTestApi::class)
class HomeStudyModeSwitchTest {
    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    @Test fun switchingModeOnTheHomeCardSavesThePreferenceAndStaysOnHome() {
        val root = Files.createTempDirectory("coach-home-mode-")
        try {
            // 关掉今日卡，走的正是首页焦点卡这条路。
            val library = TextbookLibrary(root.resolve("library"), todayCardEnabled = false)
            val c = TextbookController(library)
            runBlocking {
                c.importBook(syntheticTextbook(root.resolve("synthetic")))
                c.back()
            }
            val book = c.state.value.book!!
            assertFalse(StudyModeStore.loadOrResolve(library, book.id, book.title, book.pages.size).userLocked)

            runSkikoComposeUiTest(size = Size(1360f, 820f), density = Density(1f)) {
                setContent { WindowsTextbookHost(c) { null } }
                ready(c) { it.screen == BookScreen.HOME && it.catalog.isNotEmpty() }
                onNodeWithTag("home-focus-card").assertIsDisplayed()

                onNodeWithTag("home-mode-trigger").performClick()
                waitForIdle()
                onNodeWithTag("mode-option-DIAGNOSTIC_SKIP").performClick()
                waitForIdle()

                // 还站在首页，「继续」还在原处，人也还在这一屏。
                assertEquals(BookScreen.HOME, c.state.value.screen)
                onNodeWithTag("home-read").assertIsDisplayed()
                onNodeWithTag("home-focus-card").assertIsDisplayed()
                // 按钮就地换了标签，说明只重画了这一块。
                onNodeWithTag("home-mode-trigger").assertTextContains("先测后读", substring = true)

                // 偏好落库并锁住，下次打开沿用。
                val saved = StudyModeStore.loadOrResolve(library, book.id, book.title, book.pages.size)
                assertEquals(BookStudyMode.DIAGNOSTIC_SKIP, saved.mode)
                assertTrue(saved.userLocked)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
