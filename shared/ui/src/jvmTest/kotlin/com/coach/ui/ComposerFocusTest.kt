package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performKeyInput
import com.coach.ui.input.ComposerField
import com.coach.ui.input.LocalEnterSends
import androidx.compose.runtime.CompositionLocalProvider
import kotlin.test.*

/** Tapping the composer focuses it (so the keyboard can open), and the field takes text and sends it only through the guarded path. */
@OptIn(ExperimentalTestApi::class)
class ComposerFocusTest {
    @Test fun tappingTheComposerFocusesItAndTypedTextReachesTheDraft() = runComposeUiTest {
        var draft by mutableStateOf(TextFieldValue())
        val sent = mutableListOf<String>()
        setContent { CoachTheme(false) { Box(Modifier.size(420.dp, 200.dp)) { ComposerField(draft, { draft = it }, { sent += it; true }, placeholder = "追问…") } } }
        onNodeWithTag("composer").performClick()
        onNodeWithTag("composer").assertIsFocused()
        onNodeWithTag("composer").performTextInput("你好")
        assertEquals("你好", draft.text)
        assertTrue(sent.isEmpty())
    }

    /** The field's own state carries the IME's composition; the keyboard Enter is read against it (the commit-then-Enter timing is covered by EnterGateTest). */
    @Test fun enterWhileAnImeCompositionIsOpenNeverSendsButAnEnterAfterItClosesDoes() = runComposeUiTest {
        var draft by mutableStateOf(TextFieldValue("nihao", TextRange(5), composition = TextRange(0, 5)))
        val sent = mutableListOf<String>()
        setContent { CoachTheme(false) { CompositionLocalProvider(LocalEnterSends provides true) { Box(Modifier.size(420.dp, 200.dp)) { ComposerField(draft, { draft = it }, { sent += it; true }) } } } }
        val field = onNodeWithTag("composer")
        field.performClick()
        field.performKeyInput { pressKey(Key.Enter) }
        assertTrue(sent.isEmpty(), "Enter during composition sent: $sent")
        assertEquals("nihao", draft.text)
        // Composition closed (the IME committed "你好" and the user pressed Enter again, well after the commit): one send.
        draft = TextFieldValue("你好", TextRange(2))
        Thread.sleep(150)
        field.performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("你好"), sent)
        assertEquals("", draft.text)
    }
}
