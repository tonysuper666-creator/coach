package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

internal enum class ExamTechnique(val label: String) {
    CALCULATION("计算"), COMPARISON("比较"), APPLICATION("应用"), EXPLANATION("解释"), RECALL("回忆"),
}

internal data class PastPaperFinding(val topicId: String, val technique: ExamTechnique)

/** Independent intake. The reader has no library import, search index, upload or generation dependency. */
internal object CoursePastPaper {
    private val questionStart = Regex("""(?m)^\s*(?=(?:(?:Question|Q)\s*\d+\b|\d+[.)、]\s*|第\s*\d+\s*题))""", RegexOption.IGNORE_CASE)
    private val techniques = listOf(
        ExamTechnique.CALCULATION to Regex("""\b(?:calculate|compute|estimate|derive)\b|计算|求出|推导"""),
        ExamTechnique.COMPARISON to Regex("""\b(?:compare|contrast|distinguish)\b|比较|对比|区分"""),
        ExamTechnique.APPLICATION to Regex("""\b(?:apply|predict|illustrate)\b|应用|预测|举例"""),
        ExamTechnique.EXPLANATION to Regex("""\b(?:explain|justify|discuss)\b|解释|说明|论述"""),
    )

    private fun extract(text: String, patterns: Map<String, Regex>): Set<PastPaperFinding> {
        return buildSet {
            for (block in text.split(questionStart)) {
                val normalized = CourseExamPoints.normalize(block)
                val technique = techniques.firstOrNull { it.second.containsMatchIn(normalized) }?.first ?: ExamTechnique.RECALL
                for ((id, pattern) in patterns) {
                    if (pattern.containsMatchIn(normalized)) add(PastPaperFinding(id, technique))
                }
            }
        }
    }

    val supportedExtensions = setOf("pdf", "txt", "md")

    /** Content hash of the paper file; the only thing kept about the paper besides its techniques. */
    fun paperKey(path: Path): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    fun hasExamPoints(db: Connection, courseId: String): Boolean =
        CourseStore.loadCourseDetail(db, courseId).examinableTopics.any { it.weekLabel == CourseExamPoints.LABEL && it.title.isNotBlank() }

    private fun ensureTable(db: Connection) {
        if (BookSchemaMigration.tableExists(db, "course_exam_techniques")) {
            val hasKey = db.createStatement().use { s ->
                s.executeQuery("PRAGMA table_info(course_exam_techniques)").use { rows ->
                    var found = false
                    while (rows.next()) if (rows.getString("name") == "paper_key") found = true
                    found
                }
            }
            if (hasKey) return
            db.createStatement().use { it.execute("ALTER TABLE course_exam_techniques RENAME TO course_exam_techniques_old") }
        }
        db.createStatement().use { it.execute("""CREATE TABLE course_exam_techniques(
            course_id TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
            paper_key TEXT NOT NULL, topic_id TEXT NOT NULL, technique TEXT NOT NULL,
            PRIMARY KEY(course_id, paper_key, topic_id, technique))""") }
        if (BookSchemaMigration.tableExists(db, "course_exam_techniques_old")) {
            db.createStatement().use {
                it.execute("INSERT OR IGNORE INTO course_exam_techniques SELECT course_id, '', topic_id, technique FROM course_exam_techniques_old")
                it.execute("DROP TABLE course_exam_techniques_old")
            }
        }
    }

    fun import(db: Connection, courseId: String, path: Path): Int {
        val patterns = CourseStore.loadCourseDetail(db, courseId).examinableTopics
            .filter { it.weekLabel == CourseExamPoints.LABEL && it.title.isNotBlank() }
            .associate { it.topicId to CourseExamPoints.termPattern(CourseExamPoints.normalize(it.title)) }
        if (patterns.isEmpty()) return 0
        val key = paperKey(path)
        val findings = when (path.fileName.toString().substringAfterLast('.').lowercase()) {
            "pdf" -> BookPdfLoader.load(path.toFile()).use { pdf ->
                val stripper = BookPdfStripper()
                buildSet {
                    for (page in 1..pdf.numberOfPages) {
                        stripper.startPage = page
                        stripper.endPage = page
                        addAll(extract(stripper.getText(pdf), patterns))
                    }
                }
            }
            "txt", "md" -> extract(Files.readString(path), patterns)
            else -> throw IllegalArgumentException("往年题支持 PDF、TXT 和 Markdown。")
        }
        if (findings.isEmpty()) return 0
        // One transaction per import; a retry or another paper covering the same point is idempotent.
        db.createStatement().use { it.execute("SAVEPOINT import_exam_techniques") }
        try {
            ensureTable(db)
            db.prepareStatement("INSERT OR IGNORE INTO course_exam_techniques(course_id, paper_key, topic_id, technique) VALUES(?,?,?,?)").use { sql ->
                for (finding in findings) {
                    sql.setString(1, courseId)
                    sql.setString(2, key)
                    sql.setString(3, finding.topicId)
                    sql.setString(4, finding.technique.name)
                    sql.addBatch()
                }
                sql.executeBatch()
            }
            for (topicId in findings.map { it.topicId }.distinct()) {
                CourseStore.recomputeTopicPackHash(db, courseId, topicId, System.currentTimeMillis())
            }
            db.createStatement().use { it.execute("RELEASE import_exam_techniques") }
        } catch (error: Throwable) {
            db.createStatement().use { it.execute("ROLLBACK TO import_exam_techniques") }
            db.createStatement().use { it.execute("RELEASE import_exam_techniques") }
            throw error
        }
        return findings.size
    }

    /** Undo one paper, e.g. when the user says the file was a handout after all. Other papers keep their techniques. */
    fun remove(db: Connection, courseId: String, paperKey: String): Int {
        if (!BookSchemaMigration.tableExists(db, "course_exam_techniques")) return 0
        val topics = db.prepareStatement("SELECT DISTINCT topic_id FROM course_exam_techniques WHERE course_id=? AND paper_key=?").use { sql ->
            sql.setString(1, courseId)
            sql.setString(2, paperKey)
            sql.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        val removed = db.prepareStatement("DELETE FROM course_exam_techniques WHERE course_id=? AND paper_key=?").use { sql ->
            sql.setString(1, courseId)
            sql.setString(2, paperKey)
            sql.executeUpdate()
        }
        for (topicId in topics) CourseStore.recomputeTopicPackHash(db, courseId, topicId, System.currentTimeMillis())
        return removed
    }

    fun load(db: Connection, courseId: String): Map<String, List<ExamTechnique>> {
        if (!BookSchemaMigration.tableExists(db, "course_exam_techniques")) return emptyMap()
        return db.prepareStatement("""SELECT DISTINCT e.topic_id, e.technique FROM course_exam_techniques e
            JOIN course_topics t ON t.topic_id=e.topic_id AND t.collection_id=e.course_id WHERE e.course_id=? ORDER BY e.technique""").use { sql ->
            sql.setString(1, courseId)
            sql.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString(1) to ExamTechnique.valueOf(rows.getString(2))) }
                    .groupBy({ it.first }, { it.second })
            }
        }
    }
}
