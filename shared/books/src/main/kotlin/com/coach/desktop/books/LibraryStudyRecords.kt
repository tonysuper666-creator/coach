package com.coach.desktop.books

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.sql.Connection

/**
 * 对比表、综述提纲、概念并排进入同一个库。
 * 页正文只用来核对已经落盘的原句，对不上就不写偏移，避免点开时标到另一句。
 */
internal object LibraryStudyFields {
    const val GROUP = "文献组"
    const val ROW = "行序"
    const val SLOT = "格子"
    const val PAPER = "文献"
    const val CITE = "出处"
    const val ROLE = "角色"
    const val POINT = "论点序"
    const val CONCEPT = "概念"
    const val CONCEPT_ID = "概念标识"
    const val STANCE = "立场"
    const val SEPARATOR = '\u001f'
}

internal fun libraryDetailValues(detail: String): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    for (line in detail.lineSequence()) {
        val tab = line.indexOf('\t')
        if (tab <= 0) continue
        out.getOrPut(line.substring(0, tab)) { ArrayList() }.add(line.substring(tab + 1))
    }
    return out
}

internal fun collectStudyRecords(
    db: Connection,
    books: Map<String, LibraryBookHead>,
    ids: LibraryRecordIdScheme,
): List<LibraryRecord> {
    if (books.isEmpty()) return emptyList()
    val out = ArrayList<LibraryRecord>()
    val pages = PageText(db)
    loadComparisons(db, books, ids, pages, out::add)
    loadSayings(db, books, ids, pages, out::add)
    return out
}

private class PageText(val db: Connection) {
    private val cache = HashMap<Pair<String, Int>, String>()

    fun of(bookId: String, page: Int): String = cache.getOrPut(bookId to page) {
        if (!BookSchemaMigration.tableExists(db, "pages")) return@getOrPut ""
        db.prepareStatement("SELECT text FROM pages WHERE book=? AND page=?").use { sql ->
            sql.setString(1, bookId)
            sql.setInt(2, page)
            sql.executeQuery().use { rows -> if (rows.next()) rows.getString(1).orEmpty() else "" }
        }
    }

    /** 只有这一页上唯一对得上的那一句才返回区间。多处相同或对不上就返回 null。 */
    fun ground(bookId: String, page: Int, start: Int, quote: String): IntRange? {
        val sentence = quote
        if (sentence.isEmpty() || page <= 0) return null
        val text = of(bookId, page)
        if (text.isEmpty()) return null
        if (start >= 0 && start + sentence.length <= text.length && text.regionMatches(start, sentence, 0, sentence.length)) {
            return start until (start + sentence.length)
        }
        val at = text.indexOf(sentence)
        if (at < 0 || text.indexOf(sentence, at + sentence.length) >= 0) return null
        return at until (at + sentence.length)
    }
}

private fun loadComparisons(
    db: Connection,
    books: Map<String, LibraryBookHead>,
    ids: LibraryRecordIdScheme,
    pages: PageText,
    add: (LibraryRecord) -> Unit,
) {
    if (!BookSchemaMigration.tableExists(db, "literature_comparisons")) return
    db.createStatement().use { sql ->
        sql.executeQuery("SELECT digest, result, last_opened FROM literature_comparisons").use { rows ->
            while (rows.next()) {
                val digest = rows.getString(1).orEmpty().trim()
                val raw = rows.getString(2) ?: continue
                if (digest.isEmpty()) continue
                val at = rows.getLong(3).takeIf { !rows.wasNull() && it > 0L }
                val stored = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
                indexComparison(stored, digest, at, books, ids, pages, add)
            }
        }
    }
}

private fun indexComparison(
    stored: JsonObject,
    digest: String,
    at: Long?,
    books: Map<String, LibraryBookHead>,
    ids: LibraryRecordIdScheme,
    pages: PageText,
    add: (LibraryRecord) -> Unit,
) {
    val rowArray = stored["rows"] as? JsonArray ?: return
    var anchor: LibraryBookHead? = null
    rowArray.forEachIndexed { rowIndex, element ->
        val row = element as? JsonObject ?: return@forEachIndexed
        val bookId = jsonText(row, "bookId") ?: return@forEachIndexed
        val book = books[bookId] ?: return@forEachIndexed
        if (anchor == null) anchor = book
        val label = rowLabel(row, book)
        fun addCell(slot: String, index: Int, cell: JsonObject?) {
            add(
                comparisonCell(
                    ids, digest, at, book, bookId, label, rowIndex, slot, index, cell, pages, books,
                ),
            )
        }
        addCell("主张", 0, row["claim"] as? JsonObject)
        addCell("方法 / 证据", 0, row["method"] as? JsonObject)
        val concepts = row["concepts"] as? JsonArray
        if (concepts == null || concepts.isEmpty()) addCell("关键概念", 0, null)
        else concepts.forEachIndexed { index, item -> addCell("关键概念", index, item as? JsonObject) }
        val relations = row["relations"] as? JsonArray
        if (relations == null || relations.isEmpty()) addCell("关系", 0, null)
        else relations.forEachIndexed { index, item ->
            val relation = item as? JsonObject
            addCell("关系", index, relation?.get("cell") as? JsonObject)
        }
    }
    val home = anchor ?: return
    val outline = stored["outline"] as? JsonArray ?: return
    outline.forEachIndexed { pointIndex, element ->
        val point = element as? JsonObject ?: return@forEachIndexed
        val thesis = notesOneLine(jsonText(point, "thesis").orEmpty())
        if (thesis.isEmpty()) return@forEachIndexed
        var added = 0
        fun sides(key: String, role: String) {
            val array = point[key] as? JsonArray ?: return
            array.forEachIndexed { sideIndex, item ->
                val side = item as? JsonObject ?: return@forEachIndexed
                val record = outlineSide(
                    ids, digest, at, thesis, pointIndex, role, sideIndex, side, pages, books,
                ) ?: return@forEachIndexed
                add(record)
                added += 1
            }
        }
        sides("supports", "支持")
        sides("opposes", "反对")
        if (added == 0) {
            add(outlinePending(ids, digest, at, home, thesis, pointIndex))
        }
    }
}

private fun comparisonCell(
    ids: LibraryRecordIdScheme,
    digest: String,
    at: Long?,
    rowBook: LibraryBookHead,
    rowBookId: String,
    label: String,
    rowIndex: Int,
    slot: String,
    index: Int,
    cell: JsonObject?,
    pages: PageText,
    books: Map<String, LibraryBookHead>,
): LibraryRecord {
    val written = notesOneLine(jsonText(cell, "text").orEmpty()).ifBlank { COMPARISON_PENDING }
    val storedPending = (cell?.get("pending") as? JsonPrimitive)?.booleanOrNull == true
    val source = sourceOf(cell?.get("source") as? JsonObject)
    val grounded = if (storedPending || source == null) null else {
        pages.ground(source.bookId, source.page, source.start, source.quote)?.let { range -> source to range }
    }
    val owner = grounded?.let { books[it.first.bookId] } ?: rowBook
    val quote = if (grounded == null) "" else grounded.first.quote
    val page = grounded?.first?.page
    val start = grounded?.second?.first
    val end = grounded?.second?.endExclusive()
    val cite = if (grounded == null) COMPARISON_PENDING else "PDF 第 $page 页"
    val detail = bullet(
        LibraryStudyFields.SLOT to slot,
        LibraryStudyFields.GROUP to digest,
        LibraryStudyFields.ROW to rowIndex.toString(),
        LibraryStudyFields.PAPER to label,
        LibraryStudyFields.CITE to cite,
        "选中的原句" to quote,
    )
    val raw = listOf(digest, rowBookId, slot, index.toString()).joinToString(LibraryStudyFields.SEPARATOR.toString())
    return record(ids, LibraryKind.COMPARISON, raw, owner, page, start, end, written, notesOneLine(quote), detail, at)
}

private fun outlineSide(
    ids: LibraryRecordIdScheme,
    digest: String,
    at: Long?,
    thesis: String,
    pointIndex: Int,
    role: String,
    sideIndex: Int,
    side: JsonObject,
    pages: PageText,
    books: Map<String, LibraryBookHead>,
): LibraryRecord? {
    val bookId = jsonText(side, "bookId") ?: return null
    val book = books[bookId] ?: return null
    val label = jsonText(side, "label").orEmpty().ifBlank { book.title }
    val storedPending = (side["pending"] as? JsonPrimitive)?.booleanOrNull == true
    val source = sourceOf(side["source"] as? JsonObject)
    val grounded = if (storedPending || source == null) null else {
        pages.ground(source.bookId, source.page, source.start, source.quote)?.let { range -> source to range }
    }
    val owner = grounded?.let { books[it.first.bookId] } ?: book
    val quote = if (grounded == null) "" else grounded.first.quote
    val page = grounded?.first?.page
    val start = grounded?.second?.first
    val end = grounded?.second?.endExclusive()
    val cite = if (grounded == null) COMPARISON_PENDING else "PDF 第 $page 页"
    val detail = bullet(
        LibraryStudyFields.GROUP to digest,
        LibraryStudyFields.POINT to pointIndex.toString(),
        LibraryStudyFields.ROLE to role,
        LibraryStudyFields.PAPER to label,
        LibraryStudyFields.CITE to cite,
        "选中的原句" to quote,
    )
    val raw = listOf(digest, pointIndex.toString(), role, sideIndex.toString(), bookId).joinToString(LibraryStudyFields.SEPARATOR.toString())
    return record(ids, LibraryKind.OUTLINE, raw, owner, page, start, end, thesis, notesOneLine(quote), detail, at)
}

private fun outlinePending(
    ids: LibraryRecordIdScheme,
    digest: String,
    at: Long?,
    home: LibraryBookHead,
    thesis: String,
    pointIndex: Int,
): LibraryRecord {
    val detail = bullet(
        LibraryStudyFields.GROUP to digest,
        LibraryStudyFields.POINT to pointIndex.toString(),
        LibraryStudyFields.ROLE to "论点",
        LibraryStudyFields.PAPER to home.title,
        LibraryStudyFields.CITE to COMPARISON_PENDING,
    )
    val raw = listOf(digest, pointIndex.toString(), "论点", "0", home.id).joinToString(LibraryStudyFields.SEPARATOR.toString())
    return record(ids, LibraryKind.OUTLINE, raw, home, null, null, null, thesis, "", detail, at)
}

private fun loadSayings(
    db: Connection,
    books: Map<String, LibraryBookHead>,
    ids: LibraryRecordIdScheme,
    pages: PageText,
    add: (LibraryRecord) -> Unit,
) {
    if (!BookSchemaMigration.tableExists(db, "concept_entities")) return
    if (!BookSchemaMigration.tableExists(db, "concept_mentions")) return
    val names = HashMap<String, String>()
    db.prepareStatement(
        "SELECT concept_id, canonical_name FROM concept_entities WHERE muted=0",
    ).use { sql ->
        sql.executeQuery().use { rows ->
            while (rows.next()) {
                val name = rows.getString(2).orEmpty().trim()
                if (name.isEmpty()) continue
                names[rows.getString(1)] = name
            }
        }
    }
    if (names.isEmpty()) return
    val covered = HashSet<String>()
    if (BookSchemaMigration.tableExists(db, "concept_sayings")) {
        val cols = BookSchemaMigration.tableColumns(db, "concept_sayings")
        if ("statement" in cols && "quote_anchor" in cols && "kept" in cols) {
            db.prepareStatement(
                """SELECT concept_id, book_id, statement, stance, page, offset_utf16, quote_anchor, updated_at
                   FROM concept_sayings WHERE kept=1""",
            ).use { sql ->
                sql.executeQuery().use { rows ->
                    while (rows.next()) {
                        val conceptId = rows.getString(1)
                        val name = names[conceptId] ?: continue
                        val book = books[rows.getString(2)] ?: continue
                        covered += conceptId
                        val at = rows.getLong(8).takeIf { !rows.wasNull() && it > 0L }
                        add(
                            sayingRecord(
                                ids = ids,
                                conceptId = conceptId,
                                name = name,
                                book = book,
                                statement = rows.getString(3).orEmpty(),
                                stance = SayingStance.fromWire(rows.getString(4)).label,
                                page = rows.getInt(5),
                                start = rows.getInt(6),
                                quote = rows.getString(7).orEmpty(),
                                at = at,
                                pages = pages,
                            ),
                        )
                    }
                }
            }
        }
    }
    data class Mention(
        val conceptId: String,
        val bookId: String,
        val page: Int,
        val start: Int,
        val quote: String,
        val role: String,
        val claim: String,
        val mentionId: String,
    )
    val mentions = ArrayList<Mention>()
    db.prepareStatement(
        """SELECT m.concept_id, m.book_id, m.page, m.offset_utf16, m.quote_anchor, m.mention_role, m.claim_summary, m.mention_id
           FROM concept_mentions m
           INNER JOIN concept_entities e ON e.concept_id = m.concept_id
           WHERE e.muted = 0""",
    ).use { sql ->
        sql.executeQuery().use { rows ->
            while (rows.next()) {
                mentions += Mention(
                    conceptId = rows.getString(1),
                    bookId = rows.getString(2),
                    page = rows.getInt(3),
                    start = rows.getInt(4),
                    quote = rows.getString(5).orEmpty(),
                    role = rows.getString(6).orEmpty(),
                    claim = rows.getString(7).orEmpty(),
                    mentionId = rows.getString(8).orEmpty(),
                )
            }
        }
    }
    val pendingConcepts = mentions.groupBy { it.conceptId }.filterKeys { it !in covered }
    for ((conceptId, rows) in pendingConcepts) {
        val name = names[conceptId] ?: continue
        val byBook = rows.filter { books[it.bookId] != null }.groupBy { it.bookId }
        if (byBook.size < 2) continue
        for ((bookId, group) in byBook) {
            val book = books.getValue(bookId)
            val picked = group.minWith(
                compareBy<Mention>({ if (it.role.equals("PRIMARY_DEF", true)) 0 else 1 }, { it.page }, { it.mentionId }),
            )
            add(
                sayingRecord(
                    ids = ids,
                    conceptId = conceptId,
                    name = name,
                    book = book,
                    statement = picked.claim,
                    stance = "尚未对照",
                    page = picked.page,
                    start = picked.start,
                    quote = picked.quote,
                    at = null,
                    pages = pages,
                ),
            )
        }
    }
}

private fun sayingRecord(
    ids: LibraryRecordIdScheme,
    conceptId: String,
    name: String,
    book: LibraryBookHead,
    statement: String,
    stance: String,
    page: Int,
    start: Int,
    quote: String,
    at: Long?,
    pages: PageText,
): LibraryRecord {
    val range = pages.ground(book.id, page, start, quote)
    val groundedQuote = if (range == null) "" else pages.of(book.id, page).substring(range.first, range.endExclusive())
    val cite = if (range == null) COMPARISON_PENDING else "PDF 第 $page 页"
    val primary = notesOneLine(statement).ifBlank { notesOneLine(groundedQuote) }.ifBlank { name }
    val detail = bullet(
        LibraryStudyFields.CONCEPT to name,
        LibraryStudyFields.CONCEPT_ID to conceptId,
        LibraryStudyFields.STANCE to stance,
        LibraryStudyFields.CITE to cite,
        "选中的原句" to groundedQuote,
    )
    val raw = conceptId + LibraryStudyFields.SEPARATOR + book.id
    return record(
        ids, LibraryKind.SAYING, raw, book,
        range?.let { page }, range?.first, range?.endExclusive(),
        primary, notesOneLine(groundedQuote), detail, at,
    )
}

private fun sourceOf(obj: JsonObject?): SourceBits? {
    if (obj == null) return null
    val bookId = jsonText(obj, "bookId") ?: return null
    val page = (obj["pdfPage"] as? JsonPrimitive)?.intOrNull ?: return null
    val start = (obj["startUtf16"] as? JsonPrimitive)?.intOrNull ?: 0
    val quote = (obj["quote"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (page <= 0 || quote.isEmpty()) return null
    return SourceBits(bookId, page, start, quote)
}

private data class SourceBits(val bookId: String, val page: Int, val start: Int, val quote: String)

private fun rowLabel(row: JsonObject, book: LibraryBookHead): String {
    val author = jsonText(row, "author").orEmpty()
    val year = jsonText(row, "year").orEmpty()
    val title = jsonText(row, "title").orEmpty().ifBlank { book.title }
    return when {
        author.isNotBlank() && year.isNotBlank() -> "$author ($year)"
        author.isNotBlank() -> author
        else -> title
    }
}

private fun jsonText(obj: JsonObject?, key: String): String? {
    if (obj == null) return null
    return (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
}

private fun IntRange.endExclusive(): Int = last + 1
