package com.coach.desktop.books

import com.coach.desktop.books.CollectionQaJson.arr
import com.coach.desktop.books.CollectionQaJson.str
import kotlinx.serialization.json.JsonObject
import java.sql.Connection

/**
 * 在「我的库」里提问。
 * 只从本机的页正文、批注、高亮，以及已经入库的笔记里取句。
 * 每一句都能在原页上逐字对上；对不上的句子不进回答。没有对得上的，就直说没有。
 */
internal enum class LibraryAskSource { PAGE, ANNOTATION, NOTE }

internal enum class LibraryAskKind { FOUND, MISSING, VAGUE }

internal data class LibraryAskClaim(
    val claim: String,
    val bookId: String,
    val bookTitle: String,
    val page: Int,
    val startUtf16: Int,
    val endUtf16: Int,
    val quote: String,
    val source: LibraryAskSource,
    val score: Int = 0,
) {
    fun citeLabel(): String = "《$bookTitle》第 $page 页"

    fun shownText(): String = when (source) {
        LibraryAskSource.ANNOTATION -> "批注：$claim"
        LibraryAskSource.NOTE -> "笔记：$claim"
        LibraryAskSource.PAGE -> claim
    }
}

internal data class LibraryAskAnswer(
    val question: String,
    val claims: List<LibraryAskClaim>,
    val kind: LibraryAskKind,
    val searchedNotes: Boolean,
)

internal data class LibraryAskProse(
    val claims: List<LibraryAskClaim>,
    val unreachable: Boolean,
)

internal object LibraryAsk {
    const val MISSING = "这几本书和批注里没有讲到。没有另写一条。"
    const val MISSING_WITH_NOTES = "这几本书、批注和笔记里没有讲到。没有另写一条。"
    const val VAGUE = "把要问的概念写具体一点，例如机会成本。"
    const val NETWORK = "整理成一段话需要联网。下面是对得上的原句，点一下就回到那一句。"
    const val MAX_CLAIMS = 4

    private val enToken = Regex("""[A-Za-z][A-Za-z'-]{3,}""")
    private val zhToken = Regex("""[\u4e00-\u9fff]{2,8}""")
    private val zhTrim = listOf("是什么", "怎么讲的", "怎么讲", "如何定义", "如何", "为什么", "有没有", "讲过", "吗", "呢")

    private val enStop = setOf(
        "what", "when", "where", "which", "who", "whom", "whose", "why", "how", "does", "did",
        "this", "that", "with", "from", "into", "about", "your", "have", "been", "were", "will",
        "would", "could", "should", "please", "explain", "define", "mean", "means", "meaning",
        "difference", "between", "compare", "book", "books", "chapter", "textbook", "principle",
        "principles", "according", "tell", "says", "said", "talk", "talks", "talked", "mention",
        "mentions", "mentioned", "write", "written", "describe", "described", "there", "their",
        "these", "those", "then", "than", "them", "they", "also", "only", "very", "more", "most",
        "some", "such", "each", "other", "just", "here", "over", "under", "after", "before",
    )

    private val zhStop = setOf(
        "什么", "怎么", "如何", "为什么", "是否", "哪个", "哪些", "一下", "这个", "那个",
        "我们", "你们", "他们", "可以", "就是", "还是", "以及", "请问", "一下",
    )

    fun answer(db: Connection, rawQuestion: String): LibraryAskAnswer {
        val question = rawQuestion.trim()
        val terms = contentTerms(question)
        if (terms.isEmpty()) {
            return LibraryAskAnswer(question, emptyList(), LibraryAskKind.VAGUE, searchedNotes = false)
        }
        val books = listLibraryBooks(db).associateBy { it.id }
        if (books.isEmpty()) {
            return LibraryAskAnswer(question, emptyList(), LibraryAskKind.MISSING, searchedNotes = false)
        }
        val need = terms.sortedByDescending { it.length }.take(4)
        val found = ArrayList<LibraryAskClaim>()
        for (book in books.values) {
            found += pageClaims(db, book, need)
        }
        var searchedNotes = false
        found += annotationClaims(db, books, need)
        val notes = noteClaims(db, books, need)
        searchedNotes = notes != null
        if (notes != null) found += notes
        val ranked = rank(found)
        val kind = if (ranked.isEmpty()) LibraryAskKind.MISSING else LibraryAskKind.FOUND
        return LibraryAskAnswer(question, ranked, kind, searchedNotes)
    }

    fun missingCopy(answer: LibraryAskAnswer): String =
        if (answer.searchedNotes) MISSING_WITH_NOTES else MISSING

    suspend fun polish(
        question: String,
        claims: List<LibraryAskClaim>,
        remote: suspend (JsonObject) -> CourseServerCallResult,
    ): LibraryAskProse {
        if (claims.isEmpty()) return LibraryAskProse(emptyList(), unreachable = false)
        val blocks = claims.mapIndexed { index, claim ->
            CollectionQaBlock(
                blockId = "B${index + 1}",
                bookId = claim.bookId,
                docLabel = claim.bookTitle,
                shortLabel = claim.bookTitle.take(16),
                authorityRank = 1,
                page = claim.page,
                text = claim.quote,
            )
        }
        val payload = CollectionQaJson.encodeRequest(
            requestId = "library-ask",
            collection = CollectionQaVirtualCollections.readLibraryCollection(),
            question = question,
            blocks = blocks,
            discrepancies = emptyList(),
            shortLabelByBookId = claims.associate { it.bookId to it.bookTitle.take(16) },
        )
        val result = runCatching { remote(payload) }.getOrElse {
            return LibraryAskProse(emptyList(), unreachable = true)
        }
        if (result.body == null) {
            return LibraryAskProse(emptyList(), unreachable = result.trouble == ExplainTrouble.Unreachable)
        }
        return LibraryAskProse(acceptProse(result.body, claims), unreachable = false)
    }

    internal fun contentTerms(question: String): List<String> {
        val english = enToken.findAll(question)
            .map { it.value.lowercase().trim('\'') }
            .filter { it.length >= 4 && it !in enStop }
        var chineseSource = question
        for (suffix in zhTrim) chineseSource = chineseSource.replace(suffix, " ")
        val chinese = zhToken.findAll(chineseSource)
            .map { it.value }
            .filter { it !in zhStop && it.length >= 2 }
        return (english + chinese).distinct().toList()
    }

    internal fun acceptProse(body: JsonObject, claims: List<LibraryAskClaim>): List<LibraryAskClaim> {
        val byBlock = claims.mapIndexed { index, claim -> "B${index + 1}" to claim }.toMap()
        val accepted = ArrayList<LibraryAskClaim>()
        for (item in body.arr("sentences")) {
            val obj = item as? JsonObject ?: continue
            val text = obj.str("claim").trim()
            val blockId = obj.str("blockId").trim()
            val quoteAnchor = obj.str("quoteAnchor").trim()
            val local = byBlock[blockId] ?: continue
            if (text.length !in 2..400 || refusal(text)) continue
            val gate = SentenceCitationGate.verifyOrSnapAnchor(quoteAnchor, local.quote)
            if (!gate.verified) continue
            if (!supports(text, local.quote)) continue
            accepted += local.copy(claim = text)
        }
        return accepted.distinctBy { it.bookId to it.page to it.startUtf16 to it.claim }
    }

    private fun refusal(text: String): Boolean =
        listOf("没有讲", "没有找到", "无法回答", "不足以回答", "材料里没有").any { it in text }

    private fun supports(claim: String, quote: String): Boolean {
        val needles = contentTerms(quote).filter { it.length >= 5 }.take(4)
        if (needles.isEmpty()) return quote.length >= 8 && claim.contains(quote.take(8), ignoreCase = true)
        return needles.any { claim.contains(it, ignoreCase = true) }
    }

    private fun pageClaims(db: Connection, book: LibraryBookHead, need: List<String>): List<LibraryAskClaim> {
        val anchor = need.maxBy { it.length }
        val pages = pagesContaining(db, book.id, anchor)
        if (pages.isEmpty()) return emptyList()
        val out = ArrayList<LibraryAskClaim>()
        db.prepareStatement("SELECT text FROM pages WHERE book=? AND page=?").use { sql ->
            for (page in pages) {
                sql.setString(1, book.id)
                sql.setInt(2, page)
                val text = sql.executeQuery().use { rows -> if (rows.next()) rows.getString(1).orEmpty() else "" }
                val hit = bestSentence(text, need) ?: continue
                out += LibraryAskClaim(
                    claim = hit.quote,
                    bookId = book.id,
                    bookTitle = book.title,
                    page = page,
                    startUtf16 = hit.start,
                    endUtf16 = hit.end,
                    quote = hit.quote,
                    source = LibraryAskSource.PAGE,
                    score = 100 + need.size * 5 - hit.quote.length / 80,
                )
            }
        }
        return out
    }

    private fun annotationClaims(
        db: Connection,
        books: Map<String, LibraryBookHead>,
        need: List<String>,
    ): List<LibraryAskClaim> {
        if (!BookSchemaMigration.tableExists(db, "book_notes")) return emptyList()
        val cols = BookSchemaMigration.tableColumns(db, "book_notes")
        if ("quote" !in cols || "note" !in cols || "page" !in cols) return emptyList()
        val deleted = if ("deleted" in cols) " WHERE deleted=0" else ""
        data class NoteRow(val bookId: String, val page: Int, val start: Int, val quote: String, val note: String)
        val notes = ArrayList<NoteRow>()
        db.createStatement().use { sql ->
            sql.executeQuery("SELECT book, page, start, end, quote, note FROM book_notes$deleted").use { rows ->
                while (rows.next()) {
                    notes += NoteRow(rows.getString(1), rows.getInt(2), rows.getInt(3), rows.getString(5).orEmpty(), rows.getString(6).orEmpty())
                }
            }
        }
        val out = ArrayList<LibraryAskClaim>()
        for (row in notes) {
            val book = books[row.bookId] ?: continue
            val note = row.note.trim()
            if (note.isEmpty() || !containsAll(note, need)) continue
            val grounded = groundedQuote(db, book.id, row.page, row.start, row.quote) ?: continue
            out += LibraryAskClaim(
                claim = note.replace(Regex("\\s+"), " ").take(280),
                bookId = book.id,
                bookTitle = book.title,
                page = grounded.page,
                startUtf16 = grounded.start,
                endUtf16 = grounded.end,
                quote = grounded.quote,
                source = LibraryAskSource.ANNOTATION,
                score = 90 + need.size,
            )
        }
        if (BookSchemaMigration.tableExists(db, "book_highlights")) {
            data class HighlightRow(val bookId: String, val page: Int, val start: Int, val quote: String)
            val highlights = ArrayList<HighlightRow>()
            db.createStatement().use { sql ->
                sql.executeQuery("SELECT book, page, start, end, quote FROM book_highlights").use { rows ->
                    while (rows.next()) {
                        highlights += HighlightRow(rows.getString(1), rows.getInt(2), rows.getInt(3), rows.getString(5).orEmpty())
                    }
                }
            }
            for (row in highlights) {
                val book = books[row.bookId] ?: continue
                if (!containsAll(row.quote, need)) continue
                val grounded = groundedQuote(db, book.id, row.page, row.start, row.quote) ?: continue
                out += LibraryAskClaim(
                    claim = grounded.quote,
                    bookId = book.id,
                    bookTitle = book.title,
                    page = grounded.page,
                    startUtf16 = grounded.start,
                    endUtf16 = grounded.end,
                    quote = grounded.quote,
                    source = LibraryAskSource.PAGE,
                    score = 95 + need.size,
                )
            }
        }
        return out
    }

    /** 返回 null 表示笔记表还不存在。空列表表示表在，但没有对得上的笔记。 */
    private fun noteClaims(
        db: Connection,
        books: Map<String, LibraryBookHead>,
        need: List<String>,
    ): List<LibraryAskClaim>? {
        if (!BookSchemaMigration.tableExists(db, "library_notes")) return null
        val cols = BookSchemaMigration.tableColumns(db, "library_notes")
        if (!setOf("book_id", "page", "quote", "body").all { it in cols }) return emptyList()
        val deleted = if ("deleted" in cols) " WHERE COALESCE(deleted,0)=0" else ""
        val startSql = if ("start_utf16" in cols) "start_utf16" else "NULL"
        data class NoteRow(val bookId: String, val page: Int, val start: Int?, val quote: String, val body: String)
        val rows = ArrayList<NoteRow>()
        db.createStatement().use { sql ->
            sql.executeQuery("SELECT book_id, page, $startSql, quote, body FROM library_notes$deleted").use { result ->
                while (result.next()) {
                    val start = result.getInt(3).takeIf { !result.wasNull() }
                    rows += NoteRow(result.getString(1), result.getInt(2), start, result.getString(4).orEmpty(), result.getString(5).orEmpty())
                }
            }
        }
        val out = ArrayList<LibraryAskClaim>()
        for (row in rows) {
            val book = books[row.bookId] ?: continue
            val body = row.body.trim()
            val haystack = if (containsAll(body, need)) body else if (containsAll(row.quote, need)) row.quote else continue
            val grounded = groundedQuote(db, book.id, row.page, row.start, row.quote.ifBlank { haystack }) ?: continue
            out += LibraryAskClaim(
                claim = haystack.replace(Regex("\\s+"), " ").take(280),
                bookId = book.id,
                bookTitle = book.title,
                page = grounded.page,
                startUtf16 = grounded.start,
                endUtf16 = grounded.end,
                quote = grounded.quote,
                source = LibraryAskSource.NOTE,
                score = 88 + need.size,
            )
        }
        return out
    }

    private data class Grounded(val page: Int, val start: Int, val end: Int, val quote: String)

    private fun groundedQuote(db: Connection, bookId: String, page: Int, claimedStart: Int?, quote: String): Grounded? {
        if (page <= 0 || quote.isBlank()) return null
        val text = db.prepareStatement("SELECT text FROM pages WHERE book=? AND page=?").use { sql ->
            sql.setString(1, bookId)
            sql.setInt(2, page)
            sql.executeQuery().use { rows -> if (rows.next()) rows.getString(1).orEmpty() else "" }
        }
        val verified = ConceptAnchorVerifier.verify(text, quote, claimedStart) ?: return null
        val end = verified.offsetUtf16 + verified.quoteAnchor.length
        if (end > text.length) return null
        return Grounded(page, verified.offsetUtf16, end, text.substring(verified.offsetUtf16, end))
    }

    private fun pagesContaining(db: Connection, bookId: String, term: String): List<Int> {
        val indexed = BookFtsStore.queryCandidatePages(db, bookId, term)
        if (!indexed.isNullOrEmpty()) return indexed.take(40)
        return runCatching {
            db.prepareStatement(
                "SELECT page FROM pages WHERE book=? AND instr(lower(text), lower(?)) > 0 ORDER BY page",
            ).use { sql ->
                sql.setString(1, bookId)
                sql.setString(2, term)
                sql.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getInt(1)) } }
            }
        }.getOrDefault(emptyList()).take(40)
    }

    private data class SentenceSlice(val start: Int, val end: Int, val quote: String)

    private fun bestSentence(text: String, need: List<String>): SentenceSlice? {
        var best: SentenceSlice? = null
        var start = 0
        fun consider(from: Int, to: Int) {
            if (to - from < 12) return
            var left = from
            var right = to
            while (left < right && text[left].isWhitespace()) left++
            while (right > left && text[right - 1].isWhitespace()) right--
            if (right - left < 12 || right - left > 360) return
            val quote = text.substring(left, right)
            if (!containsAll(quote, need)) return
            val candidate = SentenceSlice(left, right, quote)
            if (best == null || candidate.quote.length < best!!.quote.length) best = candidate
        }
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '\n' || ch == '.' || ch == '!' || ch == '?' || ch == '。' || ch == '！' || ch == '？') {
                val end = if (ch == '\n') i else i + 1
                consider(start, end)
                start = i + 1
            }
        }
        consider(start, text.length)
        return best
    }

    private fun containsAll(haystack: String, terms: List<String>): Boolean =
        terms.all { haystack.contains(it, ignoreCase = true) }

    private fun rank(found: List<LibraryAskClaim>): List<LibraryAskClaim> {
        val unique = found
            .filter { it.quote.isNotBlank() && it.endUtf16 > it.startUtf16 }
            .sortedByDescending { it.score }
            .distinctBy { it.bookId to it.page to it.startUtf16 to it.endUtf16 }
        if (unique.isEmpty()) return emptyList()
        val perBook = unique.groupBy { it.bookId }.values.map { it.first() }
        val rest = unique.filter { claim ->
            perBook.none { it.bookId == claim.bookId && it.page == claim.page && it.startUtf16 == claim.startUtf16 }
        }
        return (perBook + rest).sortedByDescending { it.score }.take(MAX_CLAIMS)
    }
}
