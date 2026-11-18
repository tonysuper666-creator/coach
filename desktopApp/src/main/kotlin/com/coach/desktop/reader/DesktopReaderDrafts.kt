package com.coach.desktop.reader

import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.desktop.DesktopPairing
import com.coach.desktop.desktopServerUrl
import java.security.MessageDigest

internal fun ReaderDraftScope.forPairedSession(settings: DesktopConnectionSettings, identity: DesktopIdentity, course: String): FileNoteDraftStore? {
    if (course.isBlank() || identity.credentials.deviceId.isBlank()) return null
    val address = runCatching { desktopServerUrl(identity.serverUrl) }.getOrNull() ?: return null
    // Bare credentials from a synthetic/test source are insufficient: this client must have registered this pairing.
    val registered = runCatching { DesktopPairing(settings).load() }.getOrNull() ?: return null
    if (desktopServerUrl(registered.serverUrl) != address || registered.credentials.deviceId != identity.credentials.deviceId) return null
    val key = MessageDigest.getInstance("SHA-256").digest("$address\n${identity.credentials.deviceId}\n$course".toByteArray())
        .joinToString("") { "%02x".format(it) }.take(32)
    return FileNoteDraftStore(settings.clientDirectory.resolve("reader-drafts").resolve("paired").resolve(key), "paired-device:$key")
}
