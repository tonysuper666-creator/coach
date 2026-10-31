package com.coach.client

import com.coach.domain.Event
import com.coach.domain.Projection
import com.coach.domain.json
import com.coach.domain.project
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CourseViewTest {
    private var seq = 0L
    private fun event(type: String, payload: JsonObject, thread: String? = null) =
        Event(++seq, "e$seq", "C", type, recordedAt = seq, occurredAt = seq, deviceId = "d", actor = "SYSTEM", threadId = thread, payload = payload)

    private fun outline(vararg kps: Pair<String, String>) = event("outline_revised", json("ops" to JsonArray(kps.map {
        JsonObject(mapOf("op" to JsonPrimitive("add"), "kpId" to JsonPrimitive(it.first), "title" to JsonPrimitive(it.second)))
    })))

    private fun posed(attempt: String, group: String, letters: Map<String, String>, untaught: Boolean = false) = event(
        "question_posed",
        json(
            "attemptId" to attempt, "questionId" to "q-$attempt", "groupId" to group, "format" to "SINGLE_CHOICE", "kpIds" to listOf("K1"),
            "stem" to "题干 $attempt", "untaught" to untaught,
            "options" to JsonArray(letters.keys.map { JsonObject(mapOf("optionId" to JsonPrimitive(it), "text" to JsonPrimitive("选项 $it"))) }),
            "letterMap" to JsonObject(letters.mapValues { JsonPrimitive(it.value) }),
        ),
        thread = "kp:K1",
    )

    private fun view(events: List<Event>): CourseView = CourseView(events, project(events))

    @Test fun visibleOrderSurvivesSortedProjectionMaps() {
        val events=listOf(outline("K2" to "First topic", "K1" to "Second topic"))
        val sorted=project(events).let { it.copy(mastery=it.mastery.toSortedMap()) }
        assertEquals(listOf("First topic", "Second topic"),CourseView(events,sorted).kps.map { it.title })
    }

    @Test fun kpListKeepsOutlineOrderAndNames() {
        val v = view(listOf(outline("K1" to "信息不对称", "K2" to "逆向选择")))
        assertEquals(listOf("信息不对称", "逆向选择"), v.kps.map { it.title })
        assertTrue(v.kps.all { it.status == KpStatus.NEW })
        assertEquals("K1", v.continueTarget?.id)
    }

    @Test fun cardsShowTheServerAssignedLettersInOrder() {
        val v = view(listOf(outline("K1" to "A"), posed("a1", "g1", mapOf("o1" to "C", "o2" to "A", "o3" to "B", "o4" to "D"), untaught = true)))
        val card = v.quizGroups("kp:K1").single().cards.single()
        assertEquals(listOf("A", "B", "C", "D"), card.options.map { it.first })
        assertEquals("选项 o2", card.options.first().second)
        assertTrue(card.untaught)
        assertNull(card.answer)
        assertEquals("g1", v.pendingGroup("kp:K1")?.groupId)
    }

    @Test fun submittedAndGradedCardsShowPerQuestionResult() {
        val letters = mapOf("o1" to "A", "o2" to "B")
        val events = listOf(
            outline("K1" to "A"), posed("a1", "g1", letters), posed("a2", "g1", letters),
            event("answer_submitted", json("attemptId" to "a1", "answer" to "a")),
            event("answer_submitted", json("attemptId" to "a2", "answer" to "B")),
            event("attempt_graded", json("attemptId" to "a1", "score" to 1, "max" to 1, "status" to "EXACT", "feedback" to "对")),
            event("attempt_graded", json("attemptId" to "a2", "score" to 0, "max" to 1, "status" to "EXACT", "feedback" to "错")),
        )
        val group = view(events).quizGroups("kp:K1").single()
        assertTrue(group.submitted)
        assertEquals(listOf("A", "B"), group.cards.map { it.answer })
        assertEquals(listOf(true, false), group.cards.map { it.grade?.correct })
        assertNull(view(events).pendingGroup("kp:K1"))
    }

    private val now = 1_793_448_000_000L // 2026-10-31 12:00 UTC，伦敦逻辑日内

    @Test fun dueReviewsListOverdueFirstAndSkipFutureOnes() {
        val events = listOf(outline("K1" to "甲", "K2" to "乙", "K3" to "丙"))
        val today = com.coach.domain.logicalDay(now, "Europe/London")
        val projection = project(events).copy(review = mapOf(
            "K1" to com.coach.domain.Review(dueDay = today),
            "K2" to com.coach.domain.Review(dueDay = today - 3),
            "K3" to com.coach.domain.Review(dueDay = today + 2),
        ))
        val due = CourseView(events, projection).dueReviews(now)
        assertEquals(listOf("K2", "K1"), due.map { it.kp.id })
        assertEquals(listOf(3, 0), due.map { it.overdueDays })
    }

    @Test fun examCountdownOnlyWhenAnExamDayIsSetAndNotPast() {
        val today = com.coach.domain.logicalDay(now, "Europe/London")
        val none = view(listOf(outline("K1" to "甲")))
        assertNull(none.daysToExam(now))
        val soon = view(listOf(outline("K1" to "甲"), event("exam_set", json("examDay" to today + 5))))
        assertEquals(5, soon.daysToExam(now))
        val past = view(listOf(outline("K1" to "甲"), event("exam_set", json("examDay" to today - 1))))
        assertNull(past.daysToExam(now))
    }

    @Test fun otherThreadsDoNotLeakIntoThisKp() {
        val v = view(listOf(outline("K1" to "A"), posed("a1", "g1", mapOf("o1" to "A", "o2" to "B"))))
        assertTrue(v.quizGroups("kp:K2").isEmpty())
    }

    @Test fun courseContentGivesModuleNamesPrerequisitesAndTheReferencePage() {
        val kp = { id: String, name: String, prereqs: List<String> -> JsonObject(mapOf(
            "id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "moduleId" to JsonPrimitive("m1"), "prerequisiteIds" to JsonArray(prereqs.map { JsonPrimitive(it) }),
            "sourceAnchor" to JsonObject(mapOf("docId" to JsonPrimitive("textbook:7"), "pdfPage" to JsonPrimitive(172), "bookPage" to JsonPrimitive(141))))) }
        val view = view(listOf(event("outline_revised", json(
            "modules" to JsonArray(listOf(json("id" to "m1", "name" to "第二章 逆向选择"))),
            "knowledgePoints" to JsonArray(listOf(kp("K1", "【W4·Ch8】基础", emptyList()), kp("K2", "【W4·Ch8】进阶", listOf("K1"))))))))
        val k2 = view.kp("K2")!!
        assertEquals("第二章 逆向选择", k2.module)
        assertEquals(listOf("K1"), k2.prereqs)
        assertEquals("textbook:7", k2.anchorDoc); assertEquals(172, k2.anchorPdfPage); assertEquals(141, k2.anchorBookPage)
        assertEquals("教材 p.141", k2.reference)
    }

    @Test fun openSourceUsesTheFirstOpenableAnchorsEntryThenSourceAnchor() {
        val anchor = { doc: String, pdf: Int, book: Int -> json("docId" to doc, "pdfPage" to pdf, "bookPage" to book) }
        fun kp(id: String, anchors: JsonArray?, source: JsonObject?) = JsonObject(buildMap {
            put("id", JsonPrimitive(id)); put("name", JsonPrimitive(id))
            if (anchors != null) put("anchors", anchors)
            if (source != null) put("sourceAnchor", source)
        })
        val view = view(listOf(event("outline_revised", json("knowledgePoints" to JsonArray(listOf(
            // anchors[] wins over sourceAnchor, and an entry without a page or document is skipped.
            kp("A", JsonArray(listOf(anchor("textbook:1", 0, 0), anchor("textbook:2", 40, 12), anchor("textbook:2", 41, 13))), anchor("textbook:9", 99, 98)),
            kp("B", null, anchor("textbook:9", 99, 98)),
            kp("C", JsonArray(emptyList()), null),
            kp("D", JsonArray(listOf(anchor("", 5, 1))), anchor("textbook:3", 7, 2)),
        ))))))
        assertEquals(Triple("textbook:2", 40, 12), view.kp("A")!!.let { Triple(it.anchorDoc, it.anchorPdfPage, it.anchorBookPage) })
        assertEquals("textbook:9", view.kp("B")!!.anchorDoc); assertEquals(99, view.kp("B")!!.anchorPdfPage)
        assertEquals("", view.kp("C")!!.anchorDoc)
        assertEquals("textbook:3", view.kp("D")!!.anchorDoc); assertEquals(7, view.kp("D")!!.anchorPdfPage)
    }
}
