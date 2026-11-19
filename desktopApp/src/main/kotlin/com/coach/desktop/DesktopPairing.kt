package com.coach.desktop

import com.coach.client.DeviceAuthorizationException
import com.coach.client.HttpLedgerTransport
import com.coach.client.PairedCredentials
import com.coach.client.PairingClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Deliberately not a data class: credentials must never appear in generated toString output. */
internal class DesktopIdentity(val serverUrl: String, val credentials: PairedCredentials)

internal class DesktopPairing(
    private val settings: DesktopConnectionSettings,
    private val store: DesktopCredentials = DesktopCredentials(settings.clientDirectory),
) {
    fun load(): DesktopIdentity? {
        val url = settings.serverUrl() ?: return null
        return (store.load(url) ?: settings.nativeCredentials(url))?.let { DesktopIdentity(url, it) }
    }

    suspend fun pair(address: String, code: String, name: String): DesktopIdentity {
        val url = desktopServerUrl(address)
        requireDevelopmentServer(settings, url)
        val client = PairingClient(url)
        try {
            val value = client.pair(code, name.take(80))
            if (value.role == "friend") check(client.friendAuthorized(value))
            else {
                val transport = HttpLedgerTransport(url, value.deviceId, value.token)
                try { transport.hello(null) } finally { transport.close() }
            }
            settings.saveAddress(url)
            store.save(url, value)
            return DesktopIdentity(url, value)
        } finally { client.close() }
    }

    suspend fun sable(open: suspend (String, String) -> Unit, issuer: String = com.coach.client.SABLE_ORIGIN): DesktopIdentity {
        val url = settings.sableFriendUrl()
        requireDevelopmentServer(settings, url)
        val client = PairingClient(url)
        try {
            val value = client.sable(open, issuer)
            withContext(Dispatchers.IO) {
                settings.saveAddress(url)
                store.save(url, value)
            }
            return DesktopIdentity(url, value)
        } finally { client.close() }
    }

    /** Offline is not revocation. Only an authenticated endpoint's explicit 401 retires a pairing. */
    suspend fun stillAuthorized(identity: DesktopIdentity, startup: Boolean = false): Boolean {
        if (identity.credentials.role == "friend") {
            val client = PairingClient(identity.serverUrl)
            return try {
                client.friendAuthorized(identity.credentials, startup).also { if (!it) store.clear(identity.serverUrl, identity.credentials) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { true }
            finally { client.close() }
        }
        val transport = HttpLedgerTransport(identity.serverUrl, identity.credentials.deviceId, identity.credentials.token)
        return try { transport.hello(null); true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: DeviceAuthorizationException) { store.clear(identity.serverUrl, identity.credentials); false }
        catch (_: Exception) { true }
        finally { transport.close() }
    }

    suspend fun awaitRevocation(identity: DesktopIdentity) {
        var startup = true
        while (currentCoroutineContext().isActive) {
            if (!stillAuthorized(identity, startup)) return
            startup = false
            delay(5_000)
        }
    }
}
