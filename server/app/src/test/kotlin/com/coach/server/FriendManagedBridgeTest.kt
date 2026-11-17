package com.coach.server

import java.nio.file.Path
import kotlin.test.*

class FriendManagedBridgeTest {
    private val env = mapOf("COACH_FRIEND_API_ONLY" to "1", "COACH_TRAFFIC" to "test",
        "BRIDGE_PORT" to "58001", "COACH_BRIDGE_URL" to "http://127.0.0.1:58001",
        "COACH_PORT" to "58002", "COACH_PUBLIC_PORT" to "58003")

    @Test fun temporaryFriendServiceActuallySelectsTheNarrowAdapter() {
        assertEquals(listOf("python", "/candidate/tools/smoke/friend-service-bridge.py"),
            managedBridgeCommand(env, Path.of("/candidate"), "python"))
        assertEquals(listOf("/candidate/tools/bridge/run.sh"),
            managedBridgeCommand(emptyMap(), Path.of("/candidate"), "python"))
    }

    @Test fun modeCannotBeUsedByProductionOrAnUnisolatedBridge() {
        for (change in listOf(mapOf("COACH_TRAFFIC" to "user"), mapOf("COACH_FRIEND_API_ONLY" to "true"),
            mapOf("COACH_PUBLIC_PORT" to "0"), mapOf("BRIDGE_PORT" to "8791"),
            mapOf("COACH_BRIDGE_URL" to "http://127.0.0.1:58004"))) {
            assertFailsWith<IllegalArgumentException> { managedBridgeCommand(env + change, Path.of("/candidate"), "python") }
        }
    }
}
