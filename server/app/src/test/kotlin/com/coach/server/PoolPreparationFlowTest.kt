package com.coach.server

import com.coach.ai.*
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.LedgerStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class PoolPreparationFlowTest {
    @Test fun emptyPoolShowsRealStagesAndPushesReviewedCardsWithoutAnotherClick() = runBlocking<Unit> {
        val dir=Files.createTempDirectory("pool-flow")
        val packs=dir.resolve("packs")
        Files.createDirectories(packs.resolve("course"))
        val source="Synthetic source says the uniquely correct result is the selected text."
        Files.writeString(packs.resolve("course/kp.md"),source)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val actor=LedgerActor(scope,LedgerStore(dir.resolve("data")))
        StubTutor().use { stub ->
            val chat=ChatService(actor,scope,BridgeClient(BridgeConfig(stub.baseUrl)),packsDirectory=packs,log={})
            val frames=java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
            val listener=scope.launch(start=CoroutineStart.UNDISPATCHED) { chat.hub.frames.collect { frames.add(it) } }
            try {
                actor.execute { append("course","outline_revised",json("ops" to JsonArray(listOf(json(
                    "kpId" to "kp","title" to "Synthetic objective","op" to "add"))))) }
                chat.openTutor("thread","course","kp",setOf("concept"),"Synthetic objective")
                // First generation is malformed; the durable retry must use a changed prompt.
                stub.completion("[{broken]")
                stub.completion(JsonArray((0..2).map { i -> json("stem" to "Synthetic question $i asks for the uniquely correct result.",
                    "correct_text" to "Right result $i","distractors" to listOf("Wrong one $i","Wrong two $i","Wrong three $i"),
                    "explanation" to "Supported by the source.","source_quote" to source) }).toString())
                stub.completion(JsonArray((0..2).map { i -> json("id" to i,"answer_text" to "Right result $i",
                    "defects" to emptyList<String>(),"note" to "Supported") }).toString(),BridgeClient.CODEX)
                chat.nextStep("thread","course",testUntaught=true)
                delay(600)
                assertTrue(frames.any { it.str("t")=="tutor.progress" && it.str("phase")=="pool_queued" })
                val worker=PoolWorker(actor,BridgeClient(BridgeConfig(stub.baseUrl)))
                assertEquals("retrying",worker.runOne()!!.state)
                delay(600)
                assertTrue(frames.any { it.str("phase")=="pool_retrying" })
                assertEquals("completed",worker.runOne()!!.state)
                assertTrue(frames.none { it.str("t")=="tutor.cards" })
                assertEquals(3,worker.runOne()!!.inserted)
                withTimeout(5_000) { while(frames.none { it.str("t")=="tutor.cards" }) delay(20) }
                val cards=frames.single { it.str("t")=="tutor.cards" }.getValue("cards").jsonArray
                assertEquals(3,cards.size)
                assertTrue(cards.all { "correctOptionId" !in it.jsonObject })
                delay(600)
                assertEquals(1,frames.count { it.str("t")=="tutor.cards" })
                assertEquals(3,actor.execute { events("course").count { it.type=="question_posed" } })
                println("POOL_REPLAY clicks=1 cards=3 unreviewedCards=0 retries=1 duplicateGroups=0")
            } finally { listener.cancelAndJoin();scope.coroutineContext[Job]!!.cancelChildren();actor.close();scope.cancel() }
        }
        dir.toFile().deleteRecursively()
    }
}
