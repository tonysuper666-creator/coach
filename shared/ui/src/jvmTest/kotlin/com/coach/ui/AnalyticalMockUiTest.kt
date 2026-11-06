package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.coach.domain.json
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class AnalyticalMockUiTest {
    @Test fun finalFieldExplainsMissingNonNumericPrecisionAndValidInputImmediately() = runComposeUiTest {
        setContent {
            var value by remember { mutableStateOf("") }
            CoachTheme(false) { Box(Modifier.size(360.dp, 240.dp)) { AnalyticalFinalInput("A#a", value) { value = it } } }
        }
        onNodeWithText("未填写最终数值", substring = true).assertExists()
        val field = onNodeWithTag("mock-final:A#a")
        field.performTextReplacement("NaN")
        onNodeWithText("请输入十进制数", substring = true).assertExists()
        field.performTextReplacement("1.0")
        onNodeWithText("须保留两位小数", substring = true).assertExists()
        field.performTextReplacement("1.00")
        onNodeWithText("格式正确", substring = true).assertExists()
        field.assertTextContains("1.00")
    }

    @Test fun figureAndWorkStackOnPhoneAndCompareAcrossOnTabletWithoutLosingWork() = runComposeUiTest {
        var wide by mutableStateOf(false)
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 160"><path d="M20 10 V140 H300" fill="none" stroke="black"/><text x="40" y="40">Synthetic</text></svg>"""
        setContent {
            CoachTheme(false) { Box(Modifier.size(if (wide) 900.dp else 360.dp, 600.dp)) {
                AnalyticalComparison(json("text" to "Q = 5 + 5; P = 1.00", "final" to "1.00"), svg)
            } }
        }
        onNodeWithText("Q = 5 + 5; P = 1.00").assertIsDisplayed()
        val phoneWork = onNodeWithTag("mock-submitted-work").getUnclippedBoundsInRoot()
        val phoneFigure = onNodeWithTag("mock-standard-figure").getUnclippedBoundsInRoot()
        assertTrue(phoneFigure.top >= phoneWork.bottom, "phone comparison must not overlap")
        runOnIdle { wide = true }
        val tabletWork = onNodeWithTag("mock-submitted-work").getUnclippedBoundsInRoot()
        val tabletFigure = onNodeWithTag("mock-standard-figure").getUnclippedBoundsInRoot()
        assertTrue(tabletFigure.left >= tabletWork.right, "wide comparison is side by side")
        assertEquals(tabletWork.top, tabletFigure.top)
        onNodeWithText("最终数值：1.00").assertIsDisplayed()
        onNodeWithText("标准图 · 交卷后对照").assertIsDisplayed()
    }
}
