package com.coach.ui.v7

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import coach.design.tokens.V7Surfaces
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

    @Test fun v7PalettePreservedAndPassesContrast() {
        val light = CoachColors.light()
        assertEquals(Color(0xFFE8E2D6), light.bgCanvas)
        assertEquals(Color(0xFFFCFAF6), light.surface1)
        assertEquals(Color(0xFFFBF8F2), light.surface2)
        assertEquals(Color(0xFF24211C), light.fgPrimary)
        assertEquals(Color(0xFF665F55), light.fgSecondary)
        assertEquals(Color(0xFFA63E2C), light.accent)
        assertEquals(Color(0xFF2F6A4B), light.successFg)

        val dark = CoachColors.dark()
        assertEquals(Color(0xFF0E0D0B), dark.bgCanvas)
        assertEquals(Color(0xFF1B1916), dark.surface1)
        assertEquals(Color(0xFF23201C), dark.surface2)
        assertEquals(Color(0xFFE6E0D5), dark.fgPrimary)
        assertEquals(Color(0xFFA0978A), dark.fgSecondary)
        assertEquals(Color(0xFFE07A68), dark.accent)
        assertEquals(Color(0xFF7FB394), dark.successFg)

        for ((mode, colors) in listOf("light" to light, "dark" to dark)) {
            for ((fg, bg) in colors.textPairs()) {
                assertTrue(contrast(fg, bg) >= 4.5, "$mode pair $fg on $bg below AA: ${contrast(fg, bg)}")
            }
            assertTrue(contrast(colors.focusRing, colors.bgCanvas) >= 3.0, "$mode focusRing on bgCanvas < 3.0")
            assertTrue(contrast(colors.focusRing, colors.surface1) >= 3.0, "$mode focusRing on surface1 < 3.0")
        }
    }

    @Test fun surfacesUseSingleLayerQuietShadows() {
        for (dark in listOf(false, true)) {
            for (kind in listOf("sheet", "menu", "card", "option", "button")) {
                val shadows = V7Surfaces.shadows(kind, dark)
                assertEquals(1, shadows.size, "Shadow stack for $kind (dark=$dark) must stay single-layer")
                assertFalse(shadows.first().inset, "Shadow for $kind (dark=$dark) must not be inset")
            }
        }
    }

    @Test fun uxPrimitivesRenderAndInteractAcrossLightAndDark() {
        val internalTerms = listOf("朋友服务", "Mac", "标准版", "旗舰版", "Anthropic", "OpenAI")
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
                    assertFalse(term in "当前离线本地记录已保留恢复连接后会继续同步没连上服务核对地址后再试正在整理本节要点书架还是空的拖入一本教材或新建一门课程开始学习导入材料确认删除")
                }
            }
        }
    }
}
