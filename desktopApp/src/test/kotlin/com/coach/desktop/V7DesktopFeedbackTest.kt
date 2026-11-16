package com.coach.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.ui.CoachTheme
import com.coach.ui.theme.CoachTheme as Theme
import com.coach.ui.v7.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class V7DesktopFeedbackTest {
    @Test fun mouseFeedbackAndKeyboardFocusRemainDistinctInBothThemes() {
        for (dark in listOf(false, true)) runSkikoComposeUiTest(size = Size(640f, 240f), density = Density(1f)) {
            var clicks = 0
            setContent { CoachTheme(dark) {
                Row(Modifier.fillMaxSize().background(Theme.colors.bgCanvas).padding(40.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    PaperButton({ clicks++ }, Modifier.testTag("paper")) { Text("继续学") }
                    PrimaryButton({ clicks++ }, Modifier.testTag("primary")) { Text("下一课") }
                    PaperButton({ clicks++ }, Modifier.testTag("disabled"), enabled = false) { Text("暂不可用") }
                }
            } }
            waitForIdle()
            for (tag in listOf("paper", "primary", "disabled")) {
                val node = onNodeWithTag(tag)
                val b = node.fetchSemanticsNode().boundsInRoot
                fun fill(): Color = onRoot().captureToImage().toPixelMap()[(b.left + 12).toInt(), b.center.y.toInt()]
                val normal = fill()
                node.performMouseInput { moveTo(center) }
                waitForIdle()
                val hover = fill()
                node.performMouseInput { press() }
                waitForIdle()
                val down = fill()
                node.performMouseInput { release(); exit() }
                waitForIdle()
                if (tag == "disabled") {
                    assertEquals(normal, hover, "disabled hover")
                    assertEquals(normal, down, "disabled press")
                } else {
                    assertNotEquals(normal, hover, "$dark $tag hover")
                    assertNotEquals(hover, down, "$dark $tag press")
                    assertEquals(normal, fill(), "$dark $tag click restores fill")
                }
            }
            assertEquals(2, clicks, "disabled button must not dispatch")
            onNodeWithTag("paper").requestFocus()
            onNodeWithTag("paper").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("primary").assertIsFocused()
            val b = onNodeWithTag("primary").fetchSemanticsNode().boundsInRoot
            fun ring() = onRoot().captureToImage().toPixelMap()[(b.left - 3).toInt(), b.center.y.toInt()]
            val keyboard = ring()
            onNodeWithTag("primary").performMouseInput { click() }
            waitForIdle()
            assertNotEquals(keyboard, ring(), "$dark mouse click must remove ring even on an already focused button")
            onNodeWithTag("primary").performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft) }
            onNodeWithTag("paper").performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            assertEquals(keyboard, ring(), "$dark Tab restores keyboard ring")
        }
    }

    @Test fun tooltipWaitsHalfASecondShowsShortcutAndDismissesOnExit() = runSkikoComposeUiTest {
        setContent { CoachTheme(false) {
            V7Tooltip("返回书架", Modifier.testTag("tip"), shortcut = "Esc") { Text("书") }
        } }
        mainClock.autoAdvance = false
        onNodeWithTag("tip").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(400)
        onNodeWithText("返回书架").assertDoesNotExist()
        mainClock.advanceTimeBy(200)
        waitForIdle()
        onNodeWithText("返回书架").assertExists()
        onNodeWithText("Esc").assertExists()
        onNodeWithTag("tip").performMouseInput { exit() }
        mainClock.advanceTimeBy(32)
        waitForIdle()
        onNodeWithText("返回书架").assertDoesNotExist()
    }

    @Test fun scrollbarExpandsWithoutMovingContentAndDragsTheActualScrollState() {
        for (dark in listOf(false, true)) runSkikoComposeUiTest(size = Size(260f, 240f), density = Density(1f)) {
            lateinit var state: androidx.compose.foundation.ScrollState
            setContent { CoachTheme(dark) {
                state = rememberScrollState()
                Row(Modifier.fillMaxSize().background(Theme.colors.surface1).padding(20.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(state).testTag("content")) {
                        repeat(40) { Text("样张 $it") }
                    }
                    V7Scrollbar(rememberScrollbarAdapter(state), Modifier.fillMaxHeight().testTag("scroll"))
                }
            } }
            waitForIdle()
            val bar = onNodeWithTag("scroll")
            val b = bar.fetchSemanticsNode().boundsInRoot
            val content = onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
            fun thumbWidth(): Int {
                val pixels = onRoot().captureToImage().toPixelMap()
                val y = (b.top + 8).toInt()
                val bg = pixels[(b.left - 2).toInt(), y]
                return (b.left.toInt() until b.right.toInt()).count { pixels[it, y] != bg }
            }
            val thin = thumbWidth()
            bar.performMouseInput { moveTo(Offset(width / 2f, 8f)) }
            waitForIdle()
            assertTrue(thumbWidth() > thin, "hover expands thumb")
            assertEquals(content, onNodeWithTag("content").fetchSemanticsNode().boundsInRoot)
            bar.performMouseInput { press(); moveTo(Offset(width / 2f, 100f), 200); release() }
            waitForIdle()
            assertTrue(state.value > 0, "thumb drag must scroll content")
        }
    }
}
