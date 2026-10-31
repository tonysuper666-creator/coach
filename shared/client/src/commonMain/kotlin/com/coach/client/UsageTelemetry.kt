package com.coach.client

import com.coach.client.db.ClientDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.random.Random

/** Closed vocabulary: no question, answer, title, URL, exception or free-form text. */
object UsageVocabulary {
    val kinds = setOf("open", "screen", "tap", "error", "disconnect", "reconnect", "first_word")
    val targets = setOf("app", "today", "study", "review", "compact", "wide", "connection",
        "open_kp", "next", "test", "ask", "answer", "cancel", "settings", "explain", "quick_ask",
        "select_option", "scope", "offline_pack", "journal", "network", "server", "unknown")
    fun valid(event: JsonObject): Boolean = runCatching {
        event.keys == setOf("id", "at", "kind", "target", "ms") &&
            event.getValue("id").jsonPrimitive.content.matches(Regex("[a-f0-9]{32}")) &&
            event.getValue("at").jsonPrimitive.long >= 0 &&
            event.getValue("kind").jsonPrimitive.content in kinds &&
            event.getValue("target").jsonPrimitive.content in targets &&
            event.getValue("ms").jsonPrimitive.long in -1..86_400_000
    }.getOrDefault(false)
}

/** Durable bounded queue. Only successful receipt removes a batch; retries keep the same ids. */
class UsageTelemetry(
    private val db: ClientDatabase,
    private val now: () -> Long,
    private val deliver: suspend (List<JsonObject>) -> Boolean,
) {
    private val sending = Mutex()
    fun record(kind: String, target: String = "app", ms: Long = -1) {
        if (kind !in UsageVocabulary.kinds || target !in UsageVocabulary.targets) return
        // Diagnostics must never stop studying, including when local storage is full.
        runCatching {
            val id = Random.nextBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            val event = buildJsonObject { put("id", id); put("at", now()); put("kind", kind); put("target", target); put("ms", ms.coerceIn(-1, 86_400_000)) }
            db.transaction { db.ledgerQueries.putUsage(id, event.toString()); db.ledgerQueries.trimUsage() }
        }
    }
    suspend fun flush(): Int = sending.withLock {
        var count = 0
        // Bound each pass; a long offline backlog cannot monopolize the connection.
        repeat(10) {
            val batch = db.ledgerQueries.usageBatch().executeAsList()
            if (batch.isEmpty()) return@withLock count
            val events = batch.map { Json.parseToJsonElement(it.payload).jsonObject }
            val accepted = try { deliver(events) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (!accepted) return@withLock count
            db.transaction { batch.forEach { db.ledgerQueries.ackUsage(it.id) } }
            count += batch.size
        }
        count
    }
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            try { flush() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* retry next pass */ }
            delay(5_000)
        }
    }
}

class UsageReporter(baseUrl: String, private val device: String, private val token: String) {
    init { validateCoachBaseUrl(baseUrl) }
    private val url = baseUrl.trimEnd('/') + "/v1/usage"
    private val http = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 5_000; connectTimeoutMillis = 2_000 }
    }
    suspend fun send(events: List<JsonObject>): Boolean = http.post(url) {
        header("Authorization", "Bearer $token"); header("X-Coach-Device", device)
        contentType(ContentType.Application.Json); setBody(JsonArray(events).toString())
    }.status == HttpStatusCode.NoContent
    fun close() = http.close()
}
