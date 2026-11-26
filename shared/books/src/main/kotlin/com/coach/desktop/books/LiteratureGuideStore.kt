package com.coach.desktop.books

import java.security.MessageDigest
import java.sql.Connection
import kotlinx.serialization.json.*

internal data class GuideSelection(
    val books: List<String>,
    val questions: List<String>,
    val metas: Map<String, LiteraturePaperMeta> = emptyMap(),
)

internal object LiteratureGuideStore {
    private fun prepare(db: Connection) {
        db.createStatement().use {
            it.executeUpdate(
                """CREATE TABLE IF NOT EXISTS literature_guides(
                course TEXT NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
                digest TEXT NOT NULL, books TEXT NOT NULL, questions TEXT NOT NULL, result TEXT,
                last_opened INTEGER NOT NULL, revision INTEGER NOT NULL DEFAULT 0, papers_meta TEXT,
                PRIMARY KEY(course, digest))""",
            )
        }
        if (!BookSchemaMigration.tableColumns(db, "literature_guides").contains("papers_meta")) {
            db.createStatement().use { it.executeUpdate("ALTER TABLE literature_guides ADD COLUMN papers_meta TEXT") }
        }
    }

    fun latest(db: Connection, course: String): GuideSelection? {
        prepare(db)
        return db.prepareStatement(
            "SELECT books, questions, papers_meta FROM literature_guides WHERE course=? ORDER BY last_opened DESC, rowid DESC LIMIT 1",
        ).use {
            it.setString(1, course)
            it.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                fun strings(i: Int) = Json.parseToJsonElement(rows.getString(i)).jsonArray.map { p -> p.jsonPrimitive.content }
                val metas = rows.getString(3)?.let(::decodeMetas).orEmpty()
                GuideSelection(strings(1), strings(2), metas)
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
            it.executeQuery().use { r ->
                if (r.next()) r.getString(1)?.let { s -> runCatching { Json.parseToJsonElement(s).jsonObject }.getOrNull() } else null
            }
        }
    }

    /**
     * A book left the library: take it out of every saved selection and drop the finished page built on it,
     * but keep the questions the user wrote.
     */
    fun forgetBook(db: Connection, bookId: String) {
        db.prepareStatement(
            """UPDATE literature_guides
               SET books=(SELECT json_group_array(value) FROM json_each(literature_guides.books) WHERE value<>?), result=NULL
               WHERE EXISTS (SELECT 1 FROM json_each(books) WHERE value=?)""",
        ).use {
            it.setString(1, bookId)
            it.setString(2, bookId)
            it.executeUpdate()
        }
    }

    /** Record selection metadata without marking a guide as complete (never writes a partial result). */
    fun saveSelection(db: Connection, course: String, key: String, input: LiteratureInput) {
        prepare(db)
        db.prepareStatement(
            """INSERT INTO literature_guides(course,digest,books,questions,result,last_opened,revision,papers_meta) VALUES(?,?,?,?,NULL,?,?,?)
            ON CONFLICT(course,digest) DO UPDATE SET last_opened=excluded.last_opened, papers_meta=excluded.papers_meta""",
        ).use {
            it.setString(1, course)
            it.setString(2, key)
            it.setString(3, JsonArray(input.books.map { b -> JsonPrimitive(b.id) }).toString())
            it.setString(4, JsonArray(input.questions.map(::JsonPrimitive)).toString())
            it.setLong(5, System.currentTimeMillis())
            it.setInt(6, input.revision)
            it.setString(7, encodeMetas(input.paperMetas))
            it.executeUpdate()
        }
    }

    /** Persist a fully verified one-page guide. Must only be called when all items passed verification. */
    fun saveComplete(db: Connection, course: String, key: String, input: LiteratureInput, storedGuide: JsonObject) {
        prepare(db)
        db.prepareStatement(
            """INSERT INTO literature_guides(course,digest,books,questions,result,last_opened,revision,papers_meta) VALUES(?,?,?,?,?,?,?,?)
            ON CONFLICT(course,digest) DO UPDATE SET last_opened=excluded.last_opened, result=excluded.result,
                revision=excluded.revision, papers_meta=excluded.papers_meta""",
        ).use {
            it.setString(1, course)
            it.setString(2, key)
            it.setString(3, JsonArray(input.books.map { b -> JsonPrimitive(b.id) }).toString())
            it.setString(4, JsonArray(input.questions.map(::JsonPrimitive)).toString())
            it.setString(5, storedGuide.toString())
            it.setLong(6, System.currentTimeMillis())
            it.setInt(7, input.revision)
            it.setString(8, encodeMetas(input.paperMetas))
            it.executeUpdate()
        }
    }

    private fun encodeMetas(metas: Map<String, LiteraturePaperMeta>): String = buildJsonArray {
        metas.values.forEach { m ->
            add(buildJsonObject {
                put("bookId", m.bookId)
                put("title", m.title)
                put("author", m.author)
                put("year", m.year)
            })
        }
    }.toString()

    private fun decodeMetas(raw: String): Map<String, LiteraturePaperMeta> = runCatching {
        Json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = (obj["bookId"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            id to LiteraturePaperMeta(
                bookId = id,
                title = (obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                author = (obj["author"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                year = (obj["year"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        }.toMap()
    }.getOrDefault(emptyMap())
}
