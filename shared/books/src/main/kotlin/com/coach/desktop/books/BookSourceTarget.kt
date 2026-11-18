package com.coach.desktop.books

import java.nio.file.Files
import java.security.MessageDigest

/**
 * Typed source -> reader target. Built only from a typed [OwnedBookCitation]; it never reads
 * model text or the "出处" footer. The book id is the local content SHA-256 and is never a
 * course/document id of the paired server. [pdfPage] is the physical page number the reader
 * opens (LocalBooksReaderSource: version "1", existing physical pages).
 */
internal data class BookSourceTarget(
    val book: String, val bookTitle: String, val pdfPage: Int, val start: Int, val end: Int,
    val quote: String, val pageTextSha256: String, val pageLabel: String = "第 $pdfPage 页",
) {
    /** Plain-language label for the UI; no SHA or offsets. */
    val label: String get() = "《$bookTitle》$pageLabel"
}

internal enum class BookSourceRefusal { MALFORMED_BOOK, BOOK_MISSING, FILE_CHANGED, PAGE_MISSING, RANGE_INVALID, QUOTE_MISMATCH, NOT_ONE_BODY_BLOCK }

internal sealed interface BookSourceResolution {
    data class Resolved(val target: BookSourceTarget) : BookSourceResolution
    data class Refused(val reason: BookSourceRefusal) : BookSourceResolution
}

internal open class BookSourceResolver(private val library: TextbookLibrary) {
    /**
     * Resolves against the WHOLE library: it does not know which book the UI currently shows and
     * does not check that the citation belongs to the original request. The CALLER must (1) bind the
     * reply to its originating book/lesson/request, (2) check `passage.book == currentBook.id` before
     * calling, (3) pass the ORIGINAL physical page, range and verified quote as [expectedQuote] (never
     * search a same-sentence page), (4) check `target.book == currentBook.id`, then [stillValid].
     * Checks here: file hash == id, page order, UTF-16 range on code-point boundaries, quote, one body block.
     * Page text comes from the trusted import cache; the PDF is not re-extracted.
     */
    open fun resolve(citation: OwnedBookCitation, expectedQuote: String? = null): BookSourceResolution =
        resolveAll(listOf(citation to expectedQuote)).single()

    /** Batch form: each distinct book is loaded, file-hashed and block-planned once for the whole batch. */
    open fun resolveAll(requests: List<Pair<OwnedBookCitation, String?>>): List<BookSourceResolution> {
        class Loaded(val book: Textbook?, val fileOk: Boolean, val blocks: List<SourceBlock>)
        val cache = HashMap<String, Loaded>()
        fun no(r: BookSourceRefusal) = BookSourceResolution.Refused(r)
        return requests.map { (citation, expectedQuote) ->
            if (!citation.book.matches(Regex("[a-f0-9]{64}"))) return@map no(BookSourceRefusal.MALFORMED_BOOK)
            val loaded = cache.getOrPut(citation.book) {
                val b = runCatching { library.book(citation.book) }.getOrNull()
                if (b == null || b.id != citation.book || !fileMatches(b)) Loaded(b, false, emptyList())
                else Loaded(b, true, StudyCoachCourse.blocks(b.id, b.pages))
            }
            val book = loaded.book ?: return@map no(BookSourceRefusal.BOOK_MISSING)
            if (!loaded.fileOk) return@map no(BookSourceRefusal.FILE_CHANGED)
            val page = book.page(citation.page) ?: return@map no(BookSourceRefusal.PAGE_MISSING)
            if (citation.start < 0 || citation.end <= citation.start || citation.end > page.text.length) return@map no(BookSourceRefusal.RANGE_INVALID)
            // A UTF-16 boundary inside a surrogate pair would quote half a character.
            if (splitsSurrogate(page.text, citation.start) || splitsSurrogate(page.text, citation.end)) return@map no(BookSourceRefusal.RANGE_INVALID)
            val quote = page.text.substring(citation.start, citation.end)
            if (expectedQuote != null && quote != expectedQuote) return@map no(BookSourceRefusal.QUOTE_MISMATCH)
            val oneBlock = loaded.blocks.any { !it.heading && it.page == citation.page && citation.start >= it.start && citation.end <= it.end }
            if (!oneBlock) return@map no(BookSourceRefusal.NOT_ONE_BODY_BLOCK)
            val sha = MessageDigest.getInstance("SHA-256").digest(page.text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            BookSourceResolution.Resolved(BookSourceTarget(book.id, book.title, citation.page, citation.start, citation.end, quote, sha, book.pageLabel(citation.page)))
        }
    }

    /**
     * Call at click time. NOT an atomic snapshot: it only proves cache and file hash matched at this instant;
     * a file swapped afterwards is caught by the next call. Rendering must consume the same verified bytes
     * if "safe after verification" is required.
     */
    open fun stillValid(target: BookSourceTarget): Boolean {
        val again = resolve(OwnedBookCitation(target.book, target.pdfPage, target.start, target.end), target.quote)
        return again is BookSourceResolution.Resolved && again.target == target
    }

    private fun splitsSurrogate(text: String, i: Int) =
        i in 1 until text.length && Character.isHighSurrogate(text[i - 1]) && Character.isLowSurrogate(text[i])

    protected open fun fileMatches(book: Textbook): Boolean = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(library.directory.resolve("${book.id}.${book.format}")).use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == book.id
    }.getOrDefault(false)
}

/** Typed carrier the provider should return instead of folding citations into text. */
internal data class CitedBookText(val text: String, val citations: List<OwnedBookCitation>)
