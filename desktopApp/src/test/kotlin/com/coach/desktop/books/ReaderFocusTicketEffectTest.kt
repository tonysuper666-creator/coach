package com.coach.desktop.books

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.*

/**
 * 票已发、界面在同一个 UI 回合里先变：消费票的 LaunchedEffect 不能请求旧焦点。
 * 票和界面状态在同一次 runOnIdle 里一起改，两步之间不 delay、不 waitForIdle，所以不靠时序。
 */
@OptIn(ExperimentalTestApi::class)
class ReaderFocusTicketEffectTest {
    private fun book(id: String) = Textbook(id, "t", "pdf", listOf(BookPage(1, "a"), BookPage(2, "b")))
    private val lessonA = BookLesson("a", "A", emptyList())
    private val lessonB = BookLesson("b", "B", emptyList())
    private fun reader(bookId: String = "book-1", source: BookLesson? = lessonA) =
        LibraryUi(book = book(bookId), screen = BookScreen.READER, source = source)

    /** 先显示 book-1/lessonA 的阅读屏，再在一个回合里发票并把界面换成 [after]，返回焦点请求次数。 */
    private fun requests(after: LibraryUi): Int {
        var count = 0
        runComposeUiTest {
            val ticket = mutableStateOf<ReaderFocusTicket?>(null)
            val ui = mutableStateOf(reader())
            setContent { ReaderFocusTicketEffect(ticket.value, ui.value) { count++ } }
            runOnIdle { ticket.value = ReaderFocusTicket("book-1", lessonA); ui.value = after }
            runOnIdle { }
        }
        return count
    }

    @Test fun theSameBookAndSourceRequestsFocusExactlyOnce() = assertEquals(1, requests(reader()))
    @Test fun aNewBookLandingInTheSameTurnRequestsNothing() = assertEquals(0, requests(reader(bookId = "book-2")))
    @Test fun aNewSourceLandingInTheSameTurnRequestsNothing() = assertEquals(0, requests(reader(source = lessonB)))
    @Test fun anotherScreenOrAPromptLandingInTheSameTurnRequestsNothing() {
        assertEquals(0, requests(reader().copy(screen = BookScreen.LEARNING)))
        assertEquals(0, requests(reader().copy(prompt = BookPrompt.START)))
    }
}
