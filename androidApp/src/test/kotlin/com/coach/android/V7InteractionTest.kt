package com.coach.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.coach.ui.CoachTheme
import com.coach.ui.AppTab
import com.coach.ui.DeskNavigation
import com.coach.ui.v7.LocalDeskNavigation
import com.coach.ui.v7.Desk
import com.coach.ui.v7.LocalV7ReducedMotion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1224dp-h924dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V7InteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reviewBackKeepsItsDestinationAndWorksWithoutBottomNavigation() {
        var dimensions by mutableStateOf(411 to 900)
        var focused by mutableStateOf(false)
        var returned by mutableStateOf(false)
        compose.setContent { CoachTheme(false) {
            CompositionLocalProvider(LocalDeskNavigation provides { Text("底部导航") }) {
                Box(Modifier.requiredSize(dimensions.first.dp, dimensions.second.dp)) {
                    if (returned) Text("回到复习队列")
                    else Desk("合成复习", onBack = { returned = true }, backLabel = "← 复习",
                        focus = focused, showNavigation = false) { Text("待答题") }
                }
            }
        } }
        for (window in listOf(411 to 900, 933 to 704, 704 to 933, 1224 to 924)) {
            for (focus in listOf(false, true)) {
                compose.runOnIdle { dimensions = window; focused = focus; returned = false }
                compose.onNodeWithText("底部导航").assertDoesNotExist()
                compose.onNodeWithText("← 复习").assertIsDisplayed().performClick()
                compose.onNodeWithText("回到复习队列").assertIsDisplayed()
            }
        }
    }

    @Test fun mediumWindowCanHideNotesAndChangeTypeWithoutLosingTheDraft() {
        compose.setContent { CoachTheme(false) {
            Box(Modifier.requiredSize(933.dp, 704.dp)) {
                Desk("合成课程", directory = { Text("合成周目录") }, margin = { Text("合成页边") }) {
                    var draft by remember { mutableStateOf("") }
                    Column {
                        Text("正文随字号重排", Modifier.testTag("sample-body"), style = MaterialTheme.typography.bodyLarge)
                        OutlinedTextField(draft, { draft = it }, Modifier.testTag("draft"))
                    }
                }
            }
        } }
        compose.onNodeWithTag("draft").performTextInput("keep this draft")
        val before = compose.onNodeWithTag("sample-body").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        compose.onNodeWithTag("v7-directory").assertDoesNotExist()
        compose.onNodeWithTag("v7-margin").assertIsDisplayed()
        compose.onNodeWithTag("v7-directory-toggle").performClick()
        compose.onNodeWithTag("v7-directory-drawer").assertIsDisplayed()
        compose.onNodeWithText("收起目录").performClick()
        compose.onNodeWithTag("v7-margin-toggle").performClick()
        compose.onNodeWithTag("v7-margin").assertDoesNotExist()
        compose.onNodeWithTag("v7-type-menu").performClick()
        compose.onNodeWithTag("v7-type-大").performClick()
        val after = compose.onNodeWithTag("sample-body").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue(after > before)
        compose.onNodeWithTag("draft").assertTextContains("keep this draft")
        compose.onNodeWithTag("v7-margin-toggle").performClick()
        compose.onNodeWithTag("v7-margin").assertIsDisplayed()
    }

    @Test fun phoneNotesRemainReachableInABottomSheet() {
        compose.setContent { CoachTheme(false) {
            Box(Modifier.requiredSize(411.dp, 900.dp)) {
                Desk("合成课程", margin = { Text("窄屏参考原文") }) { Text("正文") }
            }
        } }
        compose.onNodeWithTag("v7-margin").assertDoesNotExist()
        compose.onNodeWithTag("v7-margin-toggle").performClick()
        compose.onNodeWithTag("v7-margin-sheet").assertIsDisplayed()
        compose.onNodeWithText("窄屏参考原文").assertIsDisplayed()
    }

    @Test fun focusAndFoldKeepTheSameReadingDraftAndSelection() {
        compose.setContent { CoachTheme(false) {
            var focus by remember { mutableStateOf(false) }
            var folded by remember { mutableStateOf(false) }
            Box(Modifier.requiredSize(if (folded) 624.dp else 1224.dp, 924.dp)) {
                Desk("合成阅读", focus = focus, onFocus = { focus = !focus }) {
                    var draft by remember { mutableStateOf("") }
                    var chosen by remember { mutableStateOf(false) }
                    Column {
                        OutlinedTextField(draft, { draft = it }, Modifier.testTag("draft"))
                        Checkbox(chosen, { chosen = it }, Modifier.testTag("choice"))
                        TextButton(onClick = { folded = !folded }, modifier = Modifier.testTag("fold")) { Text("合成窗口切换") }
                    }
                }
            }
        } }
        compose.onNodeWithTag("draft").performTextInput("draft survives")
        compose.onNodeWithTag("choice").performClick()
        compose.onNodeWithTag("v7-focus").performClick()
        compose.onNodeWithTag("draft").assertTextContains("draft survives")
        compose.onNodeWithTag("choice").assertIsOn()
        compose.onNodeWithTag("v7-focus").performClick()
        compose.onNodeWithTag("fold").performClick()
        compose.onNodeWithTag("draft").assertTextContains("draft survives")
        compose.onNodeWithTag("choice").assertIsOn()
        compose.onNodeWithTag("v7-directory").assertDoesNotExist()
    }

    @Test fun phoneWeekFooterKeepsTheReviewNavigationReachable() {
        var selected = AppTab.TODAY
        compose.setContent { CoachTheme(false) {
            CompositionLocalProvider(LocalDeskNavigation provides { vertical -> DeskNavigation(AppTab.TODAY, vertical) { selected = it } }) {
                Box(Modifier.requiredSize(624.dp, 986.dp)) {
                    Desk("合成周页", footer = { Text("合成周页的相邻周") }) { Text("合成正文") }
                }
            }
        } }
        compose.onNodeWithText("复习").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(AppTab.REVIEW, selected) }
    }

    @Test fun reducedMotionRevealsPaperWithoutAdvancingTheAnimationClock() {
        compose.mainClock.autoAdvance = false
        compose.setContent { CoachTheme(false) {
            CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                Desk("合成阅读") { Text("纸面立即可读") }
            }
        } }
        compose.onNodeWithText("纸面立即可读").assertIsDisplayed()
        // A transparent/unstarted page would expose the darker desk at this point.
        val pixels = compose.onNodeWithTag("v7-paper").captureToImage().toPixelMap()
        val paper = pixels[pixels.width / 2, pixels.height - 40]
        assertEquals(Color(0xFFFCFAF6).red, paper.red, .005f)
        assertEquals(Color(0xFFFCFAF6).green, paper.green, .005f)
        assertEquals(Color(0xFFFCFAF6).blue, paper.blue, .005f)
    }
    @Test fun innerBrandMenuKeepsRecordsReachable() {
        var selected = AppTab.TODAY
        compose.setContent { CoachTheme(false) {
            CompositionLocalProvider(com.coach.ui.v7.LocalDeskSelect provides { selected = it }) {
                Desk("合成周页") { Text("合成正文") }
            }
        } }
        compose.onNodeWithText("Coach").performClick()
        compose.onNodeWithText("记录").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(AppTab.RECORDS, selected) }
    }

}
