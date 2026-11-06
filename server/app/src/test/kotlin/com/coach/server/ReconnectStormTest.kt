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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * TON-1241: 桌面端 06:43–06:44 两分钟断线重连 235 次。隔离端口、临时数据目录，不碰生产端口。
 * 修前：连上即被踢的连接每 ~250ms 重连一次；修后：等待按指数退避拉长。
 */
class ReconnectStormTest {
    private val token = "reconnect-storm-test-token-32-characters"

    private fun observe(window: Long, block: suspend () -> Unit) = runBlocking { withTimeoutOrNull(window) { block() } }

    private fun gaps(starts: List<Long>) = starts.zipWithNext { a, b -> (b - a) / 1_000_000 }

    /** 生产库里账本已超过 1 MiB：服务端每次连上都把整份账本作为一帧推给导师 socket。 */
    @Test fun oversizedLedgerCatchUpKeepsTutorConnected() = runBlocking {
        val root = Files.createTempDirectory("coach-storm-")
        val store = LedgerStore(root.resolve("server"))
        store.enroll("desktop", token)
        repeat(300) { store.append("course", "note_saved", buildJsonObject { put("noteId", "n$it"); put("text", "长笔记".repeat(1_200)) }) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actor = LedgerActor(scope, store)
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) { ledgerRoutes(actor, MutableSharedFlow()) }.apply { start(wait = false) }
        val port = engine.engine.resolvedConnectors().first().port
        check(port !in setOf(8790, 8791, 18791))
        val model = TutorModel(System::currentTimeMillis)
        val client = TutorClient("http://127.0.0.1:$port", "desktop", token, model)
        val flips = AtomicInteger()
        val watcher = scope.launch { model.state.map { it.connected }.distinctUntilChanged().collect { if (!it) flips.incrementAndGet() } }
        try {
            client.start(scope)
            withTimeout(15_000) { while (!model.state.value.connected) delay(20) }
            val before = flips.get()
            val started = System.nanoTime()
            observe(6_000) { while (true) delay(50) }
            val drops = flips.get() - before
            val perSecond = drops * 1_000.0 / ((System.nanoTime() - started) / 1_000_000)
            println("STORM oversizedLedger drops=$drops perSecond=${"%.1f".format(perSecond)}")
            assertEquals(0, drops, "导师连接被 >1 MiB 的账本帧反复踢断：6 秒内掉线 $drops 次")
            assertTrue(model.state.value.connected)
        } finally { client.close(); watcher.cancel(); engine.stop(0, 500); actor.close(); scope.cancel() }
    }

    /** 服务端回 pong 后立刻关连接：修前每次都把等待重置到 250ms，修后按退避拉长。 */
    @Test fun tutorReconnectsBackOffWhenServerClosesRightAfterHandshake() = runBlocking {
        val starts = Collections.synchronizedList(mutableListOf<Long>())
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                webSocketRaw("/v1/ws") {
                    starts += System.nanoTime()
                    for (frame in incoming) if (frame is Frame.Ping) {
                        send(Frame.Pong(frame.data))
                        delay(100)
                        break
                    }
                }
            }
        }.apply { start(wait = false) }
        val port = engine.engine.resolvedConnectors().first().port
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = TutorClient("http://127.0.0.1:$port", "desktop", token, TutorModel(System::currentTimeMillis))
        try {
            client.start(scope)
            observe(8_000) { while (true) delay(50) }
            val seen = synchronized(starts) { starts.toList() }
            println("STORM tutorFastClose connections=${seen.size} gapsMs=${gaps(seen)}")
            assertTrue(seen.size in 3..7, "8 秒内重连 ${seen.size} 次，间隔 ${gaps(seen)} ms")
            assertTrue(gaps(seen).zipWithNext().all { (a, b) -> b >= a * 0.9 }, "间隔应逐次拉长：${gaps(seen)}")
        } finally { client.close(); engine.stop(0, 500); scope.cancel() }
    }

    /** 账本 socket 被服务端正常关闭（如 TRY_AGAIN_LATER）时，同步引擎同样不能每 250ms 重连。 */
    @Test fun ledgerWatchBacksOffWhenSocketEndsNormally() = runBlocking {
        val starts = Collections.synchronizedList(mutableListOf<Long>())
        val transport = object : LedgerTransport {
            override suspend fun hello(epoch: String?): Hello = error("offline")
            override suspend fun snapshot(course: String): Snapshot = error("offline")
            override suspend fun events(course: String, after: Long): EventsPage = error("offline")
            override suspend fun command(command: WireCommand): Receipt = error("offline")
            override suspend fun watch(onLedger: suspend () -> Unit) { starts += System.nanoTime() }
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val engine = SyncEngine(ClientDatabase(driver), "desktop", transport, listOf("course"), { UUID.randomUUID().toString() }, ::sha256)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            engine.start(scope)
            observe(6_000) { while (true) delay(50) }
            val seen = synchronized(starts) { starts.toList() }
            println("STORM ledgerWatch connections=${seen.size} gapsMs=${gaps(seen)}")
            assertTrue(seen.size in 2..7, "6 秒内账本 socket 重连 ${seen.size} 次，间隔 ${gaps(seen)} ms")
        } finally { scope.cancel(); driver.close() }
    }
}
