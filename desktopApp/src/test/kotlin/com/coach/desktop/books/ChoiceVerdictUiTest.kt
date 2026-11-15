package com.coach.desktop.books

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.coach.ui.CoachTheme
import com.coach.ui.theme.CoachColors
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChoiceVerdictUiTest {
    @Test fun darkChoicesShowVerdicts() = exerciseChoices(dark = true)
    @Test fun lightChoicesShowVerdicts() = exerciseChoices(dark = false)

    private fun exerciseChoices(dark: Boolean) = runComposeUiTest {
        val colors = if (dark) CoachColors.dark() else CoachColors.light()
        var answer by mutableStateOf(PracticeAnswer())
        val practice = Practice("Choose one", "Reference", "Right", listOf("Right", "Wrong", "Other"))
        setContent {
            CoachTheme(dark) {
                Column(Modifier.width(500.dp)) {
                    SourcePractice("lesson", QuestionMode.CHOICE, practice, answer, { answer = it }, {}, true, FocusRequester())
                }
            }
        }
        fun background(tag: String): androidx.compose.ui.graphics.Color {
            val pixels = onNodeWithTag(tag).captureToImage().toPixelMap()
            return pixels[5, pixels.height / 2]
        }
        fun assertColor(expected: androidx.compose.ui.graphics.Color, actual: androidx.compose.ui.graphics.Color) {
            // Compare quantized screen colors, not floating-point color representations.
            assertEquals(expected.toArgb(), actual.toArgb())
        }
        onNodeWithTag("choice-0").performClick()
        onNodeWithText("正确").assertExists()
        assertColor(colors.successBg, background("choice-0"))
        val unselected = background("choice-2")
        val textL = colors.fgPrimary.luminance()
        val bgL = unselected.luminance()
        assertTrue((max(textL, bgL) + 0.05f) / (min(textL, bgL) + 0.05f) >= 3f)
        onNodeWithText("重新作答").performClick()
        onNodeWithTag("choice-1").performClick()
        onNodeWithText("你选的").assertExists()
        assertColor(colors.dangerBg, background("choice-1"))
        onNodeWithTag("choice-2").assertIsNotEnabled()
    }
}
