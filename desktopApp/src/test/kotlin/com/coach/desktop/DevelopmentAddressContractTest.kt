package com.coach.desktop

import com.coach.client.PairedCredentials
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

/**
 * Development runs must stay on the normalized COACH_URL.
 * Synthetic loopback only: never binds or dials a production port.
 */
class DevelopmentAddressContractTest {
    @Test fun developmentPairRejectsForeignAddressBeforeNetworkOrSave() {
        val root = Files.createTempDirectory("coach-dev-pair-").toRealPath()
        CountingServer().use { foreign ->
            val isolated = freePort()
            val settings = developmentSettings(root, "http://127.0.0.1:$isolated")
            val store = DesktopCredentials(settings.clientDirectory)
            val pairing = DesktopPairing(settings, store)
            val rejected = assertFailsWith<IllegalArgumentException> {
                runBlocking { pairing.pair(foreign.url, "123456", "synthetic") }
            }
            assertTrue(rejected.message.orEmpty().contains("isolated COACH_URL"), rejected.message)
            assertEquals(0, foreign.hits.get(), "pair() reached the typed server before refusing")
            assertFalse(Files.exists(store.file), "credentials were saved for a foreign address")
            // Same guard, production-shaped address. Reached only after the foreign server saw nothing.
            val production = assertFailsWith<IllegalArgumentException> {
                runBlocking { pairing.pair("http://127.0.0.1:18791", "123456", "synthetic") }
            }
            assertTrue(production.message.orEmpty().contains("isolated COACH_URL"), production.message)
            assertEquals(0, foreign.hits.get())
            assertFalse(Files.exists(store.file))
        }
        root.toFile().deleteRecursively()
    }

    @Test fun developmentSessionRejectsMismatchedStoredIdentity() {
        val root = Files.createTempDirectory("coach-dev-session-").toRealPath()
        try {
            val isolated = freePort()
            val other = freePort()
            val settings = developmentSettings(root, "http://127.0.0.1:$isolated")
            val opened = runCatching {
                DesktopLearningSession(
                    settings,
                    DesktopIdentity("http://127.0.0.1:$other", syntheticCredentials()),
                    "synthetic",
                )
            }
            opened.getOrNull()?.close()
            val error = opened.exceptionOrNull()
            assertTrue(error is IllegalArgumentException, "expected address refusal, got $error")
            assertTrue(error.message.orEmpty().contains("isolated COACH_URL"), error.message)
            assertFalse(Files.exists(settings.clientDirectory.resolve("ledger.sqlite")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun equivalentAddressStillPairsAndMatchingIdentityStarts() {
        val root = Files.createTempDirectory("coach-dev-equivalent-").toRealPath()
        try {
            SuccessServer().use { server ->
                val settings = developmentSettings(root, "http://127.0.0.1:${server.port}/")
                val store = DesktopCredentials(settings.clientDirectory)
                val identity = runBlocking {
                    DesktopPairing(settings, store).pair("HTTP://127.0.0.1:${server.port}", "123456", "synthetic")
                }
                assertEquals("http://127.0.0.1:${server.port}", identity.serverUrl)
                assertEquals(settings.serverUrl(), identity.serverUrl)
                assertTrue(server.paths.contains("/v1/pair"))
                assertTrue(Files.exists(store.file))
                DesktopLearningSession(
                    settings,
                    DesktopIdentity("http://127.0.0.1:${server.port}/", identity.credentials),
                    "synthetic",
                ).use { }
                assertTrue(Files.exists(settings.clientDirectory.resolve("ledger.sqlite")))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun packagedAppStillPairsToATypedAddressAndKeepsAnotherIdentity() {
        val root = Files.createTempDirectory("coach-dev-packaged-").toRealPath()
        try {
            SuccessServer().use { server ->
                val settings = DesktopConnectionSettings(root, { null }, mac = false, property = { name ->
                    if (name == "jpackage.app-path") "/synthetic/Coach.app" else null
                })
                val store = DesktopCredentials(settings.clientDirectory)
                val typed = "http://127.0.0.1:${server.port}"
                val identity = runBlocking {
                    DesktopPairing(settings, store).pair(typed, "123456", "synthetic")
                }
                assertEquals(typed, identity.serverUrl)
                assertTrue(Files.exists(store.file))
                val other = freePort()
                DesktopLearningSession(settings, DesktopIdentity("http://127.0.0.1:$other", identity.credentials), "synthetic").use { }
                assertTrue(Files.exists(settings.clientDirectory.resolve("ledger.sqlite")))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun explicitAgentRunStillRejectsMismatchedIdentity() {
        val root = Files.createTempDirectory("coach-dev-agent-").toRealPath()
        try {
            val isolated = freePort()
            val other = freePort()
            val token = root.resolve("token").also { Files.writeString(it, "synthetic-token") }
            val env = mapOf(
                "COACH_URL" to "http://127.0.0.1:$isolated",
                "COACH_TOKEN_FILE" to token.toString(),
                "COACH_CLIENT_DIR" to root.resolve("client").toString(),
                "COACH_ACCEPTANCE_REPLAY" to "1",
                "COACH_AGENT_RUN_ID" to "synthetic_run",
            )
            val settings = DesktopConnectionSettings(root, env::get, mac = false)
            val opened = runCatching {
                DesktopLearningSession(settings, DesktopIdentity("http://127.0.0.1:$other", syntheticCredentials()), "synthetic")
            }
            opened.getOrNull()?.close()
            assertTrue(opened.exceptionOrNull() is IllegalArgumentException)
            assertFalse(Files.exists(settings.clientDirectory.resolve("ledger.sqlite")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun developmentSettings(root: Path, url: String): DesktopConnectionSettings {
        val token = root.resolve("token").also { Files.writeString(it, "synthetic-token") }
        val env = mapOf(
            "COACH_URL" to url,
            "COACH_TOKEN_FILE" to token.toString(),
            "COACH_CLIENT_DIR" to root.resolve("client").toString(),
        )
        return DesktopConnectionSettings(root, env::get, mac = false)
    }

    private fun syntheticCredentials() = PairedCredentials("isolated-desktop", "a".repeat(43))

    private fun freePort(): Int {
        val port = ServerSocket(0).use { it.localPort }
        check(port !in productionPorts && port != 3790 && port != 19540) { "Refusing reserved port $port" }
        return port
    }

    private class CountingServer : AutoCloseable {
        val hits = AtomicInteger()
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url: String

        init {
            server.createContext("/") { exchange ->
                hits.incrementAndGet()
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            }
            server.start()
            val port = server.address.port
            check(port !in productionPorts && port != 3790 && port != 19540) { "Refusing reserved port $port" }
            url = "http://127.0.0.1:$port"
        }

        override fun close() {
            server.stop(0)
        }
    }

    private class SuccessServer : AutoCloseable {
        val paths = mutableListOf<String>()
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int

        init {
            server.createContext("/") { exchange -> respond(exchange) }
            server.start()
            port = server.address.port
            check(port !in productionPorts && port != 3790 && port != 19540) { "Refusing reserved port $port" }
        }

        private fun respond(exchange: HttpExchange) {
            paths += exchange.requestURI.path
            exchange.requestBody.use { it.readBytes() }
            val body = when (exchange.requestURI.path) {
                "/v1/pair" -> """{"deviceId":"isolated-desktop","token":"${"a".repeat(43)}"}"""
                "/v1/hello" -> """{"epoch":"synthetic","lastSeq":0,"backedUpThroughSeq":0,"lastSeqBefore":0}"""
                else -> {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                    return
                }
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        override fun close() {
            server.stop(0)
        }
    }
}
