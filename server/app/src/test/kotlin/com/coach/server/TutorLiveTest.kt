package com.coach.server

import com.coach.ai.*
import com.coach.client.ChatStreamAssembler
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Opt-in production Ktor/WS + real CLI walk. Imports only previously accepted local questions. */
class TutorLiveTest {
    @Test fun lessonThenThreeChoicesFollowupAndExactGrades() = runBlocking(Dispatchers.IO) {
        val now = Instant.now()
        require(now < Instant.parse("2026-11-02T00:00:00Z") || now >= Instant.parse("2026-11-02T15:00:00Z")) { "期中暂停窗口" }
        val home = Path.of(System.getProperty("user.home"))
        val root = Path.of(System.getenv("COACH_TUTOR_LIVE_OUT") ?: home.resolve("Coach/corpus/tutor-live/${now.epochSecond}").toString())
        Files.createDirectories(root)
        fun record(vararg fields: Pair<String,Any?>) {
            val row = json("at" to Instant.now().toString(),*fields)
            Files.writeString(root.resolve("results.jsonl"),"$row\n",CREATE,APPEND)
            println("TUTOR_LIVE $row")
        }
        val corpus = Path.of(System.getenv("COACH_GRADEGEN_DIR") ?: home.resolve("Coach/corpus/gradegen").toString())
        val accepted = Files.list(corpus.resolve("runs/kp")).use { files -> files.filter { it.toString().endsWith(".json") && !it.toString().endsWith(".gen.json") }.sorted().map { file ->
            LedgerJson.parseToJsonElement(Files.readString(file)).jsonObject
        }.filter { (it["accepted"] as? JsonArray)?.size?.let { count -> count >= 3 } == true }.findFirst().orElseThrow { IllegalStateException("缺少已复核题池，未运行") } }
        val kpId = accepted.getValue("kp").let { if (it is JsonObject) it.str("id") else it.jsonPrimitive.content }
        val kp = LedgerJson.parseToJsonElement(Files.readString(corpus.resolve("inputs/kps.json"))).jsonArray.map { it.jsonObject }.single { it.str("id") == kpId }
        val choices = accepted.getValue("accepted").jsonArray.take(3).map { it.jsonObject }
        val bridge = BridgeClient()
        var ownedBridge: Process? = null
        var scope: CoroutineScope? = null
        var actor: LedgerActor? = null
        var stopServer: (suspend () -> Unit)? = null
        try {
            if (bridge.health() == null) {
                ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1",8791)) }
                val python = System.getenv("COACH_BRIDGE_PYTHON") ?: "tools/bridge/.venv/bin/python"
                ownedBridge = ProcessBuilder(python,"tools/bridge/subscription_bridge.py").apply {
                    environment().remove("BRIDGE_CHILD_PATH")
                    environment()["BRIDGE_PORT"] = "8791"
                    environment()["COACH_BRIDGE_HOME"] = home.resolve("Coach/bridge").toString()
                    environment()["BRIDGE_DAILY_CLAUDE_CAP"] = "60"
                    environment()["BRIDGE_RATE_PAUSE_AT"] = "0.5"
                    environment()["BRIDGE_CLAUDE_CONCURRENCY"] = "1"
                    environment()["BRIDGE_AGY_ENABLED"] = "0"
                    redirectOutput(root.resolve("bridge.stdout.log").toFile())
                    redirectError(root.resolve("bridge.stderr.log").toFile())
                }.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (bridge.health() == null) {
                    check(ownedBridge.isAlive && System.nanoTime() < deadline) { "桥未就绪" }
                    delay(100)
                }
                record("stage" to "bridge","ownedPid" to ownedBridge.pid())
            }
            fun preflight(required: Int = 1): JsonObject {
                val h = requireNotNull(bridge.health()) { "桥不可用" }
                val daily = h.getValue("daily").jsonObject
                val rate = h.getValue("rate_limit").jsonObject
                require(daily.num("remaining") >= required && rate.str("all_paused") != "true") { "测试额度不足或使用率暂停" }
                return h
            }
            val before = preflight(2)
            record("stage" to "preflight","estimatedCalls" to 2,"dailyBefore" to before["daily"])
            val runScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
            val store = LedgerStore(root.resolve("data"))
            val token = UUID.randomUUID().toString()
            store.enroll("tutor-live",token)
            choices.forEachIndexed { index,q ->
                val rawOptions = q.getValue("options").jsonObject
                val options = rawOptions.entries.mapIndexed { i,entry -> Option("option-$i",entry.value.jsonPrimitive.content) }
                val correct = options[rawOptions.keys.indexOf(q.str("key"))].optionId
                store.registerQuestion("accepted-$index","live-course","","SINGLE_CHOICE",q.str("stem"),"VERIFIED",correct,options,listOf(kpId))
            }
            val ledger = LedgerActor(runScope,store).also { actor = it }
            val chat = ChatService(ledger,runScope,bridge,fallbackOnFirstTokenTimeout=false,log={})
            val server = embeddedServer(Netty,host="127.0.0.1",port=0,parentCoroutineContext=runScope.coroutineContext) { ledgerRoutes(ledger,MutableSharedFlow(extraBufferCapacity=64),chat) }.apply {
                engineConfig.apply { connectionGroupSize=1; workerGroupSize=1; callGroupSize=2 }
                start(wait=false)
            }
            stopServer = { server.stopSuspend(0,500) }
            val port = server.engine.resolvedConnectors().single().port
            Client(port,token).use { ws ->
                val thread = "tutor-live-thread"
                fun send(type: String,vararg fields: Pair<String,Any?>) = ws.send(json("t" to type,"courseId" to "live-course","threadId" to thread,*fields))
                send("tutor.open","kpId" to kpId,"conceptIds" to listOf(kpId),"title" to kp.str("objective"))
                assertEquals("IDLE",ws.await("tutor.state").str("state"))
                suspend fun reply(label: String,question: String): String {
                    preflight()
                    val started = System.nanoTime()
                    send("msg.ask","text" to question,"pack" to kp.str("excerpt"))
                    val id = ws.await("msg.start").str("msg")
                    val assembler = ChatStreamAssembler()
                    var first: Long? = null
                    while (true) {
                        val frame = ws.await("msg.delta","msg.math","msg.snapshot","msg.end")
                        assembler.handle(frame)
                        if (first == null && frame.str("t") in setOf("msg.delta","msg.math") && assembler.text(id).isNotBlank()) first = (System.nanoTime()-started)/1_000_000
                        if (frame.str("t") == "msg.end") {
                            record("stage" to label,"status" to frame.str("status"),"ttftMs" to first,"chars" to assembler.text(id).length)
                            assertEquals("complete",frame.str("status"))
                            assertTrue(assembler.text(id).isNotBlank())
                            assertEquals(assembler.text(id),ledger.execute { messageSnapshot(id) }?.content)
                            return id
                        }
                    }
                }
                reply("teach","继续")
                val callsAfterTeach = sqlCalls(root.resolve("data/coach.sqlite"))
                val started = System.nanoTime()
                send("msg.ask","text" to "出题")
                val group = ws.await("tutor.cards")
                val cards = group.getValue("cards").jsonArray.map { it.jsonObject }
                assertEquals(3,cards.size)
                assertTrue(cards.none { it.flag("untaught") })
                assertEquals(callsAfterTeach,sqlCalls(root.resolve("data/coach.sqlite")))
                record("stage" to "pose","cards" to cards.size,"elapsedMs" to (System.nanoTime()-started)/1_000_000,"newAiCalls" to 0)
                reply("followup","先别给答案，只提醒我该怎样分析这些题。")
                val answers = ledger.execute { JsonObject(cards.associate { card -> card.str("attemptId") to card.getValue("letterMap").jsonObject.getValue(tutorChoiceKey(card.str("questionId")).correctOptionId) }) }
                send("tutor.answer","groupId" to group.str("groupId"),"submissionId" to UUID.randomUUID().toString(),"answers" to answers)
                val feedback = ws.await("tutor.feedback").getValue("grades").jsonArray
                assertEquals(3,feedback.size)
                assertTrue(feedback.all { it.jsonObject.str("status") == "EXACT" && it.jsonObject.num("score") == 1L })
                val snapshot = ledger.execute { snapshot("live-course") }
                val leakIds = snapshot.events.filter { it.type == "hint_revealed" }.map { it.payload.str("attemptId") }.toSet()
                val window = snapshot.projection.mastery[kpId]?.window.orEmpty()
                assertEquals(cards.count { it.str("attemptId") !in leakIds },window.size)
                assertTrue(window.none { it in leakIds })
                val explanations = snapshot.events.filter { it.type == "explanation_delivered" }
                assertEquals(1,explanations.size)
                record("stage" to "grade","exactGrades" to feedback.size,"window" to window.size,"passed" to (snapshot.projection.mastery[kpId]?.passed == true),
                    "leaks" to leakIds.size,"explanations" to explanations.size,"eventSeqs" to snapshot.events.filter { it.type in setOf("question_posed","answer_submitted","attempt_graded") }.map { it.seq })
            }
            record("stage" to "complete","aiCalls" to sqlCalls(root.resolve("data/coach.sqlite")),"dailyAfter" to bridge.health()?.get("daily"))
        } finally {
            stopServer?.invoke()
            actor?.close()
            scope?.cancel()
            ownedBridge?.let { process ->
                process.destroy()
                if (!process.waitFor(10,TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS) }
                record("stage" to "cleanup","ownedBridgeStopped" to !process.isAlive)
            }
        }
    }

    private fun sqlCalls(path: Path): Int = java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { c -> c.createStatement().use { s -> s.executeQuery("SELECT coalesce(sum(physical_calls),0) FROM ai_calls").use { rows -> rows.next(); rows.getInt(1) } } }

    private class Client(port: Int,token: String): AutoCloseable {
        private val queue = LinkedBlockingQueue<JsonObject>()
        private val socket = HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization","Bearer $token").header("X-Coach-Device","tutor-live")
            .buildAsync(URI.create("ws://127.0.0.1:$port/v1/ws"),object: WebSocket.Listener {
                private val buffer = StringBuilder()
                override fun onText(ws: WebSocket,data: CharSequence,last: Boolean): CompletionStage<*>? {
                    buffer.append(data)
                    if (last) { queue.add(LedgerJson.parseToJsonElement(buffer.toString()).jsonObject); buffer.setLength(0) }
                    ws.request(1); return null
                }
            }).get(5,TimeUnit.SECONDS)
        fun send(frame: JsonObject) { socket.sendText(frame.toString(),true).get(5,TimeUnit.SECONDS) }
        fun await(vararg types: String): JsonObject {
            val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(195)
            while (System.nanoTime()<deadline) {
                val frame = queue.poll(50,TimeUnit.MILLISECONDS) ?: continue
                if (frame.str("t") == "msg.error") error("服务端拒绝命令：${frame.str("error")}")
                if (frame.str("t") in types) return frame
            }
            error("WS timeout waiting for ${types.toList()}")
        }
        override fun close() { socket.abort() }
    }
}
