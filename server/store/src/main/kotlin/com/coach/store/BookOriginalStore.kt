package com.coach.store

import com.coach.domain.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/** Local content-owner import only. A paired device cannot install, replace or withdraw this relation. */
internal class BookOriginalStore(private val db: Connection, private val blobs: Path) {
    init {
        sql("""CREATE TABLE IF NOT EXISTS book_original_imports(
            course TEXT NOT NULL,original_sha256 TEXT NOT NULL,page_count INTEGER NOT NULL,
            PRIMARY KEY(course,original_sha256))""")
        sql("""CREATE TABLE IF NOT EXISTS book_original_pages(
            course TEXT NOT NULL,original_sha256 TEXT NOT NULL,pdf_page INTEGER NOT NULL,
            text TEXT NOT NULL,text_sha256 TEXT NOT NULL,canonical_prefix INTEGER NOT NULL,
            PRIMARY KEY(course,original_sha256,pdf_page))""")
    }
    private fun sql(statement: String, vararg args: Any?) = db.prepareStatement(statement).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }; ps.executeUpdate()
    }
    private fun <T> rows(statement: String, vararg args: Any?, read: (java.sql.ResultSet) -> T): List<T> =
        db.prepareStatement(statement).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }
    private fun need(ok: Boolean, code: String) { if (!ok) throw ReviewVariantRejected(code) }
    fun blob(course: String, original: String): String = rows("""SELECT blob_sha256 FROM source_docs
        WHERE course_id=? AND blob_sha256=? AND kind='TEXTBOOK' AND tutor_visible=1""", course, original) {
        it.getString(1)
    }.singleOrNull() ?: rows("SELECT blob_sha256 FROM book_original_sha WHERE course=? AND original_sha256=?",
        course, original) { it.getString(1) }.singleOrNull() ?: throw ReviewVariantRejected("BOOK_FILE_UNSEEDED")

    private fun bytes(hash: String): ByteArray {
        need(hash.matches(Regex("[a-f0-9]{64}")), "BOOK_IDENTITY_INCOMPLETE")
        val file = blobs.resolve(hash)
        need(Files.isRegularFile(file), "BOOK_FILE_UNSEEDED")
        return Files.readAllBytes(file).also { need(sha256(it) == hash, "BOOK_FILE_UNSEEDED") }
    }
    private data class Page(val number: Int, val text: String, val hash: String)
    private fun pages(doc: String) = rows("SELECT pdf_page,text,text_sha256 FROM source_pages WHERE doc_id=? ORDER BY pdf_page", doc) {
        Page(it.getInt(1), it.getString(2), it.getString(3))
    }
    // This is the only permitted difference from the actual extracted PDF page. No fuzzy match,
    // whitespace normalization or inferred pagination can authorize a different text/range.
    private fun prefix(text: String, page: Int): Int = Regex("\\A<!--\\s*PDF 第 $page 页 · 书第 (.*?) 页\\s*-->\\r?\\n")
        .find(text)?.value?.length ?: 0

    fun install(course: String, original: String, canonical: String, actual: ByteArray): Boolean {
        need(sha256(actual) == original && original != canonical, "BOOK_FILE_MISMATCH")
        need(actual.size in 1..(128 * 1024 * 1024), "BOOK_FILE_MISMATCH")
        val doc = rows("""SELECT doc_id FROM source_docs WHERE course_id=? AND blob_sha256=?
            AND kind='TEXTBOOK' AND tutor_visible=1""", course, canonical) { it.getString(1) }.singleOrNull()
        need(doc != null, "BOOK_FILE_UNSEEDED")
        val markdown = bytes(canonical).toString(Charsets.UTF_8)
        val seeded = pages(doc!!)
        val marked = textbookPagesFromMarkers(markdown)
        need(seeded.isNotEmpty() && seeded.all { sha256(it.text.toByteArray()) == it.hash } &&
            (if (marked.isEmpty()) seeded.size == 1 && seeded.single().text == markdown
             else marked.map { it.pdfPage to it.text } == seeded.map { it.number to it.text }), "BOOK_PAGE_SET_MISMATCH")
        val extracted = Loader.loadPDF(actual).use { pdf ->
            need(pdf.numberOfPages in 1..5000 && pdf.currentAccessPermission.canExtractContent(), "BOOK_PDF_UNREADABLE")
            val stripper = PDFTextStripper().apply { sortByPosition = true; paragraphEnd = "\n\n" }
            (1..pdf.numberOfPages).map { n ->
                stripper.startPage = n; stripper.endPage = n
                stripper.getText(pdf).replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")
            }
        }
        need(seeded.map { it.number } == (1..extracted.size).toList(), "BOOK_PAGE_SET_MISMATCH")
        seeded.zip(extracted).forEach { (page, text) ->
            need(page.text.substring(prefix(page.text, page.number)) == text, "BOOK_PAGE_TEXT_MISMATCH")
        }
        val prior = rows("SELECT blob_sha256 FROM book_original_sha WHERE course=? AND original_sha256=?", course, original) {
            it.getString(1)
        }.singleOrNull()
        need(prior == null || prior == canonical, "BOOK_SOURCE_CONFLICT")
        if (prior != null && importedPages(course, original) != null) {
            need(seeded.all { page(course, original, canonical, doc, it.number) == extracted[it.number - 1] }, "BOOK_PAGE_TEXT_MISMATCH")
            return false
        }
        sql("INSERT OR IGNORE INTO book_original_sha VALUES(?,?,?)", course, original, canonical)
        sql("INSERT INTO book_original_imports VALUES(?,?,?)", course, original, extracted.size)
        seeded.zip(extracted).forEach { (page, text) ->
            sql("INSERT INTO book_original_pages VALUES(?,?,?,?,?,?)", course, original, page.number,
                text, sha256(text.toByteArray()), prefix(page.text, page.number))
        }
        return true
    }
    private fun importedPages(course: String, original: String) = rows(
        "SELECT page_count FROM book_original_imports WHERE course=? AND original_sha256=?", course, original) { it.getInt(1) }.singleOrNull()

    /** Local offsets remain local; the exact canonical page and prefix are checked on every use. */
    fun page(course: String, original: String, canonical: String, doc: String, number: Int): String {
        val canonicalBytes = bytes(canonical)
        val allSeeded = pages(doc)
        val seeded = allSeeded.singleOrNull { it.number == number }
        need(seeded != null && sha256(seeded.text.toByteArray()) == seeded.hash, "BOOK_BLOCK_MISMATCH")
        val local = rows("""SELECT text,text_sha256,canonical_prefix FROM book_original_pages
            WHERE course=? AND original_sha256=? AND pdf_page=?""", course, original, number) {
            Triple(it.getString(1), it.getString(2), it.getInt(3))
        }.singleOrNull()
        val count = importedPages(course, original)
        if (count != null) {
            val numbered = rows("SELECT pdf_page FROM book_original_pages WHERE course=? AND original_sha256=? ORDER BY pdf_page", course, original) { it.getInt(1) }
            val marked = textbookPagesFromMarkers(canonicalBytes.toString(Charsets.UTF_8))
            need(local != null && numbered == (1..count).toList() && allSeeded.map { it.number } == numbered &&
                (if (marked.isEmpty()) count == 1 && allSeeded.single().text == canonicalBytes.toString(Charsets.UTF_8)
                 else marked.map { it.pdfPage to it.text } == allSeeded.map { it.number to it.text }), "BOOK_PAGE_SET_MISMATCH")
        }
        // Existing direct-blob imports and the fixed PR247 relation remain readable.
        if (local == null) return seeded!!.text
        need(blob(course, original) == canonical, "BOOK_FILE_MISMATCH")
        bytes(original)
        need(sha256(local.first.toByteArray()) == local.second &&
            local.third == prefix(seeded!!.text, number) && seeded.text.substring(local.third) == local.first,
            "BOOK_BLOCK_MISMATCH")
        return local.first
    }
    fun withdraw(course: String, original: String) {
        sql("DELETE FROM book_original_sha WHERE course=? AND original_sha256=?", course, original)
        sql("DELETE FROM book_original_pages WHERE course=? AND original_sha256=?", course, original)
        sql("DELETE FROM book_original_imports WHERE course=? AND original_sha256=?", course, original)
        sql("DELETE FROM book_ledger_sources WHERE course=? AND book=?", course, original)
    }
}
