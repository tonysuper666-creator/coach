package com.coach.desktop.books

import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.desktop.books.exam.LocalExamStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

private const val FIRST = "How do these samples differ?"
private const val FOLLOW = "Which sample comes next?"
private const val PART = "The first synthetic sample"

private fun explainer(root: Path, library: TextbookLibrary, base: String) =
    object : BookExplainer(DesktopConnectionSettings(home = root, environment = { null }), library) {
        init {
            identity = DesktopIdentity(base, PairedCredentials("synthetic-device", "synthetic-token"))
            loaded = true
        }
    }

internal object InterruptedAnswerChild {
    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0])
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.book(args[1])
        val block = book.lessons.first().blocks.first { !it.heading }
        val turn = library.beginBookQuestion(book.id, "thread", block, "", FIRST, listOf(block))
        runBlocking {
            withContext(BookAnswerProgress { text ->
                library.saveBookQuestionProgress(turn, text)
                Runtime.getRuntime().halt(23)
            }) { explainer(root, library, args[2]).explainSelection("ask", block, question = FIRST) }
        }
    }
}

class BookAnswerRecoveryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun killedDuringHttpAnswerReopensWithPartialAndCanFollowUp() = runBlocking {
        val root = folder.root.toPath()
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(Files.writeString(root.resolve("sample.txt"), "Two synthetic samples in a row."))
        ExplainConsent(library.directory).allow(book.id)
        val block = book.lessons.first().blocks.first { !it.heading }
        val firstCommitted = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val stream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stream.createContext("/v1/library/explain") { call ->
            call.use {
                val request = Json.parseToJsonElement(call.requestBody.bufferedReader().readText()).jsonObject
                val first = request["question"]?.jsonPrimitive?.content == FIRST
                check(call.requestHeaders.getFirst("Accept").contains("application/x-ndjson"))
                if (first) {
                    val pending = library.lastUnfinishedQuestion(book.id, "thread")
                    check(pending?.question == FIRST && library.loadLatestAskThread(book.id)!!.turns.single().explanation.text.isEmpty())
                    firstCommitted.countDown()
                } else {
                    check(request["history"]!!.jsonArray.single().jsonObject["answer"] == JsonPrimitive(PART))
                }
                call.responseHeaders.set("Content-Type", "application/x-ndjson")
                call.sendResponseHeaders(200, 0)
                val body = call.responseBody.bufferedWriter(Charsets.UTF_8)
                body.appendLine("""{"meta":{"sources":[{"anchor":"S1","docId":"${block.id}","pdfPage":${block.page}}]}}""")
                body.appendLine(if (first) """{"delta":"$PART"}""" else """{"delta":"The other sample follows."}""")
                body.flush()
                if (first) resume.await(45, TimeUnit.SECONDS) else {
                    body.appendLine("""{"done":true}""")
                    body.flush()
                }
            }
        }
        stream.start()
        try {
            val base = "http://127.0.0.1:${stream.address.port}"
            val classpath = (generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
                .flatMap { it.urLs.asSequence() }.map { File(it.toURI()).path }.toList() +
                System.getProperty("java.class.path").split(File.pathSeparator)).distinct().joinToString(File.pathSeparator)
            val args = root.resolve("child.args")
            Files.writeString(args, "-cp\n\"" + classpath.replace("\\", "/") + "\"\n")
            val log = root.resolve("child.log")
            val child = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@$args", InterruptedAnswerChild::class.java.name, root.toString(), book.id, base)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                assertTrue(child.waitFor(45, TimeUnit.SECONDS), "Child must receive a fragment before HTTP EOF")
                assertEquals(23, child.exitValue(), Files.readString(log))
                assertEquals(0L, firstCommitted.count)
            } finally {
                resume.countDown()
                if (child.isAlive) child.destroyForcibly().waitFor()
            }
            val reopened = TextbookLibrary(library.directory)
            val thread = assertNotNull(reopened.loadLatestAskThread(book.id))
            val interrupted = assertNotNull(reopened.lastUnfinishedQuestion(book.id, thread.threadId))
            assertEquals(FIRST, interrupted.question)
            val entry = thread.turns.single()
            assertEquals(PART, entry.explanation.text)
            val turn = reopened.beginBookQuestion(book.id, thread.threadId, block, "", FOLLOW, listOf(block))
            val answer = withContext(BookAnswerProgress { reopened.saveBookQuestionProgress(turn, it) }) {
                explainer(root, reopened, base).explainSelection("ask", block, question = FOLLOW,
                    history = listOf(FollowUpTurn(entry.label, entry.explanation.text)))
            }
            reopened.finishBookQuestion(turn, assertIs<ExplainResult.Done>(answer).explanation)
            val history = assertNotNull(TextbookLibrary(library.directory).loadLatestAskThread(book.id)).turns
            assertEquals(listOf(PART, "The other sample follows."), history.map { it.explanation.text })
            assertEquals(block.id, history.last().explanation.citations.single().blockId)
            assertNull(reopened.lastUnfinishedQuestion(book.id, thread.threadId))
        } finally { resume.countDown(); stream.stop(0) }
    }

    @Test fun v8UpgradePreservesExistingQuestionsAndCitations() {
        val root = folder.root.toPath()
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(Files.writeString(root.resolve("sample.txt"), "A synthetic example for migration."))
        val block = book.lessons.first().blocks.first { !it.heading }
        val answer = Explanation("A saved reply.", listOf(Citation("S1", block.id, block.page)), 12)
        library.saveAskTurn(book.id, "legacy", block, "", "ask", "问：$FIRST", FIRST, answer, listOf(block))
        library.withCourseDatabase { db -> db.createStatement().use {
            it.execute("ALTER TABLE book_ask_turns DROP COLUMN answer_status")
            it.execute("ALTER TABLE book_ask_turns DROP COLUMN write_id")
            it.execute("PRAGMA user_version = 8")
        } }
        val upgraded = TextbookLibrary(library.directory)
        val entry = assertNotNull(upgraded.loadLatestAskThread(book.id)).turns.single()
        assertEquals("问：$FIRST", entry.label)
        assertEquals(answer, entry.explanation)
        assertNull(upgraded.lastUnfinishedQuestion(book.id, "legacy"))
        assertTrue(Files.isRegularFile(library.directory.resolve("library.sqlite.v8.bak")))
    }

    @Test fun retryUpdatesOneTurnAndRejectsItsStaleWriter() {
        val root = folder.root.toPath()
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(Files.writeString(root.resolve("sample.txt"), "A synthetic example for a retry."))
        val block = book.lessons.first().blocks.first { !it.heading }
        val first = library.beginBookQuestion(book.id, "thread", block, "", FIRST, listOf(block))
        library.saveBookQuestionProgress(first, PART)
        library.finishBookQuestion(first, null)
        val retry = library.beginBookQuestion(book.id, "thread", block, "", FIRST, listOf(block), first)
        assertFails { library.saveBookQuestionProgress(first, "stale") }
        library.finishBookQuestion(first, null)
        assertEquals(PART, library.loadLatestAskThread(book.id)!!.turns.single().explanation.text)
        val final = Explanation("The answer.", listOf(Citation("S1", block.id, block.page)), 7)
        library.finishBookQuestion(retry, final)
        assertEquals(final, library.loadLatestAskThread(book.id)!!.turns.single().explanation)
        assertFails { library.beginBookQuestion(book.id, "thread", block, "", FIRST, listOf(block), retry) }
    }

    @Test fun completeAnswerSurvivesEchoOfThePendingTurn() = runBlocking {
        val root = folder.root.toPath()
        val library = syncedLibrary(root.resolve("library"))
        val book = library.importBook(Files.writeString(root.resolve("sample.txt"), "Two synthetic samples in a row."))
        val block = book.lessons.first().blocks.first { !it.heading }
        val turn = library.beginBookQuestion(book.id, "thread", block, "", FIRST, listOf(block))
        val remote = PendingAskEcho(SYNC_EPOCH, SYNC_SCOPE)
        LearningTransfer(library.directory.resolve("library.sqlite"), false).sync(SYNC_SCOPE, remote, SYNC_EPOCH) { true }
        val echoed = assertNotNull(remote.ask, "the pending ask must be offered back as a sync change")
        assertEquals("pending", echoed.getValue("body").jsonObject.getValue("answer_status").jsonPrimitive.content)
        val kept = assertNotNull(library.lastUnfinishedQuestion(book.id, "thread"))
        assertEquals(turn.writeId, kept.writeId, "echo of this device's pending ask must keep the in-flight write token")

        val other = syncedLibrary(root.resolve("other"))
        other.importBook(Files.writeString(root.resolve("other-sample.txt"), "Two synthetic samples in a row."))
        LearningTransfer(other.directory.resolve("library.sqlite"), false).sync(SYNC_SCOPE, remote, SYNC_EPOCH) { true }
        val restored = assertNotNull(other.lastUnfinishedQuestion(book.id, "thread"))
        assertTrue(restored.writeId.isNotBlank())
        assertNotEquals(turn.writeId, restored.writeId, "a device with no token still mints one for a restored unfinished ask")

        val answerText = "The samples differ by order."
        val stream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stream.createContext("/v1/library/explain") { call ->
            call.use {
                check(call.requestHeaders.getFirst("Accept").contains("application/x-ndjson"))
                call.requestBody.readBytes()
                call.responseHeaders.set("Content-Type", "application/x-ndjson")
                call.sendResponseHeaders(200, 0)
                call.responseBody.bufferedWriter(Charsets.UTF_8).use { body ->
                    body.appendLine("""{"meta":{"sources":[{"anchor":"S1","docId":"${block.id}","pdfPage":${block.page}}],"elapsedMs":4}}""")
                    body.appendLine("""{"delta":"$answerText"}""")
                    body.appendLine("""{"done":true}""")
                }
            }
        }
        stream.start()
        try {
            val base = "http://127.0.0.1:${stream.address.port}"
            val answer = withContext(BookAnswerProgress { library.saveBookQuestionProgress(turn, it) }) {
                explainer(root, library, base).explainSelection("ask", block, question = FIRST)
            }
            val done = assertIs<ExplainResult.Done>(answer, answer.toString())
            library.finishBookQuestion(turn, done.explanation)
            val stored = assertNotNull(library.loadLatestAskThread(book.id)).turns.single()
            assertEquals(answerText, stored.explanation.text)
            assertEquals(block.id, stored.explanation.citations.single().blockId)
            assertNull(library.lastUnfinishedQuestion(book.id, "thread"))
        } finally { stream.stop(0) }
    }

    private fun syncedLibrary(dir: Path): TextbookLibrary {
        val library = TextbookLibrary(dir)
        LocalExamStore(library)
        LearningJournal.installProfile(library)
        library.withCourseDatabase { db ->
            db.createStatement().use { sql ->
                sql.executeUpdate(
                    """INSERT OR REPLACE INTO learning_sync_meta(key,value) VALUES
                    ('epoch','$SYNC_EPOCH'),('transport','$SYNC_EPOCH'),('snapshot_done','1'),('cursor','0')""",
                )
            }
        }
        return library
    }
}

private const val SYNC_SCOPE = "sable-abababababababababababababababababababababababababababababababab"
private const val SYNC_EPOCH = "11111111-1111-4111-8111-111111111111"

/** Serves the device's own pending ask back once, the way the changes feed echoes a push. */
private class PendingAskEcho(private val epoch: String, private val scope: String) : LearningRemote {
    override suspend fun part(epoch: String, transport: String, snapshot: String?, seq: Long, offset: Int): JsonObject? =
        error("No chunked records in the echo fixture")
    var ask: JsonObject? = null

    override suspend fun snapshot(epoch: String, token: String?, after: Long): JsonObject? = null

    override suspend fun push(change: JsonObject): JsonObject {
        if (change["kind"]?.jsonPrimitive?.content == "ask_turn" && change["after"] is JsonObject) {
            ask = buildJsonObject {
                put("seq", 1)
                put("kind", "ask_turn")
                put("id", change.getValue("id"))
                put("body", change.getValue("after"))
                put("round", change.getValue("round"))
                put("clock", change.getValue("clock"))
            }
        }
        return buildJsonObject {
            put("scope", scope)
            put("epoch", epoch)
            put("seq", change.getValue("seq"))
        }
    }

    override suspend fun changes(epoch: String?, after: Long, stream: String): JsonObject = buildJsonObject {
        put("scope", scope)
        put("epoch", this@PendingAskEcho.epoch)
        put("transportEpoch", this@PendingAskEcho.epoch)
        put("backedThrough", 1_000_000)
        val echoed = ask
        put("changes", if (after == 0L && echoed != null) JsonArray(listOf(echoed)) else JsonArray(emptyList()))
    }
}
