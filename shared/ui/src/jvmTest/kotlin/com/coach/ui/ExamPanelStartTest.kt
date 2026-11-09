package com.coach.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import com.coach.client.*
import com.coach.domain.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import kotlin.test.*

/** 期末面板的「点一下就开始」：只发一次、历史在也照发、重连不重发、离开就作废。课程数据全是合成的，不连服务端或导师桥。 */
@OptIn(ExperimentalTestApi::class)
class ExamPanelStartTest {
    private fun view(mastery: Map<String, Mastery>, vararg points: Pair<String, String>): CourseView {
        val e = Event(1, "outline", "SYN", "outline_revised", recordedAt = 1, occurredAt = 1, deviceId = "t", actor = "SYSTEM",
            payload = json("knowledgePoints" to JsonArray(points.map { (id, title) -> json("id" to id, "title" to title) })))
        val p = project(listOf(e))
        return CourseView(listOf(e), p.copy(mastery = mastery))
    }

    @Suppress("UNCHECKED_CAST")
    private fun unusedSync(): SyncEngine {
        val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return (f.get(null) as sun.misc.Unsafe).allocateInstance(SyncEngine::class.java) as SyncEngine
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

    private val history = listOf(ChatItem.Learner("l:old", "Synthetic earlier question"))

    @Test fun oneClickStartsOnceAndAReconnectDoesNotSendItAgain() {
        val queue = Channel<String>(Channel.UNLIMITED)
        val tutor = tutor(queue)
        try {
            runComposeUiTest {
                val c = CoachController("SYN", unusedSync(), tutor, formatClock = { "" })
                val v = view(mapOf("a" to Mastery(passed = true, needsRepair = true)), "a" to "【W1·Ch1】Synthetic point")
                var connected by mutableStateOf(true)
                var opened by mutableStateOf<String?>(null)
                setContent {
                    CoachTheme(false) {
                        val ui = TutorUiState(connected = connected, threads = mapOf("kp:a" to ThreadView(historyLoaded = true, items = history)))
                        if (opened == null) ExamPanelCard(c, v, ExamFocus(listOf(WeekFocus(1, 1.0))), onOpen = { id, _ -> opened = id }, onPractice = {})
                        else StudyScreen(c, v.kp(opened!!)!!, v, ui, autoStart = true, onBack = null)
                    }
                }
                onNode(hasClickAction() and hasText("修复 →")).performClick()
                waitForIdle()
                assertEquals(1, frames(queue).count { it == "tutor.next" }, "the repair click teaches once")
                connected = false; waitForIdle()
                connected = true; waitForIdle()
                assertEquals(0, frames(queue).count { it == "tutor.next" || it == "tutor.test" }, "a reconnect must not resend the click")
            }
        } finally { tutor.close(); queue.cancel() }
    }

    @Test fun aClickWhileTheMacIsOfflineStartsWhenItComesBackAndIsVoidedByLeaving() {
        val queue = Channel<String>(Channel.UNLIMITED)
        val tutor = tutor(queue)
        try {
            runComposeUiTest {
                val c = CoachController("SYN", unusedSync(), tutor, formatClock = { "" })
                val v = view(mapOf("a" to Mastery(passed = true)), "a" to "【W1·Ch1】Synthetic point")
                var connected by mutableStateOf(false)
                var opened by mutableStateOf<String?>(null)
                setContent {
                    CoachTheme(false) {
                        val ui = TutorUiState(connected = connected, threads = mapOf("kp:a" to ThreadView(historyLoaded = true, items = history)))
                        if (opened == null) ExamPanelCard(c, v, ExamFocus(listOf(WeekFocus(1, 1.0))), onOpen = { id, _ -> opened = id }, onPractice = {})
                        else StudyScreen(c, v.kp(opened!!)!!, v, ui, autoStart = true, onBack = null)
                    }
                }
                onNode(hasClickAction() and hasText("开练 →")).performClick()
                waitForIdle()
                assertEquals(emptyList(), frames(queue).filter { it == "tutor.next" || it == "tutor.test" }, "nothing is sent while offline")
                connected = true; waitForIdle()
                assertEquals(listOf("tutor.test"), frames(queue).filter { it == "tutor.next" || it == "tutor.test" }, "practice asks for questions once the Mac is back")

                // 再点一次、但没连上就离开：这次启动作废，之后从路线打开同一个点不会自己开练。
                opened = null; connected = false; waitForIdle()
                onNode(hasClickAction() and hasText("开练 →")).performClick()
                waitForIdle()
                opened = null; waitForIdle()
                assertTrue(c.startRequests.isEmpty(), "leaving drops the pending start")
            }
        } finally { tutor.close(); queue.cancel() }
    }

    @Test fun anEmptyTopThreeSaysWhyInsteadOfClaimingEverythingIsSolid() {
        val queue = Channel<String>(Channel.UNLIMITED)
        val tutor = tutor(queue)
        try {
            runComposeUiTest {
                val c = CoachController("SYN", unusedSync(), tutor, formatClock = { "" })
                val v = view(mapOf("a" to Mastery(passed = true, solid = true)), "a" to "【W1·Ch1】Synthetic point", "b" to "【W2·Ch2】Other point")
                var focus by mutableStateOf(ExamFocus(listOf(WeekFocus(1, 1.0), WeekFocus(2, 0.0))))
                setContent { CoachTheme(false) { ExamPanelCard(c, v, focus, { _, _ -> }, {}) } }
                onNodeWithText("各周都稳了，没有要补的。").assertDoesNotExist()
                onNodeWithText("W2 还没掌握", substring = true).assertExists()

                focus = ExamFocus(listOf(WeekFocus(1, 1.0), WeekFocus(2, 0.0), WeekFocus(7, 2.0), WeekFocus(8, 1.0)))
                waitForIdle()
                onNodeWithText("押题的 W7–W8 在大纲里还没有知识点", substring = true).assertExists()
            }
        } finally { tutor.close(); queue.cancel() }
    }
}
