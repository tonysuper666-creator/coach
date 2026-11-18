package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coach.design.tokens.CoachV7
import com.coach.ui.LocalV7TextScale
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.v7FocusRing

private object BelowAa : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset = IntOffset(
        (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        anchorBounds.bottom.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
    )
}

@Composable
internal fun ReadingSettingsPanel(settings: ReadingSettings, isOriginal: () -> Boolean,
    extra: @Composable (() -> Unit) -> Unit,
    viewRow: (@Composable () -> Unit)? = null,
    readingModeRow: (@Composable () -> Unit)? = null,
    layoutRow: (@Composable () -> Unit)? = null,
    pageTurnRow: (@Composable () -> Unit)? = null,
) {
    // Desk sits inside the workspace theme. This is the scale that also drives page measurement.
    val textScale = LocalV7TextScale.current
    val preferredScale = settings.size.scale
    SideEffect { textScale.value = preferredScale }
    val aa = remember { FocusRequester() }
    var open by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf(false) }
    val close = { open = false; restoreFocus = true }
    LaunchedEffect(open) {
        if (!open && restoreFocus) {
            aa.requestFocus()
            restoreFocus = false
        }
    }
    val colors = CoachTheme.colors
    Text("Aa", Modifier.focusRequester(aa).then(if (open) Modifier else Modifier.v7FocusRing()).clickable {
        original = isOriginal()
        open = true
    }.testTag("v7-type-menu"), style = MaterialTheme.typography.labelLarge, color = colors.fgSecondary)
    if (!open) return
    Popup(popupPositionProvider = BelowAa, onDismissRequest = close, properties = PopupProperties(focusable = true)) {
        val shape = RoundedCornerShape(CoachV7.Radius.m)
        val sizes = remember { ReadingSize.entries.map { FocusRequester() } }
        val fonts = remember { ReadingFont.entries.map { FocusRequester() } }
        val papers = remember { PaperColor.entries.map { FocusRequester() } }
        fun focusSize() = sizes[settings.size.ordinal].requestFocus()
        fun focusFont() = fonts[settings.font.ordinal].requestFocus()
        fun focusPaper() = papers[settings.paper.ordinal].requestFocus()
        Column(Modifier.width(360.dp).clip(shape)
            .background(if (colors.dark) CoachV7.Dark.menuBg else CoachV7.Light.menuBg)
            .border(1.dp, if (colors.dark) CoachV7.Dark.menuSep else CoachV7.Light.menuSep, shape)
            .padding(CoachV7.Space.s4).testTag("reading-settings-panel")) {
            extra(close)
            viewRow?.invoke()
            readingModeRow?.invoke()
            layoutRow?.invoke()
            pageTurnRow?.invoke()
            if (viewRow != null || readingModeRow != null || layoutRow != null || pageTurnRow != null) {
                HorizontalDivider(color = if (colors.dark) CoachV7.Dark.menuSep else CoachV7.Light.menuSep)
            }
            SettingRow("字号", "size", ReadingSize.entries, settings.size, { it.label }, sizes, !original,
                previousRow = { focusPaper() }, nextRow = { focusFont() }, choose = settings::chooseSize)
            if (original) Text("原版按原书大小显示", style = MaterialTheme.typography.labelSmall, color = colors.fgTertiary)
            SettingRow("正文字体", "font", ReadingFont.entries, settings.font, { it.label }, fonts, !original,
                previousRow = { focusSize() }, nextRow = { focusPaper() }, choose = settings::chooseFont)
            SettingRow("纸色", "paper", PaperColor.entries, settings.paper, { it.label }, papers, true,
                previousRow = { if (!original) focusFont() }, nextRow = { if (!original) focusSize() },
                choose = settings::choosePaper)
        }
        LaunchedEffect(Unit) { if (original) focusPaper() else focusSize() }
    }
}

@Composable
private fun <T : Enum<T>> SettingRow(title: String, tag: String, values: List<T>, selected: T, label: (T) -> String,
    focus: List<FocusRequester>, enabled: Boolean, previousRow: () -> Unit, nextRow: () -> Unit, choose: (T) -> Unit) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.s)
    Row(Modifier.fillMaxWidth().height(40.dp).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall.copy(fontSize = CoachV7.Size.cap),
            color = colors.fgTertiary)
        for ((i, value) in values.withIndex()) {
            val active = value == selected
            Text(label(value), Modifier.focusRequester(focus[i]).onPreviewKeyEvent { event ->
                if (event.key !in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) {
                    return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionUp -> previousRow()
                        Key.DirectionDown -> nextRow()
                        else -> {
                            val next = (i + if (event.key == Key.DirectionLeft) -1 else 1).coerceIn(values.indices)
                            choose(values[next])
                            focus[next].requestFocus()
                        }
                    }
                }
                true
            }.v7FocusRing(shape).clip(shape).background(if (!active || !enabled) Color.Transparent
                else if (colors.dark) CoachV7.Dark.pineWash else CoachV7.Light.pineWash)
                .selectable(active, enabled = enabled, role = Role.RadioButton) {
                    choose(value)
                    focus[i].requestFocus()
                }
                .testTag("reading-$tag-${value.name}").padding(horizontal = CoachV7.Space.s3, vertical = CoachV7.Space.s2),
                style = MaterialTheme.typography.labelLarge,
                color = if (!enabled) colors.fgTertiary else if (active) colors.fgPrimary else colors.fgSecondary)
        }
    }
}
