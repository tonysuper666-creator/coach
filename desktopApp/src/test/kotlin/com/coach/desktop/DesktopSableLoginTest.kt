package com.coach.desktop

import com.sun.net.httpserver.HttpServer
import com.coach.client.PairedCredentials
import com.coach.domain.LedgerJson
import com.coach.domain.str
import kotlinx.serialization.json.jsonObject
import com.coach.core.LedgerActor
import com.coach.store.LedgerStore
import com.coach.server.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class DesktopSableLoginTest {
    @Test fun deviceGrantOpensLinkThenExchangesForSeparateFriendCredential() = runBlocking {
        val fake = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val origin = "http://127.0.0.1:${fake.address.port}"
        val approved = AtomicBoolean()
        val polls = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val upstream = "synthetic-sable-grant-for-contract-test"
        val secondGrant = "synthetic-sable-other-grant"
        val revoked = AtomicBoolean()
        val offline = AtomicBoolean()
        val browser = java.net.http.HttpClient.newHttpClient()
        suspend fun send(request: HttpRequest.Builder) = withContext(Dispatchers.IO) {
            browser.send(request.timeout(java.time.Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
        }
        fake.createContext("/") { request ->
            val form = request.requestBody.bufferedReader().readText()
            val bearer = request.requestHeaders.getFirst("Authorization")
            val reply = when (request.requestURI.path) {
                "/api/device/code" -> if (form == "client_id=learningcoach" && bearer == null)
                    200 to """{"device_code":"synthetic","user_code":"BCDF-GHJK","expires_in":60,"interval":1,"verification_uri":"$origin/link","verification_uri_complete":"$origin/link?code=BCDF-GHJK"}"""
                    else 400 to "{}"
                "/link" -> { approved.set(request.requestURI.query == "code=BCDF-GHJK"); 200 to "{}" }
                "/api/device/token" -> {
                    polls.add(System.nanoTime())
                    when {
                        !approved.get() || "device_code=synthetic" !in form -> 400 to """{"error":"access_denied"}"""
                        polls.size == 1 -> 400 to """{"error":"authorization_pending"}"""
                        polls.size == 2 -> 400 to """{"error":"slow_down"}"""
                        else -> 200 to """{"access_token":"$upstream"}"""
                    }
                }
                "/api/coach/whoami" -> if (offline.get()) 503 to "{}" else if (bearer == "Bearer $secondGrant" || (bearer == "Bearer $upstream" && !revoked.get())) 200 to """{"sub":"subject","username":"demo","display_name":"Demo","email":"reader@example.org"}""" else 401 to "{}"
                else -> 404 to "{}"
            }
            val bytes = reply.second.toByteArray()
            request.sendResponseHeaders(reply.first, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
        }
        fake.start()
        val root = Files.createTempDirectory("coach-sable-").toRealPath()
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(root.resolve("data"))
        val actor = LedgerActor(jobs, store)
        val clock = java.util.concurrent.atomic.AtomicLong(1000)
        val friends = FriendAccess(root.resolve("friends"), root.resolve("state"), root.resolve("limits.json"),
            SableSessions(actor, root.resolve("keys"), URI(origin), clock::get))
        val listener = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), friends = friends, publicOnly = true)
        }.apply { start(false) }
        try {
            val base = "http://127.0.0.1:${listener.engine.resolvedConnectors().single().port}"
            val settings = DesktopConnectionSettings(root, { key -> base.takeIf { key == "COACH_FRIEND_URL" } },
                mac = false, property = { if (it == "jpackage.app-path") "/synthetic/Coach.exe" else null })
            val pairing = DesktopPairing(settings)
            val identity = pairing.sable({ uri, code ->
                assertEquals("BCDF-GHJK", code)
                send(HttpRequest.newBuilder(URI(uri)).GET())
            }, origin)
            assertEquals(3, polls.size)
            assertTrue(polls[2] - polls[1] >= 5_950_000_000L, "slow_down adds five seconds to the interval")
            assertEquals("friend", identity.credentials.role)
            assertTrue(upstream != identity.credentials.token)
            val saved = assertNotNull(DesktopPairing(settings).load())
            assertEquals(identity.credentials.deviceId, saved.credentials.deviceId)
            assertFalse(Files.readString(DesktopCredentials(settings.clientDirectory).file).contains(upstream))
            assertTrue(pairing.stillAuthorized(saved, startup = true))
            val issued = send(HttpRequest.newBuilder(URI("$base/v1/public/sable"))
                .header("Authorization", "Bearer $secondGrant").POST(HttpRequest.BodyPublishers.noBody()))
            assertEquals(200, issued.statusCode())
            val body = LedgerJson.parseToJsonElement(issued.body()).jsonObject
            val second = PairedCredentials(body.str("deviceId"), body.str("token"), body.str("role"))
            assertEquals(actor.execute { friendName(saved.credentials.deviceId) }, actor.execute { friendName(second.deviceId) })
            suspend fun status(credential: PairedCredentials = saved.credentials) = send(HttpRequest.newBuilder(URI("$base/v1/public/status"))
                .header("X-Coach-Device", credential.deviceId)
                .header("Authorization", "Bearer ${credential.token}").GET()).statusCode()
            val settingsClient = com.coach.client.PairingClient(base)
            try {
                val info = settingsClient.account(saved.credentials)
                assertEquals("Demo", info.str("nickname"))
                assertEquals("r***@example.org", info.str("maskedEmail"))
            } finally { settingsClient.close() }
            val thirdGrant = send(HttpRequest.newBuilder(URI("$base/v1/public/sable"))
                .header("Authorization", "Bearer $secondGrant").POST(HttpRequest.BodyPublishers.noBody()))
            val thirdBody = LedgerJson.parseToJsonElement(thirdGrant.body()).jsonObject
            val third = PairedCredentials(thirdBody.str("deviceId"), thirdBody.str("token"), thirdBody.str("role"))
            val separateStore = DesktopCredentials(root.resolve("separate-client"))
            separateStore.save(base, third)
            DesktopPairing(settings, separateStore).logout(DesktopIdentity(base, third))
            assertNull(separateStore.load(base))
            assertEquals(401, status(third))
            assertEquals(200, status(second))
            offline.set(true)
            clock.addAndGet(10 * 60_000)
            assertEquals(200, status())
            clock.addAndGet(5 * 60_000)
            assertEquals(503, status())
            offline.set(false)
            assertEquals(200, status())
            val restarted = SableSessions(actor, root.resolve("keys"), URI(origin))
            offline.set(true)
            assertEquals(503, restarted.check(saved.credentials.deviceId, startup = true).value)
            offline.set(false)
            assertEquals(200, restarted.check(saved.credentials.deviceId, startup = true).value)
            revoked.set(true)
            assertFalse(pairing.stillAuthorized(saved, startup = true))
            assertNull(pairing.load())
            assertEquals(200, status(second))
            assertTrue(actor.execute { disableSable("demo") })
            assertEquals(401, status(second))
        } finally {
            listener.stopSuspend(0, 500)
            actor.close()
            jobs.cancel()
            fake.stop(0)
            root.toFile().deleteRecursively()
        }
    }
}
