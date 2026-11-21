package com.coach.server

import com.coach.domain.*
import com.coach.store.tutorSourceAllowed
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Personal imports are owned by the authenticated device. They never enter the course ledger,
 * search index, source catalog or learned ceiling. Store only consented blocks, not whole pages.
 * A page hash is a device's immutable snapshot identity, not server verification of original bytes. */
class ImportedBookSources internal constructor(directory: Path) {
    private val file = privateFiles.file(privateFiles.directory(directory).resolve("sources.sqlite"))
    init { database().use { db -> db.createStatement().use { sql ->
        sql.execute("CREATE TABLE IF NOT EXISTS blocks(device TEXT NOT NULL,book TEXT NOT NULL,page INTEGER NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,text TEXT NOT NULL,snapshot TEXT NOT NULL,PRIMARY KEY(device,book,page,start,end))")
    } } }
    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite:$file")

    internal data class Receipt(val device: String, val book: String, val blocks: List<Block>)
    internal data class Block(val page: Int, val start: Int, val text: String, val snapshot: String)

    @Synchronized internal fun register(device: String, input: JsonObject, parsed: LibraryExplainRequest): Receipt {
        fun bad(): Nothing = throw MockError(HttpStatusCode.BadRequest, "这本书的保存页或发送许可无法核对，请重新打开后重试。")
        val source = input["localSource"] as? JsonObject ?: bad()
        if (!parsed.isLocalBook || source.keys != setOf("schema", "book", "format", "title", "consented", "pages") ||
            source.num("schema") != 1L || source.str("book") != parsed.bookId ||
            (source["consented"] as? JsonPrimitive)?.booleanOrNull != true ||
            source.str("format") !in setOf("pdf", "epub", "md", "txt", "docx", "pptx", "html", "mobi", "azw3", "rtf", "odt", "png", "jpg") ||
            source.str("title").length !in 1..300 || !tutorSourceAllowed(source.str("title"))) bad()
        val pages = source["pages"] as? JsonArray ?: bad()
        if (pages.isEmpty() || pages.size > (if (parsed.isSectionScoped) 128 else 4)) bad()
        val pageMap = pages.map { value ->
            val page = value as? JsonObject ?: bad()
            if (page.keys != setOf("number", "length", "ocr", "sha256") || page.num("number") !in 1L..100_000L ||
                (parsed.bookPages != null && page.num("number") > parsed.bookPages) ||
                page.num("length") !in 1L..2_000_000L || (page["ocr"] as? JsonPrimitive)?.booleanOrNull == null ||
                !page.str("sha256").matches(Regex("[0-9a-f]{64}"))) bad()
            page.num("number").toInt() to page
        }.toMap()
        if (pageMap.size != pages.size) bad()
        val blocks = (input["blocks"] as? JsonArray ?: bad()).map { value ->
            val block = value.jsonObject
            val id = block.str("id")
            val page = id.substringAfter(":p").substringBefore(':').toIntOrNull() ?: bad()
            val start = id.substringAfterLast(':').toIntOrNull() ?: bad()
            val text = block.str("text")
            val meta = pageMap[page] ?: bad()
            if (start < 0 || text.isBlank() || text.length > 8_000 || start.toLong() + text.length > meta.num("length")) bad()
            // Canonicalize metadata without normalizing text or changing UTF-16 positions.
            val snapshot = json("sha256" to meta.str("sha256"), "length" to meta.num("length"),
                "ocr" to meta.getValue("ocr")).toString()
            Block(page, start, text, snapshot)
        }
        if (blocks.map { it.page }.toSet() != pageMap.keys) bad()
        if (blocks.any { a -> blocks.any { b -> a.page == b.page && !consistent(a, b) } }) bad()
        val receipt = Receipt(device, parsed.bookId, blocks)
        database().use { db ->
            db.autoCommit = false
            try {
                val previous = read(db, device, parsed.bookId)
                if (previous.any { old -> blocks.any { fresh -> old.page == fresh.page &&
                        (old.snapshot != fresh.snapshot || !consistent(old, fresh)) } })
                    throw MockError(HttpStatusCode.Conflict, "这本书的保存页已变化，请重新导入并核对原文。")
                val added = blocks.count { fresh -> previous.none { it == fresh } }
                db.prepareStatement("SELECT count(*) FROM blocks WHERE device=?").use { sql ->
                    sql.setString(1, device); sql.executeQuery().use { row ->
                        row.next(); if (row.getInt(1) + added > 25_000)
                            throw MockError(HttpStatusCode.PayloadTooLarge, "已保存的讲解原文过多，请先清理服务上的个人导入来源。")
                    }
                }
                db.prepareStatement("INSERT OR IGNORE INTO blocks(device,book,page,start,end,text,snapshot) VALUES(?,?,?,?,?,?,?)").use { sql ->
                    for (block in blocks) {
                        sql.setString(1, device); sql.setString(2, parsed.bookId); sql.setInt(3, block.page)
                        sql.setInt(4, block.start); sql.setInt(5, block.start + block.text.length)
                        sql.setString(6, block.text); sql.setString(7, block.snapshot); sql.addBatch()
                    }
                    sql.executeBatch()
                }
                db.commit()
            } catch (e: Exception) { db.rollback(); throw e }
        }
        return receipt
    }

    /** Re-read after generation: deleting or changing a private registration revokes its answer. */
    @Synchronized internal fun isCurrent(receipt: Receipt): Boolean = runCatching {
        database().use { db -> read(db, receipt.device, receipt.book).containsAll(receipt.blocks) }
    }.getOrDefault(false)

    /**
     * Validates and registers a chapter-companion personal import receipt using the exact same
     * device-owned `sources.sqlite`, snapshot hash, UTF-16 boundary, and overlap-consistency rules
     * as [register]. Fails closed on any missing consent, forged page/hash, or conflicting block.
     */
    @Synchronized internal fun registerCompanion(
        device: String,
        input: JsonObject,
        req: ChapterCompanionRequest,
        bookPages: Int? = null,
    ): Receipt {
        fun bad(): Nothing = throw MockError(HttpStatusCode.BadRequest, "这本书的保存页或发送许可无法核对，请重新打开后重试。")
        val source = input["localSource"] as? JsonObject ?: bad()
        if (!req.bookId.matches(Regex("[0-9a-f]{64}")) ||
            source.keys != setOf("schema", "book", "format", "title", "consented", "pages") ||
            source.num("schema") != 1L || source.str("book") != req.bookId ||
            (source["consented"] as? JsonPrimitive)?.booleanOrNull != true ||
            source.str("format") !in setOf("pdf", "epub", "md", "txt", "docx", "pptx", "html", "mobi", "azw3", "rtf", "odt", "png", "jpg") ||
            source.str("title").length !in 1..300 || !tutorSourceAllowed(source.str("title"))) bad()
        val pages = source["pages"] as? JsonArray ?: bad()
        if (pages.isEmpty() || pages.size > 128) bad()
        val pageMap = pages.map { value ->
            val page = value as? JsonObject ?: bad()
            if (page.keys != setOf("number", "length", "ocr", "sha256") || page.num("number") !in 1L..100_000L ||
                (bookPages != null && page.num("number") > bookPages) ||
                page.num("length") !in 1L..2_000_000L || (page["ocr"] as? JsonPrimitive)?.booleanOrNull == null ||
                !page.str("sha256").matches(Regex("[0-9a-f]{64}"))) bad()
            page.num("number").toInt() to page
        }.toMap()
        if (pageMap.size != pages.size) bad()

        val idPattern = Regex("[0-9a-f]{64}:p([0-9]{1,6}):[0-9]{1,9}")
        val seenIds = HashSet<String>()
        val blocks = req.sections.flatMap { sec -> sec.blocks }.map { blk ->
            val match = idPattern.matchEntire(blk.id) ?: bad()
            if (blk.id.substringBefore(":p") != req.bookId || !seenIds.add(blk.id)) bad()
            val idPage = match.groupValues[1].toInt()
            val idStart = blk.id.substringAfterLast(':').toIntOrNull() ?: bad()
            if (idPage != blk.pdfPage || idStart != blk.startUtf16) bad()
            val meta = pageMap[blk.pdfPage] ?: bad()
            if (blk.startUtf16 < 0 || blk.text.isBlank() || blk.text.length > 24_000 ||
                blk.startUtf16.toLong() + blk.text.length > meta.num("length")) bad()
            val snapshot = json("sha256" to meta.str("sha256"), "length" to meta.num("length"),
                "ocr" to meta.getValue("ocr")).toString()
            Block(blk.pdfPage, blk.startUtf16, blk.text, snapshot)
        }
        if (blocks.isEmpty() || blocks.map { it.page }.toSet() != pageMap.keys) bad()
        if (blocks.any { a -> blocks.any { b -> a.page == b.page && !consistent(a, b) } }) bad()
        val receipt = Receipt(device, req.bookId, blocks)
        database().use { db ->
            db.autoCommit = false
            try {
                val previous = read(db, device, req.bookId)
                if (previous.any { old -> blocks.any { fresh -> old.page == fresh.page &&
                        (old.snapshot != fresh.snapshot || !consistent(old, fresh)) } })
                    throw MockError(HttpStatusCode.Conflict, "这本书的保存页已变化，请重新导入并核对原文。")
                val added = blocks.count { fresh -> previous.none { it == fresh } }
                db.prepareStatement("SELECT count(*) FROM blocks WHERE device=?").use { sql ->
                    sql.setString(1, device); sql.executeQuery().use { row ->
                        row.next(); if (row.getInt(1) + added > 25_000)
                            throw MockError(HttpStatusCode.PayloadTooLarge, "已保存的讲解原文过多，请先清理服务上的个人导入来源。")
                    }
                }
                db.prepareStatement("INSERT OR IGNORE INTO blocks(device,book,page,start,end,text,snapshot) VALUES(?,?,?,?,?,?,?)").use { sql ->
                    for (block in blocks) {
                        sql.setString(1, device); sql.setString(2, req.bookId); sql.setInt(3, block.page)
                        sql.setInt(4, block.start); sql.setInt(5, block.start + block.text.length)
                        sql.setString(6, block.text); sql.setString(7, block.snapshot); sql.addBatch()
                    }
                    sql.executeBatch()
                }
                db.commit()
            } catch (e: Exception) { db.rollback(); throw e }
        }
        return receipt
    }

    private fun read(db: Connection, device: String, book: String): List<Block> =
        db.prepareStatement("SELECT page,start,text,snapshot FROM blocks WHERE device=? AND book=?").use { sql ->
            sql.setString(1, device); sql.setString(2, book)
            sql.executeQuery().use { row -> buildList { while (row.next()) add(Block(row.getInt(1), row.getInt(2), row.getString(3), row.getString(4))) } }
        }

    private fun consistent(a: Block, b: Block): Boolean {
        val from = maxOf(a.start, b.start); val until = minOf(a.start + a.text.length, b.start + b.text.length)
        return from >= until || a.text.regionMatches(from - a.start, b.text, from - b.start, until - from)
    }
}
