package com.coach.ui.v7

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
internal actual fun Modifier.v7ButtonSurface(base: Color, interaction: MutableInteractionSource,
    enabled: Boolean, primary: Boolean): Modifier = background(base)

@Composable
internal actual fun Modifier.v7ButtonClick(interaction: MutableInteractionSource,
    enabled: Boolean, onClick: () -> Unit): Modifier =
    clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
