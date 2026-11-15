package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import java.nio.file.Files
import kotlin.test.*

/** Uses only PR214 APIs so the same behavioral assertion can run against the old production tree. */
@OptIn(ExperimentalTestApi::class)
class BookVariantEntryLegacyRedTest {
    @Test fun unlinkedOriginalHasExplicitNotReadyStateInActualReviewHost() {
        val root = Files.createTempDirectory("unlinked-variant-host-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("source")))
        library.save(book.copy(learning = true))
        val lesson = book.lessons.first()
        val p = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)!!
        library.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE,
            PracticeAnswer(p.options.first { it != p.answer }, true))
        val c = TextbookController(library)
        runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
            setContent { WindowsTextbookHost(c) { null } }
            waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && c.state.value.book != null }
            onNode(hasAnyAncestor(hasTestTag("host-navigation")) and hasText("今天")).performClick()
            onNodeWithTag("host-today-review").performClick()
            waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && c.state.value.screen == BookScreen.REVIEWS }
            onNodeWithTag("local-review-item-0").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && c.state.value.screen == BookScreen.REVIEWING }
            onNodeWithTag("review-variant-state").performScrollTo().assertTextEquals("新变式未备好：缺少明确关联、有效版本或可查来源。")
            onNodeWithTag("review-start-variant").assertDoesNotExist()
            onNodeWithText(p.prompt).assertExists()
            assertEquals(1, library.reviews().size)
        }
    }
}
