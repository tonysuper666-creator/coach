package com.coach.server

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Opt-in for the installed service. Standalone servers and isolated tests own no children. */
internal class ChildServices(private val scope: CoroutineScope, private val env: Map<String, String> = System.getenv()) {
    private val root = Path.of(env.getValue("COACH_SERVICE_ROOT"))
    private val run = Files.createDirectories(Path.of(env.getValue("COACH_RUN_DIR")))
    private val logs = Path.of(env.getValue("COACH_LOG_DIR"))
    private val python = env.getValue("COACH_PYTHON")
    private val bridgePort = (env["BRIDGE_PORT"] ?: "8791").toInt().also {
        require(it == 8791) { "The managed bridge must use the isolated Coach port 8791" }
    }
    private val jobs = mutableListOf<Job>()

    fun start() {
        jobs += supervise("bridge", listOf(root.resolve("tools/bridge/run.sh").toString()), false)
        jobs += supervise("mathsvc", listOf(env.getValue("COACH_NODE"), root.resolve("tools/mathsvc/src/server.mjs").toString()), true)
    }

    suspend fun stop() { jobs.forEach { it.cancel() }; jobs.joinAll() }

    private fun supervise(name: String, command: List<String>, jsonLines: Boolean) = scope.launch(Dispatchers.IO) {
        var backoff = 1000L
        val state = run.resolve("$name.json")
        val pidFile = run.resolve("$name.pid")
        val log = ServiceLog(logs, name)
        try {
            while (isActive) {
                var process: Process? = null
                val pumps = mutableListOf<Thread>()
                var healthySince = 0L
                try {
                    // Refuse an occupied bridge port rather than adopting or killing its owner.
                    if (!jsonLines) java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress("127.0.0.1", bridgePort)) }
                    Files.deleteIfExists(pidFile)
                    process = ProcessBuilder(listOf(python, root.resolve("tools/dev/service-child.py").toString(), pidFile.toString()) + command)
                        .directory(root.toFile()).start()
                    val child = process
                    val replies = LinkedBlockingQueue<String>(16)
                    pumps += thread(name = "$name-stderr", isDaemon = true) { child.errorStream.use { it.copyTo(log) } }
                    pumps += thread(name = "$name-stdout", isDaemon = true) {
                        child.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                            if (jsonLines) replies.offer(line) else log.write((line + "\n").toByteArray())
                        } }
                    }
                    fun status(healthy: Boolean) {
                        val json = buildJsonObject {
                            put("ownerPid", ProcessHandle.current().pid()); put("guardPid", child.pid())
                            put("pid", runCatching { Files.readString(pidFile).trim().toLong() }.getOrDefault(0))
                            put("healthy", healthy); put("checkedAt", System.currentTimeMillis())
                        }
                        val temp = run.resolve("$name.json.tmp")
                        Files.writeString(temp, json.toString())
                        Files.move(temp, state, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    }
                    status(false)
                    val startup = System.nanoTime()
                    while (isActive && child.isAlive) {
                        val healthy = runCatching {
                            if (jsonLines) {
                                val id = "health-${System.nanoTime()}"
                                child.outputStream.write("{\"id\":\"$id\",\"type\":\"ping\"}\n".toByteArray())
                                child.outputStream.flush()
                                val reply = replies.poll(3, TimeUnit.SECONDS) ?: error("mathsvc ping timeout")
                                val pong = Json.parseToJsonElement(reply).jsonObject
                                pong["type"]?.jsonPrimitive?.content == "pong" && pong["id"]?.jsonPrimitive?.content == id
                            } else {
                                val connection = URI("http://127.0.0.1:$bridgePort/health?scope=process").toURL().openConnection() as HttpURLConnection
                                try {
                                    connection.connectTimeout = 2000; connection.readTimeout = 3000
                                    connection.responseCode == 200 && connection.inputStream.use {
                                        Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject["ok"]?.jsonPrimitive?.boolean == true
                                    }
                                } finally { connection.disconnect() }
                            }
                        }.getOrDefault(false)
                        status(healthy)
                        if (healthy) {
                            if (healthySince == 0L) {
                                healthySince = System.nanoTime()
                                println("COACH $name ready pid=${Files.readString(pidFile).trim()}")
                            }
                            if (System.nanoTime() - healthySince >= 60_000_000_000) backoff = 1000
                        } else if (healthySince != 0L || System.nanoTime() - startup > 20_000_000_000) {
                            error("$name health check failed")
                        }
                        // Observe exits promptly, health-check healthy children every ten seconds.
                        val next = System.nanoTime() + if (healthy) 10_000_000_000 else 250_000_000
                        while (isActive && child.isAlive && System.nanoTime() < next) delay(100)
                    }
                    if (isActive) error("$name exited")
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    println("COACH $name unavailable; retryMs=$backoff ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    // The guard kills service/CLI process groups and also handles JVM SIGKILL.
                    process?.let { child ->
                        child.destroy()
                        if (!child.waitFor(8, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor() }
                    }
                    pumps.forEach { it.join(1000) }
                    Files.deleteIfExists(state); Files.deleteIfExists(pidFile)
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
        } finally { log.close(); Files.deleteIfExists(state) }
    }
}
