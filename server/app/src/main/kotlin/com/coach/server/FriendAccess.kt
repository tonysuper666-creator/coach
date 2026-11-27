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
// Which endpoints a signed-in friend may call lives in PublicApiTable.kt (friendPath/publicApiGate).

/** Fixed, human-readable errors; upstream bodies and diagnostics never cross this boundary. */
internal class FriendServiceError(val code: String = "unavailable", private val retryAfterSec: Int? = null) : RuntimeException(code) {
    val status get() = if (code in setOf("daily_quota", "monthly_quota")) HttpStatusCode.TooManyRequests else HttpStatusCode.ServiceUnavailable
    val body get() = buildJsonObject {
        put("code", code)
        put("error", "讲解服务暂时连不上，读书照常。")
        retryAfterSec?.takeIf { it > 0 }?.let { put("retryAfterSec", it) }
    }
}

/** These stores never open the owner's catalog, ledger, source files or AI-call table. */
class FriendAccess(private val directory: Path,
    private val state: Path = Path.of(System.getenv("COACH_API_STATE_DIR") ?: "${System.getProperty("user.home")}/Coach/state"),
    private val config: Path = Path.of(System.getenv("COACH_FRIEND_CONFIG") ?: "${System.getProperty("user.home")}/Coach/config/friends.json"),
    sableSessions: SableSessions? = null) {
    internal var sable: SableSessions? = sableSessions
    private val stores = ConcurrentHashMap<String, ImportedBookSources>()
    internal fun imports(name: String) = stores.computeIfAbsent(friendId(name)) { ImportedBookSources(directory.resolve(it).resolve("imports")) }
    @Synchronized internal fun deleteAccountData(name: String) {
        val id = friendId(name)
        fun hasOverride(file: Path): Boolean {
            if (Files.notExists(file)) return false
            val entries = LedgerJson.parseToJsonElement(Files.readString(file)).jsonObject["friends"] ?: return false
            return id in entries.jsonObject
        }
        if (hasOverride(state.resolve("api-credit-ledger.json")) || hasOverride(config)) {
            val serviceRoot = System.getenv("COACH_SERVICE_ROOT") ?: throw java.io.IOException("missing Bridge installation")
            val python = System.getenv("COACH_PYTHON") ?: throw java.io.IOException("missing Bridge interpreter")
            val script = Path.of(serviceRoot).resolve("tools/bridge/erase_friend_usage.py")
            val process = ProcessBuilder(python,script.toString(),state.toAbsolutePath().toString(),config.toAbsolutePath().toString(),id)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                if (!process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                    throw java.io.IOException("Bridge usage cleanup pending")
                }
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
        }
        stores.remove(id)
        val root = directory.resolve(id)
        if (Files.exists(root) && (!root.toFile().deleteRecursively() || Files.exists(root))) {
            throw java.io.IOException("friend account data was not fully removed")
        }
    }
    internal fun status(name: String, device: String? = null): JsonObject {
        try {
            val settings = if (Files.exists(config)) LedgerJson.parseToJsonElement(Files.readString(config)).jsonObject else json()
            val id = friendId(name)
            val defaults = settings["defaults"]?.jsonObject ?: json()
            val overrides = settings["friends"]?.jsonObject?.get(id)?.jsonObject ?: json()
            fun limit(key: String): Long? = (overrides[key] ?: defaults[key])?.jsonPrimitive?.let {
                require(!it.isString)
                it.long.also { value -> require(value >= 0) }
            }
            val day = LocalDate.now(ZoneOffset.UTC).toString()
            val file = state.resolve("api-credit-ledger.json")
            val ledger = if (Files.exists(file)) LedgerJson.parseToJsonElement(Files.readString(file)).jsonObject else json()
            val usage = ((ledger["friends"] as? JsonObject)?.get(id) as? JsonObject)?.get(day) as? JsonObject ?: json()
            val calls = usage["calls"]?.jsonPrimitive?.long ?: 0L
            val tokens = (usage["input_tokens"]?.jsonPrimitive?.long ?: 0L) + (usage["output_tokens"]?.jsonPrimitive?.long ?: 0L)
            val pending = usage["reserved"] as? JsonObject ?: json()
            val deviceUsage = (usage["devices"] as? JsonObject)?.get(device) as? JsonObject
            return buildJsonObject {
                put("online", true)
                put("day", day)
                put("calls", if (device == null) calls else deviceUsage?.get("calls")?.jsonPrimitive?.long ?: 0L)
                limit("daily_calls")?.let { put("callsRemaining", (it - calls - pending.size).coerceAtLeast(0)) }
                limit("daily_tokens")?.let {
                    val pendingTokens = pending.values.sumOf { row -> row.jsonObject.getValue("tokens").jsonPrimitive.long }
                    put("tokensRemaining", (it - tokens - pendingTokens).coerceAtLeast(0))
                }
            }
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
            "formatReason" to diagnostics?.formatReason?.name,
            "streamJsonError" to diagnostics?.streamJsonError?.name,
            "bridgeFailureStage" to diagnostics?.bridgeFailureStage?.name,
            "timeoutStage" to diagnostics?.timeoutStage?.name)
        Files.writeString(log, "$row\n", java.nio.file.StandardOpenOption.APPEND)
    }
}
