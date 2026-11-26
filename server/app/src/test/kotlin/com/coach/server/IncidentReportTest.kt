package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.IncidentRedactor
import com.coach.domain.LedgerJson
import com.coach.domain.json
import com.coach.domain.str
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncidentReportTest {
    private class Harness(val maxBytes: Int = 256 * 1024, val maxPerHour: Int = 10) : AutoCloseable {
        val root = Files.createTempDirectory("coach-incidents-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val admin = "isolated-desktop-token-32-characters"
        val actor = LedgerActor(scope, LedgerStore(root.resolve("ledger")).also { it.enroll("desktop", admin) })
        val inbox = root.resolve("incident-reports")
        val store = IncidentReportStore(inbox, maxPerHour)
        val privateServer = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8))
            incidentReportRoutes(inbox, maxBytes = maxBytes, maxPerHour = maxPerHour)
        }.apply { start(false) }
        val publicServer = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 8), publicOnly = true)
            incidentReportRoutes(inbox, maxBytes = maxBytes, maxPerHour = maxPerHour)
        }.apply { start(false) }
        val client = HttpClient.newHttpClient()

        fun port(public: Boolean) = runBlocking {
            (if (public) publicServer else privateServer).engine.resolvedConnectors().single().port
        }

        fun post(body: String, device: String? = "desktop", token: String? = "Bearer $admin", public: Boolean = false): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${port(public)}/v1/incidents"))
                .timeout(java.time.Duration.ofSeconds(10))
            if (device != null) builder.header("X-Coach-Device", device)
            if (token != null) builder.header("Authorization", token)
            return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        }

        override fun close() {
            runBlocking {
                privateServer.stopSuspend(0, 500)
                publicServer.stopSuspend(0, 500)
                actor.close()
            }
            scope.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun loginIsRequiredAndTheStoredRecordDropsSecrets() = Harness().use { harness ->
        assertEquals(401, harness.post("kind: crash", device = null, token = null).statusCode())
        assertEquals(400, harness.post("   ").statusCode())
        assertEquals(204, harness.post(IncidentRedactor.syntheticBlob()).statusCode())
        val stored = harness.store.storedText("desktop")
        for (needle in IncidentRedactor.needles()) assertFalse(stored.contains(needle), needle)
        assertTrue(stored.contains("[redacted]"))
    }

    @Test fun oversizedBodyIsRefusedAndNotStored() = Harness(maxBytes = 40).use { harness ->
        assertEquals(413, harness.post("z".repeat(80)).statusCode())
        assertEquals("", harness.store.storedText("desktop"))
    }

    @Test fun hourlyCapRefusesTheNextRecord() = Harness(maxPerHour = 2).use { harness ->
        assertEquals(204, harness.post("kind: crash\n").statusCode())
        assertEquals(204, harness.post("kind: freeze\n").statusCode())
        assertEquals(429, harness.post("kind: import_failure\n").statusCode())
        val oldest = Files.list(harness.inbox.resolve("desktop")).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".txt") }.min(compareBy { Files.getLastModifiedTime(it) }).get()
        }
        Files.setLastModifiedTime(oldest, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000 - 5_000))
        assertEquals(204, harness.post("kind: sync_failure\n").statusCode())
    }

    @Test fun aSignedInFriendCanLeaveARecord() = Harness().use { harness ->
        val codeResponse = run {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${harness.port(false)}/v1/pairing-code"))
                .header("X-Coach-Device", "desktop")
                .header("Authorization", "Bearer ${harness.admin}")
                .POST(HttpRequest.BodyPublishers.ofString(json("friend" to "synthetic").toString()))
                .build()
            harness.client.send(request, HttpResponse.BodyHandlers.ofString())
        }
        assertEquals(200, codeResponse.statusCode())
        val code = LedgerJson.parseToJsonElement(codeResponse.body()).jsonObject.str("code")
        val pair = HttpRequest.newBuilder(URI("http://127.0.0.1:${harness.port(true)}/v1/pair"))
            .POST(HttpRequest.BodyPublishers.ofString(json("code" to code, "name" to "synthetic phone").toString()))
            .build()
        val paired = harness.client.send(pair, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, paired.statusCode())
        val credential = LedgerJson.parseToJsonElement(paired.body()).jsonObject
        val response = harness.post(
            "kind: crash\n",
            device = credential.str("deviceId"),
            token = "Bearer ${credential.str("token")}",
            public = true,
        )
        assertEquals(204, response.statusCode())
        assertTrue(harness.store.storedText(credential.str("deviceId")).contains("kind: crash"))
    }
}
