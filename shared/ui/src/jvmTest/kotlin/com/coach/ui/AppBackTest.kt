package com.coach.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AppBackTest {
    @Test fun systemBackClosesSourceThenStudyAndReturnsToToday() = runComposeUiTest {
        var source by mutableStateOf(true)
        var review by mutableStateOf(false)
        var study by mutableStateOf(true)
        var tab by mutableStateOf(AppTab.ROUTE)
        var enabled = false
        var dispatch: () -> Unit = {}
        setContent {
            AppBackHandler(source, review, study, { source = false }, { review = false },
                { study = false; tab = AppTab.TODAY }) { e, action ->
                SideEffect { enabled = e; dispatch = action }
            }
        }
        waitForIdle()
        assertTrue(enabled)
        runOnIdle { dispatch() }; waitForIdle()
        assertFalse(source); assertTrue(study); assertEquals(AppTab.ROUTE, tab)
        runOnIdle { dispatch() }; waitForIdle()
        assertFalse(study); assertEquals(AppTab.TODAY, tab); assertFalse(enabled)
        runOnIdle { review = true }; waitForIdle()
        assertTrue(enabled)
        runOnIdle { dispatch() }; waitForIdle()
        assertFalse(review); assertFalse(enabled)
    }
}
