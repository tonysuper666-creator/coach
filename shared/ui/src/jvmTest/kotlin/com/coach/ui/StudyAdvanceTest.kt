package com.coach.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import com.coach.client.KpStatus
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class StudyAdvanceTest {
    @Test fun alreadyPassedStaysOpenAndNewPassWaitsForGrading() = runComposeUiTest {
        var id by mutableStateOf("a")
        var status by mutableStateOf(KpStatus.PASSED)
        var busy by mutableStateOf(false)
        var next by mutableStateOf<String?>("b")
        val opened = mutableListOf<String?>()
        val onOpen: (String?) -> Unit = { opened.add(it) }
        setContent { AdvanceOnNewPass(id, status, true, next, busy, onOpen) }
        waitForIdle()
        assertTrue(opened.isEmpty(), "opening a passed point must not redirect")
        runOnIdle { status = KpStatus.SOLID; next = null }
        waitForIdle()
        assertTrue(opened.isEmpty(), "the last passed point must remain visible")
        runOnIdle { id = "b"; status = KpStatus.IN_PROGRESS; next = "c" }
        waitForIdle()
        runOnIdle { status = KpStatus.PASSED; busy = true }
        waitForIdle()
        assertTrue(opened.isEmpty(), "wait until the grading stream settles")
        runOnIdle { busy = false }
        waitForIdle()
        assertEquals(listOf("c"), opened)
        runOnIdle { next = null }
        waitForIdle()
        assertEquals(listOf("c"), opened, "advance only once")
    }
}
