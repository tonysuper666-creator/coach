package com.coach.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.client.CourseView
import com.coach.client.TutorClient
import com.coach.client.TutorModel
import com.coach.domain.Event
import com.coach.domain.json
import com.coach.domain.project
import kotlinx.serialization.json.JsonArray
import kotlin.test.*

/**
 * QA-C regression on e0c6d969 (MistakeReviewSmoke.kt:81): with the offline block and the shortcut row above it,
 * the mistake list fell below the first screen of an unfolded Fold. The 错题 tab must show its mistakes first;
 * offline practice and the shortcuts stay reachable further down. Synthetic ledger only.
 */
@OptIn(ExperimentalTestApi::class)
class ReviewMistakeFirstScreenTest {
    private fun view(): CourseView {
        var seq = 0L
        fun event(type: String, payload: kotlinx.serialization.json.JsonObject, tid: String? = "review:C:a"): Event {
            seq++
            return Event(seq, "e$seq", "C", type, recordedAt = seq, occurredAt = seq,
                deviceId = "d", actor = "LEARNER", threadId = tid, payload = payload)
        }
        val events = listOf(
            event("outline_revised", json("ops" to JsonArray(listOf(json("op" to "add", "kpId" to "a", "title" to "Synthetic mistake objective")))), null),
            event("kp_opened", json("kpId" to "a", "title" to "Synthetic mistake objective", "via" to "REVIEW")),
            event("question_posed", json("attemptId" to "a1", "questionId" to "q1", "groupId" to "g1",
                "format" to "SINGLE_CHOICE", "kpIds" to listOf("a"), "stem" to "Synthetic mistake stem",
                "options" to JsonArray(listOf(json("optionId" to "o1", "text" to "one"), json("optionId" to "o2", "text" to "two"))),
                "letterMap" to json("o1" to "A", "o2" to "B"))),
            event("answer_submitted", json("attemptId" to "a1", "answer" to "B")),
            event("attempt_graded", json("attemptId" to "a1", "score" to 0, "max" to 1,
                "status" to "EXACT", "correctAnswer" to "A", "feedback" to "synthetic reason")),
        )
        return CourseView(events, project(events))
    }

    private fun firstScreen(width: Float, height: Float) {
        val view = view()
        assertEquals(1, view.mistakes.items.size)
        val tutor = TutorClient("http://127.0.0.1:29170", "d", "test", TutorModel(wallClock = { 1000L }))
        val ledger = SyntheticLocalLedger("C")
        val c = CoachController("C", ledger.sync, tutor, formatClock = { "" })
        try {
            runSkikoComposeUiTest(size = Size(width, height), density = Density(2f)) {
                setContent { CoachTheme(false) { Surface {
                    ReviewScreen(c, view, { _, _ -> }, {}, onOpenMock = {}, onOpenEssay = {}, onOpenMainline = { _, _ -> })
                } } }
                onNodeWithText("错题 1").assertIsSelected()
                // No scrolling: the mistake itself and its retry button are on the first screen.
                onNodeWithText("Synthetic mistake objective").assertIsDisplayed()
                onNodeWithText("Synthetic mistake stem").assertIsDisplayed()
                onNodeWithText("再练一组").assertIsDisplayed()
                // The rest of the review page is still on this tab, below the list.
                onNodeWithText("离线练习").performScrollTo().assertIsDisplayed()
                onNodeWithTag("open-mock").performScrollTo().assertIsDisplayed()
                onNodeWithTag("essay-entry").performScrollTo().assertIsDisplayed()
                onNodeWithText("今天没有到期的复习。").performScrollTo().assertIsDisplayed()
            }
        } finally { tutor.close(); ledger.close() }
    }

    // Same Fold landscape evidence size as ReviewLayoutTest, and the unfolded portrait it rotates to.
    @Test fun foldLandscapeShowsMistakesFirst() = firstScreen(1812f, 1128f)
    @Test fun foldPortraitShowsMistakesFirst() = firstScreen(1128f, 1812f)
}
