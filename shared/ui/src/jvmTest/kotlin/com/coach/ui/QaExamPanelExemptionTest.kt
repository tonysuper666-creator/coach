package com.coach.ui

import androidx.compose.ui.test.*
import com.coach.client.*
import com.coach.domain.*
import kotlinx.serialization.json.JsonArray
import kotlin.test.*

/** Synthetic regression for the already-merged zero-point midterm metadata. */
@OptIn(ExperimentalTestApi::class)
class QaExamPanelExemptionTest {
    @Test fun panelMustShowExplicitMidtermExemptionInsteadOfMissingOutline() {
        val outline = Event(1, "outline", "SYN1251", "outline_revised", recordedAt = 1,
            occurredAt = 1, deviceId = "qa", actor = "SYSTEM", payload = json(
                "knowledgePoints" to JsonArray(listOf(json("id" to "a", "title" to "Synthetic point", "week" to 1))),
                "week7" to json("knowledgePoints" to 0, "reason" to "Week 7 is Midterm Exam; no new lecture.")
            ))
        val view = CourseView(listOf(outline), project(listOf(outline)))
        assertEquals(setOf(7), view.midtermExemptionWeeks)
        val ledger = SyntheticLocalLedger("SYN1251")
        val tutor = TutorClient("http://127.0.0.1:18959", "qa", "synthetic", TutorModel({ 1_800_000_000_000L }))
        try {
            runComposeUiTest {
                val c = CoachController("SYN1251", ledger.sync, tutor, formatClock = { "" })
                setContent { CoachTheme(false) { ExamPanelCard(c, view,
                    ExamFocus(listOf(WeekFocus(1, 1.0), WeekFocus(7, 0.0))), { _, _ -> }, {}) } }
                onNodeWithText("第 7 周").assertExists()
                onNodeWithText("课程里还没有这周的节").assertDoesNotExist()
                onNodeWithText("期中周", substring = true).assertExists()
            }
        } finally { tutor.close(); ledger.close() }
    }
}
