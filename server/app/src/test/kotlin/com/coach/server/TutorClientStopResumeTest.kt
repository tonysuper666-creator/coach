package com.coach.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.store.*
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Host-only regression for TON-1215: pause only the JVM this test creates.
 * Pauses an isolated JVM on an ephemeral port and records when the shared
 * client reports offline. Does not touch production, Tailscale, or a device.
 */
class TutorClientStopResumeTest {
    @Test fun sigstopOfflineLatencyOnLoopback() = runBlocking {
        val forbidden = setOf(8790, 8791, 18791)
        val root = Files.createTempDirectory("coach-stop-offline-probe-")
        val data = root.resolve("data")
        val log = root.resolve("server.log")
        val token = "probe-token-not-a-secret-32chars-min"
        val tokenFile = root.resolve("token")
        Files.writeString(tokenFile, token)
        val port = ServerSocket(0).use { it.localPort }
        check(port !in forbidden && port >= 1024) { "Refusing port $port" }
        val binary = System.getProperty("coach.serverBinary")
        val builder = ProcessBuilder(
            "python3", "-c",
            "import os,sys;os.setsid();os.execvp('nice',['nice','-n','15',sys.argv[1]])",
            binary,
        )
        builder.environment().putAll(mapOf(
            "COACH_DATA_DIR" to data.toString(),
            "COACH_TOKEN_FILE" to tokenFile.toString(),
            "COACH_DEVICE_ID" to "phone",
            "COACH_PORT" to port.toString(),
            "COACH_TAIL_HOST" to "192.0.2.1",
            "COACH_MANAGE_SERVICES" to "0",
            "COACH_TRAFFIC" to "test",
            "COACH_BRIDGE_URL" to "http://127.0.0.1:1",
            "COACH_SOURCE_LOOKUP" to "false",
            "COACH_LOG_DIR" to root.resolve("logs").toString(),
            "COACH_STATE_DIR" to root.resolve("state").toString(),
        ))
        builder.redirectErrorStream(true)
        builder.redirectOutput(log.toFile())
        val process = builder.start()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClientDatabase.Schema.create(driver)
        val transport = HttpLedgerTransport("http://127.0.0.1:$port", "phone", token)
        val client = SyncEngine(
            ClientDatabase(driver), "phone", transport, listOf("synthetic"),
            { UUID.randomUUID().toString() }, ::sha256,
        )
        val tutorModel = TutorModel(System::currentTimeMillis)
        val tutor = TutorClient("http://127.0.0.1:$port", "phone", token, tutorModel)
        var loop: Job? = null
        var tutorLoop: Job? = null
        var paused = false
        var ledgerOfflineMs = -1L
        var tutorOfflineMs = -1L
        var tutorUpBeforeStop = false
        var helloOk = false
        var reconnectMs = -1L
        var tutorBackMs = -1L
        var probeError: String? = null
        fun signal(name: String) {
            check(ProcessBuilder("/bin/kill", "-$name", "-${process.pid()}").start().waitFor() == 0) { "kill $name failed" }
        }
        fun line(): String = "PROBE scenario=socket-up pauseMs=32000 port=$port " +
            "ledgerOfflineMs=$ledgerOfflineMs tutorUpBeforeStop=$tutorUpBeforeStop tutorOfflineMs=$tutorOfflineMs " +
            "ledgerWithin30s=${ledgerOfflineMs in 0..30_000} tutorWithin15s=${tutorOfflineMs in 0..15_000} " +
            "helloAfterResume=$helloOk reconnectMs=$reconnectMs tutorBackMs=$tutorBackMs epochUnchanged=$helloOk " +
            "error=${probeError ?: "none"}"
        try {
            withTimeout(20_000) {
                while (true) {
                    try {
                        client.syncOnce()
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!process.isAlive) error(Files.readString(log))
                        delay(100)
                    }
                }
            }
            loop = client.start(this)
            tutorLoop = tutor.start(this)
            // Steady sockets: pause only after ledger sync and the tutor banner socket are both up.
            withTimeout(20_000) {
                while (client.connection.value != "online" || !tutorModel.state.value.connected) delay(50)
            }
            tutorUpBeforeStop = tutorModel.state.value.connected
            val revision = client.revision.value
            val epoch = client.epoch
            val started = System.nanoTime()
            signal("STOP")
            paused = true
            val deadline = started + 32_000_000_000L
            while (System.nanoTime() < deadline) {
                val elapsed = (System.nanoTime() - started) / 1_000_000
                if (ledgerOfflineMs < 0 && client.connection.value == "offline") ledgerOfflineMs = elapsed
                if (tutorOfflineMs < 0 && tutorUpBeforeStop && !tutorModel.state.value.connected) tutorOfflineMs = elapsed
                // Stay stopped through multiple reconnect attempts, not just the first timeout.
                if (tutorOfflineMs >= 0) check(!tutorModel.state.value.connected) { "Claimed online while JVM was stopped" }
                delay(50)
            }
            signal("CONT")
            paused = false
            val resumed = System.nanoTime()
            withTimeout(25_000) {
                while (!helloOk) {
                    try {
                        val hello = transport.hello(epoch)
                        check(hello.epoch == epoch) { "epoch changed" }
                        helloOk = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        delay(250)
                    }
                }
                while (client.connection.value != "online" || client.revision.value <= revision) delay(50)
                reconnectMs = (System.nanoTime() - resumed) / 1_000_000
                if (tutorUpBeforeStop) {
                    while (!tutorModel.state.value.connected) delay(50)
                    tutorBackMs = (System.nanoTime() - resumed) / 1_000_000
                }
            }
            assertTrue(tutorUpBeforeStop)
            assertTrue(tutorOfflineMs in 0..15_000, "Tutor offline deadline: ${line()}")
            assertTrue(ledgerOfflineMs in 0..30_000, "Ledger offline deadline: ${line()}")
            assertTrue(helloOk && tutorBackMs >= 0 && reconnectMs >= 0, line())
            delay(1_000)
            assertTrue(tutorModel.state.value.connected, "Old connection overwrote replacement state")
        } catch (e: Throwable) {
            probeError = e.javaClass.simpleName + ": " + (e.message ?: "")
            throw e
        } finally {
            val text = line()
            println(text)
            runCatching {
                val copy = java.nio.file.Path.of("build", "tutor-stop-resume.txt")
                Files.createDirectories(copy.parent)
                Files.writeString(copy, text + "\n")
            }
            if (paused && process.isAlive) runCatching { signal("CONT") }
            tutorLoop?.cancelAndJoin()
            loop?.cancelAndJoin()
            tutor.close()
            transport.close()
            driver.close()
            if (process.isAlive) {
                runCatching { signal("TERM") }
                if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    runCatching { signal("KILL") }
                    process.waitFor()
                }
            }
            val serverLog = runCatching { Files.readString(log) }.getOrDefault("")
            val kept = java.nio.file.Path.of("build", "tutor-stop-resume-server.log")
            runCatching {
                Files.createDirectories(kept.parent)
                Files.writeString(kept, serverLog.takeLast(4000))
            }
            root.toFile().deleteRecursively()
            if (probeError != null) throw AssertionError(line())
        }
    }
}
