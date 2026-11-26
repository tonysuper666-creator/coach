package com.coach.desktop.books

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 阅读区认哪些键、什么时候不认。翻页的 UI 行为另有 `ReaderKeyboardPagingTest`，
 * 这里只钉住这张映射表本身：改键位只会改动这一处。
 */
class ReaderPagingKeysTest {
    @Test fun arrowsAndPageKeysTurnOnePage() {
        assertEquals(ReaderTurn.Next, readerPagingTurn(Key.DirectionRight, typing = false))
        assertEquals(ReaderTurn.Next, readerPagingTurn(Key.PageDown, typing = false))
        assertEquals(ReaderTurn.Previous, readerPagingTurn(Key.DirectionLeft, typing = false))
        assertEquals(ReaderTurn.Previous, readerPagingTurn(Key.PageUp, typing = false))
    }

    @Test fun homeAndEndJumpToTheEnds() {
        assertEquals(ReaderTurn.First, readerPagingTurn(Key.MoveHome, typing = false))
        assertEquals(ReaderTurn.Last, readerPagingTurn(Key.MoveEnd, typing = false))
    }

    @Test fun otherKeysAreLeftToTheRestOfTheApp() {
        for (key in listOf(Key.Spacebar, Key.Tab, Key.Escape, Key.Enter, Key.DirectionUp, Key.DirectionDown, Key.A, Key.S)) {
            assertNull(readerPagingTurn(key, typing = false), "$key 不该翻页")
        }
    }

    @Test fun typingDisablesEveryPagingKey() {
        for (key in listOf(Key.DirectionLeft, Key.DirectionRight, Key.PageUp, Key.PageDown, Key.MoveHome, Key.MoveEnd)) {
            assertNull(readerPagingTurn(key, typing = true), "$key 在输入文字时不该翻页")
        }
    }

    @Test fun typingCoversTheAskBoxThePageFieldAndAnOpenNoteEditor() {
        assertEquals(true, readerTyping(asking = true, jumpFocused = false, notesOpen = false, noting = false))
        assertEquals(true, readerTyping(asking = false, jumpFocused = true, notesOpen = false, noting = false))
        assertEquals(true, readerTyping(asking = false, jumpFocused = false, notesOpen = true, noting = true))
        // 只是翻看批注、没在写的时候，方向键还是翻页。
        assertEquals(false, readerTyping(asking = false, jumpFocused = false, notesOpen = true, noting = false))
        assertEquals(false, readerTyping(asking = false, jumpFocused = false, notesOpen = false, noting = false))
    }
}
