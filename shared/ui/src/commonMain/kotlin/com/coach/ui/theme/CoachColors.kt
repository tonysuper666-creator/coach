package com.coach.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import coach.design.tokens.CoachV7

/** Compatibility names for existing components; every value comes from the single Sable-aligned palette. */
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
    val onDanger: Color = Color.White, val warningAccent: Color = warningFg, val accentHi: Color = accent,
) {
    fun textPairs(): List<Pair<Color, Color>> = listOf(
        fgPrimary to bgCanvas, fgPrimary to surface1, fgPrimary to surface2,
        fgSecondary to bgCanvas, fgSecondary to surface1, fgSecondary to surface2,
        onAccent to actionPrimary, onDanger to actionDanger, inverse to fgPrimary,
        successFg to successBg, warningFg to warningBg, dangerFg to dangerBg, infoFg to infoBg, accentDeep to surface1)
    companion object {
        fun light() = with(CoachV7.Light) { CoachColors(false,
            desk, desk, sheet, card, sheet, ink, sub, faint, onAccent,
            border, Color(0x29000000), accent, accent, accentLo, accentSoft.compositeOver(sheet), accent, dangerSolid,
            ok, pineWash.compositeOver(optBg), ink, Color(0x29B7791F).compositeOver(sheet),
            danger, dangerWash.compositeOver(optBg), accentLo, accentSoft.compositeOver(sheet),
            onDanger, warn, accentHi) }
        fun dark() = with(CoachV7.Dark) { CoachColors(true,
            desk, desk, sheet, card, sheet, ink, sub, faint, onAccent,
            border, Color(0x29FFFFFF), accent, accent, accentHi, accentSoft.compositeOver(sheet), accent, dangerSolid,
            ok, pineWash.compositeOver(optBg), ink, Color(0x2ED99A3D).compositeOver(sheet),
            danger, dangerWash.compositeOver(optBg), accent, accentSoft.compositeOver(sheet),
            onDanger, warn, accentHi) }
    }
}
