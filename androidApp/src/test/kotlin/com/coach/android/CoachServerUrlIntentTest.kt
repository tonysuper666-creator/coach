package com.coach.android

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Intent both activities hand over, read through a real Bundle: a present key that is not a string must not look like no key at all (TON-1298). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CoachServerUrlIntentTest {
    private val host = BuildConfig.COACH_TAIL_HOST
    private val configured = "http://$host:${BuildConfig.COACH_PORT}"
    private val isolated = "http://$host:18892"
    private val card = "com.coach.android.ton1149"
    private val absent = listOf(
        "no extras" to Intent(),
        "other extras only" to Intent().putExtra("openKp", "kp").putExtra("CoachUrl", isolated).putExtra("coachurl", isolated),
    )
    private val notAString = listOf(
        "explicit null" to Intent().putExtra(coachUrlExtra, null as String?),
        "int" to Intent().putExtra(coachUrlExtra, 18892),
        "long" to Intent().putExtra(coachUrlExtra, 18892L),
        "boolean" to Intent().putExtra(coachUrlExtra, true),
        "string array" to Intent().putExtra(coachUrlExtra, arrayOf(isolated)),
        "char sequence" to Intent().putExtra(coachUrlExtra, StringBuilder(isolated) as CharSequence),
    )
    private val strings = listOf("empty" to Intent().putExtra(coachUrlExtra, ""), "isolated" to Intent().putExtra(coachUrlExtra, isolated))

    @Test fun theseShapesAllReadBackAsAMissingString() {
        for ((label, intent) in notAString) {
            assertTrue(label, intent.hasExtra(coachUrlExtra))
            assertNull(label, intent.getStringExtra(coachUrlExtra))
        }
    }

    @Test fun releaseAcceptancePackageRefusesTheKeyWhateverItHolds() {
        for ((label, intent) in absent) assertEquals(label, configured, coachServerUrl(intent, releaseAcceptancePackage))
        for ((label, intent) in notAString + strings) {
            assertThrows(label, IllegalStateException::class.java) { coachServerUrl(intent, releaseAcceptancePackage) }
        }
    }

    @Test fun mainPackageKeepsItsBehaviour() {
        for ((label, intent) in absent + notAString + strings.take(1)) assertEquals(label, configured, coachServerUrl(intent, "com.coach.android"))
        assertEquals(isolated, coachServerUrl(Intent().putExtra(coachUrlExtra, isolated), "com.coach.android"))
    }

    @Test fun otherCardPackagesKeepTheirBehaviour() {
        for ((label, intent) in absent + notAString + strings.take(1)) {
            assertThrows(label, IllegalStateException::class.java) { coachServerUrl(intent, card) }
        }
        assertEquals(isolated, coachServerUrl(Intent().putExtra(coachUrlExtra, isolated), card))
    }
}
