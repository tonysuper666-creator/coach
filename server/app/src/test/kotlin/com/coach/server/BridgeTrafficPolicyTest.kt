package com.coach.server

import java.nio.file.Files
import kotlin.test.*

class BridgeTrafficPolicyTest {
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
