package com.coach.desktop.books

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection

internal enum class ConceptMentionRole {
    PRIMARY_DEF,
    FORMAL_MODEL,
    EMPIRICAL_TEST,
    WORKED_PROBLEM,
    COUNTER_VIEW,
    ;

    companion object {
        fun fromWire(raw: String?): ConceptMentionRole =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PRIMARY_DEF
    }
}

internal enum class ConceptRelationType(val label: String, val priority: Int) {
    OPPOSING_VIEW("观点交锋", 5),
    EMPIRICAL_CHALLENGE("实证反差", 4),
    NOTATION_OR_BOUNDARY_DIFF("边界差异", 3),
    PREREQUISITE("前置铺垫", 2),
    CONSISTENT_DEEPENING("深化补充", 1),
    ;

    companion object {
        fun fromWire(raw: String?): ConceptRelationType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: CONSISTENT_DEEPENING
    }
}

internal data class ConceptEntityRecord(
    val conceptId: String,
    val collectionId: String,
    val canonicalName: String,
    val aliases: List<String> = emptyList(),
    val muted: Boolean = false,
    val mutedAt: Long? = null,
    val sharedNote: String = "",
)

internal data class ConceptMentionRecord(
    val mentionId: String,
    val conceptId: String,
    val bookId: String,
    val tocNodeId: String?,
    val chunkId: String? = null,
    val page: Int,
    val offsetUtf16: Int,
    val quoteAnchor: String,
    val mentionRole: ConceptMentionRole = ConceptMentionRole.PRIMARY_DEF,
    val claimSummary: String,
)

internal data class ConceptLinkRecord(
    val linkId: String,
    val collectionId: String,
    val conceptId: String,
    val fromMentionId: String,
    val toMentionId: String,
    val relationType: ConceptRelationType,
    val diffSummary: String,
    val confidence: Double,
    val dismissed: Boolean = false,
)

/**
 * 右侧伴读抽屉底部展示的单张跨书联动微卡（严格遵守四条零打扰铁律）：
 * - 正文 0px 行内标注/弹窗；
 * - 仅连目标章已达 `READ` 的章节；
 * - 每节硬封顶 <= 2 张高价值卡片；
 * - 右上角 `×` 直接对 `concept_entities.muted = 1` 永久主题级消音。
 */
internal data class CrossBookCompanionCard(
    val linkId: String,
    val conceptId: String,
    val collectionId: String,
    val collectionTitle: String,
    val conceptName: String,
    val otherBookId: String,
    val otherBookTitle: String,
    val otherChapterId: String,
    val otherPage: Int,
    val otherOffsetUtf16: Int,
    val otherQuoteAnchor: String,
    val relationType: ConceptRelationType,
    val diffSummary: String,
    val confidence: Double,
    val hasUserAnnotation: Boolean = false,
)

internal data class BooklistMentionView(
    val mention: ConceptMentionRecord,
    val bookTitle: String,
    val chapterTitle: String,
    val readState: ChapterReadState,
)

internal data class BooklistConceptView(
    val entity: ConceptEntityRecord,
    val mentions: List<BooklistMentionView>,
    val links: List<ConceptLinkRecord>,
) {
    val bookCount: Int get() = mentions.map { it.mention.bookId }.distinct().size
    val headlineDiff: String
        get() = links.maxByOrNull { it.relationType.priority * 10.0 + it.confidence }?.diffSummary
            ?: mentions.firstOrNull()?.mention?.claimSummary.orEmpty()
}

internal data class BooklistConceptsSnapshot(
    val active: List<BooklistConceptView>,
    val muted: List<BooklistConceptView>,
)

/**
 * 原文 UTF-16 `quote_anchor` 硬核验器：
 * 任何提及在写入 `concept_mentions` 前必须能在 `pages(book, page).text` 中逐字符回验，
 * 找不到精确子串一律拒收入库，彻底杜绝幻觉锚点。
 */
internal object ConceptAnchorVerifier {
    data class VerifiedAnchor(val offsetUtf16: Int, val quoteAnchor: String)

    fun verify(pageText: String, quoteAnchor: String, claimedOffset: Int? = null): VerifiedAnchor? {
        val quote = quoteAnchor.trim()
        if (quote.length < 2 || pageText.isEmpty()) return null
        if (claimedOffset != null && claimedOffset >= 0 && claimedOffset + quote.length <= pageText.length) {
            if (pageText.regionMatches(claimedOffset, quote, 0, quote.length)) {
                return VerifiedAnchor(claimedOffset, quote)
            }
        }
        val exact = pageText.indexOf(quote)
        if (exact >= 0) {
            return VerifiedAnchor(exact, pageText.substring(exact, exact + quote.length))
        }
        val ignoreCase = pageText.indexOf(quote, ignoreCase = true)
        if (ignoreCase >= 0) {
            val canonicalSlice = pageText.substring(ignoreCase, ignoreCase + quote.length)
            return VerifiedAnchor(ignoreCase, canonicalSlice)
        }
        return null
    }
}

/**
 * 书单共同主题持久化与四铁律查询存储层（读写 `concept_entities`、`concept_mentions`、`concept_links`）。
 */
internal object ConceptStore {
    const val MAX_COMPANION_CARDS_PER_SECTION: Int = 2
    const val MIN_COMPANION_CONFIDENCE: Double = 0.85

    private val json = Json { ignoreUnknownKeys = true }

    private fun decodeAliases(raw: String?): List<String> {
        if (raw.isNullOrBlank() || raw == "[]") return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    private fun encodeAliases(aliases: List<String>): String =
        json.encodeToString(aliases.map(String::trim).filter(String::isNotEmpty).distinct())

    private fun normalizeKey(name: String): String =
        name.trim().lowercase().replace(Regex("\\s+"), " ")

    /**
     * 原子写入合集内经过 UTF-16 校验的概念、提及与关联边。
     * 重跑对齐（如新加书）时按规范名/别名继承已有的 `muted`、`muted_at` 和 `shared_note`，
     * 绝不把用户已消音的主题重新放出来，也绝不丢失共享笔记。
     */
    fun saveAlignedConcepts(
        db: Connection,
        collectionId: String,
        entities: List<ConceptEntityRecord>,
        mentions: List<ConceptMentionRecord>,
        links: List<ConceptLinkRecord>,
    ) {
        if (!BookSchemaMigration.tableExists(db, "concept_entities")) return
        val existing = loadEntities(db, collectionId)
        val preservedByKey = HashMap<String, ConceptEntityRecord>()
        for (old in existing) {
            preservedByKey[normalizeKey(old.canonicalName)] = old
            for (alias in old.aliases) {
                preservedByKey.putIfAbsent(normalizeKey(alias), old)
            }
        }

        val prevAutoCommit = db.autoCommit
        db.autoCommit = false
        try {
            db.prepareStatement("DELETE FROM concept_links WHERE collection_id=?").use { s ->
                s.setString(1, collectionId)
                s.executeUpdate()
            }
            db.prepareStatement(
                "DELETE FROM concept_mentions WHERE concept_id IN (SELECT concept_id FROM concept_entities WHERE collection_id=?)",
            ).use { s ->
                s.setString(1, collectionId)
                s.executeUpdate()
            }
            db.prepareStatement("DELETE FROM concept_entities WHERE collection_id=?").use { s ->
                s.setString(1, collectionId)
                s.executeUpdate()
            }

            val insertedEntityIds = HashSet<String>()
            db.prepareStatement(
                """INSERT INTO concept_entities(
                    concept_id, collection_id, canonical_name, aliases_json, muted, muted_at, shared_note
                ) VALUES(?,?,?,?,?,?,?)""".trimIndent(),
            ).use { s ->
                for (entity in entities) {
                    val keys = listOf(entity.canonicalName) + entity.aliases
                    val prev = keys.firstNotNullOfOrNull { preservedByKey[normalizeKey(it)] }
                    val muted = entity.muted || (prev?.muted == true)
                    val mutedAt = entity.mutedAt ?: prev?.mutedAt
                    val sharedNote = entity.sharedNote.ifBlank { prev?.sharedNote.orEmpty() }
                    s.setString(1, entity.conceptId)
                    s.setString(2, collectionId)
                    s.setString(3, entity.canonicalName.trim())
                    s.setString(4, encodeAliases(entity.aliases))
                    s.setInt(5, if (muted) 1 else 0)
                    if (mutedAt == null) s.setNull(6, java.sql.Types.INTEGER) else s.setLong(6, mutedAt)
                    s.setString(7, sharedNote)
                    s.addBatch()
                    insertedEntityIds += entity.conceptId
                }
                if (insertedEntityIds.isNotEmpty()) s.executeBatch()
            }

            // Hard UTF-16 verification against stored `pages` rows before persistence.
            val pageTextCache = HashMap<Pair<String, Int>, String>()
            fun pageText(bookId: String, page: Int): String = pageTextCache.getOrPut(bookId to page) {
                db.prepareStatement("SELECT text FROM pages WHERE book=? AND page=?").use { q ->
                    q.setString(1, bookId)
                    q.setInt(2, page)
                    q.executeQuery().use { r -> if (r.next()) r.getString(1).orEmpty() else "" }
                }
            }

            val insertedMentionIds = HashSet<String>()
            db.prepareStatement(
                """INSERT INTO concept_mentions(
                    mention_id, concept_id, book_id, toc_node_id, chunk_id, page,
                    offset_utf16, quote_anchor, mention_role, claim_summary
                ) VALUES(?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
            ).use { s ->
                for (m in mentions) {
                    if (m.conceptId !in insertedEntityIds) continue
                    val verified = ConceptAnchorVerifier.verify(pageText(m.bookId, m.page), m.quoteAnchor, m.offsetUtf16)
                        ?: continue
                    s.setString(1, m.mentionId)
                    s.setString(2, m.conceptId)
                    s.setString(3, m.bookId)
                    if (m.tocNodeId == null) s.setNull(4, java.sql.Types.VARCHAR) else s.setString(4, m.tocNodeId)
                    if (m.chunkId == null) s.setNull(5, java.sql.Types.VARCHAR) else s.setString(5, m.chunkId)
                    s.setInt(6, m.page)
                    s.setInt(7, verified.offsetUtf16)
                    s.setString(8, verified.quoteAnchor)
                    s.setString(9, m.mentionRole.name)
                    s.setString(10, m.claimSummary.trim().take(240))
                    s.addBatch()
                    insertedMentionIds += m.mentionId
                }
                if (insertedMentionIds.isNotEmpty()) s.executeBatch()
            }

            db.prepareStatement(
                """INSERT INTO concept_links(
                    link_id, collection_id, concept_id, from_mention_id, to_mention_id,
                    relation_type, diff_summary, confidence, dismissed
                ) VALUES(?,?,?,?,?,?,?,?,?)""".trimIndent(),
            ).use { s ->
                var hasAny = false
                for (l in links) {
                    if (l.conceptId !in insertedEntityIds) continue
                    if (l.fromMentionId !in insertedMentionIds || l.toMentionId !in insertedMentionIds) continue
                    if (l.fromMentionId == l.toMentionId) continue
                    s.setString(1, l.linkId)
                    s.setString(2, collectionId)
                    s.setString(3, l.conceptId)
                    s.setString(4, l.fromMentionId)
                    s.setString(5, l.toMentionId)
                    s.setString(6, l.relationType.name)
                    s.setString(7, l.diffSummary.trim().take(60))
                    s.setDouble(8, l.confidence.coerceIn(0.0, 1.0))
                    s.setInt(9, if (l.dismissed) 1 else 0)
                    s.addBatch()
                    hasAny = true
                }
                if (hasAny) s.executeBatch()
            }
            if (BookSchemaMigration.tableExists(db, "concept_sayings")) {
                db.prepareStatement(
                    "DELETE FROM concept_sayings WHERE concept_id NOT IN (SELECT concept_id FROM concept_entities)",
                ).use { it.executeUpdate() }
            }
            db.commit()
        } catch (failure: Throwable) {
            runCatching { db.rollback() }
            throw failure
        } finally {
            runCatching { db.autoCommit = prevAutoCommit }
        }
    }

    /** 主题级永久消音 / 恢复：立即写入 `concept_entities.muted`，覆盖整份书单的所有章节和边。 */
    fun setConceptMuted(
        db: Connection,
        conceptId: String,
        muted: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!BookSchemaMigration.tableExists(db, "concept_entities")) return false
        return db.prepareStatement(
            "UPDATE concept_entities SET muted=?, muted_at=? WHERE concept_id=?",
        ).use { s ->
            s.setInt(1, if (muted) 1 else 0)
            if (muted) s.setLong(2, now) else s.setNull(2, java.sql.Types.INTEGER)
            s.setString(3, conceptId)
            s.executeUpdate() > 0
        }
    }

    fun saveSharedNote(db: Connection, conceptId: String, sharedNote: String): Boolean {
        if (!BookSchemaMigration.tableExists(db, "concept_entities")) return false
        return db.prepareStatement(
            "UPDATE concept_entities SET shared_note=? WHERE concept_id=?",
        ).use { s ->
            s.setString(1, sharedNote)
            s.setString(2, conceptId)
            s.executeUpdate() > 0
        }
    }

    fun loadEntities(db: Connection, collectionId: String): List<ConceptEntityRecord> {
        if (!BookSchemaMigration.tableExists(db, "concept_entities")) return emptyList()
        return db.prepareStatement(
            """SELECT concept_id, collection_id, canonical_name, aliases_json, muted, muted_at, shared_note
               FROM concept_entities WHERE collection_id=? ORDER BY muted ASC, canonical_name ASC""".trimIndent(),
        ).use { s ->
            s.setString(1, collectionId)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) {
                        val mutedAtObj = r.getObject(6)
                        add(
                            ConceptEntityRecord(
                                conceptId = r.getString(1),
                                collectionId = r.getString(2),
                                canonicalName = r.getString(3),
                                aliases = decodeAliases(r.getString(4)),
                                muted = r.getInt(5) != 0,
                                mutedAt = if (mutedAtObj == null) null else r.getLong(6),
                                sharedNote = r.getString(7).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun loadMentions(db: Connection, collectionId: String): List<ConceptMentionRecord> {
        if (!BookSchemaMigration.tableExists(db, "concept_mentions")) return emptyList()
        return db.prepareStatement(
            """SELECT m.mention_id, m.concept_id, m.book_id, m.toc_node_id, m.chunk_id,
                      m.page, m.offset_utf16, m.quote_anchor, m.mention_role, m.claim_summary
               FROM concept_mentions m
               INNER JOIN concept_entities e ON e.concept_id = m.concept_id
               WHERE e.collection_id=?
               ORDER BY m.concept_id, m.book_id, m.page, m.offset_utf16""".trimIndent(),
        ).use { s ->
            s.setString(1, collectionId)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) {
                        add(
                            ConceptMentionRecord(
                                mentionId = r.getString(1),
                                conceptId = r.getString(2),
                                bookId = r.getString(3),
                                tocNodeId = r.getString(4),
                                chunkId = r.getString(5),
                                page = r.getInt(6),
                                offsetUtf16 = r.getInt(7),
                                quoteAnchor = r.getString(8),
                                mentionRole = ConceptMentionRole.fromWire(r.getString(9)),
                                claimSummary = r.getString(10).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun loadLinks(db: Connection, collectionId: String): List<ConceptLinkRecord> {
        if (!BookSchemaMigration.tableExists(db, "concept_links")) return emptyList()
        return db.prepareStatement(
            """SELECT link_id, collection_id, concept_id, from_mention_id, to_mention_id,
                      relation_type, diff_summary, confidence, dismissed
               FROM concept_links
               WHERE collection_id=? AND dismissed=0
               ORDER BY confidence DESC""".trimIndent(),
        ).use { s ->
            s.setString(1, collectionId)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) {
                        add(
                            ConceptLinkRecord(
                                linkId = r.getString(1),
                                collectionId = r.getString(2),
                                conceptId = r.getString(3),
                                fromMentionId = r.getString(4),
                                toMentionId = r.getString(5),
                                relationType = ConceptRelationType.fromWire(r.getString(6)),
                                diffSummary = r.getString(7).orEmpty(),
                                confidence = r.getDouble(8),
                                dismissed = r.getInt(9) != 0,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun countActiveConcepts(db: Connection, collectionId: String): Int {
        if (!BookSchemaMigration.tableExists(db, "concept_entities")) return 0
        return db.prepareStatement(
            """SELECT COUNT(DISTINCT e.concept_id)
               FROM concept_entities e
               INNER JOIN concept_links l ON l.concept_id = e.concept_id AND l.dismissed = 0
               WHERE e.collection_id=? AND e.muted=0""".trimIndent(),
        ).use { s ->
            s.setString(1, collectionId)
            s.executeQuery().use { r -> if (r.next()) r.getInt(1) else 0 }
        }
    }

    fun loadBooklistConcepts(
        db: Connection,
        collectionId: String,
        bookTitles: Map<String, String>,
        chapterTitlesByBook: Map<String, Map<String, String>>,
    ): BooklistConceptsSnapshot {
        val entities = loadEntities(db, collectionId)
        if (entities.isEmpty()) return BooklistConceptsSnapshot(emptyList(), emptyList())
        val mentionsByConcept = loadMentions(db, collectionId).groupBy { it.conceptId }
        val linksByConcept = loadLinks(db, collectionId).groupBy { it.conceptId }
        val progressCache = HashMap<String, Map<String, ChapterProgressRecord>>()

        fun readStateOf(bookId: String, tocNodeId: String?): ChapterReadState {
            if (tocNodeId.isNullOrBlank()) return ChapterReadState.UNREAD
            val map = progressCache.getOrPut(bookId) { BooklistProgressStore.loadForBook(db, bookId) }
            return map[tocNodeId]?.readState ?: ChapterReadState.UNREAD
        }

        val active = mutableListOf<BooklistConceptView>()
        val muted = mutableListOf<BooklistConceptView>()
        for (entity in entities) {
            val mViews = mentionsByConcept[entity.conceptId].orEmpty().map { m ->
                BooklistMentionView(
                    mention = m,
                    bookTitle = bookTitles[m.bookId] ?: "未命名书籍",
                    chapterTitle = m.tocNodeId?.let { chapterTitlesByBook[m.bookId]?.get(it) } ?: "第 ${m.page} 页",
                    readState = readStateOf(m.bookId, m.tocNodeId),
                )
            }
            val cLinks = linksByConcept[entity.conceptId].orEmpty()
            val view = BooklistConceptView(entity, mViews, cLinks)
            if (entity.muted) muted += view else active += view
        }
        return BooklistConceptsSnapshot(
            active = active.sortedByDescending { it.bookCount * 100 + it.links.size },
            muted = muted.sortedByDescending { it.entity.mutedAt ?: 0L },
        )
    }

    /**
     * 查询当前阅读小节应展示在右侧伴读抽屉底部的跨书联动微卡。
     * 硬门禁：
     * 1. `concept_entities.muted = 0`（整主题消音后全书单 0 泄漏）且 `concept_links.dismissed = 0`；
     * 2. 目标侧书目对应章节必须满足 `book_chapter_progress.read_state = 'READ'`（绝不剧透未读或跳过的章节）；
     * 3. 高价值门槛：`confidence >= 0.85` 且（存在实质观点/实证/边界差异/前置互补，或用户在目标页/书做过高亮或批注）；
     * 4. 每个主题最多 1 张，整节硬封顶 `<= 2` 张。
     */
    fun queryCompanionCardsForSection(
        db: Connection,
        currentBookId: String,
        currentChapterId: String?,
        currentPageRange: IntRange,
        activeBooklistId: String? = null,
        maxCards: Int = MAX_COMPANION_CARDS_PER_SECTION,
    ): List<CrossBookCompanionCard> {
        if (!BookSchemaMigration.tableExists(db, "concept_links") ||
            !BookSchemaMigration.tableExists(db, "book_chapter_progress")
        ) return emptyList()

        val sql = buildString {
            append(
                """SELECT l.link_id, l.collection_id, c.title, l.concept_id, e.canonical_name,
                          l.relation_type, l.diff_summary, l.confidence,
                          mf.book_id, mf.toc_node_id, mf.page, mf.offset_utf16, mf.quote_anchor, mf.mention_role,
                          mt.book_id, mt.toc_node_id, mt.page, mt.offset_utf16, mt.quote_anchor, mt.mention_role
                   FROM concept_links l
                   INNER JOIN collections c ON c.id = l.collection_id AND c.kind = 'BOOKLIST' AND c.archived = 0
                   INNER JOIN concept_entities e ON e.concept_id = l.concept_id AND e.muted = 0
                   INNER JOIN concept_mentions mf ON mf.mention_id = l.from_mention_id
                   INNER JOIN concept_mentions mt ON mt.mention_id = l.to_mention_id
                   WHERE l.dismissed = 0
                     AND l.confidence >= ?
                     AND (mf.book_id = ? OR mt.book_id = ?)""".trimIndent(),
            )
            if (!activeBooklistId.isNullOrBlank()) {
                append(" AND l.collection_id = ?")
            }
        }

        data class RawCandidate(
            val linkId: String,
            val collectionId: String,
            val collectionTitle: String,
            val conceptId: String,
            val conceptName: String,
            val relationType: ConceptRelationType,
            val diffSummary: String,
            val confidence: Double,
            val localTocId: String?,
            val localPage: Int,
            val localRole: ConceptMentionRole,
            val otherBookId: String,
            val otherTocId: String?,
            val otherPage: Int,
            val otherOffsetUtf16: Int,
            val otherQuoteAnchor: String,
            val otherRole: ConceptMentionRole,
        )

        val candidates = db.prepareStatement(sql).use { q ->
            q.setDouble(1, MIN_COMPANION_CONFIDENCE)
            q.setString(2, currentBookId)
            q.setString(3, currentBookId)
            if (!activeBooklistId.isNullOrBlank()) q.setString(4, activeBooklistId)
            q.executeQuery().use { r ->
                buildList {
                    while (r.next()) {
                        val fBook = r.getString(9)
                        val tBook = r.getString(15)
                        if (fBook == tBook) continue
                        val localIsFrom = fBook == currentBookId
                        val localToc = if (localIsFrom) r.getString(10) else r.getString(16)
                        val localPage = if (localIsFrom) r.getInt(11) else r.getInt(17)
                        val localRole = ConceptMentionRole.fromWire(if (localIsFrom) r.getString(14) else r.getString(20))
                        val otherBook = if (localIsFrom) tBook else fBook
                        val otherToc = if (localIsFrom) r.getString(16) else r.getString(10)
                        val otherPage = if (localIsFrom) r.getInt(17) else r.getInt(11)
                        val otherOffset = if (localIsFrom) r.getInt(18) else r.getInt(12)
                        val otherQuote = if (localIsFrom) r.getString(19) else r.getString(13)
                        val otherRole = ConceptMentionRole.fromWire(if (localIsFrom) r.getString(20) else r.getString(14))

                        val matchesSection = (currentChapterId != null && localToc == currentChapterId) ||
                            (localPage in currentPageRange)
                        if (!matchesSection) continue

                        add(
                            RawCandidate(
                                linkId = r.getString(1),
                                collectionId = r.getString(2),
                                collectionTitle = r.getString(3),
                                conceptId = r.getString(4),
                                conceptName = r.getString(5),
                                relationType = ConceptRelationType.fromWire(r.getString(6)),
                                diffSummary = r.getString(7).orEmpty(),
                                confidence = r.getDouble(8),
                                localTocId = localToc,
                                localPage = localPage,
                                localRole = localRole,
                                otherBookId = otherBook,
                                otherTocId = otherToc,
                                otherPage = otherPage,
                                otherOffsetUtf16 = otherOffset,
                                otherQuoteAnchor = otherQuote,
                                otherRole = otherRole,
                            ),
                        )
                    }
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val readChaptersCache = HashMap<String, Set<String>>()
        val bookTitleCache = HashMap<String, String>()
        val annotatedPagesCache = HashMap<Pair<String, Int>, Boolean>()

        fun isOtherChapterRead(bookId: String, tocNodeId: String?): Boolean {
            if (tocNodeId.isNullOrBlank()) return false
            val readSet = readChaptersCache.getOrPut(bookId) { BooklistProgressStore.readChapterIds(db, bookId) }
            return tocNodeId in readSet
        }

        fun bookTitle(bookId: String): String = bookTitleCache.getOrPut(bookId) {
            db.prepareStatement("SELECT title FROM books WHERE id=?").use { q ->
                q.setString(1, bookId)
                q.executeQuery().use { r -> if (r.next()) r.getString(1) else "相关书籍" }
            }
        }

        fun hasUserAnnotation(bookId: String, page: Int): Boolean = annotatedPagesCache.getOrPut(bookId to page) {
            val hasHl = db.prepareStatement("SELECT 1 FROM book_highlights WHERE book=? AND page=? LIMIT 1").use { q ->
                q.setString(1, bookId); q.setInt(2, page)
                q.executeQuery().use { it.next() }
            }
            if (hasHl) return@getOrPut true
            db.prepareStatement("SELECT 1 FROM book_notes WHERE book=? AND page=? AND deleted=0 LIMIT 1").use { q ->
                q.setString(1, bookId); q.setInt(2, page)
                q.executeQuery().use { it.next() }
            }
        }

        val qualified = mutableListOf<CrossBookCompanionCard>()
        for (c in candidates) {
            // Iron Law 2: ONLY link chapters that are genuinely READ on the target book.
            if (!isOtherChapterRead(c.otherBookId, c.otherTocId)) continue

            val annotated = hasUserAnnotation(c.otherBookId, c.otherPage)
            val complementaryModelAndCase = (c.localRole == ConceptMentionRole.FORMAL_MODEL && c.otherRole in setOf(ConceptMentionRole.EMPIRICAL_TEST, ConceptMentionRole.WORKED_PROBLEM)) ||
                (c.otherRole == ConceptMentionRole.FORMAL_MODEL && c.localRole in setOf(ConceptMentionRole.EMPIRICAL_TEST, ConceptMentionRole.WORKED_PROBLEM))
            val substantiveRelation = c.relationType != ConceptRelationType.CONSISTENT_DEEPENING ||
                complementaryModelAndCase ||
                annotated ||
                c.confidence >= 0.90
            if (!substantiveRelation) continue

            qualified += CrossBookCompanionCard(
                linkId = c.linkId,
                conceptId = c.conceptId,
                collectionId = c.collectionId,
                collectionTitle = c.collectionTitle,
                conceptName = c.conceptName,
                otherBookId = c.otherBookId,
                otherBookTitle = bookTitle(c.otherBookId),
                otherChapterId = c.otherTocId.orEmpty(),
                otherPage = c.otherPage,
                otherOffsetUtf16 = c.otherOffsetUtf16,
                otherQuoteAnchor = c.otherQuoteAnchor,
                relationType = c.relationType,
                diffSummary = c.diffSummary,
                confidence = c.confidence,
                hasUserAnnotation = annotated,
            )
        }

        // Iron Law 3: Deduplicate by concept and hard-cap to <= 2 cards per section.
        return qualified
            .sortedWith(
                compareByDescending<CrossBookCompanionCard> { if (it.hasUserAnnotation) 1 else 0 }
                    .thenByDescending { it.relationType.priority }
                    .thenByDescending { it.confidence },
            )
            .distinctBy { it.conceptId }
            .take(maxCards.coerceIn(0, MAX_COMPANION_CARDS_PER_SECTION))
    }

    /** 说法对照表按使用时创建，不升库版本。对齐重写概念后顺手清掉已经不存在的概念行。 */
    private fun ensureSayingsTable(db: Connection) {
        db.createStatement().use { st ->
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS concept_sayings (
                    concept_id TEXT NOT NULL,
                    input_hash TEXT NOT NULL,
                    book_id TEXT NOT NULL,
                    mention_id TEXT NOT NULL,
                    book_title TEXT NOT NULL,
                    statement TEXT NOT NULL,
                    stance TEXT NOT NULL,
                    page INTEGER NOT NULL,
                    offset_utf16 INTEGER NOT NULL,
                    quote_anchor TEXT NOT NULL,
                    kept INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (concept_id, input_hash, book_id)
                )""".trimIndent(),
            )
        }
    }

    fun conceptNamesForBook(db: Connection, bookId: String): List<ConceptNameRef> {
        if (!BookSchemaMigration.tableExists(db, "concept_entities") ||
            !BookSchemaMigration.tableExists(db, "concept_mentions")
        ) return emptyList()
        return db.prepareStatement(
            """SELECT DISTINCT e.concept_id, e.canonical_name, e.aliases_json
               FROM concept_entities e
               INNER JOIN concept_mentions m ON m.concept_id = e.concept_id
               WHERE m.book_id = ? AND e.muted = 0
               ORDER BY e.canonical_name""".trimIndent(),
        ).use { s ->
            s.setString(1, bookId)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) {
                        val name = r.getString(2).orEmpty().trim()
                        if (name.isEmpty()) continue
                        add(ConceptNameRef(r.getString(1), name, decodeAliases(r.getString(3))))
                    }
                }
            }
        }
    }

    /**
     * 打开一个概念时并排看各书。当前这本书始终在列里。
     * 其他书必须有置信度达标、未驳回的边，而且那一章已读、目录节点非空。
     * 同一输入散列已经留过核对结果就直接用，不再请求。
     */
    fun loadSayingsBoard(db: Connection, conceptId: String, anchorBookId: String): ConceptSayingsBoard? {
        if (!BookSchemaMigration.tableExists(db, "concept_entities") ||
            !BookSchemaMigration.tableExists(db, "concept_mentions")
        ) return null
        ensureSayingsTable(db)
        data class EntityRow(val name: String, val muted: Boolean)
        val entity = db.prepareStatement(
            "SELECT canonical_name, muted FROM concept_entities WHERE concept_id=?",
        ).use { s ->
            s.setString(1, conceptId)
            s.executeQuery().use { r ->
                if (!r.next()) null else EntityRow(r.getString(1).orEmpty().trim(), r.getInt(2) != 0)
            }
        } ?: return null
        if (entity.muted || entity.name.isEmpty()) return null

        data class RawMention(
            val mentionId: String,
            val bookId: String,
            val tocNodeId: String?,
            val page: Int,
            val offsetUtf16: Int,
            val quoteAnchor: String,
            val role: ConceptMentionRole,
            val claim: String,
        )
        val rawMentions = db.prepareStatement(
            """SELECT mention_id, book_id, toc_node_id, page, offset_utf16, quote_anchor, mention_role, claim_summary
               FROM concept_mentions WHERE concept_id=?""".trimIndent(),
        ).use { s ->
            s.setString(1, conceptId)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) {
                        add(
                            RawMention(
                                mentionId = r.getString(1),
                                bookId = r.getString(2),
                                tocNodeId = r.getString(3),
                                page = r.getInt(4),
                                offsetUtf16 = r.getInt(5),
                                quoteAnchor = r.getString(6).orEmpty(),
                                role = ConceptMentionRole.fromWire(r.getString(7)),
                                claim = r.getString(8).orEmpty(),
                            ),
                        )
                    }
                }
            }
        }
        val pageTextCache = HashMap<Pair<String, Int>, String>()
        fun pageText(bookId: String, page: Int): String = pageTextCache.getOrPut(bookId to page) {
            db.prepareStatement("SELECT text FROM pages WHERE book=? AND page=?").use { q ->
                q.setString(1, bookId)
                q.setInt(2, page)
                q.executeQuery().use { r -> if (r.next()) r.getString(1).orEmpty() else "" }
            }
        }
        val mentions = rawMentions.mapNotNull { row ->
            val verified = ConceptAnchorVerifier.verify(pageText(row.bookId, row.page), row.quoteAnchor, row.offsetUtf16)
                ?: return@mapNotNull null
            row.copy(offsetUtf16 = verified.offsetUtf16, quoteAnchor = verified.quoteAnchor)
        }
        val anchorMentions = mentions.filter { it.bookId == anchorBookId }
        if (anchorMentions.isEmpty()) return null

        data class LinkRow(val fromId: String, val toId: String, val relation: ConceptRelationType)
        val links = if (!BookSchemaMigration.tableExists(db, "concept_links")) emptyList() else {
            db.prepareStatement(
                """SELECT from_mention_id, to_mention_id, relation_type
                   FROM concept_links
                   WHERE concept_id=? AND dismissed=0 AND confidence>=?""".trimIndent(),
            ).use { s ->
                s.setString(1, conceptId)
                s.setDouble(2, MIN_COMPANION_CONFIDENCE)
                s.executeQuery().use { r ->
                    buildList {
                        while (r.next()) {
                            add(LinkRow(r.getString(1), r.getString(2), ConceptRelationType.fromWire(r.getString(3))))
                        }
                    }
                }
            }
        }
        val anchorIds = anchorMentions.map { it.mentionId }.toSet()
        fun linked(mentionId: String): List<LinkRow> = links.filter { link ->
            (link.fromId == mentionId && link.toId in anchorIds) ||
                (link.toId == mentionId && link.fromId in anchorIds)
        }
        val readCache = HashMap<String, Set<String>>()
        fun chapterRead(bookId: String, tocNodeId: String?): Boolean {
            if (tocNodeId.isNullOrBlank()) return false
            return tocNodeId in readCache.getOrPut(bookId) { BooklistProgressStore.readChapterIds(db, bookId) }
        }
        fun pick(rows: List<RawMention>): RawMention = rows.minWith(
            compareBy<RawMention>({ if (it.role == ConceptMentionRole.PRIMARY_DEF) 0 else 1 }, { it.page }, { it.mentionId }),
        )
        val titleCache = HashMap<String, String>()
        fun bookTitle(bookId: String): String = titleCache.getOrPut(bookId) {
            db.prepareStatement("SELECT title FROM books WHERE id=?").use { q ->
                q.setString(1, bookId)
                q.executeQuery().use { r ->
                    if (r.next()) r.getString(1).orEmpty().ifBlank { "未命名书籍" } else "未命名书籍"
                }
            }
        }
        val anchor = pick(anchorMentions)
        val others = mentions
            .filter { it.bookId != anchorBookId }
            .groupBy { it.bookId }
            .mapNotNull { (_, rows) ->
                val qualified = rows.filter { linked(it.mentionId).isNotEmpty() && chapterRead(it.bookId, it.tocNodeId) }
                qualified.takeIf { it.isNotEmpty() }?.let(::pick)
            }
        fun candidate(row: RawMention, anchorRow: Boolean): SayingCandidate {
            val stance = if (anchorRow) {
                SayingStance.SAME
            } else {
                linked(row.mentionId).maxByOrNull { it.relation.priority }?.relation
                    ?.let(SayingStance::fromRelation) ?: SayingStance.ANGLE
            }
            return SayingCandidate(
                bookId = row.bookId,
                bookTitle = bookTitle(row.bookId),
                mentionId = row.mentionId,
                page = row.page,
                offsetUtf16 = row.offsetUtf16,
                quoteAnchor = row.quoteAnchor,
                claimSummary = row.claim,
                stance = stance,
                anchor = anchorRow,
            )
        }
        val candidates = (
            listOf(candidate(anchor, true)) + others.map { candidate(it, false) }.sortedBy { it.bookTitle }
            ).take(12)
        val hash = ConceptSayingLogic.inputHash(conceptId, candidates)
        val cached = db.prepareStatement(
            """SELECT book_id, mention_id, book_title, statement, stance, page, offset_utf16, quote_anchor, kept
               FROM concept_sayings WHERE concept_id=? AND input_hash=?""".trimIndent(),
        ).use { s ->
            s.setString(1, conceptId)
            s.setString(2, hash)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) {
                        add(
                            StoredSaying(
                                bookId = r.getString(1),
                                mentionId = r.getString(2),
                                bookTitle = r.getString(3).orEmpty(),
                                statement = r.getString(4).orEmpty(),
                                stance = SayingStance.fromWire(r.getString(5)),
                                page = r.getInt(6),
                                offsetUtf16 = r.getInt(7),
                                quoteAnchor = r.getString(8).orEmpty(),
                                kept = r.getInt(9) != 0,
                            ),
                        )
                    }
                }
            }
        }
        val pending = cached.isEmpty() && candidates.size >= 2
        val rows = if (cached.isNotEmpty()) {
            val byBook = cached.associateBy { it.bookId }
            candidates.map { c ->
                val stored = byBook[c.bookId]
                if (stored != null) stored.copy(kept = stored.kept || c.anchor)
                else StoredSaying(
                    bookId = c.bookId,
                    mentionId = c.mentionId,
                    bookTitle = c.bookTitle,
                    statement = ConceptSayingLogic.oneLine(c.claimSummary.ifBlank { c.quoteAnchor }),
                    stance = c.stance,
                    page = c.page,
                    offsetUtf16 = c.offsetUtf16,
                    quoteAnchor = c.quoteAnchor,
                    kept = c.anchor,
                )
            }
        } else {
            candidates.map { c ->
                StoredSaying(
                    bookId = c.bookId,
                    mentionId = c.mentionId,
                    bookTitle = c.bookTitle,
                    statement = ConceptSayingLogic.oneLine(c.claimSummary.ifBlank { c.quoteAnchor }),
                    stance = c.stance,
                    page = c.page,
                    offsetUtf16 = c.offsetUtf16,
                    quoteAnchor = c.quoteAnchor,
                    kept = true,
                )
            }
        }
        return ConceptSayingsBoard(
            conceptId = conceptId,
            conceptName = entity.name,
            anchorBookId = anchorBookId,
            inputHash = hash,
            columns = ConceptSayingLogic.columnsFrom(anchorBookId, rows),
            pending = pending,
            candidates = candidates,
        )
    }

    fun saveConceptSayings(
        db: Connection,
        board: ConceptSayingsBoard,
        parsed: List<ParsedBookSaying>,
        now: Long = System.currentTimeMillis(),
    ) {
        ensureSayingsTable(db)
        val rows = ConceptSayingLogic.rows(board, parsed)
        val prev = db.autoCommit
        db.autoCommit = false
        try {
            db.prepareStatement("DELETE FROM concept_sayings WHERE concept_id=?").use { s ->
                s.setString(1, board.conceptId)
                s.executeUpdate()
            }
            if (rows.isNotEmpty()) {
                db.prepareStatement(
                    """INSERT INTO concept_sayings(
                        concept_id, input_hash, book_id, mention_id, book_title, statement, stance,
                        page, offset_utf16, quote_anchor, kept, updated_at
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
                ).use { s ->
                    for (row in rows) {
                        s.setString(1, board.conceptId)
                        s.setString(2, board.inputHash)
                        s.setString(3, row.bookId)
                        s.setString(4, row.mentionId)
                        s.setString(5, row.bookTitle)
                        s.setString(6, row.statement)
                        s.setString(7, row.stance.wire)
                        s.setInt(8, row.page)
                        s.setInt(9, row.offsetUtf16)
                        s.setString(10, row.quoteAnchor)
                        s.setInt(11, if (row.kept) 1 else 0)
                        s.setLong(12, now)
                        s.addBatch()
                    }
                    s.executeBatch()
                }
            }
            db.commit()
        } catch (failure: Throwable) {
            runCatching { db.rollback() }
            throw failure
        } finally {
            runCatching { db.autoCommit = prev }
        }
    }
}
