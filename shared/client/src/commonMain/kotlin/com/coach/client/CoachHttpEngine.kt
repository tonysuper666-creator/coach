package com.coach.client

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory

/**
 * HTTP/HTTPS engine for Coach REST clients.
 * Android uses OkHttp (platform Conscrypt TLS + network_security_config + hostname-aware
 * certificate verification); JVM keeps Ktor CIO.
 */
internal expect fun createCoachHttpEngine(): HttpClientEngine

internal object CoachHttpEngineFactory : HttpClientEngineFactory<HttpClientEngineConfig> {
    override fun create(block: HttpClientEngineConfig.() -> Unit): HttpClientEngine = createCoachHttpEngine()
}
