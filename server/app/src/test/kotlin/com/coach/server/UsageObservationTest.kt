package com.coach.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.core.LedgerActor
import com.coach.domain.json
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Properties
import kotlin.test.*

class UsageObservationTest {
    @Test fun upgradingExistingClientRetainsItsLedgerAndAddsDiagnosticQueue() {
        val root=Files.createTempDirectory("coach-usage-upgrade-")
        val url="jdbc:sqlite:${root.resolve("client.sqlite")}"
        try {
            JdbcSqliteDriver(url,Properties(),ClientDatabase.Schema).use { driver ->
                ClientDatabase(driver).ledgerQueries.putMeta("preserved","offline study")
                driver.execute(null,"DROP TABLE usage_outbox",0)
                driver.execute(null,"PRAGMA user_version=2",0)
            }
            JdbcSqliteDriver(url,Properties(),ClientDatabase.Schema).use { driver ->
                val db=ClientDatabase(driver)
                assertEquals("offline study",db.ledgerQueries.getMeta("preserved").executeAsOne())
                UsageTelemetry(db,System::currentTimeMillis) { true }.record("open")
                assertEquals(1,db.ledgerQueries.usageBatch().executeAsList().size)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun offlineRestartLostAckRetriesWithoutDuplicatesAndKeepsContentOut() = runBlocking {
        val root = Files.createTempDirectory("coach-usage-")
        val observer = UsageObservation(root.resolve("logs"),root.resolve("state"))
        var online = false
        var loseAck = true
        var driver: JdbcSqliteDriver? = null
        fun open(): ClientDatabase {
            driver = JdbcSqliteDriver("jdbc:sqlite:${root.resolve("client.sqlite")}", Properties(), ClientDatabase.Schema)
            return ClientDatabase(driver!!)
        }
        val deliver: suspend (List<JsonObject>) -> Boolean = { events ->
            if (!online) false else {
                observer.accept("phone",events)
                if (loseAck) { loseAck=false; false } else true
            }
        }
        try {
            var db = open()
            var telemetry = UsageTelemetry(db,System::currentTimeMillis,deliver)
            telemetry.record("open"); telemetry.record("screen","today"); telemetry.record("tap","test")
            telemetry.record("tap","PRIVATE ANSWER") // never enters the queue
            assertEquals(0,telemetry.flush())
            assertEquals(3,db.ledgerQueries.usageBatch().executeAsList().size)
            driver!!.close()
            db = open(); telemetry = UsageTelemetry(db,System::currentTimeMillis,deliver)
            online=true
            assertEquals(0,telemetry.flush(),"server stored it, ACK lost")
            assertEquals(3,telemetry.flush(),"same event ids survive restart and retry")
            assertTrue(db.ledgerQueries.usageBatch().executeAsList().isEmpty())
            val report=Files.readString(observer.summarize())
            assertTrue(report.contains("客户端事件 3 条"), report)
            assertTrue(report.contains("点按 / 出题"))
            assertFalse(report.contains("PRIVATE ANSWER"))
        } finally { driver?.close();observer.close();root.toFile().deleteRecursively() }
    }

    @Test fun authenticatedBoundedIngestionAccessTemplatesAndErrorSummary() = runBlocking(Dispatchers.IO) {
        val root=Files.createTempDirectory("coach-usage-http-")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val token="private-token-do-not-log-32-characters-minimum"
        val actor=LedgerActor(scope,LedgerStore(root.resolve("data")).also { it.enroll("phone",token) })
        val observer=UsageObservation(root.resolve("logs"),root.resolve("state"))
        val engine=embeddedServer(Netty,host="127.0.0.1",port=0) { ledgerRoutes(actor,MutableSharedFlow(extraBufferCapacity=8),observation=observer) }.apply { start(false) }
        val base="http://127.0.0.1:${engine.engine.resolvedConnectors().single().port}"
        val http=HttpClient.newHttpClient()
        fun send(path: String, body: String?, auth: Boolean=true, chunked: Boolean=false): Int {
            val b=HttpRequest.newBuilder(URI(base+path)).timeout(java.time.Duration.ofSeconds(10))
            if(auth) b.header("X-Coach-Device","phone").header("Authorization","Bearer $token")
            if(body!=null) b.POST(if (chunked) HttpRequest.BodyPublishers.ofInputStream { body.byteInputStream() } else HttpRequest.BodyPublishers.ofString(body))
            return http.send(b.build(),HttpResponse.BodyHandlers.ofString()).statusCode()
        }
        val event=buildJsonObject { put("id","a".repeat(32));put("at",System.currentTimeMillis());put("kind","error");put("target","server");put("ms",-1) }
        try {
            assertEquals(401,send("/v1/usage",JsonArray(listOf(event)).toString(),false))
            assertEquals(204,send("/v1/usage",JsonArray(listOf(event)).toString()))
            assertEquals(204,send("/v1/usage",JsonArray(listOf(event)).toString()))
            assertEquals(400,send("/v1/usage",JsonArray(listOf(JsonObject(event+ ("text" to JsonPrimitive("PRIVATE ANSWER"))))).toString()))
            assertEquals(413,send("/v1/usage"," ".repeat(33_000),chunked=true))
            assertEquals(404,send("/private-question?text=PRIVATE_QUESTION",null))
            val report=Files.readString(observer.summarize())
            assertTrue(report.contains("客户端事件 1 条"),report)
            val log=Files.readString(root.resolve("logs/access-${LocalDate.now(ZoneOffset.UTC)}.log"))
            assertTrue(log.contains("/unmatched"))
            assertTrue(log.contains("\"status\":401"))
            assertTrue(log.contains("\"device\":\"phone\""))
            assertFalse(log.contains(token));assertFalse(log.contains("PRIVATE"));assertFalse(report.contains(token))
            println("USAGE_HTTP: auth=401 duplicate_events=1 oversize=413 privacy=clean")
        } finally { engine.stopSuspend(0,500);actor.close();scope.cancel();observer.close();root.toFile().deleteRecursively() }
    }

    @Test fun teachAndAskFirstWordsExcludeProgressAndSurviveDuplicateDeltas() = runBlocking {
        val root=Files.createTempDirectory("coach-usage-ttft-")
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ClientDatabase.Schema.create(it) }
        val db=ClientDatabase(driver)
        val telemetry=UsageTelemetry(db,System::currentTimeMillis) { true }
        val model=TutorModel(System::currentTimeMillis,telemetry)
        try {
            for ((i,kind) in listOf("next","ask").withIndex()) {
                model.onSent("thread",kind,null)
                model.onFrame(json("t" to "msg.start","msg" to "m$i","threadId" to "thread").toString())
                model.onFrame(json("t" to "msg.progress","msg" to "m$i","phase" to "thinking").toString())
                val frame=json("t" to "msg.delta","msg" to "m$i","i" to 1,"text" to "PRIVATE ANSWER").toString()
                model.onFrame(frame);model.onFrame(frame)
            }
            val queued=db.ledgerQueries.usageBatch().executeAsList().map { Json.parseToJsonElement(it.payload).jsonObject }
            assertEquals(2,queued.size)
            assertEquals(listOf("next","ask"),queued.map { it.getValue("target").jsonPrimitive.content })
            assertTrue(queued.all(UsageVocabulary::valid));assertFalse(queued.toString().contains("PRIVATE"))
            UsageObservation(root.resolve("logs"),root.resolve("state")).use { observer ->
                var now=0L
                val first=WsFirstWords("phone",observer) { now }
                first.received(json("t" to "tutor.next","threadId" to "thread"))
                first.sent(json("t" to "msg.start","msg" to "m","threadId" to "thread"))
                first.sent(json("t" to "msg.progress","msg" to "m","text" to "progress"))
                now=73_000_000
                first.sent(json("t" to "msg.delta","msg" to "m","text" to "PRIVATE"))
                first.sent(json("t" to "msg.delta","msg" to "m","text" to "PRIVATE"))
                val lines=Files.readAllLines(root.resolve("logs/access-${LocalDate.now(ZoneOffset.UTC)}.log"))
                assertEquals(1,lines.size);assertTrue(lines.single().contains("\"firstWordMs\":73"));assertFalse(lines.single().contains("PRIVATE"))
            }
        } finally { driver.close();root.toFile().deleteRecursively() }
    }
}
