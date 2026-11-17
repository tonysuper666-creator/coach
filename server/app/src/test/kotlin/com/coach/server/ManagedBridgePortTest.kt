package com.coach.server

import kotlin.test.*

class ManagedBridgePortTest {
    private val preview = mapOf("COACH_TRAFFIC" to "test", "BRIDGE_PORT" to "58001",
        "COACH_BRIDGE_URL" to "http://127.0.0.1:58001", "COACH_PORT" to "58002",
        "COACH_PUBLIC_PORT" to "58003")

    @Test fun managedPreviewCanOwnItsBridgeWithoutBorrowingProduction() {
        configureBridgeTraffic(preview, true)
        assertEquals(58001, managedBridgePort(preview))
        assertEquals(8791, managedBridgePort(emptyMap()))
        assertEquals(8791, managedBridgePort(mapOf("COACH_TRAFFIC" to "user")))
    }

    @Test fun customPortCannotClaimLearnerTraffic() {
        for (traffic in listOf("user", null)) {
            val env = preview.toMutableMap().also {
                if (traffic == null) it.remove("COACH_TRAFFIC") else it["COACH_TRAFFIC"] = traffic
            }
            assertFailsWith<IllegalArgumentException> { configureBridgeTraffic(env, true) }
        }
    }

    @Test fun reservedAndCollidingPortsFailBeforeServiceStartup() {
        for (port in listOf(0, 80, 8790, 8791, 18791, 3790, 19540, 58002, 58003, 65536)) {
            val env = preview + mapOf("BRIDGE_PORT" to "$port", "COACH_BRIDGE_URL" to "http://127.0.0.1:$port")
            assertFailsWith<IllegalArgumentException>("port=$port") { configureBridgeTraffic(env, true) }
        }
        assertFailsWith<IllegalArgumentException> {
            configureBridgeTraffic(preview + ("COACH_PUBLIC_PORT" to "058001"), true)
        }
    }

    @Test fun bridgeUrlMustDescribeTheExactOwnedListener() {
        for (url in listOf("http://127.0.0.1:58004", "http://localhost:58001", "http://example.com:58001",
            "https://127.0.0.1:58001", "http://user@127.0.0.1:58001", "http://127.0.0.1:58001/path",
            "http://127.0.0.1:58001?x=1", "http://127.0.0.1:58001#fragment")) {
            assertFailsWith<IllegalArgumentException>(url) {
                configureBridgeTraffic(preview + ("COACH_BRIDGE_URL" to url), true)
            }
        }
    }
}
