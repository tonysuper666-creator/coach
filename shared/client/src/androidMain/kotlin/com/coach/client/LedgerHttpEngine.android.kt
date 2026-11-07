package com.coach.client

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.config
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

// Reuse Android HTTP connections so ledger hints do not wait for a new connection.
// WebSocket pings are configured on OkHttp because Ktor's ping interval does not apply to it.
internal actual fun ledgerHttpEngine(): HttpClientEngineFactory<*> = OkHttp.config {
    config { pingInterval(5, TimeUnit.SECONDS) }
}
