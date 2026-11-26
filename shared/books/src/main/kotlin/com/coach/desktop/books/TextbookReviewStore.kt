package com.coach.desktop.books

import com.coach.domain.fsrs.Fsrs5
import com.coach.domain.fsrs.FsrsConfig
import com.coach.domain.fsrs.FsrsDailyQueue
import com.coach.domain.fsrs.FsrsQueueCard
import com.coach.domain.fsrs.FsrsRating
import com.coach.domain.fsrs.FsrsState
import com.coach.domain.fsrs.LegacyAnswer
import com.coach.domain.fsrs.LegacyCardRecord
import com.coach.domain.fsrs.LegacyStreakMigration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.time.LocalDate

/** Today's review in one order, capped like the product promises; deferred cards stay due for tomorrow. */
internal data class LocalReviewQueue(val cards: List<LocalBookReview>, val deferred: Int) {
    val summary: String get() = if (deferred == 0) "今日 ${cards.size} 题" else "今日优先 ${cards.size} 题 · 另有 $deferred 题顺延"
}

/** Local calendar-day FSRS-5 policy: an independent correct answer is GOOD, anything else AGAIN.
 * Wrong/guessed answers stay in today's same queue. No server identity or exam policy is inferred.
 * Rows from the retired 1/3/7 scheme are mapped once by [LegacyStreakMigration] and never become due later.
 */
internal class TextbookReviewStore(
    private val today: () -> Long,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    fun migrate(db: Connection, manageTransaction: Boolean = true) {
        if (manageTransaction) db.autoCommit = false
        try {
            fun add(table: String, name: String, declaration: String): Boolean {
                val exists = db.createStatement().use { s -> s.executeQuery("PRAGMA table_info($table)").use { r ->
                    var found = false
                    while (r.next()) if (r.getString("name") == name) found = true
                    found
                } }
                if (!exists) db.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $name $declaration") }
                return !exists
            }
            add("answers", "guessed", "INTEGER NOT NULL DEFAULT 0")
            add("book_reviews", "guessed", "INTEGER NOT NULL DEFAULT 0")
            if (add("book_reviews", "sources", "INTEGER NOT NULL DEFAULT 0")) db.createStatement().use {
                it.executeUpdate("UPDATE book_reviews SET sources=CASE mode WHEN 'CHOICE' THEN 1 WHEN 'SHORT_ANSWER' THEN 4 ELSE 0 END")
            }
            add("book_reviews", "due_day", "INTEGER")
            add("book_reviews", "streak", "INTEGER NOT NULL DEFAULT 0")
            db.prepareStatement("UPDATE book_reviews SET due_day=? WHERE due_day IS NULL").use {
                it.setLong(1, today() + 1); it.executeUpdate()
            }
            val hadHistory = db.createStatement().use { s -> s.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='book_review_history'").use { it.next() } }
            db.createStatement().use { it.execute("""CREATE TABLE IF NOT EXISTS book_review_history(
                id INTEGER PRIMARY KEY AUTOINCREMENT,book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,
                kind TEXT NOT NULL,text TEXT NOT NULL,revealed INTEGER NOT NULL,understood INTEGER,
                guessed INTEGER NOT NULL,answered_day INTEGER)""") }
            if (!hadHistory) db.createStatement().use {
                // The old schema has no timestamps. Preserve facts without inventing an answer date.
                it.executeUpdate("""INSERT INTO book_review_history(book,lesson,mode,kind,text,revealed,understood,guessed)
                    SELECT book,lesson,mode,'MIGRATED',wrong_text,1,CASE WHEN mode='SHORT_ANSWER' THEN 0 ELSE NULL END,0
                    FROM book_reviews ORDER BY rowid""")
                it.executeUpdate("""INSERT INTO book_review_history(book,lesson,mode,kind,text,revealed,understood,guessed)
                    SELECT book,lesson,mode,'MIGRATED_REVIEW',answer_text,revealed,understood,guessed FROM book_reviews
                    WHERE revealed=1 AND (mode='CHOICE' OR understood IS NOT NULL) ORDER BY rowid""")
            }
            db.createStatement().use { it.execute(BookSchemaMigration.BOOK_FSRS_CARDS_DDL) }
            seedLegacyCards(db)
            if (manageTransaction) db.commit()
        } catch (failure: Exception) {
            if (manageTransaction) db.rollback()
            throw failure
        } finally {
            if (manageTransaction) db.autoCommit = true
        }
    }

    private val fsrsConfig = FsrsConfig()
    private val dailyQueue = FsrsDailyQueue(
        defaultCap = ExamCountdownPlanner.NORMAL_FSRS_CAP,
        preExamCap = ExamCountdownPlanner.PRE_EXAM_FSRS_CAP,
        preExamWindowDays = ExamCountdownPlanner.PRE_EXAM_WINDOW_DAYS,
    )

    private fun rating(correct: Boolean) = if (correct) FsrsRating.GOOD else FsrsRating.AGAIN

    fun fsrsState(db: Connection, book: String, lesson: String, mode: QuestionMode): FsrsState? =
        db.prepareStatement("SELECT * FROM book_fsrs_cards WHERE book=? AND lesson=? AND mode=?").use { s ->
            s.setString(1, book); s.setString(2, lesson); s.setString(3, mode.name)
            s.executeQuery().use { r -> if (r.next()) FsrsState(
                dueDay = r.getLong("due_day"),
                stability = r.getDouble("stability").let { if (r.wasNull()) null else it },
                difficulty = r.getDouble("difficulty").let { if (r.wasNull()) null else it },
                lastReviewDay = r.getLong("last_review_day").let { if (r.wasNull()) null else it },
                reps = r.getInt("reps"), lapses = r.getInt("lapses"),
            ) else null }
        }

    private fun writeFsrs(db: Connection, book: String, lesson: String, mode: QuestionMode, state: FsrsState, origin: String) {
        db.prepareStatement("""INSERT INTO book_fsrs_cards(book,lesson,mode,stability,difficulty,last_review_day,due_day,
            reps,lapses,origin,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(book,lesson,mode) DO UPDATE SET stability=excluded.stability,difficulty=excluded.difficulty,
            last_review_day=excluded.last_review_day,due_day=excluded.due_day,reps=excluded.reps,lapses=excluded.lapses,
            updated_at=excluded.updated_at""").use { s ->
            s.setString(1, book); s.setString(2, lesson); s.setString(3, mode.name)
            val stability = state.stability; val difficulty = state.difficulty; val last = state.lastReviewDay
            if (stability == null) s.setNull(4, Types.REAL) else s.setDouble(4, stability)
            if (difficulty == null) s.setNull(5, Types.REAL) else s.setDouble(5, difficulty)
            if (last == null) s.setNull(6, Types.BIGINT) else s.setLong(6, last)
            s.setLong(7, state.dueDay); s.setInt(8, state.reps); s.setInt(9, state.lapses)
            s.setString(10, origin); s.setLong(11, System.currentTimeMillis()); s.executeUpdate()
        }
    }

    /** Maps one retired 1/3/7 row: dated answers are replayed, otherwise the streak is assumed. */
    private fun seedLegacy(db: Connection, old: LocalBookReview): FsrsState {
        val answers = old.history.mapNotNull { attempt ->
            attempt.day?.let { LegacyAnswer(it, old.practice.independentlyCorrect(old.mode, attempt.answer)) }
        }
        val migrated = LegacyStreakMigration.migrate(
            LegacyCardRecord("${old.book}/${old.lesson}/${old.mode}", old.book, old.lesson, old.dueDay,
                old.confidentStreak, answers = answers), fsrsConfig)
        // The legacy row itself stays byte-identical; its stored due day still decides when it next shows.
        writeFsrs(db, old.book, old.lesson, old.mode, migrated.state, migrated.path.name)
        return migrated.state
    }

    private fun seedLegacyCards(db: Connection) {
        val missing = db.createStatement().use { s -> s.executeQuery("""SELECT r.* FROM book_reviews r
            WHERE NOT EXISTS(SELECT 1 FROM book_fsrs_cards f WHERE f.book=r.book AND f.lesson=r.lesson AND f.mode=r.mode)
            ORDER BY r.rowid""").use { r -> buildList { while (r.next()) add(read(db, r)) } } }
        for (row in missing) seedLegacy(db, row)
    }

    /** Applies one committed answer to the card's FSRS state and returns the new state. */
    private fun schedule(db: Connection, old: LocalBookReview?, book: String, lesson: String, mode: QuestionMode,
        correct: Boolean): FsrsState {
        val prior = fsrsState(db, book, lesson, mode) ?: old?.let { seedLegacy(db, it) } ?: Fsrs5.newCard(today())
        val day = today()
        val grade = rating(correct)
        val next = Fsrs5.review(prior, grade, day, fsrsConfig)
        withLearningFact(db,"card",{
            buildJsonObject {
                put("algorithm","fsrs5-default-v1")
                put("day",day)
                put("rating",grade.value)
            }
        }) { writeFsrs(db, book, lesson, mode, next, "NEW") }
        return next
    }

    /** Today's due cards ranked by real `NeedScore` (with eligible 72h T2 cohorts silently mixed in). */
    fun queue(db: Connection): LocalReviewQueue {
        val day = today()
        val now = nowMs()
        MasteryStore.enqueueEligibleT2Reviews(db, day, now)
        val due = pending(db)
        if (due.isEmpty()) return LocalReviewQueue(emptyList(), 0)
        val byId = due.associateBy { "${it.book}/${it.lesson}/${it.mode}" }
        val scored = due.map { row ->
            val cardId = "${row.book}/${row.lesson}/${row.mode}"
            val state = fsrsState(db, row.book, row.lesson, row.mode) ?: Fsrs5.newCard(row.dueDay)
            val effectiveState = state.copy(dueDay = minOf(state.dueDay, day))
            val retrievability = Fsrs5.retrievability(effectiveState, day)
            val need = MasteryStore.needScoreForReviewCard(db, row.book, row.lesson, retrievability, now)
            val waitingDays = (day - row.dueDay).coerceAtLeast(0L)
            Triple(
                FsrsQueueCard(cardId, row.book, row.lesson, need.coerceAtLeast(0.01), effectiveState),
                need,
                waitingDays,
            )
        }
        val todayDate = LocalDate.ofEpochDay(day)
        val nearestExamDay = ExamCountdownPlanner.nearestUpcomingExamEpochDay(db, todayDate)
        val cap = dailyQueue.capFor(day, nearestExamDay)
        val ordered = scored.sortedWith(
            compareByDescending<Triple<FsrsQueueCard, Double, Long>> { it.second }
                .thenByDescending { it.third }
                .thenBy { it.first.cardId },
        )
        val priority = ordered.take(cap).mapNotNull { byId[it.first.cardId] }
        val deferred = (ordered.size - priority.size).coerceAtLeast(0)
        return LocalReviewQueue(priority, deferred)
    }

    private fun sources(mode: QuestionMode, practice: Practice, answer: PracticeAnswer): Int {
        var bits = if (answer.guessed) LocalReviewSource.GUESSED.bit else 0
        if (mode == QuestionMode.CHOICE && answer.text != practice.answer && (!answer.guessed || answer.text in practice.options)) bits = bits or LocalReviewSource.WRONG.bit
        if (mode == QuestionMode.SHORT_ANSWER && answer.understood == false) bits = bits or LocalReviewSource.SELF_CHECK.bit
        return bits
    }

    fun legacyChoice(book: Textbook, lesson: BookLesson, answer: PracticeAnswer): Practice? =
        LegacyReviewChoice.reconstruct(book, lesson)?.takeIf { answer.text in it.options }

    fun recordLesson(db: Connection, book: Textbook, lesson: BookLesson, mode: QuestionMode,
        practice: Practice, answer: PracticeAnswer, migrated: Boolean = false) {
        val old = find(db, book.id, lesson.id, mode)
        val correct = (old?.practice ?: practice).independentlyCorrect(mode, answer)
        val streak = if (correct) (old?.confidentStreak ?: 0) + 1 else 0
        val next = schedule(db, old, book.id, lesson.id, mode, correct)
        db.prepareStatement("""INSERT INTO book_reviews(book,lesson,mode,book_title,lesson_title,prompt,reference,
            solution,options,wrong_text,completed,sources,due_day,streak,source_page) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(book,lesson,mode) DO UPDATE SET
            answer_text=CASE WHEN book_reviews.completed=1 AND excluded.completed=0 THEN '' ELSE book_reviews.answer_text END,
            revealed=CASE WHEN book_reviews.completed=1 AND excluded.completed=0 THEN 0 ELSE book_reviews.revealed END,
            understood=CASE WHEN book_reviews.completed=1 AND excluded.completed=0 THEN NULL ELSE book_reviews.understood END,
            guessed=CASE WHEN book_reviews.completed=1 AND excluded.completed=0 THEN 0 ELSE book_reviews.guessed END,
            completed=excluded.completed,sources=book_reviews.sources | excluded.sources,
            due_day=excluded.due_day,streak=excluded.streak""").use { s ->
            s.setString(1, book.id); s.setString(2, lesson.id); s.setString(3, mode.name)
            s.setString(4, book.title); s.setString(5, lesson.title); s.setString(6, practice.prompt)
            s.setString(7, practice.reference); s.setString(8, practice.answer)
            s.setString(9, Json.encodeToString(practice.options)); s.setString(10, answer.text)
            s.setInt(11, if (correct) 1 else 0); s.setInt(12, sources(mode, old?.practice ?: practice, answer))
            s.setLong(13, next.dueDay); s.setInt(14, streak)
            s.setObject(15, practice.sourcePage); s.executeUpdate()
        }
        append(db, book.id, lesson.id, mode, answer, if (migrated) "MIGRATED" else "LESSON", if (migrated) null else today())
    }

    private fun append(db: Connection, book: String, lesson: String, mode: QuestionMode,
        answer: PracticeAnswer, kind: String, day: Long?) {
        db.prepareStatement("""INSERT INTO book_review_history(book,lesson,mode,kind,text,revealed,understood,guessed,answered_day)
            VALUES(?,?,?,?,?,?,?,?,?)""").use { s ->
            s.setString(1, book); s.setString(2, lesson); s.setString(3, mode.name); s.setString(4, kind)
            s.setString(5, answer.text); s.setInt(6, if (answer.revealed) 1 else 0)
            if (answer.understood == null) s.setNull(7, Types.INTEGER) else s.setInt(7, if (answer.understood) 1 else 0)
            s.setInt(8, if (answer.guessed) 1 else 0)
            if (day == null) s.setNull(9, Types.BIGINT) else s.setLong(9, day)
            s.executeUpdate()
        }
    }

    private fun history(db: Connection, book: String, lesson: String, mode: QuestionMode): List<LocalReviewAttempt> =
        db.prepareStatement("SELECT * FROM book_review_history WHERE book=? AND lesson=? AND mode=? ORDER BY rowid").use { s ->
            s.setString(1, book); s.setString(2, lesson); s.setString(3, mode.name)
            s.executeQuery().use { r -> buildList {
                while (r.next()) add(LocalReviewAttempt(size + 1, r.getString("kind"), r.answer("text"),
                    r.getLong("answered_day").let { if (r.wasNull()) null else it }))
            } }
        }

    private fun ResultSet.answer(text: String) = PracticeAnswer(getString(text), getInt("revealed") == 1,
        getInt("understood").let { if (wasNull()) null else it == 1 }, getInt("guessed") == 1)

    private fun read(db: Connection, r: ResultSet): LocalBookReview {
        val book = r.getString("book"); val lesson = r.getString("lesson"); val mode = QuestionMode.valueOf(r.getString("mode"))
        val due = r.getLong("due_day")
        val completed = r.getInt("completed") == 1
        val overdue = due <= today()
        val bits = r.getInt("sources") or if (overdue) LocalReviewSource.DUE.bit else 0
        return LocalBookReview(book, lesson, mode, r.getString("book_title"), r.getString("lesson_title"),
            Practice(r.getString("prompt"), r.getString("reference"), r.getString("solution"), Json.decodeFromString(r.getString("options")),
                r.getInt("source_page").takeUnless { r.wasNull() }),
            r.getString("wrong_text"), if (completed && overdue) PracticeAnswer() else r.answer("answer_text"),
            completed && !overdue, LocalReviewSource.entries.filter { bits and it.bit != 0 }.toSet(), due,
            history(db, book, lesson, mode), r.getInt("streak"))
    }

    fun find(db: Connection, book: String, lesson: String, mode: QuestionMode): LocalBookReview? =
        db.prepareStatement("SELECT * FROM book_reviews WHERE book=? AND lesson=? AND mode=?").use { s ->
            s.setString(1, book); s.setString(2, lesson); s.setString(3, mode.name)
            s.executeQuery().use { r -> if (r.next()) read(db, r) else null }
        }

    fun pending(db: Connection): List<LocalBookReview> = db.prepareStatement(
        "SELECT * FROM book_reviews WHERE completed=0 OR due_day<=? ORDER BY due_day,rowid").use { s ->
        s.setLong(1, today())
        s.executeQuery().use { r -> buildList { while (r.next()) add(read(db, r)) } }
    }

    fun allForBook(db: Connection, bookId: String): List<LocalBookReview> = db.prepareStatement(
        "SELECT * FROM book_reviews WHERE book=? ORDER BY rowid").use { s ->
        s.setString(1, bookId)
        s.executeQuery().use { r -> buildList { while (r.next()) add(read(db, r)) } }
    }

    fun saveRetry(db: Connection, entry: LocalBookReview, answer: PracticeAnswer) {
        val old = checkNotNull(find(db, entry.book, entry.lesson, entry.mode)) { "这道复习题已不在书架中。" }
        val committed = answer.committed(old.mode)
        val newAttempt = committed && answer != old.answer
        val correct = old.practice.independentlyCorrect(old.mode, answer)
        val streak = if (newAttempt) { if (correct) old.confidentStreak + 1 else 0 } else old.confidentStreak
        val due = if (newAttempt) schedule(db, old, old.book, old.lesson, old.mode, correct).dueDay else old.dueDay
        db.prepareStatement("""UPDATE book_reviews SET answer_text=?,revealed=?,understood=?,guessed=?,completed=?,
            sources=sources | ?,due_day=?,streak=? WHERE book=? AND lesson=? AND mode=?""").use { s ->
            s.setString(1, answer.text); s.setInt(2, if (answer.revealed) 1 else 0)
            if (answer.understood == null) s.setNull(3, Types.INTEGER) else s.setInt(3, if (answer.understood) 1 else 0)
            s.setInt(4, if (answer.guessed) 1 else 0); s.setInt(5, if (correct) 1 else 0)
            s.setInt(6, old.sources.fold(if (committed) sources(old.mode, old.practice, answer) else 0) { bits, source -> bits or source.bit })
            s.setLong(7, due); s.setInt(8, streak)
            s.setString(9, old.book); s.setString(10, old.lesson); s.setString(11, old.mode.name)
            check(s.executeUpdate() == 1) { "无法保存复习作答。" }
        }
        if (newAttempt) {
            append(db, old.book, old.lesson, old.mode, answer, "REVIEW", today())
            syncReviewMastery(db, old, correct, answer.guessed, fsrsState(db, old.book, old.lesson, old.mode))
        }
    }

    /** Explicit single-card self-rating: 没记住 -> AGAIN, 记住了 -> GOOD (guessed cards never count as independent GOOD). */
    fun saveRating(db: Connection, entry: LocalBookReview, rating: FsrsRating, currentAnswer: PracticeAnswer) {
        val old = checkNotNull(find(db, entry.book, entry.lesson, entry.mode)) { "这道复习题已不在书架中。" }
        val remembered = rating != FsrsRating.AGAIN && !currentAnswer.guessed
        val recordedText = currentAnswer.text.ifBlank {
            if (old.mode == QuestionMode.CHOICE && remembered && old.practice.answer.isNotBlank()) old.practice.answer
            else if (remembered) "记住了" else "没记住"
        }
        val recordedAnswer = currentAnswer.copy(
            text = recordedText,
            revealed = true,
            understood = remembered,
        )
        val streak = if (remembered) old.confidentStreak + 1 else 0
        val next = schedule(db, old, old.book, old.lesson, old.mode, remembered)
        val extraSource = when {
            currentAnswer.guessed -> LocalReviewSource.GUESSED.bit
            !remembered -> if (old.mode == QuestionMode.CHOICE) LocalReviewSource.WRONG.bit else LocalReviewSource.SELF_CHECK.bit
            else -> 0
        }
        val mergedSources = old.sources.fold(extraSource) { bits, source -> bits or source.bit }
        db.prepareStatement("""UPDATE book_reviews SET answer_text=?,revealed=?,understood=?,guessed=?,completed=?,
            sources=?,due_day=?,streak=? WHERE book=? AND lesson=? AND mode=?""").use { s ->
            s.setString(1, recordedAnswer.text); s.setInt(2, 1)
            s.setInt(3, if (remembered) 1 else 0)
            s.setInt(4, if (recordedAnswer.guessed) 1 else 0)
            s.setInt(5, if (remembered) 1 else 0)
            s.setInt(6, mergedSources)
            s.setLong(7, next.dueDay); s.setInt(8, streak)
            s.setString(9, old.book); s.setString(10, old.lesson); s.setString(11, old.mode.name)
            check(s.executeUpdate() == 1) { "无法保存复习评分。" }
        }
        append(db, old.book, old.lesson, old.mode, recordedAnswer, "REVIEW", today())
        syncReviewMastery(db, old, remembered, recordedAnswer.guessed, next)
    }

    private fun syncReviewMastery(
        db: Connection,
        review: LocalBookReview,
        correct: Boolean,
        guessed: Boolean,
        fsrs: FsrsState?,
    ) {
        val now = nowMs()
        val score = if (correct && !guessed) 1.0 else 0.0
        if (review.lesson.startsWith(MasteryStore.T2_LESSON_PREFIX)) {
            val cohortId = review.lesson.removePrefix(MasteryStore.T2_LESSON_PREFIX)
            val cohort = MasteryStore.recordLearningGainT2(db, cohortId, score, guessed, now)
            if (cohort != null) {
                MasteryStore.recordEvidence(
                    db = db,
                    bookId = review.book,
                    tocNodeId = cohort.chapterId,
                    page = 1,
                    canonicalName = review.lessonTitle.removeSuffix(" · 三天后再测"),
                    signal = LearnerSignalType.DELAYED_TRANSFER_T2,
                    scoreRatio = score,
                    guessed = guessed,
                    fsrsStability = fsrs?.stability,
                    conceptKeyOverride = cohort.conceptKey,
                    nowMs = now,
                )
            }
            return
        }
        val baseLesson = review.lesson.substringBefore("#q")
        MasteryStore.recordEvidence(
            db = db,
            bookId = review.book,
            tocNodeId = baseLesson,
            page = 1,
            canonicalName = review.lessonTitle,
            signal = LearnerSignalType.POST_QUIZ_T1,
            scoreRatio = score,
            guessed = guessed,
            fsrsStability = fsrs?.stability,
            nowMs = now,
        )
    }

    fun active(db: Connection): LocalBookReview? = db.createStatement().use { s -> s.executeQuery("""SELECT r.*
        FROM book_reviews r JOIN book_review_position p ON r.book=p.book AND r.lesson=p.lesson AND r.mode=p.mode
        WHERE p.slot=1""").use { r -> if (r.next()) read(db, r) else null } }

    fun open(db: Connection, entry: LocalBookReview) {
        db.prepareStatement("UPDATE book_reviews SET sources=sources | 8 WHERE book=? AND lesson=? AND mode=? AND due_day<=?").use { s ->
            s.setString(1, entry.book); s.setString(2, entry.lesson); s.setString(3, entry.mode.name)
            s.setLong(4, today()); s.executeUpdate()
        }
        db.prepareStatement("""UPDATE book_reviews SET answer_text='',revealed=0,understood=NULL,guessed=0,completed=0
            WHERE book=? AND lesson=? AND mode=? AND completed=1 AND due_day<=?""").use { s ->
            s.setString(1, entry.book); s.setString(2, entry.lesson); s.setString(3, entry.mode.name)
            s.setLong(4, today()); s.executeUpdate()
        }
        db.prepareStatement("INSERT OR REPLACE INTO book_review_position(slot,book,lesson,mode) VALUES(1,?,?,?)").use { s ->
            s.setString(1, entry.book); s.setString(2, entry.lesson); s.setString(3, entry.mode.name); s.executeUpdate()
        }
    }
}

/** Reconstruct the deterministic pre-2.0 choice key only when migrating a saved answer. */
private object LegacyReviewChoice {
    private val words = Regex("[A-Za-z][A-Za-z'-]{3,}|[\\p{IsHan}]{2,6}")
    private val definition = Regex("^(.{2,40}?)(?:\\s+(?:is|are|means|refers to)\\b|是|指的是|表示|称为)", RegexOption.IGNORE_CASE)

    private fun choiceCandidates(book: Textbook): List<String> {
        val definitions = book.lessons.asSequence().flatMap { lesson ->
            lesson.text.splitToSequence(Regex("(?<=[。.!?])\\s*|\\n"))
                .mapNotNull { definition.find(it.trim())?.groupValues?.get(1)?.trim() }
        }
        val vocabulary = book.pages.asSequence().flatMap { page -> words.findAll(page.text).map { it.value } }
        return (definitions + vocabulary).filter { it.length in 2..40 }.distinctBy(::normalized).take(4).toList()
    }

    private fun normalized(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).lowercase().trim()

    fun reconstruct(book: Textbook, lesson: BookLesson): Practice? {
        val sentences = lesson.text.split(Regex("(?<=[。！？.!?])\\s*|\\n")).map(String::trim).filter { it.length >= 12 }
        val reference = sentences.firstOrNull() ?: lesson.text.trim().takeIf { it.isNotEmpty() } ?: return null
        val sentence = sentences.firstOrNull { definition.containsMatchIn(it) } ?: reference
        val defined = definition.find(sentence)?.groupValues?.get(1)?.trim()?.trimEnd(',', '，', '：', ':')
        val answer = defined?.takeIf { it.length <= 40 && sentence.contains(it) }
            ?: words.find(sentence)?.value ?: return null
        val answerKey = normalized(answer)
        val distractors = choiceCandidates(book).filter { normalized(it) != answerKey }.take(3)
        if (distractors.isEmpty()) return null
        val options = (distractors + answer).shuffled(kotlin.random.Random(lesson.id.hashCode()))
        return Practice("根据本节原文，补全这句话：\n${sentence.replaceFirst(answer, "____")}", sentence, answer, options)
    }
}
