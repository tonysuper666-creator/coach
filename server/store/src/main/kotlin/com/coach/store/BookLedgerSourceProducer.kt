package com.coach.store

import com.coach.domain.*
import com.coach.domain.reviewqueue.QueueQuestion
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * Where a local book places an original ledger question. It carries no authority: the book file,
 * source document, question, pose, answer, device and key are all re-read from server facts.
 */
data class BookSourceLocator(
    val courseId: String, val device: String, val originalAttemptId: String, val bookSha256: String,
    val lessonId: String, val pdfPage: Int, val start: Int, val end: Int, val reference: String,
    val optionOrder: List<String>,
)

/** Builds a [BookLedgerIdentity] only from facts the server already owns; runs in LedgerStore's writer transaction. */
internal class BookLedgerSourceProducer(private val db: Connection, private val blobs: Path) {
    private fun <T> rows(statement: String, vararg args: Any?, read: (java.sql.ResultSet) -> T): List<T> =
        db.prepareStatement(statement).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }
    private fun refuse(code: String): Nothing = throw ReviewVariantRejected(code)

    fun identity(l: BookSourceLocator, facts: List<Event>, content: CourseContent?): Pair<BookLedgerIdentity, ByteArray> {
        if (!l.bookSha256.matches(Regex("[a-f0-9]{64}")) || l.lessonId.isBlank() || l.device.isBlank() ||
            l.originalAttemptId.isBlank()) refuse("BOOK_IDENTITY_INCOMPLETE")
        // The book must be the server's own seeded textbook: its original bytes are re-read and re-hashed
        // from the content-addressed blob store, never accepted from the caller.
        val docId = rows("""SELECT doc_id FROM source_docs WHERE course_id=? AND blob_sha256=? AND kind='TEXTBOOK'
            AND tutor_visible=1""", l.courseId, l.bookSha256) { it.getString(1) }.singleOrNull() ?: refuse("BOOK_FILE_UNSEEDED")
        val file = blobs.resolve(l.bookSha256)
        val bytes = if (Files.isRegularFile(file)) Files.readAllBytes(file) else refuse("BOOK_FILE_UNSEEDED")
        if (sha256(bytes) != l.bookSha256) refuse("BOOK_FILE_UNSEEDED")
        val evidence = learnerEvidence(facts)
        val posed = evidence.singleOrNull { it.type == "question_posed" && it.payload.str("attemptId") == l.originalAttemptId }
        if (posed == null || posed.commandId != null) refuse("BOOK_ATTEMPT_MISMATCH")
        val answer = evidence.singleOrNull { it.type == "answer_submitted" && it.payload.str("attemptId") == l.originalAttemptId }
            ?: refuse("BOOK_ANSWER_MISMATCH")
        // Same learner device saw the server pose and submitted the answer through its own authenticated command.
        if (posed.deviceId != l.device || answer.deviceId != l.device || answer.commandId == null) refuse("BOOK_DEVICE_MISMATCH")
        val p = posed.payload
        val page = rows("SELECT text FROM source_pages WHERE doc_id=? AND pdf_page=?", docId, l.pdfPage) { it.getString(1) }
            .singleOrNull() ?: refuse("BOOK_BLOCK_MISMATCH")
        if (l.start < 0 || l.end <= l.start || l.end > page.length) refuse("BOOK_BLOCK_MISMATCH")
        // Offsets are UTF-16 code units into this exact page: a range that starts or ends between
        // the two halves of a surrogate pair names half a character, not a source block.
        if (Character.isLowSurrogate(page[l.start]) || Character.isHighSurrogate(page[l.end - 1])) refuse("BOOK_BLOCK_MISMATCH")
        val anchored = content?.takeIf { it.course.id == l.courseId }?.knowledgePoints.orEmpty()
            .filter { !it.retired && it.id in p.strings("kpIds") && it.anchors.any { a -> a.docId == docId && a.pdfPage == l.pdfPage } }
        val kp = anchored.singleOrNull()?.id ?: refuse("BOOK_KP_SOURCE_MISMATCH")
        val options = LedgerJson.decodeFromJsonElement<List<Option>>(p.getValue("options"))
        val letterMap = (p["letterMap"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        val key = rows("SELECT key_json FROM answer_keys WHERE question_id=?", p.str("questionId")) { it.getString(1) }
            .singleOrNull() ?: refuse("BOOK_QUESTION_MISMATCH")
        val correct = options.singleOrNull { it.optionId == LedgerJson.parseToJsonElement(key).jsonObject.str("correctOptionId") }
            ?: refuse("BOOK_QUESTION_MISMATCH")
        val chosen = options.singleOrNull { letterMap[it.optionId] == answer.payload.str("answer") } ?: refuse("BOOK_ANSWER_MISMATCH")
        // Only the display order comes from the locator; it must be exactly the frozen option texts.
        if (l.optionOrder.sorted() != options.map { it.text }.sorted()) refuse("BOOK_PRACTICE_MISMATCH")
        val text = page.substring(l.start, l.end)
        return BookLedgerIdentity(l.bookSha256, l.lessonId,
            BookLedgerBlock("${l.bookSha256}:p${l.pdfPage}:${l.start}", l.pdfPage, l.start, l.end, sha256(text.toByteArray())),
            docId, l.bookSha256, l.courseId, kp, QueueQuestion(p.str("questionId"), null, p.str("fingerprint").ifBlank { null }),
            l.originalAttemptId, p.str("stem"), l.optionOrder, l.reference, correct.text, chosen.text,
            answer.payload.flag("guessed")) to bytes
    }
}
