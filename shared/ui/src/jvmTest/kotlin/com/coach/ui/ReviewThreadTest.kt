package com.coach.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.coach.client.CourseView
import com.coach.client.TutorClient
import com.coach.client.TutorModel
import com.coach.domain.Event
import com.coach.domain.json
import com.coach.domain.project
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import kotlin.test.*

/** Exercise the review button and the actual TutorClient frames against a replayed ledger. */
@OptIn(ExperimentalTestApi::class)
class ReviewThreadTest {
    private fun view(thread: String, solved: Boolean = false): CourseView {
        var seq = 0L
        fun event(type: String, payload: JsonObject, tid: String? = thread): Event {
            seq++
            return Event(seq, "e$seq", "C", type, recordedAt = seq, occurredAt = seq,
                deviceId = "d", actor = "LEARNER", threadId = tid, payload = payload)
        }
        val events = mutableListOf(
            event("outline_revised", json("ops" to JsonArray(listOf(json("op" to "add", "kpId" to "a", "title" to "知识点 A")))), null),
            event("kp_opened", json("kpId" to "a", "title" to "知识点 A")),
            event("question_posed", json("attemptId" to "a1", "questionId" to "q1", "groupId" to "g1",
                "format" to "SINGLE_CHOICE", "kpIds" to listOf("a"), "stem" to "问题",
                "options" to JsonArray(listOf(json("optionId" to "o1", "text" to "一"), json("optionId" to "o2", "text" to "二"))),
                "letterMap" to json("o1" to "A", "o2" to "B"))),
            event("answer_submitted", json("attemptId" to "a1", "answer" to "B")),
            event("attempt_graded", json("attemptId" to "a1", "score" to 0, "max" to 1,
                "status" to "EXACT", "correctAnswer" to "A", "feedback" to "错因")),
        )
        if (solved) for (i in 2..4) {
            events += event("question_posed", json("attemptId" to "a$i", "questionId" to "q$i", "groupId" to "g2",
                "format" to "SINGLE_CHOICE", "kpIds" to listOf("a"), "stem" to "新题 $i",
                "options" to JsonArray(listOf(json("optionId" to "o1", "text" to "一"), json("optionId" to "o2", "text" to "二"))),
                "letterMap" to json("o1" to "A", "o2" to "B")))
            events += event("answer_submitted", json("attemptId" to "a$i", "answer" to "A"))
            events += event("attempt_graded", json("attemptId" to "a$i", "score" to 1, "max" to 1, "status" to "EXACT"))
        }
        return CourseView(events, project(events))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> unused(clazz: Class<T>): T {
        val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return (f.get(null) as sun.misc.Unsafe).allocateInstance(clazz) as T
    }

    private fun checkReviewThread(thread: String) = runDesktopComposeUiTest(width = 460, height = 900) {
        val model = TutorModel(wallClock = { 1000L })
        val tutor = TutorClient("http://127.0.0.1:29169", "d", "test", model)
        val outgoing = Channel<String>(Channel.UNLIMITED)
        TutorClient::class.java.getDeclaredField("live").apply { isAccessible = true }.set(tutor, outgoing)
        model.onConnection(true)
        val controller = CoachController("C", unused(com.coach.client.SyncEngine::class.java), tutor, formatClock = { "" })
        try {
            var currentView by mutableStateOf(view(thread))
            setContent { CoachTheme(false) { ReviewScreen(controller, currentView, { _, _ -> }, null) } }
            repeat(2) {
                onNodeWithText("再练一组").performScrollTo().performClick()
                val open = Json.parseToJsonElement(outgoing.tryReceive().getOrThrow()).jsonObject
                val retry = Json.parseToJsonElement(outgoing.tryReceive().getOrThrow()).jsonObject
                assertEquals("tutor.open", open["t"]?.jsonPrimitive?.content)
                assertEquals("tutor.retryMistakes", retry["t"]?.jsonPrimitive?.content)
                assertEquals(thread, open["threadId"]?.jsonPrimitive?.content)
                assertEquals(thread, retry["threadId"]?.jsonPrimitive?.content)
                assertEquals("a", open["kpId"]?.jsonPrimitive?.content)
                onNodeWithText("← 返回错题").performClick()
            }
            onNodeWithText("再练一组").performScrollTo().performClick()
            outgoing.tryReceive().getOrThrow(); outgoing.tryReceive().getOrThrow()
            runOnIdle { currentView = view(thread, solved = true) }
            onNodeWithText("连续答对 3 道，这个知识点的错题已移出。").assertExists()
        } finally { tutor.close() }
    }

    @Test fun reviewReusesCanonicalThread() = checkReviewThread("kp:a")
    @Test fun reviewReusesLegacyThreadAfterReplay() = checkReviewThread("old-custom-thread")
}
