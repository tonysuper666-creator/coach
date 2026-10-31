package com.coach.ui.input

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.MaterialTheme
import com.coach.ui.theme.CoachTheme
import kotlin.time.TimeSource

/** Desktop and hardware keyboards: Enter sends. Phones (soft keyboard): Enter is a newline. Each entry point provides its own answer. */
val LocalEnterSends = staticCompositionLocalOf { false }

/**
 * DESIGN §7.6. Sends only on KeyDown (never KeyUp), never while an IME composition is open, Shift+Enter is a newline.
 * Some desktop IMEs commit the composition and only then deliver the Enter that confirmed it: that Enter arrives with
 * `composition == null` but must still be swallowed, so a composition that ended within [commitGraceMs] still counts as composing.
 */
class EnterGate(private val commitGraceMs: Long = 60L, private val now: () -> Long = defaultClock()) {
    private var lastComposingMs = -1_000_000L

    /** Feed every text edit: whether a composition span existed before and after it. */
    fun onEdit(composingBefore: Boolean, composingAfter: Boolean) {
        if (composingBefore || composingAfter) lastComposingMs = now()
    }

    fun shouldSend(enterSends: Boolean, keyDown: Boolean, shift: Boolean, composingNow: Boolean): Boolean {
        if (composingNow) { lastComposingMs = now(); return false }
        if (!enterSends || !keyDown || shift) return false
        return now() - lastComposingMs > commitGraceMs
    }

    private companion object {
        fun defaultClock(): () -> Long { val origin = TimeSource.Monotonic.markNow(); return { origin.elapsedNow().inWholeMilliseconds } }
    }
}

/**
 * Draft is a [TextFieldValue] owned by the caller (ViewModel), so a fold/unfold, a layout switch or a process restore keeps text, cursor and
 * selection. [onSend] returns true when the message was accepted; only then is the draft cleared (an offline send keeps it).
 */
@Composable
fun ComposerField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: (String) -> Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    sendEnabled: Boolean = true,
    maxLines: Int = 6,
    enterSends: Boolean = LocalEnterSends.current,
    gate: EnterGate = remember { EnterGate() },
) {
    val colors = CoachTheme.colors
    val currentValue by rememberUpdatedState(value)
    fun send(): Boolean {
        val text = currentValue.text.trim()
        if (text.isEmpty() || !onSend(text)) return false
        onValueChange(TextFieldValue())
        return true
    }
    Row(modifier.fillMaxWidth().background(colors.surface1, RoundedCornerShape(24.dp)).border(1.dp, colors.borderStrong, RoundedCornerShape(24.dp)).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (value.text.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = colors.fgSecondary, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value = value,
                onValueChange = { new -> gate.onEdit(currentValue.composition != null, new.composition != null); onValueChange(new) },
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.fgPrimary),
                cursorBrush = SolidColor(colors.actionPrimary),
                maxLines = maxLines,
                modifier = Modifier.fillMaxWidth().testTag("composer").onPreviewKeyEvent { event ->
                    if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                    val sends = gate.shouldSend(enterSends, event.type == KeyEventType.KeyDown, event.isShiftPressed, currentValue.composition != null)
                    if (sends) { send(); true } else false
                },
            )
        }
        TextButton(onClick = { send() }, enabled = enabled && sendEnabled && value.text.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("send")) { Text("发送") }
    }
}
