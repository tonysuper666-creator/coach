package com.coach.client

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

internal actual fun createCoachHttpEngine(): HttpClientEngine = CIO.create()
