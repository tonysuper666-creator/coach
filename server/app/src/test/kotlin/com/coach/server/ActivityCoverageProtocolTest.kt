package com.coach.server

import com.coach.core.LedgerActor
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Runs the Python consumer against disposable JVMs using the real HTTP/WS routes and journal. */
class ActivityCoverageProtocolTest {
    @Test fun isolatedConsumerAndShutdownProtocol() {
        val result = ProcessBuilder("python3", "-m", "unittest", "tools.dev.test_activity_coverage", "-v")
            .directory(java.io.File(System.getProperty("coach.checkout")))
            .also {
                it.environment()["COACH_COVERAGE_CLASSPATH"] = System.getProperty("coach.coverageTestClasspath")
                it.environment()["COACH_COVERAGE_BINARY"] = System.getProperty("coach.serverBinary")
                it.environment()["COACH_COVERAGE_JAVA"] = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            }.inheritIO().start()
        try {
            assertTrue(result.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "isolated coverage tests timed out")
            assertEquals(0, result.exitValue(), "Python consumer rejected the protocol tests")
        } finally { if (result.isAlive) { result.destroyForcibly(); result.waitFor() } }
    }
}

/** Test-only clock/fault injection; production has no shortened observation option. */
fun main(args: Array<String>) = runBlocking(Dispatchers.IO) {
    val root = Path.of(args.single())
    val nanos = AtomicLong(0)
    val origin = Instant.now().minusSeconds(901)
    val day = AtomicLong(0)
    val fail = AtomicBoolean(false)
    val quiet = AtomicBoolean(true)
    val coverage = ActivityCoverage(root.resolve("logs"),
        day = { LocalDate.of(2026, 11, 1).plusDays(day.get()) },
        persist = { path, bytes ->
            if (fail.get()) error("injected disk failure")
            FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use {
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
        }, instant = { origin.plusNanos(nanos.get()) }, nano = nanos::get)
    coverage.workQuiet = quiet::get
    val observer = UsageObservation(root.resolve("logs"), root.resolve("state"), coverage)
    val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val actor = LedgerActor(scope, LedgerStore(root.resolve("data")).also {
        it.enroll("fixture", "fixture-token-never-production-32-characters")
    })
    val engine = embeddedServer(Netty, host="127.0.0.1", port=0) {
        ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity=8), observation=observer)
    }.apply { start(false) }
    coverage.startControl(root.resolve("control.sock"))
    fun reply(value: JsonObject) { println(value); System.out.flush() }
    reply(buildJsonObject { put("port",engine.engine.resolvedConnectors().single().port) })
    try {
        while (true) {
            val line = readlnOrNull() ?: break
            val request = Json.parseToJsonElement(line).jsonObject
            when (request.getValue("command").jsonPrimitive.content) {
                "advance" -> nanos.addAndGet(request.getValue("ns").jsonPrimitive.long)
                "rotate" -> { day.incrementAndGet(); coverage.begin("http")?.let(coverage::end) }
                "fail" -> fail.set(true)
                "quiet" -> quiet.set(request.getValue("value").jsonPrimitive.boolean)
                "stop" -> break
                "crash" -> Runtime.getRuntime().halt(0)
                "snapshot" -> {}
                else -> error("unsupported fixture command")
            }
            reply(coverage.snapshot())
        }
    } finally {
        engine.stopSuspend(0,1000)
        observer.close()
        actor.close();scope.cancel()
    }
}
