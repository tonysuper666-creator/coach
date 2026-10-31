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
    @Test fun productionCredentialTravelsOnlyInHeaders() {
        val key=java.nio.file.Files.createTempFile("coach-bridge-key-", ".txt")
        try {
            java.nio.file.Files.writeString(key,"a".repeat(64))
            StubBridge().use { stub ->
                stub.enqueue(200,StubBridge.completion("synthetic"))
                BridgeClient(BridgeConfig(baseUrl=stub.baseUrl,testTraffic=false,productionKeyFile=key))
                    .chat(ChatRequest("synthetic",BridgeClient.CLAUDE,"","synthetic",false))
                val headers=stub.requestedHeaders.single().mapKeys { it.key.lowercase() }
                assertEquals("user",headers.getValue("x-coach-traffic").single())
                assertEquals("a".repeat(64),headers.getValue("x-coach-production-key").single())
                assertFalse(stub.requests.single().contains("a".repeat(64)))
            }
        } finally { java.nio.file.Files.deleteIfExists(key) }
    }

}
