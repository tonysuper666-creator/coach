package com.coach.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

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
    // Colours are the DESIGN §9 semantic tokens (theme/CoachColors); this layer only adds the reading typography.
    com.coach.ui.theme.CoachTheme(dark) { MaterialTheme(typography = CoachTypography, content = content) }
}

/** 「对」的绿，来自令牌（深色下自动换亮一档）。 */
@Composable
fun successColor(): Color = com.coach.ui.theme.CoachTheme.colors.successFg
