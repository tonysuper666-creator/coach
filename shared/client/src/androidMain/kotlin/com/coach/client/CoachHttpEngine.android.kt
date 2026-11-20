package com.coach.client

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

internal actual fun createCoachHttpEngine(): HttpClientEngine = OkHttp.create {
    config {
        followRedirects(false)
        followSslRedirects(false)
        // Defer request deadlines to Ktor's HttpTimeout plugin (including 15s course calls
        // and 195s book explain requests) instead of OkHttp's 10s default read/write timeout.
        readTimeout(0, TimeUnit.MILLISECONDS)
        writeTimeout(0, TimeUnit.MILLISECONDS)
    }
}
