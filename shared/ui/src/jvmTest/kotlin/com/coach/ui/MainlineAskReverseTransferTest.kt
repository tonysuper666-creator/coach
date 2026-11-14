package com.coach.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.client.*
import com.coach.domain.*
import com.coach.protocol.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * Drawer already open, then the same control restores the companion rail.
 * Forward rail-to-drawer coverage stays in MainlineAskFlowTest.
 */
@OptIn(ExperimentalTestApi::class)
class MainlineAskReverseTransferTest {
    @Test fun mediumDrawerExpandsSidebarWithoutLosingTheQuestion() = reverse(933, 704, "v7-margin-toggle")

    @Test fun mediumDrawerExitsFocusWithoutLosingTheQuestion() = reverse(933, 704, "v7-focus")

    @Test fun innerDrawerExpandsSidebarWithoutLosingTheQuestion() = reverse(1280, 800, "v7-margin-toggle")

    @Test fun innerDrawerExitsFocusWithoutLosingTheQuestion() = reverse(1280, 800, "v7-focus")

    private fun reverse(width: Int, height: Int, hideControl: String) {
        val now = 1_794_052_800_000L
        val release = CountDownLatch(1)
        val held = CountDownLatch(1)
        val lateRelease = CountDownLatch(1)
        val lateHeld = CountDownLatch(1)
        val draft = "Hold synthetic draft across reverse layout"
        val reply = "Synthetic answer after reverse layout"
        val lateQuestion = "Late synthetic response for S1"
        val lateText = "Synthetic late response for S1"
        MainlineScreenTest.FakeMac(logicalDay(now, "Europe/London"), explain = { wire ->
            if (wire.str("question").startsWith("Late")) {
                lateHeld.countDown()
                check(lateRelease.await(20, TimeUnit.SECONDS)) { "late explain was not released" }
                json("text" to lateText, "sources" to emptyList<String>())
            } else {
                held.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "explain was not released" }
                json("text" to reply, "sources" to emptyList<String>())
            }
        }).use { mac ->
            SyntheticLocalLedger("C").use { local ->
                val tutor = TutorClient("http://127.0.0.1:1", "synthetic", "synthetic", TutorModel({ now }))
                val c = CoachController("C", local.sync, tutor, formatClock = { "12:00" }, nowMillis = { now }, course = mac.http)
                val back = AppBackDispatcher()
                local.sync.saveMainlineState("C", json("week" to 1, "entry" to "quiz"))
                try {
                    runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat()), density = Density(1f)) {
                        setContent { CompositionLocalProvider(LocalAppBack provides back) { CoachTheme(false) { Surface(Modifier.fillMaxSize()) {
                            MainlineScreen(c, CourseView(emptyList(), project(emptyList())), {}, {}, {}, {})
                        } } } }
                        fun fieldText() = onNodeWithTag("ladder-question").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
                        fun enterDrawer() {
                            if (onAllNodesWithTag("v7-margin").fetchSemanticsNodes().isNotEmpty()) onNodeWithTag(hideControl).performClick()
                            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("v7-margin").fetchSemanticsNodes().isEmpty() }
                        }
                        fun stage(label: String): String {
                            val toggles = onAllNodesWithTag(hideControl).fetchSemanticsNodes()
                            val text = toggles.joinToString(" | ") { node ->
                                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
                            }
                            val asks = onAllNodesWithTag("ladder-question").fetchSemanticsNodes()
                            val ask = asks.singleOrNull()
                            val field = if (ask != null && ask.config.contains(SemanticsProperties.EditableText)) ask.config[SemanticsProperties.EditableText].text else "<none>"
                            return "$label toggle=${toggles.size} text=[$text] margin=${onAllNodesWithTag("v7-margin").fetchSemanticsNodes().size} ask=${asks.size} field=$field http=${mac.explains.size} cancelled=${mac.cancellations.toList()}"
                        }
                        fun restoreRail() {
                            val httpBefore = mac.explains.size
                            val before = stage("before-click")
                            onNodeWithTag(hideControl).performClick()
                            val restored = try {
                                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("v7-margin").fetchSemanticsNodes().isNotEmpty() }
                                true
                            } catch (_: ComposeTimeoutException) { false }
                            val after = stage("after-click")
                            if (!restored) throw AssertionError("pointer reverse did not restore the rail\n$before\n$after")
                            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-question").fetchSemanticsNodes().size == 1 }
                            assertEquals(httpBefore, mac.explains.size, "Restoring the rail must not start another explain\n$before\n$after")
                        }
                        fun openAsk(text: String) {
                            onNodeWithTag("mainline-ask").performScrollTo().performClick()
                            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-question").fetchSemanticsNodes().size == 1 }
                            onNodeWithTag("ladder-question").performTextInput(text)
                            waitUntil(timeoutMillis = 10_000) { fieldText() == text }
                        }
                        fun assertKept(original: kotlinx.serialization.json.JsonObject, requestId: String?) {
                            for (key in listOf("item", "choice", "result", "ladderSession", "quizScroll"))
                                assertEquals(original[key], local.sync.mainlineState("C", "week:1")[key], key)
                            assertFalse(requestId != null && requestId in mac.cancellations, "A reverse layout change must not cancel the live request")
                            onNodeWithTag("mainline-option-1").assertIsSelected()
                        }

                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-option-1").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("mainline-option-1").performScrollTo().performClick()
                        onNodeWithTag("mainline-submit").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("mainline-outcome").fetchSemanticsNodes().isNotEmpty() }

                        enterDrawer()
                        openAsk(draft)
                        onNodeWithText("发送").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { held.count == 0L }
                        waitForIdle()
                        val inflight = local.sync.mainlineState("C", "week:1")
                        val requestId = mac.explains.single().str("requestId")
                        assertEquals("S1", mac.explains.single().str("itemId"))
                        captureLessonEvidence("ask-reverse-$width-$hideControl-pending-panel", width, height,
                            captureRoot = onNode(isRoot() and hasAnyDescendant(hasTestTag("ladder-question"))))
                        restoreRail()
                        assertEquals(draft, fieldText(), "reverse layout dropped the in-flight draft")
                        onNodeWithText("正在查课件和教材依据…").assertExists()
                        assertKept(inflight, requestId)
                        release.countDown()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(reply).fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithText(reply).performScrollTo().assertIsDisplayed()
                        assertEquals(1, mac.explains.size, "The arrived reply must stay the same single explain")
                        assertEquals("S1", mac.explains.single().str("itemId"))
                        assertEquals("S1", local.sync.mainlineState("C", "week:1").str("item"))
                        captureLessonEvidence("ask-reverse-$width-$hideControl-restored-rail", width, height)

                        onNodeWithText("回到这道题").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-question").fetchSemanticsNodes().isEmpty() }
                        enterDrawer()
                        openAsk(draft)
                        waitForIdle()
                        val drafted = local.sync.mainlineState("C", "week:1")
                        val sent = mac.explains.size
                        restoreRail()
                        assertEquals(draft, fieldText(), "reverse layout dropped the unsent draft")
                        assertEquals(sent, mac.explains.size, "An unsent draft must not explain")
                        assertKept(drafted, null)

                        onNodeWithText("回到这道题").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-question").fetchSemanticsNodes().isEmpty() }
                        enterDrawer()
                        openAsk(lateQuestion)
                        onNodeWithText("发送").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { lateHeld.count == 0L }
                        waitForIdle()
                        val lateState = local.sync.mainlineState("C", "week:1")
                        val lateId = mac.explains.last().str("requestId")
                        assertEquals(2, mac.explains.size)
                        restoreRail()
                        assertEquals(lateQuestion, fieldText(), "reverse layout dropped the late draft")
                        assertEquals("S1", mac.explains.last().str("itemId"))
                        assertKept(lateState, lateId)
                        onNodeWithTag("mainline-next").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Synthetic orchard question D1:", substring = true).fetchSemanticsNodes().isNotEmpty() }
                        lateRelease.countDown()
                        waitUntil(timeoutMillis = 10_000) { lateId in mac.cancellations }
                        onNodeWithText(lateText).assertDoesNotExist()
                        onNodeWithText(reply).assertDoesNotExist()
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-question").fetchSemanticsNodes().size == 1 }
                        assertEquals("", fieldText())
                        val moved = local.sync.mainlineState("C", "week:1")
                        assertEquals("D1", moved.str("item"))
                        assertFalse(moved["result"] is kotlinx.serialization.json.JsonObject)
                        assertEquals(2, mac.explains.size, "A late reply must not send another explain")
                        runOnIdle { assertTrue(back.back()) }
                        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("ladder-question").fetchSemanticsNodes().isEmpty() }
                        onNodeWithText("Synthetic orchard question D1:", substring = true).assertExists()
                        assertEquals("D1", local.sync.mainlineState("C", "week:1").str("item"), "Back closes help before leaving the question")
                        println("LADDER_TRACE viewport=${width}x$height control=$hideControl drawer-open -> rail:draft -> drawer:one-http -> rail:same-request/reply -> drawer:late -> rail -> D1:late-hidden")
                    }
                } finally { release.countDown(); lateRelease.countDown(); tutor.close() }
            }
        }
    }
}
