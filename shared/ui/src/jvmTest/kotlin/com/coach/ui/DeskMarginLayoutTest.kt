package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.coach.ui.adaptive.WindowSpec
import com.coach.ui.v7.Desk
import com.coach.ui.v7.LocalDeskWindow
import kotlin.math.abs
import kotlin.test.*

/** V7 三栏版心：有页边内容时照旧留页边栏；明确说没有页边内容时不留空栏，纸面居中。 */
@OptIn(ExperimentalTestApi::class)
class DeskMarginLayoutTest {
    private fun ComposeUiTest.centred(label: String) {
        val desk = onNodeWithTag("v7-desk").getBoundsInRoot()
        val paper = onNodeWithTag("v7-paper").getBoundsInRoot()
        val left = (paper.left - desk.left).value
        val right = (desk.right - paper.right).value
        assertTrue(abs(left - right) <= 1f, "$label paper is centred: desk=$desk paper=$paper")
        assertTrue(right > 0f, "$label paper leaves canvas on the right: desk=$desk paper=$paper")
    }

    @Test fun deskWithoutMarginContentDropsTheEmptyColumnAndCentresThePaper() {
        runDesktopComposeUiTest(2000, 1000) {
            var width by mutableStateOf(1900)
            var focus by mutableStateOf(false)
            setContent {
                CoachTheme(false) {
                    CompositionLocalProvider(LocalAppManagedNavigation provides true, LocalDeskWindow provides WindowSpec(width.toFloat(), 900f)) {
                        Box(Modifier.size(width.dp, 900.dp)) {
                            Desk("合成复习", focus = focus, onFocus = { focus = !focus }, hasMargin = false) { Text("合成正文") }
                        }
                    }
                }
            }
            for (size in listOf(1900, 1280, 1100)) {
                runOnIdle { width = size }
                waitForIdle()
                onNodeWithText("合成正文").assertIsDisplayed()
                onNodeWithTag("v7-margin").assertDoesNotExist()
                onNodeWithTag("v7-margin-toggle").assertDoesNotExist()
                centred("$size")
            }
            // 宽窗口下纸面不无限拉宽，两侧留出桌面。
            runOnIdle { width = 1900 }
            waitForIdle()
            val paper = onNodeWithTag("v7-paper").getBoundsInRoot()
            assertTrue((paper.right - paper.left).value <= 1046f + 1f, "paper keeps the V7 measure: $paper")
            // 专心读照旧铺满。
            onNodeWithTag("v7-focus").performClick()
            waitForIdle()
            onNodeWithTag("v7-margin").assertDoesNotExist()
            val desk = onNodeWithTag("v7-desk").getBoundsInRoot()
            val focused = onNodeWithTag("v7-paper").getBoundsInRoot()
            assertTrue(abs(((desk.right - desk.left) - (focused.right - focused.left)).value) <= 1f, "focus mode paper spans the desk: desk=$desk paper=$focused")
        }
    }

    @Test fun deskWithMarginContentKeepsTheMarginColumnAndToggle() {
        runDesktopComposeUiTest(2000, 1000) {
            setContent {
                CoachTheme(false) {
                    CompositionLocalProvider(LocalAppManagedNavigation provides true, LocalDeskWindow provides WindowSpec(1900f, 900f)) {
                        Box(Modifier.size(1900.dp, 900.dp)) {
                            Desk("合成题目", margin = { Text("合成出处") }) { Text("合成正文") }
                        }
                    }
                }
            }
            onNodeWithTag("v7-margin").assertIsDisplayed()
            onNodeWithText("合成出处").assertIsDisplayed()
            onNodeWithTag("v7-margin-toggle").assertIsDisplayed()
            val desk = onNodeWithTag("v7-desk").getBoundsInRoot()
            val paper = onNodeWithTag("v7-paper").getBoundsInRoot()
            val margin = onNodeWithTag("v7-margin").getBoundsInRoot()
            assertTrue(margin.left >= paper.right - 1.dp, "margin sits to the right of the paper: paper=$paper margin=$margin")
            assertTrue(desk.right - margin.right < 40.dp, "margin column hugs the desk edge: desk=$desk margin=$margin")
        }
    }
}
