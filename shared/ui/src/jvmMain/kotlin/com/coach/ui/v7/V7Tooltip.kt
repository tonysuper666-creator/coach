package com.coach.ui.v7

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme

/** A short, supplemental label. Keep essential instructions in the page itself. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun V7Tooltip(text: String, modifier: Modifier = Modifier, shortcut: String? = null,
    content: @Composable () -> Unit) {
    val c = CoachTheme.colors
    TooltipArea(modifier = modifier, delayMillis = 500, tooltip = {
        val shape = RoundedCornerShape(CoachV7.Radius.s)
        Row(Modifier.background(c.surface1, shape).border(1.dp, c.borderSubtle, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, color = c.fgPrimary, fontFamily = LocalV7Fonts.current.ui, fontSize = CoachV7.Size.ui)
            if (shortcut != null) Text(shortcut, color = c.fgSecondary,
                fontFamily = LocalV7Fonts.current.numbers, fontSize = CoachV7.Size.ui)
        }
    }, content = content)
}

@Composable
internal actual fun V7NavigationTip(text: String, modifier: Modifier, content: @Composable () -> Unit) =
    V7Tooltip(text, modifier, content = content)
