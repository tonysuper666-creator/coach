package com.coach.desktop.books

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.security.MessageDigest

/** A device-owned saved-page receipt, not a claim to a course's sources or learned scope.
 * Only hashes/coordinates accompany the already consented selection and neighbours. */
internal class ImportedBookWire(private val library: TextbookLibrary) {
    fun snapshot(blocks: List<SourceBlock>): JsonObject {
        val id = blocks.map { it.id.substringBefore(":p") }.distinct().single()
        require(id.matches(Regex("[0-9a-f]{64}")) && ExplainConsent(library.directory).allowed(id))
        val book = library.book(id)
        require(book.format in BookFormats.supported)
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(library.directory.resolve("$id.${book.format}")).use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        require(hex(digest.digest()) == id)
        for (block in blocks) {
            val page = book.pages.single { it.number == block.page }
            require(block.id == "$id:p${block.page}:${block.start}" && block.start >= 0 &&
                block.end > block.start && block.end <= page.text.length &&
                page.text.substring(block.start, block.end) == block.text)
        }
        return buildJsonObject {
            put("schema", 1); put("book", id); put("format", book.format); put("title", book.title)
            put("consented", true)
            put("pages", buildJsonArray {
                for (number in blocks.map { it.page }.distinct()) {
                    val page = book.pages.single { it.number == number }
                    add(buildJsonObject {
                        put("number", number); put("length", page.text.length); put("ocr", page.ocr)
                        put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(page.text.toByteArray(Charsets.UTF_8))))
                    })
                }
            })
        }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
