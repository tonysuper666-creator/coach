package com.coach.ai

import kotlin.test.*

class TestTrafficIsolationTest {
    @Test fun testRequestsCannotReachTheLearnerPort() {
        for (port in listOf(8790,8791)) {
            val bridge = BridgeClient(BridgeConfig(baseUrl="http://127.0.0.1:$port",testTraffic=true))
            val request = ChatRequest("synthetic",BridgeClient.CLAUDE,"","synthetic",false)
            assertFailsWith<IllegalArgumentException> { bridge.chat(request) }
            assertFailsWith<IllegalArgumentException> { bridge.streamChat(request, object : BridgeStreamSink {}) }
        }
    }
}
