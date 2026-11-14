package com.coach.desktop.books

import com.coach.domain.reviewqueue.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.sql.Connection

/** Supplied by an approved source adapter, never inferred from local text or a shared KP label. */
internal data class BookReviewOrigin(
    val courseId: String, val kpId: String, val question: QueueQuestion, val attemptId: String,
    val practice: Practice, val answer: String, val guessed: Boolean = false,
)

internal data class BookReviewVariantOffer(
    val candidate: VariantCandidate, val originalQuestion: QueueQuestion, val originalAttemptId: String,
    val book: String, val lesson: String, val practice: Practice, val approved: Boolean = false,
)

internal data class BookReviewVariantInput(
    val origin: BookReviewOrigin, val offers: List<BookReviewVariantOffer>,
    /** All already posed questions in this course, including attempts outside this local queue. */
    val usedQuestions: Set<QueueQuestion>,
)

internal fun interface BookReviewVariantPort {
    fun input(review: LocalBookReview): BookReviewVariantInput?
}

internal data class BookReviewVariant(val origin: BookReviewOrigin, val allocation: QueueVariant)

/** Local persistence only. Selection stays in the shared reviewqueue allocator. */
internal class BookReviewVariants(private val port: BookReviewVariantPort,
    private val sourceText: (LocalBookReview) -> String) {
    fun initialize(db: Connection) {
        db.createStatement().use { it.execute("""CREATE TABLE IF NOT EXISTS book_review_variant_attempts(
            book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,attempt TEXT NOT NULL,
            origin TEXT NOT NULL,allocation TEXT NOT NULL,practice TEXT NOT NULL,answer TEXT NOT NULL,
            PRIMARY KEY(book,lesson,mode,attempt))""") }
    }

    private data class Saved(val origin: BookReviewOrigin, val allocation: QueueVariant,
        val practice: Practice, val answer: PracticeAnswer)

    private fun history(db: Connection, review: LocalBookReview): List<Saved> =
        db.prepareStatement("SELECT * FROM book_review_variant_attempts WHERE book=? AND lesson=? AND mode=? ORDER BY rowid").use { sql ->
            sql.setString(1, review.book); sql.setString(2, review.lesson); sql.setString(3, review.mode.name)
            sql.executeQuery().use { rows -> buildList { while (rows.next()) add(Saved(
                origin(Json.parseToJsonElement(rows.getString("origin")).jsonObject),
                Json.decodeFromString<QueueVariant>(rows.getString("allocation")),
                practice(Json.parseToJsonElement(rows.getString("practice")).jsonObject),
                answer(Json.parseToJsonElement(rows.getString("answer")).jsonObject))) } }
        }

    private fun input(review: LocalBookReview, previous: Saved?): BookReviewVariantInput? {
        val input = port.input(review.copy(practice = review.originalPractice, variant = null)) ?: return null
        val o = input.origin
        if (review.mode != QuestionMode.CHOICE || o.courseId.isBlank() || o.kpId.isBlank() ||
            o.question.id.isBlank() || o.question.version.isNullOrBlank() || o.attemptId.isBlank() ||
            o.practice != review.originalPractice || o.answer != review.originalAnswer || o.guessed != review.originalGuessed ||
            (previous != null && o != previous.origin)) return null
        return input
    }

    private fun offers(review: LocalBookReview, input: BookReviewVariantInput): List<BookReviewVariantOffer> {
        val o = input.origin
        return input.offers.filter { offer ->
            val p = offer.practice
            offer.approved && offer.originalQuestion == o.question && offer.originalAttemptId == o.attemptId &&
                offer.book == review.book && offer.lesson == review.lesson &&
                !offer.candidate.question.version.isNullOrBlank() &&
                offer.candidate.courseId == o.courseId && offer.candidate.kpId == o.kpId &&
                offer.candidate.agentRunId == null && offer.candidate.keyVerified &&
                offer.candidate.availability == QueueAvailability.VALID &&
                p.prompt.isNotBlank() && p.reference.isNotBlank() && sourceText(review).contains(p.reference) &&
                p.options.size >= 2 && p.options.distinct().size == p.options.size && p.answer in p.options
        }.groupBy { it.candidate.question }.values.filter { it.distinct().size == 1 }.map { it.first() }
    }

    fun project(db: Connection, review: LocalBookReview): LocalBookReview {
        val history = history(db, review)
        val saved = history.lastOrNull()
        val input = input(review, saved)
        val offers = input?.let { offers(review, it) }.orEmpty()
        val current = saved?.takeIf { old -> offers.any {
            it.candidate.question == old.allocation.question && it.practice == old.practice &&
                it.candidate.variantGroupId == old.allocation.variantGroupId
        } }
        val next = input?.let { allocation(db, review, it, history) }
        return if (current == null) review.copy(practice = review.originalPractice, variant = null, canAnswer = saved == null, variantMessage = if (next == null)
            "新变式未备好：缺少明确关联、有效版本或可查来源。" else "有已关联的新变式，可以继续练习。",
            canStartVariant = !review.completed && next != null)
        else review.copy(practice = current.practice,
            answer = if (review.answer == PracticeAnswer() && current.answer.revealed) PracticeAnswer() else current.answer,
            canAnswer = !(review.answer == PracticeAnswer() && current.answer.revealed),
            variant = BookReviewVariant(current.origin, current.allocation),
            variantMessage = if (review.answer == PracticeAnswer() && current.answer.revealed)
                "这题已到期；请继续练新变式，旧作答已保留。" else "正在练习新变式 · ${current.allocation.question.id}",
            canStartVariant = !review.completed && current.answer.revealed && next != null)
    }

    private fun allocation(db: Connection, review: LocalBookReview, input: BookReviewVariantInput,
        history: List<Saved>): Pair<QueueVariant, Practice>? {
        val o = input.origin
        // Scope-wide persisted history also excludes variants consumed by a different local item.
        val used = db.createStatement().use { sql -> sql.executeQuery("SELECT allocation FROM book_review_variant_attempts").use { rows ->
            buildSet { while (rows.next()) {
                val v = Json.decodeFromString<QueueVariant>(rows.getString(1))
                if (v.courseId == o.courseId) add(v.question)
            } }
        } } + input.usedQuestions
        val offered = offers(review, input).filter { used.none { usedQuestion -> usedQuestion.id == it.candidate.question.id } }
        val entry = QueueEntry("local:${review.book}:${review.lesson}:${review.mode}", o.kpId, o.question,
            sources = buildList {
                if (review.originalAnswer != review.originalPractice.answer) add(QueueSource(o.attemptId, QueueReason.WRONG, o.attemptId))
                if (review.originalGuessed) add(QueueSource(o.attemptId, QueueReason.GUESSED, o.attemptId))
                if (isEmpty()) add(QueueSource(o.attemptId, QueueReason.DUE, o.attemptId))
            },
            dueDay = review.dueDay ?: 0, variants = history.map { it.allocation })
        val allocation = ReviewQueueCandidate(QueueScope(o.courseId), listOf(entry))
            .allocateVariants(VariantCandidatePort { _, _, _ -> offered.map { it.candidate } })
            .selections.singleOrNull()?.variant
        return allocation?.let { it to offered.single { offer -> offer.candidate.question == it.question }.practice }
    }

    fun start(db: Connection, review: LocalBookReview): LocalBookReview {
        val history = history(db, review)
        val previous = history.lastOrNull()
        val input = input(review, previous) ?: return project(db, review)
        if (previous != null && !previous.answer.revealed && project(db, review).variant != null)
            return project(db, review)
        val selected = allocation(db, review, input, history)
        val allocation = selected?.first
        if (allocation == null) return project(db, review).copy(canStartVariant = false,
            variantMessage = "新变式未备好：没有尚未做过的有效关联题。")
        val practice = selected.second
        db.prepareStatement("INSERT INTO book_review_variant_attempts(book,lesson,mode,attempt,origin,allocation,practice,answer) VALUES(?,?,?,?,?,?,?,?)").use { sql ->
            sql.setString(1, review.book); sql.setString(2, review.lesson); sql.setString(3, review.mode.name)
            sql.setString(4, allocation.attemptId); sql.setString(5, originJson(input.origin).toString())
            sql.setString(6, Json.encodeToString(allocation)); sql.setString(7, practiceJson(practice).toString())
            sql.setString(8, answerJson(PracticeAnswer()).toString()); check(sql.executeUpdate() == 1)
        }
        db.prepareStatement("UPDATE book_reviews SET answer_text='',revealed=0,understood=NULL,answer_guessed=0,completed=0 WHERE book=? AND lesson=? AND mode=?").use { sql ->
            sql.setString(1, review.book); sql.setString(2, review.lesson); sql.setString(3, review.mode.name)
            check(sql.executeUpdate() == 1)
        }
        return project(db, review)
    }

    /** Shares the existing answer/schedule transaction; stale or withdrawn allocations cannot be saved. */
    fun save(db: Connection, review: LocalBookReview, answer: PracticeAnswer) {
        val v = review.variant
        if (v == null) {
            require(history(db, review).isEmpty()) { "变式关联暂不可用，原题仅供查看。" }
            return
        }
        val current = project(db, review.copy(practice = review.originalPractice))
        require(current.variant == v && current.practice == review.practice) { "这道变式已变更，请重新打开复习。" }
        db.prepareStatement("UPDATE book_review_variant_attempts SET answer=? WHERE book=? AND lesson=? AND mode=? AND attempt=?").use { sql ->
            sql.setString(1, answerJson(answer).toString()); sql.setString(2, review.book)
            sql.setString(3, review.lesson); sql.setString(4, review.mode.name); sql.setString(5, v.allocation.attemptId)
            check(sql.executeUpdate() == 1)
        }
    }

    private fun practiceJson(p: Practice) = buildJsonObject {
        put("prompt", p.prompt); put("reference", p.reference); put("answer", p.answer)
        put("options", JsonArray(p.options.map(::JsonPrimitive)))
    }
    private fun practice(j: JsonObject) = Practice(j.getValue("prompt").jsonPrimitive.content,
        j.getValue("reference").jsonPrimitive.content, j.getValue("answer").jsonPrimitive.content,
        j.getValue("options").jsonArray.map { it.jsonPrimitive.content })
    private fun answerJson(a: PracticeAnswer) = buildJsonObject {
        put("text", a.text); put("revealed", a.revealed); put("guessed", a.guessed)
        put("understood", a.understood?.let(::JsonPrimitive) ?: JsonNull)
    }
    private fun answer(j: JsonObject) = PracticeAnswer(j.getValue("text").jsonPrimitive.content,
        j.getValue("revealed").jsonPrimitive.boolean, j["understood"]?.jsonPrimitive?.booleanOrNull,
        j.getValue("guessed").jsonPrimitive.boolean)
    private fun originJson(o: BookReviewOrigin) = buildJsonObject {
        put("course", o.courseId); put("kp", o.kpId); put("question", Json.encodeToJsonElement(o.question))
        put("attempt", o.attemptId); put("practice", practiceJson(o.practice)); put("answer", o.answer); put("guessed", o.guessed)
    }
    private fun origin(j: JsonObject) = BookReviewOrigin(j.getValue("course").jsonPrimitive.content,
        j.getValue("kp").jsonPrimitive.content, Json.decodeFromJsonElement(j.getValue("question")),
        j.getValue("attempt").jsonPrimitive.content, practice(j.getValue("practice").jsonObject),
        j.getValue("answer").jsonPrimitive.content, j.getValue("guessed").jsonPrimitive.boolean)
}
