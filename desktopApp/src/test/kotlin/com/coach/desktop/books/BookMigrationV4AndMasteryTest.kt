package com.coach.desktop.books

import com.coach.domain.fsrs.FsrsRating
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.*

private class MutableMillisClock(
    private var currentMillis: Long = Instant.parse("2026-11-22T09:00:00Z").toEpochMilli(),
    private val zoneId: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun getZone(): ZoneId = zoneId
    override fun withZone(zone: ZoneId): Clock = MutableMillisClock(currentMillis, zone)
    override fun instant(): Instant = Instant.ofEpochMilli(currentMillis)
    fun advanceMillis(deltaMs: Long) { currentMillis += deltaMs }
    fun advanceHours(hours: Long) = advanceMillis(hours * 3600L * 1000L)
}

class BookMigrationV4AndMasteryTest {
    private val v4Tables = listOf(
        "concept_mastery_ledger",
        "learner_evidence_events",
        "learning_gain_cohorts",
        "margin_whisper_audit",
    )

    private fun connect(file: Path): Connection = DriverManager.getConnection("jdbc:sqlite:$file")

    private fun rows(db: Connection, sql: String): List<List<String?>> = db.createStatement().use { s ->
        s.executeQuery(sql).use { r ->
            val n = r.metaData.columnCount
            buildList { while (r.next()) add((1..n).map { r.getString(it) }) }
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun snapshotTables(file: Path, tables: List<String>): Map<String, Pair<Int, String>> = connect(file).use { db ->
        tables.associateWith { table ->
            val data = rows(db, "SELECT * FROM $table ORDER BY rowid")
            val serialized = data.joinToString("\n") { row -> row.joinToString("\u001F") { it ?: "<NULL>" } }
            data.size to sha256(serialized)
        }
    }

    private fun rewindToV3(file: Path) = connect(file).use { db ->
        db.createStatement().use { s ->
            for (t in v4Tables) s.execute("DROP TABLE IF EXISTS $t")
            s.execute("PRAGMA user_version = 3")
        }
    }

    @Test
    fun v3LibraryUpgradesLosslesslyToV4WithVerifiedV3BackupAndRollbackSafety() {
        val root = Files.createTempDirectory("coach-v3-to-v4-")
        try {
            val clock = MutableMillisClock()
            val dir = root.resolve("library")
            val first = TextbookLibrary(dir, clock)
            val book = first.importBook(syntheticTextbook(root.resolve("synthetic")))
            val lesson = book.lessons.first()
            first.saveAnswer(book.id, lesson.id, QuestionMode.SHORT_ANSWER, PracticeAnswer("Initial draft", true, false))
            first.setHighlight(BookPassage(book.id, book.pages.first().number, 0, minOf(12, book.pages.first().text.length), book.pages.first().text.take(12)), true)
            first.recordPageDwell(book.id, lesson.id, book.pages.first().number, book.pages.size, ChapterReadPolicy.MIN_DWELL_MS)
            first.upsertCollection(MaterialCollectionRecord("course-v3", CollectionKind.COURSE, "Health Econ", createdAt = 100L))
            first.attachMaterial(CollectionMaterialRecord("course-v3", book.id, role = "TEXTBOOK", ordinal = 0, addedAt = 101L))

            val dbFile = dir.resolve("library.sqlite")
            rewindToV3(dbFile)

            val preservedTables = listOf(
                "books", "pages", "answers", "book_reviews", "book_review_history",
                "book_highlights", "collections", "collection_materials",
                "book_chapter_progress", "book_fsrs_cards",
            )
            val before = snapshotTables(dbFile, preservedTables)

            // 1. Simulated migration failure leaves V3 DB healthy and preserves library.sqlite.v3.bak
            assertFailsWith<IllegalStateException> {
                BookSchemaMigration.upgradeAndMigrate(
                    directory = dir,
                    openConnection = { DriverManager.getConnection("jdbc:sqlite:$dbFile") },
                    onInTransaction = { db ->
                        db.createStatement().use { it.execute("DELETE FROM books") }
                        throw IllegalStateException("Injected V3->V4 failure")
                    },
                )
            }
            val backup = dir.resolve(BookSchemaMigration.V3_BACKUP_FILENAME)
            assertTrue(Files.isRegularFile(backup), "library.sqlite.v3.bak must be generated before V3->V4 migration")
            assertTrue(BookSchemaMigration.isHealthySqlite(backup))
            assertEquals(3, connect(backup).use(BookSchemaMigration::readUserVersion))
            assertEquals(3, connect(dbFile).use(BookSchemaMigration::readUserVersion))
            assertEquals(before, snapshotTables(dbFile, preservedTables), "failed V3->V4 transaction must roll back cleanly")

            // 2. Real upgrade succeeds atomically with 100% lossless rows & SHA-256 parity
            val upgraded = TextbookLibrary(dir, clock)
            connect(dbFile).use { db ->
                assertEquals(BookSchemaMigration.V4_SCHEMA_VERSION, BookSchemaMigration.readUserVersion(db))
                for (t in v4Tables) assertTrue(BookSchemaMigration.tableExists(db, t), "missing V4 table $t")
            }
            val after = snapshotTables(dbFile, preservedTables)
            for ((table, expected) in before) {
                val actual = after[table]
                assertNotNull(actual, "missing table $table after V4 upgrade")
                assertEquals(expected.first, actual.first, "row count changed on $table")
                assertEquals(expected.second, actual.second, "content SHA-256 changed on $table")
            }
            assertEquals(1, upgraded.reviews().size)
            assertEquals(1, upgraded.allHighlights(book.id).size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun passiveReadingAndHighlightingNeverExceedMasteryCap035() {
        val root = Files.createTempDirectory("coach-passive-cap-")
        try {
            val clock = MutableMillisClock()
            val lib = TextbookLibrary(root.resolve("library"), clock)
            val book = lib.importBook(syntheticTextbook(root.resolve("synthetic")))
            val lesson = book.lessons.first()
            val conceptKey = MasteryEngine.conceptKeyFor(book.id, lesson.id)

            // 50 dwell sessions + 50 highlights + 10 asks + 5 reread backtracks with ZERO active test
            repeat(50) { i ->
                lib.recordMasteryEvidence(
                    bookId = book.id,
                    tocNodeId = lesson.id,
                    page = lesson.firstPage,
                    canonicalName = lesson.title,
                    signal = LearnerSignalType.PAGE_DWELL,
                    durationMs = 120_000L,
                    conceptKeyOverride = conceptKey,
                )
                val beforeHl = lib.conceptMastery(conceptKey)?.pMastery ?: MasteryEngine.DEFAULT_P_MASTERY
                lib.recordMasteryEvidence(
                    bookId = book.id,
                    tocNodeId = lesson.id,
                    page = lesson.firstPage,
                    canonicalName = lesson.title,
                    signal = LearnerSignalType.HIGHLIGHT_NOTE,
                    conceptKeyOverride = conceptKey,
                )
                val afterHl = lib.conceptMastery(conceptKey)!!.pMastery
                assertEquals(beforeHl, afterHl, "HIGHLIGHT_NOTE must never increase p_mastery (iteration $i)")
            }
            repeat(10) {
                lib.recordMasteryEvidence(
                    bookId = book.id,
                    tocNodeId = lesson.id,
                    page = lesson.firstPage,
                    canonicalName = lesson.title,
                    signal = LearnerSignalType.SELECTION_ASK,
                    conceptKeyOverride = conceptKey,
                )
            }
            repeat(5) {
                lib.recordMasteryEvidence(
                    bookId = book.id,
                    tocNodeId = lesson.id,
                    page = lesson.firstPage,
                    canonicalName = lesson.title,
                    signal = LearnerSignalType.REREAD_BACKTRACK,
                    durationMs = 40_000L,
                    conceptKeyOverride = conceptKey,
                )
            }

            val ledger = assertNotNull(lib.conceptMastery(conceptKey))
            assertFalse(ledger.hasActiveTest, "passive signals must keep has_active_test == 0")
            assertTrue(
                ledger.pMastery <= MasteryEngine.PASSIVE_MASTERY_CAP,
                "untested concept p_mastery must be <= 0.35, was ${ledger.pMastery}",
            )
            assertEquals(50, ledger.highlightCount)
            assertEquals(10, ledger.askCount)
            assertEquals(5, ledger.rereadCount)
            assertFalse(MasteryEngine.shouldCollapseBasicTerminology(ledger))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun guessedCorrectAnswerDoesNotRaiseMasteryAndSchedulesReview() {
        val root = Files.createTempDirectory("coach-guessed-answer-")
        try {
            val clock = MutableMillisClock()
            val lib = TextbookLibrary(root.resolve("library"), clock)
            val book = lib.importBook(syntheticTextbook(root.resolve("synthetic")))
            val lesson = book.lessons.first()
            val practice = Practice(
                prompt = "Which line shifts upward when depreciation rises?",
                reference = lesson.text.take(40),
                answer = "r + gamma line shifts up",
                options = listOf("r + gamma line shifts up", "MEC shifts left"),
            )
            val conceptKey = MasteryEngine.conceptKeyFor(book.id, lesson.id)

            // Answer correctly BUT mark "这题是猜的" (guessed = true) on T0 pretest
            lib.saveSectionCheckAnswer(
                bookId = book.id,
                lesson = lesson,
                questionIndex = 0,
                practice = practice,
                answer = PracticeAnswer(text = "r + gamma line shifts up", revealed = true, understood = true, guessed = true),
                isPretest = true,
            )
            val afterGuess = assertNotNull(lib.conceptMastery(conceptKey))
            assertFalse(afterGuess.hasActiveTest)
            assertTrue(afterGuess.pMastery <= MasteryEngine.PASSIVE_MASTERY_CAP)
            assertTrue(lib.reviews().any { it.book == book.id && it.lesson == lesson.id }, "guessed answer must stay in review queue")
            val cohort = lib.learningGainCohorts(book.id).single()
            assertTrue(cohort.t0Guessed)
            assertEquals(0.0, cohort.t0Score, "guessed T0 answer must record t0_score = 0.0")

            // Independent confident answers raise p_mastery past 0.85 and enable terminology folding
            repeat(4) {
                lib.recordMasteryEvidence(
                    bookId = book.id,
                    tocNodeId = lesson.id,
                    page = lesson.firstPage,
                    canonicalName = lesson.title,
                    signal = LearnerSignalType.POST_QUIZ_T1,
                    scoreRatio = 1.0,
                    guessed = false,
                    conceptKeyOverride = conceptKey,
                )
            }
            val mastered = assertNotNull(lib.conceptMastery(conceptKey))
            assertTrue(mastered.hasActiveTest)
            assertTrue(mastered.pMastery >= 0.85, "expected mastered p >= 0.85, got ${mastered.pMastery}")
            assertTrue(MasteryEngine.shouldCollapseBasicTerminology(mastered))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun needScoreMatchesSampleCh3FormulaAndReplacesHardcodedReviewWeight() {
        // Exact verification against sample-ch3/01-mastery-and-home-routing.json
        assertEquals(1.615, MasteryEngine.needScore(1.5, 0.33, 0.878, "GRAPH_SHIFT_OR_ROTATION", 3, 1), 0.001)
        assertEquals(1.508, MasteryEngine.needScore(1.4, 0.24, 0.90, "SIGN_OR_FORMULA", 2, 0), 0.001)
        assertEquals(0.925, MasteryEngine.needScore(1.3, 0.31, 0.931, null, 0, 0), 0.001)
        assertEquals(0.792, MasteryEngine.needScore(1.2, 0.542, 0.966, null, 2, 1), 0.001)
        assertEquals(0.385, MasteryEngine.needScore(1.0, 0.623, 0.987, null, 0, 0), 0.001)

        // Verify TextbookReviewStore.queue() ranks by real NeedScore (not hardcoded 0.5)
        val root = Files.createTempDirectory("coach-needscore-queue-")
        try {
            val clock = MutableMillisClock()
            val lib = TextbookLibrary(root.resolve("library"), clock)
            val book = lib.importBook(syntheticTextbook(root.resolve("synthetic")))
            assertTrue(book.lessons.size >= 2)
            val easyLesson = book.lessons[0]
            val weakLesson = book.lessons[1]

            // Both become due cards in book_reviews
            lib.saveAnswer(book.id, easyLesson.id, QuestionMode.SHORT_ANSWER, PracticeAnswer("Not yet", true, false))
            lib.saveAnswer(book.id, weakLesson.id, QuestionMode.SHORT_ANSWER, PracticeAnswer("Not yet", true, false))

            // Easy lesson: low weight, high mastery, no error
            repeat(4) {
                lib.recordMasteryEvidence(
                    bookId = book.id,
                    tocNodeId = easyLesson.id,
                    page = easyLesson.firstPage,
                    canonicalName = easyLesson.title,
                    signal = LearnerSignalType.POST_QUIZ_T1,
                    scoreRatio = 1.0,
                    guessed = false,
                    syllabusWeight = 0.8,
                )
            }
            // Weak lesson: high syllabus weight, active GRAPH_SHIFT_OR_ROTATION error + 3 rereads
            lib.recordMasteryEvidence(
                bookId = book.id,
                tocNodeId = weakLesson.id,
                page = weakLesson.firstPage,
                canonicalName = weakLesson.title,
                signal = LearnerSignalType.MISCONCEPTION_DIAG,
                errorClass = MisconceptionErrorClass.GRAPH_SHIFT_OR_ROTATION,
                syllabusWeight = 1.6,
            )
            repeat(3) {
                lib.recordMasteryEvidence(
                    bookId = book.id,
                    tocNodeId = weakLesson.id,
                    page = weakLesson.firstPage,
                    canonicalName = weakLesson.title,
                    signal = LearnerSignalType.REREAD_BACKTRACK,
                    durationMs = 45_000L,
                    syllabusWeight = 1.6,
                )
            }

            val queue = lib.reviewQueue()
            assertEquals(weakLesson.id, queue.cards.first().lesson, "high-NeedScore weak concept must rank first")

            val homeRec = assertNotNull(lib.topHomeRecommendation(book.id))
            assertEquals(weakLesson.id, homeRec.tocNodeId)
            assertTrue("图形移动方向" in homeRec.metaLine, "home meta line should mention plain-Chinese error reason: ${homeRec.metaLine}")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun learningGainCohortUnlocksT2StrictlyAtT0Plus72hAndSilentlyMixesIntoDailyQueue() {
        val root = Files.createTempDirectory("coach-gain-t2-72h-")
        try {
            val clock = MutableMillisClock(Instant.parse("2026-11-22T09:00:00Z").toEpochMilli())
            val lib = TextbookLibrary(root.resolve("library"), clock)
            val book = lib.importBook(syntheticTextbook(root.resolve("synthetic")))
            val lesson = book.lessons.first()
            val conceptKey = MasteryEngine.conceptKeyFor(book.id, lesson.id)

            // T0 pretest (score 0.0, not guessed)
            val t0Start = clock.millis()
            lib.recordMasteryEvidence(
                bookId = book.id,
                tocNodeId = lesson.id,
                page = lesson.firstPage,
                canonicalName = lesson.title,
                signal = LearnerSignalType.PRETEST_T0,
                scoreRatio = 0.0,
                guessed = false,
                conceptKeyOverride = conceptKey,
            )
            val cohortT0 = assertNotNull(
                lib.recordLearningGainT0(book.id, lesson.id, conceptKey, rawScore = 0.0, guessed = false),
            )
            assertEquals(t0Start, cohortT0.t0At)
            assertEquals(t0Start + 72L * 3600L * 1000L, cohortT0.t2EligibleAfter)
            assertFalse(cohortT0.isT2Eligible(t0Start + 72L * 3600L * 1000L - 1L))
            assertTrue(cohortT0.isT2Eligible(t0Start + 72L * 3600L * 1000L))

            // Read + T1 post-test
            clock.advanceMillis(12 * 60_000L)
            lib.recordLearningGainRead(book.id, lesson.id, conceptKey, addedDwellSec = 210)
            clock.advanceMillis(2 * 60_000L)
            lib.recordLearningGainT1(book.id, lesson.id, conceptKey, rawScore = 1.0, guessed = false)

            // 1 millisecond before 72h: NOT in daily review queue
            clock.advanceMillis((t0Start + MasteryEngine.T2_DELAY_MS - 1L) - clock.millis())
            assertTrue(
                lib.reviewQueue().cards.none { it.lesson.startsWith(MasteryStore.T2_LESSON_PREFIX) },
                "T2 card must NOT appear before t0_at + 72h",
            )

            // Exactly at t0_at + 72h: silently mixed into today's normal review queue
            clock.advanceMillis(1L)
            val dueQueue = lib.reviewQueue()
            val t2Card = dueQueue.cards.firstOrNull { it.lesson.startsWith(MasteryStore.T2_LESSON_PREFIX) }
            assertNotNull(t2Card, "T2 delayed transfer card must silently mix into daily review queue at T0 + 72h")

            // Learner rates GOOD on the T2 card -> cohort closes with t2_score = 1.0 and normalized gain = 1.0
            lib.saveReviewRating(t2Card, FsrsRating.GOOD, PracticeAnswer("Explained clearly", revealed = true, understood = true, guessed = false))
            val completedCohort = lib.learningGainCohorts(book.id).single()
            assertNotNull(completedCohort.t2At)
            assertEquals(1.0, completedCohort.t2Score)
            assertEquals(1.0, assertNotNull(completedCohort.normalizedGain()), 1e-6)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun quietProactivityPolicyEnforcesMaxOnePerSection10PageCooldownAndAuditableEvidence() {
        val tracker = PageReadingTracker(defaultMedianFloorMs = 10_000L)
        // Normal baseline pages (10s each)
        for (p in 1..4) {
            tracker.onPageEntered(p, isJump = false, sectionId = "sec-1")
            assertFalse(tracker.recordDwell(p, 10_000L, sectionId = "sec-1"))
        }
        // Page 5 -> 6 -> back to 5 (backtrack #1) -> 6 -> back to 5 (backtrack #2, cumulative dwell 30s > 2.2 * 10s)
        tracker.onPageEntered(5, isJump = false, sectionId = "sec-2")
        tracker.recordDwell(5, 10_000L, sectionId = "sec-2")
        tracker.onPageEntered(6, isJump = false, sectionId = "sec-2")
        tracker.recordDwell(6, 10_000L, sectionId = "sec-2")
        assertTrue(tracker.onPageEntered(5, isJump = false, sectionId = "sec-2"))
        tracker.recordDwell(5, 10_000L, sectionId = "sec-2")
        tracker.onPageEntered(6, isJump = false, sectionId = "sec-2")
        assertTrue(tracker.onPageEntered(5, isJump = false, sectionId = "sec-2"))
        assertTrue(tracker.recordDwell(5, 10_000L, sectionId = "sec-2"))
        assertEquals(2, tracker.backtrackCount(5))
        assertEquals(30_000L, tracker.cumulativeDwellMs(5))

        val policy = QuietProactivityPolicy()
        val w1 = policy.evaluate(
            bookId = "b1",
            tocNodeId = "sec-2",
            page = 5,
            conflictInfo = null,
            mastery = null,
            rereadCountOnPage = tracker.backtrackCount(5),
            cumulativeDwellMs = tracker.cumulativeDwellMs(5),
            personalMedianDwellMs = tracker.personalMedianDwellMs(excludePage = 5),
            quoteAnchor = "marginal efficiency",
        )
        assertNotNull(w1)
        assertEquals(MarginWhisperRule.REREAD_FRICTION_ON_PAGE, w1.rule)
        assertTrue(w1.triggerEvidenceJson.isNotBlank() && "REREAD_FRICTION_ON_PAGE" in w1.triggerEvidenceJson)
        assertTrue(w1.collapsedText.length <= 26)

        // Same section cannot trigger a second whisper
        assertNull(
            policy.evaluate(
                bookId = "b1", tocNodeId = "sec-2", page = 6,
                conflictInfo = "conflict" to "detail", mastery = null,
                rereadCountOnPage = 3, cumulativeDwellMs = 60_000L, personalMedianDwellMs = 10_000.0,
            ),
        )

        // User clicks × -> 10-page silent cooldown across other sections
        policy.onUserDismiss()
        assertEquals(10, policy.cooldownPagesRemaining)
        for (step in 1..9) {
            policy.onPageTurn()
            assertNull(
                policy.evaluate(
                    bookId = "b1", tocNodeId = "sec-cool-$step", page = 10 + step,
                    conflictInfo = "conflict" to "detail", mastery = null,
                    rereadCountOnPage = 0, cumulativeDwellMs = 0L, personalMedianDwellMs = 10_000.0,
                ),
                "whisper must stay suppressed during 10-page cooldown (page turn $step)",
            )
        }
        // 10th page turn ends cooldown
        policy.onPageTurn()
        assertEquals(0, policy.cooldownPagesRemaining)
        val w2 = policy.evaluate(
            bookId = "b1", tocNodeId = "sec-after-cool", page = 25,
            conflictInfo = "口径差异" to "讲义与课本边界条件不同", mastery = null,
            rereadCountOnPage = 0, cumulativeDwellMs = 0L, personalMedianDwellMs = 10_000.0,
            quoteAnchor = "boundary condition",
        )
        assertNotNull(w2)
        assertEquals(MarginWhisperRule.CROSS_MATERIAL_CONFLICT, w2.rule)

        // Section checkpoint: never on TOC jump; once on sequential forward advance after genuine dwell
        assertFalse(tracker.shouldOfferSectionCheckpoint("sec-1", 4, fromPage = 4, toPage = 8, isJump = true))
        assertTrue(tracker.shouldOfferSectionCheckpoint("sec-1", 4, fromPage = 4, toPage = 5, isJump = false))
        assertFalse(tracker.shouldOfferSectionCheckpoint("sec-1", 4, fromPage = 4, toPage = 5, isJump = false))
    }
}
