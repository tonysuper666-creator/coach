package com.coach.ui

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.coach.client.*
import com.coach.domain.Event
import com.coach.domain.json
import com.coach.domain.project
import kotlinx.serialization.json.JsonArray
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ShortStudyLayoutTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> unused(clazz: Class<T>): T {
        val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return (f.get(null) as sun.misc.Unsafe).allocateInstance(clazz) as T
    }

    @Test fun shortViewportKeepsReadingAreaAndActionsVisible() = runDesktopComposeUiTest(width = 320, height = 410) {
        fun event(seq: Long, type: String, payload: kotlinx.serialization.json.JsonObject) =
            Event(seq, "e$seq", "C", type, recordedAt = seq, occurredAt = seq, deviceId = "d", actor = "LEARNER", payload = payload)
        val events = listOf(
            event(1, "outline_revised", json("ops" to JsonArray(listOf(json("kpId" to "a", "title" to "【W2】合成知识点"))))),
            event(2, "study_scope_set", json("week" to 2, "kpIds" to listOf("a"))),
        )
        val view = CourseView(events, project(events))
        val kp = assertNotNull(view.weekTarget)
        val tutor = TutorClient("http://127.0.0.1:29169", "d", "test", TutorModel(wallClock = { 1000L }))
        val controller = CoachController("C", unused(SyncEngine::class.java), tutor, formatClock = { "" })
        val message = TutorMessage("m", "synthetic", false, "第一行讲解。\n第二行讲解。\n第三行讲解。", status = "complete")
        val ui = TutorUiState(threads = mapOf("kp:a" to ThreadView(items = listOf(ChatItem.Tutor(message)))))
        try {
            setContent { CoachTheme(false) {
                val actions = WeekActions(controller, rememberCoroutineScope())
                StudyScreen(controller, kp, view, ui, false, {}, onOpenReference = {}, weekActions = actions)
            } }
            onNodeWithText("详情 ▾").assertIsDisplayed()
            onNodeWithText("下一知识点").assertIsDisplayed()
            onNodeWithText("第三行讲解。", substring = true).assertIsDisplayed()
            assertTrue(onNodeWithTag("study-reading").getUnclippedBoundsInRoot().height >= 100.dp,
                "the reading area should fit several lines on a short viewport")
            onNodeWithText("详情 ▾").performClick()
            onNodeWithText("收起详情 ▴").assertIsDisplayed().performClick()
            onNodeWithText("详情 ▾").assertIsDisplayed()
        } finally { tutor.close() }
    }

    @Test fun continueCardNamesTheUnfinishedPointOnlyOnce() = runDesktopComposeUiTest(width = 460, height = 300) {
        val kp = KpRow("a", "【W2】合成知识点二", KpStatus.IN_PROGRESS, true, emptyList())
        var clicked = false
        setContent { CoachTheme(false) { ContinueCard(kp, true) { clicked = true } } }
        onNodeWithText("继续学习").assertIsDisplayed()
        onNodeWithText("合成知识点二（没学完）").assertIsDisplayed().performClick()
        onNodeWithText("回到（没学完）").assertDoesNotExist()
        assertTrue(clicked)
    }
}
