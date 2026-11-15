package com.coach.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.coach.ui.v7.V7TextButton
import androidx.compose.runtime.CompositionLocalProvider
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
import com.coach.ui.v7.LocalDeskSelect
import com.coach.ui.v7.LocalV7PageDetails
import com.coach.ui.v7.WeekPage
import com.coach.ui.v7.coursePageDetails
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
 * Later slices add the week-picker dialog, the 资料 shelf, the margin summary buttons, the footer week links and the week-scope dropdown entry.
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

    /**
     * The rest of the week page's direct text buttons: the week-picker dialog (week options, 关闭), the 资料 shelf and the
     * margin summary (再练这一周错题 / 浏览下一周). Real WeekPage with two weeks and one source document on the shelf.
     * Each one shows the hugging ring on Tab, Enter still runs its original action, and blur restores the pixels.
     */
    @Test fun weekDialogShelfAndBrowseButtonsShowRingAndKeepTheirActions() {
        val now = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()
        val events = listOf(Event(1, "outline", "SYN", "outline_revised", recordedAt = now,
            occurredAt = now, deviceId = "synthetic", actor = "SYSTEM", payload = json(
                "knowledgePoints" to JsonArray(listOf(json("id" to "a", "title" to "【W1】合成要点"), json("id" to "c", "title" to "【W2】下周要点"))))),
            Event(2, "exam", "SYN", "exam_set", recordedAt = now, occurredAt = now,
                deviceId = "synthetic", actor = "USER", payload = json("examDay" to 20802)))
        val view = CourseView(events, project(events))
        val shelfDoc = SourceDoc("syn-doc", "合成教材", 1, 10, 1, 10)
        SyntheticLocalLedger().use { ledger ->
            val tutor = TutorClient("http://127.0.0.1:18959", "synthetic", "synthetic", TutorModel({ now }))
            try {
                val controller = CoachController("SYN", ledger.sync, tutor, formatClock = { "" }, nowMillis = { now })
                for (dark in listOf(false, true)) runSkikoComposeUiTest(size = Size(1224f * scale, 900f * scale), density = Density(scale)) {
                    val mode = if (dark) "dark" else "light"
                    val reviewed = mutableListOf<Int>(); val browsed = mutableListOf<Int>(); val tabs = mutableListOf<AppTab>()
                    setContent {
                        // The shelf normally arrives from the course server; inject the same details with one document on it.
                        val details = coursePageDetails("SYN", view, 1, null, "", listOf(shelfDoc), null)
                        CompositionLocalProvider(LocalDeskSelect provides { tabs += it }, LocalV7PageDetails provides details) {
                            CoachTheme(dark) { Surface {
                                val actions = WeekActions(controller, rememberCoroutineScope()).apply { onReview = { reviewed += it }; onBrowse = { browsed += it } }
                                WeekPage(controller, view, TutorUiState(), actions, { _, _ -> }, 1)
                            } }
                        }
                    }
                    waitForIdle()
                    val review = hasText("再练这一周错题") and hasClickAction()
                    val next = hasText("浏览下一周") and hasClickAction()
                    val shelf = hasText("合成教材") and hasClickAction()
                    val before = shot("$mode-margin-unfocused")
                    val regions = listOf(review, next, shelf).map { first(it).boundsInRoot.inflate(10 * scale) }

                    onNode(review).requestFocus(); waitForIdle(); onNode(review).assertIsFocused()
                    measure(mode, "再练这一周错题", review, before, pageDx = 8f)
                    tabFromFocused(); assertTrue(focused(next), "$mode Tab after 再练这一周错题 must land on 浏览下一周")
                    measure(mode, "浏览下一周", next, before, pageDx = 8f)
                    var stops = 0
                    while (!focused(shelf) && stops < 15) { tabFromFocused(); stops++ }
                    assertTrue(focused(shelf), "$mode Tab never reached the 资料 shelf")
                    measure(mode, "资料-合成教材", shelf, before, pageDx = 8f)
                    println("TON-1603 week $mode tab-stops 再练这一周错题→浏览下一周=1 浏览下一周→资料=$stops")
                    tabFromFocused(); assertTrue(!focused(shelf), "$mode shelf still focused")
                    val blurred = shot("$mode-margin-blurred")
                    for ((i, r) in regions.withIndex()) assertRegionEqual(before, blurred, r, "$mode margin button $i after blur")

                    // Enter keeps each original action.
                    enter(review); enter(next); enter(shelf)
                    println("TON-1603 week $mode enter reviewed=$reviewed browsed=$browsed tabs=$tabs")
                    assertEquals(listOf(1), reviewed, "$mode Enter on 再练这一周错题")
                    assertEquals(listOf(2), browsed, "$mode Enter on 浏览下一周")
                    assertEquals(listOf(AppTab.RESOURCES), tabs, "$mode Enter on the shelf")

                    // Week picker dialog: week options and 关闭.
                    onNodeWithTag("v7-week-picker").performClick(); waitForIdle()
                    val week1 = hasText("第 1 周") and hasClickAction() and hasAnyAncestor(isDialog())
                    val week2 = hasText("第 2 周") and hasClickAction() and hasAnyAncestor(isDialog())
                    val close = hasText("关闭") and hasClickAction() and hasAnyAncestor(isDialog())
                    val initiallyFocused = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).fetchSemanticsNodes().size
                    val dialogBefore = shot("$mode-dialog-unfocused", dialog = true)
                    val dialogRegions = listOf(week1, week2, close).map { first(it).boundsInRoot.inflate(10 * scale) }
                    println("TON-1603 week $mode dialog opened focused-nodes=$initiallyFocused size=${dialogBefore.width}x${dialogBefore.height}")
                    onNode(week1).requestFocus(); waitForIdle(); onNode(week1).assertIsFocused()
                    measure(mode, "换周-第1周", week1, dialogBefore, pageDx = 8f, dialog = true)
                    tabFromFocused(); assertTrue(focused(week2), "$mode Tab after 第 1 周 must land on 第 2 周")
                    measure(mode, "换周-第2周", week2, dialogBefore, pageDx = 8f, dialog = true)
                    tabFromFocused(); assertTrue(focused(close), "$mode Tab after 第 2 周 must land on 关闭")
                    measure(mode, "换周-关闭", close, dialogBefore, pageDx = 8f, dialog = true)
                    println("TON-1603 week $mode dialog tab-stops 第1周→第2周=1 第2周→关闭=1")
                    // The week options sit 8dp apart, so a neighbour's live ring would fall inside the other's region:
                    // compare the two weeks while focus is on 关闭, then 关闭 while focus is back on 第 1 周.
                    val weeksBlurred = shot("$mode-dialog-blurred-weeks", dialog = true)
                    for (i in 0..1) assertRegionEqual(dialogBefore, weeksBlurred, dialogRegions[i], "$mode dialog week ${i + 1} after blur")
                    onNode(week1).requestFocus(); waitForIdle()
                    onNode(week1).assertIsFocused()
                    val closeBlurred = shot("$mode-dialog-refocused-first", dialog = true)
                    assertRegionEqual(dialogBefore, closeBlurred, dialogRegions[2], "$mode dialog 关闭 after blur")
                    enter(close)
                    assertEquals(0, onAllNodes(isDialog()).fetchSemanticsNodes().size, "$mode Enter on 关闭 closes the dialog")
                    assertEquals(listOf(2), browsed, "$mode 关闭 must not change week")
                    onNodeWithTag("v7-week-picker").performClick(); waitForIdle()
                    enter(week2)
                    assertEquals(0, onAllNodes(isDialog()).fetchSemanticsNodes().size, "$mode picking a week closes the dialog")
                    assertEquals(listOf(2, 2), browsed, "$mode Enter on 第 2 周 selects week 2")
                    println("TON-1603 week $mode dialog enter 关闭=closed 第2周=browsed$browsed")
                }
            } finally { tutor.close() }
        }
    }

    private fun ComposeUiTest.enter(m: SemanticsMatcher) {
        onNode(m).requestFocus(); waitForIdle()
        onNode(m).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
    }

    /**
     * Week navigation in the page footer: ‹ 上一周 and 下一周 › are clickable text, not buttons.
     * Middle week: both show the hugging ring on Tab, Enter browses that week, blur restores the pixels.
     * First and last week: the missing direction is blank and disabled, so a whole Tab cycle never stops on it.
     */
    @Test fun weekFooterShowsRingAndSkipsTheMissingDirectionAtTheEdges() {
        val view = threeWeeks()
        withController { controller ->
            for (dark in listOf(false, true)) runSkikoComposeUiTest(size = Size(1224f * scale, 900f * scale), density = Density(scale)) {
                val mode = if (dark) "dark" else "light"
                val browsed = mutableListOf<Int>()
                val shown = mutableStateOf(2)
                lateinit var focus: FocusManager
                setContent { CoachTheme(dark) { Surface {
                    focus = LocalFocusManager.current
                    val actions = WeekActions(controller, rememberCoroutineScope()).apply { onBrowse = { browsed += it } }
                    WeekPage(controller, view, TutorUiState(), actions, { _, _ -> }, shown.value)
                } } }
                waitForIdle()
                val prev = hasTestTag("v7-previous-week")
                val next = hasTestTag("v7-next-week")
                val before = shot("$mode-footer-middle-unfocused")
                val regions = listOf(prev, next).map { first(it).boundsInRoot.inflate(10 * scale) }
                onNode(prev).requestFocus(); waitForIdle(); onNode(prev).assertIsFocused()
                measure(mode, "页脚-上一周", prev, before, pageDx = 8f)
                val stops = tabUntil(next)
                assertTrue(focused(next), "$mode Tab never reached 下一周")
                measure(mode, "页脚-下一周", next, before, pageDx = 8f)
                println("TON-1603 week $mode footer tab-stops 上一周→下一周=$stops")
                runOnIdle { focus.clearFocus() }; waitForIdle()
                val blurred = shot("$mode-footer-middle-blurred")
                for ((i, r) in regions.withIndex()) assertRegionEqual(before, blurred, r, "$mode footer link $i after blur")
                enter(prev); enter(next)
                assertEquals(listOf(1, 3), browsed, "$mode Enter on 上一周 / 下一周 from week 2")

                for ((edge, dead, live, want) in listOf(Edge(1, prev, next, 2), Edge(3, next, prev, 2))) {
                    runOnIdle { focus.clearFocus(); shown.value = edge }; waitForIdle()
                    val name = if (edge == 1) "首周" else "末周"
                    onNode(dead).assertIsNotEnabled()
                    runCatching { onNode(dead).requestFocus() }; waitForIdle()
                    assertTrue(!focused(dead), "$mode $name blank direction took focus")
                    onNode(live).requestFocus(); waitForIdle(); onNode(live).assertIsFocused()
                    shot("$mode-footer-$name-focused")
                    var n = 0; var landedOnDead = false
                    do { if (!tabOnce()) break; n++; if (focused(dead)) landedOnDead = true } while (!focused(live) && n < 80)
                    println("TON-1603 week $mode footer $name dead=${if (edge == 1) "上一周" else "下一周"} tab-cycle=$n closed=${focused(live)} landed-on-dead=$landedOnDead")
                    assertTrue(!landedOnDead, "$mode $name Tab stopped on the blank direction")
                    val count = browsed.size
                    enter(live)
                    assertEquals(count + 1, browsed.size, "$mode $name Enter on the live direction")
                    assertEquals(want, browsed.last(), "$mode $name Enter browses week 2")
                }
                println("TON-1603 week $mode footer enter browsed=$browsed")
            }
        }
    }

    /**
     * The week-scope dropdown entry (WeekPicker) is a Material TextButton stretched to a 48dp pill.
     * Unfocused it must stay pixel-identical to the same Material button; focused it shows the ring around that pill.
     * Enter opens the menu, Enter on a week picks it and closes the menu, blur restores the pixels.
     */
    @Test fun weekScopePickerShowsRingAndPicksAWeekByKeyboard() {
        val view = threeWeeks()
        withController { controller ->
            for (dark in listOf(false, true)) runSkikoComposeUiTest(size = Size(400f * scale, 400f * scale), density = Density(scale)) {
                val mode = if (dark) "dark" else "light"
                val browsed = mutableListOf<Int>()
                lateinit var focus: FocusManager
                setContent { CoachTheme(dark) { Surface { Column(Modifier.padding(24.dp)) {
                    focus = LocalFocusManager.current
                    TextButton({}, Modifier.heightIn(min = 48.dp).testTag("m3")) { Text("浏览各周 ▾") }
                    val actions = WeekActions(controller, rememberCoroutineScope()).apply { onBrowse = { browsed += it } }
                    WeekPicker(view, actions, true)
                } } } }
                waitForIdle()
                val picker = hasText("浏览各周 ▾") and hasClickAction() and !hasTestTag("m3")
                val m3 = onNodeWithTag("m3").fetchSemanticsNode().boundsInRoot
                val b = first(picker).boundsInRoot
                val before = shot("$mode-picker-unfocused")
                val dy = (b.top - m3.top).roundToInt()
                var diff = 0
                for (x in m3.left.toInt() until m3.right.toInt()) for (y in m3.top.toInt() - 4 until m3.bottom.toInt() + 4)
                    if (before[x, y] != before[x, y + dy]) diff++
                println("TON-1603 week $mode picker material=${m3.width / scale}x${m3.height / scale}dp picker=${b.width / scale}x${b.height / scale}dp unfocused-diff=$diff")
                assertEquals(m3.size, b.size, "$mode picker keeps the Material 48dp pill")
                assertEquals(0, diff, "$mode unfocused picker pixels differ from the Material button")

                onNode(picker).requestFocus(); waitForIdle(); onNode(picker).assertIsFocused()
                measure(mode, "周范围下拉", picker, before, pageDx = 8f)
                onNode(picker).performKeyInput { pressKey(Key.Enter) }; waitForIdle()
                val week2 = hasText("第 2 周", substring = true) and hasClickAction()
                assertEquals(1, onAllNodes(week2).fetchSemanticsNodes().size, "$mode Enter opens the week menu")
                shot("$mode-picker-menu-open", dialog = true)
                enter(week2)
                assertEquals(listOf(2), browsed, "$mode Enter on 第 2 周 in the menu")
                assertEquals(0, onAllNodes(week2).fetchSemanticsNodes().size, "$mode picking closes the menu")
                runOnIdle { focus.clearFocus() }; waitForIdle()
                val blurred = shot("$mode-picker-blurred")
                assertRegionEqual(before, blurred, b.inflate(10 * scale), "$mode picker after blur")
                println("TON-1603 week $mode picker enter menu-opened=1 browsed=$browsed")
            }
        }
    }

    private data class Edge(val week: Int, val dead: SemanticsMatcher, val live: SemanticsMatcher, val lands: Int)

    private fun threeWeeks(): CourseView {
        val now = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()
        val events = listOf(Event(1, "outline", "SYN", "outline_revised", recordedAt = now,
            occurredAt = now, deviceId = "synthetic", actor = "SYSTEM", payload = json(
                "knowledgePoints" to JsonArray(listOf(json("id" to "a", "title" to "【W1】合成要点"), json("id" to "c", "title" to "【W2】中间要点"),
                    json("id" to "d", "title" to "【W3】末周要点"))))),
            Event(2, "exam", "SYN", "exam_set", recordedAt = now, occurredAt = now,
                deviceId = "synthetic", actor = "USER", payload = json("examDay" to 20802)))
        return CourseView(events, project(events))
    }

    private fun withController(block: (CoachController) -> Unit) {
        val now = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()
        SyntheticLocalLedger().use { ledger ->
            val tutor = TutorClient("http://127.0.0.1:18959", "synthetic", "synthetic", TutorModel({ now }))
            try { block(CoachController("SYN", ledger.sync, tutor, formatClock = { "" }, nowMillis = { now })) } finally { tutor.close() }
        }
    }

    /** Tab from the focused node; false when nothing holds focus any more. */
    private fun ComposeUiTest.tabOnce(): Boolean {
        if (onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).fetchSemanticsNodes().isEmpty()) return false
        tabFromFocused(); return true
    }

    private fun ComposeUiTest.tabUntil(m: SemanticsMatcher): Int {
        var n = 0
        while (!focused(m) && n < 15 && tabOnce()) n++
        return n
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
        // A dialog keeps its own focus owner; the page underneath keeps its focused node too. Type into the top-most one.
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).onLast().performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
    }

    /**
     * From the visible pill's own edge (its semantics bounds), walk outward left and up: transparent gap → ring → adjacent
     * background. Gap and ring width must match V7FocusRingGap / V7FocusRingWidth on both axes, so the ring hugs the pill.
     * Starting at the edge, not the centre, keeps the label glyphs out of the walk.
     * [pageDx] is where the adjacent background is sampled, left of the pill; [dialog] captures the dialog layer instead of the page.
     */
    private fun ComposeUiTest.measure(mode: String, label: String, m: SemanticsMatcher, before: PixelMap, pageDx: Float = 20f, dialog: Boolean = false) {
        val shot = shot("$mode-$label-focused", dialog)
        val b = first(m).boundsInRoot
        val cx = b.center.x.roundToInt(); val cy = b.center.y.roundToInt()
        val left = b.left.roundToInt(); val top = b.top.roundToInt()
        val page = shot[(b.left - pageDx * scale).roundToInt(), cy]
        fun isPage(c: Color) = contrast(c, page) < 1.08f
        /** Returns (ring inner, ring outer) as distances from the pill edge; skips the edge's anti-aliased pixel. */
        fun walk(step: (Int) -> Color): Pair<Int, Int> {
            var i = 2
            while (isPage(step(i))) i++
            val inner = i
            while (!isPage(step(i))) i++
            return inner to i - 1
        }
        val (innerL, outerL) = walk { shot[left - it, cy] }
        val (innerT, outerT) = walk { shot[cx, top - it] }
        val gapL = innerL - 1; val gapT = innerT - 1
        val widthL = outerL - innerL + 1; val widthT = outerT - innerT + 1
        val ringY = top - (innerT + outerT) / 2
        val ring = shot[cx, ringY]
        val fill = shot[cx, top + 3]
        println("TON-1603 week $mode $label ring=${hex(ring)} page=${hex(page)} fill=${hex(fill)} ring:page=${"%.2f".format(contrast(ring, page))} ring:fill=${"%.2f".format(contrast(ring, fill))} gap(side/top)=$gapL/${gapT}px ring(side/top)=$widthL/${widthT}px")
        assertTrue(contrast(ring, page) >= 3f, "$mode $label ring vs adjacent background")
        assertTrue(contrast(ring, fill) >= 3f, "$mode $label ring vs the button's own focused fill")
        for ((what, got, want) in listOf(Triple("side gap", gapL, V7FocusRingGap), Triple("top gap", gapT, V7FocusRingGap),
            Triple("side ring", widthL, V7FocusRingWidth), Triple("top ring", widthT, V7FocusRingWidth)))
            assertTrue(abs(got - want.value * scale) <= 1.5f, "$mode $label $what $got px, want ${want.value * scale} (ring must hug the visible pill)")
        assertTrue(contrast(before[cx, ringY], page) < 1.05f, "$mode $label ring pixel must be background when unfocused")
    }

    private fun ComposeUiTest.shot(name: String, dialog: Boolean = false): PixelMap {
        val image = (if (dialog) onAllNodes(isRoot()).onLast() else onAllNodes(isRoot()).onFirst()).captureToImage()
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
