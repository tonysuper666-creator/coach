package com.coach.ui

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import com.coach.client.*
import com.coach.domain.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import kotlin.test.*

/** Independent checks of the unified continuation callback; all course/session data are synthetic. */
@OptIn(ExperimentalTestApi::class)
class QaExamPanelAcceptanceTest {
    private fun view(mastery: Mastery = Mastery()): CourseView {
        val e = Event(1, "outline", "SYN", "outline_revised", recordedAt = 1,
            occurredAt = 1, deviceId = "qa", actor = "SYSTEM",
            payload = json("knowledgePoints" to JsonArray(listOf(json("id" to "a", "title" to "【W1·Ch1】Synthetic point")))))
        return CourseView(listOf(e), project(listOf(e)).copy(mastery = mapOf("a" to mastery)))
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

    private fun ComposeUiTest.mount(c: CoachController, v: CourseView, paused: Boolean = false) {
        if (paused) {
            c.sync.saveMainlineState("SYN", json("week" to 1))
            c.sync.saveMainlineState("SYN", json("mode" to "quiz", "item" to "Q", "choice" to 1,
                "body" to json("items" to JsonArray(listOf(json("id" to "Q", "status" to "new"))))), "week:1")
        }
        setContent {
            var opened by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
            CoachTheme(false) {
                if (opened == null) ExamPanelCard(c, v, ExamFocus(listOf(WeekFocus(1, 1.0))),
                    onOpen = { id, start ->
                        assertEquals(c.learningTarget(v)?.kp?.id, id)
                        assertEquals("a", id); assertTrue(start)
                        opened = id to start
                    }, onPractice = { fail("The panel must not dispatch a second recommendation") })
                else Text("进入节 ${opened!!.first}")
            }
        }
    }

    @Test fun freshTeachClickStartsLearning() {
        val queue = Channel<String>(Channel.UNLIMITED)
        val tutor = tutor(queue)
        val ledger = SyntheticLocalLedger()
        try {
            runComposeUiTest {
                val c = CoachController("SYN", ledger.sync, tutor, formatClock = { "" })
                mount(c, view())
                onAllNodesWithTag("exam-continue").assertCountEquals(1)
                onNodeWithTag("exam-continue").performClick()
                waitForIdle()
                val sent = frames(queue)
                onNodeWithText("进入节 a").assertExists()
                assertTrue(sent.isEmpty(), "The common session callback owns starting; the panel must not also teach: $sent")
            }
        } finally { tutor.close(); queue.cancel(); ledger.close() }
    }

    @Test fun practiceClickMustStartEvenWhenTheThreadHasHistory() {
        val queue = Channel<String>(Channel.UNLIMITED)
        val tutor = tutor(queue)
        val ledger = SyntheticLocalLedger()
        try {
            runComposeUiTest {
                val c = CoachController("SYN", ledger.sync, tutor, formatClock = { "" })
                mount(c, view(Mastery(passed = true)), paused = true)
                onNodeWithTag("exam-continue").performClick()
                waitForIdle()
                val sent = frames(queue)
                onNodeWithText("进入节 a").assertExists()
                assertEquals("Q", c.sync.mainlineState("SYN", "week:1").str("item"))
                assertEquals(1, c.sync.mainlineState("SYN", "week:1").num("choice").toInt())
                assertTrue(sent.isEmpty(), "A paused question must resume through the common callback, not generate another question: $sent")
            }
        } finally { tutor.close(); queue.cancel(); ledger.close() }
    }

    @Test fun repairClickMustStartEvenWhenTheThreadHasHistory() {
        val queue = Channel<String>(Channel.UNLIMITED)
        val tutor = tutor(queue)
        val ledger = SyntheticLocalLedger()
        try {
            runComposeUiTest {
                val c = CoachController("SYN", ledger.sync, tutor, formatClock = { "" })
                val v = view(Mastery(passed = true, needsRepair = true))
                assertEquals(KpStatus.NEEDS_REPAIR, v.kps.single().status)
                mount(c, v, paused = true)
                onNodeWithTag("exam-continue").performClick()
                waitForIdle()
                val sent = frames(queue)
                onNodeWithText("进入节 a").assertExists()
                assertTrue(sent.isEmpty(), "Repair must use the same continuation callback without starting a second tutor: $sent")
            }
        } finally { tutor.close(); queue.cancel(); ledger.close() }
    }

    @Test fun emptyRankingMustNotClaimUnlearnedPointsAreSolid() {
        val queue = Channel<String>(Channel.UNLIMITED)
        val tutor = tutor(queue)
        val ledger = SyntheticLocalLedger()
        try {
            runComposeUiTest {
                val v = view()
                assertEquals(KpStatus.NEW, v.kps.single().status)
                val c = CoachController("SYN", ledger.sync, tutor, formatClock = { "" })
                setContent { CoachTheme(false) {
                    ExamPanelCard(c, v, ExamFocus(listOf(WeekFocus(1, 0.0))), { _, _ -> }, {})
                } }
                onNodeWithText("各周都稳了，没有要补的。").assertDoesNotExist()
                onNodeWithTag("exam-continue").assertExists()
                onNodeWithText("还没会，期末分值为 0", substring = true).assertExists()
            }
        } finally { tutor.close(); queue.cancel(); ledger.close() }
    }
}
