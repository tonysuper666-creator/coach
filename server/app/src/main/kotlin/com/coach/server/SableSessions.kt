package com.coach.server

import com.coach.core.LedgerActor
import com.coach.domain.LedgerJson
import com.coach.domain.str
import com.coach.store.DeviceCredential
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

internal class SableUnavailable : Exception()
internal class SableIdentity(val sub: String, val username: String)
internal class SableWhoami(issuer: URI = URI.create(com.coach.client.SABLE_ORIGIN)) {
    init {
        require(issuer.toString() == com.coach.client.SABLE_ORIGIN ||
            (issuer.scheme == "http" && issuer.host == "127.0.0.1" && issuer.userInfo == null))
    }
    private val endpoint = issuer.resolve("/api/coach/whoami")
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    suspend fun lookup(token: String): SableIdentity? = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer $token").GET().build()
        val response = try { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            catch (_: IOException) { throw SableUnavailable() }
        when (response.statusCode()) {
            401 -> null
            200 -> {
                val body = runCatching { LedgerJson.parseToJsonElement(response.body()).jsonObject }.getOrNull()
                val sub = body?.str("sub").orEmpty()
                val username = body?.str("username").orEmpty()
                if (sub.isBlank() || sub.length > 256 || username.isBlank() || username.length > 80 ||
                    username.any(Char::isISOControl)) throw SableUnavailable()
                SableIdentity(sub, username)
            }
            else -> throw SableUnavailable()
        }
    }
}

/** Cache successful checks for this process only. Restart always requires a fresh whoami. */
class SableSessions internal constructor(
    private val actor: LedgerActor,
    private val vault: SableVault,
    private val lookup: suspend (String) -> SableIdentity? = SableWhoami()::lookup,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(actor: LedgerActor, vaultDirectory: java.nio.file.Path, issuer: URI = URI.create(com.coach.client.SABLE_ORIGIN),
        now: () -> Long = System::currentTimeMillis) : this(actor, SableVault(vaultDirectory), SableWhoami(issuer)::lookup, now)

    private class Verification(var checked: Long? = null) { val lock = Mutex() }
    private val checks = ConcurrentHashMap<String, Verification>()

    suspend fun exchange(token: String, books: UnifiedBookPipeline? = null): DeviceCredential? {
        val id = lookup(token) ?: return null
        suspend fun issue() = actor.execute {
            sableCredential(id.sub, id.username) { device, friend -> vault.seal(device, friend, token) }
        }
        val credential = (if (books == null) issue() else {
            books.withActiveAccount(UnifiedBookScope.fromSableFriend(com.coach.store.sableFriendName(id.sub))) { issue() }
        }) ?: return null
        checks[credential.deviceId] = Verification(now())
        return credential
    }

    suspend fun check(device: String, startup: Boolean): HttpStatusCode {
        val verification = checks.computeIfAbsent(device) { Verification() }
        return verification.lock.withLock {
            val last = verification.checked
            if (!startup && last != null && now() - last in 0L until 10 * 60_000L) return@withLock HttpStatusCode.OK
            val (friend, sealed) = actor.execute { sableGrant(device) } ?: return@withLock HttpStatusCode.Unauthorized
            val token = try { withContext(Dispatchers.IO) { vault.open(device, friend, sealed) } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { return@withLock HttpStatusCode.ServiceUnavailable }
            val id = try { lookup(token) } catch (_: SableUnavailable) {
                return@withLock if (last != null && now() - last in 0L until 15 * 60_000L) HttpStatusCode.OK
                    else HttpStatusCode.ServiceUnavailable
            }
            if (id == null || com.coach.store.sableFriendName(id.sub) != friend) {
                actor.execute { revokeDevice(device) }
                verification.checked = null
                checks.remove(device, verification)
                HttpStatusCode.Unauthorized
            } else {
                verification.checked = now()
                HttpStatusCode.OK
            }
        }
    }

    suspend fun refresh() {
        val devices = actor.execute { activeSableDevices() }.toSet()
        for (device in devices) check(device, false)
        for (device in checks.keys) if (device !in devices) checks.remove(device)
    }
}
