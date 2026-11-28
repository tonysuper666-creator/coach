package com.coach.desktop.books

import com.coach.desktop.books.CollectionQaJson.arr
import com.coach.desktop.books.CollectionQaJson.boolOrFalse
import com.coach.desktop.books.CollectionQaJson.intOrNull
import com.coach.desktop.books.CollectionQaJson.str
import com.coach.protocol.BookStanceCopy
import com.coach.protocol.BookStanceGuard
import kotlinx.serialization.json.JsonObject
import java.text.Normalizer

/**
 * 客户端逐句原页硬核验门禁（`SentenceCitationGate`）：
 * - 严格对称服务端与跨平台脚本的 NFKC 连字归一化、跨提取器纯散文修剪与 >= 85% 词窗对齐；
 * - 所有通过核验的引用同时回算 `pages(book, page)` 的精确 UTF-16 区间 `[startUtf16, endUtf16)`；
 * - 任何找不到原文的虚构引文当场剥离，绝不让未核验页码上屏。
 */
internal object SentenceCitationGate {
    data class AnchorResult(
        val rawExactMatch: Boolean,
        val verified: Boolean,
        val canonicalAnchor: String,
        val startUtf16: Int = 0,
        val endUtf16: Int = 0,
    )

    private data class NormalizedWithOffsets(
        val text: String,
        val rawStartOffsets: IntArray,
        val rawEndOffsets: IntArray,
    )

    private val whitespaceRegex = Regex("""\s+""")
    private val mathSpacingNoiseRegex = Regex("""[∗*^]|[A-Z]{1,2}\s+-[A-Z]{1,2}|\b[A-Z]\s+[A-Z]\b""")
    private val mathSpacingSplitRegex = Regex("""\S*[∗*^]\S*|[A-Z]{1,2}\s+-[A-Z]{1,2}|\b[A-Z]\s+[A-Z]\b""")
    private val tokenRegex = Regex("""[A-Za-z0-9]+|[\u4e00-\u9fff]""")
    private val wordSpanRegex = Regex("""\S+""")
    private val inlineTimestampRegex = Regex("""\[((?:\d{1,2}:)?\d{1,2}:\d{2})(?:[.,]\d{1,3})?]""")

    fun normalizeText(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            when (ch) {
                '\u00ad', '\u200b', '\ufeff' -> Unit
                '\u2212', '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015' -> sb.append('-')
                '\u2018', '\u2019', '\u2032', '\u02bc' -> sb.append('\'')
                '\u201c', '\u201d', '\u2033' -> sb.append('"')
                '\u00a0', '\u202f', '\u205f', '\u3000' -> sb.append(' ')
                in '\u2000'..'\u200a' -> sb.append(' ')
                '\ufb01' -> sb.append("fi")
                '\ufb02' -> sb.append("fl")
                '\ufb00' -> sb.append("ff")
                '\ufb03' -> sb.append("ffi")
                '\ufb04' -> sb.append("ffl")
                else -> sb.append(ch)
            }
        }
        return sb.toString().replace(whitespaceRegex, " ").trim()
    }

    private fun normalizeWithRawOffsets(raw: String): NormalizedWithOffsets {
        val normChars = StringBuilder(raw.length)
        val rawStarts = ArrayList<Int>(raw.length)
        val rawEnds = ArrayList<Int>(raw.length)
        var pendingSpace = false
        var spaceRawStart = 0
        var spaceRawEnd = 0
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val charLen = Character.charCount(cp)
            val pieceRaw = raw.substring(i, i + charLen)
            val pieceNorm = expandCharPiece(pieceRaw)
            if (pieceNorm.isEmpty() || pieceNorm.all { it.isWhitespace() }) {
                if (normChars.isNotEmpty()) {
                    if (!pendingSpace) {
                        spaceRawStart = i
                        pendingSpace = true
                    }
                    spaceRawEnd = i + charLen
                }
            } else {
                for (ch in pieceNorm) {
                    if (ch.isWhitespace()) {
                        if (normChars.isNotEmpty()) {
                            if (!pendingSpace) {
                                spaceRawStart = i
                                pendingSpace = true
                            }
                            spaceRawEnd = i + charLen
                        }
                    } else {
                        if (pendingSpace) {
                            normChars.append(' ')
                            rawStarts += spaceRawStart
                            rawEnds += spaceRawEnd
                            pendingSpace = false
                        }
                        normChars.append(ch)
                        rawStarts += i
                        rawEnds += i + charLen
                    }
                }
            }
            i += charLen
        }
        return NormalizedWithOffsets(
            text = normChars.toString(),
            rawStartOffsets = rawStarts.toIntArray(),
            rawEndOffsets = rawEnds.toIntArray(),
        )
    }

    private fun expandCharPiece(piece: String): String {
        val nfkc = Normalizer.normalize(piece, Normalizer.Form.NFKC)
        val out = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            when (ch) {
                '\u00ad', '\u200b', '\ufeff' -> Unit
                '\u2212', '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015' -> out.append('-')
                '\u2018', '\u2019', '\u2032', '\u02bc' -> out.append('\'')
                '\u201c', '\u201d', '\u2033' -> out.append('"')
                '\u00a0', '\u202f', '\u205f', '\u3000' -> out.append(' ')
                in '\u2000'..'\u200a' -> out.append(' ')
                '\ufb01' -> out.append("fi")
                '\ufb02' -> out.append("fl")
                '\ufb00' -> out.append("ff")
                '\ufb03' -> out.append("ffi")
                '\ufb04' -> out.append("ffl")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun isCjkChar(ch: Char): Boolean =
        ch in '\u4e00'..'\u9fff' || ch in '\u3400'..'\u4dbf' || ch in '\u3000'..'\u303f' || ch in '\uff00'..'\uffef'

    private fun compactCjkWithRawOffsets(raw: String): NormalizedWithOffsets {
        val normChars = StringBuilder(raw.length)
        val rawStarts = ArrayList<Int>(raw.length)
        val rawEnds = ArrayList<Int>(raw.length)
        var pendingSpace = false
        var spaceRawStart = 0
        var spaceRawEnd = 0
        var lastWasCjk = false
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val charLen = Character.charCount(cp)
            val pieceNorm = expandCharPiece(raw.substring(i, i + charLen))
            if (pieceNorm.isEmpty() || pieceNorm.all { it.isWhitespace() }) {
                if (normChars.isNotEmpty() && !lastWasCjk) {
                    if (!pendingSpace) {
                        spaceRawStart = i
                        pendingSpace = true
                    }
                    spaceRawEnd = i + charLen
                }
            } else {
                for (ch in pieceNorm) {
                    if (ch.isWhitespace()) {
                        if (normChars.isNotEmpty() && !lastWasCjk) {
                            if (!pendingSpace) {
                                spaceRawStart = i
                                pendingSpace = true
                            }
                            spaceRawEnd = i + charLen
                        }
                    } else {
                        val isCjk = isCjkChar(ch)
                        if (pendingSpace && !isCjk) {
                            normChars.append(' ')
                            rawStarts += spaceRawStart
                            rawEnds += spaceRawEnd
                        }
                        pendingSpace = false
                        normChars.append(ch)
                        rawStarts += i
                        rawEnds += i + charLen
                        lastWasCjk = isCjk
                    }
                }
            }
            i += charLen
        }
        return NormalizedWithOffsets(normChars.toString(), rawStarts.toIntArray(), rawEnds.toIntArray())
    }

    /**
     * Exact ordered token-sequence match over [normPage] that tolerates EPUB typographic punctuation,
     * em-dash spacing (`species,—except` vs `species, - except`), inline brace notes (`{1}`), and CJK without spaces.
     */
    private fun findOrderedTokenSpan(qTokens: List<String>, normPage: String): Pair<Int, Int>? {
        if (qTokens.size < 3) return null
        val pageTokens = tokenRegex.findAll(normPage.lowercase()).toList()
        if (pageTokens.size < qTokens.size) return null
        val limit = pageTokens.size - qTokens.size
        outer@ for (i in 0..limit) {
            if (pageTokens[i].value != qTokens[0]) continue
            var prevEnd = pageTokens[i].range.last + 1
            for (j in 1 until qTokens.size) {
                val tok = pageTokens[i + j]
                if (tok.value != qTokens[j]) continue@outer
                if (tok.range.first - prevEnd > 48) continue@outer
                prevEnd = tok.range.last + 1
            }
            return pageTokens[i].range.first to (pageTokens[i + qTokens.size - 1].range.last + 1)
        }
        return null
    }

    private fun splitWindowUnits(normPage: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (span in wordSpanRegex.findAll(normPage)) {
            val s = span.value
            if (s.none(::isCjkChar)) {
                out += span.range
            } else {
                val base = span.range.first
                var idx = 0
                while (idx < s.length) {
                    if (isCjkChar(s[idx])) {
                        out += (base + idx)..(base + idx)
                        idx++
                    } else {
                        val start = idx
                        while (idx < s.length && !isCjkChar(s[idx])) idx++
                        out += (base + start) until (base + idx)
                    }
                }
            }
        }
        return out
    }
    fun verifyOrSnapAnchor(quote: String, rawPageText: String): AnchorResult {
        val mapped = normalizeWithRawOffsets(rawPageText)
        val normPage = mapped.text
        val q = normalizeText(quote)
        if (normPage.isEmpty() || q.length < 5) {
            return AnchorResult(rawExactMatch = false, verified = false, canonicalAnchor = "")
        }

        val idx = normPage.indexOf(q, ignoreCase = true)
        if (idx >= 0) {
            val rawSlice = normPage.substring(idx, idx + q.length)
            val trimmed = trimPureProseAnchor(rawSlice)
            val relStart = rawSlice.indexOf(trimmed).coerceAtLeast(0)
            val finalNormStart = idx + relStart
            val finalNormEnd = (finalNormStart + trimmed.length).coerceAtMost(normPage.length)
            val (rawStart, rawEnd) = mapNormSpanToRaw(mapped, rawPageText, finalNormStart, finalNormEnd)
            return AnchorResult(
                rawExactMatch = true,
                verified = true,
                canonicalAnchor = trimmed,
                startUtf16 = rawStart,
                endUtf16 = rawEnd,
            )
        }

        // Handle hard-wrapped CJK lines / full-width indents where rawPageText has whitespace inside CJK runs,
        // including short 1-2 clause quotes whose tokenRegex count is < 3.
        if (q.any(::isCjkChar)) {
            val compactPage = compactCjkWithRawOffsets(rawPageText)
            val compactQ = compactCjkWithRawOffsets(quote).text
            if (compactQ.length >= 4) {
                val cIdx = compactPage.text.indexOf(compactQ, ignoreCase = true)
                if (cIdx >= 0) {
                    val (rawStart, rawEnd) = mapNormSpanToRaw(compactPage, rawPageText, cIdx, cIdx + compactQ.length)
                    if (rawEnd > rawStart) {
                        val rawSlice = rawPageText.substring(rawStart, rawEnd)
                        val trimmed = rawSlice.trim()
                        val trimDelta = if (trimmed.isNotEmpty()) rawSlice.indexOf(trimmed).coerceAtLeast(0) else 0
                        val adjStart = rawStart + trimDelta
                        val adjEnd = if (trimmed.isNotEmpty()) adjStart + trimmed.length else rawEnd
                        return AnchorResult(
                            rawExactMatch = true,
                            verified = true,
                            canonicalAnchor = if (trimmed.isNotEmpty()) trimmed else rawSlice,
                            startUtf16 = adjStart,
                            endUtf16 = adjEnd,
                        )
                    }
                }
            }
        }

        val qTokens = tokenRegex.findAll(q.lowercase()).map { it.value }.toList()
        if (qTokens.size < 3) {
            return AnchorResult(rawExactMatch = false, verified = false, canonicalAnchor = "")
        }

        findOrderedTokenSpan(qTokens, normPage)?.let { (tokStart, tokEnd) ->
            val rawSlice = normPage.substring(tokStart, tokEnd)
            val trimmed = trimPureProseAnchor(rawSlice)
            val relStart = rawSlice.indexOf(trimmed).coerceAtLeast(0)
            val finalNormStart = tokStart + relStart
            val finalNormEnd = (finalNormStart + trimmed.length).coerceAtMost(normPage.length)
            val (rawStart, rawEnd) = mapNormSpanToRaw(mapped, rawPageText, finalNormStart, finalNormEnd)
            if (rawStart in 0 until rawEnd && rawEnd <= rawPageText.length) {
                return AnchorResult(
                    rawExactMatch = false,
                    verified = true,
                    canonicalAnchor = trimmed,
                    startUtf16 = rawStart,
                    endUtf16 = rawEnd,
                )
            }
        }

        val words = splitWindowUnits(normPage)
        if (words.isEmpty()) {
            return AnchorResult(rawExactMatch = false, verified = false, canonicalAnchor = "")
        }

        val win = minOf(words.size, maxOf(4, qTokens.size + 2))
        var bestRatio = 0.0
        var bestStart = 0
        var bestEnd = 0
        val maxStart = (words.size - win).coerceAtLeast(0)
        for (i in 0..maxStart) {
            val sPos = words[i].first
            val ePos = words[minOf(words.size - 1, i + win - 1)].last + 1
            val cand = normPage.substring(sPos, ePos)
            val candLow = cand.lowercase()
            var hits = 0
            for (t in qTokens) {
                if (t in candLow) hits++
            }
            val ratio = hits.toDouble() / qTokens.size.toDouble()
            if (ratio > bestRatio) {
                bestRatio = ratio
                bestStart = sPos
                bestEnd = ePos
            }
        }
        if (bestRatio >= 0.85 && bestEnd > bestStart) {
            val rawSlice = normPage.substring(bestStart, bestEnd)
            val trimmed = trimPureProseAnchor(rawSlice)
            val relStart = rawSlice.indexOf(trimmed).coerceAtLeast(0)
            val finalNormStart = bestStart + relStart
            val finalNormEnd = (finalNormStart + trimmed.length).coerceAtMost(normPage.length)
            val (rawStart, rawEnd) = mapNormSpanToRaw(mapped, rawPageText, finalNormStart, finalNormEnd)
            return AnchorResult(
                rawExactMatch = false,
                verified = true,
                canonicalAnchor = trimmed,
                startUtf16 = rawStart,
                endUtf16 = rawEnd,
            )
        }

        return AnchorResult(rawExactMatch = false, verified = false, canonicalAnchor = "")
    }

    fun locatePassageInPage(
        bookId: String,
        page: BookPage,
        quoteAnchor: String,
        timestampLabel: String? = null,
    ): BookPassage? {
        val text = page.text
        if (text.isBlank()) return null
        val verified = verifyOrSnapAnchor(quoteAnchor, text)
        if (verified.verified && verified.startUtf16 in 0 until verified.endUtf16 && verified.endUtf16 <= text.length) {
            return BookPassage(
                book = bookId,
                page = page.number,
                start = verified.startUtf16,
                end = verified.endUtf16,
                text = text.substring(verified.startUtf16, verified.endUtf16),
            )
        }
        if (!timestampLabel.isNullOrBlank()) {
            CourseTranscriptParser.findTimestampAnchor(listOf(page), timestampLabel)?.let { anchor ->
                if (anchor.startUtf16 in 0 until anchor.endUtf16 && anchor.endUtf16 <= text.length) {
                    return BookPassage(
                        book = bookId,
                        page = page.number,
                        start = anchor.startUtf16,
                        end = anchor.endUtf16,
                        text = text.substring(anchor.startUtf16, anchor.endUtf16),
                    )
                }
            }
        }
        val lineEnd = text.indexOf('\n').let { if (it > 0) minOf(it, 60) else minOf(text.length, 60) }
        return if (lineEnd > 0) BookPassage(bookId, page.number, 0, lineEnd, text.substring(0, lineEnd)) else null
    }

    fun extractNearestTimestamp(rawPageText: String, canonicalAnchor: String): String? {
        if (canonicalAnchor.isBlank() || "[" !in rawPageText) return null
        val normAnchor = normalizeText(canonicalAnchor)
        val lines = rawPageText.replace("\r\n", "\n").lines()
        var currentTs: String? = null
        for (line in lines) {
            val tsMatch = inlineTimestampRegex.findAll(line).lastOrNull()
            if (tsMatch != null) {
                currentTs = canonicalTimestampLabel(tsMatch.groupValues[1]) ?: currentTs
            }
            val normLine = normalizeText(line)
            if (normAnchor.length >= 6 && normLine.contains(normAnchor, ignoreCase = true)) {
                return currentTs
            }
        }
        return null
    }

    fun verifyResponseLocally(
        collectionId: String,
        question: String,
        responseJson: JsonObject,
        blockMap: Map<String, CollectionQaBlock>,
        rawPagesByKey: Map<Pair<String, Int>, String>,
        shortLabelByBookId: Map<String, String>,
        docLabelByBookId: Map<String, String>,
        offlineDiscrepancies: List<MatchedOfflineDiscrepancy>,
    ): CollectionQaTurn {
        val rawSentences = responseJson.arr("sentences").mapNotNull { it as? JsonObject }
        val verifiedSentences = mutableListOf<CollectionQaSentence>()
        var dropped = responseJson.intOrNull("droppedUnverifiedCount") ?: 0

        for (item in rawSentences) {
            val claim = item.str("claim").trim()
            val bid = item.str("blockId").trim()
            val block = blockMap[bid]
            val bookId = item.str("bookId").trim().ifBlank { block?.bookId.orEmpty() }
            val page = item.intOrNull("page") ?: block?.page ?: 0
            val quote = item.str("quoteAnchor").trim()
            val rawPageText = rawPagesByKey[bookId to page] ?: block?.text.orEmpty()
            if (claim.isBlank() || bookId.isBlank() || page <= 0 || rawPageText.isBlank()) {
                dropped++
                continue
            }
            val gate = verifyOrSnapAnchor(quote, rawPageText)
            if (!gate.verified) {
                dropped++
                continue
            }
            val ts = item.str("timestampLabel").trim().takeIf { it.isNotEmpty() }
                ?: extractNearestTimestamp(rawPageText, gate.canonicalAnchor)
                ?: block?.timestampLabel
            val docLabel = item.str("docLabel").trim().ifBlank {
                docLabelByBookId[bookId] ?: block?.docLabel.orEmpty()
            }
            val shortLabel = shortLabelByBookId[bookId] ?: block?.shortLabel ?: docLabel.take(10)
            verifiedSentences += CollectionQaSentence(
                claim = claim,
                citation = CollectionQaCitation(
                    collectionId = collectionId,
                    bookId = bookId,
                    docLabel = docLabel,
                    shortLabel = shortLabel,
                    page = page,
                    timestampLabel = ts,
                    quoteAnchor = gate.canonicalAnchor,
                    startUtf16 = gate.startUtf16,
                    endUtf16 = gate.endUtf16,
                ),
                rawExactMatch = gate.rawExactMatch && item.boolOrFalse("rawExactMatch"),
            )
        }

        val conflictCards = mutableListOf<CollectionQaConflictCard>()
        val seenIds = HashSet<String>()
        for (el in responseJson.arr("conflicts")) {
            val obj = el as? JsonObject ?: continue
            val cid = obj.str("id").trim().ifBlank { "conf-${conflictCards.size}" }
            val verifiedCites = verifyConflictCitations(
                collectionId = collectionId,
                rawCites = obj.arr("citations").mapNotNull { it as? JsonObject },
                rawPagesByKey = rawPagesByKey,
                shortLabelByBookId = shortLabelByBookId,
                docLabelByBookId = docLabelByBookId,
            )
            conflictCards += CollectionQaConflictCard(
                id = cid,
                point = obj.str("point"),
                authoritativeView = obj.str("authoritativeView"),
                secondaryView = obj.str("secondaryView"),
                advice = obj.str("advice"),
                citations = verifiedCites,
            )
            seenIds += cid
        }

        for (disc in offlineDiscrepancies) {
            if (!disc.directAspectMatch || disc.id in seenIds) continue
            val verifiedCites = disc.citations.mapNotNull { c ->
                val rawPageText = rawPagesByKey[c.bookId to c.page] ?: return@mapNotNull null
                val gate = verifyOrSnapAnchor(c.quote, rawPageText)
                if (!gate.verified) return@mapNotNull null
                val ts = c.timestampLabel ?: extractNearestTimestamp(rawPageText, gate.canonicalAnchor)
                val docLabel = docLabelByBookId[c.bookId] ?: c.bookTitle
                val shortLabel = shortLabelByBookId[c.bookId] ?: c.bookTitle.take(10)
                CollectionQaCitation(
                    collectionId = collectionId,
                    bookId = c.bookId,
                    docLabel = docLabel,
                    shortLabel = shortLabel,
                    page = c.page,
                    timestampLabel = ts,
                    quoteAnchor = gate.canonicalAnchor,
                    startUtf16 = gate.startUtf16,
                    endUtf16 = gate.endUtf16,
                )
            }
            conflictCards += CollectionQaConflictCard(
                id = disc.id,
                point = disc.point,
                authoritativeView = disc.authoritativeView,
                secondaryView = disc.secondaryView,
                advice = disc.advice,
                citations = verifiedCites,
            )
            seenIds += disc.id
        }

        val serverHasConflict = responseJson.boolOrFalse("hasMaterialConflict")
        val rawSummary = responseJson.str("conflictSummaryZh").trim()
        val effectiveSummary = when {
            rawSummary.isNotBlank() -> rawSummary
            conflictCards.isNotEmpty() -> conflictCards.first().let { c ->
                buildString {
                    append(c.point)
                    if (c.authoritativeView.isNotBlank()) append("：").append(c.authoritativeView)
                    if (c.secondaryView.isNotBlank()) append("；另一材料：").append(c.secondaryView)
                }
            }
            else -> ""
        }
        val hasConflict = conflictCards.isNotEmpty() || (serverHasConflict && effectiveSummary.length > 5)

        val fallback = if (verifiedSentences.isEmpty()) {
            "这批检索页里未核验到可直接跳页的原文依据，请换个具体关键词再试。"
        } else null

        return CollectionQaTurn(
            question = question,
            sentences = verifiedSentences,
            hasMaterialConflict = hasConflict,
            conflictSummaryZh = if (hasConflict) effectiveSummary else "",
            conflictCards = conflictCards,
            fallbackMessage = fallback,
            droppedUnverifiedCount = dropped,
        )
    }

    /**
     * 阶段 2（TON-1978）按立场分层回答的客户端逐句复核：
     * - 校验每一句的 UTF-16 原句切片、小说有效进度页码边界；
     * - 任一「本书」块中有句子出处无法核对时整块丢弃（fail-closed）；
     * - 同时兼容 high_risk 直接回答与缺失阅读记录提示。
     */
    fun verifyStanceCollectionResponse(
        collectionId: String,
        question: String,
        responseJson: JsonObject,
        rawPagesByKey: Map<Pair<String, Int>, String>,
        bookStances: List<CollectionBookStancePayload>,
        shortLabelByBookId: Map<String, String>,
        docLabelByBookId: Map<String, String>,
    ): CollectionQaTurn? {
        val decoded = StanceAnswerCodec.decodeStanceAnswer(responseJson) ?: return null
        val stanceByBookId = bookStances.associateBy { it.bookId }
        val verifiedLayers = mutableListOf<StanceLayer>()
        val flatSentences = mutableListOf<CollectionQaSentence>()
        var extraDropped = 0

        for (layer in decoded.layers) {
            when (layer) {
                is StanceLayer.Outside, is StanceLayer.Guess -> {
                    if (!decoded.highRisk) verifiedLayers += layer
                }
                is StanceLayer.Book -> {
                    var blockValid = true
                    val verifiedBookSents = mutableListOf<StanceBookSentence>()
                    for (sent in layer.sentences) {
                        val verifiedCites = mutableListOf<StanceCitation>()
                        for (cite in sent.citations) {
                            val bookId = cite.bookId.ifBlank {
                                layer.bookId.ifBlank { cite.docId.substringBefore(":p") }
                            }
                            val bs = stanceByBookId[bookId]
                            if (bs != null && BookStanceGuard.FICTION in bs.guards) {
                                val rtp = bs.readThroughPage
                                if (bs.missingReadingRecord || rtp == null || cite.page > rtp) {
                                    blockValid = false
                                    break
                                }
                            }
                            val rawPageText = rawPagesByKey[bookId to cite.page]
                            if (rawPageText == null) {
                                blockValid = false
                                break
                            }
                            val exact = cite.startUtf16 in 0 until cite.endUtf16 &&
                                cite.endUtf16 <= rawPageText.length &&
                                rawPageText.substring(cite.startUtf16, cite.endUtf16) == cite.quote
                            val finalCite = if (exact) {
                                cite.copy(
                                    bookId = bookId,
                                    bookTitle = cite.bookTitle.ifBlank { bs?.title.orEmpty() },
                                )
                            } else {
                                val snapped = verifyOrSnapAnchor(cite.quote, rawPageText)
                                if (!snapped.verified || snapped.endUtf16 <= snapped.startUtf16) {
                                    blockValid = false
                                    break
                                }
                                cite.copy(
                                    startUtf16 = snapped.startUtf16,
                                    endUtf16 = snapped.endUtf16,
                                    quote = rawPageText.substring(snapped.startUtf16, snapped.endUtf16),
                                    bookId = bookId,
                                    bookTitle = cite.bookTitle.ifBlank { bs?.title.orEmpty() },
                                )
                            }
                            verifiedCites += finalCite
                        }
                        if (!blockValid || verifiedCites.isEmpty()) {
                            blockValid = false
                            break
                        }
                        verifiedBookSents += StanceBookSentence(sent.text, verifiedCites)
                    }
                    if (!blockValid || verifiedBookSents.isEmpty()) {
                        extraDropped += layer.sentences.size
                        continue
                    }
                    val resolvedBookId = layer.bookId.ifBlank {
                        verifiedBookSents.first().citations.first().bookId
                    }
                    val resolvedBookTitle = layer.bookTitle.ifBlank {
                        stanceByBookId[resolvedBookId]?.title.orEmpty()
                    }
                    verifiedLayers += layer.copy(
                        sentences = verifiedBookSents,
                        bookId = resolvedBookId,
                        bookTitle = resolvedBookTitle,
                    )
                    for (vs in verifiedBookSents) {
                        val c = vs.citations.first()
                        val bTitle = c.bookTitle.ifBlank { resolvedBookTitle }
                        flatSentences += CollectionQaSentence(
                            claim = vs.text,
                            citation = CollectionQaCitation(
                                collectionId = collectionId,
                                bookId = c.bookId,
                                docLabel = docLabelByBookId[c.bookId] ?: bTitle,
                                shortLabel = shortLabelByBookId[c.bookId] ?: bTitle.take(12),
                                page = c.page,
                                quoteAnchor = c.quote,
                                startUtf16 = c.startUtf16,
                                endUtf16 = c.endUtf16,
                            ),
                            rawExactMatch = true,
                        )
                    }
                }
            }
        }

        val allRelevantFiction = bookStances.isNotEmpty() &&
            bookStances.all { BookStanceGuard.FICTION in it.guards }
        val finalNoBookNotice = if (flatSentences.isEmpty()) {
            decoded.noBookNotice ?: if (!decoded.highRisk && allRelevantFiction) {
                BookStanceCopy.NO_BOOK_FICTION
            } else {
                BookStanceCopy.NO_BOOK_DIRECT
            }
        } else null

        val verifiedAnswer = decoded.copy(
            layers = verifiedLayers,
            noBookNotice = finalNoBookNotice,
            droppedSentences = decoded.droppedSentences + extraDropped,
        )
        val fallbackMsg = buildString {
            decoded.missingRecordNotices.forEach { appendLine(it) }
            if (flatSentences.isEmpty() && !finalNoBookNotice.isNullOrBlank()) append(finalNoBookNotice)
        }.trim().takeIf { it.isNotEmpty() }

        return CollectionQaTurn(
            question = question,
            sentences = flatSentences,
            hasMaterialConflict = false,
            conflictSummaryZh = "",
            conflictCards = emptyList(),
            fallbackMessage = fallbackMsg,
            droppedUnverifiedCount = verifiedAnswer.droppedSentences,
            stanceAnswer = verifiedAnswer,
        )
    }

    private fun verifyConflictCitations(
        collectionId: String,
        rawCites: List<JsonObject>,
        rawPagesByKey: Map<Pair<String, Int>, String>,
        shortLabelByBookId: Map<String, String>,
        docLabelByBookId: Map<String, String>,
    ): List<CollectionQaCitation> = rawCites.mapNotNull { obj ->
        val bookId = obj.str("bookId").trim()
        val page = obj.intOrNull("page") ?: return@mapNotNull null
        val quote = obj.str("quoteAnchor").trim()
        val rawPageText = rawPagesByKey[bookId to page] ?: return@mapNotNull null
        val gate = verifyOrSnapAnchor(quote, rawPageText)
        if (!gate.verified) return@mapNotNull null
        val ts = obj.str("timestampLabel").trim().takeIf { it.isNotEmpty() }
            ?: extractNearestTimestamp(rawPageText, gate.canonicalAnchor)
        val docLabel = obj.str("docLabel").trim().ifBlank { docLabelByBookId[bookId].orEmpty() }
        val shortLabel = shortLabelByBookId[bookId] ?: docLabel.take(10)
        CollectionQaCitation(
            collectionId = collectionId,
            bookId = bookId,
            docLabel = docLabel,
            shortLabel = shortLabel,
            page = page,
            timestampLabel = ts,
            quoteAnchor = gate.canonicalAnchor,
            startUtf16 = gate.startUtf16,
            endUtf16 = gate.endUtf16,
        )
    }

    private fun mapNormSpanToRaw(
        mapped: NormalizedWithOffsets,
        rawText: String,
        normStart: Int,
        normEnd: Int,
    ): Pair<Int, Int> {
        if (mapped.rawStartOffsets.isEmpty() || normStart < 0 || normEnd <= normStart || normEnd > mapped.rawEndOffsets.size) {
            return 0 to 0
        }
        val s = mapped.rawStartOffsets[normStart].coerceIn(0, rawText.length)
        val e = mapped.rawEndOffsets[normEnd - 1].coerceIn(s, rawText.length)
        return s to e
    }

    private fun trimPureProseAnchor(slice: String): String {
        if (!mathSpacingNoiseRegex.containsMatchIn(slice)) return slice
        val proseParts = mathSpacingSplitRegex.split(slice)
            .map { it.trim() }
            .filter { part ->
                part.length >= 16 && part.split(whitespaceRegex).count { it.isNotEmpty() } >= 3
            }
        return proseParts.maxByOrNull { it.length } ?: slice
    }

    private fun canonicalTimestampLabel(raw: String): String? {
        val parts = raw.trim().split(':')
        if (parts.size !in 2..3) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return if (nums.size == 3) {
            "%02d:%02d:%02d".format(nums[0], nums[1], nums[2])
        } else {
            "%02d:%02d".format(nums[0], nums[1])
        }
    }
}
