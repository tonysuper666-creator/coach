package com.coach.ui.v7

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal expect fun V7NavigationTip(text: String, modifier: Modifier, content: @Composable () -> Unit)
