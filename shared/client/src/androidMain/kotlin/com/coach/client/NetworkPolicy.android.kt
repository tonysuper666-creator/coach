package com.coach.client

import android.security.NetworkSecurityPolicy
import io.ktor.http.Url
import io.ktor.http.URLProtocol

internal actual fun checkCoachNetworkPolicy(url: Url) {
    // CIO uses raw sockets, so apply Android's allowlist before opening HTTP or WS.
    if (url.protocol == URLProtocol.HTTP && !NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(url.host)) {
        throw SecurityException("Cleartext server address is not allowlisted")
    }
}
