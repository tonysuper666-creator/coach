package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.coach.ui.v7.V7TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
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
import com.coach.ui.v7.Desk
import com.coach.ui.v7.LocalDeskSelect
import com.coach.ui.v7.LocalV7PageDetails
import com.coach.ui.v7.WeekPage
import com.coach.ui.v7.V7MarginQuestion
import com.coach.ui.v7.V7PageDetails
import com.coach.ui.v7.ReadingPage
import com.coach.ui.v7.coursePageDetails
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
 * Later slices add the week-picker dialog, the 资料 shelf, the margin summary buttons, the footer week links, the week-scope dropdown entry, its menu rows, the top-bar week name, the Desk 目录 / 收起目录 buttons, the Desk 返回 / 出处与批注 / 专心读 entrances, the reading page's 问一句 / 开始做题 / 继续练习 / 打开原页 entrances, its footer ‹ 上一节 / 下一节 › steps, its 一句批注 dialog's 取消 / 保存 and its selection menu / bar actions 划线 / 一句批注 / 再讲一遍 / 举个例子 / 复制.
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

    /**
     * The rows inside the week-scope dropdown (each week and 全部课程). The menu clips its rows, so the ring sits inside
     * each row; first and last row must keep all four sides. Unfocused rows and the menu frame stay pixel-identical to the
     * same Material menu, arrow keys walk the rows, Enter picks and closes, Escape closes, and a mouse click on a row's
     * corner still picks it.
     */
    @Test fun weekScopeMenuRowsShowRingInsideAndKeepTheirLook() {
        val view = threeWeeks(scope = 2)
        withController { controller ->
            for (dark in listOf(false, true)) runSkikoComposeUiTest(size = Size(480f * scale, 520f * scale), density = Density(scale)) {
                val mode = if (dark) "dark" else "light"
                val browsed = mutableListOf<Int>()
                val reference = mutableStateOf(true)
                var referenceDismissed = 0
                setContent { CoachTheme(dark) { Surface { Column(Modifier.padding(24.dp)) {
                    val actions = WeekActions(controller, rememberCoroutineScope()).apply { onBrowse = { browsed += it } }
                    if (reference.value) Box {
                        TextButton({}, Modifier.heightIn(min = 48.dp)) { Text("第 2 周 · 换周 ▾") }
                        DropdownMenu(expanded = true, onDismissRequest = { referenceDismissed++ }) {
                            view.weeks.forEach { DropdownMenuItem(text = { Text("${view.weekLabel(it)} · 还有 1 节没会") }, onClick = {}) }
                            DropdownMenuItem(text = { Text("全部课程") }, onClick = {})
                        }
                    } else WeekPicker(view, actions, true)
                } } } }
                waitForIdle()
                val labels = view.weeks.map { "第 $it 周 · 还有 1 节没会" } + "全部课程"
                val rows = labels.map { hasText(it) and hasClickAction() }
                val ref = shot("$mode-menu-reference", dialog = true)
                val refBounds = rows.map { first(it).boundsInRoot }
                fun key(k: Key) {
                    val target = rows.firstOrNull { focused(it) }?.let { onNode(it) } ?: onAllNodes(isRoot()).onLast()
                    target.performKeyInput { pressKey(k) }; waitForIdle()
                }
                fun focusedRow() = rows.indexOfFirst { focused(it) }
                // Baseline for Escape: the untouched Material menu in this offscreen harness.
                key(Key.DirectionDown); key(Key.Escape)
                val materialEscapeDismisses = referenceDismissed > 0
                runOnIdle { reference.value = false }; waitForIdle()

                val picker = hasText("第 2 周 · 换周 ▾") and hasClickAction()
                fun open() {
                    onNode(picker).requestFocus(); waitForIdle()
                    onNode(picker).performKeyInput { pressKey(Key.Enter) }; waitForIdle()
                    assertEquals(1, onAllNodes(rows.last()).fetchSemanticsNodes().size, "$mode Enter opens the menu with 全部课程")
                }
                open()
                val opened = shot("$mode-menu-opened", dialog = true)
                assertEquals(-1, focusedRow(), "$mode no row is focused before the first arrow key")
                assertEquals(refBounds, rows.map { first(it).boundsInRoot }, "$mode menu rows keep Material's size and position")
                // The focused picker's ring sits in the window behind the menu, so compare the menu itself (rows, padding, side and bottom shadow).
                val pad = 8 * scale; val shadow = 6 * scale
                val menu = Rect(refBounds.first().left - shadow, refBounds.first().top - pad, refBounds.last().right + shadow, refBounds.last().bottom + pad + shadow)
                val menuDiff = countDiff(ref, opened, menu)
                println("TON-1603 week $mode menu rows=${labels.size} size=${(menu.width / scale).roundToInt()}x${(menu.height / scale).roundToInt()}dp unfocused-vs-material diff=$menuDiff")
                assertEquals(0, menuDiff, "$mode unfocused menu differs from the Material menu")

                val order = mutableListOf<Int>()
                for ((i, row) in rows.withIndex()) {
                    key(Key.DirectionDown); order += focusedRow()
                    assertTrue(focused(row), "$mode ↓ ${i + 1} should land on ${labels[i]}")
                    val focusedShot = measureInside(mode, labels[i], row, ref, edgeRows = i == 0 || i == rows.lastIndex)
                    if (i > 0) assertRegionEqual(ref, focusedShot, refBounds[i - 1], "$mode ${labels[i - 1]} after focus moved on")
                }
                key(Key.DirectionUp); order += focusedRow()
                println("TON-1603 week $mode menu down-order=${order.dropLast(1)} up-from-last=${order.last()}")
                assertEquals(rows.lastIndex - 1, order.last(), "$mode ↑ goes back one row")
                assertRegionEqual(ref, shot("$mode-menu-after-up", dialog = true), refBounds.last(), "$mode 全部课程 after focus moved off")

                key(Key.Escape)
                val escapeClosed = onAllNodes(rows.last()).fetchSemanticsNodes().isEmpty()
                println("TON-1603 week $mode menu escape material-dismisses=$materialEscapeDismisses picker-closes=$escapeClosed")
                assertEquals(materialEscapeDismisses, escapeClosed, "$mode Escape behaves like the Material menu")
                assertEquals(emptyList(), browsed, "$mode Escape picks nothing")
                if (!escapeClosed) { onNode(picker).performClick(); waitForIdle() }
                if (onAllNodes(rows.last()).fetchSemanticsNodes().isNotEmpty()) { onAllNodes(isRoot()).onLast().performMouseInput { click(Offset(width - 4f, height - 4f)) }; waitForIdle() }
                assertEquals(0, onAllNodes(rows.last()).fetchSemanticsNodes().size, "$mode menu closed before reopening")
                open(); key(Key.DirectionDown); key(Key.DirectionDown); key(Key.DirectionDown)
                assertTrue(focused(rows[2]), "$mode third row focused before Enter")
                key(Key.Enter)
                assertEquals(listOf(3), browsed, "$mode Enter on 第 3 周")
                assertEquals(0, onAllNodes(rows.last()).fetchSemanticsNodes().size, "$mode Enter closes the menu")
                open(); repeat(rows.size) { key(Key.DirectionDown) }
                key(Key.Enter)
                assertEquals(listOf(3, 0), browsed, "$mode Enter on 全部课程 selects the whole course")
                assertEquals(0, onAllNodes(rows.last()).fetchSemanticsNodes().size, "$mode menu closed after 全部课程")
                open()
                onNode(rows.first()).performMouseInput { click(Offset(2 * scale, 2 * scale)) }; waitForIdle()
                assertEquals(listOf(3, 0, 1), browsed, "$mode a click 2dp inside the first row's corner still picks it")
                println("TON-1603 week $mode menu enter/escape/click browsed=$browsed escape-closed=$escapeClosed")
            }
        }
    }

    /**
     * The top-bar week name (Desk crumb) opens the 换周 dialog when the page passes onCrumbClick.
     * Real WeekPage, wide window (crumb centred in the 48dp bar): Tab reaches it from 目录, the ring hugs the text box ≥3:1,
     * blur restores the pixels, Enter opens the real dialog. Without onCrumbClick the crumb stays plain text:
     * the bar has exactly one focus stop fewer and the crumb takes no focus.
     */
    @Test fun weekCrumbShowsRingAndOpensTheWeekDialog() {
        val view = threeWeeks()
        withController { controller ->
            for (dark in listOf(false, true)) {
                val mode = if (dark) "dark" else "light"
                val stops = mutableMapOf<Boolean, Int>()
                runSkikoComposeUiTest(size = Size(1224f * scale, 900f * scale), density = Density(scale)) {
                    lateinit var focus: FocusManager
                    setContent { CoachTheme(dark) { Surface {
                        focus = LocalFocusManager.current
                        WeekPage(controller, view, TutorUiState(), WeekActions(controller, rememberCoroutineScope()), { _, _ -> }, 2)
                    } } }
                    waitForIdle()
                    val crumb = hasTestTag("v7-week-picker")
                    val directory = hasTestTag("v7-directory-toggle")
                    val before = shot("$mode-crumb-unfocused")
                    val b = first(crumb).boundsInRoot
                    println("TON-1603 week $mode crumb bounds=${b.left / scale},${b.top / scale} ${b.width / scale}x${b.height / scale}dp")

                    onNode(directory).requestFocus(); waitForIdle(); onNode(directory).assertIsFocused()
                    val n = tabUntil(crumb)
                    assertTrue(focused(crumb), "$mode Tab never reached the top-bar week name")
                    assertEquals(b, first(crumb).boundsInRoot, "$mode focusing must not move or resize the week name")
                    measure(mode, "顶栏周名", crumb, before, pageDx = 8f)
                    println("TON-1603 week $mode crumb tab-stops 目录→周名=$n")
                    runOnIdle { focus.clearFocus() }; waitForIdle()
                    assertRegionEqual(before, shot("$mode-crumb-blurred"), b.inflate(10 * scale), "$mode week name after blur")

                    assertEquals(0, onAllNodes(isDialog()).fetchSemanticsNodes().size)
                    enter(crumb)
                    assertEquals(1, onAllNodes(isDialog()).fetchSemanticsNodes().size, "$mode Enter on the week name opens the dialog")
                    onNode(hasText("换周") and hasAnyAncestor(isDialog())).assertExists()
                    shot("$mode-crumb-dialog", dialog = true)
                    enter(hasText("关闭") and hasClickAction() and hasAnyAncestor(isDialog()))
                    assertEquals(0, onAllNodes(isDialog()).fetchSemanticsNodes().size, "$mode 关闭 closes the dialog")
                    println("TON-1603 week $mode crumb enter dialog-opened=1 closed=1")
                }
                for (clickable in listOf(true, false)) runSkikoComposeUiTest(size = Size(1224f * scale, 900f * scale), density = Density(scale)) {
                    setContent { CoachTheme(dark) { Surface {
                        Desk("合成课 · 第 2 周", onCrumbClick = if (clickable) ({}) else null) { Text("合成正文") }
                    } } }
                    waitForIdle()
                    stops[clickable] = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)).fetchSemanticsNodes().size
                    val name = onNode(hasText("合成课 · 第 2 周"))
                    if (!clickable) {
                        assertEquals(0, onAllNodes(hasTestTag("v7-week-picker")).fetchSemanticsNodes().size)
                        name.assert(!hasClickAction())
                        name.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
                    }
                }
                println("TON-1603 week $mode crumb focus-stops with=${stops[true]} without=${stops[false]}")
                assertEquals(stops.getValue(true) - 1, stops.getValue(false), "$mode without onCrumbClick the bar has exactly one focus stop fewer")
            }
        }
    }

    /**
     * Desk 目录 / 收起目录 on the real WeekPage. First, for both modes, the never-focused frames: the page, and the drawer opened by mouse
 * (Material focuses 收起目录 then, which must stay as invisible as in Material).
     * Then by keyboard: Tab reaches 目录, its ring sits inside the pill (the pill starts right where "Coach" ends and 4dp
     * below the window top) ≥3:1, blur restores, Enter opens the drawer;
     * Tab reaches 收起目录, whose ring is likewise inside its pill (the drawer clips at its left edge) and complete on all four sides,
     * blur restores the open drawer, Enter closes it.
     */
    @Test fun deskDirectoryToggleAndCloseShowRingAndKeepTheirActions() {
        val view = threeWeeks()
        val toggle = hasTestTag("v7-directory-toggle")
        val close = hasText("收起目录") and hasClickAction()
        val drawer = hasTestTag("v7-directory-drawer")
        withController { controller ->
            val baseline = mutableMapOf<String, Pair<PixelMap, Rect>>()
            for (dark in listOf(false, true)) runSkikoComposeUiTest(size = Size(1224f * scale, 900f * scale), density = Density(scale)) {
                val mode = if (dark) "dark" else "light"
                setContent { CoachTheme(dark) { Surface { WeekPage(controller, view, TutorUiState(), WeekActions(controller, rememberCoroutineScope()), { _, _ -> }, 2) } } }
                waitForIdle()
                baseline["$mode-page"] = shot("$mode-directory-unfocused") to first(toggle).boundsInRoot
                assertEquals(0, onAllNodes(drawer).fetchSemanticsNodes().size, "$mode drawer starts closed")
                onNode(toggle).performClick(); waitForIdle()
                assertEquals(1, onAllNodes(drawer).fetchSemanticsNodes().size, "$mode a click on 目录 opens the drawer")
                baseline["$mode-drawer"] = shot("$mode-drawer-unfocused") to first(close).boundsInRoot
                println("TON-1603 week $mode 目录 mouse-open 收起目录 focused=${focused(close)} (Material moves focus there; no ring while input is pointer)")
                println("TON-1603 week $mode 目录 bounds=${baseline.getValue("$mode-page").second.let { "${it.left / scale},${it.top / scale} ${it.width / scale}x${it.height / scale}dp" }} " +
                    "收起目录 bounds=${baseline.getValue("$mode-drawer").second.let { "${it.left / scale},${it.top / scale} ${it.width / scale}x${it.height / scale}dp" }}")
            }
            for (dark in listOf(false, true)) runSkikoComposeUiTest(size = Size(1224f * scale, 900f * scale), density = Density(scale)) {
                val mode = if (dark) "dark" else "light"
                lateinit var focus: FocusManager
                setContent { CoachTheme(dark) { Surface {
                    focus = LocalFocusManager.current
                    WeekPage(controller, view, TutorUiState(), WeekActions(controller, rememberCoroutineScope()), { _, _ -> }, 2)
                } } }
                waitForIdle()
                val (page, toggleBounds) = baseline.getValue("$mode-page")
                assertRegionEqual(page, shot("$mode-directory-again"), Rect(0f, 0f, 1224f * scale, 900f * scale), "$mode page renders the same twice")
                onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Tab) }; waitForIdle()
                val n = 1 + tabUntil(toggle)
                assertTrue(focused(toggle), "$mode Tab never reached 目录")
                assertEquals(toggleBounds, first(toggle).boundsInRoot, "$mode focusing must not move or resize 目录")
                measurePillInside(mode, "目录", toggle, page)
                runOnIdle { focus.clearFocus() }; waitForIdle()
                assertRegionEqual(page, shot("$mode-directory-blurred"), toggleBounds.inflate(10 * scale), "$mode 目录 after blur")

                onNode(toggle).requestFocus(); waitForIdle()
                enter(toggle)
                assertEquals(1, onAllNodes(drawer).fetchSemanticsNodes().size, "$mode Enter on 目录 opens the drawer")
                val (open, closeBounds) = baseline.getValue("$mode-drawer")
                assertEquals(closeBounds, first(close).boundsInRoot, "$mode 收起目录 keeps its size and place")
                var m = 0
                while (!focused(close) && m < 40 && tabOnce()) m++
                assertTrue(focused(close), "$mode Tab never reached 收起目录")
                val inside = measurePillInside(mode, "收起目录", close, open)
                println("TON-1603 week $mode 目录 tab-stops start→目录=$n 目录→收起目录=$m")
                runOnIdle { focus.clearFocus() }; waitForIdle()
                assertRegionEqual(open, shot("$mode-drawer-blurred"), closeBounds.inflate(10 * scale).intersect(Rect(0f, 0f, 300f * scale, 900f * scale)), "$mode 收起目录 after blur")
                assertTrue(countDiff(open, inside, closeBounds) > 0, "$mode the focused frame shows a ring")
                onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Tab) }; waitForIdle()
                var k = 1
                while (!focused(close) && k < 40 && tabOnce()) k++
                assertTrue(focused(close), "$mode Tab from a blurred page never reached 收起目录")
                assertRegionEqual(inside, shot("$mode-收起目录-tabbed"), closeBounds.inflate(10 * scale).intersect(Rect(0f, 0f, 300f * scale, 900f * scale)), "$mode Tab-reached 收起目录 shows the same ring")
                println("TON-1603 week $mode 目录 tab-stops blurred→收起目录=$k")

                onNode(close).requestFocus(); waitForIdle()
                enter(close)
                assertEquals(0, onAllNodes(drawer).fetchSemanticsNodes().size, "$mode Enter on 收起目录 closes the drawer")
                println("TON-1603 week $mode 目录 enter opened=1 closed=1")
            }
        }
    }

    /**
     * Desk 返回路线, 出处与批注／收起 and 专心读／退出专心读 on the real WeekPage, in a wide window (1224dp) and on both sides of
     * the 700dp split (700dp Medium with the margin column, 699dp compact with the margin sheet).
     * First the never-focused frames and bounds; then by keyboard each entrance shows the ring ≥3:1, keeps its bounds,
     * blur restores the pixels and Enter keeps its action. Without onBack the bar has exactly one focus stop fewer.
     */
    @Test fun deskBackMarginAndReadingTogglesShowRingAndKeepTheirActions() {
        val view = threeWeeks()
        val back = hasText("← 路线") and hasClickAction()
        val margin = hasTestTag("v7-margin-toggle")
        val reading = hasTestTag("v7-focus")
        val widths = listOf(1224f, 700f, 699f)
        withController { controller ->
            val baseline = mutableMapOf<String, Pair<PixelMap, List<Rect>>>()
            for (dark in listOf(false, true)) for (w in widths) runSkikoComposeUiTest(size = Size(w * scale, 900f * scale), density = Density(scale)) {
                val mode = "${if (dark) "dark" else "light"}-${w.toInt()}"
                setContent { CoachTheme(dark) { Surface { CompositionLocalProvider(LocalDeskSelect provides {}) {
                    WeekPage(controller, view, TutorUiState(), WeekActions(controller, rememberCoroutineScope()), { _, _ -> }, 2)
                } } } }
                waitForIdle()
                val bounds = listOf(back, margin, reading).map { first(it).boundsInRoot }
                baseline[mode] = shot("$mode-bar-unfocused") to bounds
                val stops = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)).fetchSemanticsNodes().size
                println("TON-1603 week $mode bar labels=${listOf(back, margin, reading).joinToString("/") { first(it).config[SemanticsProperties.Text].joinToString() }} " +
                    "bounds=" + bounds.joinToString(" ") { "${it.left / scale},${it.top / scale} ${it.width / scale}x${it.height / scale}dp" } + " stops=$stops")
                onNode(reading).performClick(); waitForIdle()
                assertTrue(onAllNodes(hasText("退出专心读")).fetchSemanticsNodes().isNotEmpty(), "$mode a click on 专心读 enters focused reading")
                println("TON-1603 week $mode bar click 专心读 → focused=${focused(reading)}")
                onNode(reading).performClick(); waitForIdle()
                assertTrue(onAllNodes(hasText("专心读")).fetchSemanticsNodes().isNotEmpty(), "$mode a second click leaves focused reading")
            }
            for (dark in listOf(false, true)) for (w in widths) runSkikoComposeUiTest(size = Size(w * scale, 900f * scale), density = Density(scale)) {
                val mode = "${if (dark) "dark" else "light"}-${w.toInt()}"
                val inner = w >= 700f
                val picked = mutableListOf<AppTab>()
                lateinit var focus: FocusManager
                setContent { CoachTheme(dark) { Surface { CompositionLocalProvider(LocalDeskSelect provides { picked += it }) {
                    focus = LocalFocusManager.current
                    WeekPage(controller, view, TutorUiState(), WeekActions(controller, rememberCoroutineScope()), { _, _ -> }, 2)
                } } } }
                waitForIdle()
                val (before, bounds) = baseline.getValue(mode)
                assertRegionEqual(before, shot("$mode-bar-again"), Rect(0f, 0f, w * scale, 900f * scale), "$mode page renders the same twice")
                fun blurRestores(what: String, b: Rect) {
                    runOnIdle { focus.clearFocus() }; waitForIdle()
                    assertRegionEqual(before, shot("$mode-$what-blurred"), b.inflate(10 * scale), "$mode $what after blur")
                }

                onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Tab) }; waitForIdle()
                val toBack = 1 + tabUntil(back)
                assertTrue(focused(back), "$mode Tab never reached 返回路线")
                assertEquals(bounds[0], first(back).boundsInRoot, "$mode focusing must not move or resize 返回路线")
                measurePillInside(mode, "返回路线", back, before)
                val toMargin = tabUntil(margin)
                assertTrue(focused(margin), "$mode Tab never reached the margin toggle")
                assertEquals(bounds[1], first(margin).boundsInRoot, "$mode focusing must not move or resize the margin toggle")
                measure(mode, if (inner) "收起" else "出处与批注", margin, before, pageDx = 8f)
                val toReading = tabUntil(reading)
                assertTrue(focused(reading), "$mode Tab never reached 专心读")
                assertEquals(bounds[2], first(reading).boundsInRoot, "$mode focusing must not move or resize 专心读")
                measure(mode, "专心读", reading, before, pageDx = 8f)
                println("TON-1603 week $mode bar tab-stops start→返回=$toBack 返回→出处=$toMargin 出处→专心读=$toReading")
                blurRestores("返回路线", bounds[0]); blurRestores("出处", bounds[1]); blurRestores("专心读", bounds[2])

                onNode(reading).requestFocus(); waitForIdle()
                enter(reading)
                assertTrue(onAllNodes(hasText("退出专心读")).fetchSemanticsNodes().isNotEmpty(), "$mode Enter on 专心读 enters focused reading")
                assertEquals(0, onAllNodes(margin).fetchSemanticsNodes().size, "$mode focused reading hides the margin toggle")
                assertTrue(focused(reading), "$mode focus stays on the toggle after Enter")
                runOnIdle { focus.clearFocus() }; waitForIdle()
                val quiet = shot("$mode-退出专心读-unfocused")
                onNode(reading).requestFocus(); waitForIdle()
                measure(mode, "退出专心读", reading, quiet, pageDx = 8f)
                enter(reading)
                assertTrue(onAllNodes(hasText("专心读")).fetchSemanticsNodes().isNotEmpty(), "$mode Enter on 退出专心读 leaves focused reading")
                assertEquals(1, onAllNodes(margin).fetchSemanticsNodes().size, "$mode the margin toggle is back")

                onNode(back).requestFocus(); waitForIdle()
                enter(back)
                assertEquals(listOf(AppTab.ROUTE), picked, "$mode Enter on 返回路线 goes to the route")

                onNode(margin).requestFocus(); waitForIdle()
                enter(margin)
                if (inner) {
                    assertTrue(onAllNodes(hasText("出处与批注")).fetchSemanticsNodes().isNotEmpty(), "$mode Enter on 收起 collapses the margin")
                    assertEquals(0, onAllNodes(hasTestTag("v7-margin")).fetchSemanticsNodes().size, "$mode the margin column is gone")
                    assertTrue(focused(margin), "$mode focus stays on the toggle after Enter")
                    enter(margin)
                    assertTrue(onAllNodes(hasText("收起")).fetchSemanticsNodes().isNotEmpty(), "$mode Enter on 出处与批注 brings the margin back")
                    assertEquals(1, onAllNodes(hasTestTag("v7-margin")).fetchSemanticsNodes().size, "$mode the margin column is back")
                } else assertEquals(1, onAllNodes(hasTestTag("v7-margin-sheet")).fetchSemanticsNodes().size, "$mode Enter on 出处与批注 opens the margin sheet")
                println("TON-1603 week $mode bar enter reading=on/off back=${picked.single()} margin=${if (inner) "collapsed/restored" else "sheet"}")
            }
            for (dark in listOf(false, true)) {
                val stops = mutableMapOf<Boolean, Int>()
                for (withBack in listOf(true, false)) runSkikoComposeUiTest(size = Size(1224f * scale, 900f * scale), density = Density(scale)) {
                    setContent { CoachTheme(dark) { Surface { Desk("合成课 · 第 2 周", onBack = if (withBack) ({}) else null) { Text("合成正文") } } } }
                    waitForIdle()
                    stops[withBack] = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)).fetchSemanticsNodes().size
                    assertEquals(if (withBack) 1 else 0, onAllNodes(hasText("‹")).fetchSemanticsNodes().size)
                }
                println("TON-1603 week ${if (dark) "dark" else "light"} bar back focus-stops with=${stops[true]} without=${stops[false]}")
                assertEquals(stops.getValue(true) - 1, stops.getValue(false), "without onBack the bar has exactly one focus stop fewer")
            }
        }
    }

    /**
     * The reading page's 问一句… / 开始做题 → under the text and 打开原页 → / 继续练习 → in the margin cards (SourceThumbnail,
     * MarginQuestion), in a wide window (1224dp), at 700dp (margin column) and 699dp (the cards live in the 出处与批注 sheet).
     * Never-focused frames and bounds first; then by keyboard each shows the ring ≥3:1 unclipped, keeps its bounds,
     * blur restores the pixels and Enter keeps its callback. The card links sit 8dp under the thumbnail / explanation,
     * so their ring is measured downward into the card's 12dp bottom padding.
     */
    @Test fun readingAskQuizSourceAndPracticeShowRingAndKeepTheirActions() {
        val view = threeWeeks()
        val kp = view.kp("c")!!
        val text = "合成正文第一段，讲一个要点。\n\n合成正文第二段。"
        val details = V7PageDetails(course = "合成课", readStatus = "读到第 1 段", sourceLabel = "合成教材 · 第 3 页",
            sourceText = "合成教材第 3 页的原文缩略。", thumbnailCaption = "图 1",
            question = V7MarginQuestion("合成题干：哪一项对？", listOf("合成选项一", "合成选项二"), 0, "为什么：合成解释。", 3, "合成教材 3"))
        val ask = hasTestTag("v7-reading-ask")
        val quiz = hasTestTag("v7-reading-quiz")
        val open = hasText("打开原页 →") and hasClickAction()
        val practice = hasText("继续练习 →") and hasClickAction()
        val all = listOf("问一句" to ask, "开始做题" to quiz, "打开原页" to open, "继续练习" to practice)
        val widths = listOf(1224f, 700f, 699f)
        fun bounds(t: ComposeUiTest, ms: List<Pair<String, SemanticsMatcher>>) = ms.associate { (n, m) -> n to t.first(m).boundsInRoot }
        withController { controller ->
            for (dark in listOf(false, true)) for (w in widths) runSkikoComposeUiTest(size = Size(w * scale, 900f * scale), density = Density(scale)) {
                val mode = "${if (dark) "dark" else "light"}-${w.toInt()}"
                val inner = w >= 700f
                val calls = mutableListOf<String>()
                lateinit var focus: FocusManager
                setContent { CoachTheme(dark) { Surface { CompositionLocalProvider(LocalV7PageDetails provides details) {
                    focus = LocalFocusManager.current
                    val reading = remember { mutableStateOf(false) }
                    ReadingPage(controller, kp, view, text, reading.value, { reading.value = !reading.value }, null,
                        onAsk = { calls += "问一句" }, onQuiz = { calls += "做题" }, onSource = { calls += "原页" }, weekActions = null, onSelectionAsk = {})
                } } } }
                waitForIdle()
                // Page entrances (and the margin cards when the window is wide enough to show them).
                val pageItems = if (inner) all else all.take(2)
                val before = shot("$mode-read-unfocused")
                val b0 = bounds(this, pageItems)
                println("TON-1603 week $mode read bounds=" + b0.entries.joinToString(" ") { (n, r) -> "$n@${r.left / scale},${r.top / scale} ${r.width / scale}x${r.height / scale}dp" })
                fun blurRestores(shotName: String, ref: PixelMap, r: Rect, dialog: Boolean = false) {
                    runOnIdle { focus.clearFocus() }; waitForIdle()
                    assertRegionEqual(ref, shot("$mode-$shotName-blurred", dialog), r.inflate(10 * scale), "$mode $shotName after blur")
                }
                onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Tab) }; waitForIdle()
                val stops = mutableListOf<String>()
                for ((n, m) in pageItems) {
                    stops += "$n=${tabUntil(m)}"
                    assertTrue(focused(m), "$mode Tab never reached $n")
                    assertEquals(b0.getValue(n), first(m).boundsInRoot, "$mode focusing must not move or resize $n")
                    measure(mode, n, m, before, pageDx = 8f, walkDown = n == "打开原页" || n == "继续练习")
                }
                println("TON-1603 week $mode read tab-stops ${stops.joinToString(" ")}")
                for ((n, _) in pageItems) blurRestores(n, before, b0.getValue(n))
                for ((n, m) in pageItems) { onNode(m).requestFocus(); waitForIdle(); enter(m); assertTrue(focused(m), "$mode focus stays on $n after Enter") }
                assertEquals(if (inner) listOf("问一句", "做题", "原页", "做题") else listOf("问一句", "做题"), calls, "$mode Enter keeps each callback")
                if (!inner) {
                    // 699dp: the margin cards sit in the 出处与批注 sheet. Open it by keyboard, then Tab inside it.
                    val toggle = hasTestTag("v7-margin-toggle")
                    onNode(toggle).requestFocus(); waitForIdle(); enter(toggle)
                    assertEquals(1, onAllNodes(hasTestTag("v7-margin-sheet")).fetchSemanticsNodes().size, "$mode the margin sheet is open")
                    val sheetBefore = shot("$mode-read-sheet-unfocused", dialog = true)
                    val sheetItems = all.drop(2)
                    val bs = bounds(this, sheetItems)
                    println("TON-1603 week $mode read sheet bounds=" + bs.entries.joinToString(" ") { (n, r) -> "$n@${r.left / scale},${r.top / scale} ${r.width / scale}x${r.height / scale}dp" })
                    val sheetStops = mutableListOf<String>()
                    for ((n, m) in sheetItems) {
                        sheetStops += "$n=${tabUntil(m)}"
                        assertTrue(focused(m), "$mode Tab never reached $n in the sheet")
                        assertEquals(bs.getValue(n), first(m).boundsInRoot, "$mode focusing must not move or resize $n")
                        measure(mode, n, m, sheetBefore, pageDx = 8f, dialog = true, walkDown = true)
                    }
                    println("TON-1603 week $mode read sheet tab-stops ${sheetStops.joinToString(" ")}")
                    // The sheet is its own window, so the page's FocusManager can't clear it; blur each card link by moving
                    // focus to the other one, which leaves the first exactly as it was never focused.
                    for ((i, nm) in sheetItems.withIndex()) {
                        val (n, m) = nm
                        val other = sheetItems[1 - i].second
                        onNode(m).requestFocus(); waitForIdle(); assertTrue(focused(m), "$mode $n focused in the sheet")
                        onNode(other).requestFocus(); waitForIdle(); assertTrue(focused(other), "$mode focus moved off $n")
                        assertRegionEqual(sheetBefore, shot("$mode-$n-blurred", dialog = true), bs.getValue(n).inflate(10 * scale), "$mode $n after blur")
                    }
                    for ((n, m) in sheetItems) { onNode(m).requestFocus(); waitForIdle(); enter(m) }
                    assertEquals(listOf("问一句", "做题", "原页", "做题"), calls, "$mode Enter keeps each callback in the sheet")
                }
                println("TON-1603 week $mode read enter calls=$calls")
            }
            // A mouse click still runs each callback with the never-focused bounds.
            for (w in widths) runSkikoComposeUiTest(size = Size(w * scale, 900f * scale), density = Density(scale)) {
                val calls = mutableListOf<String>()
                setContent { CoachTheme(false) { Surface { CompositionLocalProvider(LocalV7PageDetails provides details) {
                    ReadingPage(controller, kp, view, text, false, {}, null,
                        onAsk = { calls += "问一句" }, onQuiz = { calls += "做题" }, onSource = { calls += "原页" }, weekActions = null, onSelectionAsk = {})
                } } } }
                waitForIdle()
                val items = if (w >= 700f) all else all.take(2)
                for ((_, m) in items) { onNode(m).performClick(); waitForIdle() }
                assertEquals(if (w >= 700f) listOf("问一句", "做题", "原页", "做题") else listOf("问一句", "做题"), calls, "${w.toInt()} mouse clicks keep each callback")
                println("TON-1603 week light-${w.toInt()} read click calls=$calls")
            }
        }
    }

    /**
     * Reading page footer: ‹ previous section and next section › step through the week's sections.
     * Each existing direction shows the hugging ring on Tab (1224 and 700dp; at 699dp the page has no footer), Enter opens
     * that section, blur restores the pixels, and a mouse click on the label or the empty rest of its half still opens it once.
     * The first section's missing ‹, the last section's missing ›, and both when there is no week navigation leave no Tab stop.
     */
    @Test fun readingFooterSectionStepsShowRingAndLeaveNoDeadStops() {
        val view = oneWeekThreeSections()
        val text = "合成正文第一段，讲一个要点。\n\n合成正文第二段。"
        val ids = listOf("s1", "s2", "s3")
        val prev = hasTestTag("v7-reading-previous")
        val next = hasTestTag("v7-reading-next")
        data class Case(val name: String, val at: Int, val live: Boolean)
        val cases = listOf(Case("首节", 0, true), Case("中节", 1, true), Case("末节", 2, true), Case("无回调", 1, false))
        withController { controller ->
            for (dark in listOf(false, true)) for (w in listOf(1224f, 700f, 699f)) runSkikoComposeUiTest(size = Size(w * scale, 900f * scale), density = Density(scale)) {
                val mode = "${if (dark) "dark" else "light"}-${w.toInt()}"
                val opened = mutableListOf<String?>()
                val shown = mutableStateOf(cases[0])
                lateinit var focus: FocusManager
                setContent { CoachTheme(dark) { Surface {
                    focus = LocalFocusManager.current
                    val scope = rememberCoroutineScope()
                    val live = remember { WeekActions(controller, scope).apply { onOpen = { opened += it } } }
                    val case = shown.value
                    ReadingPage(controller, view.kp(ids[case.at])!!, view, text, false, {}, null,
                        onAsk = {}, onQuiz = {}, onSource = {}, weekActions = if (case.live) live else null, onSelectionAsk = {})
                } } }
                // Never-focused frame of every case first, so the same frames can be compared with the parent ReadingPage.
                val frames = cases.associateWith { case ->
                    runOnIdle { shown.value = case }; waitForIdle()
                    shot("$mode-footer-${case.name}-unfocused")
                }
                for (case in cases) {
                    runOnIdle { focus.clearFocus(); shown.value = case }; waitForIdle()
                    val before = frames.getValue(case)
                    val counter = hasText("${case.at + 1} / ${ids.size}")
                    if (w < 700f) {
                        // Compact window: Desk shows no footer on the reading page, so there is nothing to step or focus.
                        assertEquals(0, onAllNodes(counter).fetchSemanticsNodes().size, "$mode ${case.name} footer counter")
                        assertEquals(0, onAllNodes(hasText("‹", substring = true)).fetchSemanticsNodes().size + onAllNodes(hasText("›", substring = true)).fetchSemanticsNodes().size,
                            "$mode ${case.name} no footer step")
                        println("TON-1603 week $mode footer ${case.name} absent (compact Desk has no footer)")
                        continue
                    }
                    val band = first(counter).boundsInRoot
                    val dirs = listOf(Triple("上一节", prev, case.at - 1), Triple("下一节", next, case.at + 1))
                    val live = dirs.filter { case.live && it.third in ids.indices }
                    val dead = dirs - live.toSet()
                    val b0 = live.associate { (n, m, _) -> n to first(m).boundsInRoot }
                    // A full Tab cycle: every stop inside the footer band must be a live step, and each live step is reached once.
                    if (live.isNotEmpty()) { onNode(live[0].second).requestFocus(); waitForIdle() }
                    else { onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Tab) }; waitForIdle() }
                    fun focusedNode() = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).fetchSemanticsNodes().firstOrNull()
                    val start = focusedNode()?.id
                    val footerStops = mutableListOf<String>()
                    var n = 0
                    do {
                        val f = focusedNode() ?: break
                        if (abs(f.boundsInRoot.center.y - band.center.y) < 12 * scale)
                            footerStops += live.firstOrNull { (_, m, _) -> first(m).id == f.id }?.first ?: "空位@${(f.boundsInRoot.left / scale).roundToInt()}dp"
                        tabFromFocused(); n++
                    } while (focusedNode()?.id != start && n < 80)
                    println("TON-1603 week $mode footer ${case.name} tab-cycle=$n closed=${focusedNode()?.id == start} footer-stops=$footerStops")
                    assertEquals(live.map { it.first }, footerStops, "$mode ${case.name} Tab stops in the footer (a blank or callback-less step must not take focus)")
                    for ((dn, dm, _) in dead) {
                        onNode(dm).assertIsNotEnabled()
                        runCatching { onNode(dm).requestFocus() }; waitForIdle()
                        assertTrue(!focused(dm), "$mode ${case.name} $dn took focus without a target")
                    }
                    for ((dn, m, _) in live) {
                        onNode(m).requestFocus(); waitForIdle(); onNode(m).assertIsFocused()
                        assertEquals(b0.getValue(dn), first(m).boundsInRoot, "$mode ${case.name} focusing must not move or resize $dn")
                        measure(mode, "页脚-${case.name}-$dn", m, before, pageDx = 8f)
                    }
                    runOnIdle { focus.clearFocus() }; waitForIdle()
                    val blurred = shot("$mode-footer-${case.name}-blurred")
                    for ((dn, _, _) in live) assertRegionEqual(before, blurred, b0.getValue(dn).inflate(10 * scale), "$mode ${case.name} $dn after blur")
                    for ((dn, m, at) in live) {
                        val count = opened.size
                        enter(m)
                        assertEquals(listOf(ids[at]), opened.drop(count), "$mode ${case.name} Enter on $dn opens ${ids[at]}")
                    }
                    // Mouse: the label, the empty rest of a live half, and a dead half (which must do nothing).
                    val root = onAllNodes(isRoot()).onFirst()
                    val rowWidth = w * scale
                    for ((dn, m, at) in dirs) {
                        val isLive = live.any { it.first == dn }
                        val halfX = if (dn == "上一节") band.left / 2 else (band.right + rowWidth) / 2
                        val points = if (isLive) {
                            val b = first(m).boundsInRoot
                            listOf("文字" to b.center, "半区空白" to Offset(if (dn == "上一节") b.right + 24 * scale else b.left - 24 * scale, b.center.y))
                        } else listOf("半区" to Offset(halfX, band.center.y))
                        for ((where, p) in points) {
                            val count = opened.size
                            root.performMouseInput { click(p) }; waitForIdle()
                            val want = if (isLive) listOf(ids[at]) else emptyList()
                            assertEquals(want, opened.drop(count), "$mode ${case.name} mouse click on $dn $where")
                        }
                    }
                }
                println("TON-1603 week $mode footer opened=$opened")
            }
        }
    }

    /**
     * Reading page 一句批注 dialog: 取消 and 保存 carry the V7 ring outside their pills.
     * Blank note: 保存 is disabled and a whole Tab cycle never stops on it. Tab in the field moves on instead of typing a tab
     * character (Shift+Tab goes back to 取消), so a typed note can reach 保存 by keyboard. With text: each shows the ring on Tab on all four
     * sides (nothing clipped by the dialog), ≥3:1 against the dialog surface and the pill's focused fill, focusing moves
     * nothing, blur restores the pixels. Enter or a click on 取消 closes without saving; on 保存 it saves the quoted note once.
     * Offscreen Skiko at 1224 / 700 / 699dp, not a native window.
     */
    @Test fun readingNoteDialogSaveAndCancelShowRingAndKeepTheirActions() {
        val view = threeWeeks()
        val kp = view.kp("c")!!
        val text = "合成正文第一段，讲一个要点。\n\n合成正文第二段。"
        val quote = "合成选中的一句"
        val details = V7PageDetails(course = "合成课", selection = quote)
        val entry = hasText("一句批注") and hasClickAction()
        val save = hasText("保存") and hasClickAction() and hasAnyAncestor(isDialog())
        val cancel = hasText("取消") and hasClickAction() and hasAnyAncestor(isDialog())
        val input = hasTestTag("v7-note-input")
        withController { controller ->
            fun notes() = controller.sync.retainedCommands().map { kotlinx.serialization.json.Json.parseToJsonElement(it.payloadBytes).toString() }
                .filter { "save_note" in it }
            for (dark in listOf(false, true)) for (w in listOf(1224f, 700f, 699f)) runSkikoComposeUiTest(size = Size(w * scale, 900f * scale), density = Density(scale)) {
                val mode = "${if (dark) "dark" else "light"}-${w.toInt()}"
                val saved0 = notes().size
                setContent { CoachTheme(dark) { Surface { CompositionLocalProvider(LocalV7PageDetails provides details) {
                    ReadingPage(controller, kp, view, text, false, {}, null,
                        onAsk = {}, onQuiz = {}, onSource = {}, weekActions = null, onSelectionAsk = {})
                } } } }
                waitForIdle()
                fun dialogs() = onAllNodes(isDialog()).fetchSemanticsNodes().size
                fun open() {
                    waitUntil(timeoutMillis = 10_000) { !first(entry).config.contains(SemanticsProperties.Disabled) }
                    onAllNodes(entry).onFirst().performClick(); waitForIdle()
                    assertEquals(1, dialogs(), "$mode 一句批注 opens the dialog")
                }
                fun focusedName(): String? = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true)).fetchSemanticsNodes().lastOrNull()?.let { n ->
                    n.config.getOrElseNullable(SemanticsProperties.TestTag) { null }
                        ?: n.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString()
                        ?: n.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.let { "input" } ?: "node@${n.boundsInRoot}"
                }
                /** Tab from 取消 until focus comes back to it: the dialog's whole cycle. */
                fun cycle(): List<String?> {
                    onNode(cancel).requestFocus(); waitForIdle(); assertTrue(focused(cancel), "$mode 取消 takes focus")
                    val stops = mutableListOf(focusedName())
                    while (stops.size < 10) { tabFromFocused(); if (focused(cancel)) break; stops += focusedName() }
                    assertTrue(focused(cancel), "$mode Tab cycle must come back to 取消: $stops")
                    return stops
                }

                // Blank note: 保存 disabled and no Tab stop.
                open()
                onNode(input).requestFocus(); waitForIdle()
                val blank = shot("$mode-note-blank-unfocused", dialog = true)
                onNode(save).assertIsNotEnabled()
                val blankStops = cycle()
                println("TON-1603 week $mode note blank tab-stops=$blankStops size=${blank.width}x${blank.height}")
                assertEquals(listOf("取消", "v7-note-input"), blankStops, "$mode blank note: Tab stops only on 取消 and the field")
                onNode(save).performClick(); waitForIdle()
                assertEquals(1, dialogs(), "$mode clicking the disabled 保存 keeps the dialog open")
                assertEquals(saved0, notes().size, "$mode the disabled 保存 saves nothing")

                // With text: ring on each button, four sides, blur restores.
                onNode(input).performTextInput("合成批注"); waitForIdle()
                val before = shot("$mode-note-filled-unfocused", dialog = true)
                val b0 = listOf(cancel, save).map { first(it).boundsInRoot }
                val buttons = Rect(b0[0].left, b0[0].top, b0[1].right, b0[1].bottom).inflate(6 * scale)
                println("TON-1603 week $mode note bounds 取消@${b0[0].left / scale},${b0[0].top / scale} ${b0[0].width / scale}x${b0[0].height / scale}dp 保存@${b0[1].left / scale},${b0[1].top / scale} ${b0[1].width / scale}x${b0[1].height / scale}dp")
                // Tab typed in the field leaves it for 保存 and types nothing; Shift+Tab leaves it for 取消.
                fun noteText() = first(input).config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text
                onNode(input).requestFocus(); waitForIdle()
                onNode(input).performKeyInput { pressKey(Key.Tab) }; waitForIdle()
                assertTrue(focused(save), "$mode Tab in the note field moves to 保存")
                onNode(input).requestFocus(); waitForIdle()
                onNode(input).performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft) }; waitForIdle()
                assertTrue(focused(cancel), "$mode Shift+Tab in the note field moves to 取消")
                assertEquals("合成批注", noteText(), "$mode Tab must not type a tab character into the note")
                val filledStops = cycle()
                println("TON-1603 week $mode note filled tab-stops=$filledStops")
                assertEquals(listOf("取消", "v7-note-input", "保存"), filledStops, "$mode filled note: Tab reaches the field, 保存 and 取消")
                for ((i, nm) in listOf("取消" to cancel, "保存" to save).withIndex()) {
                    val (n, m) = nm
                    onNode(m).requestFocus(); waitForIdle(); assertTrue(focused(m), "$mode $n focused")
                    assertEquals(b0[i], first(m).boundsInRoot, "$mode focusing must not move or resize $n")
                    measure(mode, "批注-$n", m, before, pageDx = 6f, dialog = true)
                    ringOnFourSides(mode, "批注-$n", first(m).boundsInRoot, shot("$mode-批注-$n-focused", dialog = true), before)
                }
                onNode(input).requestFocus(); waitForIdle()
                assertRegionEqual(before, shot("$mode-note-blurred", dialog = true), buttons, "$mode note buttons after blur")

                // Enter on 取消 closes, nothing saved; Enter on 保存 saves the quoted note once and closes.
                enter(cancel)
                assertEquals(0, dialogs(), "$mode Enter on 取消 closes the dialog")
                assertEquals(saved0, notes().size, "$mode 取消 saves nothing")
                open()
                onNode(save).assertIsEnabled()
                enter(save)
                assertEquals(0, dialogs(), "$mode Enter on 保存 closes the dialog")
                waitUntil(timeoutMillis = 10_000) { notes().size == saved0 + 1 }
                val note = notes().last()
                assertTrue(quote in note && "合成批注" in note && "\\t" !in note, "$mode 保存 saves the quote and the note, no tab: $note")
                // Mouse: 取消 closes without saving, 保存 saves once.
                open()
                onNode(input).performTextInput("合成第二条"); waitForIdle()
                onNode(cancel).performClick(); waitForIdle()
                assertEquals(0, dialogs(), "$mode click on 取消 closes the dialog")
                assertEquals(saved0 + 1, notes().size, "$mode click on 取消 saves nothing")
                open()
                onNode(save).performClick(); waitForIdle()
                assertEquals(0, dialogs(), "$mode click on 保存 closes the dialog")
                waitUntil(timeoutMillis = 10_000) { notes().size == saved0 + 2 }
                assertTrue("合成第二条" in notes().last(), "$mode click on 保存 saves the note")
                Thread.sleep(200); waitForIdle()
                assertEquals(saved0 + 2, notes().size, "$mode each 保存 saves exactly once")
                println("TON-1603 week $mode note tab-in-field=保存 shift-tab=取消 enter+click saved=${notes().size - saved0}")
            }
        }
    }

    /**
     * Reading page selection actions 划线 / 一句批注 / 再讲一遍 / 举个例子 / 复制: the margin's 选中这一句 menu at 1224 / 700dp,
     * the bottom bar at 699dp. Tab reaches each in order and shows the V7 ring hugging the label on all four sides (the menu's
     * rounded clip cuts none of it), ≥3:1 against the menu; focusing moves nothing and blur restores the pixels. Enter and a
     * real mouse click keep each action: 划线 saves the quoted line, 一句批注 opens its dialog, 再讲一遍 / 举个例子 ask about
     * the selection, 复制 copies it. Offscreen Skiko, not a native window.
     */
    @Test fun readingSelectionActionsShowRingAndKeepTheirCallbacks() {
        val view = threeWeeks()
        val kp = view.kp("c")!!
        val text = "合成正文第一段，讲一个要点。\n\n合成正文第二段。"
        val quote = "合成选中的一句"
        val details = V7PageDetails(course = "合成课", selection = quote)
        val names = listOf("划线", "一句批注", "再讲一遍", "举个例子", "复制")
        val actions = names.map { it to (hasText(it) and hasClickAction() and !hasAnyAncestor(isDialog())) }
        val clip = object : ClipboardManager {
            var copied: AnnotatedString? = null
            override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
            override fun getText() = copied
        }
        withController { controller ->
            fun notes() = controller.sync.retainedCommands().map { kotlinx.serialization.json.Json.parseToJsonElement(it.payloadBytes).toString() }
                .filter { "save_note" in it }
            for (dark in listOf(false, true)) for (w in listOf(1224f, 700f, 699f)) runSkikoComposeUiTest(size = Size(w * scale, 900f * scale), density = Density(scale)) {
                val mode = "${if (dark) "dark" else "light"}-${w.toInt()}"
                val bar = w < 700f
                val asks = mutableListOf<String>()
                lateinit var focus: FocusManager
                setContent { CoachTheme(dark) { Surface { CompositionLocalProvider(LocalV7PageDetails provides details, LocalClipboardManager provides clip) {
                    focus = LocalFocusManager.current
                    ReadingPage(controller, kp, view, text, false, {}, null,
                        onAsk = {}, onQuiz = {}, onSource = {}, weekActions = null, onSelectionAsk = { asks += it })
                } } } }
                waitForIdle()
                fun ready() = waitUntil(timeoutMillis = 10_000) { !first(actions[0].second).config.contains(SemanticsProperties.Disabled) }
                ready()
                fun dialogs() = onAllNodes(isDialog()).fetchSemanticsNodes().size
                val before = shot("$mode-sel-unfocused")
                val b0 = actions.map { (_, m) -> first(m).boundsInRoot }
                println("TON-1603 week $mode sel ${if (bar) "bar" else "menu"} bounds=" + names.zip(b0).joinToString(" ") { (n, r) -> "$n@${r.left / scale},${r.top / scale} ${r.width / scale}x${r.height / scale}dp" })
                // The label inside its click area: the menu pads 4dp above and below each row label, 6dp above 复制; the bar pads nothing.
                fun label(i: Int, r: Rect) = if (bar) r else Rect(r.left, r.top + (if (i == 4) 6 else 4) * scale, r.right, r.bottom - (if (i == 4) 0 else 4) * scale)
                onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Tab) }; waitForIdle()
                val stops = mutableListOf<String>()
                for ((i, nm) in actions.withIndex()) {
                    val (n, m) = nm
                    stops += "$n=${tabUntil(m)}"
                    assertTrue(focused(m), "$mode Tab never reached $n")
                    assertEquals(b0[i], first(m).boundsInRoot, "$mode focusing must not move or resize $n")
                    ringHugs(mode, "选区-$n", label(i, b0[i]), shot("$mode-选区-$n-focused"), before)
                }
                println("TON-1603 week $mode sel tab-stops ${stops.joinToString(" ")}")
                assertTrue(stops.drop(1).all { it.endsWith("=1") }, "$mode the five actions are consecutive Tab stops: $stops")
                runOnIdle { focus.clearFocus() }; waitForIdle()
                val all = b0.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }.inflate(10 * scale)
                assertRegionEqual(before, shot("$mode-sel-blurred"), all, "$mode selection actions after blur")

                /** Runs [act] on action [i] and checks that action's own effect. */
                fun check(how: String, i: Int, act: (SemanticsMatcher) -> Unit) {
                    val m = actions[i].second
                    val saved = notes().size; val asked = asks.size; clip.copied = null
                    act(m); waitForIdle()
                    when (i) {
                        0 -> { waitUntil(timeoutMillis = 10_000) { notes().size == saved + 1 }; assertTrue("> $quote" in notes().last(), "$mode $how 划线 saves the quoted line: ${notes().last()}"); ready() }
                        1 -> { assertEquals(1, dialogs(), "$mode $how 一句批注 opens the dialog"); onNode(hasText("取消") and hasAnyAncestor(isDialog())).performClick(); waitForIdle(); assertEquals(0, dialogs()) }
                        2 -> assertEquals("请再讲一遍：$quote", asks.drop(asked).single(), "$mode $how 再讲一遍")
                        3 -> assertEquals("请举例说明：$quote", asks.drop(asked).single(), "$mode $how 举个例子")
                        4 -> assertEquals(quote, clip.copied?.text, "$mode $how 复制 copies the selection")
                    }
                    if (i != 0) assertEquals(saved, notes().size, "$mode $how ${names[i]} saves nothing")
                    if (i != 2 && i != 3) assertEquals(asked, asks.size, "$mode $how ${names[i]} asks nothing")
                }
                // 划线 is disabled while it saves (unchanged behaviour), so focus leaves it; 一句批注 moves focus into its dialog.
                for (i in names.indices) check("Enter", i) { m -> enter(m); assertTrue(i <= 1 || focused(m), "$mode focus stays on ${names[i]} after Enter") }
                for (i in names.indices) check("mouse", i) { m -> onNode(m).performMouseInput { click() } }
                println("TON-1603 week $mode sel enter+mouse asks=${asks.size} notes=${notes().size} clip=${clip.copied?.text}")
            }
        }
    }

    /**
     * Ring hugging the label [b] on all four centre lines. Walking outward: the gap (at most the click area's light focus fill),
     * then the ring, ≥3:1 against what was there unfocused, then 1dp further the unfocused pixels (or that same focus fill) again. Gap and ring must be
     * V7FocusRingGap / V7FocusRingWidth on every side; a side cut by the menu's clip finds no ring there.
     */
    private fun ringHugs(mode: String, label: String, b: Rect, shot: PixelMap, before: PixelMap) {
        val cx = b.center.x.roundToInt(); val cy = b.center.y.roundToInt()
        val l = b.left.roundToInt(); val t = b.top.roundToInt(); val r = b.right.roundToInt() - 1; val bo = b.bottom.roundToInt() - 1
        fun k(p: Pair<Int, Int>) = contrast(shot[p.first, p.second], before[p.first, p.second])
        val sides = listOf("left" to { i: Int -> Pair(l - i, cy) }, "right" to { i: Int -> Pair(r + i, cy) },
            "top" to { i: Int -> Pair(cx, t - i) }, "bottom" to { i: Int -> Pair(cx, bo + i) })
        val got = sides.map { (n, at) ->
            var i = 1
            while (i < 24 && k(at(i)) < 3f) i++
            val inner = i
            while (i < 24 && k(at(i)) >= 3f) i++
            val gap = inner - 1; val width = i - inner
            val (x, y) = at((inner + i - 1) / 2)
            val ring = contrast(shot[x, y], before[x, y])
            val past = at(i - 1 + scale.roundToInt())
            assertTrue(abs(gap - V7FocusRingGap.value * scale) <= 1.5f, "$mode $label $n gap $gap px (ring must hug the label)")
            assertTrue(abs(width - V7FocusRingWidth.value * scale) <= 1.5f, "$mode $label $n ring $width px (missing or clipped)")
            assertTrue(ring >= 3f, "$mode $label $n ring ${hex(shot[x, y])} vs ${hex(before[x, y])}")
            // 1dp past the ring is the untouched surface, or (复制, whose click area pads 6dp above the label) the click
            // area's own focus tint that already lies inside the ring: either way nothing new is painted past the ring.
            val inGap = at(inner - 1)
            val pastOk = k(past) < 1.08f || contrast(shot[past.first, past.second], shot[inGap.first, inGap.second]) < 1.08f
            assertTrue(pastOk, "$mode $label $n 1dp past the ring must be unfocused pixels or the focus tint, got ${hex(shot[past.first, past.second])}")
            "$n=$gap/${width}px ${hex(shot[x, y])}:${hex(before[x, y])}=${"%.2f".format(ring)}"
        }
        println("TON-1603 week $mode $label hug ${got.joinToString(" ")}")
    }

    /**
     * On each centre line, gap+width/2 outside the pill edge must be ring (≥3:1 to the surface, same ink as the left side),
     * and one ring width further out must be the surface again: a ring cut by the dialog edge would fail on that side.
     */
    private fun ringOnFourSides(mode: String, label: String, b: Rect, shot: PixelMap, before: PixelMap) {
        val mid = (V7FocusRingGap + V7FocusRingWidth / 2).value * scale
        val past = (V7FocusRingGap + V7FocusRingWidth * 2).value * scale
        val cx = b.center.x; val cy = b.center.y
        val sides = listOf("left" to Pair(Offset(b.left - mid, cy), Offset(b.left - past, cy)), "right" to Pair(Offset(b.right - 1 + mid, cy), Offset(b.right - 1 + past, cy)),
            "top" to Pair(Offset(cx, b.top - mid), Offset(cx, b.top - past)), "bottom" to Pair(Offset(cx, b.bottom - 1 + mid), Offset(cx, b.bottom - 1 + past)))
        val got = sides.map { (n, p) ->
            val (r, o) = p
            val ring = shot[r.x.roundToInt(), r.y.roundToInt()]; val surface = before[r.x.roundToInt(), r.y.roundToInt()]; val out = shot[o.x.roundToInt(), o.y.roundToInt()]
            assertTrue(contrast(ring, surface) >= 3f, "$mode $label $n ring ${hex(ring)} vs surface ${hex(surface)}")
            assertTrue(contrast(out, surface) < 1.08f, "$mode $label $n beyond the ring must be surface, got ${hex(out)}")
            "$n=${hex(ring)}:${"%.2f".format(contrast(ring, surface))}"
        }
        println("TON-1603 week $mode $label four-sides ${got.joinToString(" ")}")
    }

    /**
     * Ring drawn inside a text-button pill that sits on a clipping edge: from each pill edge walk inward on the centre lines.
     * The ring must start at the edge (no clipped part) and be V7FocusRingWidth wide on all four sides, ≥3:1 against the
     * surface the pill sits on and against the pill's own focused fill.
     */
    private fun ComposeUiTest.measurePillInside(mode: String, label: String, m: SemanticsMatcher, before: PixelMap): PixelMap {
        val shot = shot("$mode-$label-focused")
        val b = first(m).boundsInRoot
        val cx = b.center.x.roundToInt(); val cy = b.center.y.roundToInt()
        val l = b.left.roundToInt(); val t = b.top.roundToInt(); val r = b.right.roundToInt() - 1; val bo = b.bottom.roundToInt() - 1
        val ringPx = (V7FocusRingWidth.value * scale).roundToInt()
        val fill = shot[l + ringPx + 2, cy]
        fun isFill(c: Color) = contrast(c, fill) < 1.08f
        fun walk(step: (Int) -> Color): Pair<Int, Int> {
            var i = 0
            while (isFill(step(i))) i++
            val margin = i
            while (!isFill(step(i))) i++
            return margin to i - margin
        }
        val sides = listOf("left" to walk { shot[l + it, cy] }, "right" to walk { shot[r - it, cy] },
            "top" to walk { shot[cx, t + it] }, "bottom" to walk { shot[cx, bo - it] })
        val ring = shot[l + 1, cy]
        val surface = before[l + 1, cy]
        println("TON-1603 week $mode $label ring=${hex(ring)} surface=${hex(surface)} fill=${hex(fill)} ring:surface=${"%.2f".format(contrast(ring, surface))} ring:fill=${"%.2f".format(contrast(ring, fill))} " +
            "pill=${l / scale},${t / scale} ${b.width / scale}x${b.height / scale}dp " + sides.joinToString(" ") { (n, v) -> "$n=${v.first}/${v.second}px" })
        assertTrue(contrast(ring, surface) >= 3f, "$mode $label ring vs the surface under the pill")
        assertTrue(contrast(ring, fill) >= 3f, "$mode $label ring vs the pill's focused fill")
        for ((n, v) in sides) {
            assertTrue(v.first <= 1, "$mode $label $n ring starts ${v.first}px inside the edge, want 0–1 (complete, not clipped)")
            assertTrue(abs(v.second - V7FocusRingWidth.value * scale) <= 1.5f, "$mode $label $n ring ${v.second}px")
        }
        return shot
    }

    private data class Edge(val week: Int, val dead: SemanticsMatcher, val live: SemanticsMatcher, val lands: Int)

    /** One week (W2) with three sections, so the reading footer has a first, middle and last section. */
    private fun oneWeekThreeSections(): CourseView {
        val now = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()
        val events = listOf(Event(1, "outline", "SYN", "outline_revised", recordedAt = now,
            occurredAt = now, deviceId = "synthetic", actor = "SYSTEM", payload = json(
                "knowledgePoints" to JsonArray(listOf(json("id" to "s1", "title" to "【W2】首节要点"), json("id" to "s2", "title" to "【W2】中节要点"),
                    json("id" to "s3", "title" to "【W2】末节要点"))))),
            Event(2, "exam", "SYN", "exam_set", recordedAt = now, occurredAt = now,
                deviceId = "synthetic", actor = "USER", payload = json("examDay" to 20802)))
        return CourseView(events, project(events))
    }

    private fun threeWeeks(scope: Int? = null): CourseView {
        val now = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()
        val events = listOf(Event(1, "outline", "SYN", "outline_revised", recordedAt = now,
            occurredAt = now, deviceId = "synthetic", actor = "SYSTEM", payload = json(
                "knowledgePoints" to JsonArray(listOf(json("id" to "a", "title" to "【W1】合成要点"), json("id" to "c", "title" to "【W2】中间要点"),
                    json("id" to "d", "title" to "【W3】末周要点"))))),
            Event(2, "exam", "SYN", "exam_set", recordedAt = now, occurredAt = now,
                deviceId = "synthetic", actor = "USER", payload = json("examDay" to 20802))) +
            listOfNotNull(scope?.let { Event(3, "scope", "SYN", "study_scope_set", recordedAt = now, occurredAt = now,
                deviceId = "synthetic", actor = "USER", payload = json("week" to it, "kpIds" to JsonArray(listOf(JsonPrimitive("c"))))) })
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

    /** The Desk variants: custom contentPadding and the ring drawn inside the pill keep Material's size, slot and unfocused pixels. */
    @Test fun v7TextButtonWithPaddingOrInsideRingLooksExactlyLikeMaterialWhenUnfocused() {
        val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
        for (dark in listOf(false, true)) for (custom in listOf(true, false)) runSkikoComposeUiTest(size = Size(300f * scale, 200f * scale), density = Density(scale)) {
            val mode = if (dark) "dark" else "light"
            var clicks = 0
            setContent { CoachTheme(dark) { Surface { Column {
                if (custom) TextButton({}, Modifier.testTag("m3"), contentPadding = padding) { Text("目录") }
                else TextButton({}, Modifier.testTag("m3")) { Text("收起目录") }
                if (custom) V7TextButton({ clicks++ }, Modifier.testTag("v7"), contentPadding = padding, ringInside = true) { Text("目录") }
                else V7TextButton({ clicks++ }, Modifier.testTag("v7"), ringInside = true) { Text("收起目录") }
            } } } }
            waitForIdle()
            val m3 = onNodeWithTag("m3").fetchSemanticsNode().boundsInRoot
            val v7 = onNodeWithTag("v7").fetchSemanticsNode().boundsInRoot
            println("TON-1603 week $mode padding=${if (custom) "8dp" else "default"} inside material=${m3.width / scale}x${m3.height / scale}dp v7=${v7.width / scale}x${v7.height / scale}dp slot=${(v7.top - m3.top) / scale}dp")
            assertEquals(m3.size, v7.size, "$mode same visible size")
            assertEquals(48f * scale, v7.top - m3.top, 0.5f, "$mode same 48dp touch slot")
            assertEquals(m3.left, v7.left)
            val px = shot("$mode-material-vs-v7-inside-${if (custom) "padding" else "default"}")
            val dy = (v7.top - m3.top).roundToInt()
            var diff = 0
            for (x in m3.left.toInt() until m3.right.toInt()) for (y in m3.top.toInt() - 4 until m3.bottom.toInt() + 4)
                if (px[x, y] != px[x, y + dy]) diff++
            assertEquals(0, diff, "$mode unfocused inside-ring V7TextButton pixels differ from Material TextButton")
            onNodeWithTag("v7").performMouseInput { click(Offset(2 * scale, v7.height / 2)) }; waitForIdle()
            assertEquals(1, clicks, "$mode a click 2dp inside the pill's edge still reaches the button")
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
     * [walkDown] walks down from the bottom edge instead of up, for a pill with something else just above its ring.
     */
    private fun ComposeUiTest.measure(mode: String, label: String, m: SemanticsMatcher, before: PixelMap, pageDx: Float = 20f, dialog: Boolean = false, walkDown: Boolean = false) {
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
        val bottom = b.bottom.roundToInt() - 1
        val (innerT, outerT) = if (walkDown) walk { shot[cx, bottom + it] } else walk { shot[cx, top - it] }
        val gapL = innerL - 1; val gapT = innerT - 1
        val widthL = outerL - innerL + 1; val widthT = outerT - innerT + 1
        val ringY = if (walkDown) bottom + (innerT + outerT) / 2 else top - (innerT + outerT) / 2
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

    /**
     * Ring drawn inside a full-width menu row: walk inward from each edge through the row's focused fill to the ring.
     * edgeRows also checks the outer side (top of the first row, bottom of the last) so a clipped ring would fail.
     */
    private fun ComposeUiTest.measureInside(mode: String, label: String, m: SemanticsMatcher, before: PixelMap, edgeRows: Boolean): PixelMap {
        val shot = shot("$mode-menu-$label-focused", dialog = true)
        val b = first(m).boundsInRoot
        val cx = b.center.x.roundToInt(); val cy = b.center.y.roundToInt()
        val l = b.left.roundToInt(); val t = b.top.roundToInt(); val r = b.right.roundToInt() - 1; val bo = b.bottom.roundToInt() - 1
        val fill = shot[l + 2, cy]
        fun isFill(c: Color) = contrast(c, fill) < 1.08f
        fun walk(step: (Int) -> Color): Pair<Int, Int> {
            var i = 1
            while (isFill(step(i))) i++
            val margin = i
            while (!isFill(step(i))) i++
            return margin to i - margin
        }
        val sides = listOf("left" to walk { shot[l + it, cy] }, "right" to walk { shot[r - it, cy] },
            "top" to walk { shot[cx, t + it] }, "bottom" to walk { shot[cx, bo - it] })
        val (marginL, _) = sides[0].second
        val ring = shot[l + marginL + 1, cy]
        val surface = before[l + marginL + 1, cy]
        val wantMargin = (MenuInsetForTest - V7FocusRingGap - V7FocusRingWidth).value * scale
        println("TON-1603 week $mode 菜单-$label ring=${hex(ring)} menu=${hex(surface)} fill=${hex(fill)} ring:menu=${"%.2f".format(contrast(ring, surface))} ring:fill=${"%.2f".format(contrast(ring, fill))} " +
            sides.joinToString(" ") { (n, v) -> "$n=${v.first}/${v.second}px" })
        assertTrue(contrast(ring, surface) >= 3f, "$mode $label ring vs the menu surface")
        assertTrue(contrast(ring, fill) >= 3f, "$mode $label ring vs the row's focused fill")
        for ((n, v) in sides) {
            assertTrue(abs(v.first - wantMargin) <= 1.5f, "$mode $label $n margin ${v.first}px, want $wantMargin (ring inside the row, not clipped)")
            assertTrue(abs(v.second - V7FocusRingWidth.value * scale) <= 1.5f, "$mode $label $n ring ${v.second}px")
        }
        assertTrue(contrast(surface, before[l + 2, cy]) < 1.05f, "$mode $label ring pixel is plain menu surface when unfocused")
        return shot
    }

    /** Mirrors the private MenuRingInset in WeekStudyControls.kt. */
    private val MenuInsetForTest = 8.dp

    private fun ComposeUiTest.shot(name: String, dialog: Boolean = false): PixelMap {
        val image = (if (dialog) onAllNodes(isRoot()).onLast() else onAllNodes(isRoot()).onFirst()).captureToImage()
        ImageIO.write(image.toAwtImage(), "png", File(out, "$name.png"))
        return image.toPixelMap()
    }

    private fun assertRegionEqual(a: PixelMap, b: PixelMap, r: Rect, what: String) =
        assertEquals(0, countDiff(a, b, r), "$what: pixels differ from the never-focused frame")

    private fun countDiff(a: PixelMap, b: PixelMap, r: Rect): Int {
        var diff = 0
        for (x in r.left.toInt().coerceAtLeast(0) until r.right.toInt().coerceAtMost(a.width))
            for (y in r.top.toInt().coerceAtLeast(0) until r.bottom.toInt().coerceAtMost(a.height))
                if (a[x, y] != b[x, y]) diff++
        return diff
    }

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + .05f) / (lo + .05f)
    }

    private fun hex(c: Color) = "#%02X%02X%02X".format((c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt())
}
