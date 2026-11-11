package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Invented content only: the real weekly packs live in the private generated directory. */
private fun item(id: String, tier: String, keyState: String, facts: List<String>, answer: Int = 1) = json(
    "id" to id, "type" to "mcq", "kp_week" to 1, "kp" to "kp-orchard", "key_state" to keyState, "fact_ids" to facts, "tier" to tier, "marks" to 1,
    "stem" to "Synthetic orchard question $id: which statement holds?",
    "options" to JsonArray(listOf("Apples $id", "Pears $id", "Plums $id", "Figs $id").map(::JsonPrimitive)), "answer" to answer,
    "explanation" to "正确：Pears grow in the synthetic orchard $id.\n错项0：Apples were sold out.\n错项2：Plums ripen later.\n错项3：Figs are imported.\n背景：A synthetic orchard story.\n出处：Synthetic lecture 1",
    "source" to "Synthetic lecture 1")

private fun pack() = json("id" to "mainline-W01", "kind" to "pack", "course" to "C1", "minutes" to 10, "sections" to JsonArray(listOf(json(
    "title" to "Week 1 orchard", "instructions" to "One at a time.", "choose" to null,
    "items" to JsonArray(listOf(item("D1", "detail", "VERIFIED", listOf("F2")), item("B1", "bridge", "VERIFIED", listOf("F3")),
        item("S1", "setup", "VERIFIED", listOf("F1")), item("D2", "detail", "UNREVIEWED", listOf("F2"))))))))

private val FACTS = """
Synthetic facts.

```jsonl
{"fact_id":"F1","fact":"Synthetic orchards have pears.","type":"definition","source":"Synthetic lecture 1","kp":"kp-orchard"}
{"fact_id":"F2","fact":"Synthetic plums ripen late.","type":"detail","source":"Synthetic lecture 1","kp":"kp-orchard"}
{"fact_id":"F3","fact":"Synthetic figs are imported.","type":"detail","source":"Synthetic lecture 1","kp":"kp-orchard"}
```
""".trimIndent()

class MainlineTest {
    @Test fun readerRejectsForgedQuotesOffsetsAndObsoleteVersions() {
        val dir = Files.createTempDirectory("mainline-selection")
        try {
            val library = library(dir.resolve("papers"))
            val text = "Synthetic pears and plums."
            Files.writeString(dir.resolve("papers/mcq-mainline/W01-notes.json"), json(
                "schema_version" to 1, "week" to 1, "sections" to JsonArray(listOf(json("section_id" to "S1", "title" to "Synthetic",
                    "body" to text, "fact_ids" to listOf("F1", "F2", "F3"), "sources" to listOf("Synthetic lecture 1"))))).toString())
            LedgerStore(dir.resolve("db")).use { store ->
                val mainline = Mainline(library)
                val valid = json("sectionId" to "S1", "version" to notesVersion(text), "start" to 0, "end" to 9, "quote" to "Synthetic")
                assertEquals("Synthetic", mainline.selection(store, "C1", 1, valid))
                for ((field, value) in listOf("quote" to JsonPrimitive("Forged"), "start" to JsonPrimitive(-1),
                    "end" to JsonPrimitive(9999), "version" to JsonPrimitive("old"), "sectionId" to JsonPrimitive("UNKNOWN")))
                    assertFailsWith<MockError> { mainline.selection(store, "C1", 1, JsonObject(valid + (field to value))) }
            }
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun optionalClassEmphasisDeduplicatesLongRecordingsAndWithholdsPendingMetadata() {
        val dir = Files.createTempDirectory("mainline-emphasis")
        try {
            val library = library(dir.resolve("papers"))
            val file = dir.resolve("papers/mcq-mainline/W01-emphasis.json")
            val mark = json("fact_id" to "F1", "recording_kind" to "lecture", "recording_id" to "L1",
                "timestamp" to "121:03", "summary" to "Synthetic pears were emphasized.", "reviewed" to true,
                "answer" to 3)
            fun write(marks: List<JsonObject>, week: Int = 1) = Files.writeString(file,
                json("schema_version" to 1, "week" to week, "marks" to JsonArray(marks)).toString())
            LedgerStore(dir.resolve("db")).use { store ->
                val mainline = Mainline(library)
                fun body() = mainline.week(store, "C1", 1)
                assertEquals(0, body()["emphasis"]!!.jsonArray.size)
                write(listOf(mark, mark, JsonObject(mark + ("recording_kind" to JsonPrimitive("tutorial")))))
                val published = body()["emphasis"]!!.jsonArray
                assertEquals(2, published.size)
                assertEquals("02:01:03", published.first().jsonObject.str("timestamp"))
                assertFalse("answer" in published.first().jsonObject)
                for (bad in listOf(JsonObject(mark + ("reviewed" to JsonPrimitive(false))),
                    JsonObject(mark + ("fact_id" to JsonPrimitive("UNKNOWN"))),
                    JsonObject(mark + ("timestamp" to JsonPrimitive("01:99"))),
                    JsonObject(mark + ("timestamp" to JsonPrimitive(""))))) {
                    write(listOf(bad))
                    assertEquals(0, body()["emphasis"]!!.jsonArray.size)
                    assertEquals(4, body()["items"]!!.jsonArray.size)
                    assertEquals(0L, body()["coverage"]!!.jsonObject.num("factsMastered"))
                }
                write(listOf(mark), 2)
                assertEquals(0, body()["emphasis"]!!.jsonArray.size)
                write(listOf(mark, JsonObject(mark + ("summary" to JsonPrimitive("Conflicting")))))
                assertEquals(0, body()["emphasis"]!!.jsonArray.size)
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun notesCoverFactsFilterQuestionsAndNeverMasterGuessesOrUnverifiedKeys() {
        val dir = Files.createTempDirectory("mainline-notes")
        try {
            val library = library(dir.resolve("papers"))
            val notesFile = dir.resolve("papers/mcq-mainline/W01-notes.json")
            fun section(id: String, facts: List<String>) = json("section_id" to id, "title" to "Synthetic $id", "body" to "Pears grow here.",
                "fact_ids" to facts, "sources" to listOf("Synthetic lecture 1"))
            val sections = listOf(section("orchard", listOf("F1")), section("late-plums", listOf("F2")), section("imports", listOf("F3")))
            fun write(ss: List<JsonObject>) = Files.writeString(notesFile, json("schema_version" to 1, "week" to 1, "sections" to JsonArray(ss)).toString())
            LedgerStore(dir.resolve("db")).use { store ->
                val mainline = Mainline(library)
                assertTrue(mainline.week(store, "C1", 1).str("notesError").isNotBlank())
                write(sections)
                fun notes() = mainline.week(store, "C1", 1)["notes"]!!.jsonArray.map { it.jsonObject }
                assertEquals(3, notes().size)
                assertEquals(listOf("F1"), mainline.week(store,"C1",1)["items"]!!.jsonArray.first().jsonObject.strings("factIds"))
                fun answer(id: String, guessed: Boolean, submission: String) = mainline.answer(store,"C1",1,
                    json("itemId" to id,"choice" to 1,"guessed" to guessed,"submissionId" to submission))
                answer("S1", true, "guess")
                assertFalse(notes()[0].flag("mastered"))
                answer("S1", false, "known")
                assertTrue(notes()[0].flag("mastered"))
                answer("D1", false, "detail")
                answer("D2", false, "unverified")
                assertFalse(notes()[1].flag("mastered"), "Every question linked to each fact must be verified and mastered")
                write(sections.dropLast(1))
                assertTrue(notes().isEmpty())
                assertEquals(4, mainline.week(store,"C1",1)["items"]!!.jsonArray.size, "Bad notes leave all legacy questions available")
                write(listOf(section("unknown", listOf("NOT-A-FACT"))))
                assertTrue(notes().isEmpty())
                write(sections.map { JsonObject(it + ("sources" to JsonArray(listOf(JsonPrimitive("Wrong source"))))) })
                assertTrue(notes().isEmpty())
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun submissionCannotBeReusedForAnotherItemAcrossHttpAndRestart() = runBlocking<Unit> {
        for (shuffled in listOf(false, true)) {
            val dir = Files.createTempDirectory("mainline-retry-binding")
            val now = AtomicLong(1_800_000_000_000)
            val db = Files.createDirectories(dir.resolve("db"))
            var expected: JsonObject? = null
            try {
                repeat(2) { restart ->
                    val store = LedgerStore(db) { now.get() }
                    if (restart == 0) store.enroll("phone", "synthetic-private-token-32-characters")
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    val actor = LedgerActor(scope, store)
                    val pushes = MutableSharedFlow<String>(extraBufferCapacity = 64)
                    val mainline = Mainline(library(dir.resolve("papers")), clock = { now.get() })
                    val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) { mainlineRoutes(actor, pushes, mainline) }
                    engine.start(false)
                    try {
                        val port = engine.engine.resolvedConnectors().single().port
                        val http = HttpClient.newHttpClient()
                        suspend fun send(item: String, choice: Int, guessed: Boolean): Pair<Int, JsonObject> = withContext(Dispatchers.IO) {
                            val body = json("itemId" to item, "choice" to choice, "guessed" to guessed,
                                "submissionId" to "one-submission", "layout" to if (shuffled) 3 else null)
                            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/courses/C1/mainline/1/answer"))
                                .header("X-Coach-Device", "phone").header("Authorization", "Bearer synthetic-private-token-32-characters")
                                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
                            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                            response.statusCode() to LedgerJson.parseToJsonElement(response.body()).jsonObject
                        }
                        if (restart == 0) {
                            val first = send("S1", 0, true)
                            assertEquals(200, first.first)
                            expected = first.second
                        }
                        val before = store.events("C1")
                        val retry = send("S1", 2, false)
                        assertEquals(200, retry.first)
                        assertEquals(expected, retry.second, "same item replays original choice and guessed after restart=$restart")
                        val mismatch = send("D1", 2, false)
                        assertEquals(409, mismatch.first, "item mismatch must conflict, shuffled=$shuffled restart=$restart")
                        assertEquals(before, store.events("C1"), "both retries append zero ledger events")
                    } finally { engine.stopSuspend(0, 500); actor.close(); scope.cancel() }
                }
            } finally { dir.toFile().deleteRecursively() }
        }
    }

    private fun library(dir: Path): MockLibrary {
        val root = Files.createDirectories(dir.resolve("mcq-mainline"))
        Files.writeString(root.resolve("W01.json"), pack().toString())
        Files.writeString(root.resolve("W01-facts.md"), FACTS)
        Files.writeString(root.resolve("W02.json"), "{ not json")
        return MockLibrary(dir)
    }

    @Test fun weekWalkExplainsAtOnceQueuesMissesAndGuessesAndCountsOnlyUnguessedMastery() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("mainline")
        val now = AtomicLong(1_800_000_000_000)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(Files.createDirectories(dir.resolve("db"))) { now.get() }.also { it.enroll("phone", "synthetic-private-token-32-characters") }
        store.append("C1", "outline_revised", json("knowledgePoints" to JsonArray(listOf(json("id" to "kp-orchard", "title" to "Orchard", "week" to 1)))))
        val actor = LedgerActor(scope, store)
        val mainline = Mainline(library(dir.resolve("papers")), clock = { now.get() })
        val pushes = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, pushes); mainlineRoutes(actor, pushes, mainline) }
        engine.start(false)
        try {
            val port = engine.engine.resolvedConnectors().single().port
            val client = HttpClient.newHttpClient()
            suspend fun request(path: String, body: JsonObject? = null): Pair<Int, JsonObject> = withContext(Dispatchers.IO) {
                val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/courses/C1$path")).timeout(java.time.Duration.ofSeconds(5))
                    .header("X-Coach-Device", "phone").header("Authorization", "Bearer synthetic-private-token-32-characters")
                if (body != null) b.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
                r.statusCode() to (runCatching { LedgerJson.parseToJsonElement(r.body()).jsonObject }.getOrNull() ?: JsonObject(emptyMap()))
            }
            fun JsonObject.cov(key: String) = getValue("coverage").jsonObject.num(key)

            val (homeStatus, home) = request("/mainline")
            assertEquals(200, homeStatus)
            assertEquals(listOf(1L), home.getValue("weeks").jsonArray.map { it.jsonObject.num("week") })
            assertEquals("W02.json", home.getValue("invalid").jsonArray.single().jsonObject.str("file"), "a broken file is reported, not hidden")

            val (_, week) = request("/mainline/1")
            val items = week.getValue("items").jsonArray.map { it.jsonObject }
            assertEquals(listOf("setup", "detail", "detail", "bridge"), items.map { it.str("tier") }, "setup first, bridge last")
            assertTrue(items.none { "answer" in it || "explanation" in it }, "the key and explanation stay on the server until he answers")
            assertEquals(3, week.cov("facts")); assertEquals(3, week.cov("factsWithQuestions")); assertEquals(0, week.cov("factsMastered"))

            val (_, wrong) = request("/mainline/1/answer", json("itemId" to "S1", "choice" to 0, "submissionId" to "s1-wrong"))
            val parts = wrong.getValue("explanation").jsonObject
            assertTrue(parts.str("correct").isNotBlank() && parts.str("background").isNotBlank() && parts.str("source").isNotBlank())
            assertEquals(setOf("0", "2", "3"), parts.getValue("options").jsonObject.keys, "every distractor has its own reason")
            assertEquals(1, wrong.num("answer")); assertEquals("missed", wrong.str("status"))
            assertEquals("D1", wrong.str("next"))
            val today = logicalDay(now.get(), "Europe/London")
            assertEquals(today + 1, wrong.num("dueDay"), "a miss comes back tomorrow")
            assertEquals(1L, wrong.num("mistakeWeek"))

            val (_, retry) = request("/mainline/1/answer", json("itemId" to "S1", "choice" to 0, "submissionId" to "s1-wrong"))
            assertEquals(wrong.str("attemptId"), retry.str("attemptId"), "a resent tap is the same answer")
            assertEquals(1, store.events("C1").count { it.type == "answer_submitted" })

            val (_, guess) = request("/mainline/1/answer", json("itemId" to "D1", "choice" to 1, "guessed" to true, "submissionId" to "d1-guess"))
            assertEquals("guessed", guess.str("status"), "a right guess is not mastery")
            assertEquals(0, guess.cov("factsMastered"))
            assertEquals(1L, guess.num("mistakeWeek"), "a right guess still goes into the mistake book")
            assertEquals(today + 1, guess.num("dueDay"))

            val (_, pending) = request("/mainline/1/answer", json("itemId" to "D2", "choice" to 0, "submissionId" to "d2-pending"))
            assertEquals("pending", pending.str("status"), "an unchecked key never marks him wrong")
            assertTrue(pending["mistakeWeek"] is JsonNull)
            assertEquals(today + 1, pending.num("dueDay"), "but the point is still looked at again")

            val (_, bridge) = request("/mainline/1/answer", json("itemId" to "B1", "choice" to 1, "submissionId" to "b1-right"))
            assertEquals("mastered", bridge.str("status")); assertEquals("bridge", bridge.str("tier"))
            assertTrue(bridge["next"] is JsonNull)
            assertEquals(1, bridge.cov("factsMastered"), "F3 has one question, answered without guessing")
            assertEquals(1, bridge.cov("keysPending"))

            val queue = store.snapshot("C1").projection.reviewsOn(today + 1)
            assertEquals(REVISIT, queue["kp-orchard"]?.lastResult, "the missed and guessed point is in tomorrow's due queue")
            assertEquals(listOf("S1", "D1").map { id -> "Synthetic orchard question $id: which statement holds?" },
                weekMistakes(store.events("C1"), 1).map { it.payload.str("stem") }, "wrong and guessed answers, not the unchecked one")

            val (_, sure) = request("/mainline/1/answer", json("itemId" to "S1", "choice" to 1, "submissionId" to "s1-sure"))
            assertEquals("mastered", sure.str("status"))
            assertEquals(2, sure.cov("factsMastered"))

            // Capability negotiation must work through the same HTTP boundary as the shared client.
            assertTrue(items.all { it["layout"] is JsonNull })
            val (_, shuffled) = request("/mainline/1?layout=shuffled-v1")
            val shown = shuffled.getValue("items").jsonArray.map { it.jsonObject }
            assertTrue(shown.all { it["layout"] is JsonPrimitive })
            val q = assertNotNull(shown.firstOrNull { it.str("keyState") == "VERIFIED" && it.strings("options").indexOf("Pears ${it.str("id")}") != 1 })
            val choice = q.strings("options").indexOf("Pears ${q.str("id")}")
            val (_, shuffledRight) = request("/mainline/1/answer", json("itemId" to q.str("id"), "choice" to choice,
                "layout" to q.num("layout"), "submissionId" to "http-layout-right"))
            assertEquals("mastered", shuffledRight.str("status"))
            assertEquals(choice.toLong(), shuffledRight.num("answer"))

            val (missing, _) = request("/mainline/9")
            assertEquals(404, missing)
        } finally { engine.stopSuspend(0, 500); actor.close(); scope.cancel(); dir.toFile().deleteRecursively() }
    }

    /** D2's key is unchecked and its provisional answer is option 1: a "right" pick there proves nothing. */
    private fun orchard(dir: Path, now: AtomicLong): Pair<LedgerStore, Mainline> {
        val store = LedgerStore(Files.createDirectories(dir.resolve("db"))) { now.get() }
        store.append("C1", "outline_revised", json("knowledgePoints" to JsonArray(listOf(json("id" to "kp-orchard", "title" to "Orchard", "week" to 1)))))
        return store to Mainline(library(dir.resolve("papers")), clock = { now.get() })
    }

    @Test fun anUncheckedKeyStillBringsTheQuestionBackTomorrowWhenHisPickMatchesIt() {
        val dir = Files.createTempDirectory("mainline-unchecked-first")
        val now = AtomicLong(1_800_000_000_000)
        try {
            val (store, mainline) = orchard(dir, now)
            val result = mainline.answer(store, "C1", 1, json("itemId" to "D2", "choice" to 1, "submissionId" to "unchecked-first"))
            val tomorrow = logicalDay(now.get(), "Europe/London") + 1
            assertEquals("pending", result.str("status"))
            assertEquals(tomorrow, (result["dueDay"] as? JsonPrimitive)?.longOrNull, "an unchecked key is never a pass, so he sees it again tomorrow")
            assertEquals(REVISIT, store.snapshot("C1").projection.reviewsOn(tomorrow)["kp-orchard"]?.lastResult)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun anUncheckedKeyCannotSettleARevisitThatIsDue() {
        val dir = Files.createTempDirectory("mainline-unchecked-due")
        val now = AtomicLong(1_800_000_000_000)
        try {
            val (store, mainline) = orchard(dir, now)
            mainline.answer(store, "C1", 1, json("itemId" to "S1", "choice" to 0, "submissionId" to "missed-first"))
            val due = logicalDay(now.get(), "Europe/London") + 1
            assertEquals(due, store.snapshot("C1").projection.revisit["kp-orchard"])
            now.addAndGet(86_400_000)
            val result = mainline.answer(store, "C1", 1, json("itemId" to "D2", "choice" to 1, "submissionId" to "unchecked-on-due-day"))
            assertEquals("pending", result.str("status"))
            assertEquals(due + 1, store.snapshot("C1").projection.revisit["kp-orchard"], "the due look-again moves on a day instead of clearing")
            assertEquals(due + 1, (result["dueDay"] as? JsonPrimitive)?.longOrNull)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun qaUncheckedMatchingKeyOnAPassedPointStillQueuesTomorrow() {
        val dir = Files.createTempDirectory("mainline-unchecked-passed")
        val now = AtomicLong(1_800_000_000_000)
        try {
            val (store, mainline) = orchard(dir, now)
            val today = logicalDay(now.get(), "Europe/London")
            store.append("C1", "legacy_passed", json("kpId" to "kp-orchard", "sourceRef" to "synthetic"))
            store.append("C1", "legacy_schedule", json("kpId" to "kp-orchard", "dueDay" to today + 14, "interval" to 14))
            assertTrue(store.snapshot("C1").projection.mastery.getValue("kp-orchard").passed)
            assertEquals(today + 14, store.snapshot("C1").projection.review.getValue("kp-orchard").dueDay)
            val result = mainline.answer(store, "C1", 1, json("itemId" to "D2", "choice" to 1, "submissionId" to "unchecked-passed"))
            val projection = store.snapshot("C1").projection
            assertEquals("pending", result.str("status"))
            assertFalse(projection.mastery.getValue("kp-orchard").needsRepair, "an unchecked key must not mark a known point wrong")
            println("QA_PENDING_PASSED status=${result.str("status")} dueDay=${result["dueDay"]} expected=${today + 1} revisit=${projection.revisit} review=${projection.review}")
            assertEquals(today + 1, (result["dueDay"] as? JsonPrimitive)?.longOrNull,
                "an unchecked mainline key asks for another look tomorrow even when the point was already passed")
            assertContains(projection.reviewsOn(today + 1), "kp-orchard")
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun uncheckedShuffledChoicesQueueBothPassedAndUnpassedPointsWithoutJudgingThem() {
        for (key in listOf("UNREVIEWED", "REJECTED")) for (passed in listOf(false, true))
            for (choice in 0..3) for (guessed in listOf(false, true)) {
                val dir = Files.createTempDirectory("mainline-pending-layout")
                val now = AtomicLong(1_800_000_000_000)
                try {
                    val (store, mainline) = orchard(dir, now)
                    val file = dir.resolve("papers/mcq-mainline/W01.json")
                    Files.writeString(file, Files.readString(file).replace("UNREVIEWED", key))
                    val today = logicalDay(now.get(), "Europe/London")
                    if (passed) {
                        store.append("C1", "legacy_passed", json("kpId" to "kp-orchard", "sourceRef" to "synthetic"))
                        store.append("C1", "legacy_schedule", json("kpId" to "kp-orchard", "dueDay" to today + 14, "interval" to 14))
                    }
                    fun card(engine: Mainline) = engine.week(store, "C1", 1, shuffled = true).getValue("items").jsonArray.map { it.jsonObject }.single { it.str("id") == "D2" }
                    val shown = card(mainline)
                    val restarted = Mainline(library = MockLibrary(dir.resolve("papers")), clock = { now.get() })
                    assertEquals(shown, card(restarted), "the displayed choice survives a reload and a server restart")
                    val result = restarted.answer(store, "C1", 1, json("itemId" to "D2", "choice" to choice,
                        "layout" to shown.num("layout"), "guessed" to guessed, "submissionId" to "pending-layout"))
                    assertEquals("pending", result.str("status"), "$key, passed=$passed, choice=$choice, guessed=$guessed")
                    assertEquals(today + 1, (result["dueDay"] as? JsonPrimitive)?.longOrNull)
                    assertTrue(result["mistakeWeek"] is JsonNull)
                    assertEquals(0L, result.getValue("coverage").jsonObject.num("factsMastered"))
                    val projection = store.snapshot("C1").projection
                    assertEquals(passed, projection.mastery.getValue("kp-orchard").passed)
                    assertFalse(projection.mastery.getValue("kp-orchard").needsRepair)
                    assertContains(projection.reviewsOn(today + 1), "kp-orchard")
                    assertTrue(weekMistakes(store.events("C1"), 1).isEmpty())
                    assertTrue(store.events("C1").last { it.type == "attempt_graded" }.payload.str("feedback").startsWith("答案键待核对，这次不记对错。"))
                    val count = store.events("C1").size
                    assertEquals(result, restarted.answer(store, "C1", 1, json("itemId" to "D2", "choice" to (choice + 1) % 4,
                        "layout" to 99, "guessed" to !guessed, "submissionId" to "pending-layout")), "a retry replays the recorded choice and guess")
                    assertEquals(count, store.events("C1").size)
                } finally { dir.toFile().deleteRecursively() }
            }
    }

    @Test fun theKeyLandsAnywhereOnScreenEvenWhenThePackAlwaysPutsItFirst() {
        val shown = IntArray(4)
        repeat(400) { n -> shown[optionOrder("synthetic-fingerprint-$n", 0, 4).indexOf(0)]++ }
        assertTrue(shown.all { it in 60..140 }, "each position gets about a quarter of the keys: ${shown.toList()}")
        assertEquals(optionOrder("synthetic-fingerprint-1", 3, 4), optionOrder("synthetic-fingerprint-1", 3, 4), "one showing always lays out the same way")
        assertTrue((0 until 50).count { optionOrder("synthetic-fingerprint-$it", 0, 4) != optionOrder("synthetic-fingerprint-$it", 1, 4) } > 30,
            "the next look at a question is usually in a new order")
    }

    @Test fun aShuffledQuestionIsGradedAndExplainedByWhatWasOnScreen() {
        val dir = Files.createTempDirectory("mainline-shuffle")
        val now = AtomicLong(1_800_000_000_000)
        try {
            val (store, mainline) = orchard(dir, now)
            val items = mainline.week(store, "C1", 1, shuffled = true).getValue("items").jsonArray.map { it.jsonObject }
            for (i in items) assertEquals(listOf("Apples", "Pears", "Plums", "Figs").map { "$it ${i.str("id")}" }.toSet(), i.strings("options").toSet())
            // Pick a checked question whose key (Pears, second in the pack) is somewhere else on screen.
            val q = assertNotNull(items.firstOrNull { it.str("keyState") == "VERIFIED" && it.strings("options").indexOf("Pears ${it.str("id")}") != 1 })
            val id = q.str("id"); val options = q.strings("options")
            val pears = options.indexOf("Pears $id"); val plums = options.indexOf("Plums $id")
            val letters = optionLetters(4)

            val wrong = mainline.answer(store, "C1", 1, json("itemId" to id, "choice" to plums, "layout" to q.num("layout"), "submissionId" to "shuffled-wrong"))
            assertEquals("missed", wrong.str("status"), "he picked plums, whatever position plums has in the pack")
            assertEquals(plums.toLong(), wrong.num("choice")); assertEquals(pears.toLong(), wrong.num("answer"), "the tick goes on the pears row he sees")
            val reasons = wrong.getValue("explanation").jsonObject.getValue("options").jsonObject
            assertEquals(listOf("Apples", "Plums", "Figs").map { options.indexOf("$it $id").toString() }.toSet(), reasons.keys)
            assertEquals("Plums ripen later.", reasons.str("$plums"), "each reason sits under the option it explains")
            assertEquals("Apples were sold out.", reasons.str("${options.indexOf("Apples $id")}"))
            val events = store.events("C1")
            assertEquals(letters[plums], events.last { it.type == "answer_submitted" }.payload.str("answer"))
            val feedback = events.last { it.type == "attempt_graded" }.payload.str("feedback")
            assertTrue("正确答案 ${letters[pears]}：Pears $id" in feedback && "错项 ${letters[plums]}：Plums ripen later." in feedback, "the record uses the letters on screen")

            val resent = mainline.answer(store, "C1", 1, json("itemId" to id, "choice" to pears, "layout" to 99, "submissionId" to "shuffled-wrong"))
            assertEquals("missed", resent.str("status"), "a resend is the first answer, not a second chance")
            assertEquals(plums.toLong(), resent.num("choice"))

            val again = mainline.week(store, "C1", 1, shuffled = true).getValue("items").jsonArray.map { it.jsonObject }.first { it.str("id") == id }
            assertEquals(q.num("layout") + 1, again.num("layout"))
            val right = mainline.answer(store, "C1", 1, json("itemId" to id, "choice" to again.strings("options").indexOf("Pears $id"),
                "layout" to again.num("layout"), "submissionId" to "shuffled-right"))
            assertEquals("mastered", right.str("status"))
            assertEquals(again.strings("options").indexOf("Pears $id").toLong(), right.num("answer"))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun qaAClientWithoutLayoutIsGradedByTheOptionsItActuallyReceives() {
        val dir = Files.createTempDirectory("mainline-legacy-client")
        val now = AtomicLong(1_800_000_000_000)
        try {
            val (store, mainline) = orchard(dir, now)
            // The existing unversioned GET used by the pre-layout CourseHttp client.
            val items = mainline.week(store, "C1", 1).getValue("items").jsonArray.map { it.jsonObject }
            // Legacy GET retains pack order; it cannot describe a shuffle in its POST.
            for (item in items) {
                assertEquals(listOf("Apples", "Pears", "Plums", "Figs").map { "$it ${item.str("id")}" }, item.strings("options"))
                assertTrue(item["layout"] is JsonNull)
            }
            val q = assertNotNull(items.firstOrNull { it.str("keyState") == "VERIFIED" })
            val id = q.str("id")
            val choice = q.strings("options").indexOf("Pears $id")
            println("QA_LEGACY_LAYOUT item=$id options=${q.strings("options")} visibleCorrectIndex=$choice layout=${q.num("layout")}")
            // An old client displays these options and sends the displayed index, without layout.
            val result = mainline.answer(store, "C1", 1, json("itemId" to id, "choice" to choice, "submissionId" to "qa-legacy-client"))
            println("QA_LEGACY_LAYOUT status=${result.str("status")} choice=${result.num("choice")} answer=${result.num("answer")} dueDay=${result.num("dueDay")}")
            assertEquals("mastered", result.str("status"), "the visible correct option must remain correct when a pre-layout client submits it")
        } finally { dir.toFile().deleteRecursively() }
    }


    @Test fun packsLabelledWithTheCourseCodeShowUpForTheLedgerCourse() {
        val dir = Files.createTempDirectory("mainline-code")
        try {
            val store = LedgerStore(Files.createDirectories(dir.resolve("db"))) { 1_800_000_000_000 }
            store.append("course_synthetic", "outline_revised", json("course" to json("id" to "course_synthetic", "name" to "C1 Synthetic orchard"),
                "knowledgePoints" to JsonArray(listOf(json("id" to "kp-orchard", "title" to "Orchard", "week" to 1)))))
            val mainline = Mainline(library(dir.resolve("papers")))
            assertEquals(listOf(1L), mainline.home(store, "course_synthetic").getValue("weeks").jsonArray.map { it.jsonObject.num("week") },
                "the pack says C1, the ledger course is named \"C1 …\"")
            assertEquals(4, mainline.week(store, "course_synthetic", 1).getValue("items").jsonArray.size)
            assertTrue(mainline.home(store, "course_other").getValue("weeks").jsonArray.isEmpty(), "another course does not pick up the pack")
        } finally { dir.toFile().deleteRecursively() }
    }
}
