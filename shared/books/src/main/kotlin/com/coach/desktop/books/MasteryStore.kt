package com.coach.desktop.books

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID

/**
 * SQLite persistence layer for V4 tables:
 * - `concept_mastery_ledger`
 * - `learner_evidence_events`
 * - `learning_gain_cohorts`
 * - `margin_whisper_audit`
 */
internal object MasteryStore {
    const val T2_LESSON_PREFIX = "t2::"
    private val json = Json { ignoreUnknownKeys = true }

    fun loadConcept(db: Connection, conceptKey: String): ConceptMasteryRecord? {
        if (!BookSchemaMigration.tableExists(db, "concept_mastery_ledger")) return null
        return db.prepareStatement(
            """SELECT concept_key, collection_id, book_id, toc_node_id, canonical_name,
                      syllabus_weight, p_mastery, has_active_test, fsrs_stability,
                      dominant_error_tag, reread_count, dwell_ms_total, ask_count,
                      highlight_count, updated_at
               FROM concept_mastery_ledger WHERE concept_key=?""".trimIndent(),
        ).use { s ->
            s.setString(1, conceptKey)
            s.executeQuery().use { r -> if (r.next()) readConcept(r) else null }
        }
    }

    fun loadConceptsForBook(db: Connection, bookId: String): List<ConceptMasteryRecord> {
        if (!BookSchemaMigration.tableExists(db, "concept_mastery_ledger")) return emptyList()
        return db.prepareStatement(
            """SELECT concept_key, collection_id, book_id, toc_node_id, canonical_name,
                      syllabus_weight, p_mastery, has_active_test, fsrs_stability,
                      dominant_error_tag, reread_count, dwell_ms_total, ask_count,
                      highlight_count, updated_at
               FROM concept_mastery_ledger WHERE book_id=? ORDER BY updated_at DESC""".trimIndent(),
        ).use { s ->
            s.setString(1, bookId)
            s.executeQuery().use { r -> buildList { while (r.next()) add(readConcept(r)) } }
        }
    }

    fun loadAllConcepts(db: Connection): List<ConceptMasteryRecord> {
        if (!BookSchemaMigration.tableExists(db, "concept_mastery_ledger")) return emptyList()
        return db.createStatement().use { s ->
            s.executeQuery(
                """SELECT concept_key, collection_id, book_id, toc_node_id, canonical_name,
                          syllabus_weight, p_mastery, has_active_test, fsrs_stability,
                          dominant_error_tag, reread_count, dwell_ms_total, ask_count,
                          highlight_count, updated_at
                   FROM concept_mastery_ledger ORDER BY updated_at DESC""".trimIndent(),
            ).use { r -> buildList { while (r.next()) add(readConcept(r)) } }
        }
    }

    fun upsertConcept(db: Connection, record: ConceptMasteryRecord) {
        if (!BookSchemaMigration.tableExists(db, "concept_mastery_ledger")) return
        db.prepareStatement(
            """INSERT INTO concept_mastery_ledger(
                concept_key, collection_id, book_id, toc_node_id, canonical_name,
                syllabus_weight, p_mastery, has_active_test, fsrs_stability,
                dominant_error_tag, reread_count, dwell_ms_total, ask_count,
                highlight_count, updated_at
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(concept_key) DO UPDATE SET
                collection_id=COALESCE(excluded.collection_id, concept_mastery_ledger.collection_id),
                canonical_name=excluded.canonical_name,
                syllabus_weight=excluded.syllabus_weight,
                p_mastery=excluded.p_mastery,
                has_active_test=excluded.has_active_test,
                fsrs_stability=excluded.fsrs_stability,
                dominant_error_tag=excluded.dominant_error_tag,
                reread_count=excluded.reread_count,
                dwell_ms_total=excluded.dwell_ms_total,
                ask_count=excluded.ask_count,
                highlight_count=excluded.highlight_count,
                updated_at=excluded.updated_at""".trimIndent(),
        ).use { s ->
            s.setString(1, record.conceptKey)
            if (record.collectionId == null) s.setNull(2, Types.VARCHAR) else s.setString(2, record.collectionId)
            s.setString(3, record.bookId)
            s.setString(4, record.tocNodeId)
            s.setString(5, record.canonicalName)
            s.setDouble(6, record.syllabusWeight)
            s.setDouble(7, record.pMastery)
            s.setInt(8, if (record.hasActiveTest) 1 else 0)
            s.setDouble(9, record.fsrsStability)
            if (record.dominantErrorTag == null) s.setNull(10, Types.VARCHAR) else s.setString(10, record.dominantErrorTag)
            s.setInt(11, record.rereadCount)
            s.setLong(12, record.dwellMsTotal)
            s.setInt(13, record.askCount)
            s.setInt(14, record.highlightCount)
            s.setLong(15, record.updatedAt)
            s.executeUpdate()
        }
    }

    /**
     * Records one learner evidence event and atomically updates `concept_mastery_ledger`.
     */
    fun recordEvidence(
        db: Connection,
        bookId: String,
        tocNodeId: String,
        page: Int,
        canonicalName: String,
        signal: LearnerSignalType,
        scoreRatio: Double? = null,
        guessed: Boolean = false,
        errorClass: MisconceptionErrorClass? = null,
        durationMs: Long = 0L,
        detailJson: String = "{}",
        conceptKeyOverride: String? = null,
        collectionId: String? = null,
        syllabusWeight: Double? = null,
        fsrsStability: Double? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): ConceptMasteryRecord? {
        if (!BookSchemaMigration.tableExists(db, "concept_mastery_ledger") ||
            !BookSchemaMigration.tableExists(db, "learner_evidence_events")
        ) {
            return null
        }
        val cleanNode = tocNodeId.trim().ifBlank { "p$page" }
        val key = conceptKeyOverride?.trim()?.takeIf(String::isNotEmpty)
            ?: MasteryEngine.conceptKeyFor(bookId, cleanNode)
        val resolvedWeight = syllabusWeight ?: lookupSyllabusWeight(db, bookId, cleanNode) ?: 1.0
        val resolvedCollection = collectionId ?: lookupPrimaryCollectionId(db, bookId)
        val existing = loadConcept(db, key) ?: ConceptMasteryRecord(
            conceptKey = key,
            collectionId = resolvedCollection,
            bookId = bookId,
            tocNodeId = cleanNode,
            canonicalName = canonicalName.trim().ifBlank { cleanNode },
            syllabusWeight = resolvedWeight,
            updatedAt = nowMs,
        )
        val weighted = if (syllabusWeight != null && syllabusWeight != existing.syllabusWeight) {
            existing.copy(syllabusWeight = syllabusWeight)
        } else {
            existing
        }
        val updated = MasteryEngine.applySignal(
            current = weighted,
            signal = signal,
            scoreRatio = scoreRatio,
            guessed = guessed,
            errorClass = errorClass,
            durationMs = durationMs,
            fsrsStability = fsrsStability,
            nowMs = nowMs,
        )
        upsertConcept(db, updated)
        val eventId = "ev-${UUID.randomUUID().toString().replace("-", "").take(16)}"
        db.prepareStatement(
            """INSERT INTO learner_evidence_events(
                event_id, concept_key, book_id, page, signal_type, score_ratio,
                guessed, error_class, duration_ms, detail_json, created_at
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
        ).use { s ->
            s.setString(1, eventId)
            s.setString(2, updated.conceptKey)
            s.setString(3, bookId)
            s.setInt(4, page)
            s.setString(5, signal.name)
            if (scoreRatio == null) s.setNull(6, Types.REAL) else s.setDouble(6, scoreRatio.coerceIn(0.0, 1.0))
            s.setInt(7, if (guessed) 1 else 0)
            if (errorClass == null) s.setNull(8, Types.VARCHAR) else s.setString(8, errorClass.name)
            s.setLong(9, durationMs.coerceAtLeast(0L))
            s.setString(10, detailJson.ifBlank { "{}" })
            s.setLong(11, nowMs)
            s.executeUpdate()
        }
        return updated
    }

    fun listEvidenceEvents(db: Connection, conceptKey: String? = null, bookId: String? = null): List<LearnerEvidenceEvent> {
        if (!BookSchemaMigration.tableExists(db, "learner_evidence_events")) return emptyList()
        val sql = when {
            conceptKey != null -> "SELECT * FROM learner_evidence_events WHERE concept_key=? ORDER BY created_at, rowid"
            bookId != null -> "SELECT * FROM learner_evidence_events WHERE book_id=? ORDER BY created_at, rowid"
            else -> "SELECT * FROM learner_evidence_events ORDER BY created_at, rowid"
        }
        return db.prepareStatement(sql).use { s ->
            if (conceptKey != null) s.setString(1, conceptKey)
            else if (bookId != null) s.setString(1, bookId)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) {
                        val scoreObj = r.getObject("score_ratio")
                        val score = if (scoreObj == null) null else r.getDouble("score_ratio")
                        add(
                            LearnerEvidenceEvent(
                                eventId = r.getString("event_id"),
                                conceptKey = r.getString("concept_key"),
                                bookId = r.getString("book_id"),
                                page = r.getInt("page"),
                                signalType = LearnerSignalType.valueOf(r.getString("signal_type")),
                                scoreRatio = score,
                                guessed = r.getInt("guessed") != 0,
                                errorClass = r.getString("error_class"),
                                durationMs = r.getLong("duration_ms"),
                                detailJson = r.getString("detail_json"),
                                createdAt = r.getLong("created_at"),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Records T0 pretest and strictly sets `t2_eligible_after = t0_at + 72h`. Guessed T0 scores `0.0`. */
    fun recordLearningGainT0(
        db: Connection,
        bookId: String,
        chapterId: String,
        conceptKey: String,
        rawScore: Double,
        guessed: Boolean,
        collectionId: String? = null,
        variantId: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): LearningGainCohort? {
        if (!BookSchemaMigration.tableExists(db, "learning_gain_cohorts")) return null
        val cohortId = cohortIdFor(bookId, chapterId, conceptKey)
        val effectiveScore = if (guessed) 0.0 else rawScore.coerceIn(0.0, 1.0)
        val t2Eligible = nowMs + MasteryEngine.T2_DELAY_MS
        val resolvedCollection = collectionId ?: lookupPrimaryCollectionId(db, bookId)
        val resolvedVariant = variantId ?: "var-${conceptKey.hashCode().toUInt().toString(16)}"
        db.prepareStatement(
            """INSERT INTO learning_gain_cohorts(
                cohort_id, collection_id, book_id, chapter_id, concept_key,
                t0_at, t0_score, t0_guessed, read_completed_at, read_dwell_sec,
                t1_at, t1_score, t2_eligible_after, t2_at, t2_variant_id, t2_score
            ) VALUES(?,?,?,?,?,?,?,?,NULL,0,NULL,NULL,?,NULL,?,NULL)
            ON CONFLICT(cohort_id) DO UPDATE SET
                t0_at=COALESCE(learning_gain_cohorts.t0_at, excluded.t0_at),
                t0_score=COALESCE(learning_gain_cohorts.t0_score, excluded.t0_score),
                t0_guessed=CASE WHEN learning_gain_cohorts.t0_at IS NULL THEN excluded.t0_guessed ELSE learning_gain_cohorts.t0_guessed END,
                t2_eligible_after=COALESCE(learning_gain_cohorts.t2_eligible_after, excluded.t2_eligible_after),
                t2_variant_id=COALESCE(learning_gain_cohorts.t2_variant_id, excluded.t2_variant_id)""".trimIndent(),
        ).use { s ->
            s.setString(1, cohortId)
            if (resolvedCollection == null) s.setNull(2, Types.VARCHAR) else s.setString(2, resolvedCollection)
            s.setString(3, bookId)
            s.setString(4, chapterId)
            s.setString(5, conceptKey)
            s.setLong(6, nowMs)
            s.setDouble(7, effectiveScore)
            s.setInt(8, if (guessed) 1 else 0)
            s.setLong(9, t2Eligible)
            s.setString(10, resolvedVariant)
            s.executeUpdate()
        }
        return loadCohort(db, cohortId)
    }

    fun recordLearningGainRead(
        db: Connection,
        bookId: String,
        chapterId: String,
        conceptKey: String,
        addedDwellSec: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): LearningGainCohort? {
        if (!BookSchemaMigration.tableExists(db, "learning_gain_cohorts")) return null
        val cohortId = cohortIdFor(bookId, chapterId, conceptKey)
        val existing = loadCohort(db, cohortId) ?: return null
        val nextDwell = existing.readDwellSec + addedDwellSec.coerceAtLeast(0)
        db.prepareStatement(
            "UPDATE learning_gain_cohorts SET read_completed_at=?, read_dwell_sec=? WHERE cohort_id=?",
        ).use { s ->
            s.setLong(1, nowMs)
            s.setInt(2, nextDwell)
            s.setString(3, cohortId)
            s.executeUpdate()
        }
        return loadCohort(db, cohortId)
    }

    fun recordLearningGainT1(
        db: Connection,
        bookId: String,
        chapterId: String,
        conceptKey: String,
        rawScore: Double,
        guessed: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): LearningGainCohort? {
        if (!BookSchemaMigration.tableExists(db, "learning_gain_cohorts")) return null
        val cohortId = cohortIdFor(bookId, chapterId, conceptKey)
        val effectiveScore = if (guessed) 0.0 else rawScore.coerceIn(0.0, 1.0)
        // Only update cohorts that started with an explicit T0 pretest (t2_eligible_after = t0_at + 72h).
        loadCohort(db, cohortId) ?: return null
        db.prepareStatement(
            "UPDATE learning_gain_cohorts SET t1_at=?, t1_score=? WHERE cohort_id=?",
        ).use { s ->
            s.setLong(1, nowMs)
            s.setDouble(2, effectiveScore)
            s.setString(3, cohortId)
            s.executeUpdate()
        }
        return loadCohort(db, cohortId)
    }

    fun recordLearningGainT2(
        db: Connection,
        cohortId: String,
        rawScore: Double,
        guessed: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): LearningGainCohort? {
        if (!BookSchemaMigration.tableExists(db, "learning_gain_cohorts")) return null
        val existing = loadCohort(db, cohortId) ?: return null
        val eligibleAt = existing.t2EligibleAfter
        if (eligibleAt != null && nowMs < eligibleAt) return existing
        val effectiveScore = if (guessed) 0.0 else rawScore.coerceIn(0.0, 1.0)
        db.prepareStatement(
            "UPDATE learning_gain_cohorts SET t2_at=?, t2_score=? WHERE cohort_id=?",
        ).use { s ->
            s.setLong(1, nowMs)
            s.setDouble(2, effectiveScore)
            s.setString(3, cohortId)
            s.executeUpdate()
        }
        return loadCohort(db, cohortId)
    }

    fun loadCohort(db: Connection, cohortId: String): LearningGainCohort? {
        if (!BookSchemaMigration.tableExists(db, "learning_gain_cohorts")) return null
        return db.prepareStatement("SELECT * FROM learning_gain_cohorts WHERE cohort_id=?").use { s ->
            s.setString(1, cohortId)
            s.executeQuery().use { r -> if (r.next()) readCohort(r) else null }
        }
    }

    fun listCohorts(db: Connection, bookId: String? = null): List<LearningGainCohort> {
        if (!BookSchemaMigration.tableExists(db, "learning_gain_cohorts")) return emptyList()
        val sql = if (bookId != null) {
            "SELECT * FROM learning_gain_cohorts WHERE book_id=? ORDER BY COALESCE(t0_at, t1_at, 0), cohort_id"
        } else {
            "SELECT * FROM learning_gain_cohorts ORDER BY COALESCE(t0_at, t1_at, 0), cohort_id"
        }
        return db.prepareStatement(sql).use { s ->
            if (bookId != null) s.setString(1, bookId)
            s.executeQuery().use { r -> buildList { while (r.next()) add(readCohort(r)) } }
        }
    }

    fun eligibleT2Cohorts(db: Connection, nowMs: Long): List<LearningGainCohort> {
        if (!BookSchemaMigration.tableExists(db, "learning_gain_cohorts")) return emptyList()
        return db.prepareStatement(
            """SELECT * FROM learning_gain_cohorts
               WHERE t2_eligible_after IS NOT NULL AND t2_eligible_after <= ? AND t2_at IS NULL
               ORDER BY t2_eligible_after, cohort_id""".trimIndent(),
        ).use { s ->
            s.setLong(1, nowMs)
            s.executeQuery().use { r -> buildList { while (r.next()) add(readCohort(r)) } }
        }
    }

    /**
     * Silently mixes any `learning_gain_cohorts` whose `t2_eligible_after <= nowMs` into today's
     * normal `book_reviews` / `book_fsrs_cards` review queue without popping up a separate test modal.
     */
    fun enqueueEligibleT2Reviews(db: Connection, todayDay: Long, nowMs: Long): Int {
        if (!BookSchemaMigration.tableExists(db, "learning_gain_cohorts") ||
            !BookSchemaMigration.tableExists(db, "book_reviews") ||
            !BookSchemaMigration.tableExists(db, "book_fsrs_cards")
        ) {
            return 0
        }
        val eligible = eligibleT2Cohorts(db, nowMs)
        if (eligible.isEmpty()) return 0
        var added = 0
        for (cohort in eligible) {
            val lessonKey = "$T2_LESSON_PREFIX${cohort.cohortId}"
            val exists = db.prepareStatement(
                "SELECT 1 FROM book_reviews WHERE book=? AND lesson=? AND mode='SHORT_ANSWER'",
            ).use { s ->
                s.setString(1, cohort.bookId)
                s.setString(2, lessonKey)
                s.executeQuery().use { it.next() }
            }
            if (exists) continue

            val bookTitle = db.prepareStatement("SELECT title FROM books WHERE id=?").use { s ->
                s.setString(1, cohort.bookId)
                s.executeQuery().use { if (it.next()) it.getString(1) else null }
            } ?: continue
            val concept = loadConcept(db, cohort.conceptKey)
            val conceptTitle = concept?.canonicalName?.takeIf(String::isNotBlank) ?: cohort.chapterId
            val prompt = "三天后再测：不看原文，用自己的话说明「$conceptTitle」的核心关系，并举一个贴切例子。"

            db.prepareStatement(
                """INSERT INTO book_reviews(
                    book, lesson, mode, book_title, lesson_title, prompt, reference, solution,
                    options, wrong_text, answer_text, revealed, understood, completed, guessed, sources, due_day, streak
                ) VALUES(?,?,'SHORT_ANSWER',?,?,?,?,?,'[]','','',0,NULL,0,0,8,?,0)""".trimIndent(),
            ).use { s ->
                s.setString(1, cohort.bookId)
                s.setString(2, lessonKey)
                s.setString(3, bookTitle)
                s.setString(4, "$conceptTitle · 三天后再测")
                s.setString(5, prompt)
                s.setString(6, conceptTitle)
                s.setString(7, "")
                s.setLong(8, todayDay)
                s.executeUpdate()
            }
            db.prepareStatement(
                """INSERT OR IGNORE INTO book_fsrs_cards(
                    book, lesson, mode, stability, difficulty, last_review_day, due_day, reps, lapses, origin, updated_at
                ) VALUES(?,?,'SHORT_ANSWER',NULL,NULL,NULL,?,0,0,'T2_DELAYED',?)""".trimIndent(),
            ).use { s ->
                s.setString(1, cohort.bookId)
                s.setString(2, lessonKey)
                s.setLong(3, todayDay)
                s.setLong(4, nowMs)
                s.executeUpdate()
            }
            added += 1
        }
        return added
    }

    /**
     * Computes real `NeedScore` for a `book_reviews` card, replacing the old hardcoded `0.5` constant.
     */
    fun needScoreForReviewCard(
        db: Connection,
        bookId: String,
        lessonId: String,
        retrievability: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): Double {
        if (!BookSchemaMigration.tableExists(db, "concept_mastery_ledger")) {
            return (1.0 - retrievability.coerceIn(0.0, 1.0)).coerceAtLeast(0.05)
        }
        val baseLesson = lessonId.substringBefore("#q")
        val concept = when {
            lessonId.startsWith(T2_LESSON_PREFIX) -> {
                val cohortId = lessonId.removePrefix(T2_LESSON_PREFIX)
                loadCohort(db, cohortId)?.let { loadConcept(db, it.conceptKey) }
            }
            else -> loadConcept(db, MasteryEngine.conceptKeyFor(bookId, baseLesson))
                ?: findConceptByTocNode(db, bookId, baseLesson)
        }
        if (concept != null) {
            val t2PriorityBoost = if (lessonId.startsWith(T2_LESSON_PREFIX)) 0.15 else 0.0
            return MasteryEngine.needScore(
                syllabusWeight = concept.syllabusWeight,
                pMastery = concept.pMastery,
                retrievability = retrievability,
                dominantErrorTag = concept.dominantErrorTag,
                rereadCount = concept.rereadCount,
                askCount = concept.askCount,
            ) + t2PriorityBoost
        }
        val fallbackWeight = lookupSyllabusWeight(db, bookId, baseLesson) ?: 1.0
        return MasteryEngine.needScore(
            syllabusWeight = fallbackWeight,
            pMastery = MasteryEngine.DEFAULT_P_MASTERY,
            retrievability = retrievability,
            dominantErrorTag = null,
            rereadCount = 0,
            askCount = 0,
        )
    }

    /**
     * Picks the highest-`NeedScore` weak concept for Home's `home-focus-card` single-line recommendation.
     */
    fun topHomeRecommendation(
        db: Connection,
        preferredBookId: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): HomeFocusRecommendation? {
        val candidates = if (preferredBookId != null) {
            loadConceptsForBook(db, preferredBookId).ifEmpty { loadAllConcepts(db) }
        } else {
            loadAllConcepts(db)
        }
        if (candidates.isEmpty()) return null
        val scored = candidates.map { rec -> rec to MasteryEngine.needScore(rec, nowMs) }
        val (top, score) = scored.maxByOrNull { it.second } ?: return null
        // Only recommend when there is an actionable weakness, error, reread friction, ask, or unfinished mastery.
        val actionable = !top.dominantErrorTag.isNullOrBlank() ||
            top.rereadCount >= 2 ||
            top.askCount > 0 ||
            top.pMastery < MasteryEngine.HIGH_MASTERY_COLLAPSE_THRESHOLD
        if (!actionable) return null

        val targetPage = lookupTargetPageForConcept(db, top.bookId, top.tocNodeId)
        val sectionTitle = lookupTocTitle(db, top.bookId, top.tocNodeId)
        val metaLine = MasteryEngine.buildHomeFocusMeta(top, sectionTitle, targetPage)
        return HomeFocusRecommendation(
            bookId = top.bookId,
            tocNodeId = top.tocNodeId,
            conceptKey = top.conceptKey,
            canonicalName = top.canonicalName,
            targetPage = targetPage,
            needScore = score,
            dominantErrorTag = top.dominantErrorTag,
            rereadCount = top.rereadCount,
            askCount = top.askCount,
            pMastery = top.pMastery,
            metaLine = metaLine,
        )
    }

    fun saveMarginWhisperAudit(db: Connection, candidate: MarginWhisperCandidate) {
        if (!BookSchemaMigration.tableExists(db, "margin_whisper_audit")) return
        db.prepareStatement(
            """INSERT OR REPLACE INTO margin_whisper_audit(
                whisper_id, book_id, toc_node_id, page, rule, collapsed_text, expanded_text,
                pdf_page_anchor, quote_anchor, trigger_evidence_json, dismissed, created_at
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
        ).use { s ->
            s.setString(1, candidate.whisperId)
            s.setString(2, candidate.bookId)
            s.setString(3, candidate.tocNodeId)
            s.setInt(4, candidate.page)
            s.setString(5, candidate.rule.name)
            s.setString(6, candidate.collapsedText)
            s.setString(7, candidate.expandedText)
            s.setInt(8, candidate.pdfPageAnchor)
            s.setString(9, candidate.quoteAnchor)
            s.setString(10, candidate.triggerEvidenceJson)
            s.setInt(11, if (candidate.dismissed) 1 else 0)
            s.setLong(12, candidate.createdAt)
            s.executeUpdate()
        }
    }

    fun markMarginWhisperDismissed(db: Connection, whisperId: String) {
        if (!BookSchemaMigration.tableExists(db, "margin_whisper_audit")) return
        db.prepareStatement("UPDATE margin_whisper_audit SET dismissed=1 WHERE whisper_id=?").use { s ->
            s.setString(1, whisperId)
            s.executeUpdate()
        }
    }

    fun listMarginWhisperAudits(db: Connection, bookId: String? = null): List<MarginWhisperCandidate> {
        if (!BookSchemaMigration.tableExists(db, "margin_whisper_audit")) return emptyList()
        val sql = if (bookId != null) {
            "SELECT * FROM margin_whisper_audit WHERE book_id=? ORDER BY created_at, rowid"
        } else {
            "SELECT * FROM margin_whisper_audit ORDER BY created_at, rowid"
        }
        return db.prepareStatement(sql).use { s ->
            if (bookId != null) s.setString(1, bookId)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) {
                        val rule = runCatching { MarginWhisperRule.valueOf(r.getString("rule")) }
                            .getOrDefault(MarginWhisperRule.REREAD_FRICTION_ON_PAGE)
                        add(
                            MarginWhisperCandidate(
                                whisperId = r.getString("whisper_id"),
                                bookId = r.getString("book_id"),
                                tocNodeId = r.getString("toc_node_id"),
                                page = r.getInt("page"),
                                rule = rule,
                                collapsedText = r.getString("collapsed_text"),
                                expandedText = r.getString("expanded_text"),
                                pdfPageAnchor = r.getInt("pdf_page_anchor"),
                                quoteAnchor = r.getString("quote_anchor"),
                                triggerEvidenceJson = r.getString("trigger_evidence_json"),
                                dismissed = r.getInt("dismissed") != 0,
                                createdAt = r.getLong("created_at"),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Looks up known cross-material conflicts anchored on [bookId] + [page] from either
     * `concept_links` (booklist/cross-book conflicts) or `course_topics.pack_payload_json` discrepancies.
     */
    fun queryCrossMaterialConflictForPage(
        db: Connection,
        bookId: String,
        page: Int,
        tocNodeId: String? = null,
    ): Pair<String, String>? {
        if (BookSchemaMigration.tableExists(db, "concept_links") &&
            BookSchemaMigration.tableExists(db, "concept_mentions") &&
            BookSchemaMigration.tableExists(db, "concept_entities")
        ) {
            db.prepareStatement(
                """SELECT e.canonical_name, l.diff_summary
                   FROM concept_links l
                   JOIN concept_entities e ON e.concept_id = l.concept_id
                   JOIN concept_mentions m ON (m.mention_id = l.from_mention_id OR m.mention_id = l.to_mention_id)
                   WHERE l.dismissed = 0 AND e.muted = 0
                     AND l.relation_type IN ('NOTATION_OR_BOUNDARY_DIFF','EMPIRICAL_CHALLENGE','OPPOSING_VIEW')
                     AND m.book_id = ? AND m.page = ?
                   ORDER BY l.confidence DESC LIMIT 1""".trimIndent(),
            ).use { s ->
                s.setString(1, bookId)
                s.setInt(2, page)
                s.executeQuery().use { r ->
                    if (r.next()) {
                        val name = r.getString(1).orEmpty()
                        val diff = r.getString(2).orEmpty()
                        if (diff.isNotBlank()) {
                            return ("$name 跨书口径有分歧" to diff)
                        }
                    }
                }
            }
        }
        if (BookSchemaMigration.tableExists(db, "course_topics") &&
            BookSchemaMigration.tableExists(db, "course_topic_sources")
        ) {
            db.prepareStatement(
                """SELECT t.pack_payload_json
                   FROM course_topics t
                   JOIN course_topic_sources s ON s.topic_id = t.topic_id
                   WHERE s.book_id = ? AND ? BETWEEN s.start_page AND s.end_page
                     AND t.pack_payload_json IS NOT NULL AND t.pack_payload_json <> ''
                   LIMIT 3""".trimIndent(),
            ).use { s ->
                s.setString(1, bookId)
                s.setInt(2, page)
                s.executeQuery().use { r ->
                    while (r.next()) {
                        val raw = r.getString(1).orEmpty()
                        val parsed = parseFirstDiscrepancyForPage(raw, bookId, page)
                        if (parsed != null) return parsed
                    }
                }
            }
        }
        return null
    }

    private fun parseFirstDiscrepancyForPage(rawJson: String, bookId: String, page: Int): Pair<String, String>? {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        val diffs = root["discrepancies"] as? JsonArray ?: return null
        for (el in diffs) {
            val obj = el as? JsonObject ?: continue
            val point = (obj["point"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val advice = (obj["advice"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val authView = (obj["authoritativeView"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val citations = obj["citations"] as? JsonArray
            val pageMatches = citations == null || citations.isEmpty() || citations.any { c ->
                val co = c as? JsonObject ?: return@any false
                val cBook = (co["bookId"] as? JsonPrimitive)?.contentOrNull
                val cPage = (co["page"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                (cBook == null || cBook == bookId) && (cPage == null || cPage == page)
            }
            if (pageMatches && point.isNotBlank()) {
                val detail = listOf(authView, advice).filter(String::isNotBlank).joinToString("；")
                    .ifBlank { point }
                return (point to detail)
            }
        }
        return null
    }

    fun deleteForBook(db: Connection, bookId: String) {
        for (table in listOf("margin_whisper_audit", "learning_gain_cohorts", "learner_evidence_events", "concept_mastery_ledger")) {
            if (BookSchemaMigration.tableExists(db, table)) {
                db.prepareStatement("DELETE FROM $table WHERE book_id=?").use { s ->
                    s.setString(1, bookId)
                    s.executeUpdate()
                }
            }
        }
    }

    private fun findConceptByTocNode(db: Connection, bookId: String, tocNodeId: String): ConceptMasteryRecord? =
        db.prepareStatement(
            """SELECT concept_key, collection_id, book_id, toc_node_id, canonical_name,
                      syllabus_weight, p_mastery, has_active_test, fsrs_stability,
                      dominant_error_tag, reread_count, dwell_ms_total, ask_count,
                      highlight_count, updated_at
               FROM concept_mastery_ledger WHERE book_id=? AND toc_node_id=?
               ORDER BY updated_at DESC LIMIT 1""".trimIndent(),
        ).use { s ->
            s.setString(1, bookId)
            s.setString(2, tocNodeId)
            s.executeQuery().use { r -> if (r.next()) readConcept(r) else null }
        }

    private fun lookupPrimaryCollectionId(db: Connection, bookId: String): String? {
        if (!BookSchemaMigration.tableExists(db, "collection_materials")) return null
        return db.prepareStatement(
            "SELECT collection_id FROM collection_materials WHERE material_id=? ORDER BY added_at DESC LIMIT 1",
        ).use { s ->
            s.setString(1, bookId)
            s.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
    }

    private fun lookupSyllabusWeight(db: Connection, bookId: String, tocNodeId: String): Double? {
        if (!BookSchemaMigration.tableExists(db, "course_topics") ||
            !BookSchemaMigration.tableExists(db, "course_topic_sources")
        ) {
            return null
        }
        return db.prepareStatement(
            """SELECT MAX(t.exam_weight)
               FROM course_topics t
               JOIN course_topic_sources s ON s.topic_id = t.topic_id
               WHERE s.book_id = ? AND (s.toc_node_id = ? OR s.toc_node_id IS NULL)""".trimIndent(),
        ).use { s ->
            s.setString(1, bookId)
            s.setString(2, tocNodeId)
            s.executeQuery().use { r ->
                if (!r.next()) null
                else r.getDouble(1).takeIf { !r.wasNull() && it > 0.0 }
            }
        }
    }

    private fun lookupTargetPageForConcept(db: Connection, bookId: String, tocNodeId: String): Int {
        if (BookSchemaMigration.tableExists(db, "learner_evidence_events")) {
            val fromEvent = db.prepareStatement(
                """SELECT page FROM learner_evidence_events
                   WHERE book_id=? AND concept_key LIKE ? AND page > 0
                   ORDER BY created_at DESC LIMIT 1""".trimIndent(),
            ).use { s ->
                s.setString(1, bookId)
                s.setString(2, "%$tocNodeId%")
                s.executeQuery().use { if (it.next()) it.getInt(1) else null }
            }
            if (fromEvent != null && fromEvent > 0) return fromEvent
        }
        if (BookSchemaMigration.tableExists(db, "book_toc_nodes")) {
            val fromToc = db.prepareStatement(
                "SELECT start_page FROM book_toc_nodes WHERE book=? AND id=? LIMIT 1",
            ).use { s ->
                s.setString(1, bookId)
                s.setString(2, tocNodeId)
                s.executeQuery().use { if (it.next()) it.getInt(1) else null }
            }
            if (fromToc != null && fromToc > 0) return fromToc
        }
        if (BookSchemaMigration.tableExists(db, "books")) {
            val fallback = db.prepareStatement("SELECT reading_page FROM books WHERE id=?").use { s ->
                s.setString(1, bookId)
                s.executeQuery().use { if (it.next()) it.getInt(1) else 1 }
            }
            return fallback.coerceAtLeast(1)
        }
        return 1
    }

    private fun lookupTocTitle(db: Connection, bookId: String, tocNodeId: String): String? {
        if (!BookSchemaMigration.tableExists(db, "book_toc_nodes")) return null
        return db.prepareStatement("SELECT title FROM book_toc_nodes WHERE book=? AND id=? LIMIT 1").use { s ->
            s.setString(1, bookId)
            s.setString(2, tocNodeId)
            s.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
    }

    private fun cohortIdFor(bookId: String, chapterId: String, conceptKey: String): String =
        "gain:${bookId.take(12)}:$chapterId:${conceptKey.hashCode().toUInt().toString(16)}"

    private fun readConcept(r: ResultSet): ConceptMasteryRecord = ConceptMasteryRecord(
        conceptKey = r.getString("concept_key"),
        collectionId = r.getString("collection_id"),
        bookId = r.getString("book_id"),
        tocNodeId = r.getString("toc_node_id"),
        canonicalName = r.getString("canonical_name"),
        syllabusWeight = r.getDouble("syllabus_weight"),
        pMastery = r.getDouble("p_mastery"),
        hasActiveTest = r.getInt("has_active_test") != 0,
        fsrsStability = r.getDouble("fsrs_stability"),
        dominantErrorTag = r.getString("dominant_error_tag"),
        rereadCount = r.getInt("reread_count"),
        dwellMsTotal = r.getLong("dwell_ms_total"),
        askCount = r.getInt("ask_count"),
        highlightCount = r.getInt("highlight_count"),
        updatedAt = r.getLong("updated_at"),
    )

    private fun readCohort(r: ResultSet): LearningGainCohort {
        fun optLong(col: String): Long? = r.getLong(col).let { if (r.wasNull()) null else it }
        fun optDouble(col: String): Double? = r.getDouble(col).let { if (r.wasNull()) null else it }
        return LearningGainCohort(
            cohortId = r.getString("cohort_id"),
            collectionId = r.getString("collection_id"),
            bookId = r.getString("book_id"),
            chapterId = r.getString("chapter_id"),
            conceptKey = r.getString("concept_key"),
            t0At = optLong("t0_at"),
            t0Score = optDouble("t0_score"),
            t0Guessed = r.getInt("t0_guessed") != 0,
            readCompletedAt = optLong("read_completed_at"),
            readDwellSec = r.getInt("read_dwell_sec"),
            t1At = optLong("t1_at"),
            t1Score = optDouble("t1_score"),
            t2EligibleAfter = optLong("t2_eligible_after"),
            t2At = optLong("t2_at"),
            t2VariantId = r.getString("t2_variant_id"),
            t2Score = optDouble("t2_score"),
        )
    }
}
