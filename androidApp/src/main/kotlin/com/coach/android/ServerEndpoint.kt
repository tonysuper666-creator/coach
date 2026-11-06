package com.coach.android

import java.net.URI

/** Card builds may use an explicit isolated launcher port; no production fallback. */
internal fun resolveCoachServerUrl(
    requested: String?, packageName: String, debug: Boolean, host: String,
    productionPort: Int, isolatedLaunchPort: Int,
): String {
    val configured = "http://$host:$productionPort"
    fun allowed(candidate: String): Boolean = runCatching {
        val uri = URI(candidate)
        uri.scheme == "http" && uri.host == host && uri.port in 1..65535 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.orEmpty() in setOf("", "/")
    }.getOrDefault(false)
    val accepted = if (debug) requested?.takeIf(::allowed) else null
    if (packageName != "com.coach.android") {
        val candidate = if (requested == null && debug && isolatedLaunchPort in 1..65535)
            "http://$host:$isolatedLaunchPort".takeIf(::allowed) else accepted
        val port = candidate?.let { URI(it).port }
        check(candidate != null && port !in setOf(18791, 8791, 8790)) {
            "Refusing unsafe or missing isolated endpoint for $packageName"
        }
        return candidate
    }
    if (!debug || requested.isNullOrBlank()) return configured
    return accepted ?: configured
}
