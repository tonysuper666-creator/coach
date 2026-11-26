package com.coach.desktop.books

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** 同一概念在各书里的对照：说法一致 / 角度不同 / 有冲突。 */
internal enum class SayingStance(val wire: String, val label: String) {
    SAME("SAME", "说法一致"),
    ANGLE("ANGLE", "角度不同"),
    CONFLICT("CONFLICT", "有冲突"),
    ;

    companion object {
        fun fromWire(raw: String?): SayingStance {
            val text = raw?.trim().orEmpty()
            return when {
                text.equals("SAME", true) || text.equals("CONSISTENT", true) ||
                    text.equals("CONSISTENT_DEEPENING", true) || text == "说法一致" -> SAME
                text.equals("CONFLICT", true) || text.equals("OPPOSING", true) ||
                    text.equals("OPPOSING_VIEW", true) || text == "有冲突" -> CONFLICT
                text.equals("ANGLE", true) || text.equals("DIFFERENT", true) ||
                    text.equals("NOTATION_OR_BOUNDARY_DIFF", true) ||
                    text.equals("EMPIRICAL_CHALLENGE", true) ||
                    text.equals("PREREQUISITE", true) || text == "角度不同" -> ANGLE
                else -> ANGLE
            }
        }

        fun fromRelation(relation: ConceptRelationType): SayingStance = when (relation) {
            ConceptRelationType.OPPOSING_VIEW -> CONFLICT
            ConceptRelationType.CONSISTENT_DEEPENING -> SAME
            ConceptRelationType.EMPIRICAL_CHALLENGE,
            ConceptRelationType.NOTATION_OR_BOUNDARY_DIFF,
            ConceptRelationType.PREREQUISITE,
            -> ANGLE
        }
    }
}

internal data class ConceptNameRef(
    val conceptId: String,
    val name: String,
    val aliases: List<String> = emptyList(),
)

/** 送去核对之前，一本书里已经对上原文的那一处。 */
internal data class SayingCandidate(
    val bookId: String,
    val bookTitle: String,
    val mentionId: String,
    val page: Int,
    val offsetUtf16: Int,
    val quoteAnchor: String,
    val claimSummary: String,
    val stance: SayingStance,
    val anchor: Boolean,
)

internal data class ParsedBookSaying(
    val bookId: String,
    val keep: Boolean,
    val statement: String,
    val stance: SayingStance,
)

internal data class StoredSaying(
    val bookId: String,
    val mentionId: String,
    val bookTitle: String,
    val statement: String,
    val stance: SayingStance,
    val page: Int,
    val offsetUtf16: Int,
    val quoteAnchor: String,
    val kept: Boolean,
)

internal data class ConceptSayingColumn(
    val bookId: String,
    val bookTitle: String,
    val statement: String,
    val stance: SayingStance,
    val page: Int,
    val offsetUtf16: Int,
    val quoteAnchor: String,
    val mentionId: String,
)

/**
 * 点开一个概念后并排看的结果。
 * [pending] 为真时列里先用已入库的说法，Haiku 批次回来后换成核对过的一句话，并按输入散列留下，再打开不再请求。
 */
internal data class ConceptSayingsBoard(
    val conceptId: String,
    val conceptName: String,
    val anchorBookId: String,
    val inputHash: String,
    val columns: List<ConceptSayingColumn>,
    val pending: Boolean,
    val candidates: List<SayingCandidate>,
) {
    val onlyThisBook: Boolean get() = columns.none { it.bookId != anchorBookId }

    fun toRequest(): JsonObject = buildJsonObject {
        put("conceptId", conceptId)
        put("conceptName", conceptName.take(80))
        put("inputHash", inputHash)
        put("books", buildJsonArray {
            for (c in candidates) {
                add(buildJsonObject {
                    put("bookId", c.bookId)
                    put("bookTitle", c.bookTitle.take(80))
                    put("page", c.page)
                    put("quote", c.quoteAnchor.take(400))
                    put("claim", c.claimSummary.take(180))
                })
            }
        })
    }

    companion object {
        const val ONLY_THIS_BOOK = "库里只有这一本讲到"
    }
}

internal object ConceptSayingLogic {
    const val ONLY_THIS_BOOK = ConceptSayingsBoard.ONLY_THIS_BOOK
    const val RETRY_LATER = "稍后再试"
    const val NO_MATCH = "这几本书里还对不上这个说法。"
    const val RETRY_HOLD_MS = 10 * 60_000L

    fun inputHash(conceptId: String, candidates: List<SayingCandidate>): String {
        val body = buildString {
            append(conceptId.trim())
            append('\n')
            for (c in candidates.sortedBy { it.bookId }) {
                append(c.bookId)
                append('|')
                append(c.mentionId)
                append('|')
                append(c.quoteAnchor)
                append('\n')
            }
        }
        return sha256(body).take(32)
    }

    /** 选中的是一个词或短语，才提供「别的书怎么说」。整段文字不查。 */
    fun canAskOtherBooks(selected: String): Boolean {
        val key = normalize(selected)
        return key.length in 2..40 && key.any { it.isLetter() }
    }

    /**
     * 按概念名和同义说法匹配。选中文字不必和概念名一字不差：
     * 多出来的词、词尾单复数、写在别名里的说法都可以。
     * 整段（超过 40 字）和单字母仍然对不上。
     */
    fun matchConcept(selected: String, names: List<ConceptNameRef>): String? {
        val key = normalize(selected)
        if (key.length !in 2..40) return null
        val stemmedKey = stemPhrase(key)
        var bestId: String? = null
        var bestScore = 0
        for (ref in names) {
            val labels = (listOf(ref.name) + ref.aliases).map { normalize(it) }.filter { it.length >= 2 }
            for (label in labels) {
                val stemmedLabel = stemPhrase(label)
                val score = when {
                    key == label || stemmedKey == stemmedLabel -> 1_000 + label.length
                    containsPhrase(key, label) || containsPhrase(stemmedKey, stemmedLabel) -> 500 + label.length
                    stemmedKey.length >= 4 && (containsPhrase(label, key) || containsPhrase(stemmedLabel, stemmedKey)) ->
                        200 + stemmedKey.length
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    bestId = ref.conceptId
                }
            }
        }
        return bestId
    }

    /**
     * 只有这一页的这段原文还在，才返回可高亮的区间。对不上就返回 null，绝不去标页上另一句。
     */
    fun passageIfExact(bookId: String, page: Int, pageText: String, offsetUtf16: Int, quote: String): BookPassage? {
        val verified = ConceptAnchorVerifier.verify(pageText, quote, offsetUtf16) ?: return null
        val start = verified.offsetUtf16
        val end = start + verified.quoteAnchor.length
        if (start !in 0 until end || end > pageText.length) return null
        if (pageText.substring(start, end) != verified.quoteAnchor) return null
        return BookPassage(bookId, page, start, end, verified.quoteAnchor)
    }

    fun acceptableStatement(statement: String, quote: String): Boolean {
        val text = statement.trim()
        if (text.length !in 4..80) return false
        if (text.any { it == '\n' || it == '\r' }) return false
        if (text.count { it == '。' || it == '.' } > 1) return false
        val quoted = quote.trim()
        if (quoted.isNotEmpty() && text.equals(quoted, ignoreCase = true)) return false
        if (quoted.length >= 12 && text.contains(quoted, ignoreCase = true)) return false
        return true
    }

    /**
     * 解析批次返回。返回 null 表示这轮不能入库（没有 `books`，或没有一本对得上候选）。
     * `keep=false` 或说法不合格的书会留下，但 [ParsedBookSaying.keep] 为 false，并排时丢掉。
     */
    fun parse(body: JsonObject, candidates: List<SayingCandidate>): List<ParsedBookSaying>? {
        val arr = body["books"] as? JsonArray ?: return null
        if (arr.isEmpty()) return emptyList()
        val known = candidates.associateBy { it.bookId }
        val seen = HashSet<String>()
        val parsed = mutableListOf<ParsedBookSaying>()
        for (el in arr) {
            val obj = el as? JsonObject ?: continue
            val bookId = (obj["bookId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val candidate = known[bookId] ?: continue
            if (!seen.add(bookId)) continue
            val keepPrim = obj["keep"] as? JsonPrimitive
            val keepRaw = keepPrim?.booleanOrNull
                ?: (keepPrim?.contentOrNull?.equals("true", ignoreCase = true) == true ||
                    keepPrim?.contentOrNull == "1")
            val statement = (obj["statement"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val stance = SayingStance.fromWire((obj["stance"] as? JsonPrimitive)?.contentOrNull)
            val keep = keepRaw && acceptableStatement(statement, candidate.quoteAnchor)
            parsed += ParsedBookSaying(
                bookId = bookId,
                keep = keep,
                statement = if (keep) statement else "",
                stance = stance,
            )
        }
        return parsed.takeIf { it.isNotEmpty() }
    }

    fun rows(board: ConceptSayingsBoard, parsed: List<ParsedBookSaying>): List<StoredSaying> {
        val byId = parsed.associateBy { it.bookId }
        return board.candidates.map { candidate ->
            val decision = byId[candidate.bookId]
            val kept = if (candidate.anchor) true else decision?.keep == true
            val statement = when {
                decision != null && decision.keep && decision.statement.isNotBlank() -> decision.statement
                else -> oneLine(candidate.claimSummary.ifBlank { candidate.quoteAnchor })
            }
            val stance = when {
                decision != null && (decision.keep || candidate.anchor) -> decision.stance
                candidate.anchor -> SayingStance.SAME
                else -> candidate.stance
            }
            StoredSaying(
                bookId = candidate.bookId,
                mentionId = candidate.mentionId,
                bookTitle = candidate.bookTitle,
                statement = statement,
                stance = stance,
                page = candidate.page,
                offsetUtf16 = candidate.offsetUtf16,
                quoteAnchor = candidate.quoteAnchor,
                kept = kept,
            )
        }
    }

    fun columnsFrom(anchorBookId: String, rows: List<StoredSaying>): List<ConceptSayingColumn> =
        rows.filter { it.kept }
            .sortedWith(compareBy<StoredSaying> { if (it.bookId == anchorBookId) 0 else 1 }.thenBy { it.bookTitle })
            .map {
                ConceptSayingColumn(
                    bookId = it.bookId,
                    bookTitle = it.bookTitle,
                    statement = it.statement,
                    stance = it.stance,
                    page = it.page,
                    offsetUtf16 = it.offsetUtf16,
                    quoteAnchor = it.quoteAnchor,
                    mentionId = it.mentionId,
                )
            }

    fun oneLine(raw: String): String {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return ""
        val cut = if (text.length <= 80) text else text.take(79) + "。"
        return cut
    }

    /** 伴读讲解关闭、本机已经核对过、没有第二本书，或短时间内刚失败过时，不把原文发去远端。 */
    fun mayRequestConceptSayings(
        board: ConceptSayingsBoard?,
        aiExplainEnabled: Boolean,
        holdUntilEpochMs: Long? = null,
        nowEpochMs: Long = 0L,
    ): Boolean {
        if (!aiExplainEnabled || board == null || !board.pending || board.candidates.size < 2) return false
        if (holdUntilEpochMs != null && nowEpochMs < holdUntilEpochMs) return false
        return true
    }

    private fun normalize(raw: String): String =
        raw.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun stemPhrase(raw: String): String =
        raw.split(' ').filter { it.isNotBlank() }.joinToString(" ") { stemToken(it) }

    private fun stemToken(word: String): String = when {
        word.length >= 6 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length >= 5 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is") ->
            word.dropLast(1)
        else -> word
    }

    private fun containsPhrase(haystack: String, needle: String): Boolean {
        if (needle.length < 2 || haystack.length < needle.length) return false
        val cjk = needle.any { it in '\u4e00'..'\u9fff' } || haystack.any { it in '\u4e00'..'\u9fff' }
        if (cjk) return needle in haystack
        return " $needle " in " $haystack "
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * 打开概念时：本机已有同一输入散列就直接用；否则请 Haiku 批次核对，核对完写入本机。
 * 伴读讲解关闭时不发请求，只留下本机已有的说法和标签。
 * 批次还在排队就过一会儿用同一请求再问，桥上的批次键不变，不会另开一单。
 */
internal suspend fun settleConceptSayings(
    board: ConceptSayingsBoard,
    fetch: suspend (JsonObject) -> JsonObject?,
    save: suspend (ConceptSayingsBoard, List<ParsedBookSaying>) -> Unit,
    reload: suspend () -> ConceptSayingsBoard?,
    delayMillis: suspend (Long) -> Unit,
    remoteAllowed: Boolean,
    maxPolls: Int = 4,
    onUnavailable: suspend () -> Unit = {},
): ConceptSayingsBoard {
    if (!ConceptSayingLogic.mayRequestConceptSayings(board, remoteAllowed)) return board
    val request = board.toRequest()
    var polls = 0
    while (polls < maxPolls) {
        polls++
        val response = runCatching { fetch(request) }.getOrNull()
        if (response == null) {
            onUnavailable()
            return board
        }
        val pending = (response["pending"] as? JsonPrimitive)?.booleanOrNull == true
        if (pending) {
            val wait = (response["retryAfterMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 15_000L
            delayMillis(wait.coerceIn(1_000L, 20_000L))
            continue
        }
        val parsed = ConceptSayingLogic.parse(response, board.candidates)
        if (parsed == null) {
            onUnavailable()
            return board
        }
        save(board, parsed)
        return reload() ?: board
    }
    return board
}
