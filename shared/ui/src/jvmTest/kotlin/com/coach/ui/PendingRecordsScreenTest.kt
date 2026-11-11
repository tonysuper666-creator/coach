package com.coach.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.coach.client.*
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PendingRecordsScreenTest {
    private fun render(pending: Boolean, score: Int) = runComposeUiTest {
        val detail = AttemptDetail("a", listOf("kp"), "SINGLE_CHOICE", "PRACTICE", "Synthetic orchard",
            listOf("A" to "Pear", "B" to "Fig"), "A", "", 1_000L,
            GradeView("EXACT", score, 100, "Synthetic explanation", emptyList(), emptyList(), ""), null,
            voided = false, hinted = false, pendingKey = pending)
        setContent {
            CoachTheme(false) {
                Column(Modifier.width(420.dp).verticalScroll(rememberScrollState())) {
                    AttemptCard(MathAssets(), false, { null }, 1, 1, detail)
                }
            }
        }
        waitForIdle()
        if (pending) {
            onNodeWithText("答案待核对，不计分").assertExists()
            onNodeWithText("你的选择", useUnmergedTree = true).assertExists()
            for (label in listOf("答对", "答错", "✓", "✗", "%", "选择题按选项对答案。"))
                onAllNodesWithText(label, substring = true, useUnmergedTree = true).assertCountEquals(0)
        } else {
            onNodeWithText(if (score == 100) "答对" else "答错").assertExists()
            onNodeWithText(if (score == 100) "你的选择 ✓" else "你的选择 ✗", useUnmergedTree = true).assertExists()
            onNodeWithText("答案待核对，不计分").assertDoesNotExist()
        }
    }

    @Test fun pendingCorrectGradeHasOnlyNeutralLabels() = render(true, 100)
    @Test fun pendingIncorrectGradeHasOnlyNeutralLabels() = render(true, 0)
    @Test fun ordinaryCorrectGradeStillShowsJudgment() = render(false, 100)
    @Test fun ordinaryIncorrectGradeStillShowsJudgment() = render(false, 0)
}
