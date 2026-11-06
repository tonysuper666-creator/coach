package com.coach.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.core.LedgerActor
import com.coach.domain.*
import com.coach.protocol.*
import com.coach.store.LedgerStore
import com.coach.store.sha256
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocketRaw
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** TON-1241 客户端审计：每条先在 main 上失败，再修。隔离端口、临时目录，不碰生产端口与数据。 */
class ClientAuditTest {
    private val token = "client-audit-test-token-32-characters-min"

    private fun clientDb(): Pair<JdbcSqliteDriver, ClientDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        return driver to ClientDatabase(driver)
    }

    /**
     * 服务端对某条离线命令永久拒收（换过配对留下的旧设备命令 → 403、超过 1 MiB 的笔记 → 413）时，
     * 修前 syncOnce 在解析回执时抛错、从此再也拉不到新事实，自己后面的命令也传不上去。
     */
    @Test fun refusedOutboxCommandDoesNotFreezeSync() = runBlocking {
        val root = Files.createTempDirectory("coach-audit-")
        val store = LedgerStore(root.resolve("server"))
        store.enroll("phone", token)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actor = LedgerActor(scope, store)
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 64)) }.apply { start(wait = false) }
        val port = engine.engine.resolvedConnectors().first().port
        check(port !in setOf(8790, 8791, 18791))
        val (driver, db) = clientDb()
        val transport = HttpLedgerTransport("http://127.0.0.1:$port", "phone", token)
        val sync = SyncEngine(db, "phone", transport, listOf("course"), { UUID.randomUUID().toString() }, ::sha256)
        try {
            sync.syncOnce()
            val bytes = canonicalJson(LedgerJson.encodeToJsonElement(CommandBody("save_note", "course", 1L, json("noteId" to "old", "text" to "旧配对"))))
            db.ledgerQueries.putCommand("left-by-old-pairing", "phone-before-repair", bytes, sha256(bytes.encodeToByteArray()))
            val huge = sync.enqueue(CommandBody("save_note", "course", 2L, json("noteId" to "huge", "text" to "长".repeat(400_000))))
            sync.enqueue(CommandBody("save_note", "course", 3L, json("noteId" to "mine", "text" to "这条应该上传")))
            store.append("course", "note_saved", json("noteId" to "from-mac", "text" to "Mac 上新记的"))
            sync.syncOnce()
            val notes = sync.localEvents("course").filter { it.type == "note_saved" }.map { it.payload.str("noteId") }
            assertTrue("from-mac" in notes, "被拒的命令挡住了拉取：$notes")
            assertTrue("mine" in notes, "被拒的命令挡住了后面的命令：$notes")
            assertEquals("REJECTED", sync.receipt(huge.commandId)?.status)
            sync.syncOnce()
        } finally { transport.close(); engine.stop(0, 500); actor.close(); scope.cancel(); driver.close() }
    }

    private class FakeLedger : LedgerTransport {
        val events = Collections.synchronizedList(mutableListOf<Event>())
        val snapshots = AtomicInteger()
        fun add(type: String) { events += Event(events.size + 1L, "e${events.size + 1}", "course", type, recordedAt = 1, occurredAt = 1, deviceId = "mac", actor = "SYSTEM", payload = json("noteId" to "n${events.size}")) }
        private fun hello() = Hello("epoch-1", events.size.toLong(), 0, 0)
        override suspend fun hello(epoch: String?) = hello()
        override suspend fun snapshot(course: String): Snapshot { snapshots.incrementAndGet(); return Snapshot(hello(), course, events.toList(), Projection(lastSeq = events.size.toLong())) }
        override suspend fun events(course: String, after: Long) = EventsPage("epoch-1", events.filter { it.seq > after }, events.size.toLong())
        override suspend fun command(command: WireCommand): Receipt = error("no commands in this test")
        override suspend fun watch(onLedger: suspend () -> Unit) = awaitCancellation()
    }

    /** 新版服务端写入了本客户端不认识的事实类型：修前每次轮询（2 秒一次）都重新下载整份快照（含全部事件）。 */
    @Test fun unknownFactTypeDownloadsSnapshotOncePerChange() = runBlocking {
        val ledger = FakeLedger().apply { add("note_saved"); add("future_learning_fact") }
        val (driver, db) = clientDb()
        val sync = SyncEngine(db, "phone", ledger, listOf("course"), { UUID.randomUUID().toString() }, ::sha256)
        try {
            sync.syncOnce()
            assertTrue(sync.projection("course").requiresServerSnapshot)
            val first = ledger.snapshots.get()
            repeat(3) { sync.syncOnce() }
            assertEquals(first, ledger.snapshots.get(), "账本没变，却又下载了 ${ledger.snapshots.get() - first} 次整份快照")
            ledger.add("note_saved")
            sync.syncOnce()
            assertEquals(first + 1, ledger.snapshots.get(), "有新事实时仍要刷新服务端投影")
        } finally { driver.close() }
    }

    /** 每 2 秒一次的轮询即使什么都没变也让界面从整份账本重算课程视图；只有真有变化才该刷新。 */
    @Test fun quietPollDoesNotRefreshTheScreen() = runBlocking {
        val ledger = FakeLedger().apply { add("note_saved") }
        val (driver, db) = clientDb()
        val sync = SyncEngine(db, "phone", ledger, listOf("course"), { UUID.randomUUID().toString() }, ::sha256)
        try {
            sync.syncOnce()
            val settled = sync.revision.value
            assertTrue(settled > 0, "首次同步后界面要刷新")
            repeat(3) { sync.syncOnce() }
            assertEquals(settled, sync.revision.value, "没有任何变化，界面却重算了 ${sync.revision.value - settled} 次")
            ledger.add("note_saved")
            sync.syncOnce()
            assertTrue(sync.revision.value > settled, "有新事实时界面必须刷新")
        } finally { driver.close() }
    }

    /**
     * 发问进了发送队列，但连接随即断开（半开连接、出站缓冲里的帧随 socket 丢掉）：
     * 修前这句话在界面上像已发出，服务端却从没收到，重连后也不补发。服务端按 clientMsgId 去重，补发是安全的。
     */
    @Test fun askLostWithItsConnectionIsDeliveredAfterReconnect() = runBlocking {
        val connections = AtomicInteger()
        val delivered = Collections.synchronizedList(mutableListOf<Pair<Int, String>>())
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                webSocketRaw("/v1/ws") {
                    val n = connections.incrementAndGet()
                    for (frame in incoming) when (frame) {
                        is Frame.Ping -> send(Frame.Pong(frame.data))
                        is Frame.Text -> {
                            val obj = Json.parseToJsonElement(frame.readText()).jsonObject
                            if (obj["t"]?.jsonPrimitive?.content != "msg.ask") continue
                            val clientMsgId = obj.getValue("clientMsgId").jsonPrimitive.content
                            delivered += n to clientMsgId
                            // 第一条连接在确认之前就断了。
                            if (n == 1) return@webSocketRaw
                            send(Frame.Text(json("t" to "msg.user", "msg" to "u1", "role" to "user", "status" to "complete", "threadId" to "t1",
                                "text" to "什么是极限", "createdAt" to 1L, "clientMsgId" to clientMsgId, "deviceId" to "desktop").toString()))
                        }
                        else -> {}
                    }
                }
            }
        }.apply { start(wait = false) }
        val port = engine.engine.resolvedConnectors().first().port
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = TutorModel(System::currentTimeMillis)
        val client = TutorClient("http://127.0.0.1:$port", "desktop", token, model)
        try {
            client.start(scope)
            withTimeout(10_000) { while (!model.state.value.connected) delay(20) }
            assertTrue(client.ask("t1", "course", "什么是极限", "pack", "cid-1"))
            withTimeoutOrNull(10_000) { while (delivered.none { it.first > 1 }) delay(50) }
            assertEquals(listOf(1 to "cid-1", 2 to "cid-1"), synchronized(delivered) { delivered.toList() }, "断线丢掉的发问没有在重连后补发")
            withTimeout(5_000) {
                while (model.state.value.threads["t1"]?.items?.any { it.key == "l:u1" } != true) delay(20)
            }
            delay(1_500)
            assertEquals(2, delivered.size, "确认之后不能再重复补发")
        } finally { client.close(); engine.stop(0, 500); scope.cancel() }
    }
}
