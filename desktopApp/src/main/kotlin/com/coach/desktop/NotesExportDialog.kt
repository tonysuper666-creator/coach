package com.coach.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as TickPath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.coach.client.CourseView
import com.coach.client.MathHttp
import com.coach.client.RenderedMath
import com.coach.domain.WeekPoint
import com.coach.domain.weekPoints
import com.coach.desktop.books.v7Alert
import com.coach.ui.CoachController
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7Checkbox
import com.coach.ui.v7.V7TextButton
import com.coach.ui.v7.v7FocusRing
import coach.design.tokens.CoachV7
import java.awt.Desktop
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NotesExportAction(view: CourseView, controller: CoachController, mathHttp: MathHttp) {
    var frozen by remember { mutableStateOf<CourseView?>(null) }
    var verified by remember { mutableStateOf(false) }
    TextButton(onClick = { frozen = view }, enabled = view.events.isNotEmpty()) { Text("导出复习提纲") }
    LaunchedEffect(view.events.size) {
        if (!verified && System.getenv("COACH_NOTES_VERIFY_DIR") != null && view.kps.isNotEmpty()) {
            verified = true; frozen = view
        }
    }
    frozen?.let { snapshot -> NotesExportDialog(snapshot, controller, mathHttp) { frozen = null } }
}

/** 导出弹窗里会变的东西：弹窗和截图测试共用一份，面板只读它、按回调改它。 */
internal class NotesExportState(val weeks: List<Int>, val points: List<WeekPoint>, week: Int?, directory: Path?) {
    var byWeek by mutableStateOf(weeks.isNotEmpty())
    var week by mutableStateOf(week)
    var point by mutableStateOf(points.firstOrNull()?.id)
    var expanded by mutableStateOf(false)
    var includePdf by mutableStateOf(true)
    var directory by mutableStateOf(directory)
    var busy by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var saved by mutableStateOf<Path?>(null)
    var failed by mutableStateOf(false)
    val canExport: Boolean get() = !busy && directory != null && (if (byWeek) week != null else point != null)
}

@Composable
private fun NotesExportDialog(view: CourseView, controller: CoachController, mathHttp: MathHttp, close: () -> Unit) {
    val state = remember(view) {
        val points = weekPoints(view.events)
        val weeks = points.flatMap { it.weeks }.filter { it in 1..52 }.distinct().sorted()
        NotesExportState(weeks, points,
            System.getenv("COACH_NOTES_VERIFY_WEEK")?.toIntOrNull()?.takeIf { it in weeks } ?: weeks.firstOrNull(),
            System.getenv("COACH_NOTES_VERIFY_DIR")?.let { File(it).toPath() })
    }
    val scope = rememberCoroutineScope()
    fun export() { with(state) {
        val destination = directory ?: return
        val selection = if (byWeek) NotesSelection(week = week) else NotesSelection(pointId = point)
        val cached = controller.math.assets.toMap()
        busy = true; saved = null; failed = false; status = "正在整理复习资料…"
        scope.launch {
            try {
                val draft = NotesCore.build(notesRequest(view, controller.courseId, selection))
                status = if (includePdf) "正在绘制公式与分页…" else "正在保存 Markdown…"
                var unresolved = 0
                var unresolvedImages = 0
                val result = withContext(Dispatchers.Default) {
                    val pdf = if (includePdf) notesPdf(draft.markdown) { tex, display ->
                        cached["$display:$tex"]?.let { RenderedMath(it.sha, it.svg) } ?: mathHttp.render(tex, display)
                    } else null
                    unresolved = pdf?.unresolved ?: 0
                    unresolvedImages = pdf?.unresolvedImages ?: 0
                    withContext(Dispatchers.IO) { saveNotes(destination, draft, pdf?.bytes) }
                }
                saved = result
                status = "已导出：${draft.attemptIds.size} 条待练错题。"
                if (unresolved > 0) status += " PDF 里有 $unresolved 处公式没画出来，已照原样写出并标明。"
                if (unresolvedImages > 0) status += " PDF 里有 $unresolvedImages 张图片没能放进去，已标明；引用仍留在 Markdown 里，可回原书查看。只嵌入教材自带的 PNG/JPEG，不读电脑上的其他文件或网址。"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) { failed = true; status = e.message ?: "导出失败，请重试" }
            finally {
                busy = false
                System.getenv("COACH_NOTES_VERIFY_CAPTURE")?.let {
                    delay(300)
                    java.awt.Window.getWindows().firstOrNull { w -> w.isVisible && w is java.awt.Dialog && w.title == "导出复习提纲 · LearningCoach" }
                        ?.let { window -> VerifyHooks.capture(window, File(it)) }
                }
            }
        }
    } }
    DialogWindow(onCloseRequest = { if (!state.busy) close() }, title = "导出复习提纲 · LearningCoach", icon = AppIcon.window(), state = rememberDialogState(width = 600.dp, height = 610.dp)) {
        NotesExportPanel(state, onPickDirectory = {
            val picker = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "选择保存位置" }
            if (picker.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) state.directory = picker.selectedFile.toPath()
        }, onExport = ::export, onClose = close, onOpen = { path -> Desktop.getDesktop().open(path.toFile()) })
        LaunchedEffect(Unit) {
            if (System.getenv("COACH_NOTES_VERIFY_DIR") != null) {
                delay(1800)
                System.getenv("COACH_NOTES_VERIFY_CAPTURE")?.let { VerifyHooks.capture(window, File(it)) }
                export()
            }
        }
    }
}

/**
 * 导出窗口里的全部内容；窗口外壳和文件选择框留在 [NotesExportDialog]。
 * V7：窗口是一张 surface2 的纸，字段名是 fgSecondary 的小字；范围是两格纸面选项（选中为松绿），
 * 下拉和选文件夹走纸面按钮，展开的菜单也是一张弱线纸，保存位置是叠在上面的一层 surface1 纸、路径用数字字体；导出是墨色主按钮，
 * 成功写 successFg，失败写朱色，进行中是松绿进度条。颜色、字号、圆角都走 token。
 */
@Composable
internal fun NotesExportPanel(state: NotesExportState, onPickDirectory: () -> Unit, onExport: () -> Unit, onClose: () -> Unit,
    onOpen: (Path) -> Unit) { with(state) {
    val colors = CoachTheme.colors
    val paper = RoundedCornerShape(CoachV7.Radius.btn)
    Column(Modifier.fillMaxSize().background(colors.surface2).verticalScroll(rememberScrollState()).padding(28.dp)
        .testTag("notes-export-panel"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("导出复习提纲", style = MaterialTheme.typography.headlineSmall, color = colors.fgPrimary)
            Text("包含要点、掌握度、公式和现在还要练的选择题。之后再做题，不会改动这份导出。",
                style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
        }
        NotesField("范围") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotesChoice("按周次", byWeek, !busy && weeks.isNotEmpty(), "notes-scope-week") { byWeek = true }
                NotesChoice("按知识点", !byWeek, !busy && points.isNotEmpty(), "notes-scope-point") { byWeek = false }
            }
        }
        NotesField(if (byWeek) "周次" else "知识点") {
            Box {
                PaperButton({ expanded = true }, enabled = !busy, modifier = Modifier.testTag("notes-picker")) {
                    Text(if (byWeek) "第 ${week ?: "—"} 周" else points.firstOrNull { it.id == point }?.title ?: "选择知识点",
                        style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
                    Spacer(Modifier.width(10.dp))
                    DropdownChevron(colors.fgSecondary)
                }
                // V7 纸面菜单：一张 surface1 的纸加一道弱线，没有 Material 的灰底和色调抬升；
                // 回车、Esc 和点外面关闭仍由 DropdownMenu 负责。
                DropdownMenu(expanded, { expanded = false }, shape = paper, containerColor = colors.surface1,
                    tonalElevation = 0.dp, shadowElevation = 6.dp, border = BorderStroke(1.dp, colors.borderSubtle),
                    modifier = Modifier.testTag("notes-picker-menu")) {
                    Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (byWeek) weeks.forEach { w -> NotesMenuItem("第 $w 周", w == week, "notes-week-$w") { week = w; expanded = false } }
                        else points.forEach { p -> NotesMenuItem(p.title, p.id == point, "notes-point-${p.id}") { point = p.id; expanded = false } }
                    }
                }
            }
        }
        NotesField("格式") {
            // 整行仍是同一个开关：框、字一起点，空格也能切，语义是 Checkbox；框本身按 V7 的小方框画。
            Row(Modifier.v7FocusRing(paper).clip(paper).toggleable(includePdf, remember { MutableInteractionSource() }, null,
                enabled = !busy, role = Role.Checkbox) { includePdf = it }
                .heightIn(min = 32.dp).padding(end = 12.dp).testTag("notes-pdf"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                V7Checkbox(includePdf, enabled = !busy)
                Text("Markdown + 可打印 PDF", style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
            }
        }
        NotesField("保存位置") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(directory?.toString() ?: "尚未选择保存位置", style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (directory != null) LocalV7Fonts.current.numbers else null,
                    color = if (directory != null) colors.fgPrimary else colors.fgSecondary,
                    modifier = Modifier.weight(1f).clip(paper).background(colors.surface1).border(1.dp, colors.borderSubtle, paper)
                        .padding(horizontal = 12.dp, vertical = 10.dp).testTag("notes-directory"))
                PaperButton(onPickDirectory, enabled = !busy, modifier = Modifier.testTag("notes-pick-directory")) { Text("选择本机文件夹…") }
            }
            Text("文件只保存在所选目录的新文件夹内。PDF 里的公式由你的 Mac 画出；画不出来的会照原样写出来并标明。",
                style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("notes-status"),
                color = when { failed -> v7Alert(); saved != null -> colors.successFg; else -> colors.fgSecondary })
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = v7Pine(), trackColor = colors.borderSubtle)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically) {
            saved?.let { path -> PaperButton({ onOpen(path) }, modifier = Modifier.testTag("notes-open")) { Text("打开文件夹") } }
            V7TextButton(onClose, enabled = !busy, modifier = Modifier.testTag("notes-close")) { Text("关闭") }
            PrimaryButton(onExport, enabled = canExport, modifier = Modifier.testTag("notes-export")) { Text(if (busy) "正在导出…" else "导出") }
        }
    }
} }

/**
 * 下拉指示：一道画的细线箭头，和 V7 的图标一样只有线条，不再用字体里的「▾」——不同字体会落成不同的三角，
 * 还会跟着字号一起变胖。按钮的悬停底由 P0 的 `PaperButton` 给，这里只管画箭头。
 */
@Composable
private fun DropdownChevron(color: Color) {
    Canvas(Modifier.size(10.dp, 6.dp)) {
        val w = size.width; val h = size.height
        drawPath(TickPath().apply { moveTo(w * 0.08f, h * 0.20f); lineTo(w * 0.50f, h * 0.80f); lineTo(w * 0.92f, h * 0.20f) },
            color, style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun NotesField(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = CoachTheme.colors.fgSecondary)
        content()
    }
}

/**
 * 下拉菜单里的一行：没选是透明的纸，鼠标停留时浅一层 surface2，选中是松绿淡底配松绿字和对勾，
 * 和上面「范围」两格的选中色一致；键盘焦点是 V7 焦点环（行两侧留了 6dp，环不会被菜单裁掉）。
 */
@Composable
private fun NotesMenuItem(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.btn)
    val wash = if (colors.dark) CoachV7.Dark.pineWash else CoachV7.Light.pineWash
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(Modifier.widthIn(min = 180.dp).heightIn(min = 40.dp).v7FocusRing(shape).clip(shape)
        .background(when { selected -> wash; hovered -> colors.surface2; else -> Color.Transparent })
        .hoverable(interaction)
        .selectable(selected, interaction, null, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 12.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) v7Pine() else colors.fgPrimary,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        // 对勾的位置每行都留着，选中与否文字不跳；勾是画的两笔，不靠字体里的 ✓（有的字体会落成 √）。
        val tick = v7Pine()
        Canvas(Modifier.size(14.dp)) {
            if (!selected) return@Canvas
            val w = size.width
            drawPath(TickPath().apply { moveTo(w * 0.12f, w * 0.52f); lineTo(w * 0.40f, w * 0.78f); lineTo(w * 0.88f, w * 0.24f) },
                tick, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** 两格单选：选中是松绿描边加松绿淡底，没选是 surface1 的纸；不用 Material 的灰色按压层。 */
@Composable
private fun NotesChoice(label: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.btn)
    val wash = if (colors.dark) CoachV7.Dark.pineWash else CoachV7.Light.pineWash
    Box(Modifier.v7FocusRing(shape).clip(shape).background(if (selected) wash else colors.surface1)
        .border(1.dp, if (selected) v7Pine() else colors.borderSubtle, shape)
        .selectable(selected, remember { MutableInteractionSource() }, null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 40.dp).padding(horizontal = 18.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = when { selected -> v7Pine(); enabled -> colors.fgPrimary; else -> colors.fgTertiary })
    }
}
