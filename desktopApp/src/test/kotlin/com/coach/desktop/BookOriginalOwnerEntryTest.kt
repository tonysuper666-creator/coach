package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
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
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

/** Actual owner CLI, original PDF, reviewed bank, ordinary form, authenticated HTTP and disk SQLite. */
@OptIn(ExperimentalTestApi::class)
class BookOriginalOwnerEntryTest {
    private class F : AutoCloseable {
        val root = Files.createTempDirectory("owner-original-entry-").toRealPath()
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
            mappings(listOf(mapping))
        }
        fun query(sql: String): List<String> = DriverManager.getConnection("jdbc:sqlite:${database.resolve("coach.sqlite")}").use { db ->
            db.createStatement().use { st -> st.executeQuery(sql).use { r -> buildList {
                while (r.next()) add((1..r.metaData.columnCount).joinToString("|") { r.getString(it).orEmpty() })
            } } }
        }
        fun counts() = listOf("book_original_sha", "book_original_pages", "book_original_imports", "book_practice_mappings", "book_practice_poses",
            "book_ledger_sources", "events", "questions", "answer_keys").associateWith { table ->
            if (query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").isEmpty()) "0"
            else query("SELECT COUNT(*) FROM $table").single()
        }
        fun mappings(value: List<BookPracticeMapping>) { Files.writeString(file, LedgerJson.encodeToString(value)) }
        fun register(hash: String = book.id, course: String = "C", blob: String = canonical, path: java.nio.file.Path = pdf) =
            com.coach.migrate.main(arrayOf("register-book-original", "--database", database.toString(), "--course", course,
                "--pdf", path.toString(), "--sha256", hash, "--canonical-sha256", blob, "--mappings", file.toString()))
        fun withdraw() = com.coach.migrate.main(arrayOf("withdraw-book-original", "--database", database.toString(), "--course", "C", "--sha256", book.id))
        fun rejected(action: () -> Unit) {
            val before = counts()
            assertFails { action() }
            assertEquals(before, counts(), "Refused owner input must roll back every mapping and learning fact")
        }
        fun commands(directory: java.nio.file.Path): List<Pair<String, String?>> = Files.walk(directory).use { paths ->
            paths.filter { it.fileName.toString() == "ledger.sqlite" }.toList().flatMap { file ->
                DriverManager.getConnection("jdbc:sqlite:$file").use { db -> db.createStatement().use { st ->
                    st.executeQuery("SELECT command_id,receipt_status FROM outbox").use { r -> buildList {
                        while (r.next()) add(r.getString(1) to r.getString(2))
                    } }
                } }
            }
        }
        override fun close() { root.toFile().deleteRecursively() }
    }
    private fun ComposeUiTest.await(text: String) = waitUntil(timeoutMillis = 30_000) {
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /** Copied unchanged to the old head: fails at its absent owner command, not at compilation. */
    @Test fun ownerCommandInstallsActualPdfMappingBeforeAnyPose() = F().use { f ->
        f.register()
        assertEquals(listOf("C|${f.book.id}|${f.canonical}"), f.query("SELECT * FROM book_original_sha"))
        assertEquals(f.book.pages.size.toString(), f.counts().getValue("book_original_pages"))
        assertEquals("0", f.counts().getValue("book_practice_poses"))
        val before = f.counts()
        f.register()
        assertEquals(before, f.counts())
    }

    @Test fun ownerRejectsHashCoursePageQuestionAndPartialSetsWithoutApprovalOrLearningWrites() = F().use { f ->
        f.rejected { f.register(hash = "a".repeat(64)) }
        f.rejected { f.register(course = "other-course") }
        LedgerStore(f.database).use { it.seedContent(ContentSeed(
            CourseContent(Course("other-course", "Other", "Synthetic"), emptyList(), emptyList()), emptyList())) }
        f.rejected { f.register(course = "other-course") } // A real different course cannot borrow C's canonical document.
        f.rejected { f.register(blob = "b".repeat(64)) }
        f.rejected { f.register(path = f.root.resolve("missing.pdf")) }
        for (bad in listOf(f.mapping.copy(source = f.source.copy(pdfPage = 500)),
            f.mapping.copy(kpId = "unknown"), f.mapping.copy(questionId = "unreviewed"),
            f.mapping.copy(questionFingerprint = "changed"), f.mapping.copy(source = f.source.copy(reference = "changed")))) {
            f.mappings(listOf(f.mapping, bad.copy(source = bad.source.copy(lessonId = "another-lesson"))))
            f.rejected { f.register() }
        }
        f.mappings(emptyList()); f.rejected { f.register() }; f.mappings(listOf(f.mapping))
        // Incomplete physical page collections cannot authorize even the otherwise valid first page.
        LedgerStore(f.database).use { it.seedContent(f.seed.copy(documents = listOf(f.seed.documents.single().copy(pages = f.pages.dropLast(1))))) }
        f.rejected { f.register() }
        LedgerStore(f.database).use { it.seedContent(f.seed) }
        // A different text on a genuine physical page is rejected even when its stored checksum is valid.
        val changed = f.pages.map { if (it.pdfPage == 2) it.copy(text = it.text.replace("green", "brown")) else it }
        val changedMd = "来源：synthetic.pdf\n" + changed.joinToString("") { it.text }
        LedgerStore(f.database).use { it.seedContent(f.seed.copy(documents = listOf(f.seed.documents.single().copy(markdown = changedMd, pages = changed)))) }
        f.rejected { f.register(blob = sha256(changedMd.toByteArray())) }
    }

    @Test fun offlineOwnerLockIsRequired() = F().use { f ->
        FileChannel.open(f.database.resolve("server.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            ch.lock().use { f.rejected { f.register() } }
        }
    }

    @Test fun pdfOwnerCliToOrdinaryFormPoseGuessAckAndReopenUsesOneRealAttempt() = F().use { f ->
        f.register()
        var store = LedgerStore(f.database)
        store.enroll("phone", f.token)
        val tabletToken = "t".repeat(43)
        store.enroll("tablet", tabletToken)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actor = LedgerActor(scope, store)
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8)) }
        server.start(false)
        val base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
        val settings = DesktopConnectionSettings(f.root, environment = { when (it) {
            "COACH_URL" -> base
            "COACH_CLIENT_DIR" -> f.root.resolve("client").toString()
            "COACH_TOKEN_FILE" -> f.root.resolve("unused").toString()
            else -> null
        } }, mac = false, property = { null })
        DesktopCredentials(settings.clientDirectory).save(base, PairedCredentials("phone", f.token))
        try {
            val before = runBlocking { actor.execute { events("C") } }
            val wire = LedgerJson.encodeToString(f.source)
            assertEquals(401, practiceHttp(base, "/v1/courses/C/book-practice-pose", wire, "tablet", f.token).statusCode())
            assertEquals(400, practiceHttp(base, "/v1/courses/C/book-practice-pose", JsonObject(
                LedgerJson.encodeToJsonElement(f.source).jsonObject + ("agentRunId" to JsonPrimitive("assistant"))).toString(), token = f.token).statusCode())
            assertEquals(409, practiceHttp(base, "/v1/courses/C/book-practice-pose", LedgerJson.encodeToString(f.source.copy(reference = "tampered")), token = f.token).statusCode())
            assertEquals(409, practiceHttp(base, "/v1/courses/unknown/book-practice-pose", wire, token = f.token).statusCode())
            assertEquals(before, runBlocking { actor.execute { events("C") } })
            runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
                setContent { WindowsTextbooksContent(f.library.directory, settings) { null } }
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("home-learn").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("open-settings").performClick()
                onNodeWithTag("book-practice-course").performTextInput("C")
                onNodeWithTag("book-source-register").performScrollTo().assertIsNotEnabled()
                onNodeWithTag("book-source-confirm").performScrollTo().performClick()
                onNodeWithTag("book-source-register").performScrollTo().performClick()
                await("已核对课程中确认过的原题，可以返回学习继续作答。")
                assertEquals("1", f.counts().getValue("book_practice_poses"))
                assertTrue(f.commands(settings.clientDirectory).isEmpty())
                val pose = runBlocking { actor.execute { events("C").single { it.type == "question_posed" } } }
                val payload = json("attemptId" to pose.payload.str("attemptId"), "answer" to "A", "posedSnapshot" to pose.payload)
                val before = runBlocking { actor.execute { events("C") } }
                fun rejected(payload: JsonObject, device: String = "phone", token: String = f.token) {
                    val raw = canonicalJson(LedgerJson.encodeToJsonElement(CommandBody("submit_answer", "C", System.currentTimeMillis(), payload)))
                    val wire = WireCommand(device, UUID.randomUUID().toString(), raw, sha256(raw.toByteArray()))
                    val response = practiceHttp(base, "/v1/commands", LedgerJson.encodeToString(wire), device, token)
                    assertEquals(200, response.statusCode(), response.body())
                    assertEquals("REJECTED", LedgerJson.decodeFromString<Receipt>(response.body()).status)
                    assertEquals(before, runBlocking { actor.execute { events("C") } })
                }
                rejected(payload, "tablet", tabletToken)
                rejected(JsonObject(payload + ("threadId" to JsonPrimitive("assistant-thread"))))
                rejected(JsonObject(payload + ("agentRunId" to JsonPrimitive("unknown-scope"))))
                rejected(JsonObject(payload + ("posedSnapshot" to JsonObject(pose.payload + ("fingerprint" to JsonPrimitive("tampered"))))))
                onNodeWithTag("close-settings").performClick(); onNodeWithTag("home-learn").performClick()
                await("原题已备好，可同步本次作答。")
                onNodeWithTag("guess-mark").performScrollTo().performClick()
                waitUntil(timeoutMillis = 30_000) { f.library.answer(f.book.id, f.lesson.id, QuestionMode.CHOICE).guessed }
                onNodeWithTag("choice-${f.practice.options.indexOf(f.practice.answer)}").performScrollTo().performClick()
                await("原作答已同步。")
            }
            val command = f.commands(settings.clientDirectory).single()
            assertEquals("OK", command.second)
            val events = runBlocking { actor.execute { events("C") } }
            val pose = events.single { it.type == "question_posed" }
            val answer = events.single { it.type == "answer_submitted" }
            assertEquals("phone", pose.deviceId); assertEquals("phone", answer.deviceId)
            assertEquals(pose.payload.str("attemptId"), answer.payload.str("attemptId"))
            assertEquals(command.first, answer.commandId); assertTrue(answer.payload.flag("guessed"))
            runSkikoComposeUiTest(size = Size(946f, 690f), density = Density(1f)) {
                setContent { WindowsTextbooksContent(f.library.directory, settings) { null } }
                waitUntil(timeoutMillis = 30_000) { onAllNodesWithTag("home-learn").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("home-learn").performClick(); await("原作答已同步。")
            }
            assertEquals(listOf(command), f.commands(settings.clientDirectory))
            assertEquals(events, runBlocking { actor.execute { events("C") } })
            server.stop(0, 0); runBlocking { actor.close() }; store.close()
            store = LedgerStore(f.database)
            val identity = store.publishBookLedgerSourceFromServerFacts(BookSourceLocator("C", "phone", pose.payload.str("attemptId"),
                f.book.id, f.lesson.id, f.block.page, f.block.start, f.block.end, f.practice.reference, f.practice.options))
            assertEquals(f.book.id, identity.bookSha256); assertEquals(f.canonical, identity.docBlobSha256)
            assertEquals(f.practice.answer, identity.originalAnswer); assertTrue(identity.guessed)
            assertFalse(TextbookLibrary(f.library.directory).reviews().single().completed)
            println("OWNER_PDF_CHAIN CLI->confirmedForm->HTTPpose->sameAttempt->guessedCorrect->OK->reopen->producer original=${identity.bookSha256} canonical=${identity.docBlobSha256}")
        } finally { server.stop(0, 0); runBlocking { actor.close() }; scope.cancel(); store.close() }
    }

    @Test fun withdrawnMappingCannotAddFacts() = F().use { f ->
        f.register()
        fun <T> http(action: (String, LedgerActor) -> T): T = LedgerStore(f.database).use { store ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val actor = LedgerActor(scope, store)
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8)) }
            try {
                server.start(false)
                action("http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}", actor)
            } finally { server.stop(0, 0); runBlocking { actor.close() }; scope.cancel() }
        }
        LedgerStore(f.database).use { it.enroll("phone", f.token) }
        val pose = http { base, _ ->
            val response = practiceHttp(base, "/v1/courses/C/book-practice-pose", LedgerJson.encodeToString(f.source), token = f.token)
            assertEquals(200, response.statusCode(), response.body())
            LedgerJson.decodeFromJsonElement<Event>(LedgerJson.parseToJsonElement(response.body()).jsonObject.getValue("events").jsonArray.single())
        }
        // The real pose already exists, while this original guessed answer is still offline.
        val raw = canonicalJson(LedgerJson.encodeToJsonElement(CommandBody("submit_answer", "C", System.currentTimeMillis(),
            json("attemptId" to pose.payload.str("attemptId"), "answer" to "A", "guessed" to true, "posedSnapshot" to pose.payload))))
        val wire = WireCommand("phone", UUID.randomUUID().toString(), raw, sha256(raw.toByteArray()))
        f.withdraw()
        http { base, actor ->
            val before = runBlocking { actor.execute { events("C") } }
            val response = practiceHttp(base, "/v1/commands", LedgerJson.encodeToString(wire), token = f.token)
            assertEquals(200, response.statusCode(), response.body())
            assertEquals("REJECTED", LedgerJson.decodeFromString<Receipt>(response.body()).status)
            assertEquals(409, practiceHttp(base, "/v1/courses/C/book-practice-pose", LedgerJson.encodeToString(f.source), token = f.token).statusCode())
            assertEquals(before, runBlocking { actor.execute { events("C") } })
            assertTrue(before.none { it.type == "answer_submitted" || it.type == "attempt_graded" })
            println("OWNER_PDF_WITHDRAW existingPose=true pendingGuessReceipt=REJECTED learningDelta=0")
        }
        assertEquals("0", f.counts().getValue("book_original_sha"))
        assertEquals("0", f.counts().getValue("book_original_pages"))
        assertEquals("0", f.counts().getValue("book_practice_mappings"))
    }
}
