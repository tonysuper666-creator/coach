package com.coach.desktop.books

import java.sql.Connection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Verified literature quizzes, kept apart from the one-pager JSON. */
internal object LiteratureQuizStore {
    private fun prepare(db: Connection) {
        db.createStatement().use {
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS literature_quizzes(
                course TEXT NOT NULL, digest TEXT NOT NULL, result TEXT NOT NULL, saved_at INTEGER NOT NULL,
                PRIMARY KEY(course, digest))""",
            )
        }
    }

    fun cached(db: Connection, course: String, digest: String): kotlinx.serialization.json.JsonObject? {
        prepare(db)
        return db.prepareStatement("SELECT result FROM literature_quizzes WHERE course=? AND digest=?").use {
            it.setString(1, course)
            it.setString(2, digest)
            it.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                runCatching { Json.parseToJsonElement(rows.getString(1)).jsonObject }.getOrNull()
            }
        }
    }

    fun save(db: Connection, course: String, quiz: LiteratureQuizSet) {
        prepare(db)
        db.prepareStatement(
            """INSERT INTO literature_quizzes(course,digest,result,saved_at) VALUES(?,?,?,?)
            ON CONFLICT(course,digest) DO UPDATE SET result=excluded.result, saved_at=excluded.saved_at""",
        ).use {
            it.setString(1, course)
            it.setString(2, quiz.digest)
            it.setString(3, LiteratureQuiz.toStoredJson(quiz).toString())
            it.setLong(4, System.currentTimeMillis())
            it.executeUpdate()
        }
    }

    fun dropDigests(db: Connection, digests: List<String>) {
        if (digests.isEmpty()) return
        prepare(db)
        db.prepareStatement(
            "DELETE FROM literature_quizzes WHERE digest IN (${digests.joinToString(",") { "?" }})",
        ).use { statement ->
            digests.forEachIndexed { index, digest -> statement.setString(index + 1, digest) }
            statement.executeUpdate()
        }
    }
}
