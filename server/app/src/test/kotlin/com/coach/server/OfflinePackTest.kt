package com.coach.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.store.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlin.test.*
import kotlin.time.measureTime

class OfflinePackTest {
    private val token = "isolated-offline-pack-test-token-32-chars"
    private val options = (1..4).map { Option("o$it","Synthetic option $it") }
    private fun seed(store: LedgerStore, count: Int) {
        store.enroll("phone",token); store.enroll("tablet",token)
        store.append("course","kp_opened",json("kpId" to "learning","title" to "Learning target"))
        repeat(count) { store.registerQuestion("q$it","course","ignored","SINGLE_CHOICE","Synthetic offline stem $it","VERIFIED","o${it%4+1}",options,listOf("review")) }
    }
    private class Server(val store: LedgerStore) {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val actor = LedgerActor(scope,store)
        val engine = embeddedServer(Netty,host="127.0.0.1",port=0) { ledgerRoutes(actor,MutableSharedFlow(extraBufferCapacity=64)) }.apply {
            engineConfig.apply { connectionGroupSize=1; workerGroupSize=1; callGroupSize=2 }; start(wait=false)
        }
        suspend fun url() = "http://127.0.0.1:${engine.engine.resolvedConnectors().first().port}"
        suspend fun close() { engine.stopSuspend(0,1000);actor.close();scope.cancel() }
    }
    private fun client(db: ClientDatabase, transport: LedgerTransport) = SyncEngine(db,"phone",transport,listOf("course"),{ UUID.randomUUID().toString() },::sha256)

    @Test fun downloadDisconnectRestartGradeThirtyAndReconcileWithoutLossOrDuplicate() = runBlocking {
        val root = Files.createTempDirectory("offline-pack-http-")
        val store = LedgerStore(root.resolve("server"));seed(store,70)
        val server = Server(store)
        var driver = JdbcSqliteDriver("jdbc:sqlite:${root.resolve("phone.sqlite")}")
        ClientDatabase.Schema.create(driver)
        val phone = HttpLedgerTransport(server.url(),"phone",token)
        val tablet = HttpLedgerTransport(server.url(),"tablet",token)
        try {
            var db = ClientDatabase(driver)
            var sync = client(db,phone);sync.syncOnce()
            val before = sync.projection("course").position
            val packs = awaitAll(async { phone.offlinePack("course",30) },async { tablet.offlinePack("course",30) })
            val p = packs[0]
            val phoneIds = p.groups.flatten().map { it.payload.str("questionId") }.toSet()
            assertEquals(30,phoneIds.size)
            assertTrue(phoneIds.intersect(packs[1].groups.flatten().map { it.payload.str("questionId") }.toSet()).isEmpty())
            assertEquals(p,phone.offlinePack("course",30))
            sync.downloadOfflinePack("course",30)
            assertEquals(before,sync.projection("course").position)
            assertFalse(LedgerJson.encodeToString(store.snapshot("course")).contains("correctOptionId"))
            val all = p.groups.flatten()
            val correct = p.keys.associate { it.attemptId to it.correctOptionId }
            val allocated = (p.groups+packs[1].groups).flatten().map { event ->
                val key = (p.keys+packs[1].keys).single { it.attemptId == event.payload.str("attemptId") }
                (event.payload["letterMap"] as JsonObject).str(key.correctOptionId)
            }
            assertTrue(allocated.windowed(3).all { it.distinct().size == 3 })
            all.forEach { assertNull(db.ledgerQueries.offlineKeyAfterLock(it.payload.str("attemptId")).executeAsOneOrNull()) }
            // Simulate unavailable HTTP without changing server state. UI read + submit use only SQLite.
            val disconnected = object : LedgerTransport by phone {
                override suspend fun hello(epoch: String?): Hello = error("network disconnected")
                override suspend fun command(command: WireCommand): Receipt = error("network disconnected")
            }
            sync = client(db,disconnected)
            val openTime = measureTime { assertEquals(10,sync.offlineGroups("course",System.currentTimeMillis()).size) }
            assertTrue(openTime.inWholeMilliseconds < 1000)
            val localResults = mutableMapOf<String,Boolean>()
            for ((index,group) in p.groups.withIndex()) {
                val groupId = group.first().payload.str("groupId")
                val answers = group.associate { event ->
                    val id = event.payload.str("attemptId")
                    val right = (event.payload["letterMap"] as JsonObject).str(correct.getValue(id))
                    id to if (index%2==0) right else CHOICE_LETTERS.first { it != right }
                }
                val pending = sync.retainedCommands().size
                assertFails { sync.submitOfflineGroup("course",groupId,emptyMap(),System.currentTimeMillis()) }
                assertEquals(pending,sync.retainedCommands().size)
                sync.submitOfflineGroup("course",groupId,answers,System.currentTimeMillis())
                sync.offlineGroups("course",System.currentTimeMillis()).single { it.groupId==groupId }.cards.forEach {
                    assertNotNull(it.answer); localResults[it.attemptId] = assertNotNull(it.grade).correct
                }
                assertFails { sync.submitOfflineGroup("course",groupId,answers,System.currentTimeMillis()) }
            }
            assertFails { sync.syncOnce() }
            val immutable = sync.retainedCommands()
            assertEquals(30,immutable.size)
            driver.close(); driver=JdbcSqliteDriver("jdbc:sqlite:${root.resolve("phone.sqlite")}")
            db=ClientDatabase(driver);sync=client(db,phone)
            assertEquals(immutable,sync.retainedCommands())
            assertEquals(30,sync.offlineGroups("course",System.currentTimeMillis()).sumOf { it.cards.count { c -> c.grade != null } })
            sync.syncOnce()
            val facts=server.actor.execute { events("course") }
            val answers=facts.filter { it.type=="answer_submitted" }
            assertEquals(30,answers.size);assertEquals(30,answers.map { it.payload.str("attemptId") }.distinct().size)
            val grades=facts.filter { it.type=="attempt_graded" }
            assertEquals(30,grades.size)
            assertTrue(grades.all { (it.payload.num("score")==1L)==localResults.getValue(it.payload.str("attemptId")) })
            assertTrue(sync.offlineGroups("course",System.currentTimeMillis()).flatMap { it.cards }.all { it.grade?.note=="服务端已确认" })
            val seq=server.actor.execute { hello().lastSeq };sync.syncOnce();assertEquals(seq,server.actor.execute { hello().lastSeq })
            assertEquals(before.continueTarget,sync.projection("course").position.continueTarget)
            server.actor.execute { backup(root.resolve("backup")) };sync.syncOnce();assertTrue(sync.retainedCommands().isEmpty())
            val conn=URI(server.url()+"/offline-pack?size=10").toURL().openConnection() as HttpURLConnection
            assertEquals(401,conn.responseCode);conn.disconnect()
            println("OFFLINE_HTTP n=30 agreement=30/30 lost=0 duplicateFirstAnswers=0 outboxPending=0 devicesOverlap=0 startMs=${openTime.inWholeMilliseconds} restart=true continueUnchanged=true")
        } finally { phone.close();tablet.close();driver.close();server.close();root.toFile().deleteRecursively() }
    }

    @Test fun reservationsSurviveRestartExcludeOnlineAndExpireByWallClock() {
        val root=Files.createTempDirectory("offline-expiry-")
        var now=1_800_000_000_000L
        var store=LedgerStore(root) { now }
        try {
            seed(store,30)
            val p=store.downloadOfflinePack("course","phone",10,1000)
            assertEquals(listOf(3,3,3,1),p.groups.map { it.size })
            assertEquals(20,store.poolAvailable("course","review"))
            assertEquals(20,store.tutorQuestions("course","review").size)
            val position=store.snapshot("course").projection.position
            store.close();store=LedgerStore(root) { now }
            assertEquals(p,store.downloadOfflinePack("course","phone",10,1000))
            val tablet=store.downloadOfflinePack("course","tablet",10,1000)
            val reserved=(p.groups+tablet.groups).flatten().map { it.payload.str("questionId") }.toSet()
            assertEquals(position,store.snapshot("course").projection.position)
            val online=store.takePoolGroup("course","review","online").events
            assertTrue(online.none { it.payload.str("questionId") in reserved })
            now+=1000
            assertEquals(27,store.poolAvailable("course","review"))
            val released=store.takePoolGroup("course","review","released-online",size=1).events.single()
            val snapshot=released.payload
            val letter=(snapshot["letterMap"] as JsonObject).values.first().jsonPrimitive.content
            val body=CommandBody("submit_answer","course",now,json("attemptId" to snapshot.str("attemptId"),"answer" to letter,"posedSnapshot" to snapshot))
            val bytes=canonicalJson(LedgerJson.encodeToJsonElement(body))
            assertEquals("OK",store.command(WireCommand("tablet","released-answer",bytes,sha256(bytes.toByteArray()))).status)
            val next=store.downloadOfflinePack("course","phone",10,1000)
            assertNotEquals(p.packId,next.packId)
            assertTrue(next.groups.flatten().any { it.payload.str("questionId") in reserved })
            println("OFFLINE_RESERVATION groups=3+3+3+1 restart=true onlineOverlap=0 expiresAtBoundary=true released=20")
        } finally { store.close();root.toFile().deleteRecursively() }
    }

    @Test fun existingClientMigrationRetainsOutboxBytes() {
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            ClientDatabase.Schema.create(driver)
            val db=ClientDatabase(driver)
            val old="{ \"oldSerializerField\": true }"
            db.ledgerQueries.putCommand("old-command","phone",old,sha256(old.toByteArray()))
            // The prior deployed schema has no offline tables.
            for (name in listOf("offline_packs","offline_items","offline_keys")) driver.execute(null,"DROP TABLE $name",0)
            ClientDatabase.Schema.migrate(driver,1,2)
            assertEquals(old,db.ledgerQueries.allCommands().executeAsOne().payload_bytes)
            assertTrue(db.ledgerQueries.offlineItems("course").executeAsList().isEmpty())
            println("OFFLINE_MIGRATION existingOutboxBytesPreserved=true")
        } finally { driver.close() }
    }

    @Test fun constructedOfflineAnswerKeepsOriginalTextAndWaitsForServer() = runBlocking {
        val root=Files.createTempDirectory("offline-constructed-")
        val store=LedgerStore(root);seed(store,1)
        store.registerQuestion("constructed","course","constructed-fp","SHORT_ANSWER","Synthetic explain task","VERIFIED",kpIds=listOf("review"))
        val server=Server(store)
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY);ClientDatabase.Schema.create(driver)
        val http=HttpLedgerTransport(server.url(),"phone",token)
        val transport=object : LedgerTransport by http {
            override suspend fun offlinePack(course: String,size: Int): OfflineChoicePack {
                val hello=http.hello(null)
                val posed=Event(0,"constructed-event",course,"question_posed",recordedAt=System.currentTimeMillis(),occurredAt=System.currentTimeMillis(),deviceId="phone",actor="SYSTEM",
                    payload=json("attemptId" to "constructed-attempt","questionId" to "constructed","fingerprint" to "constructed-fp","kpIds" to listOf("review"),
                        "format" to "SHORT_ANSWER","groupId" to "constructed-group","purpose" to "RECALL","policyVersion" to 1,"offlinePack" to true))
                return OfflineChoicePack(listOf(listOf(posed)),emptyList(),"constructed-pack",course,"phone",System.currentTimeMillis()+60000,hello.epoch)
            }
        }
        val sync=client(ClientDatabase(driver),transport)
        try {
            sync.syncOnce();sync.downloadOfflinePack("course",1)
            val original="  Mixed case answer: aB，逆向选择。\n第二行  "
            sync.submitOfflineGroup("course","constructed-group",mapOf("constructed-attempt" to original),System.currentTimeMillis())
            val local=sync.offlineGroups("course",System.currentTimeMillis()).single().cards.single()
            assertEquals(original,local.answer);assertNull(local.grade)
            sync.syncOnce()
            val answer=server.actor.execute { events("course") }.single { it.type=="answer_submitted" }
            assertEquals(original,answer.payload.str("answer"))
            assertTrue(server.actor.execute { events("course") }.none { it.type=="attempt_graded" })
            println("OFFLINE_CONSTRUCTED originalTextPreserved=true localGrades=0")
        } finally { http.close();driver.close();server.close();root.toFile().deleteRecursively() }
    }

    @Test fun authoritativeGradeReplacesLocalDisagreement() = runBlocking {
        val root=Files.createTempDirectory("offline-correction-")
        val store=LedgerStore(root);seed(store,3)
        val server=Server(store)
        val driver=JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY);ClientDatabase.Schema.create(driver)
        val db=ClientDatabase(driver)
        val transport=HttpLedgerTransport(server.url(),"phone",token)
        val sync=client(db,transport)
        try {
            sync.syncOnce();sync.downloadOfflinePack("course",3)
            val group=sync.offlineGroups("course",System.currentTimeMillis()).single()
            val answers=group.cards.associate { it.attemptId to "A" }
            sync.submitOfflineGroup("course",group.groupId,answers,System.currentTimeMillis())
            // Reproduce stale/corrupt cached local grading; authoritative result must visibly win.
            sync.offlineGroups("course",System.currentTimeMillis()).single().cards.forEach {
                db.ledgerQueries.putLocalGrade(if(it.grade!!.correct) 0L else 1L,it.attemptId)
            }
            sync.syncOnce()
            val cards=sync.offlineGroups("course",System.currentTimeMillis()).single().cards
            assertTrue(cards.all { it.grade?.note=="服务端已更正，以此结果为准" })
            println("OFFLINE_CORRECTION corrected=3/3 serverWins=true visible=true")
        } finally { transport.close();driver.close();server.close();root.toFile().deleteRecursively() }
    }
}
