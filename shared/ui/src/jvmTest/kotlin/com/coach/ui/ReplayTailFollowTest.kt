package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.coach.ui.chat.AnchoredChatList
import com.coach.ui.chat.ChatListState
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ReplayTailFollowTest {
    @Test fun realReplayKeepsItsPinWhileFollowingAndSwitchingMessages() = runComposeUiTest {
        val input = File(System.getProperty("user.home"), "Coach/corpus/render/replay.json")
        org.junit.Assume.assumeTrue("Real replay corpus is local acceptance input", input.isFile)
        val corpus = ReplayCorpus.load(input.readText())
        val state = ReplayState().apply { message = 8 }
        val reports = mutableListOf<String>()
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(390.dp, 700.dp)) {
                RenderingReplayScreen(state, corpus, true, "offline", {}, reports::add)
            }
        }
        mainClock.advanceTimeBy(100); waitForIdle()
        runOnIdle { state.running = true }
        repeat((corpus.messages[8].length + 29) / 30 + 4) {
            mainClock.advanceTimeBy(50); waitForIdle()
            assertTrue(state.pinned, "programmatic follow must not unpin the replay")
        }
        assertEquals(corpus.messages[8].length, state.cursor)
        val range = onNode(hasScrollToIndexAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(range.maxValue(), range.value(), .01f, "real long paragraph's bottom is visible")
        reports.filter { it.startsWith("REPLAY ") }.forEach { println("OFFLINE $it chunk=30 tickMs=50 baselineTickScrolls=${(state.cursor + 29) / 30}") }
        onNodeWithText("下一条").performClick()
        mainClock.advanceTimeBy(100); waitForIdle()
        assertEquals(9, state.message); assertEquals(0, state.cursor); assertTrue(state.pinned)
        runOnIdle { state.running = true }
        repeat(15) { mainClock.advanceTimeBy(50); waitForIdle() }
        assertTrue(state.pinned)
    }

    @Test fun chronologicalReplayFollowsMeasuredGrowthAndLeavesReaderAlone() = runComposeUiTest {
        val list = LazyListState()
        var text by mutableStateOf("")
        var pinned by mutableStateOf(true)
        var width by mutableStateOf(300)
        var scrolls = 0
        var legacyScrolls = 0
        setContent {
            FollowReplayTail(list, pinned) { scrolls++ }
            LazyColumn(state = list, modifier = Modifier.size(width.dp, 200.dp).testTag("replay")) {
                items(4, key = { it }) { Text("sealed $it", Modifier.height(30.dp)) }
                item(key = "tail") { Text(text) }
            }
        }
        waitForIdle()
        val start = scrolls
        // The same 30-character / 50 ms stream clock as the real replay. Most ticks do
        // not change line count, so there is no reason to issue a positioning operation.
        repeat(60) {
            runOnIdle { text += "word ".repeat(6); legacyScrolls++ }
            mainClock.advanceTimeBy(50); waitForIdle()
            runOnIdle { assertFalse(list.canScrollForward, "end of a tall mutable block must stay visible") }
        }
        val pinnedScrolls = scrolls - start
        println("REPLAY_SCROLL chunk=30 tickMs=50 baseline=$legacyScrolls candidate=$pinnedScrolls")
        assertTrue(pinnedScrolls < legacyScrolls)
        runOnIdle { pinned = false }; waitForIdle()
        onNodeWithTag("replay").performScrollToIndex(1)
        waitForIdle()
        val before = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        val count = scrolls
        repeat(10) { runOnIdle { text += "later ".repeat(5) }; mainClock.advanceTimeBy(50); waitForIdle() }
        assertEquals(before, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
        assertEquals(count, scrolls)
        runOnIdle { pinned = true }; mainClock.advanceTimeBy(100); waitForIdle()
        assertFalse(list.canScrollForward, "pin button resumes following immediately, even paused")
        runOnIdle { width = 190 }; mainClock.advanceTimeBy(100); waitForIdle()
        assertFalse(list.canScrollForward, "a reshape keeps the growing block's end visible")
    }

    @Test fun reverseChatGrowthKeepsPinnedTailAndReadingAnchorWithoutTickScrolls() = runComposeUiTest {
        val chat = ChatListState()
        var tail by mutableStateOf("stream")
        setContent {
            Box(Modifier.size(300.dp, 200.dp)) {
                AnchoredChatList((0..20).toList(), { it }, chat, 300, Modifier.testTag("chat")) {
                    Text(if (it == 20) tail else "sealed $it", Modifier.heightIn(min = 40.dp))
                }
            }
        }
        waitForIdle()
        repeat(20) {
            runOnIdle { tail += " more words".repeat(3) }; mainClock.advanceTimeBy(50); waitForIdle()
            assertEquals(0, chat.list.firstVisibleItemIndex)
            assertEquals(0, chat.list.firstVisibleItemScrollOffset)
            assertTrue(chat.anchor.pinned)
        }
        onNodeWithTag("chat").performScrollToIndex(8); waitForIdle()
        val before = chat.list.firstVisibleItemIndex to chat.list.firstVisibleItemScrollOffset
        repeat(10) { runOnIdle { tail += " more words" }; mainClock.advanceTimeBy(50); waitForIdle() }
        assertEquals(before, chat.list.firstVisibleItemIndex to chat.list.firstVisibleItemScrollOffset)
        assertFalse(chat.anchor.pinned)
        runOnIdle { runBlocking { chat.scrollToTail() } }; waitForIdle()
        assertTrue(chat.anchor.pinned)
        assertEquals(0, chat.list.firstVisibleItemIndex)
        assertEquals(0, chat.list.firstVisibleItemScrollOffset)
    }
}
