package com.coach.server

import com.coach.core.LedgerActor
import com.coach.store.LedgerStore
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class AppUpdatesTest {
    @Test fun authenticatedDiscoveryAndPinnedDownloadSurvivePublishingANewerVersion() = runBlocking {
        val root = Files.createTempDirectory("coach-updates-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = LedgerStore(root.resolve("data").toPath())
        val token = "synthetic-update-token-0000000000000000000"
        store.enroll("desktop", token)
        val actor = LedgerActor(scope, store)
        val updates = root.resolve("updates")
        val engine = embeddedServer(Netty, host="127.0.0.1", port=0) {
            ledgerRoutes(actor, MutableSharedFlow(extraBufferCapacity=8), updates=AppUpdates(updates))
        }.apply { start(false) }
        val base = "http://127.0.0.1:${engine.engine.resolvedConnectors().single().port}"
        val client = HttpClient.newHttpClient()
        suspend fun get(path: String, authorized: Boolean = true): HttpResponse<ByteArray> = withContext(Dispatchers.IO) {
            val builder = HttpRequest.newBuilder(URI(base + path)).timeout(java.time.Duration.ofSeconds(10))
            if (authorized) builder.header("X-Coach-Device", "desktop").header("Authorization", "Bearer $token")
            client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        }
        fun publish(code: Int, body: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
            updates.mkdirs(); updates.resolve("$digest.apk").writeText(body)
            val manifest = updates.resolve("com.coach.synthetic/latest.json"); manifest.parentFile.mkdirs()
            manifest.writeText(buildJsonObject {
                put("packageName", "com.coach.synthetic"); put("versionCode", code); put("versionName", "test-$code")
                put("sha256", digest); put("size", body.length)
            }.toString())
            return digest
        }
        try {
            assertEquals(401, get("/v1/app-update?packageName=com.coach.synthetic", false).statusCode())
            assertEquals(401, get("/app.apk?sha256=" + "a".repeat(64), false).statusCode())
            assertEquals(204, get("/v1/app-update?packageName=com.coach.synthetic").statusCode())
            val old = publish(2, "synthetic apk 2")
            val response = get("/v1/app-update?packageName=com.coach.synthetic")
            assertEquals(200, response.statusCode()); assertEquals("no-store", response.headers().firstValue("Cache-Control").get())
            assertEquals(2, Json.parseToJsonElement(String(response.body())).jsonObject.getValue("versionCode").jsonPrimitive.int)
            val newer = publish(3, "synthetic apk 3")
            assertEquals("synthetic apk 2", String(get("/app.apk?sha256=$old").body()))
            assertEquals("synthetic apk 3", String(get("/app.apk?sha256=$newer").body()))
            assertEquals(404, get("/app.apk?sha256=../../device-token").statusCode())
            assertEquals(404, get("/app.apk").statusCode())
            updates.resolve("$newer.apk").delete()
            assertEquals(503, get("/v1/app-update?packageName=com.coach.synthetic").statusCode())
            assertEquals(200, get("/v1/hello").statusCode(), "A broken update must not break learning")
        } finally {
            engine.stopSuspend(0, 500); actor.close(); scope.cancel(); root.deleteRecursively()
        }
    }
}
