package com.coach.client

import io.ktor.client.engine.HttpClientEngineFactory

internal expect fun ledgerHttpEngine(): HttpClientEngineFactory<*>
