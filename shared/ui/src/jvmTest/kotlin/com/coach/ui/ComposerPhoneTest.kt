package com.coach.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.*
import com.coach.ui.input.ComposerField
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ComposerPhoneTest {
    @Test fun phoneEnterAddsOneNewlineAndDoesNotSend() = runComposeUiTest {
        val value = mutableStateOf(TextFieldValue("第一行", TextRange(3)))
        var sent = 0
        setContent { MaterialTheme { ComposerField(value.value, { value.value = it }, { sent++; true }) } }
        onNodeWithTag("composer").performClick().performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        waitForIdle()
        assertEquals(0, sent)
        assertEquals("第一行\n", value.value.text)
    }
}
