package com.coach.desktop.books

import com.coach.domain.*
import com.coach.protocol.*
import com.coach.store.*
import com.coach.core.LedgerActor
import com.coach.server.ledgerRoutes
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

/** Real offline import/review pipeline and paired HTTP; synthetic, non-production content only. */
internal fun installPracticeMapping(store: LedgerStore, root: java.nio.file.Path, seed: ContentSeed,
    book: Textbook, lesson: BookLesson, practice: Practice, options: List<Option>): String {
    store.seedContent(seed)
    // Exercise the existing fenced generation -> blind review -> verified bank import, not registerQuestion(VERIFIED).
    val id = store.enqueueSupply("C", "K", "Synthetic definition recall", practice.reference)
    val generation = store.supplyJobs().single { it.id == id }
    check(store.queueBlindReview(store.claimJob(id)!!, generation, JsonArray(emptyList()), "synthetic-generator"))
    val review = store.supplyJobs().single { it.kind == "blind_review" }
    check(store.storeVerifiedBatch(store.claimJob(review.id)!!, review, listOf(VerifiedPoolQuestion(
        practice.prompt, options, options.single { it.text == practice.answer }.optionId,
        "Synthetic reviewed definition", practice.reference, "synthetic-generator", "synthetic-independent-reviewer"))) == 1)
    val qid = DriverManager.getConnection("jdbc:sqlite:${root.resolve("server/coach.sqlite")}").use { db ->
        db.createStatement().use { s -> s.executeQuery("SELECT question_id FROM questions").use { r -> check(r.next()); r.getString(1) } }
    }
    val block = lesson.blocks.first { !it.heading && it.text.contains(practice.reference) }
    val source = practiceSource(book, lesson, block, practice)
    // Same serialized ContentSeed entry point used by seed-content's explicit --book-practice-mappings.
    // Keeping the probe free of new production types also lets it run on the old PR238 head (HTTP 404).
    val mapped = JsonObject(LedgerJson.encodeToJsonElement(ContentSeed.serializer(), seed).jsonObject +
        ("bookPracticeMappings" to JsonArray(listOf(json("source" to source, "kpId" to "K", "questionId" to qid,
            "questionFingerprint" to questionFingerprint(practice.prompt, options))))))
    store.seedContent(LedgerJson.decodeFromJsonElement<ContentSeed>(mapped))
    return qid
}

internal fun practiceSource(book: Textbook, lesson: BookLesson, block: SourceBlock, practice: Practice) = json(
    "bookSha256" to book.id, "lessonId" to lesson.id, "pdfPage" to block.page,
    "start" to block.start, "end" to block.end, "reference" to practice.reference)

internal fun practiceHttp(base: String, path: String, body: String, device: String = "phone",
    token: String = "synthetic-book-practice-private-token-32c"): HttpResponse<String> =
    HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + path))
        .header("Authorization", "Bearer $token").header("X-Coach-Device", device)
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString())

class BookPracticePoseHttpTest {
    @Test fun existingOwnerCliMappingCanBePosedOverAuthenticatedHttp() {
        val root = java.nio.file.Files.createTempDirectory("book-practice-cli-").toRealPath()
        try {
            val snapshot = root.resolve("snapshot")
            fun file(path: String, text: String) {
                val p = snapshot.resolve(path); java.nio.file.Files.createDirectories(p.parent)
                java.nio.file.Files.writeString(p, text)
            }
            file("copy-stats.json", "{}")
            file("data/user/workspace/courses/courses.json", """{"courses":[{"id":"C","name":"ECNM10119 synthetic","resources":[{"kind":"mastery_path","ref_id":"main"}]}]}""")
            val point = json("id" to "K", "module_id" to "M", "name" to "synthetic definition")
            val module = json("id" to "M", "name" to "05 synthetic", "knowledge_points" to listOf(point))
            val reference = json("document_key" to "synthetic.pdf", "page_index" to 0, "page_label" to "1")
            val evidence = json("K" to json("sources" to listOf(json("ref" to reference))))
            val state = json("modules" to listOf(module), "lesson_evidence" to evidence)
            val legacy = snapshot.resolve("data/user/workspace/learning/mastery/mastery.sqlite3")
            java.nio.file.Files.createDirectories(legacy.parent)
            DriverManager.getConnection("jdbc:sqlite:$legacy").use { db ->
                db.createStatement().use { it.execute("CREATE TABLE mastery_paths(path_id TEXT,state_json TEXT)") }
                db.prepareStatement("INSERT INTO mastery_paths VALUES('main',?)").use { it.setString(1, state.toString()); it.executeUpdate() }
            }
            file("library/ECNM10119-健康经济学/教材/01-synthetic.md",
                "来源：synthetic.pdf\n<!-- PDF 第 1 页 · 书第 1 页 -->\nSynthetic first page definition.\n")
            file("exam-weights.md", "| 分值 | 题 | 对应知识点 | 状态 |\n| 1 | Synthetic | 05 RS | pending |\n")
            val database = root.resolve("server")
            val args = arrayOf("seed-content", "--snapshot", snapshot.toString(), "--database", database.toString(),
                "--out", root.resolve("output").toString())
            com.coach.migrate.main(args)
            val seed = com.coach.migrate.contentSeed(snapshot)
            val doc = seed.documents.single(); val page = doc.pages.single()
            val quote = "Synthetic first page definition."
            val options = listOf(Option("a", "first"), Option("b", "second"))
            val stem = "Which synthetic page is this?"
            LedgerStore(database).use { store ->
                val id = store.enqueueSupply("C", "K", "Synthetic recall", quote)
                val job = store.supplyJobs().single { it.id == id }
                assertTrue(store.queueBlindReview(store.claimJob(id)!!, job, JsonArray(emptyList()), "synthetic-generator"))
                val review = store.supplyJobs().single { it.kind == "blind_review" }
                assertEquals(1, store.storeVerifiedBatch(store.claimJob(review.id)!!, review, listOf(
                    VerifiedPoolQuestion(stem, options, "a", "Synthetic explanation", quote,
                        "synthetic-generator", "synthetic-independent-reviewer"))))
            }
            val question = DriverManager.getConnection("jdbc:sqlite:${database.resolve("coach.sqlite")}").use { db ->
                db.createStatement().use { s -> s.executeQuery("SELECT question_id FROM questions").use { r -> assertTrue(r.next()); r.getString(1) } }
            }
            val source = BookPracticeSource(sha256(doc.markdown.toByteArray()), "synthetic-lesson", page.pdfPage, 0, page.text.length, quote)
            val mappings = root.resolve("practice-mappings.json")
            java.nio.file.Files.writeString(mappings, LedgerJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(BookPracticeMapping.serializer()),
                listOf(BookPracticeMapping(source, "K", question, questionFingerprint(stem, options)))))
            com.coach.migrate.main(args + arrayOf("--book-practice-mappings", mappings.toString()))
            LedgerStore(database).use { store ->
                store.enroll("phone", "synthetic-book-practice-private-token-32c")
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val actor = LedgerActor(scope, store)
                val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8)) }
                try {
                    server.start(false)
                    val port = runBlocking { server.engine.resolvedConnectors().first().port }
                    val r = practiceHttp("http://127.0.0.1:$port", "/v1/courses/C/book-practice-pose",
                        LedgerJson.encodeToString(BookPracticeSource.serializer(), source))
                    assertEquals(200, r.statusCode(), r.body())
                    val pose = LedgerJson.parseToJsonElement(r.body()).jsonObject.getValue("events").jsonArray.single().jsonObject
                    assertEquals(question, pose.getValue("payload").jsonObject.str("questionId"))
                    assertEquals("phone", pose.str("deviceId"))
                    assertTrue(runBlocking { actor.execute { events("C").none { it.type == "answer_submitted" } } })
                    println("BOOK_PRACTICE_HTTP ownerCLI->reviewedMapping->authenticatedPose=true")
                } finally { server.stop(0, 0); runBlocking { actor.close() }; scope.cancel() }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun ownerImportRequiresAnExplicitMappingAndATrustedAnchorAtomically() = BookPracticeOutboxTest.F().use { f ->
        fun seed(anchor: SourceAnchor): ContentSeed {
            val content = f.seed.content.copy(knowledgePoints = f.seed.content.knowledgePoints.map { it.copy(anchors = listOf(anchor)) })
            val value = JsonObject(LedgerJson.encodeToJsonElement(ContentSeed.serializer(), f.seed.copy(content = content)).jsonObject +
                ("bookPracticeMappings" to JsonArray(listOf(json("source" to f.source(), "kpId" to "K", "questionId" to f.questionId,
                    "questionFingerprint" to questionFingerprint(f.practice.prompt, f.options))))))
            return LedgerJson.decodeFromJsonElement<ContentSeed>(value)
        }
        val before = f.serverEvents()
        val untrusted = seed(SourceAnchor("D", 1)) // a chapter-range hint alone is not a trusted quote/physical reference
        assertFailsWith<ReviewVariantRejected> { runBlocking { f.actor.execute { seedContent(untrusted) } } }
        assertEquals(before, f.serverEvents())
        assertEquals(f.seed.content, runBlocking { f.actor.execute { courseContent("C") } }, "failed import rolls back metadata too")
        val explicit = seed(SourceAnchor("D", 1, basis = "SOURCE_REFERENCE"))
        runBlocking { f.actor.execute { assertTrue(seedContent(explicit)); assertFalse(seedContent(explicit)) } }
        assertEquals(200, f.actualPose().statusCode())
        // An ordinary content import has no implicit mapping: future registration remains unready.
        runBlocking { f.actor.execute { seedContent(f.seed) } }
        val unmapped = f.serverEvents()
        assertEquals(409, f.actualPose().statusCode()); assertEquals(unmapped, f.serverEvents())
    }

    @Test fun realImportPoseOutboxReceiptProducerAndReopen() = BookPracticeOutboxTest.F().use { f ->
        val before = f.serverEvents()
        val response = f.actualPose()
        assertEquals(200, response.statusCode(), response.body())
        val pose = LedgerJson.decodeFromJsonElement<Event>(LedgerJson.parseToJsonElement(response.body()).jsonObject
            .getValue("events").jsonArray.single())
        val attempt = pose.payload.str("attemptId")
        assertEquals("phone", pose.deviceId); assertNull(pose.commandId)
        assertEquals(f.book.id, pose.payload.getValue("bookPractice").jsonObject.str("bookSha256"))
        assertEquals(before.size + 1, f.serverEvents().size)
        assertEquals(response.body(), f.actualPose().body(), "same device/block returns the identical server attempt")
        assertEquals(before.size + 1, f.serverEvents().size)
        assertFalse(response.body().contains("correctOptionId"), "the key stays server-side")
        runBlocking { f.sync.syncOnce() }
        val ready = assertIs<BookPracticeBinding.Ready>(f.bind())
        assertEquals(attempt, ready.attemptId)
        val command = BookPracticeOutbox(f.sync).submit(ready, PracticeAnswer(f.practice.answer, guessed = true), f.clock)
        val pending = f.serverEvents()
        f.restartClient()
        assertEquals(command, BookPracticeOutbox(f.sync).submit(f.bind() as BookPracticeBinding.Ready,
            PracticeAnswer(f.practice.answer, guessed = true), f.clock))
        assertEquals(pending, f.serverEvents())
        runBlocking { f.sync.syncOnce() }
        assertEquals("OK", f.sync.receipt(command)?.status)
        val answer = f.serverEvents().single { it.type == "answer_submitted" }
        assertEquals(attempt, answer.payload.str("attemptId")); assertEquals(command, answer.commandId)
        assertEquals("phone", answer.deviceId); assertTrue(answer.payload.flag("guessed"))
        val grade = f.serverEvents().single { it.type == "attempt_graded" }
        assertEquals(1L, grade.payload.num("score"))
        f.server.stop(0, 0); runBlocking { f.actor.close() }; f.store.close()
        LedgerStore(f.root.resolve("server")) { f.clock }.use { reopened ->
            val identity = reopened.publishBookLedgerSourceFromServerFacts(BookSourceLocator("C", "phone", attempt,
                f.book.id, f.lesson.id, f.block.page, f.block.start, f.block.end, f.practice.reference, f.practice.options))
            assertEquals(f.practice.answer, identity.originalAnswer); assertTrue(identity.guessed)
            assertEquals(attempt, identity.originalAttemptId)
            assertEquals(pose, reopened.events("C").single { it.type == "question_posed" })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val actor = LedgerActor(scope, reopened)
            val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8)) }
            try {
                server.start(false)
                val port = runBlocking { server.engine.resolvedConnectors().first().port }
                assertEquals(response.body(), practiceHttp("http://127.0.0.1:$port", "/v1/courses/C/book-practice-pose", f.source().toString()).body())
            } finally { server.stop(0, 0); runBlocking { actor.close() }; scope.cancel() }
            println("BOOK_PRACTICE_HTTP import->pose->outbox->receipt->reopen->producer attempt=$attempt guessed=true score=1 idempotent=true")
        }
    }

    @Test fun serverVoidBeforeAnOfflineAnswerArrivesAddsNoAnswerOrGrade() = BookPracticeOutboxTest.F().use { f ->
        assertEquals(200, f.actualPose().statusCode())
        runBlocking { f.sync.syncOnce() }
        val ready = assertIs<BookPracticeBinding.Ready>(f.bind())
        val command = BookPracticeOutbox(f.sync).submit(ready, PracticeAnswer(f.practice.answer, guessed = true), f.clock)
        runBlocking { f.actor.execute {
            append("C", "attempt_voided", json("attemptId" to ready.attemptId, "reason" to "synthetic-invalid"))
        } }
        val before = f.serverEvents()
        runBlocking { f.sync.syncOnce() }
        assertEquals("REJECTED", f.sync.receipt(command)?.status)
        assertEquals(before, f.serverEvents())
        assertTrue(f.serverEvents().none { it.type == "answer_submitted" || it.type == "attempt_graded" })
        println("BOOK_PRACTICE_HTTP offline-answer-after-server-void receipt=REJECTED learningDelta=0")
    }

    @Test fun refusedLocatorsAndCommandsAddNoLearningFacts() = BookPracticeOutboxTest.F().use { f ->
        fun refused(source: JsonObject, course: String = "C", device: String = "phone", token: String = f.token) {
            val before = f.serverEvents()
            val response = practiceHttp(f.base, "/v1/courses/$course/book-practice-pose", source.toString(), device, token)
            assertTrue(response.statusCode() in listOf(400, 401, 409), response.body())
            assertEquals(before, f.serverEvents()); println("BOOK_PRACTICE_HTTP refused=${response.statusCode()} delta=0")
        }
        refused(JsonObject(f.source() + ("bookSha256" to JsonPrimitive("0".repeat(64)))))
        refused(f.source(), course = "unknown")
        refused(JsonObject(f.source() + ("reference" to JsonPrimitive("tampered original quote"))))
        refused(JsonObject(f.source() + ("start" to JsonPrimitive(f.block.start + 1))))
        refused(JsonObject(f.source() + ("lessonId" to JsonPrimitive("unknown-lesson"))))
        refused(JsonObject(f.source() + ("keyState" to JsonPrimitive("VERIFIED"))))
        refused(JsonObject(f.source() + ("agentRunId" to JsonPrimitive("assistant"))))
        refused(f.source(), device = "tablet") // phone credential cannot impersonate tablet
        val response = f.actualPose(); assertEquals(200, response.statusCode(), response.body())
        val pose = LedgerJson.decodeFromJsonElement<Event>(LedgerJson.parseToJsonElement(response.body()).jsonObject.getValue("events").jsonArray.single())
        val answer = json("attemptId" to pose.payload.str("attemptId"), "answer" to "A", "posedSnapshot" to pose.payload)
        fun rejected(payload: JsonObject, device: String = "phone", token: String = f.token) {
            val before = f.serverEvents()
            val raw = canonicalJson(LedgerJson.encodeToJsonElement(CommandBody("submit_answer", "C", f.clock, payload)))
            val wire = WireCommand(device, UUID.randomUUID().toString(), raw, sha256(raw.toByteArray()))
            val r = practiceHttp(f.base, "/v1/commands", LedgerJson.encodeToString(WireCommand.serializer(), wire), device, token)
            assertEquals(200, r.statusCode(), r.body())
            assertEquals("REJECTED", LedgerJson.decodeFromString<Receipt>(r.body()).status)
            assertEquals(before, f.serverEvents(), "rejected answer adds no facts")
        }
        rejected(answer, "tablet", "synthetic-book-practice-tablet-token-32c!")
        rejected(JsonObject(answer + ("threadId" to JsonPrimitive("assistant-thread"))))
        rejected(JsonObject(answer + ("agentRunId" to JsonPrimitive("unknown-agent-scope"))))
        val changed = JsonObject(pose.payload + ("fingerprint" to JsonPrimitive("tampered")))
        rejected(JsonObject(answer + ("posedSnapshot" to changed)))
        val changedLink = JsonObject(pose.payload + ("bookPractice" to JsonObject(f.source() + ("lessonId" to JsonPrimitive("other")))))
        rejected(JsonObject(answer + ("posedSnapshot" to changedLink)))
        val fake = JsonObject(pose.payload + ("attemptId" to JsonPrimitive("unregistered-book-attempt")))
        rejected(json("attemptId" to "unregistered-book-attempt", "answer" to "A", "posedSnapshot" to fake))
        // A trusted mapping does not survive removal of its KP's physical source anchor.
        runBlocking { f.actor.execute { seedContent(f.seed.copy(content = f.seed.content.copy(knowledgePoints =
            f.seed.content.knowledgePoints.map { it.copy(anchors = emptyList()) }))) } }
        refused(f.source())
        rejected(answer)
        println("BOOK_PRACTICE_HTTP foreign-device/assistant/scope/fingerprint/link/unregistered/unknown-anchor factsDelta=0")
    }
}
