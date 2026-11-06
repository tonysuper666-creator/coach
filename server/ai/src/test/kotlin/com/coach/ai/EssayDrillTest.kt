package com.coach.ai

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.store.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

/** Timed essay drill end to end: pick a prompt, write against the clock, get four quoted criteria and a rewrite. */
class EssayDrillTest {
    // Synthetic fixture: a toy course, nothing from any real exam.
    private val reference = "A toy insurer that cannot tell careful drivers from careless drivers must charge one pooled premium " +
        "to everyone in the market. Careful drivers find that premium too high and leave, so the pool becomes riskier."
    private val s1 = "The market unravels because the insurer cannot see who is risky."
    private val s2 = "Low risk buyers leave when one price is charged to everyone, so the average cost of those who stay goes up."
    private val s3 = "In a toy study of 100 buyers, the premium rose from 50 to 80 after the safest third left."
    private val s4 = "For example, a gym that charges one fee loses its lightest users first."
    private val essay = listOf(s1, s2, s3, s4, "Therefore a mandate or risk adjustment can stop the spiral.").joinToString(" ")
    private val ownRewrite = "Because the insurer cannot observe risk, it charges everyone one price; the safest buyers then exit, " +
        "average cost climbs and the price rises again. A mandate keeps low risks in the pool, and risk adjustment pays for sicker members."
    private val copiedRewrite = "Every insurer must charge one pooled premium to everyone in the market, which drives careful drivers away."

    private fun bank(dir: Path): Path {
        val root = Files.createDirectories(dir.resolve("generated"))
        val item = { id: String, week: Int, kp: String, stem: String -> json("id" to id, "type" to "essay", "kp_week" to week, "kp" to kp,
            "marks" to 20, "word_target" to 300, "stem" to stem, "model_answer" to reference, "source" to "synthetic",
            "rubric" to JsonArray(listOf(json("criterion" to "mechanism", "marks" to 6, "look_for" to "hidden risk, one pooled price")))) }
        Files.writeString(root.resolve("toy-pack.json"), json("id" to "toy-pack", "kind" to "pack", "course" to "TOY101", "title" to "Toy essays",
            "sections" to JsonArray(listOf(json("title" to "Essays", "items" to JsonArray(listOf(
                item("e1", 1, "insurance selection", "Explain why a toy insurance market without risk information can unravel."),
                item("e9", 9, "later topic", "A week-nine question the outline does not reach yet."))))))).toString())
        Files.writeString(root.resolve("broken.json"), "{not json")
        return root
    }

    private fun LedgerStore.seed(dir: Path) {
        essays = EssayBank(bank(dir))
        append("c", "outline_revised", json("course" to json("name" to "TOY101 Toy economics"), "ops" to JsonArray(listOf(
            json("kpId" to "w1", "title" to "【W1·Ch1】Prices and quantities"),
            json("kpId" to "w1b", "title" to "【W1·Ch2】Insurance and selection"),
            json("kpId" to "w2", "title" to "【W2·Ch3】Moral hazard")))))
    }

    private fun LedgerStore.send(type: String, payload: JsonObject): Receipt {
        val bytes = LedgerJson.encodeToString(CommandBody(type, "c", 1793188800000, payload))
        return command(WireCommand("phone", UUID.randomUUID().toString(), bytes, sha256(bytes.toByteArray())))
    }

    private fun LedgerStore.pose(attempt: String, essayId: String = "toy-pack/e1", stem: String = "", limit: Int = Essay.TIME_LIMIT_SEC): Receipt =
        send("pose_essay", json("attemptId" to attempt, "essayId" to essayId, "stem" to stem, "week" to 1, "timeLimitSec" to limit, "threadId" to "essay:$attempt"))

    private fun LedgerStore.handIn(attempt: String, posed: JsonObject, text: String = essay): Receipt =
        send("submit_answer", json("attemptId" to attempt, "answer" to text, "posedSnapshot" to posed, "threadId" to "essay:$attempt"))

    private fun grade(rewrite: String = ownRewrite, structureQuote: String = s1) = json("items" to JsonArray(listOf(
        json("id" to "structure", "points" to 3.5, "reason" to "开头直接回答了问题。下一篇在结尾补一句总结。", "evidence" to structureQuote),
        json("id" to "argument", "points" to 5, "reason" to "机制讲对了。下一篇说清楚为什么高风险的人留下。", "evidence" to s2),
        json("id" to "evidence", "points" to 5, "reason" to "有数字。下一篇写出研究作者和年份。", "evidence" to s3),
        json("id" to "example", "points" to 3.5, "reason" to "例子贴切。下一篇再解释它和保险的对应关系。", "evidence" to s4))),
        "total" to 20, "awarded" to 17, "rewrite" to rewrite).toString()

    private fun run(test: suspend LedgerActor.(StubBridge, ConstructedGradeWorker) -> Unit) = runBlocking<Unit> {
        val dir = Files.createTempDirectory("essay-drill")
        val actor = LedgerActor(this, LedgerStore(dir).apply { seed(dir) })
        try { StubBridge().use { bridge -> actor.test(bridge, ConstructedGradeWorker(actor, BridgeClient(stubConfig(bridge)))) } }
        finally { actor.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun bankEssayGetsFourQuotedCriteriaAndARewriteAndCountsAsTimedEvidence() = run { bridge, worker ->
        val listed = execute { essayPrompts("c") }
        val prompts = listed.getValue("prompts").jsonArray.map { it.jsonObject }
        assertEquals(listOf("toy-pack/e1"), prompts.map { it.str("id") }, "a prompt whose week has no knowledge point stays hidden")
        assertEquals(1L, listed.num("unmatched"))
        assertFalse(reference in listed.toString(), "the model answer never leaves the Mac")

        val posed = execute { pose("a1") }
        assertEquals("OK", posed.status, posed.error)
        val frozen = posed.result.getValue("posedSnapshot").jsonObject
        assertEquals("TIMED", frozen.str("purpose")); assertEquals("ESSAY", frozen.str("format"))
        assertEquals("VERIFIED", frozen.str("keyState")); assertEquals(listOf("w1b"), frozen.strings("kpIds"))
        assertFalse(reference in frozen.toString())
        assertEquals("w1", execute { snapshot("c").projection.position.continueTarget }, "a drill does not take over the Today page")
        assertEquals(0, execute { constructedAvailable("c", "w1b") }, "an essay is never handed out as an ordinary constructed question")
        assertNull(execute { constructedSupply("c")["w1b"] })

        assertEquals("OK", execute { handIn("a1", frozen) }.status)
        bridge.enqueue(200, StubBridge.completion(grade()))
        assertTrue(worker.runOne())
        val draft = execute { events("c").single { it.type == "attempt_graded" } }.payload
        assertEquals(ownRewrite, draft.str("rewrite"))
        assertTrue(bridge.requests.last().contains("structure") && bridge.requests.last().contains(essay))

        bridge.enqueue(200, StubBridge.completion("""{"verdict":"CONFIRM","note":"Agreed"}"""))
        assertTrue(worker.runOne())
        val reviewed = execute { events("c").single { it.type == "grade_reviewed" } }.payload
        val revised = reviewed.getValue("revised").jsonObject
        assertEquals(1700L, revised.num("score")); assertEquals(2000L, revised.num("max"))
        assertEquals(ownRewrite, revised.str("rewrite"))
        val quotes = revised.getValue("items").jsonArray.map { it.jsonObject.str("evidence") }
        assertEquals(listOf(s1, s2, s3, s4), quotes, "every criterion quotes the learner's own sentence")

        val mastery = execute { snapshot("c").projection.mastery.getValue("w1b") }
        assertEquals(listOf("a1"), mastery.window)
        assertEquals(1, mastery.solidProgress.constructed)
        val record = execute { constructedRecord("c", "a1")!! }.toString()
        assertFalse(reference in record || "markingNotes" in record, "the record shows the learner's rewrite, never the reference")
    }

    @Test fun pastedPromptIsGradedButNeverCountsTowardMastery() = run { bridge, worker ->
        val posed = execute { pose("p1", essayId = "", stem = "Discuss whether a toy health insurance mandate fixes adverse selection.") }
        assertEquals("OK", posed.status, posed.error)
        val frozen = posed.result.getValue("posedSnapshot").jsonObject
        assertEquals("UNREVIEWED", frozen.str("keyState"))
        assertEquals("OK", execute { handIn("p1", frozen) }.status)
        bridge.enqueue(200, StubBridge.completion(grade()))
        bridge.enqueue(200, StubBridge.completion("""{"verdict":"CONFIRM","note":"Agreed"}"""))
        worker.runOne(); worker.runOne()
        assertEquals(1, execute { events("c").count { it.type == "grade_reviewed" } })
        assertTrue(execute { snapshot("c").projection.mastery["w1"]?.window.orEmpty().isEmpty() })
        assertTrue(bridge.requests.first().contains("the student pasted this question"))
    }

    @Test fun lateHandInIsGradedButIsNotTimedEvidence() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("essay-late")
        var now = 1793188800000
        val actor = LedgerActor(this, LedgerStore(dir) { now }.apply { seed(dir) })
        try { StubBridge().use { bridge ->
            val worker = ConstructedGradeWorker(actor, BridgeClient(stubConfig(bridge)))
            val posed = actor.execute { pose("late", limit = 120) }.result.getValue("posedSnapshot").jsonObject
            now += (120 + Essay.GRACE_SEC + 1) * 1000L
            assertEquals("OK", actor.execute { handIn("late", posed) }.status)
            assertTrue(actor.execute { events("c").single { it.type == "answer_submitted" } }.payload.flag("overtime"))
            bridge.enqueue(200, StubBridge.completion(grade()))
            bridge.enqueue(200, StubBridge.completion("""{"verdict":"CONFIRM","note":"Agreed"}"""))
            worker.runOne(); worker.runOne()
            assertEquals(1, actor.execute { events("c").count { it.type == "grade_reviewed" } }, "a late essay is still marked")
            assertEquals(0, actor.execute { snapshot("c").projection.mastery["w1b"]?.solidProgress?.count ?: 0 })

            // Handing in inside the grace window still counts as on time.
            now += 86_400_000L
            val second = actor.execute { pose("ontime", essayId = "", stem = "Explain one way a toy insurer can price risk it cannot observe.", limit = 120) }
            now += (120 + Essay.GRACE_SEC - 5) * 1000L
            assertEquals("OK", actor.execute { handIn("ontime", second.result.getValue("posedSnapshot").jsonObject) }.status)
            assertFalse(actor.execute { events("c").last { it.type == "answer_submitted" } }.payload.flag("overtime"))
        } } finally { actor.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun missingQuoteIsRepairedAndARewriteCopyingTheReferenceIsWithheld() = run { bridge, worker ->
        val frozen = execute { pose("c1") }.result.getValue("posedSnapshot").jsonObject
        execute { handIn("c1", frozen) }
        bridge.enqueue(200, StubBridge.completion(grade(rewrite = copiedRewrite, structureQuote = "")))
        bridge.enqueue(200, StubBridge.completion(grade(rewrite = copiedRewrite)))
        assertTrue(worker.runOne())
        assertTrue(bridge.requests[1].contains("essay_quote_missing:structure") && bridge.requests[1].contains("rewrite_copies_reference"))
        val draft = execute { events("c").single { it.type == "attempt_graded" } }.payload
        assertEquals("", draft.str("rewrite"))
        assertTrue(draft.str("rewriteNotice").isNotBlank(), "the learner is told why there is no rewrite")
        assertEquals(1700L, draft.num("score"), "the marks still stand")
        assertNull(Essay.sharedRun(draft.toString(), reference))
    }

    @Test fun invalidEssayRequestsAreRejectedWithoutPosingAnything() = run { _, _ ->
        assertEquals("INVALID_ESSAY", execute { pose("") }.error)
        assertEquals("ESSAY_UNAVAILABLE", execute { pose("x1", essayId = "toy-pack/missing") }.error)
        assertEquals("ESSAY_UNAVAILABLE", execute { pose("x2", essayId = "toy-pack/e9") }.error, "its week is not in the outline")
        assertEquals("ESSAY_UNAVAILABLE", execute { pose("x3", essayId = "", stem = "Too short") }.error)
        assertEquals("INVALID_ESSAY", execute { pose("x4", limit = 10) }.error)
        assertEquals("OK", execute { pose("x5") }.status)
        assertEquals("INVALID_ESSAY", execute { pose("x5") }.error, "an attempt id is posed once")
        assertEquals(1, execute { events("c").count { it.type == "question_posed" } })
    }
}
