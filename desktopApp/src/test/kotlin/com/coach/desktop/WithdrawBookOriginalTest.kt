package com.coach.desktop

import com.coach.core.LedgerActor
import com.coach.desktop.books.*
import com.coach.domain.*
import com.coach.server.ledgerRoutes
import com.coach.store.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * Owner withdrawal must be able to fail. The Architect probe called it with a mistyped course and
 * with an unknown SHA: both printed a withdrawal, deleted nothing, and left the same pose answering
 * 200. These run the real CLI in a separate JVM so the reported exit code is the owner's exit code.
 */
class WithdrawBookOriginalTest {
    private class F : AutoCloseable {
        val root = Files.createTempDirectory("owner-withdraw-").toRealPath()
        val database = root.resolve("server")
        val pdf = syntheticTextbook(root.resolve("owner"))
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(pdf)
        val lesson = book.lessons.first { StudyCoachCourse.practice(book, it, QuestionMode.CHOICE) != null }
        val practice: Practice = assertNotNull(StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE))
        val block = lesson.blocks.single { !it.heading && it.text.contains(practice.reference) }
        val pages = book.pages.map { TextbookPage(it.number, it.number, "<!-- PDF 第 ${it.number} 页 · 书第 ${it.number} 页 -->\n${it.text}") }
        val markdown = "来源：synthetic.pdf\n" + pages.joinToString("") { it.text }
        val canonical = sha256(markdown.toByteArray())
        val seed = ContentSeed(CourseContent(Course("C", "C", "Synthetic"), listOf(Module("M", 1, "M")),
            listOf(KnowledgePoint("K", "M", 1, "K", anchors = listOf(SourceAnchor("D", block.page, quote = practice.reference))))),
            listOf(TextbookDocument("D", "Synthetic", markdown, pages)))
        val source = BookPracticeSource(book.id, lesson.id, block.page, block.start, block.end, practice.reference)
        val options = practice.options.mapIndexed { n, text -> Option("o$n", text) }
        val mapping: BookPracticeMapping
        val file = root.resolve("mappings.json")
        val token = "r".repeat(43)
        init {
            library.save(book.copy(learning = true, questionMode = QuestionMode.CHOICE))
            LedgerStore(database).use { store ->
                store.seedContent(seed)
                store.enroll("phone", token)
                val id = store.enqueueSupply("C", "K", "Synthetic recall", practice.reference)
                val job = store.supplyJobs().single { it.id == id }
                check(store.queueBlindReview(store.claimJob(id)!!, job, JsonArray(emptyList()), "synthetic-generator"))
                val review = store.supplyJobs().single { it.kind == "blind_review" }
                check(store.storeVerifiedBatch(store.claimJob(review.id)!!, review, listOf(VerifiedPoolQuestion(practice.prompt,
                    options, options.single { it.text == practice.answer }.optionId, "Synthetic explanation", practice.reference,
                    "synthetic-generator", "synthetic-independent-reviewer"))) == 1)
            }
            val question = query("SELECT question_id FROM questions").single()
            mapping = BookPracticeMapping(source, "K", question, questionFingerprint(practice.prompt, options))
            Files.writeString(file, LedgerJson.encodeToString(listOf(mapping)))
        }
        fun query(sql: String): List<String> = DriverManager.getConnection("jdbc:sqlite:${database.resolve("coach.sqlite")}").use { db ->
            db.createStatement().use { st -> st.executeQuery(sql).use { r -> buildList {
                while (r.next()) add((1..r.metaData.columnCount).joinToString("|") { r.getString(it).orEmpty() })
            } } }
        }
        fun counts() = listOf("book_original_sha", "book_original_pages", "book_original_imports", "book_practice_mappings",
            "book_practice_poses", "book_ledger_sources", "events", "questions", "answer_keys").associateWith { table ->
            if (query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").isEmpty()) "0"
            else query("SELECT COUNT(*) FROM $table").single()
        }
        /** The production CLI, in its own JVM, so stdout and the exit code are what an owner sees. */
        fun cli(vararg args: String): Pair<Int, String> {
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.coach.migrate.MainKt", *args)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(120, TimeUnit.SECONDS)) { "Owner CLI did not finish: $output" }
            return process.exitValue() to output
        }
        fun register(hash: String = book.id, course: String = "C", blob: String = canonical, path: Path = pdf) = cli(
            "register-book-original", "--database", database.toString(), "--course", course, "--pdf", path.toString(),
            "--sha256", hash, "--canonical-sha256", blob, "--mappings", file.toString())
        fun withdraw(course: String = "C", sha: String = book.id) = cli(
            "withdraw-book-original", "--database", database.toString(), "--course", course, "--sha256", sha)
        fun pose(): HttpResponse<String> = LedgerStore(database).use { store ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val actor = LedgerActor(scope, store)
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
                ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8))
            }
            try {
                server.start(false)
                val base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
                practiceHttp(base, "/v1/courses/C/book-practice-pose", LedgerJson.encodeToString(source), token = token)
            } finally { server.stop(0, 0); runBlocking { actor.close() }; scope.cancel() }
        }
        override fun close() { root.toFile().deleteRecursively() }
    }

    @Test fun aMistypedCourseIsRefusedAndTheRegisteredOriginalStaysPoseable() = F().use { f ->
        val (installed, installedOut) = f.register()
        assertEquals(0, installed, installedOut)
        assertEquals(200, f.pose().statusCode())
        val before = f.counts()
        val (refused, output) = f.withdraw(course = "c")
        assertEquals(1, refused, output)
        assertTrue(output.contains("refused"), output)
        assertFalse(output.contains("withdraw-book-original: course=c"), "A refused withdrawal must not claim it happened: $output")
        assertEquals(before, f.counts(), "A refused withdrawal must delete nothing")
        assertEquals(200, f.pose().statusCode(), "A refused withdrawal must leave the same original poseable")
        println("OWNER_PDF_WITHDRAW_REFUSED reason=wrongCourse exit=$refused mappings=0 pose=200")
    }

    @Test fun anUnknownShaIsRefusedAndTheRegisteredOriginalStaysPoseable() = F().use { f ->
        val (installed, installedOut) = f.register()
        assertEquals(0, installed, installedOut)
        assertEquals(200, f.pose().statusCode())
        val before = f.counts()
        val (refused, output) = f.withdraw(sha = "d".repeat(64))
        assertEquals(1, refused, output)
        assertTrue(output.contains("refused"), output)
        assertEquals(before, f.counts(), "A refused withdrawal must delete nothing")
        assertEquals("C|${f.book.id}|${f.canonical}", f.query("SELECT * FROM book_original_sha").single())
        assertEquals(200, f.pose().statusCode(), "A refused withdrawal must leave the same original poseable")
        println("OWNER_PDF_WITHDRAW_REFUSED reason=unknownSha exit=$refused mappings=0 pose=200")
    }

    @Test fun anEffectiveWithdrawalReportsTheRealCountsAndStopsTheExistingPose() = F().use { f ->
        val (installed, installedOut) = f.register()
        assertEquals(0, installed, installedOut)
        assertEquals(200, f.pose().statusCode())
        val before = f.counts()
        val (withdrawn, output) = f.withdraw()
        assertEquals(0, withdrawn, output)
        for (name in listOf("mappings", "pages", "sources")) {
            val table = mapOf("mappings" to "book_practice_mappings", "pages" to "book_original_pages",
                "sources" to "book_ledger_sources").getValue(name)
            assertTrue(output.contains("$name=${before.getValue(table)}"), "Reported $name must be the real deleted count: $output")
        }
        assertEquals("0", f.counts().getValue("book_original_sha"))
        assertEquals("0", f.counts().getValue("book_original_pages"))
        assertEquals("0", f.counts().getValue("book_original_imports"))
        assertEquals("0", f.counts().getValue("book_practice_mappings"))
        assertEquals(409, f.pose().statusCode(), "Withdrawn mappings must not keep posing")
        val after = f.counts()
        val (again, againOut) = f.withdraw()
        assertEquals(1, again, "A second withdrawal has nothing to delete and must say so: $againOut")
        assertEquals(after, f.counts())
        println("OWNER_PDF_WITHDRAW_HONEST mappings=${before.getValue("book_practice_mappings")} " +
            "pages=${before.getValue("book_original_pages")} pose=409 repeatExit=$again")
    }
}
