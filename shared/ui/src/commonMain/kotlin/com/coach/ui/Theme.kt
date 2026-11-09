package com.coach.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import coach.design.tokens.CoachV7
import com.coach.ui.v7.*

@Composable
fun CoachTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val fonts = rememberV7Fonts()
    val reduced = rememberReducedMotion()
    val typography = remember(fonts, dark) {
        val reading = TextStyle(fontFamily = fonts.reading, fontSize = CoachV7.Size.body,
            lineHeight = CoachV7.Size.body * CoachV7.LineHeight.body,
            fontWeight = if (dark) CoachV7.Weight.bodyDark else CoachV7.Weight.body)
        val ui = TextStyle(fontFamily = fonts.ui, fontSize = CoachV7.Size.ui, lineHeight = 20.sp)
        val cap = ui.copy(fontSize = CoachV7.Size.cap, lineHeight = 18.sp, fontWeight = CoachV7.Weight.cap, letterSpacing = CoachV7.Tracking.cap)
        val title = reading.copy(fontSize = CoachV7.Size.title, lineHeight = CoachV7.Size.title * CoachV7.LineHeight.title,
            fontWeight = CoachV7.Weight.title, letterSpacing = CoachV7.Tracking.title)
        Typography(displayLarge = title, displayMedium = title, displaySmall = title,
            headlineLarge = title, headlineMedium = title, headlineSmall = title.copy(fontSize = 28.sp, lineHeight = 36.4.sp),
            titleLarge = title.copy(fontSize = 24.sp, lineHeight = 32.sp),
            titleMedium = reading.copy(fontSize = CoachV7.Size.stem, lineHeight = 30.sp, fontWeight = CoachV7.Weight.stem),
            titleSmall = ui.copy(fontSize = CoachV7.Size.note, lineHeight = 24.sp, fontWeight = CoachV7.Weight.uiStrong),
            bodyLarge = reading, bodyMedium = reading.copy(fontSize = CoachV7.Size.note, lineHeight = 24.75.sp),
            bodySmall = ui, labelLarge = ui, labelMedium = cap, labelSmall = cap)
    }
    CompositionLocalProvider(LocalV7Fonts provides fonts, LocalV7ReducedMotion provides reduced) {
        com.coach.ui.theme.CoachTheme(dark) {
            MaterialTheme(typography = typography, shapes = Shapes(
                extraSmall = RoundedCornerShape(CoachV7.Radius.s), small = RoundedCornerShape(CoachV7.Radius.btn),
                medium = RoundedCornerShape(CoachV7.Radius.m), large = RoundedCornerShape(CoachV7.Radius.sheet),
                extraLarge = RoundedCornerShape(CoachV7.Radius.sheet)), content = content)
        }
    }
}

@Composable fun successColor(): Color = com.coach.ui.theme.CoachTheme.colors.successFg
