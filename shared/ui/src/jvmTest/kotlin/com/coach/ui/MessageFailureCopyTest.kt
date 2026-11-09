package com.coach.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import com.coach.client.TutorMessage
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class MessageFailureCopyTest {
    @Test fun failureNoticesExplainTheProblemWithoutExposingReasonCodes() = runComposeUiTest {
        val message = mutableStateOf(TutorMessage(
            id = "synthetic-failure", model = "claude-sonnet-5-5", fallback = true,
            status = "failed", reason = "cli_exit"
        ))
        val math = MathAssets()
        setContent { MaterialTheme { TutorBubble(message.value, math, {}) } }
        val reasons = mapOf(
            "cli_exit" to "回答意外中断",
            "timeout" to "等待回答超时",
            "max_output_tokens" to "这次回答达到长度上限",
            "unknown_internal_reason" to "暂时没法确认原因",
            "rate_limited" to "Claude 这会儿忙，正在换一个模型接着答",
            "exam_protection" to "考前保护时段，这段时间先不答"
        )
        for ((code, explanation) in reasons) {
            runOnIdle { message.value = message.value.copy(reason = code) }
            onNodeWithText("这条没有生成出来（$explanation）。").assertIsDisplayed()
            onAllNodesWithText(code, substring = true).assertCountEquals(0)
        }
        onNodeWithText("导师 · claude-sonnet-5-5").assertIsDisplayed()
    }
}
