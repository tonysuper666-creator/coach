package com.coach.ui.v7

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily

/** Latin first, CJK second, then the platform's complete system fallback. */
data class V7Fonts(val reading: FontFamily, val ui: FontFamily, val note: FontFamily, val numbers: FontFamily, val display: FontFamily = reading)
val LocalV7Fonts = staticCompositionLocalOf { V7Fonts(FontFamily.Serif, FontFamily.SansSerif, FontFamily.Serif, FontFamily.Monospace) }
@Composable internal expect fun rememberV7Fonts(): V7Fonts
@Composable internal expect fun rememberReducedMotion(): Boolean
