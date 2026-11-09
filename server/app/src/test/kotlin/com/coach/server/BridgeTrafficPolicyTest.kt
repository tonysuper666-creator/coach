package com.coach.server

import java.nio.file.Files
import kotlin.test.*

class BridgeTrafficPolicyTest {
    @Test fun managedTestTrafficRequiresPrivatePathsAndMatchingUnreservedPorts() {
        val root = Files.createTempDirectory("coach-managed-test-")
        try {
            val env = mapOf(
                "COACH_TRAFFIC" to "test", "COACH_BRIDGE_URL" to "http://127.0.0.1:58001",
                "BRIDGE_PORT" to "58001", "COACH_PORT" to "58002",
                "COACH_DATA_DIR" to root.resolve("data").toString(),
                "COACH_BRIDGE_HOME" to root.resolve("bridge").toString(),
                "COACH_RUN_DIR" to root.resolve("run").toString(),
                "COACH_LOG_DIR" to root.resolve("logs").toString()
            )
            assertEquals(BridgeTraffic("test", 58001), configureBridgeTraffic(env, true))
            assertFalse(Files.exists(root.resolve("bridge/state/production-key")))
            val production = java.nio.file.Path.of(System.getProperty("user.home"), "Coach")
            val rejected = listOf(
                env - "COACH_BRIDGE_URL", env - "BRIDGE_PORT", env - "COACH_DATA_DIR",
                env - "COACH_BRIDGE_HOME", env - "COACH_RUN_DIR", env - "COACH_LOG_DIR",
                env + ("COACH_BRIDGE_URL" to "http://example.com:58001"),
                env + ("COACH_BRIDGE_URL" to "http://127.0.0.1:8791") + ("BRIDGE_PORT" to "8791"),
                env + ("COACH_BRIDGE_URL" to "http://127.0.0.1:3790") + ("BRIDGE_PORT" to "3790"),
                env + ("COACH_BRIDGE_URL" to "http://127.0.0.1:18791") + ("BRIDGE_PORT" to "18791"),
                env + ("COACH_PORT" to "18791"), env + ("COACH_PORT" to "8791"),
                env + ("BRIDGE_PORT" to "58003"),
                env + ("COACH_DATA_DIR" to production.resolve("data/child").toString()),
                env + ("COACH_BRIDGE_HOME" to production.resolve("bridge/child").toString()),
                env + ("COACH_RUN_DIR" to production.resolve("data/run").toString()),
                env + ("COACH_LOG_DIR" to production.resolve("bridge/logs").toString()),
                env + ("COACH_BRIDGE_PRODUCTION_KEY_FILE" to production.resolve("bridge/state/production-key").toString())
            )
            rejected.forEach { bad -> assertFailsWith<IllegalArgumentException> { configureBridgeTraffic(bad, true) } }
            assertFalse(Files.exists(root.resolve("bridge/state/production-key")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun standaloneServerCannotBorrowLearnerBridgeOrClaimUserTraffic() {
        assertFailsWith<IllegalArgumentException> { configureBridgeTraffic(emptyMap(),false) }
        assertFailsWith<IllegalArgumentException> { configureBridgeTraffic(mapOf("COACH_TRAFFIC" to "test", "COACH_BRIDGE_URL" to "http://127.0.0.1:8791"),false) }
        assertFailsWith<IllegalArgumentException> { configureBridgeTraffic(mapOf("COACH_TRAFFIC" to "user", "COACH_BRIDGE_URL" to "http://127.0.0.1:58001"),false) }
        configureBridgeTraffic(mapOf("COACH_BRIDGE_URL" to "http://127.0.0.1:58001"),false)
    }
    @Test fun managedServerSharesOnePrivateCredentialAcrossRestarts() {
        val home=Files.createTempDirectory("coach-source-key-")
        try {
            val env=mapOf("COACH_BRIDGE_HOME" to home.toString())
            assertFailsWith<IllegalArgumentException> {
                configureBridgeTraffic(env + mapOf("COACH_DATA_DIR" to home.resolve("preview-data").toString(),"COACH_PORT" to "18799"),true)
            }
            assertFalse(Files.exists(home.resolve("state/production-key")))
            configureBridgeTraffic(env,true)
            val key=home.resolve("state/production-key")
            val first=Files.readString(key)
            assertEquals(64,first.length)
            configureBridgeTraffic(env,true)
            assertEquals(first,Files.readString(key))
            assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(key)))
        } finally { home.toFile().deleteRecursively() }
    }
}
