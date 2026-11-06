package com.coach.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import com.coach.client.*
import com.coach.domain.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import kotlin.test.*

/** Independent acceptance checks against PR #91; all course data are synthetic. */
@OptIn(ExperimentalTestApi::class)
class QaExamPanelAcceptanceTest {
    private fun view(mastery: Mastery = Mastery()): CourseView {
        val e = Event(1, "outline", "SYN", "outline_revised", recordedAt = 1,
            occurredAt = 1, deviceId = "qa", actor = "SYSTEM",
            payload = json("knowledgePoints" to JsonArray(listOf(json("id" to "a", "title" to "【W1·Ch1】Synthetic point")))))
        return CourseView(listOf(e), project(listOf(e)).copy(mastery = mapOf("a" to mastery)))
    }

    @Suppress("UNCHECKED_CAST")
    private fun unusedSync(): SyncEngine {
        val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return (f.get(null) as sun.misc.Unsafe).allocateInstance(SyncEngine::class.java) as SyncEngine
    }

    /** Capture outgoing protocol frames without connecting any server or bridge. */
    private fun tutor(queue: Channel<String>): TutorClient {
        val model = TutorModel({ 1_800_000_000_000L })
        model.onConnection(true)
        return TutorClient("http://127.0.0.1:18959", "qa", "synthetic", model).also {
            TutorClient::class.java.getDeclaredField("live").apply { isAccessible = true }.set(it, queue)
        }
    }

    private fun frames(queue: Channel<String>): List<String> = buildList {
        while (true) {
            val frame = queue.tryReceive().getOrNull() ?: break
            add(Json.parseToJsonElement(frame).jsonObject.getValue("t").jsonPrimitive.content)
        }
    }

    private fun ComposeUiTest.mount(c: CoachController, v: CourseView, history: List<ChatItem>) {
        setContent {
            var opened by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
            CoachTheme(false) {
                if (opened == null) ExamPanelCard(c, v, ExamFocus(listOf(WeekFocus(1, 1.0))),
                    onOpen = { id, start -> opened = id to start }, onPractice = {})
                else StudyScreen(c, v.kp(opened!!.first)!!, v,
                    TutorUiState(connected = true, threads = mapOf("kp:a" to ThreadView(historyLoaded = true, items = history))),
                    opened!!.second, onBack = null)
            }
        }
    }

    @Test fun freshTeachClickStartsLearning() {
        val queue = Channel<String>(Channel.UNLIMITED)
        val tutor = tutor(queue)
        try {
            runComposeUiTest {
                val c = CoachController("SYN", unusedSync(), tutor, formatClock = { "" })
                mount(c, view(), emptyList())
                onNode(hasClickAction() and hasText("开讲 →")).performClick()
                waitForIdle()
                val sent = frames(queue)
                assertTrue("tutor.next" in sent, "Fresh teaching should start; actual frames=$sent")
            }
        } finally { tutor.close(); queue.cancel() }
    }

    @Test fun practiceClickMustStartEvenWhenTheThreadHasHistory() {
        val queue = Channel<String>(Channel.UNLIMITED)
        val tutor = tutor(queue)
        try {
            runComposeUiTest {
                val c = CoachController("SYN", unusedSync(), tutor, formatClock = { "" })
                mount(c, view(Mastery(passed = true)),
                    listOf(ChatItem.Learner("l:old", "Synthetic earlier question")))
                onNode(hasClickAction() and hasText("开练 →")).performClick()
                waitForIdle()
                val sent = frames(queue)
                assertTrue("tutor.next" in sent || "tutor.test" in sent,
                    "One practice click must start learning even with restored history; actual frames=$sent")
            }
        } finally { tutor.close(); queue.cancel() }
    }

    @Test fun repairClickMustStartEvenWhenTheThreadHasHistory() {
        val queue = Channel<String>(Channel.UNLIMITED)
        val tutor = tutor(queue)
        try {
            runComposeUiTest {
                val c = CoachController("SYN", unusedSync(), tutor, formatClock = { "" })
                mount(c, view(Mastery(passed = true, needsRepair = true)),
                    listOf(ChatItem.Learner("l:old", "Synthetic earlier question")))
                onNode(hasClickAction() and hasText("修复 →")).performClick()
                waitForIdle()
                val sent = frames(queue)
                assertTrue("tutor.next" in sent || "tutor.test" in sent,
                    "One repair click must start learning even with restored history; actual frames=$sent")
            }
        } finally { tutor.close(); queue.cancel() }
    }

    @Test fun emptyRankingMustNotClaimUnlearnedPointsAreSolid() {
        val queue = Channel<String>(Channel.UNLIMITED)
        val tutor = tutor(queue)
        try {
            runComposeUiTest {
                val v = view()
                assertEquals(KpStatus.NEW, v.kps.single().status)
                val c = CoachController("SYN", unusedSync(), tutor, formatClock = { "" })
                setContent { CoachTheme(false) {
                    ExamPanelCard(c, v, ExamFocus(listOf(WeekFocus(1, 0.0))), { _, _ -> }, {})
                } }
                onNodeWithText("各周都已稳固，没有要补的。").assertDoesNotExist()
            }
        } finally { tutor.close(); queue.cancel() }
    }
}
