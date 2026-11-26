package com.coach.desktop.books

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.sql.Connection

/** Five modular panels of the single-book companion drawer (TON-1792). */
internal enum class CompanionModuleTab(val shortLabel: String, val fullTitle: String, val tag: String) {
    KEY_POINTS("要点", "本章要点", "companion-mod-keypoints"),
    PRETEST("自测", "读前自测", "companion-mod-pretest"),
    DERIVATION("推导", "推导看图模板", "companion-mod-derivation"),
    PRACTICE("练习", "选做练习", "companion-mod-practice"),
    EXERCISE_HINTS("习题", "原书章末习题三级提示", "companion-mod-exercises"),
    ;

    companion object {
        fun fromWire(name: String?): CompanionModuleTab =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: KEY_POINTS
    }
}

internal data class BookCompanionAnchor(
    val pdfPage: Int,
    val blockId: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val quoteAnchor: String,
)

internal data class BookCompanionKeyPoint(
    val id: String,
    val sectionId: String,
    val summary: String,
    val anchor: BookCompanionAnchor? = null,
)

internal data class BookCompanionJargon(
    val term: String,
    val plainMeaning: String,
    val anchor: BookCompanionAnchor? = null,
)

internal data class BookCompanionQuiz(
    val id: String,
    val sectionId: String,
    val stem: String,
    val options: List<String> = emptyList(),
    val correctOption: String,
    val explanation: String = "",
    val revealHint: String = "",
    val kind: String = "MCQ",
    val anchor: BookCompanionAnchor? = null,
)

internal data class BookCompanionDerivation(
    val id: String,
    val sectionId: String,
    val title: String,
    val setup: String = "",
    val steps: List<String> = emptyList(),
    val graphCheck: String = "",
    val pitfall: String = "",
    val anchor: BookCompanionAnchor? = null,
)

internal data class BookCompanionExerciseHint(
    val id: String,
    val sectionId: String,
    val problemLabel: String,
    val questionSummary: String,
    val hint1Direction: String,
    val hint2Method: String,
    val hint3Check: String,
    val anchor: BookCompanionAnchor? = null,
)

internal data class BookCompanionOverlapAudit(
    val en8GramHits: Int = 0,
    val zh12GramHits: Int = 0,
    val passed: Boolean = true,
)

internal data class BookCompanionStats(
    val sectionChunks: Int = 0,
    val maxRequestTokens: Int = 0,
    val verifiedAnchors: Int = 0,
    val rejectedAnchors: Int = 0,
    val exercisesIsolated: Int = 0,
    val overlapAudit: BookCompanionOverlapAudit = BookCompanionOverlapAudit(),
    val p50ElapsedMs: Long = 0L,
    val p95ElapsedMs: Long = 0L,
    val p50TtftMs: Long = 0L,
    val p95TtftMs: Long = 0L,
    val totalCostMicrodollars: Long = 0L,
    val routeChannelsUsed: List<String> = emptyList(),
    val fromCache: Boolean = false,
)

internal data class BookCompanionPack(
    val version: Int = 1,
    val bookId: String,
    val chapterId: String,
    val chapterTitle: String,
    val coreQuestion: String = "",
    val keyPoints: List<BookCompanionKeyPoint> = emptyList(),
    val jargonGlosses: List<BookCompanionJargon> = emptyList(),
    val pretest: List<BookCompanionQuiz> = emptyList(),
    val derivations: List<BookCompanionDerivation> = emptyList(),
    val optionalPractice: List<BookCompanionQuiz> = emptyList(),
    val exerciseHints: List<BookCompanionExerciseHint> = emptyList(),
    val stats: BookCompanionStats = BookCompanionStats(),
)

internal sealed interface ChapterCompanionFetchResult {
    data class Ready(val pack: BookCompanionPack) : ChapterCompanionFetchResult
    data class Failed(val message: String, val trouble: ExplainTrouble = ExplainTrouble.Other) : ChapterCompanionFetchResult
}

/**
 * Real-time persisted state stored in `book_chapter_progress.practice_draft_json`.
 * Captures exact reading/practice breakpoint so closing at any step and clicking Home "继续"
 * restores page, drawer tab, active module, question, typed draft, cursor, and revealed hint tiers.
 */
internal data class ChapterPracticeDraft(
    val bookId: String = "",
    val tocNodeId: String = "",
    val page: Int = 1,
    val offsetUtf16: Int = 0,
    val companionOpen: Boolean = false,
    val companionTab: String = "EXPLAIN",
    val activeModule: String = CompanionModuleTab.KEY_POINTS.name,
    val activeQuestionId: String? = null,
    val pretestSelections: Map<String, String> = emptyMap(),
    val pretestGuessed: Map<String, Boolean> = emptyMap(),
    val pretestSubmitted: Set<String> = emptySet(),
    val practiceSelections: Map<String, String> = emptyMap(),
    val practiceTextDrafts: Map<String, String> = emptyMap(),
    val practiceCursorPositions: Map<String, Int> = emptyMap(),
    val practiceGuessed: Map<String, Boolean> = emptyMap(),
    val practiceSubmitted: Set<String> = emptySet(),
    val exerciseHintLevels: Map<String, Int> = emptyMap(),
    val openQuestionJson: String = "",
    val openAttemptsJson: String = "",
    val openAnswerDraft: String = "",
    val updatedAt: Long = 0L,
) {
    fun hasMeaningfulDraft(): Boolean =
        pretestSelections.isNotEmpty() ||
            pretestSubmitted.isNotEmpty() ||
            practiceSelections.isNotEmpty() ||
            practiceTextDrafts.values.any { it.isNotBlank() } ||
            practiceSubmitted.isNotEmpty() ||
            exerciseHintLevels.values.any { it > 0 } ||
            openQuestionJson.isNotBlank() ||
            openAnswerDraft.isNotBlank() ||
            openAttemptsJson.isNotBlank() ||
            (companionOpen && activeModule != CompanionModuleTab.KEY_POINTS.name)
}

internal object CompanionJson {
    private val parser = Json { ignoreUnknownKeys = true }

    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.strList(k: String): List<String> =
        (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun encodeAnchor(a: BookCompanionAnchor?): JsonObject? = a?.let {
        buildJsonObject {
            put("pdfPage", it.pdfPage)
            put("blockId", it.blockId)
            put("startUtf16", it.startUtf16)
            put("endUtf16", it.endUtf16)
            put("quoteAnchor", it.quoteAnchor)
        }
    }

    private fun decodeAnchor(obj: JsonObject?): BookCompanionAnchor? {
        if (obj == null) return null
        val p = obj["pdfPage"]?.jsonPrimitive?.intOrNull ?: return null
        val bid = obj["blockId"]?.jsonPrimitive?.contentOrNull ?: return null
        val s = obj["startUtf16"]?.jsonPrimitive?.intOrNull ?: return null
        val e = obj["endUtf16"]?.jsonPrimitive?.intOrNull ?: return null
        val q = obj["quoteAnchor"]?.jsonPrimitive?.contentOrNull ?: return null
        return BookCompanionAnchor(p, bid, s, e, q)
    }

    private fun encodeQuiz(q: BookCompanionQuiz): JsonObject = buildJsonObject {
        put("id", q.id); put("sectionId", q.sectionId); put("stem", q.stem)
        put("options", buildJsonArray { q.options.forEach { add(JsonPrimitive(it)) } })
        put("correctOption", q.correctOption); put("explanation", q.explanation)
        put("revealHint", q.revealHint); put("kind", q.kind)
        encodeAnchor(q.anchor)?.let { put("anchor", it) }
    }

    private fun decodeQuiz(o: JsonObject): BookCompanionQuiz = BookCompanionQuiz(
        id = o.str("id"),
        sectionId = o.str("sectionId"),
        stem = o.str("stem"),
        options = o.strList("options"),
        correctOption = o.str("correctOption"),
        explanation = o.str("explanation"),
        revealHint = o.str("revealHint"),
        kind = o.str("kind").ifBlank { "MCQ" },
        anchor = decodeAnchor(o["anchor"] as? JsonObject),
    )

    fun encodePack(pack: BookCompanionPack): JsonObject = buildJsonObject {
        put("version", pack.version)
        put("bookId", pack.bookId)
        put("chapterId", pack.chapterId)
        put("chapterTitle", pack.chapterTitle)
        put("coreQuestion", pack.coreQuestion)
        put("keyPoints", buildJsonArray {
            pack.keyPoints.forEach { kp ->
                add(buildJsonObject {
                    put("id", kp.id); put("sectionId", kp.sectionId); put("summary", kp.summary)
                    encodeAnchor(kp.anchor)?.let { put("anchor", it) }
                })
            }
        })
        put("jargonGlosses", buildJsonArray {
            pack.jargonGlosses.forEach { g ->
                add(buildJsonObject {
                    put("term", g.term); put("plainMeaning", g.plainMeaning)
                    encodeAnchor(g.anchor)?.let { put("anchor", it) }
                })
            }
        })
        put("pretest", buildJsonArray { pack.pretest.forEach { add(encodeQuiz(it)) } })
        put("derivations", buildJsonArray {
            pack.derivations.forEach { d ->
                add(buildJsonObject {
                    put("id", d.id); put("sectionId", d.sectionId); put("title", d.title)
                    put("setup", d.setup)
                    put("steps", buildJsonArray { d.steps.forEach { add(JsonPrimitive(it)) } })
                    put("graphCheck", d.graphCheck); put("pitfall", d.pitfall)
                    encodeAnchor(d.anchor)?.let { put("anchor", it) }
                })
            }
        })
        put("optionalPractice", buildJsonArray { pack.optionalPractice.forEach { add(encodeQuiz(it)) } })
        put("exerciseHints", buildJsonArray {
            pack.exerciseHints.forEach { h ->
                add(buildJsonObject {
                    put("id", h.id); put("sectionId", h.sectionId); put("problemLabel", h.problemLabel)
                    put("questionSummary", h.questionSummary); put("hint1Direction", h.hint1Direction)
                    put("hint2Method", h.hint2Method); put("hint3Check", h.hint3Check)
                    encodeAnchor(h.anchor)?.let { put("anchor", it) }
                })
            }
        })
        put("stats", buildJsonObject {
            put("sectionChunks", pack.stats.sectionChunks)
            put("maxRequestTokens", pack.stats.maxRequestTokens)
            put("verifiedAnchors", pack.stats.verifiedAnchors)
            put("rejectedAnchors", pack.stats.rejectedAnchors)
            put("exercisesIsolated", pack.stats.exercisesIsolated)
            put("overlapAudit", buildJsonObject {
                put("en8GramHits", pack.stats.overlapAudit.en8GramHits)
                put("zh12GramHits", pack.stats.overlapAudit.zh12GramHits)
                put("passed", pack.stats.overlapAudit.passed)
            })
            put("p50ElapsedMs", pack.stats.p50ElapsedMs)
            put("p95ElapsedMs", pack.stats.p95ElapsedMs)
            put("p50TtftMs", pack.stats.p50TtftMs)
            put("p95TtftMs", pack.stats.p95TtftMs)
            put("totalCostMicrodollars", pack.stats.totalCostMicrodollars)
            put("routeChannelsUsed", buildJsonArray { pack.stats.routeChannelsUsed.forEach { add(JsonPrimitive(it)) } })
            put("fromCache", pack.stats.fromCache)
        })
    }

    fun decodePack(raw: String): BookCompanionPack = decodePack(parser.parseToJsonElement(raw).jsonObject)

    fun decodePack(obj: JsonObject): BookCompanionPack {
        val st = obj["stats"] as? JsonObject
        val ov = st?.get("overlapAudit") as? JsonObject
        return BookCompanionPack(
            version = obj["version"]?.jsonPrimitive?.intOrNull ?: 1,
            bookId = obj.str("bookId"),
            chapterId = obj.str("chapterId"),
            chapterTitle = obj.str("chapterTitle"),
            coreQuestion = obj.str("coreQuestion"),
            keyPoints = (obj["keyPoints"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                BookCompanionKeyPoint(o.str("id"), o.str("sectionId"), o.str("summary"), decodeAnchor(o["anchor"] as? JsonObject))
            },
            jargonGlosses = (obj["jargonGlosses"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                BookCompanionJargon(o.str("term"), o.str("plainMeaning"), decodeAnchor(o["anchor"] as? JsonObject))
            },
            pretest = (obj["pretest"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::decodeQuiz) },
            derivations = (obj["derivations"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                BookCompanionDerivation(
                    o.str("id"), o.str("sectionId"), o.str("title"), o.str("setup"),
                    o.strList("steps"), o.str("graphCheck"), o.str("pitfall"),
                    decodeAnchor(o["anchor"] as? JsonObject),
                )
            },
            optionalPractice = (obj["optionalPractice"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::decodeQuiz) },
            exerciseHints = (obj["exerciseHints"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                BookCompanionExerciseHint(
                    o.str("id"), o.str("sectionId"), o.str("problemLabel"), o.str("questionSummary"),
                    o.str("hint1Direction"), o.str("hint2Method"), o.str("hint3Check"),
                    decodeAnchor(o["anchor"] as? JsonObject),
                )
            },
            stats = BookCompanionStats(
                sectionChunks = st?.get("sectionChunks")?.jsonPrimitive?.intOrNull ?: 0,
                maxRequestTokens = st?.get("maxRequestTokens")?.jsonPrimitive?.intOrNull ?: 0,
                verifiedAnchors = st?.get("verifiedAnchors")?.jsonPrimitive?.intOrNull ?: 0,
                rejectedAnchors = st?.get("rejectedAnchors")?.jsonPrimitive?.intOrNull ?: 0,
                exercisesIsolated = st?.get("exercisesIsolated")?.jsonPrimitive?.intOrNull ?: 0,
                overlapAudit = BookCompanionOverlapAudit(
                    en8GramHits = ov?.get("en8GramHits")?.jsonPrimitive?.intOrNull ?: 0,
                    zh12GramHits = ov?.get("zh12GramHits")?.jsonPrimitive?.intOrNull ?: 0,
                    passed = ov?.get("passed")?.jsonPrimitive?.booleanOrNull ?: true,
                ),
                p50ElapsedMs = st?.get("p50ElapsedMs")?.jsonPrimitive?.longOrNull ?: 0L,
                p95ElapsedMs = st?.get("p95ElapsedMs")?.jsonPrimitive?.longOrNull ?: 0L,
                p50TtftMs = st?.get("p50TtftMs")?.jsonPrimitive?.longOrNull ?: 0L,
                p95TtftMs = st?.get("p95TtftMs")?.jsonPrimitive?.longOrNull ?: 0L,
                totalCostMicrodollars = st?.get("totalCostMicrodollars")?.jsonPrimitive?.longOrNull ?: 0L,
                routeChannelsUsed = st?.strList("routeChannelsUsed").orEmpty(),
                fromCache = st?.get("fromCache")?.jsonPrimitive?.booleanOrNull ?: false,
            ),
        )
    }

    fun encodeDraft(d: ChapterPracticeDraft): JsonObject = buildJsonObject {
        put("bookId", d.bookId)
        put("tocNodeId", d.tocNodeId)
        put("page", d.page)
        put("offsetUtf16", d.offsetUtf16)
        put("companionOpen", d.companionOpen)
        put("companionTab", d.companionTab)
        put("activeModule", d.activeModule)
        d.activeQuestionId?.let { put("activeQuestionId", it) }
        put("pretestSelections", buildJsonObject { d.pretestSelections.forEach { (k, v) -> put(k, v) } })
        put("pretestGuessed", buildJsonObject { d.pretestGuessed.forEach { (k, v) -> put(k, v) } })
        put("pretestSubmitted", buildJsonArray { d.pretestSubmitted.forEach { add(JsonPrimitive(it)) } })
        put("practiceSelections", buildJsonObject { d.practiceSelections.forEach { (k, v) -> put(k, v) } })
        put("practiceTextDrafts", buildJsonObject { d.practiceTextDrafts.forEach { (k, v) -> put(k, v) } })
        put("practiceCursorPositions", buildJsonObject { d.practiceCursorPositions.forEach { (k, v) -> put(k, v) } })
        put("practiceGuessed", buildJsonObject { d.practiceGuessed.forEach { (k, v) -> put(k, v) } })
        put("practiceSubmitted", buildJsonArray { d.practiceSubmitted.forEach { add(JsonPrimitive(it)) } })
        put("exerciseHintLevels", buildJsonObject { d.exerciseHintLevels.forEach { (k, v) -> put(k, v) } })
        if (d.openQuestionJson.isNotBlank()) put("openQuestionJson", d.openQuestionJson)
        if (d.openAttemptsJson.isNotBlank()) put("openAttemptsJson", d.openAttemptsJson)
        if (d.openAnswerDraft.isNotBlank()) put("openAnswerDraft", d.openAnswerDraft)
        put("updatedAt", d.updatedAt)
    }

    fun decodeDraft(raw: String): ChapterPracticeDraft {
        val o = parser.parseToJsonElement(raw).jsonObject
        fun strMap(k: String): Map<String, String> =
            (o[k] as? JsonObject).orEmpty().mapNotNull { (mk, mv) ->
                (mv as? JsonPrimitive)?.contentOrNull?.let { mk to it }
            }.toMap()
        fun boolMap(k: String): Map<String, Boolean> =
            (o[k] as? JsonObject).orEmpty().mapNotNull { (mk, mv) ->
                (mv as? JsonPrimitive)?.booleanOrNull?.let { mk to it }
            }.toMap()
        fun intMap(k: String): Map<String, Int> =
            (o[k] as? JsonObject).orEmpty().mapNotNull { (mk, mv) ->
                (mv as? JsonPrimitive)?.intOrNull?.let { mk to it }
            }.toMap()
        return ChapterPracticeDraft(
            bookId = o.str("bookId"),
            tocNodeId = o.str("tocNodeId"),
            page = o["page"]?.jsonPrimitive?.intOrNull ?: 1,
            offsetUtf16 = o["offsetUtf16"]?.jsonPrimitive?.intOrNull ?: 0,
            companionOpen = o["companionOpen"]?.jsonPrimitive?.booleanOrNull ?: false,
            companionTab = o.str("companionTab").ifBlank { "EXPLAIN" },
            activeModule = o.str("activeModule").ifBlank { CompanionModuleTab.KEY_POINTS.name },
            activeQuestionId = (o["activeQuestionId"] as? JsonPrimitive)?.contentOrNull,
            pretestSelections = strMap("pretestSelections"),
            pretestGuessed = boolMap("pretestGuessed"),
            pretestSubmitted = o.strList("pretestSubmitted").toSet(),
            practiceSelections = strMap("practiceSelections"),
            practiceTextDrafts = strMap("practiceTextDrafts"),
            practiceCursorPositions = intMap("practiceCursorPositions"),
            practiceGuessed = boolMap("practiceGuessed"),
            practiceSubmitted = o.strList("practiceSubmitted").toSet(),
            exerciseHintLevels = intMap("exerciseHintLevels"),
            openQuestionJson = o.str("openQuestionJson"),
            openAttemptsJson = o.str("openAttemptsJson"),
            openAnswerDraft = o.str("openAnswerDraft"),
            updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }
}

/** Reads & writes `book_chapter_progress.practice_draft_json` and `book_section_cache` companion packs. */
internal object ChapterProgressStore {
    private const val COMPANION_CACHE_KIND = "companion_v2"

    fun savePracticeDraft(
        db: Connection,
        bookId: String,
        tocNodeId: String,
        pagesTotal: Int,
        draft: ChapterPracticeDraft,
    ) {
        if (!BookSchemaMigration.tableExists(db, "book_chapter_progress")) return
        val now = draft.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val payload = CompanionJson.encodeDraft(draft.copy(bookId = bookId, tocNodeId = tocNodeId, updatedAt = now)).toString()
        db.prepareStatement(
            """INSERT INTO book_chapter_progress(
                book_id, toc_node_id, pages_total, pages_dwelled, dwelled_pages_json,
                read_state, manual_override, practice_draft_json, updated_at
            ) VALUES(?,?,?,0,'[]','UNREAD',0,?,?)
            ON CONFLICT(book_id, toc_node_id) DO UPDATE SET
                pages_total=MAX(book_chapter_progress.pages_total, excluded.pages_total),
                practice_draft_json=excluded.practice_draft_json,
                updated_at=excluded.updated_at""".trimIndent(),
        ).use { s ->
            s.setString(1, bookId)
            s.setString(2, tocNodeId)
            s.setInt(3, pagesTotal.coerceAtLeast(1))
            s.setString(4, payload)
            s.setLong(5, now)
            s.executeUpdate()
        }
    }

    fun loadPracticeDraft(db: Connection, bookId: String, tocNodeId: String): ChapterPracticeDraft? {
        if (!BookSchemaMigration.tableExists(db, "book_chapter_progress")) return null
        return db.prepareStatement(
            "SELECT practice_draft_json FROM book_chapter_progress WHERE book_id=? AND toc_node_id=?",
        ).use { s ->
            s.setString(1, bookId)
            s.setString(2, tocNodeId)
            s.executeQuery().use { r ->
                if (!r.next()) null else decodeDraft(r.getString(1))
            }
        }
    }

    fun latestPracticeDraft(db: Connection, bookId: String): ChapterPracticeDraft? {
        if (!BookSchemaMigration.tableExists(db, "book_chapter_progress")) return null
        return db.prepareStatement(
            """SELECT practice_draft_json FROM book_chapter_progress
               WHERE book_id=? AND practice_draft_json <> '{}'
               ORDER BY updated_at DESC LIMIT 5""".trimIndent(),
        ).use { s ->
            s.setString(1, bookId)
            s.executeQuery().use { r ->
                var fallback: ChapterPracticeDraft? = null
                while (r.next()) {
                    val decoded = decodeDraft(r.getString(1)) ?: continue
                    if (decoded.hasMeaningfulDraft()) return decoded
                    if (fallback == null) fallback = decoded
                }
                fallback
            }
        }
    }

    private fun decodeDraft(raw: String?): ChapterPracticeDraft? {
        if (raw.isNullOrBlank() || raw == "{}") return null
        return runCatching { CompanionJson.decodeDraft(raw) }.getOrNull()
    }

    fun saveCachedPack(db: Connection, bookId: String, chapterId: String, pack: BookCompanionPack) {
        if (!BookSchemaMigration.tableExists(db, "book_section_cache")) return
        val payload = CompanionJson.encodePack(pack).toString()
        db.prepareStatement(
            "INSERT OR REPLACE INTO book_section_cache(book, section_id, kind, payload, updated_at) VALUES(?,?,?,?,?)",
        ).use { s ->
            s.setString(1, bookId)
            s.setString(2, chapterId)
            s.setString(3, COMPANION_CACHE_KIND)
            s.setString(4, payload)
            s.setLong(5, System.currentTimeMillis())
            s.executeUpdate()
        }
    }

    fun loadCachedPack(db: Connection, bookId: String, chapterId: String, asOf: Long = Long.MAX_VALUE): BookCompanionPack? {
        if (!BookSchemaMigration.tableExists(db, "book_section_cache")) return null
        return db.prepareStatement(
            "SELECT payload FROM book_section_cache WHERE book=? AND section_id=? AND kind=? AND updated_at<=?",
        ).use { s ->
            s.setString(1, bookId)
            s.setString(2, chapterId)
            s.setString(3, COMPANION_CACHE_KIND)
            s.setLong(4, asOf)
            s.executeQuery().use { r ->
                if (!r.next()) null else runCatching {
                    CompanionJson.decodePack(r.getString(1))
                }.getOrNull()
            }
        }
    }
}

/** Groups sections belonging to the current chapter and builds the wire request for `/v1/library/companion/chapter`. */
internal data class ChapterSectionGroup(
    val chapterId: String,
    val chapterTitle: String,
    val firstPage: Int,
    val lastPage: Int,
    val sections: List<BookLesson>,
) {
    val pageCount: Int get() = (lastPage - firstPage + 1).coerceAtLeast(1)
}

internal object ChapterCompanionGrouping {
    private val EXERCISE_RE = Regex(
        """(?i)^(?:\d+(?:\.\d+)*\s+)?(?:exercises?|problems?|review\s+questions?|chapter\s+exercises?|study\s+questions?|homework|习题|练习题|思考题)\b""",
    )

    fun groupForSection(book: Textbook, sectionIndex: Int): ChapterSectionGroup? {
        val current = book.lessons.getOrNull(sectionIndex) ?: return null
        if (book.tocNodes.isNotEmpty()) {
            val chapter = BookChapterResolver.chapterForLesson(book, sectionIndex) ?: return null
            val sections = chapter.sectionIndices.mapNotNull(book.lessons::getOrNull).ifEmpty { listOf(current) }
            return ChapterSectionGroup(chapter.tocNodeId, chapter.title, chapter.firstPage, chapter.lastPage, sections)
        }
        val chapterPrefix = chapterGroupKey(current)
        val siblings = if (chapterPrefix != null) {
            book.lessons.filter { chapterGroupKey(it) == chapterPrefix }
        } else {
            listOf(current)
        }
        val ordered = siblings.ifEmpty { listOf(current) }
        val chapterTitle = ordered.first().breadcrumb.split('›').map(String::trim)
            .lastOrNull { it.isNotEmpty() && it != ordered.first().title }
            ?: ordered.first().title
        val chapterId = chapterPrefix ?: ordered.first().id
        return ChapterSectionGroup(
            chapterId = chapterId,
            chapterTitle = chapterTitle,
            firstPage = ordered.minOf { it.firstPage },
            lastPage = ordered.maxOf { it.lastPage },
            sections = ordered,
        )
    }

    private fun chapterGroupKey(lesson: BookLesson): String? {
        val parts = lesson.breadcrumb.split('›').map(String::trim).filter(String::isNotEmpty)
        if (parts.size >= 2) {
            return parts.dropLast(1).joinToString(" › ")
        }
        val numMatch = Regex("^(\\d+)\\.\\d+").find(lesson.title.trim())
        if (numMatch != null) return "ch-${numMatch.groupValues[1]}"
        return null
    }

    fun isExerciseSection(title: String): Boolean = EXERCISE_RE.containsMatchIn(title.trim())

    fun filterForCompanion(book: Textbook, group: ChapterSectionGroup): ChapterSectionGroup {
        val captionCache = HashMap<Int, Set<String>>()
        fun captionsFor(page: Int): Set<String> = captionCache.getOrPut(page) { pageIllustrationCaptions(book, page) }

        val hasAnyProse = group.sections.any { sec ->
            sec.blocks.any { blk -> !blk.heading && !isLeadingImageCaptionBlock(blk, captionsFor(blk.page)) }
        }
        if (!hasAnyProse) return group

        val withoutLeadingCaptionSections = group.sections.dropWhile { sec ->
            val effective = sec.blocks.filterNot { it.heading }.ifEmpty { sec.blocks }
            effective.isEmpty() || effective.all { blk -> isLeadingImageCaptionBlock(blk, captionsFor(blk.page)) }
        }.ifEmpty { group.sections }

        var proseSeen = false
        val cleanedSections = withoutLeadingCaptionSections.mapNotNull { sec ->
            if (proseSeen) {
                sec
            } else {
                val filteredBlocks = ArrayList<SourceBlock>(sec.blocks.size)
                for (blk in sec.blocks) {
                    if (proseSeen || blk.heading) {
                        filteredBlocks += blk
                    } else if (!isLeadingImageCaptionBlock(blk, captionsFor(blk.page))) {
                        proseSeen = true
                        filteredBlocks += blk
                    }
                }
                if (filteredBlocks.any { !it.heading }) sec.copy(blocks = filteredBlocks) else null
            }
        }.ifEmpty { withoutLeadingCaptionSections }

        return group.copy(
            firstPage = cleanedSections.minOf { it.firstPage },
            lastPage = cleanedSections.maxOf { it.lastPage },
            sections = cleanedSections,
        )
    }

    private data class DroppedCaptionContext(
        val droppedBlockIds: Set<String>,
        val droppedSectionIds: Set<String>,
        val droppedBlocks: List<SourceBlock>,
    )

    private fun droppedLeadingCaptionContext(book: Textbook, group: ChapterSectionGroup): DroppedCaptionContext {
        val companionGroup = filterForCompanion(book, group)
        val allowedBlockIds = wireBlocks(companionGroup).mapTo(HashSet()) { it.id }
        val droppedBlocks = wireBlocks(group).filter { it.id !in allowedBlockIds }
        val allowedSectionIds = companionGroup.sections.mapTo(HashSet()) { it.id }
        val droppedSectionIds = group.sections.map { it.id }.filterTo(HashSet()) { it !in allowedSectionIds }
        return DroppedCaptionContext(
            droppedBlockIds = droppedBlocks.mapTo(HashSet()) { it.id },
            droppedSectionIds = droppedSectionIds,
            droppedBlocks = droppedBlocks,
        )
    }

    private fun matchesDroppedCaption(
        sectionId: String,
        anchor: BookCompanionAnchor?,
        ctx: DroppedCaptionContext,
    ): Boolean {
        if (anchor != null && anchor.blockId in ctx.droppedBlockIds) return true
        if (sectionId.isNotBlank() && sectionId in ctx.droppedSectionIds) return true
        if (anchor != null) {
            val quote = anchor.quoteAnchor.trim()
            return ctx.droppedBlocks.any { blk ->
                blk.page == anchor.pdfPage && (
                    (anchor.endUtf16 > blk.start && anchor.startUtf16 < blk.end) ||
                        (quote.isNotBlank() && (blk.text.contains(quote, ignoreCase = true) || quote.contains(blk.text.trim(), ignoreCase = true)))
                    )
            }
        }
        return false
    }

    fun hasStaleLeadingCaption(book: Textbook, group: ChapterSectionGroup, pack: BookCompanionPack): Boolean {
        val ctx = droppedLeadingCaptionContext(book, group)
        if (ctx.droppedBlocks.isEmpty()) return false
        return pack.keyPoints.any { kp -> matchesDroppedCaption(kp.sectionId, kp.anchor, ctx) }
    }

    fun sanitizeCachedPack(book: Textbook, group: ChapterSectionGroup, pack: BookCompanionPack): BookCompanionPack? {
        val ctx = droppedLeadingCaptionContext(book, group)
        if (ctx.droppedBlocks.isEmpty()) return pack
        val cleanKeyPoints = pack.keyPoints.filterNot { kp -> matchesDroppedCaption(kp.sectionId, kp.anchor, ctx) }
        if (cleanKeyPoints.isEmpty()) return null
        val cleanJargon = pack.jargonGlosses.filterNot { g -> matchesDroppedCaption("", g.anchor, ctx) }
        return pack.copy(keyPoints = cleanKeyPoints, jargonGlosses = cleanJargon)
    }

    fun wireBlocks(group: ChapterSectionGroup): List<SourceBlock> {
        val seen = HashSet<String>()
        return group.sections.flatMap { sec ->
            (sec.blocks.filterNot { it.heading }.ifEmpty { sec.blocks }).filter { seen.add(it.id) }
        }
    }

    fun buildWireRequest(book: Textbook, group: ChapterSectionGroup): JsonObject = buildJsonObject {
        val effectiveGroup = filterForCompanion(book, group)
        val seen = HashSet<String>()
        put("bookId", book.id)
        put("bookPages", book.pages.size.coerceAtLeast(1))
        put("chapterId", effectiveGroup.chapterId)
        put("chapterTitle", effectiveGroup.chapterTitle)
        put("sections", buildJsonArray {
            for (sec in effectiveGroup.sections) {
                val bodyBlocks = (sec.blocks.filterNot { it.heading }.ifEmpty { sec.blocks }).filter { seen.add(it.id) }
                if (bodyBlocks.isEmpty()) continue
                add(buildJsonObject {
                    put("sectionId", sec.id)
                    put("title", sec.title)
                    put("isExerciseSection", isExerciseSection(sec.title))
                    put("blocks", buildJsonArray {
                        for (blk in bodyBlocks) {
                            add(buildJsonObject {
                                put("id", blk.id)
                                put("pdfPage", blk.page)
                                put("startUtf16", blk.start)
                                put("text", blk.text)
                            })
                        }
                    })
                })
            }
        })
    }
}

internal object ChapterCompanionProgressive {
    /** Picks one initial body section so Pretest unlocks as soon as the first section completes. */
    fun firstSectionGroup(group: ChapterSectionGroup, currentSectionId: String?): ChapterSectionGroup? {
        if (group.sections.size <= 1) return null
        val nonExercise = group.sections.filterNot { ChapterCompanionGrouping.isExerciseSection(it.title) }
        val first = nonExercise.firstOrNull { it.id == currentSectionId }
            ?: nonExercise.firstOrNull()
            ?: group.sections.first()
        return group.copy(
            firstPage = first.firstPage,
            lastPage = first.lastPage,
            sections = listOf(first),
        )
    }

    fun remainingSectionGroup(group: ChapterSectionGroup, firstSectionId: String): ChapterSectionGroup? {
        val rest = group.sections.filterNot { it.id == firstSectionId }
        if (rest.isEmpty()) return null
        return group.copy(
            firstPage = rest.minOf { it.firstPage },
            lastPage = rest.maxOf { it.lastPage },
            sections = rest,
        )
    }

    fun merge(first: BookCompanionPack, rest: BookCompanionPack): BookCompanionPack = BookCompanionPack(
        version = maxOf(first.version, rest.version),
        bookId = first.bookId,
        chapterId = first.chapterId,
        chapterTitle = first.chapterTitle.ifBlank { rest.chapterTitle },
        coreQuestion = first.coreQuestion.ifBlank { rest.coreQuestion },
        keyPoints = (first.keyPoints + rest.keyPoints).distinctBy { it.id },
        jargonGlosses = (first.jargonGlosses + rest.jargonGlosses).distinctBy { it.term },
        pretest = (first.pretest + rest.pretest).distinctBy { it.id },
        derivations = (first.derivations + rest.derivations).distinctBy { it.id },
        optionalPractice = (first.optionalPractice + rest.optionalPractice).distinctBy { it.id },
        exerciseHints = (first.exerciseHints + rest.exerciseHints).distinctBy { it.id },
        stats = BookCompanionStats(
            sectionChunks = first.stats.sectionChunks + rest.stats.sectionChunks,
            maxRequestTokens = maxOf(first.stats.maxRequestTokens, rest.stats.maxRequestTokens),
            verifiedAnchors = first.stats.verifiedAnchors + rest.stats.verifiedAnchors,
            rejectedAnchors = first.stats.rejectedAnchors + rest.stats.rejectedAnchors,
            exercisesIsolated = first.stats.exercisesIsolated + rest.stats.exercisesIsolated,
            overlapAudit = BookCompanionOverlapAudit(
                en8GramHits = first.stats.overlapAudit.en8GramHits + rest.stats.overlapAudit.en8GramHits,
                zh12GramHits = first.stats.overlapAudit.zh12GramHits + rest.stats.overlapAudit.zh12GramHits,
                passed = first.stats.overlapAudit.passed && rest.stats.overlapAudit.passed,
            ),
            p50ElapsedMs = maxOf(first.stats.p50ElapsedMs, rest.stats.p50ElapsedMs),
            p95ElapsedMs = maxOf(first.stats.p95ElapsedMs, rest.stats.p95ElapsedMs),
            p50TtftMs = maxOf(first.stats.p50TtftMs, rest.stats.p50TtftMs),
            p95TtftMs = maxOf(first.stats.p95TtftMs, rest.stats.p95TtftMs),
            totalCostMicrodollars = first.stats.totalCostMicrodollars + rest.stats.totalCostMicrodollars,
            routeChannelsUsed = (first.stats.routeChannelsUsed + rest.stats.routeChannelsUsed).distinct(),
            fromCache = first.stats.fromCache && rest.stats.fromCache,
        ),
    )
}
