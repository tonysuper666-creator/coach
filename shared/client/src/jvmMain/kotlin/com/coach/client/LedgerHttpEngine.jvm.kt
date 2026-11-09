package com.coach.client

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

internal actual fun ledgerHttpEngine(): HttpClientEngineFactory<*> = CIO
