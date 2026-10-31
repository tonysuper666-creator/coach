package com.coach.server

import com.coach.ai.*
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.LedgerStore
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.net.URI
import java.net.http.*
import kotlin.test.*

class StudyCompanionFlowTest {
    private class Fixture : AutoCloseable {
        val dir = Files.createTempDirectory("coach-study-flow-")
        val stub = StubTutor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(dir)
        val actor = LedgerActor(scope,store)
        val chat = ChatService(actor,scope,BridgeClient(BridgeConfig(stub.baseUrl)),log={})
        val token = "synthetic-companion-token-32-characters"
        val engine = embeddedServer(Netty,host="127.0.0.1",port=0) {
            ledgerRoutes(actor,MutableSharedFlow(extraBufferCapacity=8),chat)
        }
        init { store.enroll("desktop",token); engine.start(wait=false) }
        suspend fun seed() = actor.execute {
            repeat(3) { i ->
                val options = listOf("correct","alternative","other","last").map { Option("$i-$it","$it $i") }
                registerQuestion("q$i","course","","SINGLE_CHOICE","Synthetic prompt $i","VERIFIED",options.first().optionId,options,listOf("kp"))
            }
        }
        suspend fun get(path: String): String = withContext(Dispatchers.IO) {
            val port = engine.engine.resolvedConnectors().first().port
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Authorization","Bearer $token").header("X-Coach-Device","desktop").build()
            val response = HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString())
            assertEquals(200,response.statusCode())
            response.body()
        }
        suspend fun reply(action: suspend () -> String): String = coroutineScope {
            val ended = async(start=CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { chat.hub.frames.first { it.str("t") == "msg.end" } } }
            val id = action(); ended.await(); id
        }
        override fun close() {
            runBlocking { engine.stopSuspend(0,500); actor.close() }
            scope.cancel(); stub.close(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun progressRepliesComeFromProjectionBeforeAnyModelCallEvenWhileAwaitingAnswer() = runBlocking {
        Fixture().use { f ->
            f.actor.execute {
                append("course","exam_weights_set",json("weights" to json("kp" to 30,"other" to 70)))
                append("course","kp_overridden",json("kpId" to "kp","kind" to "MARK_PASSED"))
            }
            f.seed()
            f.chat.openTutor("thread","course","kp",setOf("concept"),"Synthetic objective")
            f.chat.nextStep("thread","course",testUntaught=true)
            val factsBefore = f.actor.execute { events("course") }
            val snapshot = LedgerJson.parseToJsonElement(f.get("/v1/courses/course/today")).jsonObject
            var first: kotlinx.serialization.json.JsonObject? = null
            val listener = launch(start=CoroutineStart.UNDISPATCHED) { f.chat.hub.frames.first { first=it; true } }
            val id = f.chat.ask("thread","course","学了多少")
            listener.join()
            assertEquals("projection",first?.str("source"))
            val stored = assertNotNull(f.chat.snapshot(id))
            assertEquals(snapshot.str("coverageText"),stored.str("text"))
            assertEquals(0,f.stub.requests)
            assertEquals(factsBefore,f.actor.execute { events("course") })
            val review = f.chat.ask("thread","course","今天复习什么")
            assertTrue(assertNotNull(f.chat.snapshot(review)).str("text").contains("今天"))
        }
    }

    @Test fun quickConversationCannotAssistAnActiveAssessmentAndOnlyHasFilingActions() = runBlocking {
        Fixture().use { f ->
            f.seed()
            f.chat.openTutor("kp-thread","course","kp",setOf("concept"),"Synthetic objective")
            f.chat.nextStep("kp-thread","course",testUntaught=true)
            val before = f.actor.execute { snapshot("course").projection }
            f.stub.stream(5,"A general answer.",StubTutor.terminal("complete"),StubTutor.DONE)
            lateinit var thread: QuickAskThread
            f.reply {
                val result = f.chat.quickAsk("course","继续")
                thread = result.first
                result.second
            }
            f.stub.stream(5,"An additional answer.",StubTutor.terminal("complete"),StubTutor.DONE)
            f.reply { f.chat.ask(thread.threadId,"course","Could you explain that?") }
            val after = f.actor.execute { snapshot("course").projection }
            assertEquals(before.mastery,after.mastery)
            assertEquals(before.position,after.position)
            assertEquals(before.review,after.review)
            assertTrue(f.actor.execute { events("course") }.none { it.threadId == thread.threadId && it.type in setOf("question_posed","followup_asked","answer_submitted","explanation_delivered") })
            assertEquals(2,f.stub.requests)
            assertTrue(f.stub.requestBodies.all { it.contains("read-only") })
        }
    }

    @Test fun officialRateLimitSwitchesQuickAskAndJournalToCodexWithANotice() = runBlocking {
        Fixture().use { f ->
            f.stub.httpError(429)
            f.stub.completion("A fallback answer.",BridgeClient.CODEX)
            val messages = mutableListOf<kotlinx.serialization.json.JsonObject>()
            val listener = launch(start=CoroutineStart.UNDISPATCHED) { f.chat.hub.frames.collect { messages += it } }
            val thread = f.actor.execute { openQuickAsk("course") }
            val finished = async(start=CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { f.chat.hub.frames.first { it.str("t") == "msg.end" && it.str("model") == BridgeClient.CODEX } } }
            f.chat.ask(thread.threadId,"course","A general question")
            assertEquals("complete",finished.await().str("status"))
            assertTrue(messages.any { it.str("phase") == "rate_limited" && it.str("text").contains(BridgeClient.CODEX) })
            assertTrue(f.stub.requestBodies.last().contains(BridgeClient.CODEX))
            listener.cancelAndJoin()
            f.stub.httpError(429)
            f.stub.completion("""{"summary":"No assessed answers today."}""",BridgeClient.CODEX)
            val journal = assertNotNull(f.chat.journals.summarize("course",thread.day))
            assertTrue(journal.summaryNotice.orEmpty().contains(BridgeClient.CODEX))
            assertEquals(4,f.stub.requests)
        }
    }

    @Test fun journalSummaryHasSnapshotSeqAndConcurrentEditsMakeItStale() = runBlocking {
        Fixture().use { f ->
            val day = f.actor.execute { todayProgress("course").day }
            f.actor.execute { append("course","note_saved",json("content" to "A synthetic note")) }
            val fromSeq = f.actor.execute { snapshot("course").projection.lastSeq }
            f.stub.completion("""{"summary":"One note was saved."}""")
            val summary = assertNotNull(f.chat.journals.summarize("course",day))
            assertEquals(fromSeq,summary.summaryFromSeq)
            assertEquals("One note was saved.",summary.summary)
            assertFalse(summary.summaryStale)
            f.actor.execute { append("course","journal_edited",json("day" to day,"rev" to 1,"content" to "A personal reflection")) }
            val fetched = LedgerJson.decodeFromString<JournalDay>(f.get("/v1/courses/course/journal/$day"))
            assertTrue(fetched.summaryStale)
            assertEquals("A personal reflection",fetched.manual)
            assertEquals(summary.summary,fetched.summary)
            f.stub.completion("unusable summary")
            assertNull(f.chat.journals.summarize("course",day))
            assertEquals(summary.summary,f.actor.execute { journalDay("course",day).summary })
        }
    }
}
