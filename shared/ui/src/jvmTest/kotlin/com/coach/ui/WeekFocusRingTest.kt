package com.coach.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.coach.ui.v7.V7TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.client.*
import com.coach.domain.*
import com.coach.ui.v7.V7FocusRingGap
import com.coach.ui.v7.V7FocusRingWidth
import com.coach.ui.v7.WeekPage
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TON-1603: the week page's three learning entrances (整周刷题 / 开始 / 先看要点) are direct text buttons.
 * Keyboard focus must show the V7 ring hugging the visible pill (same gap above as beside), ≥3:1 in light and dark,
 * Tab must reach them, and blur must restore the never-focused pixels. Offscreen Skiko, not a native window.
 */
@OptIn(ExperimentalTestApi::class)
class WeekFocusRingTest {
    private val scale = 2f
    private val out = File("build/screens/ton1603-week").apply { mkdirs() }

    @Test fun weekEntrancesShowAHuggingRingOnTabAndRestoreOnBlur() {
        val now = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()
        val events = listOf(Event(1, "outline", "SYN", "outline_revised", recordedAt = now,
            occurredAt = now, deviceId = "synthetic", actor = "SYSTEM", payload = json(
                "knowledgePoints" to JsonArray(listOf(json("id" to "a", "title" to "【W1】合成要点"), json("id" to "b", "title" to "【W1】第二要点"))))),
            Event(2, "exam", "SYN", "exam_set", recordedAt = now, occurredAt = now,
                deviceId = "synthetic", actor = "USER", payload = json("examDay" to 20802)))
        val view = CourseView(events, project(events))
        SyntheticLocalLedger().use { ledger ->
            val tutor = TutorClient("http://127.0.0.1:18959", "synthetic", "synthetic", TutorModel({ now }))
            try {
                val controller = CoachController("SYN", ledger.sync, tutor, formatClock = { "" }, nowMillis = { now })
                for (dark in listOf(false, true)) runSkikoComposeUiTest(size = Size(1224f * scale, 900f * scale), density = Density(scale)) {
                    setContent { CoachTheme(dark) { Surface {
                        WeekPage(controller, view, TutorUiState(), WeekActions(controller, rememberCoroutineScope()), { _, _ -> }, 1)
                    } } }
                    waitForIdle()
                    val mode = if (dark) "dark" else "light"
                    val practice = hasTestTag("week-practice")
                    val start = hasText("开始") and hasClickAction()
                    val read = hasText("先看要点") and hasClickAction()
                    val before = shot("$mode-unfocused")
                    val regions = listOf(practice, start, read).map { first(it).boundsInRoot.inflate(10 * scale) }

                    onNode(practice).requestFocus()
                    waitForIdle(); onNode(practice).assertIsFocused()
                    measure(mode, "整周刷题", practice, before)
                    var stops = 0
                    while (!focused(start) && stops < 15) { tabFromFocused(); stops++ }
                    assertTrue(focused(start), "$mode Tab never reached 开始")
                    measure(mode, "开始", start, before)
                    tabFromFocused()
                    assertTrue(focused(read), "$mode Tab after 开始 must land on 先看要点")
                    measure(mode, "先看要点", read, before)
                    println("TON-1603 week $mode tab-stops 整周刷题→开始=$stops 开始→先看要点=1")
                    tabFromFocused()
                    assertTrue(!focused(read), "$mode 先看要点 still focused")
                    val blurred = shot("$mode-blurred")
                    for ((i, r) in regions.withIndex()) assertRegionEqual(before, blurred, r, "$mode entrance $i after blur")
                }
            } finally { tutor.close() }
        }
    }

    /** V7TextButton must keep Material's size, 48dp slot and unfocused pixels; only the focus ring is added. */
    @Test fun v7TextButtonLooksAndSitsExactlyLikeMaterialWhenUnfocused() {
        for (dark in listOf(false, true)) runSkikoComposeUiTest(size = Size(300f * scale, 200f * scale), density = Density(scale)) {
            setContent { CoachTheme(dark) { Surface { Column {
                TextButton({}, Modifier.testTag("m3")) { Text("先看要点") }
                V7TextButton({}, Modifier.testTag("v7")) { Text("先看要点") }
            } } } }
            waitForIdle()
            val m3 = onNodeWithTag("m3").fetchSemanticsNode().boundsInRoot
            val v7 = onNodeWithTag("v7").fetchSemanticsNode().boundsInRoot
            println("TON-1603 week ${if (dark) "dark" else "light"} material=${m3.width / scale}x${m3.height / scale}dp v7=${v7.width / scale}x${v7.height / scale}dp slot=${(v7.top - m3.top) / scale}dp")
            assertEquals(m3.size, v7.size, "same visible size")
            assertEquals(48f * scale, v7.top - m3.top, 0.5f, "same 48dp touch slot")
            assertEquals(m3.left, v7.left)
            val px = shot("${if (dark) "dark" else "light"}-material-vs-v7")
            val dy = (v7.top - m3.top).roundToInt()
            var diff = 0
            for (x in m3.left.toInt() until m3.right.toInt()) for (y in m3.top.toInt() - 4 until m3.bottom.toInt() + 4)
                if (px[x, y] != px[x, y + dy]) diff++
            assertEquals(0, diff, "unfocused V7TextButton pixels differ from Material TextButton")
        }
    }

    private fun ComposeUiTest.first(m: SemanticsMatcher) = onAllNodes(m).fetchSemanticsNodes().first()

    private fun ComposeUiTest.focused(m: SemanticsMatcher) =
        first(m).config.getOrElseNullable(SemanticsProperties.Focused) { null } == true

    private fun ComposeUiTest.tabFromFocused() {
        onNode(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
    }

    /** From the button centre, walk left and up: focus fill → page gap → ring → page. Both gaps must be equal. */
    private fun ComposeUiTest.measure(mode: String, label: String, m: SemanticsMatcher, before: PixelMap) {
        val shot = shot("$mode-$label-focused")
        val b = first(m).boundsInRoot
        val cx = b.center.x.roundToInt(); val cy = b.center.y.roundToInt()
        val page = shot[(b.left - 20 * scale).roundToInt(), cy]
        fun isPage(c: Color) = contrast(c, page) < 1.08f
        fun walk(step: (Int) -> Color): Pair<Int, Int> {
            var i = 0
            while (!isPage(step(i))) i++
            val fillEdge = i - 1
            while (isPage(step(i))) i++
            while (!isPage(step(i))) i++
            return fillEdge to i - 1
        }
        val (fillL, ringL) = walk { shot[cx - it, cy] }
        val (fillT, ringT) = walk { shot[cx, cy - it] }
        val side = ringL - fillL; val top = ringT - fillT
        val ring = shot[cx, cy - ringT + 2]
        val fill = shot[cx, cy - fillT + 3]
        val expected = (V7FocusRingGap.value + V7FocusRingWidth.value) * scale
        println("TON-1603 week $mode $label ring=${hex(ring)} page=${hex(page)} fill=${hex(fill)} ring:page=${"%.2f".format(contrast(ring, page))} ring:fill=${"%.2f".format(contrast(ring, fill))} side-gap=${side}px top-gap=${top}px")
        assertTrue(contrast(ring, page) >= 3f, "$mode $label ring vs page")
        assertTrue(contrast(ring, fill) >= 3f, "$mode $label ring vs focus fill")
        assertTrue(abs(side - expected) <= 1.5f, "$mode $label side gap $side px, want $expected")
        assertTrue(abs(top - expected) <= 1.5f, "$mode $label top gap $top px, want $expected (ring must hug the visible pill)")
        assertTrue(contrast(before[cx, cy - ringT + 2], page) < 1.05f, "$mode $label ring pixel must be page colour when unfocused")
    }

    private fun ComposeUiTest.shot(name: String): PixelMap {
        val image = onRoot().captureToImage()
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
