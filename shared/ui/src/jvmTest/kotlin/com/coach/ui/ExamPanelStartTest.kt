package com.coach.ui

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import com.coach.client.*
import com.coach.domain.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import kotlin.test.*

/** The unified entry opens the shared target once, online or offline, without starting a second tutor route. */
@OptIn(ExperimentalTestApi::class)
class ExamPanelStartTest {
    private fun view(mastery: Map<String, Mastery>, vararg points: Pair<String, String>): CourseView {
        val e = Event(1, "outline", "SYN", "outline_revised", recordedAt = 1, occurredAt = 1, deviceId = "t", actor = "SYSTEM",
            payload = json("knowledgePoints" to JsonArray(points.map { (id, title) -> json("id" to id, "title" to title) })))
        val p = project(listOf(e))
        return CourseView(listOf(e), p.copy(mastery = mastery))
    }

    /** 把发出去的帧接到本地队列里，不连任何服务。 */
    private fun tutor(queue: Channel<String>): TutorClient {
        val model = TutorModel({ 1_800_000_000_000L })
        model.onConnection(true)
        return TutorClient("http://127.0.0.1:18959", "t", "synthetic", model).also {
            TutorClient::class.java.getDeclaredField("live").apply { isAccessible = true }.set(it, queue)
        }
    }

    private fun frames(queue: Channel<String>): List<String> = buildList {
        while (true) add(Json.parseToJsonElement(queue.tryReceive().getOrNull() ?: break).jsonObject.getValue("t").jsonPrimitive.content)
    }

    @Test fun oneClickStartsOnceAndAReconnectDoesNotSendItAgain() {
        val queue = Channel<String>(Channel.UNLIMITED)
        val tutor = tutor(queue)
        val ledger = SyntheticLocalLedger()
        try {
            runComposeUiTest {
                val c = CoachController("SYN", ledger.sync, tutor, formatClock = { "" })
                val v = view(mapOf("a" to Mastery(passed = true, needsRepair = true)), "a" to "【W1·Ch1】Synthetic point")
                var connected by mutableStateOf(true)
                var opened by mutableStateOf<String?>(null)
                var opens = 0
                setContent {
                    CoachTheme(false) {
                        SideEffect { tutor.model.onConnection(connected) }
                        Text(if (connected) "在线" else "离线")
                        if (opened == null) ExamPanelCard(c, v, ExamFocus(listOf(WeekFocus(1, 1.0))), onOpen = { id, start ->
                            assertEquals(c.learningTarget(v)?.kp?.id, id); assertTrue(start); opens++; opened = id
                        }, onPractice = {})
                        else Text("进入节 $opened")
                    }
                }
                onNodeWithTag("exam-continue").performClick()
                waitForIdle()
                assertEquals("a", opened)
                assertEquals(1, opens, "one click opens exactly the shared repair target")
                assertTrue(frames(queue).isEmpty(), "the panel must not also start the old tutor route")
                connected = false; waitForIdle()
                connected = true; waitForIdle()
                assertEquals(1, opens, "a reconnect must not reopen the session")
                assertEquals(0, frames(queue).count { it == "tutor.next" || it == "tutor.test" }, "a reconnect must not resend the click")
            }
        } finally { tutor.close(); queue.cancel(); ledger.close() }
    }

    @Test fun anOfflineClickOpensTheSameTargetAndLeavingDoesNotQueueASecondStart() {
        val queue = Channel<String>(Channel.UNLIMITED)
        val tutor = tutor(queue)
        val ledger = SyntheticLocalLedger()
        try {
            runComposeUiTest {
                val c = CoachController("SYN", ledger.sync, tutor, formatClock = { "" })
                val v = view(mapOf("a" to Mastery(passed = true)), "a" to "【W1·Ch1】Synthetic point", "b" to "【W2】Next point")
                var connected by mutableStateOf(false)
                var opened by mutableStateOf<String?>(null)
                var opens = 0
                setContent {
                    CoachTheme(false) {
                        SideEffect { tutor.model.onConnection(connected) }
                        Text(if (connected) "在线" else "离线")
                        if (opened == null) ExamPanelCard(c, v, ExamFocus(listOf(WeekFocus(1, 1.0))), onOpen = { id, start ->
                            assertEquals(c.learningTarget(v)?.kp?.id, id); assertTrue(start); opens++; opened = id
                        }, onPractice = {})
                        else Text("进入节 $opened")
                    }
                }
                onNodeWithTag("exam-continue").performClick()
                waitForIdle()
                assertEquals("b", opened, "offline and online use the same next unfinished section")
                assertFalse(tutor.model.state.value.connected)
                assertEquals(1, opens)
                assertEquals(emptyList(), frames(queue).filter { it == "tutor.next" || it == "tutor.test" }, "nothing is sent while offline")
                connected = true; waitForIdle()
                assertEquals(1, opens)
                assertTrue(frames(queue).isEmpty(), "reconnection must not start a separate practice route")

                // 再明确打开一次后离开；重连只能恢复连接，不能自己再开一个会话。
                opened = null; connected = false; waitForIdle()
                onNodeWithTag("exam-continue").performClick()
                waitForIdle()
                opened = null; waitForIdle()
                assertTrue(c.startRequests.isEmpty(), "the unified entry must not leave a hidden tutor start behind")
                connected = true; waitForIdle()
                assertEquals(2, opens, "only the two explicit clicks open a session")
            }
        } finally { tutor.close(); queue.cancel(); ledger.close() }
    }

    @Test fun anEmptyTopThreeSaysWhyInsteadOfClaimingEverythingIsSolid() {
        val queue = Channel<String>(Channel.UNLIMITED)
        val tutor = tutor(queue)
        val ledger = SyntheticLocalLedger()
        try {
            runComposeUiTest {
                val c = CoachController("SYN", ledger.sync, tutor, formatClock = { "" })
                val v = view(mapOf("a" to Mastery(passed = true, solid = true)), "a" to "【W1·Ch1】Synthetic point", "b" to "【W2·Ch2】Other point")
                var focus by mutableStateOf(ExamFocus(listOf(WeekFocus(1, 1.0), WeekFocus(2, 0.0))))
                setContent { CoachTheme(false) { ExamPanelCard(c, v, focus, { _, _ -> }, {}) } }
                onNodeWithText("各周都稳了，没有要补的。").assertDoesNotExist()
                onNodeWithText("第 2 周 还没会", substring = true).assertExists()

                focus = ExamFocus(listOf(WeekFocus(1, 1.0), WeekFocus(2, 0.0), WeekFocus(7, 2.0), WeekFocus(8, 1.0)))
                waitForIdle()
                onNodeWithText("有分值的第 7–8 周在课程里还没有对应的节", substring = true).assertExists()
            }
        } finally { tutor.close(); queue.cancel(); ledger.close() }
    }
}
