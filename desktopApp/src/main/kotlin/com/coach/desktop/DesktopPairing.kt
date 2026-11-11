package com.coach.desktop

import com.coach.client.DeviceAuthorizationException
import com.coach.client.HttpLedgerTransport
import com.coach.client.PairedCredentials
import com.coach.client.PairingClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
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
        val client = PairingClient(url)
        try {
            val value = client.pair(code, name.take(80))
            val transport = HttpLedgerTransport(url, value.deviceId, value.token)
            try { transport.hello(null) } finally { transport.close() }
            settings.saveAddress(url)
            store.save(url, value)
            return DesktopIdentity(url, value)
        } finally { client.close() }
    }

    /** Offline is not revocation. Only an authenticated endpoint's explicit 401 retires a pairing. */
    suspend fun stillAuthorized(identity: DesktopIdentity): Boolean {
        val transport = HttpLedgerTransport(identity.serverUrl, identity.credentials.deviceId, identity.credentials.token)
        return try { transport.hello(null); true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: DeviceAuthorizationException) { store.clear(identity.serverUrl, identity.credentials); false }
        catch (_: Exception) { true }
        finally { transport.close() }
    }

    suspend fun awaitRevocation(identity: DesktopIdentity) {
        while (currentCoroutineContext().isActive) {
            if (!stillAuthorized(identity)) return
            delay(5_000)
        }
    }
}
