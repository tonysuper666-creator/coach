package com.coach.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Which server each installed package may reach. Only the main package and the pinned release acceptance package ever reach production (TON-1149, TON-1298). */
class CoachServerUrlTest {
    private val host = BuildConfig.COACH_TAIL_HOST
    private val configured = "http://$host:${BuildConfig.COACH_PORT}"
    private val isolated = "http://$host:18892"
    private val production = listOf("http://$host:18791", "http://$host:8791", "http://$host:8790", "http://$host:018791")
    private val notThisMac = listOf(
        "", " ", "http://$host", "http://$host:0", "http://$host:65536", "https://$host:18892", "HTTP://$host:18892", " $isolated",
        "http://user@$host:18892", "$isolated?x=1", "$isolated#x", "$isolated/v1", "http://$host.:18892",
        "http://127.0.0.1:18892", "http://localhost:18892", "http://10.0.2.2:18892", "http://[::1]:18892", "http://coach-mac.example.ts.net:18892",
    )
    private val cards = listOf("com.coach.android.ton1149", "com.coach.android.reviewtest", "com.coach.android.ton12980", "com.coach.android.ton1298.test", "com.coach.android.TON1298")

    @Test fun mainPackageKeepsItsConfiguredServerAndMayPickAnIsolatedPort() {
        for (missing in listOf(null, "", " ")) assertEquals(configured, coachServerUrl(missing, "com.coach.android"))
        assertEquals(isolated, coachServerUrl(isolated, "com.coach.android"))
        for (other in notThisMac) assertEquals(other, configured, coachServerUrl(other, "com.coach.android"))
    }

    @Test fun cardPackagesReachOnlyAnIsolatedServerOnTheConfiguredMac() {
        for (card in cards) {
            assertEquals(isolated, coachServerUrl(isolated, card))
            assertEquals("$isolated/", coachServerUrl("$isolated/", card))
            for (refused in listOf(null) + production + notThisMac) assertThrows("$card $refused", IllegalStateException::class.java) { coachServerUrl(refused, card) }
        }
    }

    @Test fun releaseAcceptancePackageUsesTheConfiguredServerAndRefusesEveryOverride() {
        assertEquals(configured, coachServerUrl(null, releaseAcceptancePackage))
        for (override in listOf(isolated, configured) + production + notThisMac) {
            assertThrows(override, IllegalStateException::class.java) { coachServerUrl(override, releaseAcceptancePackage) }
        }
    }
}
