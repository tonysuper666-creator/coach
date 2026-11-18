package com.coach.desktop.books

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.util.concurrent.CancellationException

/** A persisted index into the immutable source archive, not a rendered structure or hiding permit.
 * Attributes, mixed text, styles and resources remain in that archive. Resolve a node only after
 * checking its member digest. Empty and nested ranges are meaningful and must be retained.
 */
internal data class BookNativeElement(
    val spineIndex: Int?, val member: String, val memberSha256: String, val nodePath: List<Int>,
    val namespace: String, val localName: String, val ranges: List<BookNativeRange>,
)

internal data class BookNativeRange(val page: Int, val start: Int, val end: Int)
internal data class BookNativeSources(val format: String, val elements: List<BookNativeElement>)

/** Optional provenance: extraction/validation failure leaves ordinary text import available.
 * The small versioned binary record contains no second copy of the book's canonical text.
 */
internal object BookNativeSourceStore {
    private const val VERSION = 1
    private const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_ELEMENTS = 200_000
    private const val MAX_RANGES = 1_000_000

    fun migrate(db: Connection) {
        db.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS book_native_sources(book TEXT PRIMARY KEY,payload BLOB NOT NULL)") }
    }

    fun capture(path: Path, format: String, book: String, pages: List<BookPage>, control: BookImportControl): ByteArray? {
        if (format !in setOf("epub", "docx")) return null
        control.check()
        return try {
            val sources = when (format) {
                "epub" -> TextbookExtraction.epubSourceMapping(path, pages).let { mapping ->
                    check(mapping.status == EpubSourceMapping.Status.MAPPED)
                    BookNativeSources(format, mapping.elements.map { e -> BookNativeElement(e.spineIndex, e.member,
                        e.memberSha256, e.nodePath, e.namespace, e.localName, e.ranges.map { BookNativeRange(it.page, it.start, it.end) }) })
                }
                else -> StructuredBookExtraction.wordSourceMapping(path, pages).let { mapping ->
                    check(mapping.status == WordSourceMapping.Status.MAPPED)
                    BookNativeSources(format, mapping.elements.map { e -> BookNativeElement(null, e.member,
                        e.memberSha256, e.nodePath, e.namespace, e.localName, e.ranges.map { BookNativeRange(it.page, it.start, it.end) }) })
                }
            }
            control.check()
            encode(book, pages, sources).also { control.check() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { control.check(); null }
    }

    fun saveInTransaction(db: Connection, book: String, payload: ByteArray) {
        db.prepareStatement("INSERT OR REPLACE INTO book_native_sources(book,payload) VALUES(?,?)").use { sql ->
            sql.setString(1, book); sql.setBytes(2, payload); sql.executeUpdate()
        }
    }

    fun load(db: Connection, book: String, format: String, pages: List<BookPage>): BookNativeSources? =
        db.prepareStatement("SELECT payload FROM book_native_sources WHERE book=? AND length(payload)<=?").use { sql ->
            sql.setString(1, book); sql.setInt(2, MAX_BYTES)
            sql.executeQuery().use { row ->
                if (!row.next()) null else try { decode(row.getBytes(1), book, format, pages) } catch (_: Exception) { null }
            }
        }

    private fun textHash(text: String): ByteArray {
        // Hash actual UTF-16 code units, including any unpaired surrogate, without charset replacement.
        val digest = MessageDigest.getInstance("SHA-256")
        for (char in text) { digest.update(char.code.toByte()); digest.update((char.code ushr 8).toByte()) }
        return digest.digest()
    }

    private fun validate(pages: List<BookPage>, sources: BookNativeSources) {
        require(sources.format in setOf("epub", "docx") && sources.elements.size in 1..MAX_ELEMENTS)
        val canonical = pages.associate { it.number to it.text }
        require(canonical.size == pages.size && pages.isNotEmpty() && pages.all { it.number > 0 })
        val identities = HashSet<Triple<Int?, String, List<Int>>>()
        val hashes = mutableMapOf<String, String>()
        var rangeCount = 0
        for (e in sources.elements) {
            require(if (sources.format == "epub") e.spineIndex != null && e.spineIndex >= 0 else e.spineIndex == null)
            require(e.member.isNotEmpty() && !e.member.startsWith('/') && '\\' !in e.member &&
                e.member.split('/').none { it in setOf("", ".", "..") })
            require(BookIllustrationAssets.isValidSha256(e.memberSha256))
            require(hashes.putIfAbsent(e.member, e.memberSha256)?.let { it == e.memberSha256 } != false)
            require(e.nodePath.size <= 256 && e.nodePath.all { it >= 0 } && e.localName.isNotEmpty())
            require(identities.add(Triple(e.spineIndex, e.member, e.nodePath)))
            rangeCount += e.ranges.size
            require(rangeCount <= MAX_RANGES)
            for (r in e.ranges) {
                val text = requireNotNull(canonical[r.page])
                require(r.start >= 0 && r.start < r.end && r.end <= text.length)
                for (offset in listOf(r.start, r.end)) require(!(offset > 0 && offset < text.length &&
                    Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset])))
            }
        }
    }

    internal fun encode(book: String, pages: List<BookPage>, sources: BookNativeSources): ByteArray {
        require(BookIllustrationAssets.isValidSha256(book))
        validate(pages, sources)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION); out.writeUTF(book); out.writeUTF(sources.format)
            out.writeInt(pages.size)
            for (page in pages) {
                out.writeInt(page.number); out.writeInt(page.text.length); out.write(textHash(page.text))
            }
            out.writeInt(sources.elements.size)
            for (e in sources.elements) {
                out.writeInt(e.spineIndex ?: -1); out.writeUTF(e.member); out.writeUTF(e.memberSha256)
                out.writeInt(e.nodePath.size); e.nodePath.forEach(out::writeInt)
                out.writeUTF(e.namespace); out.writeUTF(e.localName)
                out.writeInt(e.ranges.size)
                for (r in e.ranges) { out.writeInt(r.page); out.writeInt(r.start); out.writeInt(r.end) }
                require(bytes.size() <= MAX_BYTES)
            }
        }
        return bytes.toByteArray()
    }

    internal fun decode(bytes: ByteArray, book: String, format: String, pages: List<BookPage>): BookNativeSources {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == VERSION && input.readUTF() == book && input.readUTF() == format)
            require(input.readInt() == pages.size)
            for (page in pages) {
                require(input.readInt() == page.number && input.readInt() == page.text.length)
                val digest = ByteArray(32); input.readFully(digest)
                require(digest.contentEquals(textHash(page.text)))
            }
            fun count(max: Int): Int = input.readInt().also { require(it in 0..max) }
            var ranges = 0
            val sources = BookNativeSources(format, List(count(MAX_ELEMENTS)) {
                val spine = input.readInt().also { require(it >= -1) }.takeIf { it >= 0 }
                val member = input.readUTF(); val sha = input.readUTF()
                val path = List(count(256)) { input.readInt() }
                val namespace = input.readUTF(); val name = input.readUTF()
                val size = count(MAX_RANGES - ranges); ranges += size
                BookNativeElement(spine, member, sha, path, namespace, name,
                    List(size) { BookNativeRange(input.readInt(), input.readInt(), input.readInt()) })
            })
            require(input.read() == -1)
            validate(pages, sources)
            sources
        }
    }
}
