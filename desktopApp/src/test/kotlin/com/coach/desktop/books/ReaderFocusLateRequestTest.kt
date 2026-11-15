package com.coach.desktop.books

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * 旧的翻页请求在等待焦点丢失期间，界面可能已经换了书、换了出处或离开阅读屏。用受控的状态变化验证：
 * 只有发起时的书和出处仍是当前阅读屏时才发焦点票，其它情形旧请求一律不发（也就不会再去请求焦点）。
 */
class ReaderFocusLateRequestTest {
    private fun book(id: String) = Textbook(id, "t", "pdf", listOf(BookPage(1, "a"), BookPage(2, "b")))
    private val lessonA = BookLesson("a", "A", emptyList())
    private val lessonB = BookLesson("b", "B", emptyList())
    private fun reader(bookId: String = "book-1", source: BookLesson? = lessonA) =
        LibraryUi(book = book(bookId), screen = BookScreen.READER, source = source)

    /** 返回发出的票数；[change] 在"请求已在等待、焦点还在"时改界面状态，之后焦点才丢失。 */
    private fun run(change: (setUi: (LibraryUi) -> Unit) -> Unit, timeoutMs: Long = 5_000, loseFocus: Boolean = true): Int = runBlocking {
        val hasFocus = mutableStateOf(true)
        val ui = mutableStateOf(reader())
        val tickets = mutableListOf<ReaderFocusTicket>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            awaitReaderFocusRestore("book-1", lessonA, { hasFocus.value }, { ui.value }, timeoutMs) { tickets += it }
        }
        change { ui.value = it }
        Snapshot.sendApplyNotifications()
        delay(50)                                                   // 旧请求此刻还在等；界面已经变了
        if (loseFocus) { hasFocus.value = false; Snapshot.sendApplyNotifications() }
        job.await()
        tickets.size
    }

    @Test fun sameBookAndSourceStillGetsExactlyOneTicketWhenFocusIsLost() {
        assertEquals(1, run(change = { })) // 同书翻页：保持原行为
    }

    @Test fun aNewBookThatLandsBeforeTheOldRequestFinishesGetsNoTicket() {
        assertEquals(0, run(change = { it(reader(bookId = "book-2")) }))
    }

    @Test fun aNewSourceOnTheSameBookGetsNoTicket() {
        assertEquals(0, run(change = { it(reader(source = lessonB)) }))
    }

    @Test fun anotherScreenOrAPromptGetsNoTicket() {
        assertEquals(0, run(change = { it(reader().copy(screen = BookScreen.LEARNING)) }))
        assertEquals(0, run(change = { it(reader().copy(screen = BookScreen.HOME)) }))
        assertEquals(0, run(change = { it(reader().copy(prompt = BookPrompt.START)) }))
    }

    @Test fun noTicketWhenFocusIsNeverLostBeforeTheTimeout() {
        assertEquals(0, run(change = { }, timeoutMs = 200, loseFocus = false))
    }
}
