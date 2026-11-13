package com.coach.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.coach.client.CourseView
import com.coach.client.Mistake
import com.coach.client.QuizCard
import com.coach.domain.Event
import com.coach.domain.json
import com.coach.domain.project
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 回归（TON-1164 cross_review 新发现）：退休点上的历史在界面上仍要显示知识点名。
 * 这里用真实的 [CourseView] 喂给界面用的取名函数，并用错题卡渲染确认不会露出原始 id。
 */
@OptIn(ExperimentalTestApi::class)
class RetiredKpTitleUiTest {
    private var seq = 0L
    private fun event(type: String, payload: JsonObject, thread: String? = "kp:b") =
        Event(++seq, "e$seq", "C", type, recordedAt = 1_000 * seq, occurredAt = 1_000 * seq,
            deviceId = "d", actor = "LEARNER", threadId = thread, payload = payload)

    private fun outline(vararg ops: JsonObject) = event("outline_revised", json("ops" to JsonArray(ops.toList())))
    private fun add(id: String, title: String) = json("op" to "add", "kpId" to id, "title" to title)
    private fun retire(id: String) = json("op" to "retire", "kpId" to id)

    private fun choice(attempt: String, right: Boolean): List<Event> = listOf(
        event("question_posed", json(
            "attemptId" to attempt, "questionId" to "q-$attempt", "groupId" to "g-b",
            "format" to "SINGLE_CHOICE", "kpIds" to listOf("b"), "purpose" to "PRACTICE",
            "fingerprint" to "fp-$attempt", "stem" to "题干 $attempt",
            "options" to JsonArray(listOf("o1", "o2").map { o -> JsonObject(mapOf("optionId" to JsonPrimitive(o), "text" to JsonPrimitive("选项 $o"))) }),
            "letterMap" to JsonObject(mapOf("o1" to JsonPrimitive("A"), "o2" to JsonPrimitive("B"))),
            "keyState" to "VERIFIED")),
        event("answer_submitted", json("attemptId" to attempt, "answer" to if (right) "A" else "B")),
        event("attempt_graded", json("attemptId" to attempt, "score" to if (right) 1 else 0, "max" to 1,
            "status" to "EXACT", "feedback" to "反馈 $attempt")),
    )

    /** b 连对三道后答错一道，再把 b 退休；历史（错题、记录）都留在 b 上。 */
    private fun view(): CourseView {
        val events = listOf(outline(add("a", "【W2】A"), add("b", "【W2】B"), add("c", "【W2】C"))) +
            choice("b1", true) + choice("b2", true) + choice("b3", true) + choice("b4", false) +
            listOf(outline(retire("b")))
        return CourseView(events, project(events))
    }

    @Test fun mistakeAndRecordTitlesSurviveRetirement() {
        val v = view()
        assertEquals(null, v.kp("b"), "活跃查询仍排除退休点")
        assertEquals("【W2】B", mistakeTitle(v, "b"), "错题卡要显示知识点名")
        assertEquals("B", changeTitle(v, "b"), "记录页去掉周前缀后仍显示名称，而不是原始 id")
    }

    @Test fun mistakeCardRendersTheRetiredPointName() = runDesktopComposeUiTest(width = 360, height = 780) {
        val v = view()
        val mistake = Mistake("b4", "b",
            QuizCard("b4", "题干 b4", listOf("A" to "选项 o1", "B" to "选项 o2"), false, "B"),
            "A", "判分依据")
        setContent {
            CoachTheme(false) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    MistakeCard(mistake, mistakeTitle(v, mistake.kpId), MathAssets(), true) {}
                }
            }
        }
        onNodeWithText("【W2】B").performScrollTo().assertIsDisplayed()
    }
}
