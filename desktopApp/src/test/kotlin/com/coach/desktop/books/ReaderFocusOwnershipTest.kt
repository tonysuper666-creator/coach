package com.coach.desktop.books

import kotlin.test.*

/** 待办的阅读器焦点请求只属于发起时的那本书、那个出处；退出、切书、换出处、弹窗后一律作废（确定性判定，不依赖时序）。 */
class ReaderFocusOwnershipTest {
    private fun book(id: String) = Textbook(id, "t", "pdf", listOf(BookPage(1, "a"), BookPage(2, "b")))
    private val lessonA = BookLesson("a", "A", emptyList())
    private val lessonB = BookLesson("b", "B", emptyList())
    private fun reader(bookId: String = "book-1", source: BookLesson? = lessonA) =
        LibraryUi(book = book(bookId), screen = BookScreen.READER, source = source)

    @Test fun theSameBookAndSourceOnTheReaderScreenKeepsTheRequest() {
        assertTrue(readerFocusStillOwned("book-1", lessonA, reader()))
    }

    @Test fun switchingToAnotherBookInvalidatesAnOlderRequest() {
        assertFalse(readerFocusStillOwned("book-1", lessonA, reader(bookId = "book-2")))
    }

    @Test fun openingAnotherSourceOnTheSameBookInvalidatesAnOlderRequest() {
        assertFalse(readerFocusStillOwned("book-1", lessonA, reader(source = lessonB)))
    }

    @Test fun leavingTheReaderOrOpeningAPromptInvalidatesIt() {
        assertFalse(readerFocusStillOwned("book-1", lessonA, reader().copy(screen = BookScreen.LEARNING)))
        assertFalse(readerFocusStillOwned("book-1", lessonA, reader().copy(screen = BookScreen.HOME)))
        assertFalse(readerFocusStillOwned("book-1", lessonA, reader().copy(prompt = BookPrompt.START)))
        assertFalse(readerFocusStillOwned("book-1", lessonA, reader(source = null)))
    }
}
