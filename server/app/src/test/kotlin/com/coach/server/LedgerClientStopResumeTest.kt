package com.coach.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.HttpLedgerTransport
import com.coach.client.SyncEngine
import com.coach.client.db.ClientDatabase
import com.coach.store.sha256
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Match the phone smoke: ledger alone, without a second tutor socket. */
class LedgerClientStopResumeTest {
    @Test fun ledgerAloneSurvivesStoppedServer(): Unit = runBlocking {
        val root = Files.createTempDirectory("coach-ledger-pause-")
        val port = ServerSocket(0).use { it.localPort }
        check(port !in setOf(8790, 8791, 18791))
        val token = "isolated-ledger-probe-" + UUID.randomUUID()
        val tokenFile = root.resolve("token")
        Files.writeString(tokenFile, token)
        val builder = ProcessBuilder("python3", "-c",
            "import os,sys;os.setsid();os.execv(sys.argv[1],[sys.argv[1]])",
            System.getProperty("coach.serverBinary"))
        builder.environment().keys.removeIf { it.startsWith("COACH_") || it.startsWith("BRIDGE_") }
        builder.environment().putAll(mapOf(
            "COACH_DATA_DIR" to root.resolve("data").toString(),
            "COACH_TOKEN_FILE" to tokenFile.toString(), "COACH_DEVICE_ID" to "desktop",
            "COACH_PORT" to port.toString(), "COACH_TAIL_HOST" to "192.0.2.1",
            "COACH_MANAGE_SERVICES" to "0", "COACH_TRAFFIC" to "test",
            "COACH_BRIDGE_URL" to "http://127.0.0.1:1", "COACH_SOURCE_LOOKUP" to "false",
            "COACH_LOG_DIR" to root.resolve("logs").toString(),
            "COACH_STATE_DIR" to root.resolve("state").toString(),
        ))
        val process = builder.redirectErrorStream(true).redirectOutput(root.resolve("server.log").toFile()).start()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val transport = HttpLedgerTransport("http://127.0.0.1:$port", "desktop", token)
        val engine = SyncEngine(ClientDatabase(driver), "desktop", transport, listOf("synthetic"),
            { UUID.randomUUID().toString() }, ::sha256)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ended = AtomicReference("running")
        var loop: Job? = null
        var paused = false
        fun signal(name: String) {
            check(ProcessBuilder("/bin/kill", "-$name", "-${process.pid()}").start().waitFor() == 0)
        }
        try {
            withTimeout(30_000) {
                while (true) {
                    try { engine.syncOnce(); break }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { check(process.isAlive); delay(100) }
                }
            }
            loop = engine.start(scope)
            loop.invokeOnCompletion { ended.set(it?.javaClass?.simpleName ?: "completed") }
            val epoch = engine.epoch
            val revision = engine.revision.value
            signal("STOP")
            paused = true
            val start = System.nanoTime()
            withTimeout(30_000) {
                while (engine.connection.value != "offline") {
                    assertTrue(loop.isActive, "Ledger loop exited: ${ended.get()}")
                    delay(50)
                }
            }
            val offlineMs = (System.nanoTime() - start) / 1_000_000
            println("LEDGER_ONLY offlineMs=$offlineMs loopActive=${loop.isActive}")
            signal("CONT")
            paused = false
            withTimeout(25_000) {
                while (engine.connection.value != "online" || engine.revision.value <= revision) delay(50)
                assertEquals(epoch, transport.hello(epoch).epoch)
            }
        } finally {
            println("LEDGER_ONLY finalState=${engine.connection.value} loopActive=${loop?.isActive} completion=${ended.get()}")
            if (paused && process.isAlive) runCatching { signal("CONT") }
            scope.cancel()
            withTimeoutOrNull(5_000) { loop?.join() }
            transport.close()
            driver.close()
            if (process.isAlive) {
                runCatching { signal("TERM") }
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    runCatching { signal("KILL") }
                    process.waitFor()
                }
            }
            root.toFile().deleteRecursively()
        }
    }
}
