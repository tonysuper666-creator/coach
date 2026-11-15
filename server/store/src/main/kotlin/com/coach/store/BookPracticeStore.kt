package com.coach.store

import com.coach.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/** No authorization grants or question approval here. Used inside LedgerStore's writer transaction. */
internal class BookPracticeStore(private val db: Connection, private val blobs: Path) {
    init {
        sql("""CREATE TABLE IF NOT EXISTS book_practice_mappings(
            course TEXT NOT NULL,source_key TEXT NOT NULL,mapping_json TEXT NOT NULL,
            PRIMARY KEY(course,source_key))""")
        sql("""CREATE TABLE IF NOT EXISTS book_practice_poses(
            course TEXT NOT NULL,device TEXT NOT NULL,source_key TEXT NOT NULL,mapping_json TEXT NOT NULL,
            attempt TEXT NOT NULL UNIQUE,event_json TEXT NOT NULL,PRIMARY KEY(course,device,source_key))""")
        sql("""CREATE TABLE IF NOT EXISTS book_original_sha(
            course TEXT NOT NULL, original_sha256 TEXT NOT NULL, blob_sha256 TEXT NOT NULL,
            CHECK(length(original_sha256)=64 AND original_sha256 NOT GLOB '*[^a-f0-9]*'),
            CHECK(length(blob_sha256)=64 AND blob_sha256 NOT GLOB '*[^a-f0-9]*'),
            CHECK(original_sha256<>blob_sha256), PRIMARY KEY(course,original_sha256))""")
    }
    private fun sql(statement: String, vararg args: Any?) = db.prepareStatement(statement).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate()
    }
    private fun <T> rows(statement: String, vararg args: Any?, read: (java.sql.ResultSet) -> T): List<T> =
        db.prepareStatement(statement).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }
    private fun need(ok: Boolean, code: String) { if (!ok) throw ReviewVariantRejected(code) }
    private fun key(s: BookPracticeSource) = canonicalJson(json("book" to s.bookSha256, "lesson" to s.lessonId,
        "page" to s.pdfPage, "start" to s.start, "end" to s.end))
    private fun encoded(m: BookPracticeMapping) = canonicalJson(LedgerJson.encodeToJsonElement(m))
    private fun blob(course: String, original: String): String {
        val direct = rows("""SELECT blob_sha256 FROM source_docs WHERE course_id=? AND blob_sha256=?
            AND kind='TEXTBOOK' AND tutor_visible=1""", course, original) { it.getString(1) }.singleOrNull()
        return direct ?: rows("SELECT blob_sha256 FROM book_original_sha WHERE course=? AND original_sha256=?",
            course, original) { it.getString(1) }.singleOrNull() ?: throw ReviewVariantRejected("BOOK_FILE_UNSEEDED")
    }
    private fun snapshot(course: String, m: BookPracticeMapping): String = canonicalJson(JsonObject(
        LedgerJson.encodeToJsonElement(m).jsonObject + ("sourceBlobSha256" to JsonPrimitive(blob(course, m.source.bookSha256)))))
    private fun snapshotMatches(course: String, m: BookPracticeMapping, raw: String): Boolean {
        val obj = LedgerJson.parseToJsonElement(raw).jsonObject
        val saved = obj["sourceBlobSha256"]?.jsonPrimitive?.content
        val mapping = canonicalJson(JsonObject(obj - "sourceBlobSha256"))
        val current = blob(course, m.source.bookSha256)
        // Legacy poses had no resolved-hash field and only supported direct blob identities.
        return mapping == encoded(m) && (saved == current || (saved == null && current == m.source.bookSha256))
    }

    /** Import does not manufacture VERIFIED: it can only reference an already reviewed pool key. */
    fun install(course: String, mappings: List<BookPracticeMapping>, content: CourseContent) {
        need(mappings.map { key(it.source) }.distinct().size == mappings.size, "BOOK_MAPPING_AMBIGUOUS")
        mappings.forEach { validate(course, it, content) }
        sql("DELETE FROM book_practice_mappings WHERE course=?", course)
        mappings.forEach { sql("INSERT INTO book_practice_mappings VALUES(?,?,?)", course, key(it.source), encoded(it)) }
    }
    fun mapping(course: String, s: BookPracticeSource, content: CourseContent?): BookPracticeMapping {
        need(content?.course?.id == course, "BOOK_COURSE_UNKNOWN")
        val raw = rows("SELECT mapping_json FROM book_practice_mappings WHERE course=? AND source_key=?", course, key(s)) {
            it.getString(1)
        }.singleOrNull() ?: throw ReviewVariantRejected("BOOK_MAPPING_UNAVAILABLE")
        val m = LedgerJson.decodeFromString<BookPracticeMapping>(raw)
        need(m.source == s, "BOOK_REFERENCE_MISMATCH")
        validate(course, m, content!!)
        return m
    }
    private fun validate(course: String, m: BookPracticeMapping, content: CourseContent) {
        val s = m.source
        need(s.bookSha256.matches(Regex("[a-f0-9]{64}")) && s.lessonId.isNotBlank() &&
            s.pdfPage > 0 && s.start >= 0 && s.end > s.start && s.reference.isNotBlank(), "BOOK_IDENTITY_INCOMPLETE")
        val resolved = blob(course, s.bookSha256)
        val doc = rows("""SELECT doc_id FROM source_docs WHERE course_id=? AND blob_sha256=?
            AND kind='TEXTBOOK' AND tutor_visible=1""", course, resolved) { it.getString(1) }.singleOrNull()
        val file = blobs.resolve(resolved)
        need(doc != null && Files.isRegularFile(file) && sha256(Files.readAllBytes(file)) == resolved, "BOOK_FILE_UNSEEDED")
        val page = rows("SELECT text,text_sha256 FROM source_pages WHERE doc_id=? AND pdf_page=?", doc, s.pdfPage) {
            it.getString(1) to it.getString(2)
        }.singleOrNull()
        need(page != null && sha256(page.first.toByteArray()) == page.second && s.end <= page.first.length &&
            page.first.substring(s.start, s.end).contains(s.reference), "BOOK_BLOCK_MISMATCH")
        val kp = content.knowledgePoints.singleOrNull { it.id == m.kpId && !it.retired }
        need(content.course.id == course && kp != null && kp.anchors.any {
            val quote = it.quote.orEmpty()
            it.docId == doc && it.pdfPage == s.pdfPage &&
                (if (quote.isBlank()) it.basis == "SOURCE_REFERENCE"
                 else quote.contains(s.reference) && page!!.first.contains(quote))
        }, "BOOK_KP_SOURCE_MISMATCH")
        val q = rows("""SELECT q.stem_md,q.options,q.fingerprint,q.key_state,a.key_json,a.reviewed_by,
            d.source_quote,d.generator,d.reviewer FROM questions q JOIN answer_keys a USING(question_id)
            JOIN pool_details d USING(question_id) JOIN question_kp k USING(question_id)
            WHERE q.course_id=? AND q.question_id=? AND q.format='SINGLE_CHOICE' AND k.kp_id=?""",
            course, m.questionId, m.kpId) { rs -> (1..9).map { rs.getString(it).orEmpty() } }.singleOrNull()
        need(q != null && q[2] == m.questionFingerprint && q[3] == "VERIFIED" &&
            q[5].isNotBlank() && q[5] == q[8] && q[7].isNotBlank() && q[8] != q[7] &&
            q[6] == s.reference, "BOOK_REVIEWED_QUESTION_UNAVAILABLE")
        val options = LedgerJson.decodeFromString<List<Option>>(q!![1])
        need(questionFingerprint(q[0], options) == m.questionFingerprint && options.map { it.text }.distinct().size == options.size,
            "BOOK_QUESTION_MISMATCH")
        ChoiceKey(options, LedgerJson.parseToJsonElement(q[4]).jsonObject.str("correctOptionId"))
    }
    fun prior(course: String, device: String, m: BookPracticeMapping): Event? = rows(
        "SELECT mapping_json,event_json FROM book_practice_poses WHERE course=? AND device=? AND source_key=?",
        course, device, key(m.source)) {
        need(snapshotMatches(course, m, it.getString(1)), "BOOK_MAPPING_CHANGED")
        LedgerJson.decodeFromString<Event>(it.getString(2))
    }.singleOrNull()
    fun remember(course: String, device: String, m: BookPracticeMapping, event: Event) {
        sql("INSERT INTO book_practice_poses VALUES(?,?,?,?,?,?)", course, device, key(m.source), snapshot(course, m),
            event.payload.str("attemptId"), LedgerJson.encodeToString(event))
    }
    /** A book link on a snapshot carries no authority; only this server's persisted pose does. */
    fun acceptsAnswer(course: String, device: String, attempt: String, snapshot: JsonObject?,
        payload: JsonObject, facts: List<Event>, content: CourseContent?): Boolean = runCatching {
        val row = rows("SELECT mapping_json,event_json FROM book_practice_poses WHERE course=? AND attempt=?", course, attempt) {
            it.getString(1) to LedgerJson.decodeFromString<Event>(it.getString(2))
        }.single()
        val (raw, pose) = row
        val m = LedgerJson.decodeFromJsonElement<BookPracticeMapping>(JsonObject(LedgerJson.parseToJsonElement(raw).jsonObject - "sourceBlobSha256"))
        val paired = rows("SELECT 1 FROM devices WHERE device_id=? AND revoked_at IS NULL", device) { it.getInt(1) }.isNotEmpty()
        need(paired && mapping(course, m.source, content) == m && snapshotMatches(course, m, raw) && pose.deviceId == device && snapshot == pose.payload &&
            payload.str("threadId").isBlank() && payload.str("agentRunId").isBlank() &&
            learnerEvidence(facts).any { it == pose } && facts.none {
                it.type == "attempt_voided" && it.payload.str("attemptId") == attempt
            }, "BOOK_PRACTICE_SCOPE_MISMATCH")
        true
    }.getOrDefault(false)
}
