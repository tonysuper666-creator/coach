package com.coach.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.client.CourseView
import com.coach.client.MathHttp
import com.coach.client.RenderedMath
import com.coach.desktop.books.BookNotesScope
import com.coach.desktop.books.NotesExportDraft
import com.coach.desktop.books.Textbook
import com.coach.desktop.books.TextbookLibrary
import com.coach.desktop.books.buildBookNotesOutline
import com.coach.desktop.books.buildCourseNotesOutline
import com.coach.ui.CoachController
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDate
import java.util.UUID

internal fun saveAndroidNotesToDirectory(directory: Path, markdown: String, pdf: ByteArray?): Path {
    val requested = directory.toAbsolutePath().normalize()
    require(generateSequence(requested) { it.parent }.none { Files.exists(it.resolve(".git")) }) {
        "请选择 Git 仓库之外的私人目录"
    }
    Files.createDirectories(requested)
    val chosen = requested.toRealPath()
    require(Files.isDirectory(chosen)) { "请选择已有文件夹" }
    require(generateSequence(chosen) { it.parent }.none { Files.exists(it.resolve(".git")) }) {
        "请选择 Git 仓库之外的私人目录"
    }
    val posixSupported = Files.getFileStore(chosen).supportsFileAttributeView(PosixFileAttributeView::class.java)
    val staging = if (posixSupported) {
        Files.createTempDirectory(chosen, ".coach-notes-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    } else {
        Files.createTempDirectory(chosen, ".coach-notes-")
    }
    var failure: Throwable? = null
    try {
        writePrivateFile(staging.resolve("复习提纲.md"), markdown.toByteArray(Charsets.UTF_8), posixSupported)
        if (pdf != null) writePrivateFile(staging.resolve("复习提纲.pdf"), pdf, posixSupported)
        val target = chosen.resolve("LearningCoach-复习-${LocalDate.now()}-${UUID.randomUUID().toString().take(8)}")
        if (Files.exists(target, NOFOLLOW_LINKS)) throw FileAlreadyExistsException(target.toString())
        return Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            if (Files.exists(staging, NOFOLLOW_LINKS)) {
                Files.list(staging).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
                Files.deleteIfExists(staging)
            }
        } catch (cleanup: Exception) {
            if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
        }
    }
}

private fun writePrivateFile(path: Path, bytes: ByteArray, posixSupported: Boolean) {
    if (posixSupported) {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } else {
        Files.createFile(path)
    }
    Files.write(path, bytes, WRITE, NOFOLLOW_LINKS)
}

internal fun saveAndroidNotesToTree(context: Context, treeUri: Uri, markdown: String, pdf: ByteArray?): String {
    val resolver = context.contentResolver
    val parentDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    val folderName = "LearningCoach-复习-${LocalDate.now()}-${UUID.randomUUID().toString().take(8)}"
    val folderUri = requireNotNull(
        DocumentsContract.createDocument(resolver, parentDoc, DocumentsContract.Document.MIME_TYPE_DIR, folderName)
    ) { "无法在所选目录中创建导出文件夹" }
    try {
        val mdUri = requireNotNull(
            DocumentsContract.createDocument(resolver, folderUri, "text/markdown", "复习提纲.md")
        ) { "无法创建 Markdown 文件" }
        resolver.openOutputStream(mdUri)?.use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
            ?: error("无法写入 Markdown 文件")
        if (pdf != null) {
            val pdfUri = requireNotNull(
                DocumentsContract.createDocument(resolver, folderUri, "application/pdf", "复习提纲.pdf")
            ) { "无法创建 PDF 文件" }
            resolver.openOutputStream(pdfUri)?.use { it.write(pdf) }
                ?: error("无法写入 PDF 文件")
        }
        return folderName
    } catch (error: Throwable) {
        runCatching { DocumentsContract.deleteDocument(resolver, folderUri) }
        throw error
    }
}

@Composable
internal fun AndroidBookNotesExportDialog(
    book: Textbook,
    library: TextbookLibrary,
    currentPage: Int,
    defaultDirectory: Path? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val appExportsDir = remember(defaultDirectory, context) {
        defaultDirectory ?: context.filesDir.toPath().resolve("exports")
    }
    var scopeMode by remember(book.id) { mutableStateOf(BookNotesScope.FULL_BOOK) }
    var includePdf by remember(book.id) { mutableStateOf(true) }
    var pickedTree by remember(book.id) { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) pickedTree = uri
    }

    NotesExportShellDialog(
        title = "导出笔记与复习提纲",
        subtitle = "包含划线、错题复习记录与章节提纲。之后继续学习不会改动这份导出。",
        locationLabel = pickedTree?.lastPathSegment?.let { "已选文件夹：$it" } ?: "应用私有目录（exports/）",
        includePdf = includePdf,
        busy = busy,
        status = status,
        failed = failed,
        canExport = !busy,
        onTogglePdf = { includePdf = !includePdf },
        onPickDirectory = { treePicker.launch(null) },
        onClose = onDismiss,
        scopeRow = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = scopeMode == BookNotesScope.FULL_BOOK,
                    onClick = { scopeMode = BookNotesScope.FULL_BOOK },
                    enabled = !busy,
                    label = { Text("整本教材") },
                    modifier = Modifier.testTag("notes-scope-book"),
                )
                FilterChip(
                    selected = scopeMode == BookNotesScope.CURRENT_PAGE,
                    onClick = { scopeMode = BookNotesScope.CURRENT_PAGE },
                    enabled = !busy,
                    label = { Text("当前页附近") },
                    modifier = Modifier.testTag("notes-scope-page"),
                )
            }
        },
        onExport = {
            busy = true
            failed = false
            status = "正在整理笔记与提纲…"
            coroutineScope.launch {
                try {
                    val resultSummary = exportNotesPayload(
                        context = context,
                        pickedTree = pickedTree,
                        fallbackDir = appExportsDir,
                        includePdf = includePdf,
                        onProgress = { status = it },
                        buildDraft = {
                            buildBookNotesOutline(
                                book = book,
                                highlights = library.allHighlights(book.id),
                                reviews = library.allReviews(book.id),
                                answers = library.allAnswers(book.id),
                                scope = scopeMode,
                                currentPage = currentPage,
                            )
                        },
                    )
                    status = resultSummary
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    failed = true
                    status = e.message ?: "导出失败，请重试"
                } finally {
                    busy = false
                }
            }
        },
    )
}

@Composable
internal fun AndroidCourseNotesExportAction(
    view: CourseView,
    controller: CoachController,
    mathHttp: MathHttp,
    defaultDirectory: Path? = null,
) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = view.events.isNotEmpty(), modifier = Modifier.testTag("course-export-notes")) {
        Text("导出复习提纲")
    }
    if (!open) return

    val context = LocalContext.current.applicationContext
    val appExportsDir = remember(defaultDirectory, context) {
        defaultDirectory ?: context.filesDir.toPath().resolve("exports")
    }
    val weeks = remember(view) { view.weeks.filter { it in 1..52 }.distinct().sorted() }
    val points = remember(view) { view.weekPoints }
    var byWeek by remember(view) { mutableStateOf(weeks.isNotEmpty()) }
    var selectedWeek by remember(weeks) { mutableStateOf(weeks.firstOrNull()) }
    var selectedPoint by remember(points) { mutableStateOf(points.firstOrNull()?.id) }
    var includePdf by remember { mutableStateOf(true) }
    var pickedTree by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) pickedTree = uri
    }

    NotesExportShellDialog(
        title = "导出复习提纲",
        subtitle = "包含要点掌握度、公式与待练错题。之后再做题不会改动这份导出。",
        locationLabel = pickedTree?.lastPathSegment?.let { "已选文件夹：$it" } ?: "应用私有目录（exports/）",
        includePdf = includePdf,
        busy = busy,
        status = status,
        failed = failed,
        canExport = !busy && (if (byWeek) selectedWeek != null else selectedPoint != null),
        onTogglePdf = { includePdf = !includePdf },
        onPickDirectory = { treePicker.launch(null) },
        onClose = { open = false },
        scopeRow = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = byWeek,
                        onClick = { byWeek = true },
                        enabled = !busy && weeks.isNotEmpty(),
                        label = { Text("按周次") },
                        modifier = Modifier.testTag("notes-scope-week"),
                    )
                    FilterChip(
                        selected = !byWeek,
                        onClick = { byWeek = false },
                        enabled = !busy && points.isNotEmpty(),
                        label = { Text("按知识点") },
                        modifier = Modifier.testTag("notes-scope-point"),
                    )
                }
                if (byWeek && weeks.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        weeks.forEach { w ->
                            FilterChip(
                                selected = selectedWeek == w,
                                onClick = { selectedWeek = w },
                                enabled = !busy,
                                label = { Text("第 $w 周") },
                            )
                        }
                    }
                } else if (points.isNotEmpty()) {
                    Text(
                        points.firstOrNull { it.id == selectedPoint }?.title ?: "选择知识点",
                        style = MaterialTheme.typography.bodySmall,
                        color = CoachTheme.colors.fgPrimary,
                    )
                }
            }
        },
        onExport = {
            val cachedMath = controller.math.assets.toMap()
            busy = true
            failed = false
            status = "正在整理复习资料…"
            coroutineScope.launch {
                try {
                    val resultSummary = exportNotesPayload(
                        context = context,
                        pickedTree = pickedTree,
                        fallbackDir = appExportsDir,
                        includePdf = includePdf,
                        onProgress = { status = it },
                        buildDraft = {
                            buildCourseNotesOutline(
                                view = view,
                                courseId = controller.courseId,
                                week = if (byWeek) selectedWeek else null,
                                pointId = if (!byWeek) selectedPoint else null,
                            )
                        },
                        fetchMath = { tex, display ->
                            cachedMath["$display:$tex"]?.let { RenderedMath(it.sha, it.svg) }
                                ?: mathHttp.render(tex, display)
                        },
                    )
                    status = resultSummary
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    failed = true
                    status = e.message ?: "导出失败，请重试"
                } finally {
                    busy = false
                }
            }
        },
    )
}

private suspend fun exportNotesPayload(
    context: Context,
    pickedTree: Uri?,
    fallbackDir: Path,
    includePdf: Boolean,
    onProgress: (String) -> Unit,
    buildDraft: suspend () -> NotesExportDraft,
    fetchMath: suspend (String, Boolean) -> RenderedMath? = { _, _ -> null },
): String {
    val draft = withContext(Dispatchers.IO) { buildDraft() }
    val pdfResult = if (includePdf) {
        onProgress("正在生成公式与 PDF 分页…")
        withContext(Dispatchers.Default) { renderAndroidNotesPdf(draft.markdown, fetchMath) }
    } else null
    onProgress("正在保存到本机…")
    val folderName = withContext(Dispatchers.IO) {
        if (pickedTree != null) {
            saveAndroidNotesToTree(context, pickedTree, draft.markdown, pdfResult?.bytes)
        } else {
            saveAndroidNotesToDirectory(fallbackDir, draft.markdown, pdfResult?.bytes).fileName.toString()
        }
    }
    return buildString {
        append("已导出到 $folderName（共 ${draft.itemCount} 条")
        if (pdfResult != null) append("，PDF ${pdfResult.pageCount} 页")
        append("）。")
        if (pdfResult != null && pdfResult.unresolvedFormulas > 0) {
            append(" 有 ${pdfResult.unresolvedFormulas} 处公式未能绘制，已保留原始 TeX。")
        }
        if (pdfResult != null && pdfResult.unresolvedImages > 0) {
            append(" 有 ${pdfResult.unresolvedImages} 张外链图片未嵌入。")
        }
    }
}

@Composable
private fun NotesExportShellDialog(
    title: String,
    subtitle: String,
    locationLabel: String,
    includePdf: Boolean,
    busy: Boolean,
    status: String?,
    failed: Boolean,
    canExport: Boolean,
    onTogglePdf: () -> Unit,
    onPickDirectory: () -> Unit,
    onClose: () -> Unit,
    scopeRow: @Composable () -> Unit,
    onExport: () -> Unit,
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.btn)
    val dark = colors.dark
    val pine = if (dark) CoachV7.Dark.pine else CoachV7.Light.pine
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("notes-export-panel"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                Text("导出范围", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                scopeRow()
                Text("文件格式", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                Row(
                    Modifier.clip(shape)
                        .toggleable(value = includePdf, enabled = !busy, role = Role.Checkbox) { onTogglePdf() }
                        .padding(vertical = 4.dp, horizontal = 4.dp)
                        .testTag("notes-pdf"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = includePdf, onCheckedChange = null, enabled = !busy)
                    Text("Markdown + 可打印 PDF", color = colors.fgPrimary, style = MaterialTheme.typography.bodyMedium)
                }
                Text("保存位置", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        locationLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.fgPrimary,
                        modifier = Modifier.weight(1f).clip(shape).background(colors.surface1)
                            .border(1.dp, colors.borderSubtle, shape).padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("notes-directory"),
                    )
                    PaperButton(onPickDirectory, enabled = !busy, modifier = Modifier.testTag("notes-pick-directory")) {
                        Text("选择文件夹…")
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = pine, trackColor = colors.borderSubtle)
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            failed -> colors.dangerFg
                            else -> colors.successFg
                        },
                        modifier = Modifier.testTag("notes-status"),
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(onExport, enabled = canExport, modifier = Modifier.testTag("notes-export")) {
                Text(if (busy) "正在导出…" else "导出")
            }
        },
        dismissButton = {
            V7TextButton(onClose, enabled = !busy, modifier = Modifier.testTag("notes-close")) {
                Text("关闭")
            }
        },
    )
}
