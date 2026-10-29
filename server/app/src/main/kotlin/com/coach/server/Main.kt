package com.coach.server

import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.util.Properties
import java.io.File

private fun Application.hello() {
    install(WebSockets)
    routing {
        get("/v1/hello") { call.respondText("Hello from Coach") }
        webSocket("/v1/hello") {
            send(Frame.Text("Hello from Coach"))
        }
    }
}

fun main(): Unit = runBlocking {
    // This process supervises two engines, so one hook owns shutdown ordering.
    System.setProperty("io.ktor.server.engine.ShutdownHook", "false")
    val port = System.getenv("COACH_PORT")?.toInt() ?: 18791
    val localConfig = Properties().apply {
        val file = File(System.getProperty("user.home"), "Coach/config/local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    val tailHost = System.getenv("COACH_TAIL_HOST") ?: localConfig.getProperty("coach.tailHost")
    val local = embeddedServer(Netty, host = "127.0.0.1", port = port, module = Application::hello)
    local.engineConfig.apply { connectionGroupSize = 1; workerGroupSize = 1; callGroupSize = 2 }
    local.start(wait = false)
    println("COACH loopback ready http://127.0.0.1:$port/v1/hello")
    val supervisor = launch {
        var backoff = 1000L
        while (isActive) {
            if (tailHost.isNullOrBlank()) {
                println("COACH tailnet not configured; loopback remains available")
                delay(60_000)
                continue
            }
            require(!java.net.InetAddress.getByName(tailHost).isAnyLocalAddress) { "Wildcard listen addresses are forbidden" }
            // Each engine has its own supervised lifetime: a bind failure must not
            // cancel the loopback listener or the retry loop.
            val engineJob = SupervisorJob(coroutineContext[Job])
            val engineErrors = CoroutineExceptionHandler { _, error ->
                println("COACH tailnet engine: ${error.javaClass.simpleName}: ${error.message}")
            }
            val tail = embeddedServer(Netty, host = tailHost, port = port,
                parentCoroutineContext = engineJob + engineErrors, module = Application::hello)
            tail.engineConfig.apply { connectionGroupSize = 1; workerGroupSize = 1; callGroupSize = 2 }
            try {
                tail.start(wait = false)
                println("COACH tailnet ready http://$tailHost:$port/v1/hello")
                backoff = 1000L
                // A disconnected/replaced interface is detected and re-bound on its return.
                while (isActive && java.net.NetworkInterface.getNetworkInterfaces().toList()
                        .any { it.inetAddresses.toList().any { address -> address.hostAddress == tailHost } }) {
                    delay(2000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("COACH tailnet unavailable host=$tailHost retryMs=$backoff ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                withContext(NonCancellable) {
                    engineJob.cancelAndJoin()
                    tail.stopSuspend(100, 500)
                }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000)
        }
    }
    val shutdown = Thread {
        runBlocking { supervisor.cancelAndJoin() }
        local.stop(100, 1000)
    }
    Runtime.getRuntime().addShutdownHook(shutdown)
    try { awaitCancellation() } finally {
        supervisor.cancelAndJoin()
        local.stop(100, 1000)
    }
}
