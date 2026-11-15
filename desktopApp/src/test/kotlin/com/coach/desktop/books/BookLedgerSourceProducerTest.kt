package com.coach.desktop.books

import com.coach.domain.*
import com.coach.protocol.*
import com.coach.store.BookSourceLocator
import com.coach.store.LedgerStore
import com.coach.store.ReviewVariantRejected
import com.coach.store.sha256
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.*

/**
 * Server-fact producer -> PR224 bridge -> exact take, on an isolated real SQLite ledger.
 * The learner device only names a location in its local book; it never supplies bytes,
 * question text, keys, answers or an approval flag.
 */
class BookLedgerSourceProducerTest {
    private val oneMarker = "<!-- PDF 第 1 页 · 书第 1 页 -->\n# Chapter 1 Light\n\n" +
        "Photosynthesis is the process by which plants use light to make sugars.\n\n# Chapter 2 Cells\n\n" +
        "Chlorophyll is a green pigment that absorbs light in plant cells.\n\n# Chapter 3 Energy\n\n" +
        "Respiration is the process that releases energy from stored sugars.\n"

    /** Mirrors tools/migrate-deeptutor textbookPages(): a page runs from its PDF marker to the next marker. */
    private fun markerPages(md: String): List<TextbookPage> {
        val m = Regex("<!--\\s*PDF 第 (\\d+) 页 · 书第 (.*?) 页\\s*-->").findAll(md).toList()
        return m.mapIndexed { i, x -> TextbookPage(x.groupValues[1].toInt(), x.groupValues[2].toIntOrNull(),
            md.substring(x.range.first, m.getOrNull(i + 1)?.range?.first ?: md.length)) }
    }

    private inner class F(md: String = oneMarker, localBytes: ByteArray? = null, poseDevice: String = "phone") : AutoCloseable {
        val root = Files.createTempDirectory("book-source-producer-")
        val seeded = md.toByteArray()
        val book: Textbook
        val lesson: BookLesson
        val practice: Practice
        val wrong: String
        var clock = 1_794_052_800_000L - 86_400_000
        val store = LedgerStore(root.resolve("server")) { clock }
        init {
            val file = root.resolve("source").also { Files.createDirectories(it) }.resolve("chapter.md")
            Files.write(file, localBytes ?: seeded)
            book = TextbookLibrary(root.resolve("library")).importBook(file)
            lesson = book.lessons.first { l -> StudyCoachCourse.practice(book, l, QuestionMode.CHOICE) != null }
            practice = assertNotNull(StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE))
            wrong = practice.options.first { it != practice.answer }
            val pages = markerPages(md)
            val options = practice.options.mapIndexed { n, text -> Option("o$n", text) }
            val correct = options.single { it.text == practice.answer }.optionId
            store.enroll("phone", "synthetic-producer-private-token-32-chars")
            store.enroll("tablet", "synthetic-producer-tablet-token-32-chars!")
            // Same path as the content-owner seed (CourseContentStore stores the markdown bytes as the doc blob).
            store.seedContent(ContentSeed(CourseContent(Course("C", "C", "Synthetic"), listOf(Module("M", 1, "M")),
                listOf(KnowledgePoint("K", "M", 1, "K", anchors = listOf(SourceAnchor("D", pages.first().pdfPage))))),
                listOf(TextbookDocument("D", "Synthetic", md, pages))))
            // Stand-in for the missing production edge: nothing in production registers the local practice as a ledger question.
            store.registerQuestion("original", "C", "", "SINGLE_CHOICE", practice.prompt, "VERIFIED", correct, options, listOf("K"))
            val pose = store.poseChoiceGroup("C", "g-original", listOf(json("attemptId" to "original-attempt",
                "questionId" to "original", "kpIds" to listOf("K"), "purpose" to "PRACTICE", "policyVersion" to 1,
                "mainline" to true, "posedAt" to clock)), Random(20), device = poseDevice).single()
            val answer = pose.payload.getValue("letterMap").jsonObject.getValue(options.single { it.text == wrong }.optionId).jsonPrimitive.content
            val raw = canonicalJson(LedgerJson.encodeToJsonElement(CommandBody("submit_answer", "C", clock,
                json("attemptId" to "original-attempt", "answer" to answer, "offline" to true, "posedSnapshot" to pose.payload))))
            assertEquals("OK", store.command(WireCommand(poseDevice, "real-original-answer", raw, sha256(raw.toByteArray()))).status)
            store.registerQuestion("variant", "C", "", "SINGLE_CHOICE", "Synthetic verified variation", "VERIFIED", correct, options, listOf("K"))
            clock += 86_400_000
        }
        val block get() = lesson.blocks.first { !it.heading && it.text.contains(practice.reference) }
        fun locator(device: String = "phone", attempt: String = "original-attempt", reference: String = practice.reference,
            page: Int = block.page) = BookSourceLocator("C", device, attempt, book.id, lesson.id, page, block.start, block.end,
            reference, practice.options)
        fun count(table: String) = DriverManager.getConnection("jdbc:sqlite:${root.resolve("server").resolve("coach.sqlite")}").use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) } } }
        fun refused(locator: BookSourceLocator): String {
            val before = store.events("C")
            val e = assertFailsWith<ReviewVariantRejected> { store.publishBookLedgerSourceFromServerFacts(locator) }
            assertEquals(before, store.events("C")); assertEquals(0, count("book_ledger_sources"))
            return e.code
        }
        override fun close() { store.close(); root.toFile().deleteRecursively() }
    }

    @Test fun serverFactsProduceTheSameIdentityTheLocalBookChecksAndTheBridgeTakesIt() = F().use { f ->
        val identity = f.store.publishBookLedgerSourceFromServerFacts(f.locator())
        // Every edge the desktop bridge's local() compares, now sourced from the server.
        assertEquals(f.book.id, identity.bookSha256); assertEquals(sha256(f.seeded), identity.docBlobSha256)
        assertEquals(f.lesson.id, identity.lessonId); assertEquals(f.block.id, identity.block.id)
        assertEquals(sha256(f.block.text.toByteArray()), identity.block.textSha256)
        assertEquals(f.practice.prompt, identity.prompt); assertEquals(f.practice.options, identity.options)
        assertEquals(f.practice.answer, identity.correctAnswer); assertEquals(f.wrong, identity.originalAnswer)
        assertEquals(1, f.count("book_ledger_sources"))
        val r = f.store.registerBookLedgerSource(identity, f.store.bookLedgerCoverage("C"))
        val posed = f.store.takeBookReviewVariant("C", "phone", null, "K", r.entryId, r.attemptId, r.candidate, r)
        println("PRODUCER chain take=${posed.map { it.type to it.payload.str("questionId") }} takes=${f.count("book_ledger_takes")}")
        assertEquals("variant", posed.single().payload.str("questionId")); assertEquals(1, f.count("book_ledger_takes"))
    }

    @Test fun missingOrForeignEdgesAreRefusedWithZeroFacts() {
        // Local file differs from the seeded textbook bytes by one trailing byte: not the server's book.
        F(localBytes = oneMarker.toByteArray() + "\n".toByteArray()).use { f ->
            assertEquals("BOOK_FILE_UNSEEDED", f.refused(f.locator()).also { println("PRODUCER foreign file=$it") })
        }
        F().use { f ->
            assertEquals("BOOK_DEVICE_MISMATCH", f.refused(f.locator(device = "tablet")))
            assertEquals("BOOK_ATTEMPT_MISMATCH", f.refused(f.locator(attempt = "synthetic-invented-attempt")))
            assertEquals("BOOK_BLOCK_MISMATCH", f.refused(f.locator(reference = "Text that is not in this block.")))
            assertEquals("BOOK_PRACTICE_MISMATCH", f.refused(f.locator().copy(optionOrder = f.practice.options.drop(1) + "invented")))
            // Server posed but never answered: no original attempt to bind.
            f.store.poseChoiceGroup("C", "g-open", listOf(json("attemptId" to "open-attempt", "questionId" to "original",
                "kpIds" to listOf("K"), "purpose" to "PRACTICE", "policyVersion" to 1, "mainline" to true, "posedAt" to f.clock)),
                Random(21), device = "phone")
            val before = f.store.events("C")
            val e = assertFailsWith<ReviewVariantRejected> { f.store.publishBookLedgerSourceFromServerFacts(f.locator(attempt = "open-attempt")) }
            assertEquals("BOOK_ANSWER_MISMATCH", e.code); assertEquals(before, f.store.events("C"))
        }
        // Pose and answer belong to another device of the account.
        F(poseDevice = "tablet").use { f -> assertEquals("BOOK_DEVICE_MISMATCH", f.refused(f.locator())) }
    }

    @Test fun realShapeMultiPageTextbookCannotBeLocatedFromLocalCoordinates() {
        // Production seeds pages by PDF markers (here PDF 5 and 6); the local Markdown import re-paginates by
        // length, so the same file's local page/offset coordinates do not exist on the server.
        val md = "<!-- PDF 第 5 页 · 书第 1 页 -->\n# Chapter 1 Light\n\n" +
            "Photosynthesis is the process by which plants use light to make sugars.\n\n# Chapter 2 Cells\n\n" +
            "Chlorophyll is a green pigment that absorbs light in plant cells.\n\n" +
            "<!-- PDF 第 6 页 · 书第 2 页 -->\n# Chapter 3 Energy\n\n" +
            "Respiration is the process that releases energy from stored sugars.\n"
        F(md).use { f ->
            assertEquals(f.book.id, sha256(md.toByteArray()), "same file as the seeded textbook")
            println("PRODUCER local pages=${f.book.pages.map { it.number }} block page=${f.block.page} start=${f.block.start}")
            val local = f.refused(f.locator())
            println("PRODUCER real-shape local=$local")
            assertEquals("BOOK_BLOCK_MISMATCH", local)
            // Re-labelled to the server page the text may pass the server, but it no longer names the local
            // block, so the desktop bridge's local() comparison still cannot accept it: not ready, not a link.
            val relabelled = runCatching { f.store.publishBookLedgerSourceFromServerFacts(f.locator(page = 5)) }
            println("PRODUCER real-shape page5=${relabelled.map { it.block.id }.getOrElse { (it as ReviewVariantRejected).code }} local=${f.block.id}")
            relabelled.onSuccess { assertNotEquals(f.block.id, it.block.id); assertNotEquals(f.block.page, it.block.pdfPage) }
        }
    }
}
