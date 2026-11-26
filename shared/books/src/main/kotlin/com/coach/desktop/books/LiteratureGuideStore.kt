package com.coach.desktop.books

import java.sql.Connection
import java.security.MessageDigest
import kotlinx.serialization.json.*

internal data class GuideSelection(val books: List<String>, val questions: List<String>)

internal object LiteratureGuideStore {
    private fun prepare(db: Connection) {
        db.createStatement().use {
            it.executeUpdate("""CREATE TABLE IF NOT EXISTS literature_guides(
                course TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
                digest TEXT NOT NULL, books TEXT NOT NULL, questions TEXT NOT NULL, result TEXT, last_opened INTEGER NOT NULL, revision INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(course, digest))""")
        }
    }

    fun latest(db: Connection, course: String): GuideSelection? {
        prepare(db)
        return db.prepareStatement("SELECT books, questions FROM literature_guides WHERE course=? ORDER BY last_opened DESC, rowid DESC LIMIT 1").use {
            it.setString(1, course)
            it.executeQuery().use { rows ->
                fun strings(i: Int) = Json.parseToJsonElement(rows.getString(i)).jsonArray.map { p -> p.jsonPrimitive.content }
                if (rows.next()) GuideSelection(strings(1), strings(2)) else null
            }
        }
    }

    fun digest(request: JsonObject): String = MessageDigest.getInstance("SHA-256")
        .digest(request.toString().toByteArray()).joinToString("") { "%02x".format(it) }

    fun cached(db: Connection, course: String, key: String): JsonObject? {
        prepare(db)
        return db.prepareStatement("SELECT result FROM literature_guides WHERE course=? AND digest=?").use {
            it.setString(1, course)
            it.setString(2, key)
            it.executeQuery().use { r -> if (r.next()) r.getString(1)?.let { s -> Json.parseToJsonElement(s).jsonObject } else null }
        }
    }

    fun revision(db: Connection, course: String, key: String, advance: Boolean = false): Int {
        if (advance) db.prepareStatement("UPDATE literature_guides SET revision=revision+1 WHERE course=? AND digest=?").use {
            it.setString(1, course)
            it.setString(2, key)
            it.executeUpdate()
        }
        return db.prepareStatement("SELECT revision FROM literature_guides WHERE course=? AND digest=?").use {
            it.setString(1, course)
            it.setString(2, key)
            it.executeQuery().use { r -> if (r.next()) r.getInt(1) else 0 }
        }
    }

    fun save(db: Connection, course: String, key: String, input: LiteratureInput, result: JsonObject?) {
        prepare(db)
        db.prepareStatement("""INSERT INTO literature_guides(course,digest,books,questions,result,last_opened) VALUES(?,?,?,?,?,?)
            ON CONFLICT(course,digest) DO UPDATE SET last_opened=excluded.last_opened, result=COALESCE(excluded.result,result)""").use {
            it.setString(1, course)
            it.setString(2, key)
            it.setString(3, JsonArray(input.books.map { b -> JsonPrimitive(b.id) }).toString())
            it.setString(4, JsonArray(input.questions.map(::JsonPrimitive)).toString())
            it.setString(5, result?.toString())
            it.setLong(6, System.currentTimeMillis())
            it.executeUpdate()
        }
    }
}
