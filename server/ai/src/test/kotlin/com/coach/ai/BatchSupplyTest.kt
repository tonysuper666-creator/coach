package com.coach.ai

import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class BatchSupplyTest {
    private val source = "Synthetic source says the uniquely correct result is the selected text."
    private fun generated() = JsonArray((0..2).map { i -> json(
        "stem" to "Synthetic question $i asks for the uniquely correct result.",
        "correct_text" to "Right result $i", "distractors" to listOf("Wrong one $i", "Wrong two $i", "Wrong three $i"),
        "explanation" to "The source supports this result.", "source_quote" to source) }).toString()
    private fun reviewed() = JsonArray((0..2).map { i -> json(
        "id" to i, "answer_text" to "Right result $i", "defects" to emptyList<String>(), "note" to "Supported") }).toString()
    private val pending = """{"x_coach_batch":"pending","retry_after_ms":60000}"""
    private fun StubBridge.key(index: Int) = requestedHeaders[index].entries
        .singleOrNull { it.key.equals("X-Coach-Batch-Key", ignoreCase = true) }?.value?.single()

    @Test fun pendingClaimsReleaseAcrossRestartAndOnlyCrossVendorReviewedQuestionsEnterPool() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("batch-supply")
        var now = 1794787200000L
        var actor = LedgerActor(this, LedgerStore(dir) { now })
        try { StubBridge().use { bridge ->
            var worker = PoolWorker(actor, BridgeClient(stubConfig(bridge)))
            val id = worker.ensure("c", "kp", "Objective", source, 3, background = true)!!
            bridge.enqueue(202, pending)
            assertEquals("pending", worker.runOne()!!.state)
            assertEquals("queued", actor.execute { jobState(id)!!.first })
            assertEquals(0L, actor.execute { supplyCallCount() })
            assertNull(worker.runOne(), "pending batches must not be polled in a tight loop")
            assertEquals(1, bridge.requests.size)
            actor.close()
            actor = LedgerActor(this, LedgerStore(dir) { now })
            worker = PoolWorker(actor, BridgeClient(stubConfig(bridge)))
            assertNull(worker.runOne(), "poll deadline must survive reopening the database")
            now += 60_001
            bridge.enqueue(200, StubBridge.completion(generated(), "claude-haiku-5-5"))
            assertEquals("completed", worker.runOne()!!.state)
            assertEquals(bridge.key(0), bridge.key(1), "new fences retain the durable batch identity")
            assertNotNull(bridge.key(0))
            assertEquals(0, actor.execute { poolAvailable("c", "kp") })
            bridge.enqueue(202, pending)
            assertEquals("pending", worker.runOne()!!.state)
            assertNotEquals(bridge.key(0), bridge.key(2), "review is an independently submitted task")
            now += 60_001
            bridge.enqueue(200, StubBridge.completion(reviewed(), "gpt-6.1-terra"))
            assertEquals(3, worker.runOne()!!.inserted)
            assertEquals(bridge.key(2), bridge.key(3))
            assertEquals(3, actor.execute { poolAvailable("c", "kp") })
            assertEquals(2L, actor.execute { supplyCallCount() })
        } } finally { actor.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun foregroundSupplyDoesNotWaitForTheBackgroundBatch() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("batch-foreground")
        val actor = LedgerActor(this, LedgerStore(dir))
        try { StubBridge().use { bridge ->
            val worker = PoolWorker(actor, BridgeClient(stubConfig(bridge)))
            val background = worker.ensure("c", "kp", "Objective", source, 3, background = true)
            bridge.enqueue(202, pending)
            assertEquals("pending", worker.runOne()!!.state)
            val foreground = worker.ensure("c", "kp", "Objective", source, 3)
            assertNotEquals(background, foreground)
            bridge.enqueue(200, StubBridge.completion(generated()))
            bridge.enqueue(200, StubBridge.completion(reviewed()))
            assertEquals("completed", worker.runOne()!!.state)
            assertEquals(3, worker.runOne()!!.inserted)
            assertNull(bridge.key(1)); assertNull(bridge.key(2))
            assertEquals("queued", actor.execute { jobState(background!!)!!.first })
        } } finally { actor.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun formatRetryHasItsOwnBatchAndReplaysEarlierResultsBeforeResuming() {
        StubBridge().use { bridge ->
            val client = BridgeClient(stubConfig(bridge)).forBatchJob("epoch", "job")
            val req = ChatRequest("gen_mcq", BridgeClient.CLAUDE, "synthetic", "synthetic", false, background = true)
            bridge.enqueue(200, StubBridge.completion("invalid", "claude-haiku-5-5"))
            bridge.enqueue(202, pending)
            assertFailsWith<BatchPending> { client.chat(req) }
            assertNotEquals(bridge.key(0), bridge.key(1))
            bridge.enqueue(200, StubBridge.completion("invalid", "claude-haiku-5-5"))
            bridge.enqueue(200, StubBridge.completion(generated(), "claude-haiku-5-5"))
            val result = assertIs<BridgeResult.Success>(client.chat(req))
            assertEquals(bridge.key(0), bridge.key(2))
            assertEquals(bridge.key(1), bridge.key(3))
            assertEquals(2, result.call.physicalCalls)
        }
    }

    @Test fun sameVendorBatchReviewCannotAdmitQuestions() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("batch-vendor")
        val actor = LedgerActor(this, LedgerStore(dir))
        try { StubBridge().use { bridge ->
            val worker = PoolWorker(actor, BridgeClient(stubConfig(bridge)))
            worker.ensure("c", "kp", "Objective", source, 3, background = true)
            bridge.enqueue(200, StubBridge.completion(generated(), "claude-haiku-5-5"))
            assertEquals("completed", worker.runOne()!!.state)
            bridge.enqueue(200, StubBridge.completion(reviewed(), "claude-haiku-5-5"))
            assertEquals("retrying", worker.runOne()!!.state)
            assertEquals(0, actor.execute { poolAvailable("c", "kp") })
        } } finally { actor.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun sourceRepairResumesWithoutCountingTheCachedDraftTwice() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("batch-repair")
        var now = 1794787200000L
        val actor = LedgerActor(this, LedgerStore(dir) { now })
        try { StubBridge().use { bridge ->
            val worker = PoolWorker(actor, BridgeClient(stubConfig(bridge)))
            worker.ensure("c", "kp", "Objective", source, 3, background = true)
            val draft = generated().replace(source, "A quotation absent from the source.")
            bridge.enqueue(200, StubBridge.completion(draft, "claude-haiku-5-5"))
            bridge.enqueue(202, pending)
            assertEquals("pending", worker.runOne()!!.state)
            assertEquals(1L, actor.execute { supplyCallCount() })
            now += 60_001
            bridge.enqueue(200, StubBridge.completion(draft, "claude-haiku-5-5"))
            val repairs = JsonArray((0..2).map { json("id" to it, "source_quote" to source) }).toString()
            bridge.enqueue(200, StubBridge.completion(repairs, "claude-haiku-5-5"))
            assertEquals("completed", worker.runOne()!!.state)
            assertEquals(bridge.key(0), bridge.key(2))
            assertEquals(bridge.key(1), bridge.key(3))
            assertNotEquals(bridge.key(0), bridge.key(1))
            bridge.enqueue(200, StubBridge.completion(reviewed(), "gpt-6.1-terra"))
            assertEquals(3, worker.runOne()!!.inserted)
            assertEquals(3L, actor.execute { supplyCallCount() })
        } } finally { actor.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun constructedDraftAndIndependentReviewsCanAllDefer() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("batch-constructed")
        var now = 1794787200000L
        val actor = LedgerActor(this, LedgerStore(dir) { now })
        try { StubBridge().use { bridge ->
            val worker = PoolWorker(actor, BridgeClient(stubConfig(bridge)))
            actor.execute { append("c", "legacy_passed", json("kpId" to "kp")) }
            assertNotNull(worker.ensureConstructed("c", "kp", "Objective", source))
            bridge.enqueue(202, pending)
            assertEquals("pending", worker.runOne()!!.state)
            now += 60_001
            val draft = JsonArray((0..1).map { i -> json("stem" to "Explain the source supported result for case $i.",
                "reference_answer" to "The selected text is the uniquely correct result.",
                "rubric" to JsonArray((1..3).map { r -> json("id" to "R$r", "text" to "Explains factor $r",
                    "points" to if (r == 3) 4 else 3, "basis" to source) })) }).toString()
            bridge.enqueue(200, StubBridge.completion(draft, "claude-haiku-5-5"))
            assertEquals("completed", worker.runOne()!!.state)
            assertEquals(bridge.key(0), bridge.key(1))
            bridge.enqueue(202, pending)
            assertEquals("pending", worker.runOne()!!.state)
            assertEquals(0, actor.execute { constructedAvailable("c", "kp") })
            val verdict = """{"verdict":"CONFIRM","note":"Supported"}"""
            bridge.enqueue(200, StubBridge.completion(verdict, "gpt-6.1-terra"))
            assertEquals(1, worker.runOne()!!.inserted)
            now += 60_001
            bridge.enqueue(200, StubBridge.completion(verdict, "gpt-6.1-terra"))
            assertEquals(1, worker.runOne()!!.inserted)
            assertEquals(bridge.key(2), bridge.key(4))
            assertNotEquals(bridge.key(2), bridge.key(3))
            assertEquals(2, actor.execute { constructedAvailable("c", "kp") })
            assertEquals(3L, actor.execute { supplyCallCount() })
        } } finally { actor.close(); dir.toFile().deleteRecursively() }
    }
}
