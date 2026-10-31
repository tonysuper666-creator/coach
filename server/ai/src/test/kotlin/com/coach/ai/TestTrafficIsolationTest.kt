package com.coach.ai

import kotlin.test.*

class TestTrafficIsolationTest {
    @Test fun testRequestsCannotReachTheLearnerPort() {
        val bridge = BridgeClient(BridgeConfig(baseUrl="http://127.0.0.1:8791",testTraffic=true))
        val request = ChatRequest("synthetic",BridgeClient.CLAUDE,"","synthetic",false)
        assertFailsWith<IllegalArgumentException> { bridge.chat(request) }
        assertFailsWith<IllegalArgumentException> { bridge.streamChat(request, object : BridgeStreamSink {}) }
    }
}
