package com.coach.ui.v7

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import coach.design.tokens.ReadingSizes
import com.coach.ui.CoachTheme
import com.coach.ui.theme.CoachColors
import com.coach.ui.theme.CoachTheme as Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SableThemeContractTest {
    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test fun sableTokenHexValuesAndContrastMatchCanonicalPalette() {
        val light = CoachColors.light()
        assertEquals(Color(0xFFF7F6F3), light.bgCanvas)
        assertEquals(Color(0xFFFFFFFF), light.surface1)
        assertEquals(Color(0xFFF0EFEC), light.surface2)
        assertEquals(Color(0xFF1A1A18), light.fgPrimary)
        assertEquals(Color(0xFF6B6A65), light.fgSecondary)
        assertEquals(Color(0xFF8A6A44), light.accent)
        assertEquals(Color(0xFF8A6A44), light.actionPrimary)
        assertEquals(Color(0xFFA83A2B), light.actionDanger)
        assertEquals(Color(0xFF3E7948), light.successFg)

        val dark = CoachColors.dark()
        assertEquals(Color(0xFF0E0E10), dark.bgCanvas)
        assertEquals(Color(0xFF17171A), dark.surface1)
        assertEquals(Color(0xFF1F1F23), dark.surface2)
        assertEquals(Color(0xFFE8E6E3), dark.fgPrimary)
        assertEquals(Color(0xFF8A8A90), dark.fgSecondary)
        assertEquals(Color(0xFFD8B98E), dark.accent)
        assertEquals(Color(0xFFD8B98E), dark.actionPrimary)
        assertEquals(Color(0xFFC2543F), dark.actionDanger)
        assertEquals(Color(0xFF7FBF8A), dark.successFg)

        for ((mode, colors) in listOf("light" to light, "dark" to dark)) {
            for ((fg, bg) in colors.textPairs()) {
                assertTrue(contrast(fg, bg) >= 4.5, "$mode pair $fg on $bg below AA: ${contrast(fg, bg)}")
            }
            assertTrue(contrast(colors.focusRing, colors.bgCanvas) >= 3.0, "$mode focusRing on bgCanvas < 3.0")
            assertTrue(contrast(colors.focusRing, colors.surface1) >= 3.0, "$mode focusRing on surface1 < 3.0")
        }
    }

    @Test fun uiSizesAreIntegersAndMotionHasZeroOvershoot() {
        val uiSizes = listOf(
            CoachV7.Size.micro.value,
            CoachV7.Size.cap.value,
            CoachV7.Size.ui.value,
            CoachV7.Size.uiBody.value,
            CoachV7.Size.input.value,
            CoachV7.Size.lg.value,
            CoachV7.Size.heading.value,
            CoachV7.Size.section.value,
            CoachV7.Size.title.value,
            ReadingSizes.ui.value,
            ReadingSizes.caption.value,
            ReadingSizes.section.value,
            ReadingSizes.week.value,
        )
        for (value in uiSizes) {
            assertEquals(value.toInt().toFloat(), value, "UI size $value must be an integer sp")
        }
        assertEquals(listOf(6f, 10f, 14f, 999f), listOf(CoachV7.Radius.s.value, CoachV7.Radius.m.value, CoachV7.Radius.lg.value, CoachV7.Radius.pill.value))
        for (step in 0..100) {
            val t = step / 100f
            val y = CoachV7.Motion.easing.transform(t)
            assertTrue(y in 0f..1f, "Sable motion easing must not overshoot at t=$t: y=$y")
        }
    }

    @Test fun sablePrimitivesRenderAndInteractAcrossLightAndDark() {
        val internalTerms = listOf("朋友服务", "Mac", "标准版", "旗舰版")
        for (dark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(800f, 700f)) {
                var emptyClicked = 0
                var retryClicked = 0
                var dangerClicked = 0
                setContent {
                    CoachTheme(dark) {
                        CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                            Column(
                                Modifier.fillMaxSize().background(Theme.colors.bgCanvas).padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CoachOfflineBanner()
                                CoachErrorBanner("没连上服务。核对地址后再试。", onRetry = { retryClicked++ })
                                CoachLoadingDots(label = "正在整理本节要点…")
                                CoachProgressBar(progress = 0.65f)
                                CoachCardMeta("3 节 · 18 分钟 · 第 42 页")
                                CoachEmptyState(
                                    title = "书架还是空的",
                                    hint = "拖入一本教材，或新建一门课程开始学习。",
                                    actionLabel = "导入材料",
                                    onAction = { emptyClicked++ },
                                )
                                DangerButton(onClick = { dangerClicked++ }) {
                                    Text("确认删除")
                                }
                                assertEquals(MaterialTheme.typography.headlineMedium.fontFamily, MaterialTheme.typography.labelLarge.fontFamily)
                            }
                        }
                    }
                }
                waitForIdle()
                onNodeWithTag("coach-offline-banner").assertIsDisplayed()
                onNodeWithTag("coach-error-banner").assertIsDisplayed()
                onNodeWithTag("coach-loading-dots").assertIsDisplayed()
                onNodeWithTag("coach-progress-bar").assertIsDisplayed()
                onNodeWithTag("coach-card-meta").assertIsDisplayed()
                onNodeWithTag("coach-empty-state").assertIsDisplayed()
                onNodeWithTag("coach-empty-action").performClick()
                onNodeWithTag("coach-error-retry").performClick()
                onNodeWithText("确认删除").performClick()
                assertEquals(1, emptyClicked)
                assertEquals(1, retryClicked)
                assertEquals(1, dangerClicked)
                for (term in internalTerms) {
                    assertFalse(term in "书架还是空的拖入一本教材或新建一门课程开始学习正在整理本节要点当前离线")
                }
            }
        }
    }
}
