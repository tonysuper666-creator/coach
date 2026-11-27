package com.coach.desktop.books

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal const val COMPARISON_PENDING = "出处待查"
internal const val COMPARISON_MIN_PAPERS = 2
internal const val COMPARISON_MAX_PAPERS = 8

internal fun comparisonRefusal(count: Int) =
    "这一组只有 $count 篇文献，至少两篇才能对比，所以没有出表。"

internal fun comparisonTooMany(count: Int) =
    "这一组有 $count 篇文献，一次最多放 $COMPARISON_MAX_PAPERS 篇。请先去掉几篇，再出表。"

internal enum class ComparisonRelationKind { SUPPORT, REFUTE, SUPPLEMENT }

internal fun ComparisonRelationKind.zh(): String = when (this) {
    ComparisonRelationKind.SUPPORT -> "支持"
    ComparisonRelationKind.REFUTE -> "反驳"
    ComparisonRelationKind.SUPPLEMENT -> "补充"
}

internal data class ComparisonCell(
    val text: String,
    val source: GuideExcerpt?,
    val pending: Boolean,
) {
    fun edited(text: String): ComparisonCell = copy(text = text.trim().ifBlank { COMPARISON_PENDING })

    fun markdownValue(): String = when {
        !pending -> text
        text.isBlank() || text == COMPARISON_PENDING -> COMPARISON_PENDING
        else -> "$text（$COMPARISON_PENDING）"
    }
}

internal data class ComparisonRelation(
    val otherBookId: String,
    val otherLabel: String,
    val kind: ComparisonRelationKind?,
    val cell: ComparisonCell,
)

internal data class ComparisonRow(
    val bookId: String,
    val title: String,
    val author: String,
    val year: String,
    val claim: ComparisonCell,
    val method: ComparisonCell,
    val concepts: List<ComparisonCell>,
    val relations: List<ComparisonRelation>,
) {
    fun label(): String = when {
        author.isNotBlank() && year.isNotBlank() -> "$author ($year)"
        author.isNotBlank() -> author
        else -> title
    }
}

internal data class OutlineSide(
    val bookId: String,
    val label: String,
    val source: GuideExcerpt?,
    val pending: Boolean,
)

internal data class OutlinePoint(
    val thesis: String,
    val supports: List<OutlineSide>,
    val opposes: List<OutlineSide>,
)

internal data class LiteratureComparison(
    val rows: List<ComparisonRow>,
    val outline: List<OutlinePoint>,
) {
    fun audit(): ComparisonAudit {
        var verified = 0
        var pending = 0
        fun countCell(cell: ComparisonCell) {
            if (cell.pending || cell.source == null) pending++ else verified++
        }
        rows.forEach { row ->
            countCell(row.claim)
            countCell(row.method)
            if (row.concepts.isEmpty()) pending++ else row.concepts.forEach(::countCell)
            if (row.relations.isEmpty()) pending++ else row.relations.forEach { countCell(it.cell) }
        }
        outline.forEach { point ->
            (point.supports + point.opposes).forEach { side ->
                if (side.pending || side.source == null) pending++ else verified++
            }
        }
        return ComparisonAudit(rows.size * 4, verified, pending, outline.size)
    }

    fun editClaim(bookId: String, text: String) = copy(rows = rows.map {
        if (it.bookId == bookId) it.copy(claim = it.claim.edited(text)) else it
    })

    fun editMethod(bookId: String, text: String) = copy(rows = rows.map {
        if (it.bookId == bookId) it.copy(method = it.method.edited(text)) else it
    })

    fun editConcept(bookId: String, index: Int, text: String) = copy(rows = rows.map { row ->
        if (row.bookId != bookId || index !in row.concepts.indices) row
        else row.copy(concepts = row.concepts.mapIndexed { i, cell -> if (i == index) cell.edited(text) else cell })
    })

    fun editRelation(bookId: String, index: Int, text: String) = copy(rows = rows.map { row ->
        if (row.bookId != bookId || index !in row.relations.indices) row
        else row.copy(relations = row.relations.mapIndexed { i, relation ->
            if (i == index) relation.copy(cell = relation.cell.edited(text)) else relation
        })
    })

    fun editThesis(index: Int, text: String) = copy(outline = outline.mapIndexed { i, point ->
        if (i == index) point.copy(thesis = text.trim().ifBlank { point.thesis }) else point
    })

    fun toMarkdown(): String = buildString {
        append("# 文献对比\n\n")
        append("| 文献 | 主张 | 方法 / 证据 | 关键概念 | 与其他几篇的关系 |\n")
        append("| --- | --- | --- | --- | --- |\n")
        rows.forEach { row ->
            val concepts = if (row.concepts.isEmpty()) COMPARISON_PENDING
            else row.concepts.joinToString("；") { it.markdownValue() }
            val relations = if (row.relations.isEmpty()) COMPARISON_PENDING
            else row.relations.joinToString("；") { relation ->
                val kind = relation.kind?.zh()?.let { "$it " }.orEmpty()
                "$kind${relation.otherLabel}：${relation.cell.markdownValue()}"
            }
            append("| ${md(row.label())} | ${md(row.claim.markdownValue())} | ${md(row.method.markdownValue())} | ${md(concepts)} | ${md(relations)} |\n")
        }
        append("\n## 出处\n\n")
        rows.forEach { row ->
            append("### ${row.label()}\n\n")
            append("- 主张：${citeMarkdown(row.claim)}\n")
            append("- 方法 / 证据：${citeMarkdown(row.method)}\n")
            if (row.concepts.isEmpty()) append("- 关键概念：$COMPARISON_PENDING\n")
            row.concepts.forEach { append("- 关键概念：${citeMarkdown(it)}\n") }
            if (row.relations.isEmpty()) append("- 与其他几篇的关系：$COMPARISON_PENDING\n")
            row.relations.forEach { relation ->
                val kind = relation.kind?.zh()?.let { "$it " }.orEmpty()
                append("- 与 ${relation.otherLabel}：$kind${citeMarkdown(relation.cell)}\n")
            }
            append("\n")
        }
        append("# 综述提纲\n\n")
        if (outline.isEmpty()) {
            append("没有可用论点，没有硬补。\n")
        }
        outline.forEachIndexed { index, point ->
            append("## ${index + 1}. ${point.thesis}\n\n")
            if (point.supports.isEmpty()) append("- 支持：没有列出\n")
            point.supports.forEach { append("- 支持：${sideMarkdown(it)}\n") }
            if (point.opposes.isEmpty()) append("- 反对：没有列出\n")
            point.opposes.forEach { append("- 反对：${sideMarkdown(it)}\n") }
            append("\n")
        }
    }
}

internal data class ComparisonAudit(
    val tableCells: Int,
    val verifiedCites: Int,
    val pendingCites: Int,
    val outlinePoints: Int,
) {
    val passRate: Double
        get() {
            val total = verifiedCites + pendingCites
            return if (total == 0) 0.0 else verifiedCites.toDouble() / total
        }
}

internal data class ComparisonOutcome(
    val table: LiteratureComparison?,
    val notice: String,
    val audit: ComparisonAudit,
)

internal class LiteratureComparisonInput(val books: List<Textbook>) {
    init {
        require(books.size in COMPARISON_MIN_PAPERS..COMPARISON_MAX_PAPERS && books.distinctBy { it.id }.size == books.size) {
            if (books.size < COMPARISON_MIN_PAPERS) comparisonRefusal(books.size) else comparisonTooMany(books.size)
        }
        require(books.none { it.hasPendingOcr }) { "扫描页正在识别，完成后再生成对比表。" }
    }

    val paperMetas: Map<String, LiteraturePaperMeta> = books.associate { book ->
        val base = book.literatureMeta ?: LiteraturePaperMeta(book.id, LiteratureTextNorm.decodeHtmlEntities(book.title))
        book.id to base.copy(
            title = LiteratureTextNorm.decodeHtmlEntities(base.title),
            author = LiteratureTextNorm.decodeHtmlEntities(base.author),
        )
    }

    val excerpts: List<GuideExcerpt> = selectExcerpts()

    fun request(): JsonObject = requestFor(excerpts)

    private fun requestFor(blocks: List<GuideExcerpt>): JsonObject = buildJsonObject {
        put("kind", "literatureComparison")
        put("papers", buildJsonArray {
            books.forEach { book ->
                val meta = paperMetas.getValue(book.id)
                add(buildJsonObject {
                    put("bookId", meta.bookId)
                    put("title", meta.title)
                    put("author", meta.author)
                    put("year", meta.year)
                })
            }
        })
        put("blocks", buildJsonArray {
            blocks.forEachIndexed { ref, excerpt ->
                val meta = paperMetas.getValue(excerpt.book.id)
                add(buildJsonObject {
                    put("ref", ref)
                    put("bookId", excerpt.book.id)
                    if (meta.author.isNotBlank()) put("author", meta.author) else put("title", meta.title.take(60))
                    if (meta.year.isNotBlank()) put("year", meta.year)
                    put("page", excerpt.anchor.pdfPage)
                    put("text", excerpt.anchor.quoteAnchor)
                })
            }
        })
    }

    fun verify(body: JsonObject, ocr: LiteratureOcrVerifier): ComparisonOutcome {
        val cached = (ocr as? CachingOcrVerifier) ?: CachingOcrVerifier(ocr)
        val invalid = (body["invalidComparison"] as? JsonPrimitive)?.booleanOrNull == true
        val rawRows = body["rows"] as? JsonArray
        if (invalid || rawRows == null) {
            return ComparisonOutcome(null, "模型没有返回对比表，没有硬填。", ComparisonAudit(0, 0, 0, 0))
        }
        val byBook = LinkedHashMap<String, JsonObject>()
        rawRows.forEach { element ->
            val row = element as? JsonObject ?: return@forEach
            val id = (row["bookId"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@forEach
            if (id in paperMetas && id !in byBook) byBook[id] = row
        }
        val cache = HashMap<Pair<Int, String>, Pair<GuideExcerpt?, String>>()
        fun cite(ref: Int?, bookId: String): GuideExcerpt? {
            if (ref == null) return null
            val (source, rule) = cache.getOrPut(ref to bookId) {
                LiteratureCitationCheck.check(excerpts, ref, bookId, cached)
            }
            return if (rule == "ok") source else null
        }
        val rows = reconcileRelations(books.map { book ->
            val meta = paperMetas.getValue(book.id)
            val raw = byBook[book.id]
            ComparisonRow(
                bookId = book.id,
                title = meta.title,
                author = meta.author,
                year = meta.year,
                claim = cellFrom(raw?.get("claim") as? JsonObject, book.id, ::cite),
                method = cellFrom(raw?.get("method") as? JsonObject, book.id, ::cite),
                concepts = ((raw?.get("concepts") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty())
                    .map { cellFrom(it, book.id, ::cite) },
                relations = relationsFrom(raw?.get("relations") as? JsonArray, book.id, ::cite),
            )
        })
        val (outline, outlineNotice) = outlineFrom(body["outline"] as? JsonArray, ::cite)
        val table = LiteratureComparison(rows, outline)
        val audit = table.audit()
        val notice = buildString {
            if (outlineNotice.isNotBlank()) append(outlineNotice)
            if (audit.pendingCites > 0) {
                if (isNotEmpty()) append(" ")
                append("有 ${audit.pendingCites} 处标成出处待查。")
            }
        }
        return ComparisonOutcome(table, notice, audit)
    }

    private fun selectExcerpts(): List<GuideExcerpt> {
        val ranked = books.associate { book ->
            book.id to LiteraturePassages.rankForPaper(
                candidates = LiteraturePassages.extractCandidates(book),
                meta = paperMetas.getValue(book.id),
                questions = emptyList(),
            )
        }
        val chosen = books.associate { it.id to mutableListOf<GuideExcerpt>() }
        fun packed(): List<GuideExcerpt> = books.flatMap { book ->
            chosen.getValue(book.id).sortedWith(compareBy({ it.anchor.pdfPage }, { it.anchor.startUtf16 }))
        }
        fun fits(next: List<GuideExcerpt>): Boolean = requestFor(next).toString().toByteArray().size <= REQUEST_BYTE_BUDGET
        for (book in books) {
            for (item in ranked.getValue(book.id).take(MIN_SENTENCES)) {
                val trial = packed() + item
                if (!fits(trial)) break
                chosen.getValue(book.id) += item
            }
        }
        var progressed = true
        while (progressed) {
            progressed = false
            for (book in books) {
                val have = chosen.getValue(book.id)
                if (have.size >= MAX_SENTENCES) continue
                val next = ranked.getValue(book.id).firstOrNull { candidate ->
                    have.none { it.anchor.pdfPage == candidate.anchor.pdfPage && it.anchor.startUtf16 == candidate.anchor.startUtf16 }
                } ?: continue
                if (!fits(packed() + next)) continue
                have += next
                progressed = true
            }
        }
        val selected = packed()
        require(selected.isNotEmpty()) { "这些 PDF 里没有可用的正文句子，所以没有出表。" }
        return selected
    }

    /** Support against refute (either direction) is not shown as two verified cells. */
    private fun reconcileRelations(rows: List<ComparisonRow>): List<ComparisonRow> {
        val kindAt = HashMap<Pair<String, String>, ComparisonRelationKind>()
        rows.forEach { row ->
            row.relations.forEach { rel ->
                val kind = rel.kind ?: return@forEach
                if (rel.cell.pending) return@forEach
                kindAt[row.bookId to rel.otherBookId] = kind
            }
        }
        fun conflicts(from: String, to: String): Boolean {
            val forward = kindAt[from to to] ?: return false
            val back = kindAt[to to from] ?: return false
            return forward != back &&
                (forward == ComparisonRelationKind.REFUTE || back == ComparisonRelationKind.REFUTE)
        }
        return rows.map { row ->
            row.copy(relations = row.relations.map { rel ->
                if (rel.kind != null && !rel.cell.pending && conflicts(row.bookId, rel.otherBookId)) {
                    rel.copy(kind = null, cell = pendingCell())
                } else {
                    rel
                }
            })
        }
    }

    private fun cellFrom(
        raw: JsonObject?,
        bookId: String,
        cite: (Int?, String) -> GuideExcerpt?,
    ): ComparisonCell {
        val text = (raw?.get("text") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val ref = (raw?.get("ref") as? JsonPrimitive)?.intOrNull
        if (!acceptableProse(text, 80)) return pendingCell()
        val source = cite(ref, bookId) ?: return pendingCell()
        if (comparisonCopiesQuote(text, source.anchor.quoteAnchor)) return pendingCell()
        return ComparisonCell(text, source, pending = false)
    }

    private fun relationsFrom(
        raw: JsonArray?,
        bookId: String,
        cite: (Int?, String) -> GuideExcerpt?,
    ): List<ComparisonRelation> {
        if (raw == null) return emptyList()
        val out = mutableListOf<ComparisonRelation>()
        raw.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val otherId = (obj["otherBookId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (otherId.isBlank() || otherId == bookId || otherId !in paperMetas) return@forEach
            val kind = kindOf((obj["kind"] as? JsonPrimitive)?.contentOrNull)
            val cell = cellFrom(obj, bookId, cite)
            val grounded = if (kind == null || cell.pending) pendingCell() else cell
            out += ComparisonRelation(otherId, labelOf(otherId), kind.takeIf { !grounded.pending }, grounded)
        }
        return out
    }

    private fun outlineFrom(
        raw: JsonArray?,
        cite: (Int?, String) -> GuideExcerpt?,
    ): Pair<List<OutlinePoint>, String> {
        if (raw == null) return emptyList<OutlinePoint>() to "综述提纲没有可用论点，没有硬补。"
        val points = mutableListOf<OutlinePoint>()
        var seen = 0
        raw.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            seen++
            if (points.size >= 6) return@forEach
            val thesis = (obj["thesis"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (!acceptableProse(thesis, 120)) return@forEach
            points += OutlinePoint(
                thesis = thesis,
                supports = sidesFrom(obj["supports"] as? JsonArray, cite),
                opposes = sidesFrom(obj["opposes"] as? JsonArray, cite),
            )
        }
        val notice = when {
            points.isEmpty() -> "综述提纲没有可用论点，没有硬补。"
            points.size < 3 -> "综述提纲只有 ${points.size} 个论点，没有补成 3 个。"
            seen > 6 -> "综述提纲超过 6 个论点，只保留前 6 个。"
            else -> ""
        }
        return points to notice
    }

    private fun sidesFrom(raw: JsonArray?, cite: (Int?, String) -> GuideExcerpt?): List<OutlineSide> {
        if (raw == null) return emptyList()
        val out = mutableListOf<OutlineSide>()
        raw.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val bookId = (obj["bookId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (bookId !in paperMetas) return@forEach
            val ref = (obj["ref"] as? JsonPrimitive)?.intOrNull
            val source = cite(ref, bookId)
            out += OutlineSide(bookId, labelOf(bookId), source, pending = source == null)
        }
        return out
    }

    private fun labelOf(bookId: String): String {
        val meta = paperMetas.getValue(bookId)
        return when {
            meta.author.isNotBlank() && meta.year.isNotBlank() -> "${meta.author} (${meta.year})"
            meta.author.isNotBlank() -> meta.author
            else -> meta.title
        }
    }

    companion object {
        const val REQUEST_BYTE_BUDGET = 79_000
        private const val MIN_SENTENCES = 4
        private const val MAX_SENTENCES = 12
        private const val STORED_VERSION = 1

        fun toStoredJson(table: LiteratureComparison): JsonObject = buildJsonObject {
            put("version", STORED_VERSION)
            put("rows", buildJsonArray {
                table.rows.forEach { row ->
                    add(buildJsonObject {
                        put("bookId", row.bookId)
                        put("title", row.title)
                        put("author", row.author)
                        put("year", row.year)
                        put("claim", cellToJson(row.claim))
                        put("method", cellToJson(row.method))
                        put("concepts", buildJsonArray { row.concepts.forEach { add(cellToJson(it)) } })
                        put("relations", buildJsonArray {
                            row.relations.forEach { relation ->
                                add(buildJsonObject {
                                    put("otherBookId", relation.otherBookId)
                                    put("otherLabel", relation.otherLabel)
                                    relation.kind?.let { put("kind", it.name.lowercase()) }
                                    put("cell", cellToJson(relation.cell))
                                })
                            }
                        })
                    })
                }
            })
            put("outline", buildJsonArray {
                table.outline.forEach { point ->
                    add(buildJsonObject {
                        put("thesis", point.thesis)
                        put("supports", buildJsonArray { point.supports.forEach { add(sideToJson(it)) } })
                        put("opposes", buildJsonArray { point.opposes.forEach { add(sideToJson(it)) } })
                    })
                }
            })
        }

        fun restore(stored: JsonObject, books: List<Textbook>): LiteratureComparison? {
            if ((stored["version"] as? JsonPrimitive)?.intOrNull != STORED_VERSION) return null
            val byId = books.associateBy { it.id }
            fun resolve(obj: JsonObject?): GuideExcerpt? {
                if (obj == null) return null
                val book = byId[(obj["bookId"] as? JsonPrimitive)?.contentOrNull ?: return null] ?: return null
                val pageNo = (obj["pdfPage"] as? JsonPrimitive)?.intOrNull ?: return null
                val start = (obj["startUtf16"] as? JsonPrimitive)?.intOrNull ?: return null
                val end = (obj["endUtf16"] as? JsonPrimitive)?.intOrNull ?: return null
                val quote = (obj["quote"] as? JsonPrimitive)?.contentOrNull ?: return null
                val page = book.page(pageNo) ?: return null
                if (start < 0 || end > page.text.length || page.text.substring(start, end) != quote) return null
                val region = page.guideRegions.firstOrNull { it.containsSentence(quote) } ?: GuideRegion(page.text)
                return GuideExcerpt(book, BookCompanionAnchor(pageNo, "${book.id}#p$pageNo-$start", start, end, quote), region)
            }
            fun cell(obj: JsonObject?): ComparisonCell? {
                if (obj == null) return null
                val text = (obj["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().ifBlank { COMPARISON_PENDING }
                val storedPending = (obj["pending"] as? JsonPrimitive)?.booleanOrNull == true
                val source = resolve(obj["source"] as? JsonObject)
                val pending = storedPending || source == null
                return ComparisonCell(text, source.takeUnless { pending }, pending)
            }
            val rowArray = stored["rows"] as? JsonArray ?: return null
            val rows = rowArray.map { element ->
                val obj = element as? JsonObject ?: return null
                val bookId = (obj["bookId"] as? JsonPrimitive)?.contentOrNull ?: return null
                if (bookId !in byId) return null
                ComparisonRow(
                    bookId = bookId,
                    title = (obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    author = (obj["author"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    year = (obj["year"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    claim = cell(obj["claim"] as? JsonObject) ?: return null,
                    method = cell(obj["method"] as? JsonObject) ?: return null,
                    concepts = (obj["concepts"] as? JsonArray)?.map { cell(it as? JsonObject) ?: return null }.orEmpty(),
                    relations = (obj["relations"] as? JsonArray)?.map { rel ->
                        val relation = rel as? JsonObject ?: return null
                        val other = (relation["otherBookId"] as? JsonPrimitive)?.contentOrNull ?: return null
                        ComparisonRelation(
                            otherBookId = other,
                            otherLabel = (relation["otherLabel"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                            kind = kindOf((relation["kind"] as? JsonPrimitive)?.contentOrNull),
                            cell = cell(relation["cell"] as? JsonObject) ?: return null,
                        )
                    }.orEmpty(),
                )
            }
            if (rows.map { it.bookId }.toSet() != byId.keys) return null
            val outline = (stored["outline"] as? JsonArray)?.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                fun sides(key: String): List<OutlineSide>? {
                    val array = obj[key] as? JsonArray ?: return emptyList()
                    return array.map { sideElement ->
                        val side = sideElement as? JsonObject ?: return null
                        val bookId = (side["bookId"] as? JsonPrimitive)?.contentOrNull ?: return null
                        val source = resolve(side["source"] as? JsonObject)
                        val pending = (side["pending"] as? JsonPrimitive)?.booleanOrNull == true || source == null
                        OutlineSide(
                            bookId = bookId,
                            label = (side["label"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                            source = source.takeUnless { pending },
                            pending = pending,
                        )
                    }
                }
                val supports = sides("supports") ?: return@mapNotNull null
                val opposes = sides("opposes") ?: return@mapNotNull null
                OutlinePoint(
                    thesis = (obj["thesis"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
                    supports = supports,
                    opposes = opposes,
                )
            } ?: return null
            return LiteratureComparison(rows, outline)
        }

        private fun cellToJson(cell: ComparisonCell): JsonObject = buildJsonObject {
            put("text", cell.text)
            put("pending", cell.pending)
            cell.source?.let { put("source", sourceToJson(it)) }
        }

        private fun sideToJson(side: OutlineSide): JsonObject = buildJsonObject {
            put("bookId", side.bookId)
            put("label", side.label)
            put("pending", side.pending)
            side.source?.let { put("source", sourceToJson(it)) }
        }

        private fun sourceToJson(excerpt: GuideExcerpt): JsonObject = buildJsonObject {
            put("bookId", excerpt.book.id)
            put("pdfPage", excerpt.anchor.pdfPage)
            put("startUtf16", excerpt.anchor.startUtf16)
            put("endUtf16", excerpt.anchor.endUtf16)
            put("quote", excerpt.anchor.quoteAnchor)
        }
    }
}

private fun pendingCell() = ComparisonCell(COMPARISON_PENDING, null, pending = true)

private fun acceptableProse(text: String, limit: Int): Boolean =
    text.isNotBlank() && text.length <= limit && LiteraturePassages.substantive(text) && !LiteraturePassages.hasLeakedRef(text)

private fun kindOf(raw: String?): ComparisonRelationKind? = when (raw?.lowercase()) {
    "support" -> ComparisonRelationKind.SUPPORT
    "refute" -> ComparisonRelationKind.REFUTE
    "supplement" -> ComparisonRelationKind.SUPPLEMENT
    else -> null
}

internal fun comparisonCopiesQuote(text: String, quote: String): Boolean {
    val left = LiteratureTextNorm.normalize(text)
    val right = LiteratureTextNorm.normalize(quote)
    if (right.length < 8 || left.isEmpty()) return false
    if (right.length <= 24) return right in left
    val window = 24
    for (index in 0..right.length - window) {
        if (right.substring(index, index + window) in left) return true
    }
    return false
}

private fun citeMarkdown(cell: ComparisonCell): String {
    val source = cell.source
    return if (cell.pending || source == null) cell.markdownValue()
    else "${cell.text}（PDF 第 ${source.anchor.pdfPage} 页「${source.anchor.quoteAnchor}」）"
}

private fun sideMarkdown(side: OutlineSide): String {
    val source = side.source
    return if (side.pending || source == null) "${side.label}（$COMPARISON_PENDING）"
    else "${side.label}（PDF 第 ${source.anchor.pdfPage} 页「${source.anchor.quoteAnchor}」）"
}

private fun md(text: String) = text.replace("|", "\\|").replace("\n", " ")
