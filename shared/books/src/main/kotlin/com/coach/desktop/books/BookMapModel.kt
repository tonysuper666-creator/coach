package com.coach.desktop.books

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 2.1「这本书讲什么」全书地图数据模型、骨架对齐、原句锚点解析与编解码（TON-1889）。
 *
 * 设计约束：
 * - 每章 3–7 个要点（极短章按可用原句数降级），每个要点一句人话 + 至少 1 处可高亮原句；
 * - 地图建在书的骨架（章数、正文起始页、章节拆并改名）上，改骨架后按原句页码/偏移自动重排；
 * - 原句点击必须产出 `page.text.substring(startUtf16, endUtf16) == quoteAnchor`，保证阅读器直接跳页并高亮；
 * - 生成一次存本机，再打开零额度；原文只在生成那一步发送。
 */
internal data class BookMapAnchor(
    val pdfPage: Int,
    val startUtf16: Int,
    val endUtf16: Int,
    val quote: String,
    val blockId: String = "",
)

internal data class BookMapPoint(
    val id: String,
    val summary: String,
    val anchors: List<BookMapAnchor>,
) {
    val primaryAnchor: BookMapAnchor? get() = anchors.firstOrNull()
}

internal data class BookMapChapter(
    val chapterId: String,
    val chapterNum: Int,
    val title: String,
    val firstPage: Int,
    val lastPage: Int,
    val oneLiner: String = "",
    val points: List<BookMapPoint> = emptyList(),
)

internal data class BookMapDocument(
    val version: Int = CURRENT_VERSION,
    val bookId: String,
    val bookTitle: String,
    val skeletonHash: String,
    val bodyStartPage: Int,
    val chapterCount: Int,
    val chapters: List<BookMapChapter>,
    val generatedAt: Long,
    val model: String = "claude-haiku-5-5",
    val staleChapterIds: Set<String> = emptySet(),
    val reconciledFromSkeleton: Boolean = false,
) {
    val totalPoints: Int get() = chapters.sumOf { it.points.size }

    companion object {
        const val CURRENT_VERSION: Int = 2
        const val MIN_POINTS_PER_CHAPTER: Int = 3
        const val MAX_POINTS_PER_CHAPTER: Int = 7
        /** Single-chapter books (e.g. lecture slide decks or un-chaptered books) allow up to 10 points so 10-point sampling is always supported. */
        const val MAX_POINTS_SINGLE_CHAPTER_BOOK: Int = 10
    }
}

internal data class BookMapSentenceRef(
    val ref: Int,
    val chapterId: String,
    val pdfPage: Int,
    val startUtf16: Int,
    val endUtf16: Int,
    val text: String,
)

internal data class BookMapPendingJob(
    val bookId: String,
    val skeletonHash: String,
    val jobKey: String,
    val revision: Int,
    val updatedAt: Long,
)

internal enum class BookMapFilter(val labelZh: String, val tag: String) {
    ALL("全部", "book-map-filter-all"),
    UNREAD("没读", "book-map-filter-unread"),
    READ("已读", "book-map-filter-read"),
}

internal sealed interface BookMapUiState {
    data object Idle : BookMapUiState

    data class Generating(
        val message: String,
        val pending: Boolean = false,
        val completedChunks: Int = 0,
        val totalChunks: Int = 0,
    ) : BookMapUiState

    data class Ready(
        val document: BookMapDocument,
    ) : BookMapUiState

    data class Failed(
        val message: String,
        val offline: Boolean = false,
        val retryable: Boolean = true,
        val existing: BookMapDocument? = null,
    ) : BookMapUiState
}

internal sealed interface BookMapBatchParseResult {
    data class Pending(
        val retryAfterMs: Long,
        val jobKey: String,
        val completedChunks: Int,
        val totalChunks: Int,
    ) : BookMapBatchParseResult

    data class Ready(val document: BookMapDocument) : BookMapBatchParseResult

    data class Invalid(val message: String) : BookMapBatchParseResult
}

internal object BookMapErrors {
    fun fromCallFailure(
        error: String?,
        trouble: ExplainTrouble?,
        existing: BookMapDocument?,
    ): BookMapUiState.Failed {
        val raw = error.orEmpty()
        val offline = trouble == ExplainTrouble.Unreachable ||
            raw.contains("连不上") ||
            raw.contains("离线") ||
            raw.contains("网络")
        val quota = trouble == ExplainTrouble.Busy ||
            raw.contains("credit_limit", ignoreCase = true) ||
            raw.contains("free_grant_exhausted", ignoreCase = true) ||
            raw.contains("免费额度") ||
            raw.contains("quota", ignoreCase = true) ||
            raw.contains("额度") ||
            raw.contains("上限") ||
            raw.contains("繁忙")
        val auth = trouble == ExplainTrouble.Revoked || trouble == ExplainTrouble.Unpaired
        val message = when {
            offline -> if (existing != null) {
                "现在连不上网络，已保留本机存好的地图，联网后再更新。"
            } else {
                "现在连不上网络，联网后再生成地图；生成一次会存在本机，以后离线也能看。"
            }
            auth -> raw.ifBlank { "登录状态已失效，请重新登录后再生成地图。" }
            quota -> "当前生成额度紧张或已到上限，请稍后再试；已生成的地图不受影响。"
            raw.contains("超时") -> "全书地图还在整理中，这次等待超时了，点一下继续获取，不会重复花额度。"
            else -> raw.ifBlank { "这次全书地图没生成成功，点一下重试。" }
        }
        return BookMapUiState.Failed(
            message = message,
            offline = offline,
            retryable = !auth,
            existing = existing,
        )
    }
}

/**
 * 基于书的真实骨架（TON-1876：正文起始页、章数、章名、章节边界）提取章节和带精确 UTF-16 偏移的候选原句。
 */
internal object BookMapSkeleton {
    private val asciiWordRe = Regex("[A-Za-z0-9]+")
    private val pageNumberLineRe = Regex("""^\s*(?:page\s*)?\d{1,5}\s*$""", RegexOption.IGNORE_CASE)
    private val tocDotsRe = Regex("""\.{4,}\s*\d+\s*$""")
    private val headingLineRe = Regex(
        """^(?:(?:CHAPTER|PART|BOOK|SECTION|APPENDIX)\b[\s\dIVXLCM.:—-]*$|第[一二三四五六七八九十百千万\d]+[章节篇回卷部](?:\s|$).{0,36}$)""",
        RegexOption.IGNORE_CASE,
    )
    private val listItemStartRe = Regex("""^(?:[-*•▪▫◦]\s+|\d{1,3}[.)]\s+|[（(]\d{1,2}[)）]\s+)""")
    private val abbreviationRe = Regex(
        """(?:^|[\s(“"‘'(])(?:Mr|Mrs|Ms|Dr|Prof|St|Fig|Figs|No|Nos|Vol|Ch|Chap|Sec|pp|p|vs|etc|cf|al|Jan|Feb|Mar|Apr|Aug|Sept|Sep|Oct|Nov|Dec|e\.g|i\.e)\.$""",
        RegexOption.IGNORE_CASE,
    )

    fun bodyStartPage(book: Textbook): Int {
        val anchor = BookBoundaries.bodyStart(book.pages, book.structure, book.correctedBodyPage)
        return anchor.importPage.coerceAtLeast(book.pages.firstOrNull()?.number ?: 1)
    }

    /**
     * 按书的当前骨架列出正文各章，排除正文起始页之前的前置页。
     * 与 TON-1876 的 `BookChapterResolver.chapters(book)` 保持同一章节主键（`tocNodeId`）。
     */
    fun chapters(book: Textbook): List<BookChapterSpan> {
        val start = bodyStartPage(book)
        val raw = BookChapterResolver.chapters(book)
        val filtered = raw.mapNotNull { span ->
            val clampedFirst = maxOf(span.firstPage, start)
            if (clampedFirst > span.lastPage) return@mapNotNull null
            val offset = if (clampedFirst == span.firstPage) span.firstOffset else 0
            span.copy(firstPage = clampedFirst, firstOffset = offset)
        }
        if (filtered.isNotEmpty()) return filtered
        val last = book.pages.lastOrNull()?.number ?: start
        return listOf(BookChapterSpan("ch-all", book.title.ifBlank { "全书" }, start, last.coerceAtLeast(start), book.lessons.indices.toList()))
    }

    /**
     * 骨架指纹：只要用户改了正文起始页、章名、拆章或并章，指纹必然改变。
     */
    fun computeSkeletonHash(book: Textbook): String {
        val start = bodyStartPage(book)
        val spans = chapters(book)
        val editRev = book.structure?.editRevision.orEmpty()
        val canonical = buildString {
            append("v2|rev=").append(editRev).append("|body=").append(start).append("|count=").append(spans.size)
            for (span in spans) {
                append('|').append(span.tocNodeId)
                    .append(':').append(span.title.trim())
                    .append('@').append(span.firstPage).append(':').append(span.firstOffset)
                    .append('-').append(span.lastPage)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    /**
     * 决定某个 `(pdfPage, startUtf16)` 落在哪一章里（兼容重排 EPUB 同页多章）。
     */
    fun chapterForAnchor(chapters: List<BookChapterSpan>, pdfPage: Int, startUtf16: Int = 0): BookChapterSpan? {
        if (chapters.isEmpty()) return null
        val ordered = chapters.sortedWith(compareBy(BookChapterSpan::firstPage, BookChapterSpan::firstOffset))
        for (i in ordered.indices) {
            val cur = ordered[i]
            val next = ordered.getOrNull(i + 1)
            val afterStart = pdfPage > cur.firstPage || (pdfPage == cur.firstPage && startUtf16 >= cur.firstOffset)
            val beforeNext = next == null ||
                pdfPage < next.firstPage ||
                (pdfPage == next.firstPage && next.firstOffset > 0 && startUtf16 < next.firstOffset)
            if (afterStart && beforeNext && pdfPage <= cur.lastPage) {
                return cur
            }
        }
        return ordered.lastOrNull { pdfPage in it.firstPage..it.lastPage }
    }

    /**
     * 从一章的页面里均匀抽取带精确 `(pdfPage, startUtf16, endUtf16)` 的候选原句。
     * 每条原句严格满足 `book.page(pdfPage)!!.text.substring(startUtf16, endUtf16) == text`。
     */
    fun extractChapterSentences(
        book: Textbook,
        chapter: BookChapterSpan,
        nextChapter: BookChapterSpan? = null,
        startRef: Int = 0,
        maxSentences: Int = 24,
    ): List<BookMapSentenceRef> {
        val chapterPages = book.pages
            .filter { it.number in chapter.firstPage..chapter.lastPage && it.text.isNotBlank() }
            .sortedBy { it.number }
        if (chapterPages.isEmpty()) return emptyList()

        val allCandidates = mutableListOf<BookMapSentenceRef>()
        for (page in chapterPages) {
            val rangeStart = if (page.number == chapter.firstPage) chapter.firstOffset.coerceIn(0, page.text.length) else 0
            val rangeEnd = if (nextChapter != null && nextChapter.firstPage == page.number && nextChapter.firstOffset > rangeStart) {
                nextChapter.firstOffset.coerceAtMost(page.text.length)
            } else {
                page.text.length
            }
            if (rangeStart >= rangeEnd) continue
            extractSentencesInRange(page.number, page.text, rangeStart, rangeEnd, chapter.tocNodeId, allCandidates)
        }
        if (allCandidates.isEmpty()) return emptyList()

        val picked = if (allCandidates.size <= maxSentences) {
            allCandidates
        } else {
            val step = (allCandidates.size - 1).toDouble() / (maxSentences - 1).coerceAtLeast(1)
            val indices = linkedSetOf<Int>()
            for (i in 0 until maxSentences) {
                indices += kotlin.math.round(i * step).toInt().coerceIn(0, allCandidates.lastIndex)
            }
            indices.map { allCandidates[it] }
        }
        return picked.mapIndexed { idx, item -> item.copy(ref = startRef + idx) }
    }

    private fun extractSentencesInRange(
        pdfPage: Int,
        fullText: String,
        rangeStart: Int,
        rangeEnd: Int,
        chapterId: String,
        out: MutableList<BookMapSentenceRef>,
    ) {
        // Assemble logical paragraphs across single soft line-wraps (`\n`) so Gutenberg/EPUB/PDF wrapped
        // sentences are NEVER truncated mid-sentence at a hard line break, while keeping exact raw UTF-16 offsets.
        var paraStart = -1
        var paraEnd = -1
        var cursor = rangeStart
        while (cursor < rangeEnd) {
            val nextBreak = fullText.indexOf('\n', cursor).let { if (it < 0 || it > rangeEnd) rangeEnd else it }
            val rawLine = fullText.substring(cursor, nextBreak)
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || isNoiseLine(trimmed) || isStandaloneHeading(trimmed)) {
                if (paraStart in 0 until paraEnd) {
                    splitParagraphIntoSentences(pdfPage, fullText, paraStart, paraEnd, chapterId, out)
                    paraStart = -1
                    paraEnd = -1
                }
            } else {
                val leadTrim = rawLine.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                val lineStart = cursor + leadTrim
                val lineEnd = (cursor + rawLine.trimEnd().length).coerceAtMost(rangeEnd)
                if (paraStart < 0) {
                    paraStart = lineStart
                    paraEnd = lineEnd
                } else if (listItemStartRe.containsMatchIn(trimmed)) {
                    splitParagraphIntoSentences(pdfPage, fullText, paraStart, paraEnd, chapterId, out)
                    paraStart = lineStart
                    paraEnd = lineEnd
                } else {
                    paraEnd = lineEnd
                }
            }
            cursor = nextBreak + 1
        }
        if (paraStart in 0 until paraEnd) {
            splitParagraphIntoSentences(pdfPage, fullText, paraStart, paraEnd, chapterId, out)
        }
    }

    private fun splitParagraphIntoSentences(
        pdfPage: Int,
        fullText: String,
        paraStart: Int,
        paraEnd: Int,
        chapterId: String,
        out: MutableList<BookMapSentenceRef>,
    ) {
        var segStart = paraStart
        var i = paraStart
        while (i < paraEnd) {
            val ch = fullText[i]
            val isSentenceEnd = when (ch) {
                '。', '！', '？' -> true
                '；' -> (i - segStart) >= 120
                '.', '!', '?' -> {
                    val boundaryAfter = i + 1 == paraEnd ||
                        fullText[i + 1].isWhitespace() ||
                        fullText[i + 1] in "\"”'’)]）】"
                    if (!boundaryAfter) {
                        false
                    } else if (ch == '.') {
                        val decimal = i > paraStart && fullText[i - 1].isDigit() && i + 1 < paraEnd && fullText[i + 1].isDigit()
                        val initial = i > paraStart && fullText[i - 1].isUpperCase() &&
                            (i - 1 == paraStart || !fullText[i - 2].isLetter())
                        val lookbackStart = (i - 12).coerceAtLeast(paraStart)
                        val abbr = abbreviationRe.containsMatchIn(fullText.substring(lookbackStart, i + 1))
                        !decimal && !initial && !abbr
                    } else {
                        true
                    }
                }
                ';' -> (i - segStart) >= 160 && (i + 1 == paraEnd || fullText[i + 1].isWhitespace())
                else -> false
            }
            if (isSentenceEnd) {
                var endInclusive = (i + 1).coerceAtMost(paraEnd)
                while (endInclusive < paraEnd && fullText[endInclusive] in "\"”'’」』）】") {
                    endInclusive++
                }
                emitSentenceIfSubstantive(pdfPage, fullText, segStart, endInclusive, chapterId, out)
                var next = endInclusive
                while (next < paraEnd && fullText[next].isWhitespace()) next++
                segStart = next
                i = next
            } else {
                i++
            }
        }
        if (segStart < paraEnd) {
            emitSentenceIfSubstantive(pdfPage, fullText, segStart, paraEnd, chapterId, out)
        }
    }

    private fun emitSentenceIfSubstantive(
        pdfPage: Int,
        fullText: String,
        rawStart: Int,
        rawEnd: Int,
        chapterId: String,
        out: MutableList<BookMapSentenceRef>,
    ) {
        var s = rawStart
        var e = rawEnd
        while (s < e && fullText[s].isWhitespace()) s++
        while (e > s && fullText[e - 1].isWhitespace()) e--
        if (e <= s) return
        val len = e - s
        if (len > 380) {
            // Trim overly long run-on sentences at a natural clause boundary while keeping exact UTF-16 slice.
            val cap = (s + 340).coerceAtMost(e)
            val stop = (cap downTo (s + 120)).firstOrNull { fullText[it] in ",，;；:：" }
                ?: (cap downTo (s + 120)).firstOrNull { fullText[it].isWhitespace() }
                ?: cap
            e = stop
            while (e > s && fullText[e - 1].isWhitespace()) e--
        }
        val slice = fullText.substring(s, e)
        if (!isSubstantiveSentence(slice)) return
        out += BookMapSentenceRef(
            ref = -1,
            chapterId = chapterId,
            pdfPage = pdfPage,
            startUtf16 = s,
            endUtf16 = e,
            text = slice,
        )
    }

    private fun isStandaloneHeading(trimmed: String): Boolean {
        if (headingLineRe.matches(trimmed)) return true
        // All-uppercase short title lines (e.g. Gutenberg "OF THE DIVISION OF LABOUR.")
        if (trimmed.length in 3..90 && trimmed.any { it in 'A'..'Z' } && trimmed.none { it in 'a'..'z' } &&
            trimmed.none { it in '\u4e00'..'\u9fff' }
        ) {
            return true
        }
        return false
    }

    private fun isNoiseLine(trimmed: String): Boolean {
        if (trimmed.startsWith("#")) return true
        if (pageNumberLineRe.matches(trimmed)) return true
        if (tocDotsRe.containsMatchIn(trimmed)) return true
        return false
    }

    fun isSubstantiveSentence(text: String): Boolean {
        val asciiWords = asciiWordRe.findAll(text).count()
        if (asciiWords >= 4 && text.length >= 20) return true
        val cjk = text.count { it in '\u4e00'..'\u9fff' }
        return cjk >= 8
    }
}

/**
 * 原句锚点精确解析器：
 * 确保点击地图要点时产出 `page.text.substring(startUtf16, endUtf16) == quoteAnchor`，
 * 直接命中 `TextbookReader.kt` 的 `handleCitationJump(exactPassage)` 实现跳页并高亮。
 */
internal object BookMapAnchorResolver {
    private val edgeQuoteRe = Regex("""^[\s“”"‘’'`]+|[\s“”"‘’'`]+$""")

    fun resolve(book: Textbook, anchor: BookMapAnchor): BookMapAnchor? {
        val claimedPage = book.page(anchor.pdfPage)
        if (claimedPage != null &&
            anchor.startUtf16 in 0 until anchor.endUtf16 &&
            anchor.endUtf16 <= claimedPage.text.length &&
            claimedPage.text.substring(anchor.startUtf16, anchor.endUtf16) == anchor.quote &&
            anchor.quote.isNotBlank()
        ) {
            return anchor.copy(blockId = anchor.blockId.ifBlank { "${book.id}:p${anchor.pdfPage}:${anchor.startUtf16}" })
        }

        val cleanQuote = edgeQuoteRe.replace(anchor.quote.trim(), "")
        if (cleanQuote.isBlank()) return null

        // 1. Try claimed page first (exact, SentenceCitationGate typographic snap, then NFKC whitespace-collapsed).
        if (claimedPage != null) {
            findRawSpan(claimedPage.text, cleanQuote)?.let { (s, e) ->
                return BookMapAnchor(
                    pdfPage = claimedPage.number,
                    startUtf16 = s,
                    endUtf16 = e,
                    quote = claimedPage.text.substring(s, e),
                    blockId = "${book.id}:p${claimedPage.number}:$s",
                )
            }
            val snapped = SentenceCitationGate.verifyOrSnapAnchor(cleanQuote, claimedPage.text)
            if (snapped.verified && snapped.startUtf16 in 0 until snapped.endUtf16 && snapped.endUtf16 <= claimedPage.text.length) {
                return BookMapAnchor(
                    pdfPage = claimedPage.number,
                    startUtf16 = snapped.startUtf16,
                    endUtf16 = snapped.endUtf16,
                    quote = claimedPage.text.substring(snapped.startUtf16, snapped.endUtf16),
                    blockId = "${book.id}:p${claimedPage.number}:${snapped.startUtf16}",
                )
            }
        }

        // 2. Fallback: search nearest pages in the same book (handles ±1 page shifts or chapter remaps).
        val orderedPages = book.pages
            .filter { it.number != anchor.pdfPage && it.text.isNotBlank() }
            .sortedBy { kotlin.math.abs(it.number - anchor.pdfPage) }
        for (page in orderedPages) {
            findRawSpan(page.text, cleanQuote)?.let { (s, e) ->
                return BookMapAnchor(
                    pdfPage = page.number,
                    startUtf16 = s,
                    endUtf16 = e,
                    quote = page.text.substring(s, e),
                    blockId = "${book.id}:p${page.number}:$s",
                )
            }
            if (kotlin.math.abs(page.number - anchor.pdfPage) <= 3) {
                val snapped = SentenceCitationGate.verifyOrSnapAnchor(cleanQuote, page.text)
                if (snapped.verified && snapped.startUtf16 in 0 until snapped.endUtf16 && snapped.endUtf16 <= page.text.length) {
                    return BookMapAnchor(
                        pdfPage = page.number,
                        startUtf16 = snapped.startUtf16,
                        endUtf16 = snapped.endUtf16,
                        quote = page.text.substring(snapped.startUtf16, snapped.endUtf16),
                        blockId = "${book.id}:p${page.number}:${snapped.startUtf16}",
                    )
                }
            }
        }
        return null
    }

    fun toCompanionAnchor(book: Textbook, anchor: BookMapAnchor): BookCompanionAnchor {
        val resolved = resolve(book, anchor) ?: anchor
        return BookCompanionAnchor(
            pdfPage = resolved.pdfPage,
            blockId = resolved.blockId.ifBlank { "${book.id}:p${resolved.pdfPage}:${resolved.startUtf16}" },
            startUtf16 = resolved.startUtf16,
            endUtf16 = resolved.endUtf16,
            quoteAnchor = resolved.quote,
        )
    }

    internal fun findRawSpan(pageText: String, quote: String): Pair<Int, Int>? {
        if (pageText.isEmpty() || quote.isBlank()) return null
        val exact = pageText.indexOf(quote)
        if (exact >= 0) return exact to (exact + quote.length)
        val ci = pageText.indexOf(quote, ignoreCase = true)
        if (ci >= 0) return ci to (ci + quote.length)

        // NFKC + whitespace-collapsed fuzzy match mapped back to raw UTF-16 offsets.
        val normNeedle = normalize(quote)
        if (normNeedle.length < 6) return null
        val normChars = StringBuilder(pageText.length)
        val rawIndexForNorm = IntArray(pageText.length * 2 + 16)
        var normLen = 0
        var prevSpace = true
        for (rawIdx in pageText.indices) {
            val ch = pageText[rawIdx]
            if (ch == '\u00AD') continue
            if (ch.isWhitespace()) {
                if (!prevSpace && normLen > 0) {
                    if (normLen < rawIndexForNorm.size) {
                        normChars.append(' ')
                        rawIndexForNorm[normLen++] = rawIdx
                    }
                    prevSpace = true
                }
                continue
            }
            val piece = Normalizer.normalize(ch.toString(), Normalizer.Form.NFKC).lowercase(Locale.US)
            for (pCh in piece) {
                if (pCh.isWhitespace()) {
                    if (!prevSpace && normLen > 0 && normLen < rawIndexForNorm.size) {
                        normChars.append(' ')
                        rawIndexForNorm[normLen++] = rawIdx
                        prevSpace = true
                    }
                } else if (normLen < rawIndexForNorm.size) {
                    normChars.append(pCh)
                    rawIndexForNorm[normLen++] = rawIdx
                    prevSpace = false
                }
            }
        }
        val hit = normChars.indexOf(normNeedle)
        if (hit < 0) return null
        val endNormIdx = (hit + normNeedle.length - 1).coerceAtMost(normLen - 1)
        val rawStart = rawIndexForNorm[hit].coerceIn(0, pageText.length)
        var rawEnd = (rawIndexForNorm[endNormIdx] + 1).coerceIn(rawStart, pageText.length)
        while (rawEnd < pageText.length && pageText[rawEnd] == '\u00AD') rawEnd++
        return if (rawEnd > rawStart) rawStart to rawEnd else null
    }

    private fun normalize(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace("\u00AD", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.US)
}

/**
 * 当用户在 TON-1876 里修改了书的骨架（改正文起始页、改章名、合并章、拆章）时，
 * 按每条要点的真实原句位置 `(pdfPage, startUtf16)` 自动把已生成的地图重排到新骨架，不丢原句也不盲目重花额度。
 */
internal object BookMapReconciler {
    fun reconcile(saved: BookMapDocument, book: Textbook): BookMapDocument {
        val currentHash = BookMapSkeleton.computeSkeletonHash(book)
        val currentBodyStart = BookMapSkeleton.bodyStartPage(book)
        val spans = BookMapSkeleton.chapters(book)
        if (saved.skeletonHash == currentHash && saved.chapters.size == spans.size) {
            return saved
        }

        val oldById = saved.chapters.associateBy { it.chapterId }
        val allPoints = saved.chapters
            .flatMap { ch -> ch.points }
            .mapNotNull { pt ->
                val resolvedAnchors = pt.anchors.mapNotNull { BookMapAnchorResolver.resolve(book, it) }
                if (resolvedAnchors.isEmpty()) null else pt.copy(anchors = resolvedAnchors)
            }

        val buckets = LinkedHashMap<String, MutableList<BookMapPoint>>()
        spans.forEach { buckets[it.tocNodeId] = mutableListOf() }

        for (pt in allPoints) {
            val anchor = pt.primaryAnchor ?: continue
            if (anchor.pdfPage < currentBodyStart) continue
            val targetSpan = BookMapSkeleton.chapterForAnchor(spans, anchor.pdfPage, anchor.startUtf16) ?: continue
            val bucket = buckets[targetSpan.tocNodeId] ?: continue
            if (bucket.none { it.summary == pt.summary && it.primaryAnchor?.pdfPage == anchor.pdfPage }) {
                bucket += pt
            }
        }

        val stale = linkedSetOf<String>()
        val rebuiltChapters = spans.mapIndexed { idx, span ->
            val sortedPoints = buckets[span.tocNodeId].orEmpty()
                .sortedWith(compareBy({ it.primaryAnchor?.pdfPage ?: Int.MAX_VALUE }, { it.primaryAnchor?.startUtf16 ?: 0 }))
            val prevOneLiner = oldById[span.tocNodeId]?.oneLiner
                ?: saved.chapters.firstOrNull { it.firstPage <= span.lastPage && it.lastPage >= span.firstPage }?.oneLiner
                ?: ""
            val expectedMin = minOf(
                BookMapDocument.MIN_POINTS_PER_CHAPTER,
                BookMapSkeleton.extractChapterSentences(book, span, spans.getOrNull(idx + 1), maxSentences = 4).size.coerceAtLeast(1),
            )
            if (sortedPoints.size < expectedMin) {
                stale += span.tocNodeId
            }
            BookMapChapter(
                chapterId = span.tocNodeId,
                chapterNum = idx + 1,
                title = span.title,
                firstPage = span.firstPage,
                lastPage = span.lastPage,
                oneLiner = prevOneLiner,
                points = sortedPoints,
            )
        }

        return saved.copy(
            bookTitle = book.title.ifBlank { saved.bookTitle },
            skeletonHash = currentHash,
            bodyStartPage = currentBodyStart,
            chapterCount = rebuiltChapters.size,
            chapters = rebuiltChapters,
            staleChapterIds = stale,
            reconciledFromSkeleton = true,
        )
    }
}

/**
 * 结合真实章节阅读进度（`book_chapter_progress`）判断每章、每个要点的已读 / 没读状态。
 */
internal object BookMapReadProgress {
    data class ChapterStatus(
        val state: ChapterReadState,
        val readPoints: Int,
        val totalPoints: Int,
        val labelZh: String,
    )

    fun loadForBook(library: TextbookLibrary, bookId: String): Map<String, ChapterProgressRecord> = runCatching {
        java.sql.DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            BooklistProgressStore.loadForBook(db, bookId)
        }
    }.getOrDefault(emptyMap())

    fun isPointRead(point: BookMapPoint, progress: ChapterProgressRecord?): Boolean {
        if (progress == null) return false
        if (progress.readState == ChapterReadState.READ) return true
        return point.anchors.any { it.pdfPage in progress.dwelledPages }
    }

    fun chapterStatus(chapter: BookMapChapter, progress: ChapterProgressRecord?): ChapterStatus {
        val total = chapter.points.size
        val readCount = chapter.points.count { isPointRead(it, progress) }
        val state = when {
            progress?.readState == ChapterReadState.READ -> ChapterReadState.READ
            readCount > 0 || progress?.readState == ChapterReadState.PARTIAL -> ChapterReadState.PARTIAL
            else -> ChapterReadState.UNREAD
        }
        val label = when (state) {
            ChapterReadState.READ -> "已读"
            ChapterReadState.PARTIAL -> "读了 $readCount/$total"
            ChapterReadState.UNREAD -> "没读"
        }
        return ChapterStatus(state = state, readPoints = readCount, totalPoints = total, labelZh = label)
    }
}

/**
 * 构建与解析 2.1 全书地图 Batch 请求/响应与本地 JSON 序列化。
 */
internal object BookMapWire {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class BuiltBatchRequest(
        val payload: JsonObject,
        val skeletonHash: String,
        val refMap: Map<Int, BookMapSentenceRef>,
        val chapters: List<BookChapterSpan>,
    )

    fun buildBatchRequest(
        book: Textbook,
        explainLang: ExplainLanguage = ExplainLanguage.BILINGUAL,
        revision: Int = 0,
        targetChapterIds: Set<String> = emptySet(),
    ): BuiltBatchRequest {
        val bodyStart = BookMapSkeleton.bodyStartPage(book)
        val allSpans = BookMapSkeleton.chapters(book)
        val spans = if (targetChapterIds.isEmpty()) allSpans else allSpans.filter { it.tocNodeId in targetChapterIds }.ifEmpty { allSpans }
        val skeletonHash = BookMapSkeleton.computeSkeletonHash(book)

        val maxSentencesPerChapter = if (allSpans.size == 1) 36 else 24
        val refMap = LinkedHashMap<Int, BookMapSentenceRef>()
        var nextRef = 1
        val chaptersArray = buildJsonArray {
            spans.forEach { span ->
                val globalIndex = allSpans.indexOfFirst { it.tocNodeId == span.tocNodeId }.coerceAtLeast(0)
                val nextSpan = allSpans.getOrNull(globalIndex + 1)
                val refs = BookMapSkeleton.extractChapterSentences(
                    book = book,
                    chapter = span,
                    nextChapter = nextSpan,
                    startRef = nextRef,
                    maxSentences = maxSentencesPerChapter,
                )
                nextRef += refs.size
                refs.forEach { refMap[it.ref] = it }

                add(
                    buildJsonObject {
                        put("chapterId", span.tocNodeId)
                        put("chapterNum", globalIndex + 1)
                        put("chapterTitle", span.title)
                        put("firstPage", span.firstPage)
                        put("lastPage", span.lastPage)
                        put(
                            "refs",
                            buildJsonArray {
                                refs.forEach { r ->
                                    add(
                                        buildJsonObject {
                                            put("ref", r.ref)
                                            put("pdfPage", r.pdfPage)
                                            put("text", r.text.replace(Regex("\\s+"), " ").trim())
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }

        val payload = buildJsonObject {
            put("formatVersion", BookMapDocument.CURRENT_VERSION)
            put("bookId", book.id)
            put("bookTitle", book.title.ifBlank { book.id })
            put("skeletonHash", skeletonHash)
            put("bodyStartPage", bodyStart)
            put("explainLang", explainLang.name)
            put("useBatchApi", true)
            put("revision", revision)
            put("chapters", chaptersArray)
        }
        return BuiltBatchRequest(
            payload = payload,
            skeletonHash = skeletonHash,
            refMap = refMap,
            chapters = spans,
        )
    }

    fun parseBatchResponse(
        book: Textbook,
        response: JsonObject,
        refMap: Map<Int, BookMapSentenceRef>,
        existingToMerge: BookMapDocument? = null,
    ): BookMapBatchParseResult {
        if (response["pending"]?.jsonPrimitive?.booleanOrNull == true) {
            return BookMapBatchParseResult.Pending(
                retryAfterMs = (response["retryAfterMs"]?.jsonPrimitive?.longOrNull ?: 5_000L).coerceIn(1_000L, 60_000L),
                jobKey = response["jobKey"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                completedChunks = response["completedChunks"]?.jsonPrimitive?.intOrNull ?: 0,
                totalChunks = response["totalChunks"]?.jsonPrimitive?.intOrNull ?: 1,
            )
        }
        val chaptersArr = response["chapters"] as? JsonArray
            ?: return BookMapBatchParseResult.Invalid("返回的地图缺少章节数据，请重试。")

        val spans = BookMapSkeleton.chapters(book)
        val maxPointsPerChapter = if (spans.size == 1) {
            BookMapDocument.MAX_POINTS_SINGLE_CHAPTER_BOOK
        } else {
            BookMapDocument.MAX_POINTS_PER_CHAPTER
        }
        val spanById = spans.associateBy { it.tocNodeId }
        val parsedById = LinkedHashMap<String, BookMapChapter>()

        for (el in chaptersArr) {
            val chObj = el as? JsonObject ?: continue
            val chNum = chObj["chapterNum"]?.jsonPrimitive?.intOrNull ?: 0
            val rawId = chObj["chapterId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val span = spanById[rawId] ?: spans.getOrNull(chNum - 1) ?: continue
            val oneLiner = chObj["oneLiner"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val rawPoints = chObj["points"] as? JsonArray ?: JsonArray(emptyList())

            val validPoints = mutableListOf<BookMapPoint>()
            rawPoints.forEachIndexed { idx, pEl ->
                if (validPoints.size >= maxPointsPerChapter) return@forEachIndexed
                val pObj = pEl as? JsonObject ?: return@forEachIndexed
                val summary = pObj["summary"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                if (summary.isBlank()) return@forEachIndexed

                val anchors = mutableListOf<BookMapAnchor>()
                // Prefer exact sentence refs resolved locally from refMap (100% exact UTF-16).
                (pObj["refs"] as? JsonArray).orEmpty().forEach { rEl ->
                    val rId = (rEl as? JsonPrimitive)?.intOrNull ?: return@forEach
                    val sent = refMap[rId] ?: return@forEach
                    if (sent.pdfPage in span.firstPage..span.lastPage) {
                        val candidate = BookMapAnchor(
                            pdfPage = sent.pdfPage,
                            startUtf16 = sent.startUtf16,
                            endUtf16 = sent.endUtf16,
                            quote = sent.text,
                            blockId = "${book.id}:p${sent.pdfPage}:${sent.startUtf16}",
                        )
                        BookMapAnchorResolver.resolve(book, candidate)?.let { resolved ->
                            if (anchors.none { it.pdfPage == resolved.pdfPage && it.startUtf16 == resolved.startUtf16 }) {
                                anchors += resolved
                            }
                        }
                    }
                }
                // Also support explicit anchors array with quoteAnchor fallback.
                (pObj["anchors"] as? JsonArray).orEmpty().forEach { aEl ->
                    val aObj = aEl as? JsonObject ?: return@forEach
                    val refId = aObj["ref"]?.jsonPrimitive?.intOrNull
                    val fromRef = refId?.let { refMap[it] }
                    val candidate = if (fromRef != null) {
                        BookMapAnchor(fromRef.pdfPage, fromRef.startUtf16, fromRef.endUtf16, fromRef.text)
                    } else {
                        val pg = aObj["pdfPage"]?.jsonPrimitive?.intOrNull ?: return@forEach
                        val quote = aObj["quote"]?.jsonPrimitive?.contentOrNull
                            ?: aObj["quoteAnchor"]?.jsonPrimitive?.contentOrNull
                            ?: return@forEach
                        BookMapAnchor(
                            pdfPage = pg,
                            startUtf16 = aObj["startUtf16"]?.jsonPrimitive?.intOrNull ?: 0,
                            endUtf16 = aObj["endUtf16"]?.jsonPrimitive?.intOrNull ?: 0,
                            quote = quote,
                        )
                    }
                    BookMapAnchorResolver.resolve(book, candidate)?.let { resolved ->
                        if (anchors.none { it.pdfPage == resolved.pdfPage && it.startUtf16 == resolved.startUtf16 }) {
                            anchors += resolved
                        }
                    }
                }
                // Fail-closed: every point MUST carry at least one verified original sentence anchor.
                if (anchors.isNotEmpty()) {
                    validPoints += BookMapPoint(
                        id = pObj["id"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "${span.tocNodeId}-pt-${idx + 1}",
                        summary = summary,
                        anchors = anchors,
                    )
                }
            }

            parsedById[span.tocNodeId] = BookMapChapter(
                chapterId = span.tocNodeId,
                chapterNum = spans.indexOfFirst { it.tocNodeId == span.tocNodeId } + 1,
                title = span.title,
                firstPage = span.firstPage,
                lastPage = span.lastPage,
                oneLiner = oneLiner,
                points = validPoints,
            )
        }

        val existingById = existingToMerge?.chapters?.associateBy { it.chapterId }.orEmpty()
        val finalChapters = spans.mapIndexed { idx, span ->
            parsedById[span.tocNodeId]
                ?: existingById[span.tocNodeId]?.copy(
                    chapterNum = idx + 1,
                    title = span.title,
                    firstPage = span.firstPage,
                    lastPage = span.lastPage,
                )
                ?: BookMapChapter(
                    chapterId = span.tocNodeId,
                    chapterNum = idx + 1,
                    title = span.title,
                    firstPage = span.firstPage,
                    lastPage = span.lastPage,
                    points = emptyList(),
                )
        }
        val totalValid = finalChapters.sumOf { it.points.size }
        if (totalValid == 0) {
            return BookMapBatchParseResult.Invalid("模型返回的要点未能匹配到原书句子，已拒绝保存，请重试。")
        }

        val model = response["model"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "claude-haiku-5-5"
        val doc = BookMapDocument(
            version = BookMapDocument.CURRENT_VERSION,
            bookId = book.id,
            bookTitle = book.title.ifBlank { book.id },
            skeletonHash = BookMapSkeleton.computeSkeletonHash(book),
            bodyStartPage = BookMapSkeleton.bodyStartPage(book),
            chapterCount = finalChapters.size,
            chapters = finalChapters,
            generatedAt = System.currentTimeMillis(),
            model = model,
            staleChapterIds = emptySet(),
            reconciledFromSkeleton = false,
        )
        return BookMapBatchParseResult.Ready(doc)
    }

    fun encodeDocument(doc: BookMapDocument): String = buildJsonObject {
        put("version", doc.version)
        put("bookId", doc.bookId)
        put("bookTitle", doc.bookTitle)
        put("skeletonHash", doc.skeletonHash)
        put("bodyStartPage", doc.bodyStartPage)
        put("chapterCount", doc.chapterCount)
        put("generatedAt", doc.generatedAt)
        put("model", doc.model)
        put("reconciledFromSkeleton", doc.reconciledFromSkeleton)
        put("staleChapterIds", buildJsonArray { doc.staleChapterIds.forEach { add(JsonPrimitive(it)) } })
        put(
            "chapters",
            buildJsonArray {
                doc.chapters.forEach { ch ->
                    add(
                        buildJsonObject {
                            put("chapterId", ch.chapterId)
                            put("chapterNum", ch.chapterNum)
                            put("title", ch.title)
                            put("firstPage", ch.firstPage)
                            put("lastPage", ch.lastPage)
                            put("oneLiner", ch.oneLiner)
                            put(
                                "points",
                                buildJsonArray {
                                    ch.points.forEach { pt ->
                                        add(
                                            buildJsonObject {
                                                put("id", pt.id)
                                                put("summary", pt.summary)
                                                put(
                                                    "anchors",
                                                    buildJsonArray {
                                                        pt.anchors.forEach { a ->
                                                            add(
                                                                buildJsonObject {
                                                                    put("pdfPage", a.pdfPage)
                                                                    put("startUtf16", a.startUtf16)
                                                                    put("endUtf16", a.endUtf16)
                                                                    put("quote", a.quote)
                                                                    put("blockId", a.blockId)
                                                                },
                                                            )
                                                        }
                                                    },
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }.toString()

    fun decodeDocument(raw: String): BookMapDocument? = runCatching {
        val obj = json.parseToJsonElement(raw).jsonObject
        val bookId = obj["bookId"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: return@runCatching null
        val chapters = (obj["chapters"] as? JsonArray).orEmpty().mapNotNull { cEl ->
            val cObj = cEl as? JsonObject ?: return@mapNotNull null
            val cid = cObj["chapterId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val points = (cObj["points"] as? JsonArray).orEmpty().mapNotNull { pEl ->
                val pObj = pEl as? JsonObject ?: return@mapNotNull null
                val summary = pObj["summary"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: return@mapNotNull null
                val anchors = (pObj["anchors"] as? JsonArray).orEmpty().mapNotNull { aEl ->
                    val aObj = aEl as? JsonObject ?: return@mapNotNull null
                    val pg = aObj["pdfPage"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val quote = aObj["quote"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    BookMapAnchor(
                        pdfPage = pg,
                        startUtf16 = aObj["startUtf16"]?.jsonPrimitive?.intOrNull ?: 0,
                        endUtf16 = aObj["endUtf16"]?.jsonPrimitive?.intOrNull ?: 0,
                        quote = quote,
                        blockId = aObj["blockId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
                if (anchors.isEmpty()) return@mapNotNull null
                BookMapPoint(
                    id = pObj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    summary = summary,
                    anchors = anchors,
                )
            }
            BookMapChapter(
                chapterId = cid,
                chapterNum = cObj["chapterNum"]?.jsonPrimitive?.intOrNull ?: 1,
                title = cObj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                firstPage = cObj["firstPage"]?.jsonPrimitive?.intOrNull ?: 1,
                lastPage = cObj["lastPage"]?.jsonPrimitive?.intOrNull ?: 1,
                oneLiner = cObj["oneLiner"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                points = points,
            )
        }
        BookMapDocument(
            version = obj["version"]?.jsonPrimitive?.intOrNull ?: BookMapDocument.CURRENT_VERSION,
            bookId = bookId,
            bookTitle = obj["bookTitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            skeletonHash = obj["skeletonHash"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            bodyStartPage = obj["bodyStartPage"]?.jsonPrimitive?.intOrNull ?: 1,
            chapterCount = obj["chapterCount"]?.jsonPrimitive?.intOrNull ?: chapters.size,
            chapters = chapters,
            generatedAt = obj["generatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            model = obj["model"]?.jsonPrimitive?.contentOrNull ?: "claude-haiku-5-5",
            staleChapterIds = (obj["staleChapterIds"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .toSet(),
            reconciledFromSkeleton = obj["reconciledFromSkeleton"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }.getOrNull()
}
