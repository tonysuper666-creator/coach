package com.coach.desktop.books

import com.coach.client.*
import com.coach.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager

/** Explicit, paired ledger integration. Local Practice answers are never emitted as ledger commands. */
internal class BookLedgerBridge(private val library: TextbookLibrary, private val course: CourseHttp) {
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun database() = DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").apply {
        createStatement().use {
            it.execute("PRAGMA busy_timeout=5000")
            it.execute("""CREATE TABLE IF NOT EXISTS book_ledger_registration(
                book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,registration_json TEXT NOT NULL,
                PRIMARY KEY(book,lesson,mode))""")
        }
    }
    private fun local(review: LocalBookReview, identity: BookLedgerIdentity) {
        val live = library.review(review.book, review.lesson, review.mode)
        val book = library.book(review.book)
        val lesson = book.lessons.single { it.id == review.lesson }
        val block = lesson.blocks.singleOrNull { it.id == identity.block.id && !it.heading }
        val original = live.originalPractice
        require(review.mode == QuestionMode.CHOICE && !live.completed && live.variant == null &&
            identity.bookSha256 == book.id && identity.lessonId == lesson.id && block != null &&
            identity.block.pdfPage == block.page && identity.block.start == block.start && identity.block.end == block.end &&
            identity.block.textSha256 == digest(block.text.toByteArray()) && block.text.contains(identity.reference) &&
            identity.reference == original.reference && identity.prompt == original.prompt && identity.options == original.options &&
            identity.correctAnswer == original.answer && identity.originalAnswer == live.originalAnswer &&
            identity.guessed == live.originalGuessed && identity.question.version == null &&
            !identity.question.fingerprint.isNullOrBlank()) { "来源与原书、原题或原答不一致，新变式未备好。" }
        val bytes = Files.newInputStream(library.directory.resolve("${book.id}.${book.format}"))
        val hash = MessageDigest.getInstance("SHA-256")
        bytes.use { stream -> val buffer = ByteArray(65536); while (true) {
            val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n)
        } }
        require(hash.digest().joinToString("") { "%02x".format(it) } == book.id) { "教材原文件已改变。" }
    }

    /** v2 is an identity claim plus exact server coverage, not a bundle of invented local variants. */
    suspend fun register(review: LocalBookReview, path: Path): BookLedgerRegistration {
        val bytes = Files.newInputStream(path).use { it.readNBytes(1_048_577) }
        require(bytes.size in 1..1_048_576) { "来源文件过大或为空。" }
        val file = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(file.getValue("format").jsonPrimitive.content == "coach-book-ledger-source/v2")
        val identity = LedgerJson.decodeFromJsonElement<BookLedgerIdentity>(file.getValue("identity"))
        val coverage = LedgerJson.decodeFromJsonElement<BookLedgerCoverage>(file.getValue("coverage"))
        local(review, identity)
        val registered = course.registerBookLedgerSource(identity, coverage)
            ?: throw IllegalArgumentException("课程账本尚未核准完整身份，新变式未备好。")
        // Re-read after the network suspension; a stale caller cannot register against a changed answer.
        local(review, identity)
        database().use { db -> db.prepareStatement("INSERT OR REPLACE INTO book_ledger_registration VALUES(?,?,?,?)").use {
            it.setString(1, review.book); it.setString(2, review.lesson); it.setString(3, review.mode.name)
            it.setString(4, LedgerJson.encodeToString(registered)); it.executeUpdate()
        } }
        return registered
    }
    fun registration(review: LocalBookReview): BookLedgerRegistration? = database().use { db ->
        db.prepareStatement("SELECT registration_json FROM book_ledger_registration WHERE book=? AND lesson=? AND mode=?").use {
            it.setString(1, review.book); it.setString(2, review.lesson); it.setString(3, review.mode.name)
            it.executeQuery().use { rows -> if (!rows.next()) null else runCatching {
                LedgerJson.decodeFromString<BookLedgerRegistration>(rows.getString(1)).also { r -> local(review, r.identity) }
            }.getOrNull() }
        }
    }
    /** The existing exact host consumes this persisted target and synchronizes authenticated ledger facts. */
    fun prepare(review: LocalBookReview, sync: SyncEngine, day: Long): ReviewPracticeTarget? =
        registration(review)?.let { prepareBookReviewPractice(sync, it, day) }

    suspend fun take(review: LocalBookReview, sync: SyncEngine, day: Long): MockReply {
        val target = prepare(review, sync, day) ?: return MockReply(null, "BOOK_SOURCE_UNLINKED")
        val reply = course.takeReviewVariant(target)
        if (reply.body != null) sync.syncOnce()
        return reply
    }
}
