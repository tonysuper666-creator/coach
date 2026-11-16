package com.coach.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.coach.client.CourseView
import com.coach.client.MathHttp
import com.coach.client.RenderedMath
import com.coach.domain.WeekPoint
import com.coach.domain.weekPoints
import com.coach.ui.CoachController
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
        busy = true; saved = null; failed = false; status = "正在整理账本与复习资料…"
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
                status = "已导出：${draft.attemptIds.size} 条待练错题，账本序号 ${draft.lastSeq}。"
                if (unresolved > 0) status += " PDF 有 $unresolved 处公式未能渲染，已标注并保留原始 TeX；请检查公式服务和源资料。"
                if (unresolvedImages > 0) status += " PDF 有 $unresolvedImages 张图片未嵌入，已标注；原引用保留在 Markdown。仅嵌入账本自带的 PNG/JPEG，不读取外部文件或网址。"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) { failed = true; status = e.message ?: "导出失败，请重试" }
            finally {
                busy = false
                System.getenv("COACH_NOTES_VERIFY_CAPTURE")?.let {
                    delay(300)
                    java.awt.Window.getWindows().firstOrNull { w -> w.isVisible && w is java.awt.Dialog && w.title == "导出复习提纲" }
                        ?.let { window -> VerifyHooks.capture(window, File(it)) }
                }
            }
        }
    } }
    DialogWindow(onCloseRequest = { if (!state.busy) close() }, title = "导出复习提纲", state = rememberDialogState(width = 600.dp, height = 610.dp)) {
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

/** 导出窗口里的全部内容；窗口外壳和文件选择框留在 [NotesExportDialog]。 */
@Composable
internal fun NotesExportPanel(state: NotesExportState, onPickDirectory: () -> Unit, onExport: () -> Unit, onClose: () -> Unit,
    onOpen: (Path) -> Unit) { with(state) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("导出复习提纲", style = MaterialTheme.typography.headlineSmall)
                    Text("包含要点、掌握度、公式和当前待练选择题。后续同步不会改变这次导出的账本快照。", style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = { byWeek = true }, enabled = !busy && weeks.isNotEmpty()) { Text(if (byWeek) "● 按周次" else "○ 按周次") }
                        TextButton(onClick = { byWeek = false }, enabled = !busy && points.isNotEmpty()) { Text(if (!byWeek) "● 按知识点" else "○ 按知识点") }
                    }
                    Box {
                        OutlinedButton(onClick = { expanded = true }, enabled = !busy) { Text(if (byWeek) "第 ${week ?: "—"} 周 ▾" else points.firstOrNull { it.id == point }?.title ?: "选择知识点") }
                        DropdownMenu(expanded, { expanded = false }) {
                            if (byWeek) weeks.forEach { w -> DropdownMenuItem(text = { Text("第 $w 周") }, onClick = { week = w; expanded = false }) }
                            else points.forEach { p -> DropdownMenuItem(text = { Text(p.title) }, onClick = { point = p.id; expanded = false }) }
                        }
                    }
                    Row { Checkbox(checked = includePdf, onCheckedChange = { includePdf = it }, enabled = !busy); Text("Markdown + 可打印 PDF", Modifier.padding(top = 12.dp)) }
                    OutlinedButton(onClick = onPickDirectory, enabled = !busy) { Text("选择本机文件夹…") }
                    Text(directory?.toString() ?: "尚未选择保存位置", style = MaterialTheme.typography.bodySmall)
                    Text("文件只保存在所选目录的新文件夹内。PDF 公式使用与应用相同的 Mac 公式服务；无法渲染的公式会标注并保留源码。", style = MaterialTheme.typography.bodySmall)
                    status?.let { Text(it, color = if (saved != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onExport, enabled = canExport) { Text(if (busy) "正在导出…" else "导出") }
                        TextButton(onClick = onClose, enabled = !busy) { Text("关闭") }
                        saved?.let { path -> TextButton(onClick = { onOpen(path) }) { Text("打开文件夹") } }
                    }
                }
            }
        }
} }
