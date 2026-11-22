package com.coach.desktop.books

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    companion object {
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
) {
    val pacePreset: StudyPacePreset
        get() = StudyPacePreset.fromMinutes(dailyMinutes, targetDate)

    fun compactBadgeText(): String = buildString {
        append("学法：")
        append(mode.titleZh)
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
    ): BookStudyPreference {
        val isEnglishHeavy = LATIN_WORD_RE.findAll(sampleText.ifBlank { title }).count() >= 5
        val defaultLang = if (isEnglishHeavy) ExplainLanguage.BILINGUAL else ExplainLanguage.ZH
        val daysToExam = courseExamDate?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(raw.trim())) }.getOrNull()
        }

        // 1. Course with imminent exam (<= 21 days) & partial prior study -> 先测后读
        if (inCourse && daysToExam != null && daysToExam in 0..21 && (testedSectionRatio >= 0.20 || readChapterRatio >= 0.25)) {
            return BookStudyPreference(
                bookId = bookId,
                mode = BookStudyMode.DIAGNOSTIC_SKIP,
                userLocked = false,
                defaultReason = "距考试还有 $daysToExam 天且已学过部分章节，默认先测后读，会的直接跳过。",
                detailLevel = ExplainDetailLevel.DETAILED,
                explainLang = defaultLang,
                targetDate = courseExamDate,
            )
        }
        // 2. Linked to a course -> 考点优先
        if (inCourse) {
            return BookStudyPreference(
                bookId = bookId,
                mode = BookStudyMode.EXAM_FOCUS,
                userLocked = false,
                defaultReason = "已关联课程备考，默认按考纲权重挑重点学，随时可换。",
                detailLevel = ExplainDetailLevel.DETAILED,
                explainLang = defaultLang,
                targetDate = courseExamDate,
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
)

internal data class BookMapOnePagerPack(
    val headlineZh: String,
    val bigPictureThesisZh: String,
    val sectionArchitecture: List<BookMapSectionRole>,
    val prerequisiteChapter: String,
    val downstreamChapter: String,
)

internal data class ExamFocusMustSection(
    val sectionId: String,
    val weightTag: String,
    val examAngleZh: String,
    val pdfPage: Int,
)

internal data class ExamFocusSkimSection(
    val sectionId: String,
    val weightTag: String,
    val whySkimZh: String,
)

internal data class ExamFocusTriagePack(
    val headlineZh: String,
    val courseWeekBadgeZh: String,
    val mustStudySections: List<ExamFocusMustSection>,
    val skimOrSkipSections: List<ExamFocusSkimSection>,
    val examPitfallAlertZh: String,
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
            pack_json TEXT NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(book_id, chapter_num, study_mode)
        )
    """

    private fun openDb(libraryDir: Path): Connection =
        DriverManager.getConnection("jdbc:sqlite:${libraryDir.resolve("library.sqlite")}").apply {
            createStatement().use {
                it.execute("PRAGMA busy_timeout=5000")
                it.execute(DDL.trimIndent())
                it.execute(PACKS_DDL.trimIndent())
            }
        }

    fun saveModePackJson(library: TextbookLibrary, bookId: String, chapterNum: Int, mode: BookStudyMode, packJson: String) {
        runCatching {
            openDb(library.directory).use { db ->
                db.prepareStatement(
                    """INSERT INTO book_study_mode_packs(book_id, chapter_num, study_mode, pack_json, updated_at)
                       VALUES(?,?,?,?,?)
                       ON CONFLICT(book_id, chapter_num, study_mode) DO UPDATE SET
                       pack_json=excluded.pack_json, updated_at=excluded.updated_at""".trimIndent(),
                ).use { s ->
                    s.setString(1, bookId)
                    s.setInt(2, chapterNum)
                    s.setString(3, mode.name)
                    s.setString(4, packJson)
                    s.setLong(5, System.currentTimeMillis())
                    s.executeUpdate()
                }
            }
        }
    }

    private fun loadRawModePackObj(library: TextbookLibrary, bookId: String, chapterNum: Int, mode: BookStudyMode): JsonObject? {
        return runCatching {
            openDb(library.directory).use { db ->
                db.prepareStatement(
                    "SELECT pack_json FROM book_study_mode_packs WHERE book_id=? AND chapter_num=? AND study_mode=?",
                ).use { s ->
                    s.setString(1, bookId)
                    s.setInt(2, chapterNum)
                    s.setString(3, mode.name)
                    s.executeQuery().use { r ->
                        if (r.next()) {
                            val raw = r.getString(1).orEmpty()
                            if (raw.isNotBlank()) jsonParser.parseToJsonElement(raw).jsonObject else null
                        } else null
                    }
                }
            }
        }.getOrNull()
    }

    fun loadBookMapPack(library: TextbookLibrary, bookId: String, chapterNum: Int): BookMapOnePagerPack? {
        val root = loadRawModePackObj(library, bookId, chapterNum, BookStudyMode.BOOK_MAP) ?: return null
        val fs = root["firstScreen"]?.jsonObject ?: root
        val thesis = fs["bigPictureThesisZh"]?.jsonPrimitive?.contentOrNull
            ?: fs["oneSentenceVerdictZh"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val headline = fs["headlineZh"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val archArr = (fs["sectionArchitecture"] as? JsonArray) ?: JsonArray(emptyList())
        val roles = archArr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            BookMapSectionRole(
                sectionId = o["sectionId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                roleLabelZh = o["roleLabelZh"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                oneLineZh = o["oneLineZh"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                pdfPage = o["pdfPage"]?.jsonPrimitive?.intOrNull ?: 1,
            )
        }
        val links = fs["chapterLinks"] as? JsonObject
        return BookMapOnePagerPack(
            headlineZh = headline,
            bigPictureThesisZh = thesis,
            sectionArchitecture = roles,
            prerequisiteChapter = links?.get("prerequisiteChapter")?.jsonPrimitive?.contentOrNull.orEmpty(),
            downstreamChapter = links?.get("downstreamChapter")?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    fun loadExamFocusPack(library: TextbookLibrary, bookId: String, chapterNum: Int): ExamFocusTriagePack? {
        val root = loadRawModePackObj(library, bookId, chapterNum, BookStudyMode.EXAM_FOCUS) ?: return null
        val fs = root["firstScreen"]?.jsonObject ?: root
        val mustArr = (fs["mustStudySections"] as? JsonArray) ?: return null
        val skimArr = (fs["skimOrSkipSections"] as? JsonArray) ?: JsonArray(emptyList())
        return ExamFocusTriagePack(
            headlineZh = fs["headlineZh"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            courseWeekBadgeZh = fs["courseWeekBadgeZh"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            mustStudySections = mustArr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                ExamFocusMustSection(
                    sectionId = o["sectionId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    weightTag = o["weightTag"]?.jsonPrimitive?.contentOrNull ?: "★ 必考核心",
                    examAngleZh = o["examAngleZh"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    pdfPage = o["pdfPage"]?.jsonPrimitive?.intOrNull ?: 1,
                )
            },
            skimOrSkipSections = skimArr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                ExamFocusSkimSection(
                    sectionId = o["sectionId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    weightTag = o["weightTag"]?.jsonPrimitive?.contentOrNull ?: "○ 背景速览",
                    whySkimZh = o["whySkimZh"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            },
            examPitfallAlertZh = fs["examPitfallAlertZh"]?.jsonPrimitive?.contentOrNull.orEmpty(),
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
                existing ?: computeSmartDefault(db, bookId, fallbackTitle, fallbackPageCount).also {
                    saveInDb(db, it)
                }
            }
        }.getOrElse {
            BookStudyPreference(bookId = bookId)
        }
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
        var inCourse = false
        var examDate: String? = null
        var inBooklist = false
        if (BookSchemaMigration.tableExists(db, "collections") && BookSchemaMigration.tableExists(db, "collection_materials")) {
            db.prepareStatement(
                """SELECT c.kind, c.exam_date FROM collections c
                   JOIN collection_materials cm ON cm.collection_id = c.id
                   WHERE cm.material_id=? AND c.archived=0""".trimIndent(),
            ).use { s ->
                s.setString(1, bookId)
                s.executeQuery().use { r ->
                    while (r.next()) {
                        val k = r.getString(1).orEmpty()
                        if (k == "COURSE") {
                            inCourse = true
                            examDate = r.getString(2) ?: examDate
                        } else if (k == "BOOKLIST") {
                            inBooklist = true
                        }
                    }
                }
            }
        }
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
        )
    }
}
