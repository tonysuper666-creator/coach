package com.coach.store

import com.coach.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.sql.Connection

/** Used through LedgerStore's single writer. Additive tables avoid changing ledger migrations. */
internal class CourseContentStore(private val db: Connection) {
    private fun sql(statement: String, vararg values: Any?) = db.prepareStatement(statement).use { ps ->
        values.forEachIndexed { i, v -> ps.setObject(i + 1, v) }; ps.executeUpdate()
    }
    init {
        sql("CREATE TABLE IF NOT EXISTS course_content(course_id TEXT PRIMARY KEY,content_json TEXT NOT NULL,seed_hash TEXT NOT NULL)")
        sql("CREATE TABLE IF NOT EXISTS course_modules(module_id TEXT PRIMARY KEY,course_id TEXT NOT NULL,ord INTEGER NOT NULL,title TEXT NOT NULL)")
    }
    fun course(id: String): CourseContent? = db.prepareStatement("SELECT content_json FROM course_content WHERE course_id=?").use { ps ->
        ps.setString(1, id); ps.executeQuery().use { rs -> if(rs.next()) LedgerJson.decodeFromString<CourseContent>(rs.getString(1)) else null }
    }
    fun install(seed: ContentSeed, append: (String, String, JsonObject, String) -> Unit,
                putBlob: (ByteArray, String) -> String): Boolean {
        val content = seed.content
        val course = content.course.id
        val hash = sha256(canonicalJson(LedgerJson.encodeToJsonElement(ContentSeed.serializer(), seed)).toByteArray())
        val old = db.prepareStatement("SELECT seed_hash FROM course_content WHERE course_id=?").use { ps ->
            ps.setString(1, course); ps.executeQuery().use { rs -> if(rs.next()) rs.getString(1) else null }
        }
        if(old == hash) return false
        val previous = course(course)
        val kpIds = content.knowledgePoints.map { it.id }.toSet()
        val moduleIds = content.modules.map { it.id }.toSet()
        require(kpIds.size == content.knowledgePoints.size && moduleIds.size == content.modules.size)
        require(content.knowledgePoints.all { it.moduleId in moduleIds && it.prerequisites.all(kpIds::contains) })
        require(content.course.examWeights.all { (id, n) -> id in kpIds && n >= 0 })
        val pages = seed.documents.associate { d -> d.id to d.pages.map { it.pdfPage }.toSet() }
        require(content.knowledgePoints.all { kp -> kp.anchors.all { it.pdfPage in pages[it.docId].orEmpty() } })
        // Only replace this course's metadata; learner and question tables are untouched.
        sql("DELETE FROM course_modules WHERE course_id=?", course)
        for(m in content.modules) sql("INSERT INTO course_modules VALUES(?,?,?,?)", m.id, course, m.order, m.title)
        for(d in seed.documents) {
            val blob = putBlob(d.markdown.toByteArray(), "text/markdown; charset=utf-8")
            sql("INSERT INTO source_docs(doc_id,course_id,kind,title,blob_sha256,book_page_offset,tutor_visible) VALUES(?,?,'TEXTBOOK',?,?,?,1) ON CONFLICT(doc_id) DO UPDATE SET title=excluded.title,blob_sha256=excluded.blob_sha256,book_page_offset=excluded.book_page_offset,tutor_visible=1",
                d.id, course, d.title, blob, d.pages.firstOrNull()?.let { p -> p.bookPage?.let { p.pdfPage - it } })
            sql("DELETE FROM source_pages WHERE doc_id=?", d.id)
            // LedgerStore's source_pages triggers maintain both FTS indexes atomically.
            for(p in d.pages) {
                sql("INSERT INTO source_pages VALUES(?,?,?,?,?)", d.id, p.pdfPage, p.bookPage, p.text, sha256(p.text.toByteArray()))
            }
        }
        // Retain any former KP for historical references, but remove it from the active outline.
        sql("UPDATE outline_kp SET retired=1 WHERE course_id=?", course)
        for(k in content.knowledgePoints) sql("INSERT INTO outline_kp(kp_id,course_id,module_id,ord,title,kind,exam_formats,prereqs,anchors,retired,legacy_id) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(kp_id) DO UPDATE SET module_id=excluded.module_id,ord=excluded.ord,title=excluded.title,kind=excluded.kind,exam_formats=excluded.exam_formats,prereqs=excluded.prereqs,anchors=excluded.anchors,retired=excluded.retired,legacy_id=excluded.legacy_id",
            k.id, course, k.moduleId, k.order, k.title, k.kind, LedgerJson.encodeToString(k.examFormats),
            LedgerJson.encodeToString(k.prerequisites), LedgerJson.encodeToString(k.anchors), if(k.retired) 1 else 0, k.legacyId)
        sql("INSERT INTO course_content VALUES(?,?,?) ON CONFLICT(course_id) DO UPDATE SET content_json=excluded.content_json,seed_hash=excluded.seed_hash",
            course, LedgerJson.encodeToString(content), hash)
        val ops = buildList {
            for(k in previous?.knowledgePoints.orEmpty().filter { it.id !in kpIds }) add(json("op" to "retire", "kpId" to k.id))
            for(k in content.knowledgePoints) {
                add(json("op" to "add", "kpId" to k.id, "prereqs" to k.prerequisites))
                if(k.retired) add(json("op" to "retire", "kpId" to k.id))
                add(json("op" to "move", "kpId" to k.id, "index" to k.order))
            }
        }
        val revision = db.createStatement().use { st -> st.executeQuery("SELECT coalesce(max(seq),0)+1 FROM events").use { rs -> rs.next(); rs.getLong(1) } }
        append(course, "outline_revised", JsonObject(LedgerJson.encodeToJsonElement(CourseContent.serializer(), content).jsonObject + ("ops" to JsonArray(ops))), "seed-content:$hash:$revision:outline")
        append(course, "exam_weights_set", json("weights" to LedgerJson.encodeToJsonElement(content.course.examWeights),
            "groups" to LedgerJson.encodeToJsonElement(content.course.examWeightGroups), "estimated" to true,
            "allocationPolicy" to "equal-per-row; remainder follows listed KP order"), "seed-content:$hash:$revision:weights")
        return true
    }
    /** Returns actual pages, in anchor order, for the tutor preparation pack. */
    fun teachingPages(course: String, kp: String): List<Pair<SourceAnchor, String>> {
        val point = course(course)?.knowledgePoints?.singleOrNull { it.id == kp } ?: return emptyList()
        return point.anchors.mapNotNull { anchor ->
            db.prepareStatement("SELECT p.text FROM source_pages p JOIN source_docs d ON p.doc_id=d.doc_id WHERE p.doc_id=? AND p.pdf_page=? AND d.course_id=? AND d.tutor_visible=1").use { ps ->
                ps.setString(1, anchor.docId); ps.setInt(2, anchor.pdfPage); ps.setString(3, course)
                ps.executeQuery().use { rs -> if(rs.next()) anchor to rs.getString(1) else null }
            }
        }
    }
}
