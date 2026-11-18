package com.coach.server

import com.coach.domain.*
import com.coach.store.sha256
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

internal val observedFriend = AttributeKey<String>("coach.friend")
internal fun friendId(name: String) = sha256(name.toByteArray(Charsets.UTF_8))
internal fun friendPath(call: ApplicationCall): Boolean = when (call.request.httpMethod to call.request.path()) {
    HttpMethod.Post to "/v1/pair", HttpMethod.Get to "/v1/public/status",
    HttpMethod.Post to "/v1/library/explain", HttpMethod.Post to "/v1/library/explain/cancel" -> true
    else -> false
}

/** Fixed, human-readable errors; upstream bodies and diagnostics never cross this boundary. */
internal class FriendServiceError(val code: String = "unavailable") : RuntimeException(code) {
    val status get() = if (code in setOf("daily_quota", "monthly_quota")) HttpStatusCode.TooManyRequests else HttpStatusCode.ServiceUnavailable
    val body get() = json("code" to code, "error" to when (code) {
        "daily_quota" -> "今天的讲解额度用完了，明天再来。"
        "monthly_quota" -> "这个月的讲解额度用完了。"
        else -> "讲解服务暂时连不上，读书照常。"
    })
}

/** These stores never open the owner's catalog, ledger, source files or AI-call table. */
class FriendAccess(private val directory: Path,
    private val state: Path = Path.of(System.getenv("COACH_API_STATE_DIR") ?: "${System.getProperty("user.home")}/Coach/state"),
    private val config: Path = Path.of(System.getenv("COACH_FRIEND_CONFIG") ?: "${System.getProperty("user.home")}/Coach/config/friends.json")) {
    private val stores = ConcurrentHashMap<String, ImportedBookSources>()
    internal fun imports(name: String) = stores.computeIfAbsent(friendId(name)) { ImportedBookSources(directory.resolve(it).resolve("imports")) }
    internal fun status(name: String, device: String? = null): JsonObject {
        try {
            val settings = if (Files.exists(config)) LedgerJson.parseToJsonElement(Files.readString(config)).jsonObject else json()
            val id = friendId(name)
            val defaults = settings["defaults"] as? JsonObject ?: json()
            val overrides = (settings["friends"] as? JsonObject)?.get(id) as? JsonObject ?: json()
            fun limit(key: String, fallback: Long): Long = ((overrides[key] ?: defaults[key])?.jsonPrimitive?.long ?: fallback).also { require(it >= 0) }
            val day = LocalDate.now(ZoneOffset.UTC).toString()
            val file = state.resolve("api-credit-ledger.json")
            val ledger = if (Files.exists(file)) LedgerJson.parseToJsonElement(Files.readString(file)).jsonObject else json()
            val usage = ((ledger["friends"] as? JsonObject)?.get(id) as? JsonObject)?.get(day) as? JsonObject ?: json()
            val calls = usage["calls"]?.jsonPrimitive?.long ?: 0L
            val tokens = (usage["input_tokens"]?.jsonPrimitive?.long ?: 0L) + (usage["output_tokens"]?.jsonPrimitive?.long ?: 0L)
            val pending = usage["reserved"] as? JsonObject ?: json()
            val pendingTokens = pending.values.sumOf { it.jsonObject["tokens"]!!.jsonPrimitive.long }
            val deviceUsage = (usage["devices"] as? JsonObject)?.get(device) as? JsonObject
            return json("online" to true, "day" to day, "calls" to (if (device == null) calls else deviceUsage?.get("calls")?.jsonPrimitive?.long ?: 0L),
                "callsRemaining" to (limit("daily_calls", 100) - calls - pending.size).coerceAtLeast(0),
                "tokensRemaining" to (limit("daily_tokens", 1_000_000) - tokens - pendingTokens).coerceAtLeast(0))
        } catch (_: Exception) { throw FriendServiceError() }
    }

    /** Answers live beside this friend's imports. The audit contains fixed codes, never source/answer text. */
    @Synchronized internal fun record(name: String, device: String, request: String, status: String, answer: JsonObject? = null,
        diagnostics: FriendReaderDiagnostics? = null) {
        val root = privateFiles.directory(directory.resolve(friendId(name)))
        val file = privateFiles.file(root.resolve("explanations.sqlite"))
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS explanations(at INTEGER,device TEXT,request TEXT,status TEXT,answer TEXT)") }
            db.prepareStatement("INSERT INTO explanations VALUES(?,?,?,?,?)").use {
                it.setLong(1, System.currentTimeMillis()); it.setString(2, device); it.setString(3, request.take(32))
                it.setString(4, status); it.setString(5, answer?.toString()); it.executeUpdate()
            }
        }
        val log = privateFiles.file(root.resolve("requests.log"))
        val row = json("at" to System.currentTimeMillis(), "device" to device, "status" to status,
            "request" to request.takeIf(readerRequestId::matches),
            "sentAnchors" to diagnostics?.sentAnchors, "citedAnchors" to diagnostics?.citedAnchors,
            "formatReason" to diagnostics?.formatReason?.name, "bridgeFailureStage" to diagnostics?.bridgeFailureStage?.name,
            "timeoutStage" to diagnostics?.timeoutStage?.name)
        Files.writeString(log, "$row\n", java.nio.file.StandardOpenOption.APPEND)
    }
}
