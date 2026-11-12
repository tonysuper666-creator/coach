package com.coach.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Fail a mismatched delivered pair before opening a UI, fixture, or measurement capture. */
@RunWith(AndroidJUnit4::class)
class ReaderMeasurementPackageSmoke {
    @Test fun installedTargetPassesTheSharedGate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetPackage = instrumentation.targetContext.packageName
        ReaderMeasurementPackage.requireIsolated(targetPackage)
        assertEquals(targetPackage + ".test", instrumentation.context.packageName)
    }
}
