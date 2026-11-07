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
