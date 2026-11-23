package com.coach.desktop.books

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * LearningCoach 2.0-MODES (`TON-1805`): Six orthogonal study modes for a single book,
 * three practical adjustment knobs, smart default resolution, and lossless SQLite storage.
 */
internal enum class BookStudyMode(
    val titleZh: String,
    val shortSummaryZh: String,
    val tag: String,
) {
    READ_THROUGH(
        titleZh = "逐节精读",
        shortSummaryZh = "按原书小节顺序读，配本节导读与读后小测。",
        tag = "mode-option-READ_THROUGH",
    ),
    BOOK_MAP(
        titleZh = "先摸全貌",
        shortSummaryZh = "每章一页纸、核心术语与跨章关系，快速通览全书。",
        tag = "mode-option-BOOK_MAP",
    ),
    EXAM_FOCUS(
        titleZh = "考点优先",
        shortSummaryZh = "按考纲与周次筛出必考节，略读背景叙事。",
        tag = "mode-option-EXAM_FOCUS",
    ),
    DIAGNOSTIC_SKIP(
        titleZh = "先测后读",
        shortSummaryZh = "章首分节摸底，答对的小节自动折起跳过。",
        tag = "mode-option-DIAGNOSTIC_SKIP",
    ),
    PROBLEM_DRIVEN(
        titleZh = "做题带读",
        shortSummaryZh = "从原书章末习题出发，卡住再跳回正文查公式。",
        tag = "mode-option-PROBLEM_DRIVEN",
    ),
    QUESTION_DRIVEN(
        titleZh = "带着问题读",
        shortSummaryZh = "输入问题或论文题目，只给全书最相关的章节与页。",
        tag = "mode-option-QUESTION_DRIVEN",
    ),
    ;

    fun displayTitle(examMode: Boolean): String = when {
        !examMode && this == PROBLEM_DRIVEN -> "自测带读"
        !examMode && this == EXAM_FOCUS -> READ_THROUGH.titleZh
        else -> titleZh
    }

    fun displaySummary(examMode: Boolean): String = when (this) {
        READ_THROUGH -> if (examMode) shortSummaryZh else "按章节顺序读，配本节导读与读后自测。"
        BOOK_MAP -> if (examMode) shortSummaryZh else "每章一页纸、核心概念与跨章关系，快速通览全书。"
        EXAM_FOCUS -> shortSummaryZh
        DIAGNOSTIC_SKIP -> if (examMode) shortSummaryZh else "先做几道自测，已掌握的部分自动折起跳过。"
        PROBLEM_DRIVEN -> if (examMode) shortSummaryZh else "先从自测题入手，卡住再跳回正文查对应段落。"
        QUESTION_DRIVEN -> if (examMode) shortSummaryZh else "输入想弄懂的问题，只看全书最相关的章节与页。"
    }

    companion object {
        fun availableModes(examMode: Boolean): List<BookStudyMode> =
            if (examMode) entries else entries.filter { it != EXAM_FOCUS }

        fun fromWire(raw: String?): BookStudyMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: READ_THROUGH
    }
}

internal enum class ExplainDetailLevel(val labelZh: String, val tag: String) {
    ORIGINAL_ONLY("只看原文", "mode-detail-original"),
    BRIEF("简短提示", "mode-detail-brief"),
    DETAILED("细讲", "mode-detail-detailed"),
    ;

    companion object {
        fun fromWire(raw: String?): ExplainDetailLevel =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DETAILED
    }
}

internal enum class ExplainLanguage(val labelZh: String, val shortZh: String, val tag: String) {
    ZH("中文", "中文", "mode-lang-zh"),
    BILINGUAL("中英对照", "中英", "mode-lang-bilingual"),
    EN("英文", "英文", "mode-lang-en"),
    ;

    companion object {
        fun fromWire(raw: String?): ExplainLanguage =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: BILINGUAL
    }
}

internal enum class StudyPacePreset(val labelZh: String, val dailyMinutes: Int?, val tag: String) {
    UNPACED("按自己节奏", null, "mode-pace-unpaced"),
    MIN_30("每天 30 分钟", 30, "mode-pace-30"),
    MIN_60("每天 60 分钟", 60, "mode-pace-60"),
    TARGET_DATE("按目标日倒排", -1, "mode-pace-date"),
    ;

    companion object {
        fun fromMinutes(minutes: Int?, targetDate: String?): StudyPacePreset = when {
            !targetDate.isNullOrBlank() -> TARGET_DATE
            minutes == 30 -> MIN_30
            minutes == 60 -> MIN_60
            else -> UNPACED
        }
    }
}

internal data class BookStudyPreference(
    val bookId: String,
    val mode: BookStudyMode = BookStudyMode.READ_THROUGH,
    val userLocked: Boolean = false,
    val defaultReason: String = "英文专业教材，默认逐节精读配中英对照；随时可换。",
    val detailLevel: ExplainDetailLevel = ExplainDetailLevel.DETAILED,
    val explainLang: ExplainLanguage = ExplainLanguage.BILINGUAL,
    val dailyMinutes: Int? = null,
    val targetDate: String? = null,
    val inquiryQuestion: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val examMode: Boolean = false,
) {
    val pacePreset: StudyPacePreset
        get() = StudyPacePreset.fromMinutes(dailyMinutes, targetDate)

    fun compactBadgeText(): String = buildString {
        append("学法：")
        append(mode.displayTitle(examMode))
        if (detailLevel == ExplainDetailLevel.ORIGINAL_ONLY) {
            append(" · 只看原文")
        }
    }
}

internal object SmartDefaultModeResolver {
    private val LATIN_WORD_RE = Regex("[A-Za-z]{4,}")

    fun resolve(
        bookId: String,
        title: String,
        format: String,
        pageCount: Int,
        sampleText: String,
        materialKind: String?,
        inCourse: Boolean,
        courseExamDate: String?,
        inBooklist: Boolean,
        testedSectionRatio: Double,
        readChapterRatio: Double,
        today: LocalDate = LocalDate.now(),
        courseIsExam: Boolean = !courseExamDate.isNullOrBlank(),
    ): BookStudyPreference {
        val isEnglishHeavy = LATIN_WORD_RE.findAll(sampleText.ifBlank { title }).count() >= 5
        val defaultLang = if (isEnglishHeavy) ExplainLanguage.BILINGUAL else ExplainLanguage.ZH
        val daysToExam = courseExamDate?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(raw.trim())) }.getOrNull()
        }
        val activeExam = inCourse && courseIsExam

        // 1. Exam course with imminent exam (<= 21 days) & partial prior study -> 先测后读
        if (activeExam && daysToExam != null && daysToExam in 0..21 && (testedSectionRatio >= 0.20 || readChapterRatio >= 0.25)) {
            return BookStudyPreference(
                bookId = bookId,
                mode = BookStudyMode.DIAGNOSTIC_SKIP,
                userLocked = false,
                defaultReason = "距考试还有 $daysToExam 天且已学过部分章节，默认先测后读，会的直接跳过。",
                detailLevel = ExplainDetailLevel.DETAILED,
                explainLang = defaultLang,
                targetDate = courseExamDate,
                examMode = true,
            )
        }
        // 2. Linked to an exam course (exam date set or explicitly marked 要考) -> 考点优先
        if (activeExam) {
            return BookStudyPreference(
                bookId = bookId,
                mode = BookStudyMode.EXAM_FOCUS,
                userLocked = false,
                defaultReason = "已关联课程备考，默认按考纲权重挑重点学，随时可换。",
                detailLevel = ExplainDetailLevel.DETAILED,
                explainLang = defaultLang,
                targetDate = courseExamDate,
                examMode = true,
            )
        }
        // 3. Standalone book with >= 30% chapters already read/tested -> 先测后读
        if (testedSectionRatio >= 0.30 || readChapterRatio >= 0.30) {
            return BookStudyPreference(
                bookId = bookId,
                mode = BookStudyMode.DIAGNOSTIC_SKIP,
                userLocked = false,
                defaultReason = "这本书已有学习记录，默认先测摸底，只补薄弱小节。",
                detailLevel = ExplainDetailLevel.BRIEF,
                explainLang = defaultLang,
            )
        }
        // 4. Monograph or booklist member -> 先摸全貌
        if (materialKind.equals("MONOGRAPH", ignoreCase = true) || inBooklist) {
            return BookStudyPreference(
                bookId = bookId,
                mode = BookStudyMode.BOOK_MAP,
                userLocked = false,
                defaultReason = "学术书单或专著默认先给每章一页纸摸清全貌，也可切逐节精读。",
                detailLevel = ExplainDetailLevel.BRIEF,
                explainLang = defaultLang,
            )
        }
        // 5. General reading (short EPUB narrative without textbook signals) -> 只看原文
        val isShortNarrativeEpub = format.equals("epub", ignoreCase = true) &&
            !materialKind.equals("TEXTBOOK", ignoreCase = true) &&
            pageCount < 120
        if (isShortNarrativeEpub) {
            return BookStudyPreference(
                bookId = bookId,
                mode = BookStudyMode.READ_THROUGH,
                userLocked = false,
                defaultReason = "一般读物默认只看原文不打扰，需要时可随时开伴读或换学法。",
                detailLevel = ExplainDetailLevel.ORIGINAL_ONLY,
                explainLang = ExplainLanguage.ZH,
            )
        }
        // 6. Default standalone textbook -> 逐节精读 + 细讲 + 中英对照
        return BookStudyPreference(
            bookId = bookId,
            mode = BookStudyMode.READ_THROUGH,
            userLocked = false,
            defaultReason = if (isEnglishHeavy) {
                "英文专业教材，默认逐节精读配中英对照；想先看全书脉络可切「先摸全貌」。"
            } else {
                "专业教材默认逐节精读；想先看全书脉络可切「先摸全貌」。"
            },
            detailLevel = ExplainDetailLevel.DETAILED,
            explainLang = defaultLang,
        )
    }
}

internal data class NumberedBookChapter(
    val number: Int,
    val title: String,
    val firstPage: Int,
    val lastPage: Int,
)

internal data class NumberedChapterPosition(
    val chapterNumber: Int,
    val totalChapters: Int,
    val current: NumberedBookChapter?,
    val next: NumberedBookChapter?,
)

/**
 * Resolves true numbered chapters (`1..24` on Health Economics) rather than raw TOC node counts
 * (which include Preface, Brief Contents, Acknowledgments, Index, etc.).
 */
internal object StudyModeChapterNumbering {
    private val TOP_CHAPTER_RE = Regex("""^\s*(\d{1,2})\s+[A-Z\u4e00-\u9fff].*""")

    fun numberedChapters(book: Textbook): List<NumberedBookChapter> =
        numberedChaptersFromLessons(book.lessons, book.pages.lastOrNull()?.number ?: 1)

    fun numberedChaptersFromLessons(lessons: List<BookLesson>, maxBookPage: Int): List<NumberedBookChapter> {
        val raw = mutableListOf<Triple<Int, String, Int>>()
        val seenNums = HashSet<Int>()
        var lastStartPage = 0
        for (lesson in lessons) {
            val cleanTitle = lesson.title.trim().replace(Regex("""（\d+/\d+）$"""), "").trim()
            val m = TOP_CHAPTER_RE.matchEntire(cleanTitle) ?: continue
            val num = m.groupValues[1].toIntOrNull() ?: continue
            if (num <= 0 || num in seenNums) continue
            if (lesson.firstPage < lastStartPage) continue
            if (raw.isNotEmpty() && num != raw.last().first + 1 && num > raw.last().first + 3) continue
            seenNums.add(num)
            lastStartPage = lesson.firstPage
            raw.add(Triple(num, cleanTitle, lesson.firstPage))
        }
        if (raw.size < 2) return emptyList()
        return raw.mapIndexed { idx, (num, title, startP) ->
            val endP = if (idx + 1 < raw.size) (raw[idx + 1].third - 1).coerceAtLeast(startP) else maxBookPage.coerceAtLeast(startP)
            NumberedBookChapter(number = num, title = title, firstPage = startP, lastPage = endP)
        }
    }

    fun resolve(book: Textbook, currentPage: Int, chapterGroupFirstPage: Int): NumberedChapterPosition {
        val numbered = numberedChapters(book)
        if (numbered.isNotEmpty()) {
            val probePage = if (currentPage > 0) currentPage else chapterGroupFirstPage
            val idx = numbered.indexOfFirst { probePage in it.firstPage..it.lastPage }
                .takeIf { it >= 0 }
                ?: numbered.indexOfLast { it.firstPage <= probePage }.coerceAtLeast(0)
            val cur = numbered[idx]
            return NumberedChapterPosition(
                chapterNumber = cur.number,
                totalChapters = numbered.size,
                current = cur,
                next = numbered.getOrNull(idx + 1),
            )
        }
        val fallback = BookChapterResolver.chapters(book)
        val idx = fallback.indexOfFirst { chapterGroupFirstPage in it.firstPage..it.lastPage }.coerceAtLeast(0)
        val nextSpan = fallback.getOrNull(idx + 1)
        return NumberedChapterPosition(
            chapterNumber = idx + 1,
            totalChapters = fallback.size.coerceAtLeast(1),
            current = fallback.getOrNull(idx)?.let { NumberedBookChapter(idx + 1, it.title, it.firstPage, it.lastPage) },
            next = nextSpan?.let { NumberedBookChapter(idx + 2, it.title, it.firstPage, it.lastPage) },
        )
    }
}

internal data class BookMapSectionRole(
    val sectionId: String,
    val roleLabelZh: String,
    val oneLineZh: String,
    val pdfPage: Int,
    val quoteAnchor: String = "",
    val skipUnlessExam: Boolean = false,
)

internal data class BookMapCoreClaim(
    val claim: String,
    val importance: String,
    val pdfPage: Int,
    val quoteAnchor: String,
)

internal data class BookMapOnePagerPack(
    val headlineZh: String,
    val bigPictureThesisZh: String,
    val sectionArchitecture: List<BookMapSectionRole>,
    val prerequisiteChapter: String,
    val downstreamChapter: String,
    val estimatedMinutes: Int = 20,
    val readingDepth: String = "CORE_SKIM",
    val coreClaims: List<BookMapCoreClaim> = emptyList(),
)

internal data class ExamFocusMustSection(
    val sectionId: String,
    val weightTag: String,
    val examAngleZh: String,
    val pdfPage: Int,
    val quoteAnchor: String = "",
    val scoringRubric: List<String> = emptyList(),
)

internal data class ExamFocusSkimSection(
    val sectionId: String,
    val weightTag: String,
    val whySkimZh: String,
    val pdfPage: Int = 1,
    val quoteAnchor: String = "",
)

internal data class ExamFocusTrapItem(
    val trap: String,
    val wrongReasoning: String,
    val correction: String,
    val pdfPage: Int,
    val quoteAnchor: String,
)

internal data class ExamFocusFormulaItem(
    val name: String,
    val formulaLatex: String,
    val coefficientNote: String,
    val examUsage: String,
    val pdfPage: Int,
    val quoteAnchor: String,
)

internal data class ExamFocusMiniCheckItem(
    val prompt: String,
    val options: List<String>,
    val answerIndex: Int,
    val rubricOnly: String,
    val pdfPage: Int,
    val quoteAnchor: String,
)

internal data class ExamFocusTriagePack(
    val headlineZh: String,
    val courseWeekBadgeZh: String,
    val mustStudySections: List<ExamFocusMustSection>,
    val skimOrSkipSections: List<ExamFocusSkimSection>,
    val examPitfallAlertZh: String,
    val traps: List<ExamFocusTrapItem> = emptyList(),
    val formulas: List<ExamFocusFormulaItem> = emptyList(),
    val miniChecks: List<ExamFocusMiniCheckItem> = emptyList(),
)

internal data class DiagnosticSkipGateItem(
    val gateId: String,
    val sectionTitle: String,
    val targetConcept: String,
    val questionType: String,
    val questionPrompt: String,
    val options: List<String>,
    val correctIndex: Int,
    val passSkipNote: String,
    val missRemediation: String,
    val missJumpPdfPage: Int,
    val quoteAnchor: String,
)

internal data class DiagnosticSkipPack(
    val skipPolicy: String,
    val gates: List<DiagnosticSkipGateItem>,
)

internal data class ProblemDrivenProblemItem(
    val problemId: String,
    val title: String,
    val exercisePdfPage: Int,
    val exerciseQuoteAnchor: String,
    val attemptPrompt: String,
    val scaffoldingHints: List<String>,
    val keyDerivationCheck: String,
    val bodyPdfPage: Int,
    val quoteAnchor: String,
    val answerOutline: List<String>,
)

internal data class ProblemDrivenPack(
    val strategyNote: String,
    val problems: List<ProblemDrivenProblemItem>,
)

internal data class QuestionDrivenEvidenceHit(
    val hitId: String,
    val bodyJumpPage: Int,
    val quoteAnchor: String,
    val howItAnswersQuestion: String,
    val limitOrCounterpoint: String,
)

internal data class QuestionDrivenPack(
    val questionFocus: String,
    val directConclusion: String,
    val evidenceHits: List<QuestionDrivenEvidenceHit>,
    val argumentationScaffold: List<String>,
    val antiGhostwriteNotice: String,
    val followUpQuestions: List<String>,
)

internal data class ReadThroughModeSectionItem(
    val sectionId: String,
    val title: String,
    val pdfPage: Int,
    val quoteAnchor: String,
    val keyConcept: String,
    val plainExplanation: String,
    val conceptChain: List<String>,
    val formulaNote: String,
    val commonMisreading: String,
    val microCheckPrompt: String,
    val microCheckHint: String,
)

internal data class ReadThroughModePack(
    val learningArc: String,
    val sections: List<ReadThroughModeSectionItem>,
    val takeawayChecklist: List<String>,
)

internal data class BookMapCluster(
    val clusterId: String,
    val title: String,
    val themeSummary: String,
    val chapterNums: List<Int>,
)

internal data class BookMapDependencyEdge(
    val fromChapter: Int,
    val toChapter: Int,
    val relation: String,
    val reason: String,
)

internal data class BookMapFastSkimStep(
    val chapterNum: Int,
    val minutes: Int,
    val focus: String,
)

internal data class BookMapGlossaryEntry(
    val en: String,
    val zh: String,
    val chapterNum: Int,
)

internal data class WholeBookMapMeta(
    val bookId: String,
    val bookTitle: String,
    val chapterCount: Int,
    val clusters: List<BookMapCluster>,
    val edges: List<BookMapDependencyEdge>,
    val fastSkimOrder40Min: List<BookMapFastSkimStep>,
    val glossary: List<BookMapGlossaryEntry>,
    val estimatedCostUsd: Double = 0.0,
)

internal object StudyModeStore {
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val DDL = """
        CREATE TABLE IF NOT EXISTS book_study_preferences(
            book_id TEXT PRIMARY KEY,
            study_mode TEXT NOT NULL,
            user_locked INTEGER NOT NULL DEFAULT 0,
            default_reason TEXT NOT NULL DEFAULT '',
            detail_level TEXT NOT NULL DEFAULT 'DETAILED',
            explain_lang TEXT NOT NULL DEFAULT 'BILINGUAL',
            daily_minutes INTEGER,
            target_date TEXT,
            inquiry_question TEXT NOT NULL DEFAULT '',
            updated_at INTEGER NOT NULL
        )
    """

    private const val PACKS_DDL = """
        CREATE TABLE IF NOT EXISTS book_study_mode_packs(
            book_id TEXT NOT NULL,
            chapter_num INTEGER NOT NULL,
            study_mode TEXT NOT NULL,
            detail_level TEXT NOT NULL DEFAULT 'DETAILED',
            explain_lang TEXT NOT NULL DEFAULT 'BILINGUAL',
            inquiry_key TEXT NOT NULL DEFAULT '',
            pack_json TEXT NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(book_id, chapter_num, study_mode, detail_level, explain_lang, inquiry_key)
        )
    """

    private const val BOOK_MAP_META_DDL = """
        CREATE TABLE IF NOT EXISTS book_map_meta(
            book_id TEXT PRIMARY KEY,
            graph_json TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        )
    """

    private fun openDb(libraryDir: Path): Connection =
        DriverManager.getConnection("jdbc:sqlite:${libraryDir.resolve("library.sqlite")}").apply {
            createStatement().use {
                it.execute("PRAGMA busy_timeout=5000")
                it.execute(DDL.trimIndent())
                it.execute(PACKS_DDL.trimIndent())
                it.execute(BOOK_MAP_META_DDL.trimIndent())
            }
            migrateModePacksIfNeeded(this)
        }

    private fun migrateModePacksIfNeeded(db: Connection) {
        val cols = mutableSetOf<String>()
        db.createStatement().use { sql ->
            sql.executeQuery("PRAGMA table_info(book_study_mode_packs)").use { rs ->
                while (rs.next()) cols += rs.getString("name").orEmpty()
            }
        }
        if ("detail_level" in cols && "explain_lang" in cols && "inquiry_key" in cols) return
        val prevAutoCommit = db.autoCommit
        db.autoCommit = false
        try {
            db.createStatement().use { sql ->
                sql.execute("DROP TABLE IF EXISTS book_study_mode_packs_v7")
                sql.execute("ALTER TABLE book_study_mode_packs RENAME TO book_study_mode_packs_v7")
                sql.execute(PACKS_DDL.trimIndent())
                sql.execute(
                    """INSERT INTO book_study_mode_packs(book_id, chapter_num, study_mode, pack_json, updated_at)
                       SELECT book_id, chapter_num, study_mode, pack_json, updated_at FROM book_study_mode_packs_v7""",
                )
                sql.execute("DROP TABLE book_study_mode_packs_v7")
            }
            db.commit()
        } catch (e: Exception) {
            runCatching { db.rollback() }
            throw e
        } finally {
            db.autoCommit = prevAutoCommit
        }
    }

    internal fun inquiryKey(inquiryQuestion: String): String =
        inquiryQuestion.trim().lowercase().take(160)

    fun saveModePackJson(library: TextbookLibrary, bookId: String, chapterNum: Int, mode: BookStudyMode, packJson: String) =
        saveModePackJson(
            library = library,
            bookId = bookId,
            chapterNum = chapterNum,
            mode = mode,
            detailLevel = ExplainDetailLevel.DETAILED,
            explainLang = ExplainLanguage.BILINGUAL,
            inquiryQuestion = "",
            packJson = packJson,
        )

    fun saveModePackJson(
        library: TextbookLibrary,
        bookId: String,
        chapterNum: Int,
        mode: BookStudyMode,
        detailLevel: ExplainDetailLevel = ExplainDetailLevel.DETAILED,
        explainLang: ExplainLanguage = ExplainLanguage.BILINGUAL,
        inquiryQuestion: String = "",
        packJson: String,
    ) {
        runCatching {
            openDb(library.directory).use { db ->
                db.prepareStatement(
                    """INSERT INTO book_study_mode_packs(book_id, chapter_num, study_mode, detail_level, explain_lang, inquiry_key, pack_json, updated_at)
                       VALUES(?,?,?,?,?,?,?,?)
                       ON CONFLICT(book_id, chapter_num, study_mode, detail_level, explain_lang, inquiry_key) DO UPDATE SET
                       pack_json=excluded.pack_json, updated_at=excluded.updated_at""".trimIndent(),
                ).use { s ->
                    s.setString(1, bookId)
                    s.setInt(2, chapterNum)
                    s.setString(3, mode.name)
                    s.setString(4, detailLevel.name)
                    s.setString(5, explainLang.name)
                    s.setString(6, if (mode == BookStudyMode.QUESTION_DRIVEN) inquiryKey(inquiryQuestion) else "")
                    s.setString(7, packJson)
                    s.setLong(8, System.currentTimeMillis())
                    s.executeUpdate()
                }
            }
        }
    }

    fun loadRawModePackJson(
        library: TextbookLibrary,
        bookId: String,
        chapterNum: Int,
        mode: BookStudyMode,
        detailLevel: ExplainDetailLevel = ExplainDetailLevel.DETAILED,
        explainLang: ExplainLanguage = ExplainLanguage.BILINGUAL,
        inquiryQuestion: String = "",
        fallbackAnyVariant: Boolean = false,
    ): String? = runCatching {
        val inqKey = if (mode == BookStudyMode.QUESTION_DRIVEN) inquiryKey(inquiryQuestion) else ""
        openDb(library.directory).use { db ->
            val exact = db.prepareStatement(
                """SELECT pack_json FROM book_study_mode_packs
                   WHERE book_id=? AND chapter_num=? AND study_mode=? AND detail_level=? AND explain_lang=? AND inquiry_key=?""",
            ).use { s ->
                s.setString(1, bookId)
                s.setInt(2, chapterNum)
                s.setString(3, mode.name)
                s.setString(4, detailLevel.name)
                s.setString(5, explainLang.name)
                s.setString(6, inqKey)
                s.executeQuery().use { r ->
                    if (r.next()) r.getString(1)?.takeIf { it.isNotBlank() } else null
                }
            }
            if (exact != null) return@use exact
            if (!fallbackAnyVariant) return@use null
            db.prepareStatement(
                """SELECT pack_json FROM book_study_mode_packs
                   WHERE book_id=? AND chapter_num=? AND study_mode=?
                   ORDER BY updated_at DESC LIMIT 1""",
            ).use { s ->
                s.setString(1, bookId)
                s.setInt(2, chapterNum)
                s.setString(3, mode.name)
                s.executeQuery().use { r ->
                    if (r.next()) r.getString(1)?.takeIf { it.isNotBlank() } else null
                }
            }
        }
    }.getOrNull()

    private fun loadRawModePackObj(
        library: TextbookLibrary,
        bookId: String,
        chapterNum: Int,
        mode: BookStudyMode,
        detailLevel: ExplainDetailLevel = ExplainDetailLevel.DETAILED,
        explainLang: ExplainLanguage = ExplainLanguage.BILINGUAL,
        inquiryQuestion: String = "",
        fallbackAnyVariant: Boolean = true,
    ): JsonObject? {
        val raw = loadRawModePackJson(library, bookId, chapterNum, mode, detailLevel, explainLang, inquiryQuestion, fallbackAnyVariant)
            ?: return null
        return runCatching { jsonParser.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    fun loadBookMapPack(library: TextbookLibrary, bookId: String, chapterNum: Int): BookMapOnePagerPack? =
        loadRawModePackObj(library, bookId, chapterNum, BookStudyMode.BOOK_MAP, fallbackAnyVariant = true)?.let(::parseBookMapPack)

    fun loadBookMapPack(library: TextbookLibrary, bookId: String, chapterNum: Int, preference: BookStudyPreference): BookMapOnePagerPack? {
        val fallbackAny = preference.detailLevel == ExplainDetailLevel.DETAILED &&
            preference.explainLang == ExplainLanguage.BILINGUAL &&
            preference.inquiryQuestion.isBlank()
        return loadRawModePackObj(library, bookId, chapterNum, BookStudyMode.BOOK_MAP, preference.detailLevel, preference.explainLang, "", fallbackAnyVariant = fallbackAny)?.let(::parseBookMapPack)
    }

    fun parseBookMapPack(rawJson: String): BookMapOnePagerPack? =
        runCatching { jsonParser.parseToJsonElement(rawJson).jsonObject }.getOrNull()?.let(::parseBookMapPack)

    fun parseBookMapPack(root: JsonObject): BookMapOnePagerPack? {
        val fs = root["firstScreen"]?.jsonObject ?: root
        val thesis = fs["bigPictureThesisZh"]?.jsonPrimitive?.contentOrNull
            ?: fs["oneSentenceVerdictZh"]?.jsonPrimitive?.contentOrNull
            ?: fs["oneLiner"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val headline = fs["headlineZh"]?.jsonPrimitive?.contentOrNull
            ?: fs["chapterTitle"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val archArr = (fs["sectionArchitecture"] as? JsonArray) ?: (fs["sections"] as? JsonArray) ?: JsonArray(emptyList())
        val roles = archArr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            BookMapSectionRole(
                sectionId = o["sectionId"]?.jsonPrimitive?.contentOrNull
                    ?: o["sectionTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                roleLabelZh = o["roleLabelZh"]?.jsonPrimitive?.contentOrNull
                    ?: if (o["skipUnlessExam"]?.jsonPrimitive?.booleanOrNull == true) "按需速览" else "核心骨架",
                oneLineZh = o["oneLineZh"]?.jsonPrimitive?.contentOrNull
                    ?: o["summary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                pdfPage = o["pdfPage"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: 0,
                quoteAnchor = o["quoteAnchor"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                skipUnlessExam = o["skipUnlessExam"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }
        val claims = (fs["coreClaims"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val c = o["claim"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            BookMapCoreClaim(
                claim = c,
                importance = o["importance"]?.jsonPrimitive?.contentOrNull ?: "CORE",
                pdfPage = o["pdfPage"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: 0,
                quoteAnchor = o["quoteAnchor"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        val links = fs["chapterLinks"] as? JsonObject
        val prereqStr = links?.get("prerequisiteChapter")?.jsonPrimitive?.contentOrNull
            ?: (fs["prerequisites"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.joinToString(" · ")
        val unlockStr = links?.get("downstreamChapter")?.jsonPrimitive?.contentOrNull
            ?: (fs["unlocks"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.joinToString(" · ")
        return BookMapOnePagerPack(
            headlineZh = headline,
            bigPictureThesisZh = thesis,
            sectionArchitecture = roles,
            prerequisiteChapter = prereqStr,
            downstreamChapter = unlockStr,
            estimatedMinutes = fs["estimatedMinutes"]?.jsonPrimitive?.intOrNull ?: 20,
            readingDepth = fs["readingDepth"]?.jsonPrimitive?.contentOrNull ?: "CORE_SKIM",
            coreClaims = claims,
        )
    }

    fun loadExamFocusPack(library: TextbookLibrary, bookId: String, chapterNum: Int): ExamFocusTriagePack? =
        loadRawModePackObj(library, bookId, chapterNum, BookStudyMode.EXAM_FOCUS, fallbackAnyVariant = true)?.let(::parseExamFocusPack)

    fun loadExamFocusPack(library: TextbookLibrary, bookId: String, chapterNum: Int, preference: BookStudyPreference): ExamFocusTriagePack? =
        loadRawModePackObj(library, bookId, chapterNum, BookStudyMode.EXAM_FOCUS, preference.detailLevel, preference.explainLang, "", fallbackAnyVariant = true)?.let(::parseExamFocusPack)

    fun parseExamFocusPack(rawJson: String): ExamFocusTriagePack? =
        runCatching { jsonParser.parseToJsonElement(rawJson).jsonObject }.getOrNull()?.let(::parseExamFocusPack)

    fun parseExamFocusPack(root: JsonObject): ExamFocusTriagePack? {
        val fs = root["firstScreen"]?.jsonObject ?: root
        val mustArr = (fs["mustStudySections"] as? JsonArray) ?: (fs["mustKnows"] as? JsonArray) ?: return null
        val skimArr = (fs["skimOrSkipSections"] as? JsonArray) ?: (fs["skipableExamples"] as? JsonArray) ?: JsonArray(emptyList())
        val trapsArr = (fs["highFreqTraps"] as? JsonArray) ?: JsonArray(emptyList())
        val formulasArr = (fs["formulaSheets"] as? JsonArray) ?: JsonArray(emptyList())
        val checksArr = (fs["miniChecks"] as? JsonArray) ?: JsonArray(emptyList())
        val topicsJoined = (fs["courseTopicsMatched"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.joinToString(" · ")
        val firstTrapAlert = trapsArr.firstOrNull()?.let { it as? JsonObject }?.let {
            val t = it["trap"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val c = it["correction"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (c.isNotBlank()) "$t（纠偏：$c）" else t
        }.orEmpty()
        return ExamFocusTriagePack(
            headlineZh = fs["headlineZh"]?.jsonPrimitive?.contentOrNull ?: fs["triageSummary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            courseWeekBadgeZh = fs["courseWeekBadgeZh"]?.jsonPrimitive?.contentOrNull ?: topicsJoined,
            mustStudySections = mustArr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                ExamFocusMustSection(
                    sectionId = o["sectionId"]?.jsonPrimitive?.contentOrNull ?: o["topic"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    weightTag = o["weightTag"]?.jsonPrimitive?.contentOrNull ?: o["examWeight"]?.jsonPrimitive?.contentOrNull ?: "★ 必考核心",
                    examAngleZh = o["examAngleZh"]?.jsonPrimitive?.contentOrNull ?: o["whyTested"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    pdfPage = o["pdfPage"]?.jsonPrimitive?.intOrNull ?: 1,
                    quoteAnchor = o["quoteAnchor"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    scoringRubric = (o["scoringRubric"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
                )
            },
            skimOrSkipSections = skimArr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                ExamFocusSkimSection(
                    sectionId = o["sectionId"]?.jsonPrimitive?.contentOrNull ?: o["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    weightTag = o["weightTag"]?.jsonPrimitive?.contentOrNull ?: "○ 考场可跳过",
                    whySkimZh = o["whySkimZh"]?.jsonPrimitive?.contentOrNull ?: o["reasonToSkip"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    pdfPage = o["pdfPage"]?.jsonPrimitive?.intOrNull ?: 1,
                    quoteAnchor = o["quoteAnchor"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            },
            examPitfallAlertZh = fs["examPitfallAlertZh"]?.jsonPrimitive?.contentOrNull ?: firstTrapAlert,
        )
    }

    fun loadOrResolve(library: TextbookLibrary, bookId: String, fallbackTitle: String = "", fallbackPageCount: Int = 0): BookStudyPreference {
        return runCatching {
            openDb(library.directory).use { db ->
                var existing: BookStudyPreference? = null
                db.prepareStatement(
                    """SELECT study_mode, user_locked, default_reason, detail_level,
                              explain_lang, daily_minutes, target_date, inquiry_question, updated_at
                       FROM book_study_preferences WHERE book_id=?""".trimIndent(),
                ).use { s ->
                    s.setString(1, bookId)
                    s.executeQuery().use { r ->
                        if (r.next()) {
                            existing = BookStudyPreference(
                                bookId = bookId,
                                mode = BookStudyMode.fromWire(r.getString(1)),
                                userLocked = r.getInt(2) != 0,
                                defaultReason = r.getString(3).orEmpty(),
                                detailLevel = ExplainDetailLevel.fromWire(r.getString(4)),
                                explainLang = ExplainLanguage.fromWire(r.getString(5)),
                                dailyMinutes = r.getObject(6)?.let { (it as Number).toInt() },
                                targetDate = r.getString(7),
                                inquiryQuestion = r.getString(8).orEmpty(),
                                updatedAt = r.getLong(9),
                            )
                        }
                    }
                }
                val liveCourse = resolveBookCourseContext(db, bookId)
                val resolved = existing?.let { saved ->
                    val adjustedMode = if (!liveCourse.isExam && !saved.userLocked && saved.mode == BookStudyMode.EXAM_FOCUS) {
                        BookStudyMode.READ_THROUGH
                    } else {
                        saved.mode
                    }
                    val adjustedReason = if (!liveCourse.isExam && saved.defaultReason.contains("考")) {
                        "按章节顺序读，随时可以切换学法。"
                    } else {
                        saved.defaultReason
                    }
                    saved.copy(
                        mode = adjustedMode,
                        defaultReason = adjustedReason,
                        examMode = liveCourse.isExam,
                    )
                } ?: computeSmartDefault(db, bookId, fallbackTitle, fallbackPageCount).also {
                    saveInDb(db, it)
                }
                resolved
            }
        }.getOrElse {
            BookStudyPreference(bookId = bookId)
        }
    }

    private data class BookCourseContext(
        val inCourse: Boolean = false,
        val isExam: Boolean = false,
        val examDate: String? = null,
        val inBooklist: Boolean = false,
    )

    private fun resolveBookCourseContext(db: Connection, bookId: String): BookCourseContext {
        var inCourse = false
        var isExam = false
        var examDate: String? = null
        var inBooklist = false
        if (BookSchemaMigration.tableExists(db, "collections") && BookSchemaMigration.tableExists(db, "collection_materials")) {
            val hasMeta = BookSchemaMigration.tableColumns(db, "collections").contains("metadata_json")
            val query = if (hasMeta) {
                """SELECT c.kind, c.exam_date, c.metadata_json FROM collections c
                   JOIN collection_materials cm ON cm.collection_id = c.id
                   WHERE cm.material_id=? AND c.archived=0"""
            } else {
                """SELECT c.kind, c.exam_date, NULL FROM collections c
                   JOIN collection_materials cm ON cm.collection_id = c.id
                   WHERE cm.material_id=? AND c.archived=0"""
            }
            db.prepareStatement(query.trimIndent()).use { s ->
                s.setString(1, bookId)
                s.executeQuery().use { r ->
                    while (r.next()) {
                        val k = r.getString(1).orEmpty()
                        if (k == "COURSE") {
                            inCourse = true
                            val colDate = r.getString(2)
                            val profile = CourseJson.decodeExamProfile(r.getString(3), colDate)
                            if (profile.isExam) {
                                isExam = true
                                if (profile.examDate.isNotBlank()) examDate = profile.examDate
                            }
                        } else if (k == "BOOKLIST") {
                            inBooklist = true
                        }
                    }
                }
            }
        }
        return BookCourseContext(inCourse = inCourse, isExam = isExam, examDate = examDate, inBooklist = inBooklist)
    }

    fun save(library: TextbookLibrary, pref: BookStudyPreference): BookStudyPreference {
        val stamped = pref.copy(updatedAt = System.currentTimeMillis())
        runCatching {
            openDb(library.directory).use { db -> saveInDb(db, stamped) }
        }
        return stamped
    }

    private fun saveInDb(db: Connection, pref: BookStudyPreference) {
        db.prepareStatement(
            """INSERT INTO book_study_preferences(
                book_id, study_mode, user_locked, default_reason, detail_level,
                explain_lang, daily_minutes, target_date, inquiry_question, updated_at
            ) VALUES(?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(book_id) DO UPDATE SET
                study_mode=excluded.study_mode,
                user_locked=excluded.user_locked,
                default_reason=excluded.default_reason,
                detail_level=excluded.detail_level,
                explain_lang=excluded.explain_lang,
                daily_minutes=excluded.daily_minutes,
                target_date=excluded.target_date,
                inquiry_question=excluded.inquiry_question,
                updated_at=excluded.updated_at""".trimIndent(),
        ).use { s ->
            s.setString(1, pref.bookId)
            s.setString(2, pref.mode.name)
            s.setInt(3, if (pref.userLocked) 1 else 0)
            s.setString(4, pref.defaultReason)
            s.setString(5, pref.detailLevel.name)
            s.setString(6, pref.explainLang.name)
            if (pref.dailyMinutes == null) s.setNull(7, java.sql.Types.INTEGER) else s.setInt(7, pref.dailyMinutes)
            if (pref.targetDate == null) s.setNull(8, java.sql.Types.VARCHAR) else s.setString(8, pref.targetDate)
            s.setString(9, pref.inquiryQuestion)
            s.setLong(10, pref.updatedAt)
            s.executeUpdate()
        }
    }

    private fun computeSmartDefault(db: Connection, bookId: String, fallbackTitle: String, fallbackPageCount: Int): BookStudyPreference {
        var title = fallbackTitle
        var format = "pdf"
        var materialKind: String? = null
        if (BookSchemaMigration.tableExists(db, "books")) {
            db.prepareStatement("SELECT title, format FROM books WHERE id=?").use { s ->
                s.setString(1, bookId)
                s.executeQuery().use { r ->
                    if (r.next()) {
                        title = r.getString(1).orEmpty().ifBlank { fallbackTitle }
                        format = r.getString(2).orEmpty().ifBlank { "pdf" }
                    }
                }
            }
            runCatching {
                db.prepareStatement("SELECT material_kind FROM books WHERE id=?").use { s ->
                    s.setString(1, bookId)
                    s.executeQuery().use { r -> if (r.next()) materialKind = r.getString(1) }
                }
            }
        }
        var pageCount = fallbackPageCount
        var sampleText = ""
        if (BookSchemaMigration.tableExists(db, "pages")) {
            db.prepareStatement("SELECT COUNT(*), COALESCE(MAX(text), '') FROM pages WHERE book=? AND page <= 8").use { s ->
                s.setString(1, bookId)
                s.executeQuery().use { r ->
                    if (r.next()) sampleText = r.getString(2).orEmpty()
                }
            }
            if (pageCount <= 0) {
                db.prepareStatement("SELECT COUNT(*) FROM pages WHERE book=?").use { s ->
                    s.setString(1, bookId)
                    s.executeQuery().use { r -> if (r.next()) pageCount = r.getInt(1) }
                }
            }
        }
        val courseCtx = resolveBookCourseContext(db, bookId)
        val inCourse = courseCtx.inCourse
        val examDate = courseCtx.examDate
        val inBooklist = courseCtx.inBooklist
        var totalSections = 0
        if (BookSchemaMigration.tableExists(db, "book_toc_nodes")) {
            db.prepareStatement("SELECT COUNT(*) FROM book_toc_nodes WHERE book=?").use { s ->
                s.setString(1, bookId)
                s.executeQuery().use { r -> if (r.next()) totalSections = r.getInt(1) }
            }
        }
        if (totalSections <= 0 && BookSchemaMigration.tableExists(db, "book_study_chunks")) {
            db.prepareStatement("SELECT COUNT(*) FROM book_study_chunks WHERE book=?").use { s ->
                s.setString(1, bookId)
                s.executeQuery().use { r -> if (r.next()) totalSections = r.getInt(1) }
            }
        }
        val estimatedSections = maxOf(totalSections, (pageCount / 3).coerceAtLeast(1))
        var testedRatio = 0.0
        if (BookSchemaMigration.tableExists(db, "concept_mastery_ledger")) {
            db.prepareStatement(
                "SELECT COUNT(*), SUM(CASE WHEN has_active_test=1 THEN 1 ELSE 0 END) FROM concept_mastery_ledger WHERE book_id=?",
            ).use { s ->
                s.setString(1, bookId)
                s.executeQuery().use { r ->
                    if (r.next() && r.getInt(1) > 0) {
                        val denom = maxOf(r.getInt(1), estimatedSections, 1)
                        testedRatio = r.getInt(2).toDouble() / denom
                    }
                }
            }
        }
        var readRatio = 0.0
        if (BookSchemaMigration.tableExists(db, "book_chapter_progress")) {
            db.prepareStatement(
                "SELECT COUNT(*), SUM(CASE WHEN read_state='READ' THEN 1 ELSE 0 END) FROM book_chapter_progress WHERE book_id=?",
            ).use { s ->
                s.setString(1, bookId)
                s.executeQuery().use { r ->
                    if (r.next() && r.getInt(1) > 0) {
                        val denom = maxOf(r.getInt(1), estimatedSections, 1)
                        readRatio = r.getInt(2).toDouble() / denom
                    }
                }
            }
        }
        return SmartDefaultModeResolver.resolve(
            bookId = bookId,
            title = title,
            format = format,
            pageCount = pageCount,
            sampleText = sampleText,
            materialKind = materialKind,
            inCourse = inCourse,
            courseExamDate = examDate,
            inBooklist = inBooklist,
            testedSectionRatio = testedRatio,
            readChapterRatio = readRatio,
            courseIsExam = courseCtx.isExam,
        )
    }

    fun parseExamFocusExtras(root: JsonObject): Triple<List<ExamFocusTrapItem>, List<ExamFocusFormulaItem>, List<ExamFocusMiniCheckItem>> {
        val fs = root["firstScreen"]?.jsonObject ?: root
        val traps = (fs["highFreqTraps"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            ExamFocusTrapItem(
                trap = o["trap"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                wrongReasoning = o["wrongReasoning"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                correction = o["correction"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                pdfPage = o["pdfPage"]?.jsonPrimitive?.intOrNull ?: 1,
                quoteAnchor = o["quoteAnchor"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        val formulas = (fs["formulaSheets"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            ExamFocusFormulaItem(
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                formulaLatex = o["formulaLatex"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                coefficientNote = o["coefficientNote"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                examUsage = o["examUsage"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                pdfPage = o["pdfPage"]?.jsonPrimitive?.intOrNull ?: 1,
                quoteAnchor = o["quoteAnchor"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        val checks = (fs["miniChecks"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val prompt = o["prompt"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ExamFocusMiniCheckItem(
                prompt = prompt,
                options = (o["options"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
                answerIndex = o["answerIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                rubricOnly = o["rubricOnly"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                pdfPage = o["pdfPage"]?.jsonPrimitive?.intOrNull ?: 1,
                quoteAnchor = o["quoteAnchor"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        return Triple(traps, formulas, checks)
    }

    fun loadDiagnosticSkipPack(library: TextbookLibrary, bookId: String, chapterNum: Int, preference: BookStudyPreference? = null): DiagnosticSkipPack? {
        val root = loadRawModePackObj(
            library,
            bookId,
            chapterNum,
            BookStudyMode.DIAGNOSTIC_SKIP,
            preference?.detailLevel ?: ExplainDetailLevel.DETAILED,
            preference?.explainLang ?: ExplainLanguage.BILINGUAL,
            "",
            fallbackAnyVariant = true,
        ) ?: return null
        return parseDiagnosticSkipPack(root)
    }

    fun parseDiagnosticSkipPack(rawJson: String): DiagnosticSkipPack? =
        runCatching { jsonParser.parseToJsonElement(rawJson).jsonObject }.getOrNull()?.let(::parseDiagnosticSkipPack)

    fun parseDiagnosticSkipPack(root: JsonObject): DiagnosticSkipPack? {
        val fs = root["firstScreen"]?.jsonObject ?: root
        val arr = (fs["diagnosticGates"] as? JsonArray) ?: return null
        val gates = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val prompt = o["questionPrompt"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            DiagnosticSkipGateItem(
                gateId = o["gateId"]?.jsonPrimitive?.contentOrNull ?: "gate-${prompt.hashCode()}",
                sectionTitle = o["sectionTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                targetConcept = o["targetConcept"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                questionType = o["questionType"]?.jsonPrimitive?.contentOrNull ?: "CONCEPT_CHECK",
                questionPrompt = prompt,
                options = (o["options"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
                correctIndex = o["correctIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                passSkipNote = o["passSkipNote"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                missRemediation = o["missRemediation"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                missJumpPdfPage = o["missJumpPdfPage"]?.jsonPrimitive?.intOrNull ?: 1,
                quoteAnchor = o["quoteAnchor"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        if (gates.isEmpty()) return null
        return DiagnosticSkipPack(
            skipPolicy = fs["skipPolicy"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            gates = gates,
        )
    }

    fun loadProblemDrivenPack(library: TextbookLibrary, bookId: String, chapterNum: Int, preference: BookStudyPreference? = null): ProblemDrivenPack? {
        val root = loadRawModePackObj(
            library,
            bookId,
            chapterNum,
            BookStudyMode.PROBLEM_DRIVEN,
            preference?.detailLevel ?: ExplainDetailLevel.DETAILED,
            preference?.explainLang ?: ExplainLanguage.BILINGUAL,
            "",
            fallbackAnyVariant = true,
        ) ?: return null
        return parseProblemDrivenPack(root)
    }

    fun parseProblemDrivenPack(rawJson: String): ProblemDrivenPack? =
        runCatching { jsonParser.parseToJsonElement(rawJson).jsonObject }.getOrNull()?.let(::parseProblemDrivenPack)

    fun parseProblemDrivenPack(root: JsonObject): ProblemDrivenPack? {
        val fs = root["firstScreen"]?.jsonObject ?: root
        val arr = (fs["problems"] as? JsonArray) ?: return null
        val problems = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ProblemDrivenProblemItem(
                problemId = o["problemId"]?.jsonPrimitive?.contentOrNull ?: title,
                title = title,
                exercisePdfPage = o["exercisePdfPage"]?.jsonPrimitive?.intOrNull ?: 1,
                exerciseQuoteAnchor = o["exerciseQuoteAnchor"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                attemptPrompt = o["attemptPrompt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                scaffoldingHints = (o["scaffoldingHints"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
                keyDerivationCheck = o["keyDerivationCheck"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                bodyPdfPage = o["bodyPdfPage"]?.jsonPrimitive?.intOrNull ?: 1,
                quoteAnchor = o["quoteAnchor"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                answerOutline = (o["answerOutline"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
            )
        }
        if (problems.isEmpty()) return null
        return ProblemDrivenPack(
            strategyNote = fs["strategyNote"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            problems = problems,
        )
    }

    fun loadQuestionDrivenPack(library: TextbookLibrary, bookId: String, chapterNum: Int, preference: BookStudyPreference): QuestionDrivenPack? {
        val root = loadRawModePackObj(
            library,
            bookId,
            chapterNum,
            BookStudyMode.QUESTION_DRIVEN,
            preference.detailLevel,
            preference.explainLang,
            preference.inquiryQuestion,
            fallbackAnyVariant = false,
        ) ?: return null
        return parseQuestionDrivenPack(root)
    }

    fun parseQuestionDrivenPack(rawJson: String): QuestionDrivenPack? =
        runCatching { jsonParser.parseToJsonElement(rawJson).jsonObject }.getOrNull()?.let(::parseQuestionDrivenPack)

    fun parseQuestionDrivenPack(root: JsonObject): QuestionDrivenPack? {
        val fs = root["firstScreen"]?.jsonObject ?: root
        val hitsArr = (fs["evidenceHits"] as? JsonArray) ?: return null
        val hits = hitsArr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val quote = o["quoteAnchor"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            QuestionDrivenEvidenceHit(
                hitId = o["hitId"]?.jsonPrimitive?.contentOrNull ?: quote.take(16),
                bodyJumpPage = o["bodyJumpPage"]?.jsonPrimitive?.intOrNull ?: 1,
                quoteAnchor = quote,
                howItAnswersQuestion = o["howItAnswersQuestion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                limitOrCounterpoint = o["limitOrCounterpoint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        return QuestionDrivenPack(
            questionFocus = fs["questionFocus"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            directConclusion = fs["directConclusion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            evidenceHits = hits,
            argumentationScaffold = (fs["argumentationScaffold"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
            antiGhostwriteNotice = fs["antiGhostwriteNotice"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            followUpQuestions = (fs["followUpQuestions"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
        )
    }

    fun loadReadThroughModePack(library: TextbookLibrary, bookId: String, chapterNum: Int, preference: BookStudyPreference): ReadThroughModePack? {
        val root = loadRawModePackObj(
            library,
            bookId,
            chapterNum,
            BookStudyMode.READ_THROUGH,
            preference.detailLevel,
            preference.explainLang,
            "",
            fallbackAnyVariant = true,
        ) ?: return null
        return parseReadThroughModePack(root)
    }

    fun parseReadThroughModePack(rawJson: String): ReadThroughModePack? =
        runCatching { jsonParser.parseToJsonElement(rawJson).jsonObject }.getOrNull()?.let(::parseReadThroughModePack)

    fun parseReadThroughModePack(root: JsonObject): ReadThroughModePack? {
        val fs = root["firstScreen"]?.jsonObject ?: root
        val secArr = (fs["sections"] as? JsonArray) ?: return null
        val sections = secArr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val plain = o["plainExplanation"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ReadThroughModeSectionItem(
                sectionId = o["sectionId"]?.jsonPrimitive?.contentOrNull ?: title,
                title = title,
                pdfPage = o["pdfPage"]?.jsonPrimitive?.intOrNull ?: 1,
                quoteAnchor = o["quoteAnchor"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                keyConcept = o["keyConcept"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                plainExplanation = plain,
                conceptChain = (o["conceptChain"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
                formulaNote = o["formulaNote"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                commonMisreading = o["commonMisreading"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                microCheckPrompt = o["microCheckPrompt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                microCheckHint = o["microCheckHint"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        if (sections.isEmpty()) return null
        return ReadThroughModePack(
            learningArc = fs["learningArc"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            sections = sections,
            takeawayChecklist = (fs["takeawayChecklist"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
        )
    }

    fun saveBookMapBatchResult(
        library: TextbookLibrary,
        bookId: String,
        batchJson: JsonObject,
        detailLevel: ExplainDetailLevel = ExplainDetailLevel.BRIEF,
        explainLang: ExplainLanguage = ExplainLanguage.BILINGUAL,
    ) {
        runCatching {
            openDb(library.directory).use { db ->
                db.prepareStatement(
                    """INSERT INTO book_map_meta(book_id, graph_json, updated_at)
                       VALUES(?,?,?)
                       ON CONFLICT(book_id) DO UPDATE SET graph_json=excluded.graph_json, updated_at=excluded.updated_at""",
                ).use { s ->
                    s.setString(1, bookId)
                    s.setString(2, batchJson.toString())
                    s.setLong(3, System.currentTimeMillis())
                    s.executeUpdate()
                }
            }
            (batchJson["chapters"] as? JsonArray).orEmpty().forEach { el ->
                val chObj = el as? JsonObject ?: return@forEach
                val chNum = chObj["chapterNum"]?.jsonPrimitive?.intOrNull ?: return@forEach
                saveModePackJson(
                    library = library,
                    bookId = bookId,
                    chapterNum = chNum,
                    mode = BookStudyMode.BOOK_MAP,
                    detailLevel = detailLevel,
                    explainLang = explainLang,
                    inquiryQuestion = "",
                    packJson = chObj.toString(),
                )
            }
        }
    }

    fun loadBookMapMeta(library: TextbookLibrary, bookId: String): WholeBookMapMeta? = runCatching {
        openDb(library.directory).use { db ->
            db.prepareStatement("SELECT graph_json FROM book_map_meta WHERE book_id=?").use { s ->
                s.setString(1, bookId)
                s.executeQuery().use { rs ->
                    if (!rs.next()) return@use null
                    val raw = rs.getString(1).orEmpty()
                    if (raw.isBlank()) return@use null
                    val obj = jsonParser.parseToJsonElement(raw).jsonObject
                    WholeBookMapMeta(
                        bookId = obj["bookId"]?.jsonPrimitive?.contentOrNull ?: bookId,
                        bookTitle = obj["bookTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        chapterCount = obj["chapterCount"]?.jsonPrimitive?.intOrNull ?: 0,
                        clusters = (obj["thematicClusters"] as? JsonArray).orEmpty().mapNotNull { el ->
                            val o = el as? JsonObject ?: return@mapNotNull null
                            BookMapCluster(
                                clusterId = o["clusterId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                                themeSummary = o["themeSummary"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                chapterNums = (o["chapterNums"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull },
                            )
                        },
                        edges = (obj["dependencyEdges"] as? JsonArray).orEmpty().mapNotNull { el ->
                            val o = el as? JsonObject ?: return@mapNotNull null
                            BookMapDependencyEdge(
                                fromChapter = o["fromChapter"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                                toChapter = o["toChapter"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                                relation = o["relation"]?.jsonPrimitive?.contentOrNull ?: "BUILDS_ON",
                                reason = o["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            )
                        },
                        fastSkimOrder40Min = (obj["fastSkimOrder40Min"] as? JsonArray).orEmpty().mapNotNull { el ->
                            val o = el as? JsonObject ?: return@mapNotNull null
                            BookMapFastSkimStep(
                                chapterNum = o["chapterNum"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                                minutes = o["minutes"]?.jsonPrimitive?.intOrNull ?: 5,
                                focus = o["focus"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            )
                        },
                        glossary = (obj["masterGlossary"] as? JsonArray).orEmpty().mapNotNull { el ->
                            val o = el as? JsonObject ?: return@mapNotNull null
                            BookMapGlossaryEntry(
                                en = o["en"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                                zh = o["zh"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                chapterNum = o["chapterNum"]?.jsonPrimitive?.intOrNull ?: 0,
                            )
                        },
                        estimatedCostUsd = obj["costStats"]?.jsonObject?.get("estimatedCostUsd")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    )
                }
            }
        }
    }.getOrNull()
}

/** Builds server API payloads for `/api/books/{id}/modes/{mode}` and `/api/books/{id}/book-map/batch` with SQLite `course_topics` injection. */
internal object StudyModeWireBuilder {
    fun courseTopicsForBook(library: TextbookLibrary, bookId: String): List<String> = runCatching {
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            if (!BookSchemaMigration.tableExists(db, "course_topics")) return@runCatching emptyList()
            val titles = linkedSetOf<String>()
            if (BookSchemaMigration.tableExists(db, "collection_materials")) {
                db.prepareStatement(
                    """SELECT DISTINCT ct.title
                       FROM course_topics ct
                       JOIN collection_materials cm ON cm.collection_id = ct.collection_id
                       WHERE cm.material_id = ? AND ct.exam_status <> 'EXCLUDED'
                       ORDER BY ct.ord LIMIT 20""",
                ).use { s ->
                    s.setString(1, bookId)
                    s.executeQuery().use { rs ->
                        while (rs.next()) {
                            rs.getString(1)?.trim()?.takeIf { it.isNotBlank() }?.let { titles += it }
                        }
                    }
                }
            }
            if (titles.isEmpty() && BookSchemaMigration.tableExists(db, "course_topic_sources")) {
                db.prepareStatement(
                    """SELECT DISTINCT ct.title
                       FROM course_topics ct
                       JOIN course_topic_sources cts ON cts.topic_id = ct.topic_id
                       WHERE cts.book_id = ? AND ct.exam_status <> 'EXCLUDED'
                       ORDER BY ct.ord LIMIT 20""",
                ).use { s ->
                    s.setString(1, bookId)
                    s.executeQuery().use { rs ->
                        while (rs.next()) {
                            rs.getString(1)?.trim()?.takeIf { it.isNotBlank() }?.let { titles += it }
                        }
                    }
                }
            }
            titles.toList()
        }
    }.getOrDefault(emptyList())

    fun chapterPagesForRange(library: TextbookLibrary, book: Textbook, startPage: Int, endPage: Int): List<Pair<Int, String>> {
        val inMemory = book.pages
            .filter { it.number in startPage..endPage && it.text.isNotBlank() }
            .sortedBy { it.number }
            .map { it.number to it.text }
        if (inMemory.isNotEmpty()) return inMemory
        return runCatching {
            DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
                db.prepareStatement("SELECT page, text FROM pages WHERE book=? AND page BETWEEN ? AND ? ORDER BY page")
                    .use { s ->
                        s.setString(1, book.id)
                        s.setInt(2, startPage)
                        s.setInt(3, endPage)
                        s.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    val p = rs.getInt(1)
                                    val t = rs.getString(2).orEmpty()
                                    if (p > 0 && t.isNotBlank()) add(p to t)
                                }
                            }
                        }
                    }
            }
        }.getOrDefault(emptyList())
    }

    fun buildModeRequestJson(
        library: TextbookLibrary,
        book: Textbook,
        chapterGroup: ChapterSectionGroup,
        chapterNum: Int,
        preference: BookStudyPreference,
    ): JsonObject {
        val topics = courseTopicsForBook(library, book.id)
        val pages = chapterPagesForRange(library, book, chapterGroup.firstPage, chapterGroup.lastPage)
        return buildJsonObject {
            put("bookId", book.id)
            put("bookTitle", book.title)
            put("chapterNum", chapterNum)
            put("chapterTitle", chapterGroup.chapterTitle)
            put("detailLevel", preference.detailLevel.name)
            put("explainLang", preference.explainLang.name)
            put("inquiryQuestion", preference.inquiryQuestion)
            put("stream", true)
            if (topics.isNotEmpty()) {
                put("courseTopics", buildJsonArray { topics.forEach { add(JsonPrimitive(it)) } })
            }
            put(
                "sections",
                buildJsonArray {
                    chapterGroup.sections.forEach { sec ->
                        add(
                            buildJsonObject {
                                put("sectionId", sec.id)
                                put("title", sec.title)
                                put("startPdfPage", sec.firstPage)
                                put("endPdfPage", sec.lastPage)
                            },
                        )
                    }
                },
            )
            put(
                "pages",
                buildJsonArray {
                    pages.forEach { (pg, txt) ->
                        add(buildJsonObject { put("pdfPage", pg); put("text", txt) })
                    }
                },
            )
        }
    }

    fun buildBookMapBatchRequestJson(
        library: TextbookLibrary,
        book: Textbook,
        preference: BookStudyPreference,
    ): JsonObject {
        val topics = courseTopicsForBook(library, book.id)
        val batchDetail = if (preference.detailLevel == ExplainDetailLevel.ORIGINAL_ONLY) ExplainDetailLevel.BRIEF else preference.detailLevel
        val seenIds = linkedSetOf<String>()
        val groups = buildList {
            for (i in book.lessons.indices) {
                val g = ChapterCompanionGrouping.groupForSection(book, i) ?: continue
                if (seenIds.add(g.chapterId)) add(g)
            }
        }
        return buildJsonObject {
            put("bookId", book.id)
            put("bookTitle", book.title)
            put("detailLevel", batchDetail.name)
            put("explainLang", preference.explainLang.name)
            put("useBatchApi", true)
            if (topics.isNotEmpty()) {
                put("courseTopics", buildJsonArray { topics.forEach { add(JsonPrimitive(it)) } })
            }
            put(
                "chapters",
                buildJsonArray {
                    groups.forEachIndexed { idx, g ->
                        val chNum = StudyModeChapterNumbering.resolve(book, g.firstPage, g.firstPage).chapterNumber
                            .takeIf { it > 0 } ?: (idx + 1)
                        val allPages = chapterPagesForRange(library, book, g.firstPage, g.lastPage)
                        val sample = listOfNotNull(
                            allPages.firstOrNull(),
                            allPages.getOrNull(allPages.size / 2),
                        ).distinctBy { it.first }
                        add(
                            buildJsonObject {
                                put("chapterNum", chNum)
                                put("chapterTitle", g.chapterTitle)
                                put(
                                    "sections",
                                    buildJsonArray {
                                        g.sections.forEach { sec ->
                                            add(
                                                buildJsonObject {
                                                    put("sectionId", sec.id)
                                                    put("title", sec.title)
                                                    put("startPdfPage", sec.firstPage)
                                                    put("endPdfPage", sec.lastPage)
                                                },
                                            )
                                        }
                                    },
                                )
                                put(
                                    "pages",
                                    buildJsonArray {
                                        sample.forEach { (pg, txt) ->
                                            add(buildJsonObject { put("pdfPage", pg); put("text", txt) })
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }
}
