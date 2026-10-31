package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.coach.ui.lab.FormFactorLab
import com.coach.ui.lab.LabState
import kotlin.math.abs
import kotlin.test.*

/** Fold8 cover (475x751 dp) <-> inner (704x933 dp), 5 round trips, while a stream runs. Each assertion is something the reader would notice. */
@OptIn(ExperimentalTestApi::class)
class FoldUnfoldStateTest {
    private val outer = 475 to 751
    private val inner = 704 to 933

    private class Snapshot(val draft: TextFieldValue, val selected: String?, val streamed: Int, val key: Any?, val fraction: Float, val pinned: Boolean)

    private fun LabState.snapshot() = Snapshot(draft, selected["q1"], streamedChars, chat.anchor.key, chat.anchor.fraction, chat.anchor.pinned)

    private fun readerFraction(state: LabState): Pair<Any?, Float> {
        val first = state.chat.list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == state.chat.list.firstVisibleItemIndex } ?: return null to 0f
        return first.key to state.chat.list.firstVisibleItemScrollOffset.toFloat() / first.size
    }

    private fun runCase(readerPinned: Boolean) = runComposeUiTest {
        val state = LabState()
        var size by mutableStateOf(outer)
        setContent { Box(Modifier.size(size.first.dp, size.second.dp)) { FormFactorLab(state, tickMs = 40L, chunk = 40) } }
        waitForIdle()

        // Real input: pick option B, type a draft with the cursor in the middle.
        if (!readerPinned) onNodeWithTag("chat-list").performScrollToIndex(12)
        else onNodeWithTag("chat-list").performScrollToIndex(0)
        waitForIdle()
        runOnIdle { state.selected["q1"] = "B"; state.draft = TextFieldValue("我认为是 B，因为高风险者", TextRange(6)) }
        waitForIdle()
        val before = state.snapshot()
        assertEquals(!readerPinned, !before.pinned, "reader pinned state as set up")

        var switches = 0; var draftLoss = 0; var optionLoss = 0; var streamLoss = 0; var anchorLoss = 0
        var lastStreamed = state.streamedChars
        repeat(5) {
            for (target in listOf(inner, outer)) {
                val keyBefore = state.chat.anchor.key; val fractionBefore = state.chat.anchor.fraction; val pinnedBefore = state.chat.anchor.pinned
                size = target
                mainClock.advanceTimeBy(200L)
                waitForIdle()
                mainClock.advanceTimeBy(200L)
                waitForIdle()
                switches++
                if (state.draft.text != "我认为是 B，因为高风险者" || state.draft.selection != TextRange(6)) draftLoss++
                if (state.selected["q1"] != "B") optionLoss++
                if (state.streamedChars < lastStreamed) streamLoss++
                lastStreamed = state.streamedChars
                if (pinnedBefore) { if (state.chat.list.firstVisibleItemIndex != 0 || state.chat.list.firstVisibleItemScrollOffset != 0) anchorLoss++ }
                else {
                    val (key, fraction) = readerFraction(state)
                    if (key != keyBefore || abs(fraction - fractionBefore) > 0.03f) { anchorLoss++; println("ANCHOR_DIFF switch=$switches before=($keyBefore,$fractionBefore) after=($key,$fraction) index=${state.chat.list.firstVisibleItemIndex} off=${state.chat.list.firstVisibleItemScrollOffset} restoring=${state.chat.restoring}") }
                }
            }
        }
        // Stream finishes, and its text is exactly the target: nothing restarted or duplicated across 10 switches.
        mainClock.advanceTimeBy(5_000L); waitForIdle()
        while (state.streamedChars < state.streamTarget.length) { mainClock.advanceTimeBy(500L); waitForIdle() }
        println("FOLD_SWITCH pinned=$readerPinned switches=$switches draftLoss=$draftLoss optionLoss=$optionLoss streamLoss=$streamLoss anchorLoss=$anchorLoss streamedChars=${state.streamedChars}/${state.streamTarget.length}")
        assertEquals(10, switches)
        assertEquals(0, draftLoss, "draft"); assertEquals(0, optionLoss, "selected option"); assertEquals(0, streamLoss, "stream"); assertEquals(0, anchorLoss, "scroll anchor")
        assertEquals(state.streamTarget.length, state.streamedChars)
    }

    @Test fun readerAwayFromTheTailKeepsPlaceDraftOptionAndStream() = runCase(readerPinned = false)
    @Test fun readerFollowingTheTailStaysAtTheTail() = runCase(readerPinned = true)

    @Test fun typingAndTapGoThroughTheRealWidgets() = runComposeUiTest {
        val state = LabState()
        setContent { Box(Modifier.size(704.dp, 933.dp)) { FormFactorLab(state, tickMs = 1000L) } }
        waitForIdle()
        onNodeWithTag("composer").performTextInput("你好")
        assertEquals("你好", state.draft.text)
        onNodeWithTag("chat-list").performScrollToIndex(1)
        onNodeWithTag("option-C").performClick()
        assertEquals("C", state.selected["q1"])
    }
}
