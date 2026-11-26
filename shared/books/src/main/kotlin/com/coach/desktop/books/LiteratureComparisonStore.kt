package com.coach.desktop.books

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class StoredComparison(
    val bookIds: List<String>,
    val stored: JsonObject?,
)

/** Local comparison tables. Separate from literature_guides so the one-page guide store stays untouched. */
internal object LiteratureComparisonStore {
    private fun prepare(db: Connection) {
        db.createStatement().use {
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS literature_comparisons(
                course TEXT NOT NULL,
                digest TEXT NOT NULL,
                books TEXT NOT NULL,
                result TEXT,
                last_opened INTEGER NOT NULL,
                PRIMARY KEY(course, digest))""",
            )
        }
    }

    fun digest(bookIds: List<String>): String {
        val canonical = bookIds.distinct().sorted().joinToString("\n")
        val bytes = MessageDigest.getInstance("SHA-256").digest("literature-comparison:v1\n$canonical".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun latest(db: Connection, course: String): StoredComparison? {
        prepare(db)
        return db.prepareStatement(
            "SELECT books, result FROM literature_comparisons WHERE course=? ORDER BY last_opened DESC, rowid DESC LIMIT 1",
        ).use { sql ->
            sql.setString(1, course)
            sql.executeQuery().use { rows -> if (rows.next()) read(rows.getString(1), rows.getString(2)) else null }
        }
    }

    fun load(db: Connection, course: String, key: String): StoredComparison? {
        prepare(db)
        return db.prepareStatement(
            "SELECT books, result FROM literature_comparisons WHERE course=? AND digest=?",
        ).use { sql ->
            sql.setString(1, course)
            sql.setString(2, key)
            sql.executeQuery().use { rows -> if (rows.next()) read(rows.getString(1), rows.getString(2)) else null }
        }
    }

    fun save(db: Connection, course: String, bookIds: List<String>, table: LiteratureComparison) {
        prepare(db)
        db.prepareStatement(
            """INSERT INTO literature_comparisons(course, digest, books, result, last_opened) VALUES(?,?,?,?,?)
            ON CONFLICT(course, digest) DO UPDATE SET books=excluded.books, result=excluded.result, last_opened=excluded.last_opened""",
        ).use { sql ->
            sql.setString(1, course)
            sql.setString(2, digest(bookIds))
            sql.setString(3, JsonArray(bookIds.map(::JsonPrimitive)).toString())
            sql.setString(4, LiteratureComparisonInput.toStoredJson(table).toString())
            sql.setLong(5, System.currentTimeMillis())
            sql.executeUpdate()
        }
    }

    /** A book left the library: drop every comparison that cited it. */
    fun forgetBook(db: Connection, bookId: String) {
        db.prepareStatement(
            "DELETE FROM literature_comparisons WHERE EXISTS (SELECT 1 FROM json_each(books) WHERE value=?)",
        ).use {
            it.setString(1, bookId)
            it.executeUpdate()
        }
    }

    private fun read(booksJson: String, resultJson: String?): StoredComparison {
        val bookIds = runCatching {
            Json.parseToJsonElement(booksJson).jsonArray.map { it.jsonPrimitive.content }
        }.getOrDefault(emptyList())
        val stored = resultJson?.let { raw ->
            runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        }
        return StoredComparison(bookIds, stored)
    }
}
