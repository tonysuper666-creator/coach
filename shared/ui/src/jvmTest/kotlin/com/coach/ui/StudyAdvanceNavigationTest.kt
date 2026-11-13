package com.coach.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import com.coach.client.*
import com.coach.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import kotlin.test.*

/** Exercise the actual StudyScreen and its navigation callback, without a server or device. */
@OptIn(ExperimentalTestApi::class)
class StudyAdvanceNavigationTest {
    private val ledgers = mutableListOf<SyntheticLocalLedger>()
    @AfterTest fun closeLedgers() { ledgers.forEach { it.close() } }

    private fun view(passed: Boolean): CourseView {
        val event = Event(1, "outline", "C", "outline_revised", recordedAt = 1, occurredAt = 1, deviceId = "d", actor = "LEARNER", payload = json(
            "ops" to JsonArray(listOf(json("op" to "add", "kpId" to "a", "title" to "【W1】知识点甲")))))
        val events = listOf(event)
        return CourseView(events, project(events).copy(
            studyScope = StudyScope("scope", 1, listOf("a")),
            mastery = if (passed) mapOf("a" to Mastery(passed = true)) else emptyMap(),
        ))
    }

    @Suppress("UNCHECKED_CAST")
    private fun unusedSync(): SyncEngine {
        // CoachController reads device-local mainline state on construction: use a real in-memory ledger.
        return SyntheticLocalLedger("C").also { ledgers += it }.sync
    }

    @Test fun openingPreviouslyPassedPointKeepsItsStudyScreenVisible() = runDesktopComposeUiTest(width = 390, height = 800) {
        val tutor = TutorClient("http://127.0.0.1:12345", "d", "token", TutorModel({ 0 }))
        val controller = CoachController("C", unusedSync(), tutor, formatClock = { "" })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val actions = WeekActions(controller, scope)
        var selected by mutableStateOf<String?>(null)
        actions.onOpen = { selected = it }
        val course = view(passed = true)
        try {
            setContent { CoachTheme(false) {
                if (selected == null) Button(onClick = { selected = "a" }) { Text("打开已通过知识点") }
                else StudyScreen(controller, course.kp("a")!!, course, TutorUiState(), false, {}, weekActions = actions)
            } }
            onNodeWithText("打开已通过知识点").performClick()
            waitForIdle()
            onNodeWithText("知识点甲").assertIsDisplayed()
            onNodeWithText("打开已通过知识点").assertDoesNotExist()
        } finally { scope.cancel(); tutor.close() }
    }

    @Test fun newPassAdvancesOnlyAfterGradingSettles() = runDesktopComposeUiTest(width = 390, height = 800) {
        val tutor = TutorClient("http://127.0.0.1:12345", "d", "token", TutorModel({ 0 }))
        val controller = CoachController("C", unusedSync(), tutor, formatClock = { "" })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val actions = WeekActions(controller, scope)
        val opened = mutableListOf<String?>()
        actions.onOpen = { opened.add(it) }
        var course by mutableStateOf(view(passed = false))
        var busy by mutableStateOf(false)
        try {
            setContent { CoachTheme(false) {
                val state = TutorUiState(threads = mapOf("kp:a" to ThreadView(tutorState = if (busy) "GRADE" else "IDLE")))
                StudyScreen(controller, course.kp("a")!!, course, state, false, {}, weekActions = actions)
            } }
            onNodeWithText("知识点甲").assertIsDisplayed()
            assertTrue(opened.isEmpty(), "opening an unpassed point must not navigate")
            runOnIdle { busy = true; course = view(passed = true) }
            waitForIdle()
            assertTrue(opened.isEmpty(), "do not leave during grading")
            runOnIdle { busy = false }
            waitForIdle()
            assertEquals(listOf<String?>(null), opened, "last new pass exits to the week summary")
            runOnIdle { busy = true }; runOnIdle { busy = false }; waitForIdle()
            assertEquals(listOf<String?>(null), opened, "advance only once")
        } finally { scope.cancel(); tutor.close() }
    }
}
