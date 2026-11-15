package com.coach.store

import com.coach.domain.*
import com.coach.domain.reviewqueue.QueueQuestion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.sql.Connection

/** Used only in LedgerStore's single-writer transaction. No learner HTTP route can approve links. */
internal class BookLedgerSourceStore(private val db: Connection) {
    init {
        sql("""CREATE TABLE IF NOT EXISTS book_ledger_sources(
            book TEXT NOT NULL,course TEXT NOT NULL,attempt TEXT NOT NULL,identity_json TEXT NOT NULL,
            PRIMARY KEY(book,course,attempt))""")
        sql("""CREATE TABLE IF NOT EXISTS book_ledger_takes(
            course TEXT NOT NULL,device TEXT NOT NULL,attempt TEXT NOT NULL,registration_json TEXT NOT NULL,
            PRIMARY KEY(course,device,attempt))""")
    }
    private fun sql(statement: String, vararg args: Any?) = db.prepareStatement(statement).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate()
    }
    private fun <T> rows(statement: String, vararg args: Any?, read: (java.sql.ResultSet) -> T): List<T> =
        db.prepareStatement(statement).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }
    private fun requireFact(condition: Boolean, code: String) {
        if (!condition) throw ReviewVariantRejected(code)
    }
    private fun encoded(identity: BookLedgerIdentity) = canonicalJson(LedgerJson.encodeToJsonElement(identity))

    /** Explicit content-owner operation, with the actual original bytes, never a client approval flag. */
    fun approve(identity: BookLedgerIdentity, originalBytes: ByteArray, facts: List<Event>, content: CourseContent?) {
        requireFact(sha256(originalBytes) == identity.bookSha256, "BOOK_FILE_MISMATCH")
        validateFacts(identity, facts, content)
        val old = approved(identity)
        requireFact(old == null || old == encoded(identity), "BOOK_SOURCE_CONFLICT")
        sql("INSERT OR IGNORE INTO book_ledger_sources VALUES(?,?,?,?)", identity.bookSha256,
            identity.courseId, identity.originalAttemptId, encoded(identity))
    }
    private fun approved(i: BookLedgerIdentity) = rows(
        "SELECT identity_json FROM book_ledger_sources WHERE book=? AND course=? AND attempt=?",
        i.bookSha256, i.courseId, i.originalAttemptId) { it.getString(1) }.singleOrNull()

    fun verify(i: BookLedgerIdentity, facts: List<Event>, content: CourseContent?) {
        requireFact(approved(i) == encoded(i), "BOOK_SOURCE_UNLINKED")
        validateFacts(i, facts, content)
    }
    fun withdraw(book: String, course: String, attempt: String) {
        sql("DELETE FROM book_ledger_sources WHERE book=? AND course=? AND attempt=?", book, course, attempt)
    }

    private fun validateFacts(i: BookLedgerIdentity, facts: List<Event>, content: CourseContent?) {
        val hex = Regex("[a-f0-9]{64}")
        requireFact(i.bookSha256.matches(hex) && i.docBlobSha256.matches(hex) &&
            i.block.textSha256.matches(hex) && i.question.version == null &&
            !i.question.fingerprint.isNullOrBlank() && i.question.id.isNotBlank() &&
            i.originalAttemptId.isNotBlank() && i.lessonId.isNotBlank(), "BOOK_IDENTITY_INCOMPLETE")
        val kp = content?.takeIf { it.course.id == i.courseId }?.knowledgePoints
            ?.singleOrNull { it.id == i.kpId && !it.retired }
        requireFact(kp != null && kp.anchors.any { it.docId == i.docId && it.pdfPage == i.block.pdfPage }, "BOOK_KP_SOURCE_MISMATCH")
        val page = rows("""SELECT p.text FROM source_pages p JOIN source_docs d USING(doc_id)
            WHERE d.course_id=? AND d.doc_id=? AND d.blob_sha256=? AND d.tutor_visible=1 AND p.pdf_page=?""",
            i.courseId, i.docId, i.docBlobSha256, i.block.pdfPage) { it.getString(1) }.singleOrNull()
        requireFact(page != null && i.block.start >= 0 && i.block.end > i.block.start &&
            i.block.end <= page.length && i.block.id == "${i.bookSha256}:p${i.block.pdfPage}:${i.block.start}", "BOOK_BLOCK_MISMATCH")
        val text = page!!.substring(i.block.start, i.block.end)
        requireFact(sha256(text.toByteArray()) == i.block.textSha256 && i.reference.isNotBlank() &&
            text.contains(i.reference), "BOOK_BLOCK_MISMATCH")
        val question = rows("""SELECT q.stem_md,q.fingerprint,q.options,q.key_state,a.key_json FROM questions q
            JOIN answer_keys a USING(question_id) JOIN question_kp k USING(question_id)
            WHERE q.course_id=? AND q.question_id=? AND k.kp_id=? AND q.format='SINGLE_CHOICE'""",
            i.courseId, i.question.id, i.kpId) {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5))
        }.singleOrNull()
        requireFact(question != null && question[1] == i.question.fingerprint && question[3] == "VERIFIED", "BOOK_QUESTION_MISMATCH")
        val options = LedgerJson.decodeFromString<List<Option>>(question!![2])
        requireFact(questionFingerprint(question[0], options) == i.question.fingerprint, "BOOK_QUESTION_MISMATCH")
        val correctId = LedgerJson.parseToJsonElement(question[4]).jsonObject.str("correctOptionId")
        val posed = learnerEvidence(facts).filter { it.type == "question_posed" && it.payload.str("attemptId") == i.originalAttemptId }.singleOrNull()
        requireFact(posed != null && posed.commandId == null && posed.payload.str("questionId") == i.question.id &&
            posed.payload.str("fingerprint") == i.question.fingerprint && posed.payload.str("questionVersion").isBlank() &&
            i.kpId in posed.payload.strings("kpIds") && posed.payload.str("format") == "SINGLE_CHOICE" &&
            posed.payload.str("keyState") == "VERIFIED", "BOOK_ATTEMPT_MISMATCH")
        val p = posed!!.payload
        val frozenOptions = LedgerJson.decodeFromJsonElement<List<Option>>(p.getValue("options"))
        val letterMap = (p["letterMap"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        requireFact(frozenOptions == options && validLetterMap(options, letterMap) &&
            p.str("stem") == question[0] && p.str("stem") == i.prompt &&
            options.map { it.text }.toSet() == i.options.toSet() && options.size == i.options.size &&
            i.options.distinct().size == i.options.size &&
            options.singleOrNull { it.optionId == correctId }?.text == i.correctAnswer, "BOOK_PRACTICE_MISMATCH")
        val answer = learnerEvidence(facts).filter { it.type == "answer_submitted" && it.payload.str("attemptId") == i.originalAttemptId }.singleOrNull()
        val answerOption = answer?.payload?.str("answer")?.let { letter -> options.singleOrNull { letterMap[it.optionId] == letter } }
        requireFact(answer != null && answerOption?.text == i.originalAnswer &&
            answer.payload.flag("guessed") == i.guessed && facts.none {
                it.type == "attempt_voided" && it.payload.str("attemptId") == i.originalAttemptId
            }, "BOOK_ANSWER_MISMATCH")
    }

    fun coverage(course: String, facts: List<Event>) = BookLedgerCoverage(course, facts.maxOfOrNull { it.seq } ?: 0,
        facts.filter { it.type == "question_posed" }.map {
            BookLedgerPose(it.id, it.seq, it.payload.str("attemptId"), QueueQuestion(it.payload.str("questionId"),
                it.payload.str("questionVersion").ifBlank { null }, it.payload.str("fingerprint").ifBlank { null }))
        }.sortedBy { it.seq })

    fun checkCoverage(expected: BookLedgerCoverage, course: String, facts: List<Event>) {
        requireFact(expected == coverage(course, facts), "BOOK_POSED_SET_INCOMPLETE")
    }
    fun priorTake(course: String, device: String, attempt: String): String? = rows(
        "SELECT registration_json FROM book_ledger_takes WHERE course=? AND device=? AND attempt=?",
        course, device, attempt) { it.getString(1) }.singleOrNull()
    fun takeJson(r: BookLedgerRegistration) = canonicalJson(LedgerJson.encodeToJsonElement(r))
    fun rememberTake(device: String, r: BookLedgerRegistration) {
        sql("INSERT OR IGNORE INTO book_ledger_takes VALUES(?,?,?,?)", r.identity.courseId, device, r.attemptId, takeJson(r))
    }
}
