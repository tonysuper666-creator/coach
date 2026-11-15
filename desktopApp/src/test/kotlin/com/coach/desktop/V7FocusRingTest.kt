package com.coach.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.ui.CoachTheme
import com.coach.ui.theme.CoachTheme as Theme
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7FocusRingGap
import com.coach.ui.v7.V7FocusRingWidth
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TON-1603: keyboard focus on V7 buttons must be visible (WCAG 2.2 1.4.11, ≥3:1) in light and dark, and vanish on blur. */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class V7FocusRingTest {
    private val scale = 2f
    private val out = File(System.getProperty("coach.focusScreens") ?: "build/screens/ton1603").apply { mkdirs() }

    @Test fun tabFocusDrawsAnInkRingAtLeastThreeToOneAndBlurRestoresTheButton() {
        for (dark in listOf(false, true)) runSkikoComposeUiTest(size = Size(900f, 260f), density = Density(scale)) {
            setContent {
                CoachTheme(dark) {
                    Row(Modifier.fillMaxSize().background(Theme.colors.bgCanvas).padding(40.dp).testTag("root"),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        PaperButton({}, Modifier.testTag("paper")) { Text("继续学") }
                        PrimaryButton({}, Modifier.testTag("primary")) { Text("下一课") }
                        // Un-migrated control: a direct Material TextButton keeps only its default focus fill.
                        TextButton({}, Modifier.testTag("text-default")) { Text("先看要点") }
                    }
                }
            }
            waitForIdle()
            val mode = if (dark) "dark" else "light"
            val before = shot("$mode-unfocused")
            val tags = listOf("paper", "primary")
            onNodeWithTag("paper").requestFocus()
            for ((i, tag) in tags.withIndex()) {
                if (i > 0) onNodeWithTag(tags[i - 1]).performKeyInput { pressKey(Key.Tab) }
                waitForIdle()
                onNodeWithTag(tag).assertIsFocused()
                val focused = shot("$mode-$tag-focused")
                val b = bounds(tag)
                val ringX = (b.left - (V7FocusRingGap.value + V7FocusRingWidth.value / 2) * scale).roundToInt()
                val y = b.center.y.roundToInt()
                val ring = focused[ringX, y]
                val page = focused[(b.left - 12 * scale).roundToInt(), y]
                val gap = focused[(b.left - V7FocusRingGap.value / 2 * scale).roundToInt(), y]
                val pageRatio = contrast(ring, page); val gapRatio = contrast(ring, gap)
                println("TON-1603 $mode $tag ring=${hex(ring)} page=${hex(page)} gap=${hex(gap)} ring:page=${"%.2f".format(pageRatio)} ring:gap=${"%.2f".format(gapRatio)}")
                assertTrue(pageRatio >= 3f, "$mode $tag ring vs page ${"%.2f".format(pageRatio)}")
                assertTrue(gapRatio >= 3f, "$mode $tag ring vs gap ${"%.2f".format(gapRatio)}")
                assertTrue(contrast(before[ringX, y], page) < 1.05f, "$mode $tag ring pixel must be page colour when unfocused")
                // Ring hugs the visible control: the top edge sits the same gap above it as the left edge does.
                val topY = (b.top - (V7FocusRingGap.value + V7FocusRingWidth.value / 2) * scale).roundToInt()
                assertTrue(contrast(focused[b.center.x.roundToInt(), topY], page) >= 3f, "$mode $tag ring top edge missing")
            }
            onNodeWithTag("primary").performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            onNodeWithTag("primary").assertIsNotFocused()
            onNodeWithTag("text-default").assertIsFocused()
            val blurred = shot("$mode-text-default-focused")
            bounds("text-default").let { b ->
                val fill = blurred[(b.left + 10 * scale).roundToInt(), b.center.y.roundToInt()]
                val page = blurred[(b.left - 12 * scale).roundToInt(), b.center.y.roundToInt()]
                println("TON-1603 $mode text-default material-fill=${hex(fill)} page=${hex(page)} ratio=${"%.2f".format(contrast(fill, page))} (not migrated)")
            }
            for (tag in tags) assertRegionEqual(before, blurred, bounds(tag).inflate(8 * scale), "$mode $tag after blur")
        }
    }

    @Test fun realWindowsHostTabStopsOnUploadAndShowsTheRing() {
        val previous = System.getProperty("coach.windows.theme")
        try {
            for (mode in listOf("light", "dark")) {
                System.setProperty("coach.windows.theme", mode)
                val controller = TextbookController(TextbookLibrary(Files.createTempDirectory("coach-focus-")))
                runSkikoComposeUiTest(size = Size(1200f, 800f), density = Density(scale)) {
                    // Pin the OS theme too: 书架 still nests a system-following CoachTheme (reported separately).
                    setContent {
                        CompositionLocalProvider(LocalSystemTheme provides if (mode == "dark") SystemTheme.Dark else SystemTheme.Light) {
                            WindowsTextbookHost(controller) { null }
                        }
                    }
                    waitForIdle()
                    val before = shot("host-$mode-unfocused")
                    var stops = 0
                    while (!isFocused("upload-textbook") && stops < 30) {
                        val focused = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).fetchSemanticsNodes()
                        if (focused.isEmpty()) onNodeWithTag("windows-host").performKeyInput { pressKey(Key.Tab) }
                        else onNode(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).performKeyInput { pressKey(Key.Tab) }
                        waitForIdle(); stops++
                    }
                    onNodeWithTag("upload-textbook").assertIsFocused()
                    val focused = shot("host-$mode-upload-focused")
                    val b = bounds("upload-textbook")
                    val y = b.center.y.roundToInt()
                    val ring = focused[(b.left - (V7FocusRingGap.value + V7FocusRingWidth.value / 2) * scale).roundToInt(), y]
                    val page = focused[(b.left - 12 * scale).roundToInt(), y]
                    println("TON-1603 host $mode tab-stops=$stops ring=${hex(ring)} page=${hex(page)} ratio=${"%.2f".format(contrast(ring, page))}")
                    assertTrue(contrast(ring, page) >= 3f)
                    onNodeWithTag("upload-textbook").performKeyInput { pressKey(Key.Tab) }
                    waitForIdle()
                    onNodeWithTag("upload-textbook").assertIsNotFocused()
                    assertRegionEqual(before, shot("host-$mode-blurred"), b.inflate(8 * scale), "host $mode upload after blur")
                }
            }
        } finally {
            if (previous == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", previous)
        }
    }

    private fun ComposeUiTest.isFocused(tag: String) =
        onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.config?.getOrElseNullable(SemanticsProperties.Focused) { null } == true

    private fun ComposeUiTest.bounds(tag: String): Rect = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.shot(name: String): PixelMap {
        val image: ImageBitmap = onRoot().captureToImage()
        ImageIO.write(image.toAwtImage(), "png", File(out, "$name.png"))
        return image.toPixelMap()
    }

    private fun assertRegionEqual(a: PixelMap, b: PixelMap, r: Rect, what: String) {
        var diff = 0
        for (x in r.left.toInt().coerceAtLeast(0) until r.right.toInt().coerceAtMost(a.width))
            for (y in r.top.toInt().coerceAtLeast(0) until r.bottom.toInt().coerceAtMost(a.height))
                if (a[x, y] != b[x, y]) diff++
        assertEquals(0, diff, "$what: pixels differ from the never-focused frame")
    }

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + .05f) / (lo + .05f)
    }

    private fun hex(c: Color) = "#%02X%02X%02X".format((c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt())
}
