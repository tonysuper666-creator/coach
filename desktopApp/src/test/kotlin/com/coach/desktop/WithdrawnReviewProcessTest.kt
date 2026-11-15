package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.core.LedgerActor
import com.coach.desktop.books.*
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.server.ledgerRoutes
import com.coach.store.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.ServerSocket
import java.nio.channels.FileChannel
import java.nio.file.*
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Each phase is a real, separate headless JVM; no injected session, queue or withdrawal substitute. */
class WithdrawnReviewProcessTest {
    private fun fixture(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("review-withdraw-process-").toRealPath()
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val pdf = syntheticTextbook(root.resolve("source"))
            val book = library.importBook(pdf)
            library.save(book.copy(learning = true, questionMode = QuestionMode.CHOICE))
            val lesson = book.lessons.first { StudyCoachCourse.practice(book, it, QuestionMode.CHOICE) != null }
            val practice = assertNotNull(StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE))
            val block = lesson.blocks.single { !it.heading && it.text.contains(practice.reference) }
            val pages = book.pages.map { TextbookPage(it.number, it.number, "<!-- PDF 第 ${it.number} 页 · 书第 ${it.number} 页 -->\n${it.text}") }
            val markdown = "来源：synthetic.pdf\n" + pages.joinToString("") { it.text }
            val canonical = sha256(markdown.toByteArray())
            val content = ContentSeed(CourseContent(Course("C", "C", "Synthetic"), listOf(Module("M", 1, "M")),
                listOf(KnowledgePoint("K", "M", 1, "K", anchors = listOf(SourceAnchor("D", block.page, quote = practice.reference))))),
                listOf(TextbookDocument("D", "Synthetic", markdown, pages)))
            val options = practice.options.mapIndexed { n, text -> Option("o$n", text) }
            val db = root.resolve("server"); Files.createDirectories(db)
            // The same offline owner storage API used by register-book-original, under its server lock.
            FileChannel.open(db.resolve("server.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.tryLock().use { lock ->
                    assertNotNull(lock)
                    LedgerStore(db).use { store ->
                        store.seedContent(content)
                        val id = store.enqueueSupply("C", "K", "Synthetic recall", practice.reference)
                        val job = store.supplyJobs().single { it.id == id }
                        check(store.queueBlindReview(store.claimJob(id)!!, job, JsonArray(emptyList()), "synthetic-generator"))
                        val review = store.supplyJobs().single { it.kind == "blind_review" }
                        check(store.storeVerifiedBatch(store.claimJob(review.id)!!, review,
                            listOf(VerifiedPoolQuestion(practice.prompt, options, options.single { it.text == practice.answer }.optionId,
                                "Synthetic explanation", practice.reference, "synthetic-generator", "synthetic-reviewer"))) == 1)
                        val question = sql(db.resolve("coach.sqlite"), "SELECT question_id FROM questions").single()
                        val mapping = BookPracticeMapping(BookPracticeSource(book.id, lesson.id, block.page, block.start, block.end, practice.reference),
                            "K", question, questionFingerprint(practice.prompt, options))
                        assertTrue(store.registerBookOriginal("C", book.id, canonical, Files.readAllBytes(pdf), listOf(mapping)))
                    }
                }
            }
            val port = ServerSocket(0).use { it.localPort }
            check(port !in setOf(18791, 8791, 8790, 3790, 19540))
            Properties().apply { setProperty("book", book.id); setProperty("lesson", lesson.id); setProperty("port", "$port") }
                .let { plan -> Files.newOutputStream(root.resolve("plan.properties")).use { plan.store(it, "Synthetic test only; no credentials") } }
            action(root)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun phase(root: Path, name: String, expected: Int): JsonObject {
        val output = Path.of("build/withdraw-review-evidence", root.fileName.toString(), name).toAbsolutePath()
        Files.createDirectories(output)
        val args = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8",
            "-cp", System.getProperty("java.class.path"), "com.coach.desktop.ReviewWithdrawalProcess", root.toString(), name, "$expected", output.toString())
        Files.writeString(output.resolve("argv.json"), LedgerJson.encodeToString(args))
        val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output.resolve("run.log").toFile()).start()
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Owned child JVM did not finish: ${process.pid()}")
        Files.writeString(output.resolve("exit.txt"), "${process.exitValue()}")
        assertEquals(0, process.exitValue(), "Product phase $name: ${Files.readAllBytes(output.resolve("run.log")).toString(Charsets.UTF_8)}")
        val result = LedgerJson.parseToJsonElement(Files.readString(output.resolve("result.json"))).jsonObject
        println("REVIEW_PHASE phase=$name exit=${process.exitValue()} record=$result")
        return result
    }

    @Test fun guessedQuestionIsInTheProductQueueAndSurvivesANewProcess() = fixture { root ->
        val answer = phase(root, "answer", 1)
        val reopened = phase(root, "inspect", 1)
        assertNotEquals(answer["pid"], reopened["pid"])
        assertEquals(answer["queue"], reopened["queue"])
        assertEquals(answer["answer"], reopened["answer"])
        assertEquals(answer["events"], reopened["events"])
    }

    @Test fun withdrawingTheSameBookRemovesItFromTheQueueInANewProcessButKeepsHistory() = fixture { root ->
        val before = phase(root, "answer", 1)
        val withdrawal = phase(root, "withdraw", 0)
        val after = phase(root, "inspect", 0)
        val offline = phase(root, "offline", 0)
        assertEquals(4, setOf(before["pid"], withdrawal["pid"], after["pid"], offline["pid"]).size)
        assertNotEquals(before["queue"], after["queue"])
        assertEquals(JsonArray(emptyList()), after["queue"])
        assertEquals(before["answer"], after["answer"])
        assertEquals(before["history"], after["history"])
        assertEquals(before["events"], after["events"])
        assertEquals(after["queue"], offline["queue"])
        assertEquals(before["answer"], offline["answer"])
    }

    @Test fun sourceStatusIsReadOnlyAndRefusesForeignDevicesAndAgentFields() = fixture { root ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(root.resolve("server"))
        val actor = LedgerActor(scope, store)
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8)) }
        try {
            server.start(false)
            val base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().single().port }}"
            check(java.net.URI(base).port !in setOf(18791, 8791, 8790, 3790, 19540))
            val settings = DesktopConnectionSettings(root, { null }, mac = false, property = { key ->
                if (key == "jpackage.app-path") "/synthetic/Coach" else null
            })
            val code = runBlocking { actor.execute { createPairingCode().code } }
            val identity = runBlocking { DesktopPairing(settings).pair(base, code, "Synthetic status boundary") }
            val source = sql(root.resolve("server/coach.sqlite"), "SELECT mapping_json FROM book_practice_mappings").single()
                .let { LedgerJson.decodeFromString<BookPracticeMapping>(it).source }
            val body = LedgerJson.encodeToString(source)
            val path = "/v1/courses/C/book-practice-status"
            val before = runBlocking { actor.execute { events("C") } }
            assertEquals(401, practiceHttp(base, path, body, "foreign-device", identity.credentials.token).statusCode())
            val agent = JsonObject(LedgerJson.encodeToJsonElement(source).jsonObject + ("agentRunId" to JsonPrimitive("assistant")))
            assertEquals(400, practiceHttp(base, path, agent.toString(), identity.credentials.deviceId, identity.credentials.token).statusCode())
            val unposed = practiceHttp(base, path, body, identity.credentials.deviceId, identity.credentials.token)
            assertEquals(409, unposed.statusCode())
            assertEquals("BOOK_PRACTICE_UNPOSED", LedgerJson.parseToJsonElement(unposed.body()).jsonObject.str("error"))
            assertEquals(before, runBlocking { actor.execute { events("C") } })
            assertEquals("0", sql(root.resolve("server/coach.sqlite"), "SELECT COUNT(*) FROM book_practice_poses").single())
            println("REVIEW_STATUS wrongDevice=401 agentField=400 unposed=409 newPoses=0 newEvents=0")
        } finally { server.stop(0, 0); runBlocking { actor.close() }; scope.cancel(); store.close() }
    }

    companion object {
        internal fun sql(file: Path, query: String): List<String> = DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { statement -> statement.executeQuery(query).use { rows -> buildList {
                while (rows.next()) add((1..rows.metaData.columnCount).joinToString("|") { rows.getString(it).orEmpty() })
            } } }
        }
    }
}

@OptIn(ExperimentalTestApi::class)
object ReviewWithdrawalProcess {
    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0]); val phase = args[1]; val expected = args[2].toInt(); val output = Path.of(args[3])
        val plan = Properties().apply { Files.newInputStream(root.resolve("plan.properties")).use { load(it) } }
        val bookId = plan.getProperty("book"); val lessonId = plan.getProperty("lesson")
        val library = TextbookLibrary(root.resolve("library"))
        val database = root.resolve("server")
        val dbFile = database.resolve("coach.sqlite")
        var withdrawal: BookOriginalWithdrawal? = null
        if (phase == "withdraw") {
            FileChannel.open(database.resolve("server.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.tryLock().use { lock ->
                    checkNotNull(lock)
                    LedgerStore(database).use { store -> withdrawal = store.withdrawBookOriginal("C", bookId) }
                }
            }
            check(withdrawal!!.mappings == 1 && withdrawal!!.pages > 0)
        } else {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store = if (phase == "offline") null else LedgerStore(database)
            val actor = store?.let { LedgerActor(scope, it) }
            val server = actor?.let { value -> embeddedServer(Netty, host = "127.0.0.1", port = plan.getProperty("port").toInt()) {
                ledgerRoutes(value, MutableSharedFlow(extraBufferCapacity = 8))
            }.apply { start(false) } }
            val base = "http://127.0.0.1:${plan.getProperty("port")}" 
            val settings = DesktopConnectionSettings(root, environment = { key ->
                if (key == "COACH_CLIENT_DIR") root.resolve("client").toString() else null
            }, mac = false, property = { key -> if (key == "jpackage.app-path") "/synthetic/Coach" else null })
            try {
                if (phase == "answer") {
                    val code = runBlocking { actor!!.execute { createPairingCode().code } }
                    runBlocking { DesktopPairing(settings).pair(base, code, "Synthetic review process") }
                    WindowsBookPracticeConnection(settings, library).use { it.selectCourse(bookId, "C") }
                }
                runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
                    setContent { WindowsTextbooksContent(library.directory, settings) { null } }
                    waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("home-learn").fetchSemanticsNodes().isNotEmpty() }
                    if (phase == "answer") {
                        val book = library.book(bookId); val lesson = book.lessons.single { it.id == lessonId }
                        val practice = checkNotNull(StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE))
                        onNodeWithTag("home-learn").performClick()
                        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("原题已备好，可同步本次作答。").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("guess-mark").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 30_000) { library.answer(bookId, lessonId, QuestionMode.CHOICE).guessed }
                        onNodeWithTag("choice-${practice.options.indexOf(practice.answer)}").performScrollTo().performClick()
                        waitUntil(timeoutMillis = 30_000) { onAllNodesWithText("原作答已同步。").fetchSemanticsNodes().isNotEmpty() }
                    } else {
                        onNodeWithText("今天").performClick()
                        waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("host-today-book").fetchSemanticsNodes().isNotEmpty() }
                        val observed = json("pid" to ProcessHandle.current().pid(), "book" to bookId,
                            "queue" to library.reviews().map { "${it.book}|${it.lesson}|${it.mode.name}" })
                        Files.writeString(output.resolve("observed-queue.json"), observed.toString())
                        println("QUEUE_BEFORE_UI=$observed")
                        if (expected == 0) onNodeWithTag("host-today-review").assertDoesNotExist()
                        else onNodeWithTag("host-today-review").assertExists()
                    }
                }
            } finally { server?.stop(0, 0); if (actor != null) runBlocking { actor.close() }; scope.cancel(); store?.close() }
        }
        val queue = library.reviews().map { json("book" to it.book, "lesson" to it.lesson, "mode" to it.mode.name,
            "guessed" to it.originalGuessed, "completed" to it.completed) }
        val record = json("pid" to ProcessHandle.current().pid(), "phase" to phase, "book" to bookId,
            "queue" to JsonArray(queue), "answer" to WithdrawnReviewProcessTest.sql(library.directory.resolve("library.sqlite"),
                "SELECT book,lesson,mode,text,revealed,guessed FROM answers ORDER BY book,lesson,mode"),
            "history" to WithdrawnReviewProcessTest.sql(library.directory.resolve("library.sqlite"),
                "SELECT book,lesson,mode,wrong_text,wrong_guessed,completed FROM book_reviews ORDER BY book,lesson,mode"),
            "events" to WithdrawnReviewProcessTest.sql(dbFile, "SELECT seq,type FROM events ORDER BY seq"),
            "mappings" to WithdrawnReviewProcessTest.sql(dbFile, "SELECT COUNT(*) FROM book_original_sha").single(),
            "withdrawnMappings" to withdrawal?.mappings)
        Files.writeString(output.resolve("result.json"), record.toString())
        if (phase != "withdraw") check(queue.size == expected) { "Expected queue size $expected, actual ${queue.size}: $record" }
        check(WithdrawnReviewProcessTest.sql(library.directory.resolve("library.sqlite"), "SELECT COUNT(*) FROM book_reviews").single() == "1")
        check(library.answer(bookId, lessonId, QuestionMode.CHOICE).guessed)
    }
}
