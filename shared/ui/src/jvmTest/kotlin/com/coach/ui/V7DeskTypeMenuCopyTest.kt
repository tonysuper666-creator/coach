package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.coach.ui.adaptive.WindowSpec
import com.coach.ui.v7.Desk
import com.coach.ui.v7.LocalDeskWindow
import kotlin.test.Test
import kotlin.test.assertEquals

/** The top-bar text-scale control is labelled with a word, not the placeholder "Aa". */
@OptIn(ExperimentalTestApi::class)
class V7DeskTypeMenuCopyTest {
    @Test fun theTextScaleControlIsNamedAndStillScalesTheText() {
        runDesktopComposeUiTest(1600, 1000) {
            val scale = mutableStateOf(1f)
            setContent {
                CoachTheme(false) {
                    CompositionLocalProvider(
                        LocalV7TextScale provides scale,
                        LocalDeskWindow provides WindowSpec(1500f, 900f),
                    ) {
                        Box(Modifier.size(1500.dp, 900.dp)) {
                            Desk("合成题目", directory = { Text("合成周目录") }, margin = { Text("合成出处") }) {
                                Text("合成正文")
                            }
                        }
                    }
                }
            }
            onNodeWithTag("v7-type-menu").assertTextEquals("字号")
            onNodeWithText("Aa").assertDoesNotExist()
            onNodeWithTag("v7-type-menu").performClick()
            onNodeWithTag("v7-type-大").performClick()
            assertEquals(1.15f, scale.value)
        }
    }
}
