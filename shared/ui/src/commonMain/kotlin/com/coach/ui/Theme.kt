package com.coach.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BDB), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF), onPrimaryContainer = Color(0xFF0E1F6B),
    secondary = Color(0xFF55608F), secondaryContainer = Color(0xFFE2E7FA), onSecondaryContainer = Color(0xFF121C45),
    tertiary = Color(0xFF8A5A00), tertiaryContainer = Color(0xFFFFE7BE),
    background = Color(0xFFFAFAFD), onBackground = Color(0xFF1B1C22),
    surface = Color(0xFFFAFAFD), onSurface = Color(0xFF1B1C22),
    surfaceVariant = Color(0xFFE6E8F0), onSurfaceVariant = Color(0xFF4A4D5C),
    outline = Color(0xFF767A8C), outlineVariant = Color(0xFFC8CBD8),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFFBE0DC), onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB5C4FF), onPrimary = Color(0xFF0B2487),
    primaryContainer = Color(0xFF2D45B0), onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFFBCC5F2), secondaryContainer = Color(0xFF2F3758), onSecondaryContainer = Color(0xFFDDE3FF),
    tertiary = Color(0xFFFFC76B), tertiaryContainer = Color(0xFF5A3C00),
    background = Color(0xFF121318), onBackground = Color(0xFFE4E2EA),
    surface = Color(0xFF121318), onSurface = Color(0xFFE4E2EA),
    surfaceVariant = Color(0xFF2B2D38), onSurfaceVariant = Color(0xFFC6C8D8),
    outline = Color(0xFF8F93A6), outlineVariant = Color(0xFF454859),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF7A1A14), onErrorContainer = Color(0xFFFFDAD6),
)

/** 阅读为主的排版：正文 17sp / 27sp，题干与选项同一档，Fold8 外屏单手握持时也不挤。 */
private val CoachTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontSize = 30.sp, lineHeight = 38.sp),
        titleLarge = base.titleLarge.copy(fontSize = 22.sp, lineHeight = 30.sp),
        titleMedium = base.titleMedium.copy(fontSize = 18.sp, lineHeight = 26.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 27.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
        labelLarge = base.labelLarge.copy(fontSize = 15.sp),
    )
}

@Composable
fun CoachTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = CoachTypography, content = content)
}

private val ColorScheme.isDark get() = background.luminance() < 0.5f

/** 「对」的绿；深色背景下换成亮一档，保证对比度。 */
@Composable
fun successColor(): Color = if (MaterialTheme.colorScheme.isDark) Color(0xFF7EDC9A) else Color(0xFF1B7F3B)
