package com.coach.ui.v7

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme

@Composable
internal actual fun Modifier.v7ButtonSurface(base: Color, interaction: MutableInteractionSource,
    enabled: Boolean, primary: Boolean): Modifier {
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val c = CoachTheme.colors
    val wash = if (primary) c.borderSubtle.copy(alpha = if (pressed) (if (c.dark) .30f else .08f) else .16f)
        else if (pressed) (if (c.dark) CoachV7.Dark.selBg else CoachV7.Light.selBg)
        else (if (c.dark) CoachV7.Dark.zhuWash else CoachV7.Light.zhuWash)
    return background(if (enabled && (hovered || pressed)) wash.compositeOver(base) else base)
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
}

@Composable
internal actual fun Modifier.v7ButtonClick(interaction: MutableInteractionSource,
    enabled: Boolean, onClick: () -> Unit): Modifier =
    clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
