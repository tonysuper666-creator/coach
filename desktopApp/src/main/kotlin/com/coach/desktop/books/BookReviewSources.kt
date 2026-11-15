package com.coach.desktop.books

import com.coach.domain.reviewqueue.QueueQuestion
import com.coach.domain.reviewqueue.VariantCandidate
import kotlinx.serialization.json.*
import java.sql.Connection

/** An approved source owner's explicit snapshot, not a mapping inferred by this library.
 * Revisions include withdrawals (input=null). The owner must include the complete posed set.
 */
internal data class BookReviewSourceUpdate(
    val approvalId: String, val revision: Long, val input: BookReviewVariantInput?,
)

/** Durable adapter used by the ordinary library/Windows host, without a test-only injected port. */
internal class BookReviewSources(private val database: () -> Connection) : BookReviewVariantPort {
    fun initialize(db: Connection) = db.createStatement().use { sql ->
        sql.execute("""CREATE TABLE IF NOT EXISTS book_review_sources(
            book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,approval TEXT NOT NULL,
            revision INTEGER NOT NULL,payload TEXT,PRIMARY KEY(book,lesson,mode))""")
        // Posed facts are monotone: replacing/withdrawing a source must not make an old question new.
        sql.execute("""CREATE TABLE IF NOT EXISTS book_review_source_posed(
            course TEXT NOT NULL,question TEXT NOT NULL,version TEXT NOT NULL,fingerprint TEXT,
            PRIMARY KEY(course,question,version))""")
    }

    override fun input(review: LocalBookReview): BookReviewVariantInput? = database().use { db ->
        val payload = db.prepareStatement("SELECT payload FROM book_review_sources WHERE book=? AND lesson=? AND mode=?").use { sql ->
            sql.setString(1, review.book); sql.setString(2, review.lesson); sql.setString(3, review.mode.name)
            sql.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        } ?: return null
        val source = BookReviewSourceJson.decode(Json.parseToJsonElement(payload).jsonObject)
        val origin = source.origin
        val used = db.prepareStatement("SELECT question,version,fingerprint FROM book_review_source_posed WHERE course=?").use { sql ->
            sql.setString(1, origin.courseId)
            sql.executeQuery().use { rows -> buildSet {
                while (rows.next()) add(QueueQuestion(rows.getString(1), rows.getString(2).ifEmpty { null }, rows.getString(3)))
            } }
        }
        source.copy(usedQuestions = used)
    }

    /** Caller validates the live original and commits this together with the posed facts. */
    fun update(db: Connection, review: LocalBookReview, update: BookReviewSourceUpdate) {
        require(update.approvalId.isNotBlank() && update.revision > 0) { "来源须有明确批准记录和版本。" }
        val payload = update.input?.let { BookReviewSourceJson.encode(it).toString() }
        db.prepareStatement("SELECT approval,revision,payload FROM book_review_sources WHERE book=? AND lesson=? AND mode=?").use { sql ->
            sql.setString(1, review.book); sql.setString(2, review.lesson); sql.setString(3, review.mode.name)
            sql.executeQuery().use { rows -> if (rows.next()) {
                require(rows.getString(1) == update.approvalId) { "来源批准记录不一致。" }
                val oldRevision = rows.getLong(2)
                require(update.revision > oldRevision || update.revision == oldRevision && rows.getString(3) == payload) {
                    "来源快照已更新，请使用最新版本。"
                }
            } }
        }
        db.prepareStatement("INSERT OR REPLACE INTO book_review_sources(book,lesson,mode,approval,revision,payload) VALUES(?,?,?,?,?,?)").use { sql ->
            sql.setString(1, review.book); sql.setString(2, review.lesson); sql.setString(3, review.mode.name)
            sql.setString(4, update.approvalId); sql.setLong(5, update.revision); sql.setString(6, payload)
            check(sql.executeUpdate() == 1)
        }
        update.input?.let { input ->
            db.prepareStatement("INSERT OR IGNORE INTO book_review_source_posed(course,question,version,fingerprint) VALUES(?,?,?,?)").use { sql ->
                for (q in input.usedQuestions + input.origin.question) {
                    require(q.id.isNotBlank()) { "已做题须有明确身份。" }
                    sql.setString(1, input.origin.courseId); sql.setString(2, q.id); sql.setString(3, q.version.orEmpty())
                    sql.setString(4, q.fingerprint)
                    sql.addBatch()
                }
                sql.executeBatch()
            }
        }
    }

}

/** One codec for persisted snapshots and explicit source receipts; no inferred/default identities. */
internal object BookReviewSourceJson {
    fun encode(input: BookReviewVariantInput) = buildJsonObject {
        put("origin", originJson(input.origin))
        put("offers", JsonArray(input.offers.map(::offerJson)))
        put("usedQuestions", Json.encodeToJsonElement(input.usedQuestions.sortedWith(
            compareBy({ q -> q.id }, { q -> q.version.orEmpty() }, { q -> q.fingerprint.orEmpty() }))))
    }
    fun decode(source: JsonObject) = BookReviewVariantInput(origin(source.getValue("origin").jsonObject),
        source.getValue("offers").jsonArray.map { offer(it.jsonObject) },
        Json.decodeFromJsonElement<Set<QueueQuestion>>(source.getValue("usedQuestions")))

    // Desktop already uses explicit JSON codecs; shared queue models have generated serializers.
    private fun practiceJson(p: Practice) = buildJsonObject {
        put("prompt", p.prompt); put("reference", p.reference); put("answer", p.answer)
        put("options", JsonArray(p.options.map(::JsonPrimitive)))
    }
    private fun practice(j: JsonObject) = Practice(j.getValue("prompt").jsonPrimitive.content,
        j.getValue("reference").jsonPrimitive.content, j.getValue("answer").jsonPrimitive.content,
        j.getValue("options").jsonArray.map { it.jsonPrimitive.content })
    private fun originJson(o: BookReviewOrigin) = buildJsonObject {
        put("course", o.courseId); put("kp", o.kpId); put("question", Json.encodeToJsonElement(o.question))
        put("attempt", o.attemptId); put("practice", practiceJson(o.practice)); put("answer", o.answer); put("guessed", o.guessed)
    }
    private fun origin(j: JsonObject) = BookReviewOrigin(j.getValue("course").jsonPrimitive.content,
        j.getValue("kp").jsonPrimitive.content, Json.decodeFromJsonElement(j.getValue("question")),
        j.getValue("attempt").jsonPrimitive.content, practice(j.getValue("practice").jsonObject),
        j.getValue("answer").jsonPrimitive.content, j.getValue("guessed").jsonPrimitive.boolean)
    private fun offerJson(o: BookReviewVariantOffer) = buildJsonObject {
        put("candidate", Json.encodeToJsonElement(o.candidate)); put("original", Json.encodeToJsonElement(o.originalQuestion))
        put("attempt", o.originalAttemptId); put("book", o.book); put("lesson", o.lesson)
        put("practice", practiceJson(o.practice)); put("approved", o.approved)
    }
    private fun offer(j: JsonObject) = BookReviewVariantOffer(
        Json.decodeFromJsonElement<VariantCandidate>(j.getValue("candidate")), Json.decodeFromJsonElement(j.getValue("original")),
        j.getValue("attempt").jsonPrimitive.content, j.getValue("book").jsonPrimitive.content,
        j.getValue("lesson").jsonPrimitive.content, practice(j.getValue("practice").jsonObject), j.getValue("approved").jsonPrimitive.boolean)
}
