package com.coach.desktop.books

import com.coach.desktop.books.exam.LocalExamController
import com.coach.desktop.books.exam.LocalExamPaper
import com.coach.desktop.books.exam.LocalExamStore
import com.coach.domain.flag
import com.coach.domain.json
import com.coach.domain.num
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExamBankMockTest {
    private val quote = "Photosynthesis is the process by which plants use light to make sugars."

    private fun usable(id: String, topic: String, past: Boolean = false) = BankQuestion(
        id = id,
        topicId = topic,
        topicTitle = topic,
        type = "mcq",
        stem = "Which resource supplies energy?",
        options = listOf("Light", "Rock"),
        answer = 0,
        marks = 2,
        bookId = "book",
        page = 1,
        quote = quote,
        pastOriginal = past,
    )

    @Test fun blueprintReadsCountedTypes() {
        assertEquals(
            listOf("mcq" to 20, "essay" to 2),
            ExamBankMock.blueprint("20道单选+2道论述", 120),
        )
    }

    @Test fun pastPaperOriginalsAreLeftOut() {
        val paper = ExamBankMock.assemble(
            listOf(usable("kept", "t"), usable("dropped", "t", past = true)),
            listOf("t"),
            "1道单选",
            30,
        )
        assertEquals(listOf("kept"), paper.items.map { it.id })
        val empty = ExamBankMock.assemble(listOf(usable("dropped", "t", past = true)), listOf("t"), "1道单选", 30)
        assertTrue(empty.items.isEmpty())
        assertEquals(listOf("t"), empty.pointsWithoutQuestions)
    }

    @Test fun packReaderSkipsPastOriginalsAndBlankCitations() {
        val raw = """
            {"drill":{"choices":[
              {"id":"ok","stem":"What do plants use?","options":[
                {"letter":"A","text":"Light","correct":true,"citation":{"bookId":"book","page":1,"quote":"$quote"}},
                {"letter":"B","text":"Rock","correct":false,"citation":{"bookId":"book","page":1,"quote":"$quote"}}
              ]},
              {"id":"old","stem":"Old paper","pastOriginal":true,"options":[
                {"letter":"A","text":"A","correct":true,"citation":{"bookId":"book","page":1,"quote":"$quote"}},
                {"letter":"B","text":"B","correct":false,"citation":{"bookId":"book","page":1,"quote":"$quote"}}
              ]}
            ],"essay":{"thesis":"Light makes sugars.","paragraphs":[{"claim":"Light supplies energy.","citation":{"bookId":"book","page":1,"quote":"$quote"}}]}}}
        """.trimIndent()
        val read = ExamBankMock.readPack("topic-a", "Light", raw)
        assertEquals(listOf("topic-a-ok", "topic-a-essay"), read.filter { it.usableCopy() }.map { it.id })
        assertTrue(read.single { it.id == "topic-a-old" }.pastOriginal)
    }

    @Test fun pendingEssaysAreNotRankedAndLossesScheduleForward() {
        val paper = json(
            "sections" to kotlinx.serialization.json.JsonArray(listOf(json(
                "items" to kotlinx.serialization.json.JsonArray(listOf(
                    json("id" to "a-1", "topicId" to "a", "weekLabel" to "Light"),
                    json("id" to "b-1", "topicId" to "b", "weekLabel" to "Cells"),
                    json("id" to "a-essay", "topicId" to "a", "weekLabel" to "Light"),
                )),
            ))),
        )
        val result = json(
            "items" to kotlinx.serialization.json.JsonArray(listOf(
                json("key" to "a-1", "status" to "EXACT", "score" to 0, "max" to 200),
                json("key" to "b-1", "status" to "EXACT", "score" to 200, "max" to 200),
                json("key" to "a-essay", "status" to "PENDING_REVIEW", "score" to null, "max" to 1000),
            )),
        )
        val scores = ExamBankMock.scorePaper(paper, result)
        val light = scores.first { it.topicId == "a" }
        assertEquals(1, light.pending)
        assertEquals(200, light.lost)
        assertEquals(listOf("a"), ExamBankMock.topLosses(scores).map { it.topicId })
        val today = LocalDate.of(2026, 11, 26)
        val pins = ExamBankMock.schedulePins(today, 14, listOf("a", "b", "c", "d"))
        assertEquals(listOf("a", "b", "c"), pins.map { it.pointId })
        assertEquals(today.plusDays(1), pins[0].date)
        assertEquals(today.plusDays(2), pins[1].date)
        assertEquals(today.plusDays(3), pins[2].date)
        assertEquals(today, ExamBankMock.schedulePins(today, 0, listOf("a")).single().date)
    }

    @Test fun bankPaperFreezesAutoSubmitsAndPinsTheNextDay() {
        val root = Files.createTempDirectory("exam-bank-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("source")))
        val page = book.pages.first { quote in it.text }
        val today = LocalDate.of(2026, 11, 26)
        val exam = today.plusDays(14)
        val courseId = library.withCourseDatabase { db ->
            CourseStore.createCourse(
                db,
                "Biology",
                CourseExamProfile(
                    examDate = exam.toString(),
                    examFormat = "2道单选+1道论述",
                    durationMinutes = 30,
                    examEnabled = true,
                ),
            ).first
        }
        val pack = """
            {"drill":{"choices":[{"id":"c1","stem":"What do plants use to make sugars?","options":[
              {"letter":"A","text":"Light","correct":true,"citation":{"bookId":"${book.id}","page":${page.number},"quote":"$quote"}},
              {"letter":"B","text":"Rock","correct":false,"citation":{"bookId":"${book.id}","page":${page.number},"quote":"$quote"}}
            ]}],"essay":{"thesis":"Plants make sugars with light.","paragraphs":[
              {"claim":"Light supplies the energy.","citation":{"bookId":"${book.id}","page":${page.number},"quote":"$quote"}}
            ]}}}
        """.trimIndent()
        library.withCourseDatabase { db ->
            db.prepareStatement(
                """INSERT INTO course_topics(topic_id, collection_id, week_label, ord, title, exam_status, user_locked, exam_weight, pack_payload_json, practice_draft_json, mastery_state, updated_at)
                   VALUES(?,?,?,?,?,'EXAMINABLE',0,1.0,?,'{}','UNSEEN',?)""",
            ).use { sql ->
                sql.setString(1, "topic-b"); sql.setString(2, courseId); sql.setString(3, "第 1 周")
                sql.setInt(4, 1); sql.setString(5, "没题"); sql.setString(6, null); sql.setLong(7, 1); sql.executeUpdate()
                sql.setString(1, "topic-a"); sql.setString(3, "第 2 周"); sql.setInt(4, 2)
                sql.setString(5, "Light"); sql.setString(6, pack); sql.executeUpdate()
            }
        }
        val detail = CourseDetailSnapshot(
            courseId = courseId,
            title = "Biology",
            examProfile = CourseExamProfile(
                examDate = exam.toString(),
                examFormat = "2道单选+1道论述",
                durationMinutes = 30,
                examEnabled = true,
            ),
            materials = emptyList(),
            examinableTopics = listOf(
                topic(courseId, "topic-b", 1, "没题"),
                topic(courseId, "topic-a", 2, "Light"),
            ),
            pendingTopics = emptyList(),
            excludedTopics = emptyList(),
        )
        val assembled = library.withCourseDatabase { ExamBankMock.assemble(it, detail) }
        assertEquals(listOf("topic-a", "topic-a"), assembled.items.map { it.topicId })
        assertTrue(assembled.items.any { it.type == "mcq" })
        assertTrue(assembled.items.any { it.type == "essay" })
        assertEquals(listOf("topic-b"), assembled.pointsWithoutQuestions)
        val scope = LocalExamController.courseMockScopeId(courseId)
        var now = 1_700_000_000_000L
        val store = LocalExamStore(library) { now }
        val paper = LocalExamPaper(ExamBankMock.toPaper(scope, detail, assembled))
        store.register(paper)
        val opened = store.start(scope, paper.id, 30)
        val attempt = opened.str("mockId")
        val answers = buildJsonObject {
            put(assembled.items.first { it.type == "mcq" }.id, buildJsonObject { put("choice", 1) })
            put(assembled.items.first { it.type == "essay" }.id, buildJsonObject { put("text", "Plants use light.") })
        }
        assertTrue(store.save(scope, attempt, answers, 1).flag("saved"))
        now = opened.num("deadline") + LocalExamStore.GRACE_MILLIS + 1
        val closed = store.get(scope, attempt)
        assertEquals("submitted", closed.str("status"))
        assertTrue(closed.flag("autoSubmitted"))
        val scores = ExamBankMock.scorePaper(closed.getValue("paper").jsonObject, closed.getValue("result").jsonObject)
        val light = scores.first { it.topicId == "topic-a" }
        assertEquals(1, light.pending)
        assertEquals(200, light.lost)
        assertEquals(listOf("topic-a"), ExamBankMock.topLosses(scores).map { it.topicId })
        ExamDailyHome.schedulePins(library, courseId, today, 14, listOf("topic-a"))
        val next = ExamDailyHome.load(library, today.plusDays(1))
        assertEquals(courseId, next?.courseId)
        assertEquals(13, next?.tasks?.daysRemaining)
        assertEquals("topic-a", next?.tasks?.newPointIds?.first())
        assertFalse("topic-a" in next!!.tasks.pointsWithoutQuestions)
        assertTrue("topic-b" !in next.tasks.newPointIds || "topic-b" in next.tasks.pointsWithoutQuestions)
    }

    private fun topic(courseId: String, id: String, ord: Int, title: String) = CourseTopicEntry(
        topicId = id,
        collectionId = courseId,
        weekLabel = "第 $ord 周",
        weekNum = ord,
        ord = ord,
        title = title,
        examStatus = CourseTopicStatus.EXAMINABLE,
        userLocked = false,
    )

    private fun BankQuestion.usableCopy(): Boolean =
        !pastOriginal && stem.isNotBlank() && quote.isNotBlank() && bookId.isNotBlank() && page > 0 &&
            (type != "mcq" || (options.size >= 2 && answer != null))

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()
}
