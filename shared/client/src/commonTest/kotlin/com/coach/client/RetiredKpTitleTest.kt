package com.coach.client

import com.coach.domain.Event
import com.coach.domain.StudyScope
import com.coach.domain.json
import com.coach.domain.project
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 回归（TON-1164 cross_review 新发现）：把退休点从学习路线里移除，不能把它的名称一起丢掉。
 * 历史记录、错题卡、已打开的学习页仍要显示知识点名；活跃路线与周目标仍排除退休点；重新加入要恢复。
 */
class RetiredKpTitleTest {
    private var seq = 0L
    private fun event(type: String, payload: JsonObject, thread: String? = "kp:b") =
        Event(++seq, "e$seq", "C", type, recordedAt = 1_000 * seq, occurredAt = 1_000 * seq,
            deviceId = "d", actor = "LEARNER", threadId = thread, payload = payload)

    private fun outline(vararg ops: JsonObject) = event("outline_revised", json("ops" to JsonArray(ops.toList())))
    private fun add(id: String, title: String) = json("op" to "add", "kpId" to id, "title" to title)
    private fun retire(id: String) = json("op" to "retire", "kpId" to id)

    private fun choice(attempt: String, right: Boolean, kp: String = "b"): List<Event> = listOf(
        event("question_posed", json(
            "attemptId" to attempt, "questionId" to "q-$attempt", "groupId" to "g-$kp",
            "format" to "SINGLE_CHOICE", "kpIds" to listOf(kp), "purpose" to "PRACTICE",
            "fingerprint" to "fp-$attempt", "stem" to "题干 $attempt",
            "options" to JsonArray(listOf("o1", "o2").map { o -> JsonObject(mapOf("optionId" to JsonPrimitive(o), "text" to JsonPrimitive("选项 $o"))) }),
            "letterMap" to JsonObject(mapOf("o1" to JsonPrimitive("A"), "o2" to JsonPrimitive("B"))),
            "keyState" to "VERIFIED"), thread = "kp:$kp"),
        event("answer_submitted", json("attemptId" to attempt, "answer" to if (right) "A" else "B"), thread = "kp:$kp"),
        event("attempt_graded", json("attemptId" to attempt, "score" to if (right) 1 else 0, "max" to 1,
            "status" to "EXACT", "feedback" to "反馈 $attempt"), thread = "kp:$kp"),
    )

    /** 【W2】A/B/C；b 上先连对三道再答错一道（历史记录 + 未清错题都在 b 上）。 */
    private fun learnedThenRetired(): List<Event> =
        listOf(outline(add("a", "【W2】A"), add("b", "【W2】B"), add("c", "【W2】C"))) +
            choice("b1", true) + choice("b2", true) + choice("b3", true) + choice("b4", false) +
            listOf(outline(retire("b")))

    private fun viewOf(events: List<Event>) = CourseView(events, project(events))

    @Test fun retiredPointKeepsItsNameInHistoryButLeavesTheActiveRoute() {
        val events = learnedThenRetired()
        val view = viewOf(events)

        assertEquals(listOf("a", "c"), view.kps.map { it.id }, "活跃路线仍要排除退休点")
        assertNull(view.kp("b"), "活跃查询不认识退休点")
        assertEquals("【W2】B", view.kpAny("b")?.title, "全量查询仍要给出退休点的名称")

        // 错题卡：错题是历史，必须显示知识点名而不是原始 id。
        val mistake = assertNotNull(view.mistakes.items.singleOrNull { it.kpId == "b" }, "退休前答错的题仍留在错题里")
        assertEquals("【W2】B", view.kpAny(mistake.kpId)?.title ?: mistake.kpId)

        // 记录页：掌握变化的历史同样不能退回原始 id。
        val changes = RecordsView(events).changes.filter { it.kpId == "b" }
        assertTrue(changes.isNotEmpty(), "b 上要有历史掌握变化")
        assertTrue(changes.all { view.kpAny(it.kpId)?.title == "【W2】B" })

        // 已打开的学习页：退休后仍取得到名称和锚点信息，界面不会变空。
        assertNotNull(view.kpAny("b"), "已打开的知识点不能因为退休而消失")
    }

    @Test fun weekTargetAndContinueSkipRetiredPointsEvenWhenTheScopeStillListsThem() {
        val events = learnedThenRetired()
        val scoped = project(events).copy(studyScope = StudyScope("s1", 2, listOf("a", "b", "c"), cursor = 1))
        val view = CourseView(events, scoped)

        assertEquals("c", view.weekTarget?.id, "周目标必须跳过退休的 b")
        assertEquals("c", view.continueTarget?.id)
        assertTrue("b" !in view.kps.map { it.id }, "退休点不回队列")
    }

    @Test fun reAddedPointReturnsToTheRouteWithItsName() {
        val events = learnedThenRetired() + outline(add("b", "【W2】B2"))
        val view = viewOf(events)

        assertEquals(listOf("a", "b", "c"), view.kps.map { it.id }, "重新加入的点回到活跃路线")
        assertEquals("【W2】B2", view.kp("b")?.title)
        assertEquals("【W2】B2", view.kpAny("b")?.title, "重新加入后全量名称同步更新")
        assertEquals(setOf("a", "b", "c"), view.weekPoints.map { it.id }.toSet(), "重新加入后回到周队列")
    }
}
