package com.coach.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import coach.design.tokens.CoachV7
import coach.design.tokens.ReadingSizes
import com.coach.ui.v7.*

val LocalV7TextScale = staticCompositionLocalOf<MutableState<Float>> { mutableStateOf(1f) }

@Composable
fun CoachTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val fonts = rememberV7Fonts()
    val reduced = rememberReducedMotion()
    val textScale = rememberSaveable { mutableStateOf(1f) }
    val scale = textScale.value
    val typography = remember(fonts, dark, scale) {
        val leading = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
        val reading = TextStyle(lineHeightStyle = leading, fontFamily = fonts.reading, fontSize = ReadingSizes.body * scale,
            lineHeight = 25.sp * scale,
            fontWeight = if (dark) CoachV7.Weight.bodyDark else CoachV7.Weight.body)
        val ui = TextStyle(lineHeightStyle = leading, fontFamily = fonts.ui, fontSize = ReadingSizes.ui * scale, lineHeight = 18.sp * scale)
        val cap = ui.copy(fontSize = ReadingSizes.caption * scale, lineHeight = 16.sp * scale, fontWeight = CoachV7.Weight.cap, letterSpacing = CoachV7.Tracking.cap)
        val title = reading.copy(fontSize = ReadingSizes.section * scale, lineHeight = 29.sp * scale,
            fontWeight = CoachV7.Weight.title, letterSpacing = CoachV7.Tracking.title)
        Typography(displayLarge = title, displayMedium = title, displaySmall = title,
            headlineLarge = title.copy(fontSize = ReadingSizes.week * scale, lineHeight = 34.sp * scale), headlineMedium = title, headlineSmall = title,
            titleLarge = title,
            titleMedium = reading.copy(fontSize = ReadingSizes.stem * scale, lineHeight = 25.sp * scale, fontWeight = CoachV7.Weight.stem),
            titleSmall = ui.copy(fontSize = ReadingSizes.body * scale, lineHeight = 23.sp * scale, fontWeight = CoachV7.Weight.uiStrong),
            bodyLarge = reading, bodyMedium = reading,
            bodySmall = ui, labelLarge = ui, labelMedium = cap, labelSmall = cap)
    }
    CompositionLocalProvider(LocalV7Fonts provides fonts, LocalV7ReducedMotion provides reduced, LocalV7TextScale provides textScale) {
        com.coach.ui.theme.CoachTheme(dark) {
            MaterialTheme(typography = typography, shapes = Shapes(
                extraSmall = RoundedCornerShape(CoachV7.Radius.s), small = RoundedCornerShape(CoachV7.Radius.btn),
                medium = RoundedCornerShape(CoachV7.Radius.m), large = RoundedCornerShape(CoachV7.Radius.sheet),
                extraLarge = RoundedCornerShape(CoachV7.Radius.sheet)), content = content)
        }
    }
}

@Composable fun successColor(): Color = com.coach.ui.theme.CoachTheme.colors.successFg
