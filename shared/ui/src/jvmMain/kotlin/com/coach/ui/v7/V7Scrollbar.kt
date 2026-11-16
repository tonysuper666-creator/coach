package com.coach.ui.v7

import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme

/** Fixed hit area prevents layout shifts as the thumb widens from 4dp to 8dp. */
@Composable
fun V7Scrollbar(adapter: ScrollbarAdapter, modifier: Modifier = Modifier) {
    val c = CoachTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(modifier.width(12.dp).hoverable(interaction), contentAlignment = Alignment.Center) {
        VerticalScrollbar(adapter, Modifier.fillMaxHeight(), interactionSource = interaction,
            style = ScrollbarStyle(minimalHeight = 24.dp, thickness = if (hovered) 8.dp else 4.dp,
                shape = RoundedCornerShape(4.dp), hoverDurationMillis = 0,
                unhoverColor = c.fgTertiary, hoverColor = c.fgSecondary))
    }
}
