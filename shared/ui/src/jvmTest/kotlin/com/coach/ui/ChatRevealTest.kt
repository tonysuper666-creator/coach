package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.coach.ui.chat.AnchoredChatList
import com.coach.ui.chat.ChatListState
import com.coach.ui.chat.scrollItemToTop
import kotlin.test.*

/** A new question group must open at question 1, whatever the viewport and item sizes are. */
@OptIn(ExperimentalTestApi::class)
class ChatRevealTest {
    private fun run(itemHeights: List<Int>, viewport: Int, reveal: Int, check: ComposeUiTest.(ChatListState) -> Unit) = runComposeUiTest {
        val state = ChatListState()
        var done = false
        setContent {
            Box(Modifier.size(300.dp, viewport.dp)) {
                AnchoredChatList(itemHeights.indices.toList(), { it }, state, 300, Modifier.testTag("list"), spacing = 0.dp) { i ->
                    Box(Modifier.fillMaxWidth().height(itemHeights[i].dp).testTag("i$i"))
                }
            }
            LaunchedEffect(Unit) { state.scrollItemToTop(reveal, itemHeights.size); done = true }
        }
        waitUntil(timeoutMillis = 5_000) { done }
        waitForIdle()
        check(state)
    }

    private fun ComposeUiTest.top(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
    private fun ComposeUiTest.listTop() = onNodeWithTag("list").fetchSemanticsNode().boundsInRoot.top

    @Test fun itemShorterThanViewportOpensAtTopWithNewerItemsBelow() = run(List(10) { 200 }, viewport = 500, reveal = 4) {
        assertTrue(kotlin.math.abs(listTop() - top("i4")) <= 1f, "item 4 sits at the top edge: ${top("i4")} vs ${listTop()}")
        assertTrue(top("i5") > top("i4"), "newer items follow below it")
    }

    @Test fun itemTallerThanViewportShowsItsOwnTop() = run(List(6) { 600 }, viewport = 500, reveal = 2) {
        assertTrue(kotlin.math.abs(listTop() - top("i2")) <= 1f, "tall item starts at the top edge: ${top("i2")} vs ${listTop()}")
    }

    @Test fun nearTailClampsWithoutJumpingPastIt() = run(List(10) { 200 }, viewport = 500, reveal = 9) {
        assertTrue(top("i9") >= listTop(), "last item stays inside the viewport")
        assertTrue(it.anchor.pinned, "reader is at the tail")
    }
}
