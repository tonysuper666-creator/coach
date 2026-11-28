package com.coach.desktop.books

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.LocalDate

internal data class ExamDailyHomeView(
    val courseId: String,
    val courseTitle: String,
    val tasks: TodayExamTasks,
    val titles: Map<String, String>,
)

/** Local daily budget and the points pinned into the next few days. Syncs as kind exam_daily. */
internal object ExamDailyStateStore {
    /** One cap shared by every course. Not a course id. */
    const val SHARED_BUDGET_ID = "daily-budget"
    private val json = Json { ignoreUnknownKeys = true }

    fun ensure(db: Connection) {
        db.createStatement().use { sql ->
            sql.execute(
                """CREATE TABLE IF NOT EXISTS exam_daily_state(
                    course_id TEXT PRIMARY KEY,
                    daily_minutes INTEGER NOT NULL,
                    pins_json TEXT NOT NULL DEFAULT '[]',
                    updated_at INTEGER NOT NULL
                )""",
            )
        }
    }

    fun read(db: Connection, courseId: String): Pair<Int?, List<ExamTaskPin>> {
        ensure(db)
        return db.prepareStatement(
            "SELECT daily_minutes, pins_json FROM exam_daily_state WHERE course_id=?",
        ).use { sql ->
            sql.setString(1, courseId)
            sql.executeQuery().use { rows ->
                if (!rows.next()) null to emptyList()
                else rows.getInt(1) to decodePins(rows.getString(2))
            }
        }
    }

    fun writeMinutes(db: Connection, courseId: String, minutes: Int) {
        ensure(db)
        val now = System.currentTimeMillis()
        db.prepareStatement(
            """INSERT INTO exam_daily_state(course_id, daily_minutes, pins_json, updated_at)
               VALUES(?, ?, '[]', ?)
               ON CONFLICT(course_id) DO UPDATE SET
                   daily_minutes=excluded.daily_minutes,
                   updated_at=excluded.updated_at""",
        ).use { sql ->
            sql.setString(1, courseId)
            sql.setInt(2, minutes.coerceIn(15, 240))
            sql.setLong(3, now)
            sql.executeUpdate()
        }
    }

    fun mergePins(db: Connection, courseId: String, pins: List<ExamTaskPin>) {
        if (pins.isEmpty()) return
        ensure(db)
        val (minutes, existing) = read(db, courseId)
        val incoming = pins.map { it.pointId }.toSet()
        val merged = existing.filter { it.pointId !in incoming } + pins
        val now = System.currentTimeMillis()
        db.prepareStatement(
            """INSERT INTO exam_daily_state(course_id, daily_minutes, pins_json, updated_at)
               VALUES(?, ?, ?, ?)
               ON CONFLICT(course_id) DO UPDATE SET
                   pins_json=excluded.pins_json,
                   updated_at=excluded.updated_at""",
        ).use { sql ->
            sql.setString(1, courseId)
            // No saved budget yet: same 30-minute first-run default as the shared daily card.
            sql.setInt(2, (minutes ?: 30).coerceIn(15, 240))
            sql.setString(3, encodePins(merged))
            sql.setLong(4, now)
            sql.executeUpdate()
        }
    }

    fun encodePins(pins: List<ExamTaskPin>): String = buildJsonArray {
        for (pin in pins) {
            if (pin.pointId.isBlank()) continue
            add(buildJsonObject {
                put("pointId", pin.pointId)
                put("date", pin.date.toString())
            })
        }
    }.toString()

    fun decodePins(raw: String?): List<ExamTaskPin> {
        val array = runCatching { json.parseToJsonElement(raw ?: "[]") as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val id = (row["pointId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse((row["date"] as? JsonPrimitive)?.contentOrNull) }.getOrNull()
                ?: return@mapNotNull null
            ExamTaskPin(id, date)
        }
    }
}

internal data class ExamDayBoard(
    val budgetMinutes: Int,
    val days: List<MultiCourseDay>,
) {
    val today: MultiCourseDay get() = days.first()
}

/** Home countdown for the nearest course exam, sized from what is still unmastered. */
internal object ExamDailyHome {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(library: TextbookLibrary, today: LocalDate = LocalDate.now()): ExamDailyHomeView? =
        library.withCourseDatabase { db ->
            val course = pickCourse(db, today) ?: return@withCourseDatabase null
            val points = loadPoints(db, course.id)
            val (savedMinutes, pins) = ExamDailyStateStore.read(db, course.id)
            val budget = savedMinutes ?: preferenceMinutes(db, course.id) ?: 90
            val tasks = ExamDailyTaskPlanner.plan(today, course.exam, points, budget, pins)
            ExamDailyHomeView(course.id, course.title, tasks, points.associate { it.id to it.title })
        }

    fun examDate(library: TextbookLibrary, courseId: String): LocalDate? =
        library.withCourseDatabase { db ->
            db.prepareStatement("SELECT exam_date FROM collections WHERE id=?").use { sql ->
                sql.setString(1, courseId)
                sql.executeQuery().use { rows ->
                    if (!rows.next()) null else ExamCountdownPlanner.parseExamDate(rows.getString(1))
                }
            }
        }

    fun setBudget(library: TextbookLibrary, courseId: String, minutes: Int) {
        library.withCourseDatabase { db -> ExamDailyStateStore.writeMinutes(db, courseId, minutes) }
    }

    /** Several courses share one daily cap. The next five days assume unfinished work is spread, not piled. */
    fun loadDay(library: TextbookLibrary, today: LocalDate = LocalDate.now()): ExamDayBoard? =
        library.withCourseDatabase { db ->
            val courses = listCourses(db)
            if (courses.isEmpty()) return@withCourseDatabase null
            val inputs = courses.map { course ->
                val (_, pins) = ExamDailyStateStore.read(db, course.id)
                CourseDayInput(course.id, course.title, course.exam, loadPoints(db, course.id), pins)
            }
            // 第一次用 30 分钟；之后沿用上次选的时长。选完立刻按它排。
            val budget = ExamDailyStateStore.read(db, ExamDailyStateStore.SHARED_BUDGET_ID).first ?: 30
            ExamDayBoard(budget, MultiCourseDailyPlanner.preview(today, inputs, budget, days = 5))
        }

    fun setSharedBudget(library: TextbookLibrary, minutes: Int) {
        library.withCourseDatabase { db ->
            ExamDailyStateStore.writeMinutes(db, ExamDailyStateStore.SHARED_BUDGET_ID, minutes)
        }
    }

    fun schedulePins(
        library: TextbookLibrary,
        courseId: String,
        today: LocalDate,
        daysRemaining: Int?,
        pointIds: List<String>,
    ) {
        val pins = ExamBankMock.schedulePins(today, daysRemaining, pointIds)
        library.withCourseDatabase { db -> ExamDailyStateStore.mergePins(db, courseId, pins) }
    }

    private data class DatedCourse(val id: String, val title: String, val exam: LocalDate)

    private fun pickCourse(db: Connection, today: LocalDate): DatedCourse? {
        val courses = listCourses(db)
        return courses.filter { !it.exam.isBefore(today) }.minByOrNull { it.exam }
            ?: courses.maxByOrNull { it.exam }
    }

    private fun listCourses(db: Connection): List<DatedCourse> =
        db.prepareStatement(
            """SELECT id, title, exam_date FROM collections
               WHERE kind='COURSE' AND IFNULL(archived,0)=0
                 AND exam_date IS NOT NULL AND exam_date<>''
               ORDER BY exam_date, id""",
        ).use { sql ->
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val exam = ExamCountdownPlanner.parseExamDate(rows.getString(3)) ?: continue
                        add(DatedCourse(rows.getString(1), rows.getString(2).orEmpty(), exam))
                    }
                }
            }
        }

    private fun loadPoints(db: Connection, courseId: String): List<ExamPointWork> {
        val mastery = loadMastery(db, courseId)
        val wrongLessons = loadWrongLessons(db, courseId)
        return db.prepareStatement(
            """SELECT topic_id, title, pack_payload_json FROM course_topics
               WHERE collection_id=? AND exam_status='EXAMINABLE'
               ORDER BY ord, topic_id""",
        ).use { sql ->
            sql.setString(1, courseId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val id = rows.getString(1)
                        val title = rows.getString(2).orEmpty()
                        val records = mastery.filter { it.tocNodeId == id || it.conceptKey == id }
                        val average = if (records.isEmpty()) 0.0 else records.map { it.pMastery }.average()
                        val tagged = records.count { it.error }
                        val reviews = wrongLessons.count { lessonMatches(it, id) }
                        val pack = rows.getString(3)
                        val bank = ExamBankMock.bankCount(id, title, pack)
                        add(
                            ExamPointWork(
                                id = id,
                                title = title,
                                mastered = ExamDailyTaskPlanner.mastered(average, records.isNotEmpty()),
                                wrongQuestions = maxOf(reviews, tagged),
                                bankQuestions = bank,
                                questionGap = questionGap(pack, bank),
                            ),
                        )
                    }
                }
            }
        }
    }

    private data class MasteryHit(
        val tocNodeId: String,
        val conceptKey: String,
        val pMastery: Double,
        val error: Boolean,
    )

    private fun loadMastery(db: Connection, courseId: String): List<MasteryHit> =
        db.prepareStatement(
            """SELECT toc_node_id, concept_key, p_mastery, dominant_error_tag
               FROM concept_mastery_ledger WHERE collection_id=?""",
        ).use { sql ->
            sql.setString(1, courseId)
            sql.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val tag = rows.getString(4).orEmpty()
                        add(
                            MasteryHit(
                                tocNodeId = rows.getString(1).orEmpty(),
                                conceptKey = rows.getString(2).orEmpty(),
                                pMastery = rows.getDouble(3),
                                error = tag.isNotBlank() && !tag.equals("NONE", ignoreCase = true),
                            ),
                        )
                    }
                }
            }
        }

    private fun loadWrongLessons(db: Connection, courseId: String): List<String> =
        db.prepareStatement(
            """SELECT r.lesson FROM book_reviews r
               JOIN collection_materials m ON m.material_id = r.book
               WHERE m.collection_id=? AND (r.understood IS NULL OR r.understood=0)""",
        ).use { sql ->
            sql.setString(1, courseId)
            sql.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1).orEmpty()) } }
        }

    private fun lessonMatches(lesson: String, topicId: String): Boolean {
        if (topicId.isBlank() || lesson.isBlank()) return false
        if (lesson == topicId || lesson.substringBefore('#') == topicId) return true
        return topicId in lesson
    }

    private fun questionGap(packJson: String?, bankQuestions: Int): QuestionGap {
        if (bankQuestions > 0) return QuestionGap.NONE
        if (packJson.isNullOrBlank()) return QuestionGap.NEVER_GENERATED
        val root = runCatching { json.parseToJsonElement(packJson) as? JsonObject }.getOrNull()
            ?: return QuestionGap.NEVER_GENERATED
        val drill = (root["drill"] as? JsonObject)?.let { ExamPointDrillLocal.decode(it) }
            ?: return QuestionGap.NEVER_GENERATED
        val attempted = drill.checked > 0 || drill.discards.isNotEmpty() || !drill.emptyReason.isNullOrBlank()
        return if (attempted) QuestionGap.DROPPED else QuestionGap.NEVER_GENERATED
    }

    private fun preferenceMinutes(db: Connection, courseId: String): Int? {
        val exists = db.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='book_study_preferences'",
        ).use { sql -> sql.executeQuery().use { it.next() } }
        if (!exists) return null
        return db.prepareStatement(
            """SELECT MIN(p.daily_minutes) FROM book_study_preferences p
               JOIN collection_materials m ON m.material_id = p.book_id
               WHERE m.collection_id=? AND p.daily_minutes IS NOT NULL""",
        ).use { sql ->
            sql.setString(1, courseId)
            sql.executeQuery().use { rows ->
                if (!rows.next()) null else rows.getInt(1).takeIf { !rows.wasNull() && it > 0 }
            }
        }
    }
}
