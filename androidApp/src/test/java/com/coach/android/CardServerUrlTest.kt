package com.coach.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CardServerUrlTest {
    private val card = "com.coach.android.ton1169"
    private fun url(requested: String?, port: Int = 29169, packageName: String = card) =
        resolveCoachServerUrl(requested, packageName, "coach.example", port, true)

    @Test fun cardUsesIsolatedConfiguredPortWhenNoOverrideIsProvided() {
        assertEquals("http://coach.example:29169", url(null))
        assertEquals("http://coach.example:29170", url("http://coach.example:29170"))
    }

    @Test fun productionAndInvalidDestinationsFailClosed() {
        for (port in listOf(18791, 8790, 8791, 0, 65536)) {
            assertThrows(IllegalStateException::class.java) { url(null, port) }
        }
        assertThrows(IllegalStateException::class.java) { url("http://coach.example:18791") }
        assertThrows(IllegalStateException::class.java) { url("http://other.example:29169") }
        assertThrows(IllegalStateException::class.java) { url(null, packageName = "com.coach.android.ton1149") }
    }
}
