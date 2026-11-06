package com.coach.store

import com.coach.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.sql.Connection

/** Append-only outline import. Source documents and learner facts are never replaced. */
internal class OutlineAdditionStore(private val db: Connection) {
    private fun sql(statement: String, vararg args: Any?) = db.prepareStatement(statement).use { ps ->
        args.forEachIndexed { i, v -> ps.setObject(i + 1, v) }; ps.executeUpdate()
    }
    private fun <T> query(statement: String, vararg args: Any?, read: (java.sql.ResultSet) -> T): List<T> =
        db.prepareStatement(statement).use { ps ->
            args.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
            ps.executeQuery().use { rs -> buildList { while(rs.next()) add(read(rs)) } }
        }

    fun install(input: JsonObject, contentStore: CourseContentStore,
                append: (String, JsonObject, String) -> Unit): Boolean {
        require(input["additive"] == JsonPrimitive(true) && input["retiresExisting"] == JsonPrimitive(false)) {
            "Expected an additive outline with retiresExisting=false"
        }
        val course = input.getValue("course").jsonObject.str("id")
        require(course.isNotBlank() && course != "__system__")
        val modules = input.getValue("modules").jsonArray.map { raw ->
            val m = raw.jsonObject
            Module(m.str("id"), m.getValue("ordinal").jsonPrimitive.int, m.str("name"))
        }
        val rawPoints = input.getValue("knowledgePoints").jsonArray
        val points = rawPoints.map { raw ->
            val p = raw.jsonObject
            require(!p.flag("retired") && !p.flag("calendarOnly")) { "Only new teaching points can be appended" }
            val title = p.str("title").ifBlank { p.str("name") }
            require(p.str("name").isBlank() || p.str("name") == title) { "Point titles disagree" }
            val week = p.getValue("week").jsonPrimitive.int
            require(week > 0 && titleWeeks(title) == setOf(week)) { "Point week and title disagree" }
            require(p["sourceAnchor"] == null || p["sourceAnchor"] in p.getValue("anchors").jsonArray) {
                "Primary source anchor must be one of the teaching anchors"
            }
            require(p["conceptIds"] == null || p.strings("conceptIds") == listOf(p.str("id"))) {
                "New points must use their own concept ID"
            }
            KnowledgePoint(p.str("id"), p.str("moduleId"), p.getValue("ordinal").jsonPrimitive.int, title,
                examFormats = p.getValue("examFormats").jsonArray.map { Format.valueOf(it.jsonPrimitive.content) }.toSet(),
                prerequisites = p.getValue("prerequisiteIds").jsonArray.map { it.jsonPrimitive.content },
                anchors = p.getValue("anchors").jsonArray.map { a ->
                    val anchor = a.jsonObject
                    SourceAnchor(anchor.str("docId"), anchor.getValue("pdfPage").jsonPrimitive.int,
                        anchor["bookPage"]?.jsonPrimitive?.intOrNull)
                }, conceptIds = setOf(p.str("id")))
        }
        val ids = points.map { it.id }.toSet()
        val moduleIds = modules.map { it.id }.toSet()
        require(points.isNotEmpty() && ids.size == points.size && moduleIds.size == modules.size) { "Duplicate or empty outline" }
        require(modules.all { it.id.isNotBlank() && it.title.isNotBlank() && it.order >= 0 })
        require(points.all { it.id.isNotBlank() && it.title.isNotBlank() && it.moduleId in moduleIds && it.anchors.isNotEmpty() })
        require(points.zipWithNext().all { (a, b) -> a.order < b.order }) { "New points must be in outline order" }
        // Derive operations from validated point metadata; never execute arbitrary input ops.
        val ops = points.mapIndexed { index, p -> json("op" to "add", "kpId" to p.id, "title" to p.title,
            "conceptIds" to p.conceptIds.toList(), "prereqs" to p.prerequisites,
            "week" to rawPoints[index].jsonObject.getValue("week")) }
        val event = JsonObject(input + ("ops" to JsonArray(ops)))
        val hash = sha256(canonicalJson(event).toByteArray())
        val idem = "add-outline:$hash"
        if(query("SELECT payload FROM events WHERE course_id=? AND idem_key=?", course, idem) { it.getString(1) }.isNotEmpty()) return false

        val existing = query("SELECT kp_id,prereqs,retired,ord FROM outline_kp WHERE course_id=?", course) {
            ExistingPoint(it.getString(1), it.getString(2), it.getInt(3) != 0, it.getInt(4))
        }
        require(existing.isNotEmpty()) { "Append requires an existing course outline" }
        require(points.first().order > existing.maxOf { it.order }) { "New points must follow the existing outline" }
        require(query("SELECT 1 FROM events WHERE course_id=? AND type='outline_revised' LIMIT 1", course) { true }.isNotEmpty())
        for(id in ids) require(query("SELECT 1 FROM outline_kp WHERE kp_id=?", id) { true }.isEmpty()) { "Knowledge point ID already exists" }
        for(id in moduleIds) {
            require(query("SELECT 1 FROM course_modules WHERE module_id=? UNION SELECT 1 FROM outline_kp WHERE module_id=?", id, id) { true }.isEmpty()) { "Module ID already exists" }
        }
        val active = existing.filter { !it.retired }.map { it.id }.toSet() + ids
        require(points.all { p -> p.prerequisites.all { it in active } }) { "Unknown or retired prerequisite" }
        val edges = existing.associate { p -> p.id to p.prerequisites?.let { LedgerJson.decodeFromString<List<String>>(it) }.orEmpty() } +
            points.associate { it.id to it.prerequisites }
        val visiting = mutableSetOf<String>(); val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if(id in visited) return
            require(visiting.add(id)) { "Prerequisite cycle" }
            edges[id].orEmpty().forEach(::visit)
            visiting.remove(id); visited.add(id)
        }
        ids.forEach(::visit)
        for(p in points) for(a in p.anchors) {
            val valid = query("""SELECT p.book_page,p.text,d.doc_id,d.title,d.kind FROM source_pages p
                JOIN source_docs d ON d.doc_id=p.doc_id WHERE p.doc_id=? AND p.pdf_page=?
                AND d.course_id=? AND d.tutor_visible=1 AND d.learner_visible=1""", a.docId, a.pdfPage, course) {
                (a.bookPage == null || it.getObject(1) != null && it.getInt(1) == a.bookPage) &&
                    it.getString(2).orEmpty().isNotBlank() && tutorSourceAllowed(it.getString(3), it.getString(4).orEmpty(), it.getString(5).orEmpty())
            }
            require(a.pdfPage > 0 && valid.singleOrNull() == true) { "Missing or unavailable source anchor" }
        }
        val previous = contentStore.course(course)
        if(previous != null) {
            require(previous.knowledgePoints.map { it.id }.toSet() == existing.map { it.id }.toSet()) { "Course metadata and outline disagree" }
            val merged = previous.copy(modules = previous.modules + modules, knowledgePoints = previous.knowledgePoints + points)
            sql("UPDATE course_content SET content_json=?,seed_hash=? WHERE course_id=?",
                LedgerJson.encodeToString(merged), "add-outline:$hash", course)
        }
        for(m in modules) sql("INSERT INTO course_modules VALUES(?,?,?,?)", m.id, course, m.order, m.title)
        for(p in points) sql("""INSERT INTO outline_kp(kp_id,course_id,module_id,ord,title,kind,exam_formats,prereqs,anchors,retired,legacy_id)
            VALUES(?,?,?,?,?,NULL,?,?,?,0,NULL)""", p.id, course, p.moduleId, p.order, p.title,
            LedgerJson.encodeToString(p.examFormats), LedgerJson.encodeToString(p.prerequisites), LedgerJson.encodeToString(p.anchors))
        append(course, event, idem)
        return true
    }

    private data class ExistingPoint(val id: String, val prerequisites: String?, val retired: Boolean, val order: Int)
}
