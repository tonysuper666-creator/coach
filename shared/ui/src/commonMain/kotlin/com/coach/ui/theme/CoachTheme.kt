package com.coach.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val LocalCoachColors = staticCompositionLocalOf { CoachColors.light() }

object CoachTheme {
    val colors: CoachColors @Composable @ReadOnlyComposable get() = LocalCoachColors.current
}

/** Minimum touch target for answer options (DESIGN: 选择题选项点按区 ≥56dp). */
val OptionMinHeight = 56.dp

private fun CoachColors.toMaterial(): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = actionPrimary, onPrimary = onAccent, primaryContainer = accentSoft, onPrimaryContainer = fgPrimary,
        secondary = accentDeep, onSecondary = onAccent, secondaryContainer = surface2, onSecondaryContainer = fgPrimary,
        tertiary = infoFg, onTertiary = infoBg, tertiaryContainer = infoBg, onTertiaryContainer = infoFg,
        background = bgCanvas, onBackground = fgPrimary,
        surface = surface1, onSurface = fgPrimary, surfaceVariant = surface2, onSurfaceVariant = fgSecondary,
        surfaceTint = Color.Transparent, surfaceContainerLowest = bgCanvas, surfaceContainerLow = bgCanvas, surfaceContainer = surface1,
        surfaceContainerHigh = surface2, surfaceContainerHighest = bgCanvasDeep, inverseSurface = fgPrimary, inverseOnSurface = inverse,
        outline = borderStrong, outlineVariant = borderSubtle, error = actionDanger, onError = onAccent, errorContainer = dangerBg, onErrorContainer = dangerFg,
    )
}

/** Light/dark follow the system; dynamic colour stays off so both schemes are the designed ones. */
@Composable
fun CoachTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = remember(darkTheme) { if (darkTheme) CoachColors.dark() else CoachColors.light() }
    CompositionLocalProvider(LocalCoachColors provides colors) {
        MaterialTheme(colorScheme = remember(colors) { colors.toMaterial() }, content = content)
    }
}
