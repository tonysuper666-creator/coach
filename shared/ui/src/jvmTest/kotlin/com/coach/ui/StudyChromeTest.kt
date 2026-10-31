package com.coach.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.coach.client.KpStatus
import com.coach.ui.theme.CoachColors
import com.coach.ui.theme.toMaterial
import kotlin.test.*

class StudyChromeTest {
    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble(); val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test fun everyStatusLabelIsReadableOnItsChipInLightAndDark() {
        for ((name, colors) in listOf("light" to CoachColors.light(), "dark" to CoachColors.dark())) {
            val scheme = colors.toMaterial()
            for (status in KpStatus.entries) {
                val text = kpStatusColor(status, scheme, colors.successFg)
                // StatusChip draws the text colour at CHIP_TINT over whatever surface it sits on (card or page).
                for (surface in listOf(scheme.surface, scheme.background)) {
                    val chip = text.copy(alpha = CHIP_TINT).compositeOver(surface)
                    assertTrue(contrast(text, chip) >= 4.5, "$name ${status.label} on ${surface}: ${contrast(text, chip)}")
                }
            }
        }
    }

    @Test fun titleShowsOnlyTheFirstAnchor() {
        val title = "【W4·Ch8】区分 uncertainty 与 asymmetric information；定义 adverse selection ｜ 【W4·slides】Pareto efficient 的定义 ｜ 【W1·slides】健康生产函数"
        assertEquals("W4·Ch8" to "区分 uncertainty 与 asymmetric information；定义 adverse selection", splitTitle(title))
        assertEquals(2, additionalTitleScope(title).size)
        assertEquals("" to "没有标签的标题", splitTitle("没有标签的标题"))
        assertEquals(0, additionalTitleScope("没有标签的标题").size)
    }
}
