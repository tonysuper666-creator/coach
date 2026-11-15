package com.coach.desktop.books

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.domain.reviewqueue.QueueQuestion
import com.coach.protocol.*
import com.coach.server.ledgerRoutes
import com.coach.store.LedgerStore
import com.coach.store.ReviewVariantRejected
import com.coach.store.sha256
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Properties
import java.util.UUID
import kotlin.random.Random
import kotlin.test.*

/** Owned HTTP + actual submit_answer command, independent content approval, real SQLite restart. */
class BookLedgerBridgeTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("book-ledger-bridge-")
        val source = syntheticTextbook(root.resolve("source"))
        val bytes = Files.readAllBytes(source)
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(source)
        val lesson = book.lessons.first()
        val practice = assertNotNull(StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE))
        val wrong = practice.options.first { it != practice.answer }
        var clock = 1_794_052_800_000L - 86_400_000
        val token = "synthetic-book-ledger-private-token-32chars"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(root.resolve("server")) { clock }
        val actor = LedgerActor(scope, store)
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8))
        }
        val identity: BookLedgerIdentity
        val http: CourseHttp
        val transport: HttpLedgerTransport
        private fun openDriver() = JdbcSqliteDriver("jdbc:sqlite:${root.resolve("client.sqlite")}", Properties(), ClientDatabase.Schema)
        var driver = openDriver()
        val base: String
        val sync: SyncEngine
        init {
            library.save(book.copy(learning = true))
            library.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE, PracticeAnswer(wrong, true))
            val document = book.pages.joinToString("\n") { it.text }
            val options = practice.options.mapIndexed { n, text -> Option("o$n", text) }
            val correct = options.single { it.text == practice.answer }.optionId
            val original = runBlocking { actor.execute {
                enroll("phone", token)
                seedContent(ContentSeed(CourseContent(Course("C", "C", "Synthetic"), listOf(Module("M", 1, "M")),
                    listOf(KnowledgePoint("K", "M", 1, "K", anchors = listOf(SourceAnchor("D", 1))))),
                    listOf(TextbookDocument("D", "Synthetic", document, book.pages.map { TextbookPage(it.number, null, it.text) }))))
                registerQuestion("original", "C", "", "SINGLE_CHOICE", practice.prompt, "VERIFIED", correct, options, listOf("K"))
                val pose = poseChoiceGroup("C", "g-original", listOf(json("attemptId" to "original-attempt",
                    "questionId" to "original", "kpIds" to listOf("K"), "purpose" to "PRACTICE", "policyVersion" to 1,
                    "mainline" to true, "posedAt" to clock)), Random(20), device = "phone").single()
                val map = pose.payload.getValue("letterMap").jsonObject
                val answer = map.getValue(options.single { it.text == wrong }.optionId).jsonPrimitive.content
                val body = CommandBody("submit_answer", "C", clock, json("attemptId" to "original-attempt", "answer" to answer,
                    "offline" to true, "posedSnapshot" to pose.payload))
                val raw = canonicalJson(LedgerJson.encodeToJsonElement(body))
                assertEquals("OK", command(WireCommand("phone", "real-original-answer", raw, sha256(raw.toByteArray()))).status)
                registerQuestion("variant", "C", "", "SINGLE_CHOICE", "Synthetic verified variation", "VERIFIED", correct, options, listOf("K"))
                pose
            } }
            val block = lesson.blocks.first { !it.heading && it.text.contains(practice.reference) }
            identity = BookLedgerIdentity(book.id, lesson.id,
                BookLedgerBlock(block.id, block.page, block.start, block.end, sha256(block.text.toByteArray())),
                "D", sha256(document.toByteArray()), "C", "K",
                QueueQuestion("original", null, original.payload.str("fingerprint")), "original-attempt",
                practice.prompt, practice.options, practice.reference, practice.answer, wrong, false)
            clock += 86_400_000
            server.start(false)
            base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().first().port }}"
            http = CourseHttp(base, "phone", token, "C")
            transport = HttpLedgerTransport(base, "phone", token)
            sync = newSync()
        }
        fun newSync() = SyncEngine(ClientDatabase(driver), "phone", transport, listOf("C"),
            { UUID.randomUUID().toString() }, ::sha256)
        fun restartSync(): SyncEngine { driver.close(); driver = openDriver(); return newSync() }
        fun review() = library.review(book.id, lesson.id, QuestionMode.CHOICE)
        suspend fun <T> db(block: LedgerStore.() -> T): T = actor.execute(block)
        suspend fun approve(i: BookLedgerIdentity = identity) = db { approveBookLedgerSource(i, bytes) }
        suspend fun coverage() = assertNotNull(http.bookLedgerCoverage())
        fun file(i: BookLedgerIdentity, c: BookLedgerCoverage) = Files.writeString(root.resolve("receipt.json"), json(
            "format" to "coach-book-ledger-source/v2", "identity" to LedgerJson.encodeToJsonElement(i),
            "coverage" to LedgerJson.encodeToJsonElement(c)).toString())
        fun bridge() = BookLedgerBridge(library, http)
        override fun close() {
            http.close(); transport.close(); driver.close(); server.stop(0, 0)
            runBlocking { actor.close() }; scope.cancel(); root.toFile().deleteRecursively()
        }
    }

    @Test fun completeRealIdentityTakesExactVariantRestoresAndRetriesWithoutInventedVersionOrAnswer() = runBlocking {
        Fixture().use { f ->
            val before = f.db { events("C") }
            assertFalse(f.review().canStartVariant)
            assertNull(f.bridge().registration(f.review()))
            assertFailsWith<IllegalArgumentException> { f.bridge().register(f.review(), f.file(f.identity, f.coverage())) }
            assertEquals(before, f.db { events("C") }, "A learner receipt cannot approve its own association")
            f.approve()
            val coverage = f.coverage()
            val expected = f.db { registerBookLedgerSource(f.identity, coverage) }
            val receipt = f.bridge().register(f.review(), f.file(f.identity, coverage))
            assertEquals(expected, receipt)
            assertNull(receipt.identity.question.version); assertNull(receipt.candidate.version)
            assertEquals("variant", receipt.candidate.id)
            assertNotEquals("local:${f.book.id}:${f.lesson.id}", receipt.entryId)
            assertEquals(before, f.db { events("C") }, "Registration cannot fabricate attempts or answers")
            val reply = f.bridge().take(f.review(), f.sync, 21000)
            assertNull(reply.error); assertNotNull(reply.body)
            val restarted = f.restartSync()
            val restored = assertNotNull(reviewPracticeTarget(restarted, "C"))
            assertEquals(receipt, restored.bookSource)
            assertEquals(receipt, BookLedgerBridge(TextbookLibrary(f.library.directory), f.http).registration(f.review()))
            val session = restoreReviewPracticeSession(restarted, "C", "K", null,
                CourseView(restarted.localEvents("C"), restarted.projection("C")), f.clock)
            assertIs<ReviewPracticeSession.Ready>(session)
            val settled = f.db { events("C") }
            assertNotNull(f.http.takeReviewVariant(restored).body)
            assertEquals(settled, f.db { events("C") })
            val pose = settled.single { it.type == "question_posed" && it.payload.str("attemptId") == receipt.attemptId }
            assertEquals(receipt.candidate.fingerprint, pose.payload.str("fingerprint"))
            assertEquals(1, settled.count { it.type == "answer_submitted" })
            assertEquals(f.wrong, f.review().originalAnswer)
            println("BOOK_LEDGER realCommand=true rawSha=${f.book.id} original=original@null/original-attempt candidate=variant@null/${receipt.attemptId} exactRetry=true restored=true answers=1")
        }
    }

    @Test fun rejectsEveryIdentityEdgeAndIncompleteExtraReorderedOrStalePoseSetsWithoutFacts() = runBlocking {
        Fixture().use { f ->
            val mutations: List<(BookLedgerIdentity) -> BookLedgerIdentity> = listOf(
                { it.copy(bookSha256 = "0".repeat(64)) }, { it.copy(docBlobSha256 = "0".repeat(64)) },
                { it.copy(block = it.block.copy(start = it.block.start + 1)) },
                { it.copy(block = it.block.copy(textSha256 = "0".repeat(64))) },
                { it.copy(docId = "other") }, { it.copy(courseId = "other") }, { it.copy(kpId = "other") },
                { it.copy(question = it.question.copy(id = "other")) },
                { it.copy(question = it.question.copy(fingerprint = "0".repeat(64))) },
                { it.copy(question = it.question.copy(version = "invented-v1")) },
                { it.copy(originalAttemptId = "made-up-attempt") }, { it.copy(prompt = "other prompt") },
                { it.copy(options = it.options.dropLast(1)) }, { it.copy(reference = "absent source") },
                { it.copy(correctAnswer = f.wrong) }, { it.copy(originalAnswer = f.practice.answer) }, { it.copy(guessed = true) })
            val before = f.db { events("C") }
            for ((n, mutate) in mutations.withIndex()) {
                assertFailsWith<ReviewVariantRejected>("edge $n") { f.approve(mutate(f.identity)) }
            }
            assertEquals(before, f.db { events("C") })
            f.approve()
            // Repeated question on another device is a second pose, not a duplicate to discard.
            f.db { poseChoiceGroup("C", "repeated-original", listOf(json("attemptId" to "repeat-attempt",
                "questionId" to "original", "kpIds" to listOf("K"), "purpose" to "PRACTICE", "policyVersion" to 1)),
                device = "other-device") }
            val c = f.coverage()
            assertEquals(2, c.poses.size)
            assertEquals(1, c.poses.map { it.question }.distinct().size)
            val beforeClaims = f.db { events("C") }
            val sets = listOf(c.copy(poses = emptyList()), c.copy(poses = c.poses.dropLast(1)),
                c.copy(poses = c.poses + c.poses.first()), c.copy(poses = c.poses.reversed()),
                c.copy(cursor = c.cursor - 1), c.copy(courseId = "other"),
                c.copy(poses = c.poses.map { it.copy(eventId = "made-up-event") }),
                c.copy(poses = c.poses.map { it.copy(attemptId = "made-up-attempt") }))
            for (set in sets) {
                assertNull(f.http.registerBookLedgerSource(f.identity, set))
                assertFailsWith<IllegalArgumentException> { f.bridge().register(f.review(), f.file(f.identity, set)) }
                assertNull(f.bridge().registration(f.review()))
            }
            assertEquals(beforeClaims, f.db { events("C") })
            assertFalse(f.review().canStartVariant)
            assertNotNull(f.bridge().register(f.review(), f.file(f.identity, c)), "Exact full repeated-pose coverage is accepted")
            println("BOOK_LEDGER_REJECT identityEdges=${mutations.size} coverageSets=${sets.size} newFacts=0")
        }
    }

    @Test fun localMismatchWithdrawalAndConcurrentPoseRefuseBeforeConsumption() = runBlocking {
        Fixture().use { f ->
            f.approve()
            val source = f.bridge().register(f.review(), f.file(f.identity, f.coverage()))
            f.library.saveAnswer(f.book.id, f.lesson.id, QuestionMode.CHOICE, PracticeAnswer(f.practice.answer, true))
            assertEquals(f.wrong, f.review().originalAnswer, "Later ordinary practice cannot overwrite the linked original")
            assertNotNull(f.bridge().registration(f.review()))
            f.library.saveAnswer(f.book.id, f.lesson.id, QuestionMode.CHOICE, PracticeAnswer(f.wrong, true))
            assertFailsWith<IllegalArgumentException> {
                f.bridge().register(f.review(), f.file(f.identity.copy(lessonId = "wrong-lesson"), f.coverage()))
            }
            f.db { poseChoiceGroup("C", "other-device", listOf(json("attemptId" to "other-device-attempt",
                "questionId" to "variant", "kpIds" to listOf("K"), "purpose" to "PRACTICE", "policyVersion" to 1)),
                device = "other-device") }
            val before = f.db { events("C") }
            val target = assertNotNull(prepareBookReviewPractice(f.sync, source, 21000))
            assertEquals("BOOK_POSED_SET_INCOMPLETE", f.http.takeReviewVariant(target).error)
            assertEquals(before, f.db { events("C") })
            assertNull(f.http.registerBookLedgerSource(f.identity, f.coverage()), "No unused verified candidate remains")
            f.db { withdrawBookLedgerSource(f.identity.bookSha256, "C", "original-attempt") }
            assertEquals("BOOK_SOURCE_UNLINKED", f.http.takeReviewVariant(target).error)
            assertEquals(before, f.db { events("C") })
        }
    }

    @Test fun changedRawBookOrWithdrawnOriginalKeyCannotConsumeTheSavedTarget() = runBlocking {
        Fixture().use { f ->
            f.approve()
            val source = f.bridge().register(f.review(), f.file(f.identity, f.coverage()))
            val raw = f.library.directory.resolve("${f.book.id}.${f.book.format}")
            Files.write(raw, byteArrayOf(0))
            assertNull(f.bridge().registration(f.review()))
            val before = f.db { events("C") }
            assertEquals("BOOK_SOURCE_UNLINKED", f.bridge().take(f.review(), f.sync, 21000).error)
            assertEquals(before, f.db { events("C") })
            Files.write(raw, f.bytes)
            assertNotNull(f.bridge().registration(f.review()))
            f.db { append("C", "question_key_changed", json("questionId" to "original", "keyState" to "UNREVIEWED"), "withdraw-original-key") }
            val afterWithdrawal = f.db { events("C") }
            val target = assertNotNull(prepareBookReviewPractice(f.sync, source, 21000))
            assertEquals("BOOK_QUESTION_MISMATCH", f.http.takeReviewVariant(target).error)
            assertEquals(afterWithdrawal, f.db { events("C") })
        }
    }

    @Test fun pairedHttpRejectsUnauthenticatedOversizedMalformedAndCrossCourseClaims() = runBlocking {
        Fixture().use { f ->
            val client = HttpClient.newHttpClient()
            suspend fun request(path: String, body: String? = null, auth: Boolean = true): Int = withContext(Dispatchers.IO) {
                val builder = HttpRequest.newBuilder(URI(f.base + "/v1/courses/$path"))
                    .timeout(java.time.Duration.ofSeconds(10))
                if (auth) builder.header("X-Coach-Device", "phone").header("Authorization", "Bearer ${f.token}")
                if (body != null) builder.POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json")
                client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode()
            }
            val before = f.db { events("C") }
            val body = Files.readString(f.file(f.identity, f.coverage()))
            assertEquals(401, request("C/book-review-coverage", auth = false))
            assertEquals(401, request("C/book-review-source", body, false))
            assertEquals(400, request("C/book-review-source", "{"))
            assertEquals(400, request("C/book-review-source", " ".repeat(1_048_577)))
            assertEquals(403, request("other/book-review-source", body))
            assertEquals(409, request("C/book-review-source", body), "Paired client cannot approve a source")
            f.approve()
            val r = f.bridge().register(f.review(), f.file(f.identity, f.coverage()))
            val target = assertNotNull(prepareBookReviewPractice(f.sync, r, 21000))
            assertEquals("BOOK_TARGET_MISMATCH", f.http.takeReviewVariant(target.copy(attemptId = "made-up")).error)
            assertEquals(before, f.db { events("C") })
        }
    }

    @Test fun defaultLibraryCannotReviveUnverifiedLegacyReceiptAfterRestart() {
        val f = SourceReceiptFixture()
        f.library.importReviewVariantSource(f.review(), f.file())
        assertTrue(f.review().canStartVariant, "Independent synthetic authority permits the synthetic adapter")
        val ordinary = TextbookLibrary(f.library.directory)
        val review = ordinary.review(f.data.book.id, f.data.lesson.id, QuestionMode.CHOICE)
        assertFalse(review.canStartVariant)
        assertNull(ordinary.startReviewVariant(review).variant)
        assertFailsWith<IllegalArgumentException> { ordinary.importReviewVariantSource(review, f.file()) }
        assertEquals(0, f.data.rows())
    }
}
