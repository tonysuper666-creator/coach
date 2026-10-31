package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.coach.ui.chat.AnchoredChatList
import com.coach.ui.chat.ChatListState
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ChatListFollowTest {
    @Test fun readerAtTheTailSeesEachNewItemAndOneWhoScrolledUpIsNotMoved() = runComposeUiTest {
        val items = mutableStateListOf<String>().apply { addAll((0 until 30).map { "m$it" }) }
        val chat = ChatListState()
        setContent {
            Box(Modifier.size(300.dp, 400.dp)) {
                AnchoredChatList(items.toList(), { it }, chat, windowKey = 300, modifier = Modifier.testTag("list")) { Text(it, Modifier.height(60.dp)) }
            }
        }
        waitForIdle()
        // At the tail: the learner's message and then the tutor's reply both appear without the reader doing anything.
        runOnIdle { items += "asked" }; waitForIdle(); mainClock.advanceTimeBy(100); waitForIdle()
        onNodeWithText("asked").assertIsDisplayed()
        runOnIdle { items += "reply" }; waitForIdle(); mainClock.advanceTimeBy(100); waitForIdle()
        onNodeWithText("reply").assertIsDisplayed()
        assertEquals(0, chat.list.firstVisibleItemIndex); assertTrue(chat.anchor.pinned)

        // Scrolled up: new items arrive below and the reader's place does not move.
        onNodeWithTag("list").performScrollToIndex(12)
        waitForIdle()
        assertFalse(chat.anchor.pinned)
        val keyBefore = chat.list.layoutInfo.visibleItemsInfo.first { it.index == chat.list.firstVisibleItemIndex }.key
        val offsetBefore = chat.list.firstVisibleItemScrollOffset
        runOnIdle { items += "later1"; items += "later2" }; waitForIdle(); mainClock.advanceTimeBy(100); waitForIdle()
        val keyAfter = chat.list.layoutInfo.visibleItemsInfo.first { it.index == chat.list.firstVisibleItemIndex }.key
        assertEquals(keyBefore, keyAfter); assertEquals(offsetBefore, chat.list.firstVisibleItemScrollOffset)
        assertFalse(chat.anchor.pinned)
        onNodeWithText("later2").assertDoesNotExist()
    }
}
