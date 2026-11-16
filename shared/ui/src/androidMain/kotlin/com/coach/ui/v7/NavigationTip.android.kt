package com.coach.ui.v7

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun V7NavigationTip(text: String, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier) { content() }
}
