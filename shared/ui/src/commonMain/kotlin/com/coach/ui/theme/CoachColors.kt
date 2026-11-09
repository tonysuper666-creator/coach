package com.coach.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import coach.design.tokens.CoachV7

/** Compatibility names for existing components; every value comes from the single V7 palette. */
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
    fun textPairs(): List<Pair<Color, Color>> = listOf(
        fgPrimary to bgCanvas, fgPrimary to surface1, fgPrimary to surface2, fgSecondary to bgCanvas, fgSecondary to surface1,
        onAccent to actionPrimary, onAccent to actionDanger, inverse to fgPrimary,
        successFg to successBg, warningFg to warningBg, dangerFg to dangerBg, infoFg to infoBg, accentDeep to surface1)
    companion object {
        fun light() = with(CoachV7.Light) { CoachColors(false,
            desk, desk, sheet, card, sheet, ink, sub, faint, sheet,
            line, sub, zhu, zhu, pine, selBg.compositeOver(sheet), ink, zhu,
            pine, pineWash.compositeOver(optBg), ink, hl.compositeOver(sheet),
            zhu, zhuWash.compositeOver(optBg), pine, pineWash.compositeOver(sheet)) }
        fun dark() = with(CoachV7.Dark) { CoachColors(true,
            desk, desk, sheet, card, sheet, ink, sub, faint, sheet,
            line, sub, zhu, zhu, pine, selBg.compositeOver(sheet), ink, zhu,
            pine, pineWash.compositeOver(optBg), ink, hl.compositeOver(sheet),
            zhu, zhuWash.compositeOver(optBg), pine, pineWash.compositeOver(sheet)) }
    }
}
