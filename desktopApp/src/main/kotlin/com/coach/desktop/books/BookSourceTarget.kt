package com.coach.desktop.books

import java.nio.file.Files
import java.security.MessageDigest

/**
 * Typed source -> reader target. Built only from a typed [OwnedBookCitation]; it never reads
 * model text or the "出处" footer. The book id is the local content SHA-256 and is never a
 * course/document id of the paired server. [pdfPage] is the physical page number the reader
 * opens (LocalBooksReaderSource: version "1", page 1..pages.size).
 */
internal data class BookSourceTarget(
    val book: String, val bookTitle: String, val pdfPage: Int, val start: Int, val end: Int,
    val quote: String, val pageTextSha256: String,
) {
    /** Plain-language label for the UI; no SHA or offsets. */
    val label: String get() = "《$bookTitle》第 $pdfPage 页"
}

internal enum class BookSourceRefusal { MALFORMED_BOOK, BOOK_MISSING, FILE_CHANGED, PAGE_MISSING, RANGE_INVALID, QUOTE_MISMATCH, NOT_ONE_BODY_BLOCK }

internal sealed interface BookSourceResolution {
    data class Resolved(val target: BookSourceTarget) : BookSourceResolution
    data class Refused(val reason: BookSourceRefusal) : BookSourceResolution
}

internal class BookSourceResolver(private val library: TextbookLibrary) {
    /**
     * Same book, same version (file hash == id), same physical page, in-range UTF-16 interval,
     * and (when given) the exact quote. A stale or foreign citation is refused, never relocated.
     */
    fun resolve(citation: OwnedBookCitation, expectedQuote: String? = null): BookSourceResolution {
        fun no(r: BookSourceRefusal) = BookSourceResolution.Refused(r)
        if (!citation.book.matches(Regex("[a-f0-9]{64}"))) return no(BookSourceRefusal.MALFORMED_BOOK)
        val book = runCatching { library.book(citation.book) }.getOrNull() ?: return no(BookSourceRefusal.BOOK_MISSING)
        if (book.id != citation.book || !fileMatches(book)) return no(BookSourceRefusal.FILE_CHANGED)
        // The reader indexes pages by position; the stored number must agree with it.
        val index = book.pages.indexOfFirst { it.number == citation.page }
        if (index < 0 || index + 1 != citation.page) return no(BookSourceRefusal.PAGE_MISSING)
        val page = book.pages[index]
        if (citation.start < 0 || citation.end <= citation.start || citation.end > page.text.length) return no(BookSourceRefusal.RANGE_INVALID)
        val quote = page.text.substring(citation.start, citation.end)
        if (expectedQuote != null && quote != expectedQuote) return no(BookSourceRefusal.QUOTE_MISMATCH)
        val oneBlock = StudyCoachCourse.blocks(book.id, book.pages).any {
            !it.heading && it.page == citation.page && citation.start >= it.start && citation.end <= it.end
        }
        if (!oneBlock) return no(BookSourceRefusal.NOT_ONE_BODY_BLOCK)
        val sha = MessageDigest.getInstance("SHA-256").digest(page.text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return BookSourceResolution.Resolved(BookSourceTarget(book.id, book.title, citation.page, citation.start, citation.end, quote, sha))
    }

    /** Call again at click time: refuses if the book/page changed since the answer was produced. */
    fun stillValid(target: BookSourceTarget): Boolean {
        val again = resolve(OwnedBookCitation(target.book, target.pdfPage, target.start, target.end), target.quote)
        return again is BookSourceResolution.Resolved && again.target == target
    }

    private fun fileMatches(book: Textbook): Boolean = runCatching {
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
