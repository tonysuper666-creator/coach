package com.coach.desktop.books

import kotlin.test.Test
import kotlin.test.assertTrue

class ProcessWorkingSetTest {
    @Test fun workingSetIsTheProcessResidentSize() {
        val resident = ProcessWorkingSet.bytes()
        val heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        assertTrue(resident > 32L * 1024 * 1024, "working set $resident")
        assertTrue(resident > heap / 4, "working set $resident should not be the used-heap gauge $heap")
    }
}
