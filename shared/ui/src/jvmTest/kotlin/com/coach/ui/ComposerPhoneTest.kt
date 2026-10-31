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
    @Test fun sendingPreservesTheOriginalWhitespaceAndClearsOnlyAnAcceptedDraft() = runComposeUiTest {
        val original = "  合成问题 β\n下一行  "
        val value = mutableStateOf(TextFieldValue(original))
        val sent = mutableListOf<String>()
        var accept = false
        setContent { MaterialTheme { ComposerField(value.value, { value.value = it }, { sent += it; accept }) } }
        onNodeWithTag("send").performClick()
        assertEquals(original,sent.single())
        assertEquals(original,value.value.text)
        accept = true
        onNodeWithTag("send").performClick()
        assertEquals(listOf(original,original),sent)
        assertEquals("",value.value.text)
    }

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
