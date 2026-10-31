package com.coach.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Semantic colour layer (DESIGN §9). Components read these, never the palette constants below.
 * `fgTertiary` and `accent` do not carry information text (contrast < 4.5:1 on paper); only disabled/decorative use.
 */
@Immutable
data class CoachColors(
    val dark: Boolean,
    val bgCanvas: Color, val bgCanvasDeep: Color, val surface1: Color, val surface2: Color, val inverse: Color,
    val fgPrimary: Color, val fgSecondary: Color, val fgTertiary: Color, val onAccent: Color,
    val borderSubtle: Color, val borderStrong: Color, val focusRing: Color,
    val accent: Color, val accentDeep: Color, val accentSoft: Color,
    val actionPrimary: Color, val actionDanger: Color,
    val successFg: Color, val successBg: Color, val warningFg: Color, val warningBg: Color,
    val dangerFg: Color, val dangerBg: Color, val infoFg: Color, val infoBg: Color,
) {
    /** Every pair that carries readable text, with the surface it sits on: the contrast test walks this list. */
    fun textPairs(): List<Pair<Color, Color>> = listOf(
        fgPrimary to bgCanvas, fgPrimary to surface1, fgPrimary to surface2, fgSecondary to bgCanvas, fgSecondary to surface1,
        onAccent to actionPrimary, onAccent to actionDanger, inverse to fgPrimary,
        successFg to successBg, warningFg to warningBg, dangerFg to dangerBg, infoFg to infoBg,
        accentDeep to surface1,
    )

    companion object {
        fun light() = CoachColors(
            dark = false,
            bgCanvas = Color(0xFFF4EFE6), bgCanvasDeep = Color(0xFFEAE3D6), surface1 = Color(0xFFFFFAF2), surface2 = Color(0xFFF8F0E3), inverse = Color(0xFFFFFAF2),
            fgPrimary = Color(0xFF2B2620), fgSecondary = Color(0xFF665F54), fgTertiary = Color(0xFF96998F), onAccent = Color(0xFFFFFFFF),
            borderSubtle = Color(0xFFE2D9C8), borderStrong = Color(0xFFB9AE99), focusRing = Color(0xFF9C2F5C),
            accent = Color(0xFFEC78A4), accentDeep = Color(0xFF9C2F5C), accentSoft = Color(0xFFF8DCE7),
            actionPrimary = Color(0xFFBD4070), actionDanger = Color(0xFFB3261E),
            successFg = Color(0xFF2B6B47), successBg = Color(0xFFDDEFE3), warningFg = Color(0xFF7A4F00), warningBg = Color(0xFFF7E8C6),
            dangerFg = Color(0xFF9C1F18), dangerBg = Color(0xFFF8DAD6), infoFg = Color(0xFF285487), infoBg = Color(0xFFDCE8F6),
        )
        fun dark() = CoachColors(
            dark = true,
            bgCanvas = Color(0xFF191712), bgCanvasDeep = Color(0xFF12100C), surface1 = Color(0xFF26221C), surface2 = Color(0xFF2F2A23), inverse = Color(0xFF191712),
            fgPrimary = Color(0xFFEFE9DC), fgSecondary = Color(0xFFB5AD9D), fgTertiary = Color(0xFF8F8A80), onAccent = Color(0xFF2A0F1B),
            borderSubtle = Color(0xFF3A342B), borderStrong = Color(0xFF6A6254), focusRing = Color(0xFFF088B0),
            accent = Color(0xFFF088B0), accentDeep = Color(0xFFF4A3C3), accentSoft = Color(0xFF4A2535),
            actionPrimary = Color(0xFFF088B0), actionDanger = Color(0xFFFF8A80),
            successFg = Color(0xFF8FD3A8), successBg = Color(0xFF1F3A2B), warningFg = Color(0xFFE8C16A), warningBg = Color(0xFF3F3216),
            dangerFg = Color(0xFFFFA8A0), dangerBg = Color(0xFF4A2220), infoFg = Color(0xFF9CC3F0), infoBg = Color(0xFF1F3350),
        )
    }
}
