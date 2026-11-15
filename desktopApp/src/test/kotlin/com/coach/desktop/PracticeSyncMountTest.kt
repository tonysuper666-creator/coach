package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import java.nio.file.Files
import kotlin.test.Test

/** Can run unchanged on the old tree to expose the missing actual-host state. */
@OptIn(ExperimentalTestApi::class)
class PracticeSyncMountTest {
    @Test fun unpairedPracticeShowsNotReadyInHost() {
        val root = Files.createTempDirectory("practice-sync-unpaired-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("source")))
        library.save(book.copy(learning = true, questionMode = QuestionMode.CHOICE))
        val controller = TextbookController(library)
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbookHost(controller) { null } }
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.book != null }
            onNodeWithTag("home-learn").performClick()
            waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy && controller.state.value.screen == BookScreen.LEARNING }
            onNodeWithTag("practice-sync-status").performScrollTo().assertTextEquals("同步未备好：尚未连接配对课程。")
        }
    }
}
