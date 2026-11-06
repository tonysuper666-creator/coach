package com.coach.ui

import androidx.compose.ui.test.*
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
    private fun view(thread: String): CourseView {
        var seq = 0L
        fun event(type: String, payload: JsonObject, tid: String? = thread): Event {
            seq++
            return Event(seq, "e$seq", "C", type, recordedAt = seq, occurredAt = seq,
                deviceId = "d", actor = "LEARNER", threadId = tid, payload = payload)
        }
        val events = listOf(
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
            setContent { CoachTheme(false) { ReviewScreen(controller, view(thread), { _, _ -> }, null) } }
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
        } finally { tutor.close() }
    }

    @Test fun reviewReusesCanonicalThread() = checkReviewThread("kp:a")
    @Test fun reviewReusesLegacyThreadAfterReplay() = checkReviewThread("old-custom-thread")
}
