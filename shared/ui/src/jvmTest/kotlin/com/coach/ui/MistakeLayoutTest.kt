package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.coach.client.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MistakeLayoutTest {
    private fun check(width: Int, height: Int) = runDesktopComposeUiTest(width=width,height=height) {
        val mistake=Mistake("a","kp",QuizCard("a","A long original question about uncertainty and asymmetric information. ".repeat(5),
            listOf("A" to "My original answer"),false,"A"),"B · The verified correct answer",
            "The source distinguishes information known before the transaction from risk shared by both parties.")
        var retries=0
        setContent {
            CoachTheme(false) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    MistakeCard(mistake,"Knowledge point",MathAssets(),true) { retries++ }
                }
            }
        }
        onNodeWithText("当时的答案：A · My original answer").performScrollTo().assertIsDisplayed()
        onNodeWithText("正确答案：B · The verified correct answer").performScrollTo().assertIsDisplayed()
        onNodeWithText("再练一组").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1,retries)
        val bounds=onNodeWithText("再练一组").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left>=0 && bounds.right<=width)
    }
    @Test fun outerScreen() = check(360,780)
    @Test fun innerScreen() = check(673,841)
    @Test fun padPortrait() = check(800,1280)
    @Test fun padLandscape() = check(1280,800)
}
