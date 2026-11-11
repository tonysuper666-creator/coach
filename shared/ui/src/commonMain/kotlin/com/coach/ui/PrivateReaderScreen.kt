package com.coach.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.coach.client.ReaderHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal expect fun decodeReaderImage(bytes: ByteArray): ImageBitmap?

internal data class PrivateDocument(val id: String, val title: String, val version: String, val pages: Int)
private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull

internal fun privateDocuments(body: JsonObject?): List<PrivateDocument>? =
    (body?.get("documents") as? JsonArray)?.mapNotNull { entry ->
        val obj = entry as? JsonObject ?: return@mapNotNull null
        val id = obj.string("id"); val title = obj.string("title"); val version = obj.string("version")
        val pages = obj.int("pages") ?: return@mapNotNull null
        if (id.isBlank() || title.isBlank() || version.isBlank() || pages < 1) null else PrivateDocument(id, title, version, pages)
    }

internal sealed interface ReaderLoad<out T> {
    data object Loading : ReaderLoad<Nothing>
    data object Failed : ReaderLoad<Nothing>
    data class Ready<T>(val value: T) : ReaderLoad<T>
}

/** Only registered private PDFs, through the existing paired client; no content enters tutor requests. */
@Composable
internal fun PrivateReaderScreen(reader: ReaderHttp, practice: (@Composable (week: Int, ids: Set<String>, close: () -> Unit) -> Unit)? = null,
                                 onBack: () -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    val catalog by produceState<ReaderLoad<List<PrivateDocument>>>(ReaderLoad.Loading, reader, retry) {
        value = ReaderLoad.Loading
        val response = reader.catalog()
        value = if (response.ok) privateDocuments(response.body)?.let { ReaderLoad.Ready(it) } ?: ReaderLoad.Failed else ReaderLoad.Failed
    }
    var document by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<PrivateDocument?, List<Any>>(
        save = { doc -> if (doc == null) emptyList() else listOf(doc.id, doc.title, doc.version, doc.pages) },
        restore = { saved -> if (saved.isEmpty()) null else PrivateDocument(saved[0] as String, saved[1] as String, saved[2] as String, saved[3] as Int) }
    )) { mutableStateOf<PrivateDocument?>(null) }
    AppBackHandler(document != null, priority = 10) { document = null }
    Column(Modifier.fillMaxSize()) {
        if (document == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 返回") }
                Text("教材阅读", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            HorizontalDivider()
        }
        val selected = document
        when {
            selected != null -> PrivatePdfDocument(reader, selected, practice = practice) { document = null }
            catalog is ReaderLoad.Loading -> CenterMessage("正在读取教材目录…")
            catalog is ReaderLoad.Failed -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("教材目录暂时不可用，请检查 Mac 连接。")
                OutlinedButton(onClick = { retry++ }) { Text("重试") }
            }
            else -> {
                val docs = (catalog as ReaderLoad.Ready).value
                if (docs.isEmpty()) CenterMessage("这门课还没有加入教材。")
                else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(docs, key = { it.id }) { doc ->
                        Surface(onClick = { document = doc }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("private-book-${doc.id}"), tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.padding(14.dp)) { Text(doc.title); Text("${doc.pages} 页", style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
@Composable
internal fun PrivatePdfDocument(reader: ReaderHttp, doc: PrivateDocument, initialPage: Int? = null, initialAnchor: String = "",
                               practice: (@Composable (week: Int, ids: Set<String>, close: () -> Unit) -> Unit)? = null, onBack: () -> Unit) {
    var retry by remember(doc) { mutableIntStateOf(0) }
    val state by produceState<ReaderLoad<JsonObject>>(ReaderLoad.Loading, reader, doc, retry) {
        value = ReaderLoad.Loading
        val response = reader.state(doc.id)
        val body = response.body
        val version = (body?.get("document") as? JsonObject)?.string("version")
        value = if (response.ok && body != null && version == doc.version) ReaderLoad.Ready(body) else ReaderLoad.Failed
    }
    when (val loaded = state) {
        ReaderLoad.Loading -> CenterMessage("正在读取上次位置…")
        ReaderLoad.Failed -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("无法读取教材位置，或教材版本已变化。请返回目录重试。")
            OutlinedButton(onClick = { retry++ }) { Text("重试") }
        }
        is ReaderLoad.Ready -> {
            val progress = loaded.value["progress"] as? JsonObject
            val stale = (progress?.get("stale") as? JsonPrimitive)?.booleanOrNull == true ||
                (progress != null && progress.string("version") != doc.version)
            val restored = if (stale) null else progress?.int("pdfPage")?.takeIf { it in 1..doc.pages }
            var page by remember(doc, loaded.value) { mutableIntStateOf(initialPage ?: restored ?: 1) }
            var revision by remember(doc, loaded.value) { mutableLongStateOf((progress?.get("revision") as? JsonPrimitive)?.longOrNull ?: 0L) }
            var pending by remember(doc, loaded.value) { mutableStateOf<ProgressWrite?>(null) }
            var status by remember(doc, loaded.value) { mutableStateOf("") }
            var busy by remember(doc, loaded.value) { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            var showNotes by remember(doc) { mutableStateOf(false) }
            var focus by remember(doc, loaded.value) { mutableStateOf(initialPage?.takeIf { initialAnchor.isNotBlank() }?.let { HighlightFocus(it, initialAnchor) }) }
            var focusRetry by remember(doc) { mutableIntStateOf(0) }
            val highlight by rememberHighlight(reader, doc, focus, focusRetry)
            if (showNotes) PrivateReaderAnnotations(reader, doc, page, onShow = { quote -> focus = HighlightFocus(page, quote); showNotes = false }) { showNotes = false }
            var showMap by remember(doc) { mutableStateOf(false) }
            var readingFocus by remember(doc) { mutableStateOf(false) }
            AppBackHandler(priority = 20) { if (!busy && pending == null) onBack() }
            AppBackHandler(readingFocus, priority = 100) { readingFocus = false }
            TaskChrome(readingFocus)
            fun send(write: ProgressWrite) {
                pending = write
                busy = true
                status = "正在保存…"
                scope.launch {
                    val result = reader.saveProgress(doc.id, doc.version, write.page, write.baseRevision, write.id)
                    val nextRevision = (result.body?.get("revision") as? JsonPrimitive)?.longOrNull
                    if (result.ok && nextRevision != null) {
                        revision = nextRevision
                        pending = null
                        status = "已保存"
                    } else status = if (result.status == 409) "阅读位置或资料版本已变化，请核对后选择。" else "位置尚未保存，请重试。"
                    busy = false
                }
            }
            fun move(target: Int) {
                focus = null
                page = target
                send(ProgressWrite(target, revision, Uuid.random().toString()))
            }
            if (showMap) ChapterMapDialog(reader, doc.id, doc.pages, onJump = { target -> showMap = false; if (target != page) move(target) }, onClose = { showMap = false }, practice = practice)
            LaunchedEffect(doc, loaded.value, initialPage) {
                if (initialPage != null && initialPage != restored) move(initialPage)
                // move() clears any mark from the previous page; a citation's own anchor belongs to the page it opened on.
                if (initialPage != null && initialAnchor.isNotBlank()) focus = HighlightFocus(initialPage, initialAnchor)
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 840.dp
                var spread by remember { mutableStateOf(false) }
                val two = wide && spread && page < doc.pages
                Column(Modifier.fillMaxSize().background(com.coach.ui.theme.CoachTheme.colors.surface1)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { if (readingFocus) readingFocus = false else onBack() }, enabled = !busy && pending == null, modifier = Modifier.heightIn(min = 48.dp).testTag("private-document-back")) { Text(if (readingFocus) "退出专心读" else "← 教材阅读") }
                        Text(doc.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { readingFocus = !readingFocus }, modifier = Modifier.testTag("private-focus")) { Text(if (readingFocus) "退出专心读" else "专心读") }
                    }
                    HorizontalDivider()
                    if (stale) Text("旧版本的阅读位置未自动套用，请重新定位。", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                    if (initialPage != null) Text("已按出处定位到页；页面内容仍需核对。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
                    if (initialPage != null && initialAnchor.isBlank() && page == initialPage) Text("这条出处只登记到页，没有登记原文位置。", Modifier.padding(horizontal = 16.dp).testTag("private-citation-noanchor"), style = MaterialTheme.typography.labelMedium)
                    Text("PDF 第 $page${if (two) "–${page + 1}" else ""} 页 / ${doc.pages}", Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("private-page-label"), style = MaterialTheme.typography.labelMedium)
                    if (status.isNotEmpty()) Text(status, Modifier.padding(horizontal = 16.dp).testTag("private-save-status"))
                    // Keep the panel's expanded state while focus mode gives its space to the page image.
                    Box(if (readingFocus) Modifier.height(0.dp).clearAndSetSemantics { } else Modifier) {
                        PageStudyPanel(reader, doc.id, page, practice, onShowAnchor = { quote -> focus = HighlightFocus(page, quote) })
                    }
                    HighlightStatus(highlight, onRetry = { focusRetry++ }, onClear = { focus = null })
                    val draft = pending
                    if (draft != null && !busy) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { send(draft) }, modifier = Modifier.testTag("private-save-retry")) { Text("重试保存") }
                            TextButton(onClick = {
                                busy = true
                                scope.launch {
                                    val fresh = reader.state(doc.id)
                                    val body = fresh.body
                                    val currentVersion = (body?.get("document") as? JsonObject)?.string("version")
                                    val remote = body?.get("progress") as? JsonObject
                                    val freshRevision = (remote?.get("revision") as? JsonPrimitive)?.longOrNull ?: 0L
                                    if (fresh.ok && currentVersion == doc.version) {
                                        revision = freshRevision
                                        val remotePage = remote?.int("pdfPage")?.takeIf { remote.string("version") == doc.version && it in 1..doc.pages }
                                        page = remotePage ?: 1
                                        pending = null
                                        status = "已载入保存的位置"
                                    } else status = "无法载入，或资料版本已变化；请返回目录重新打开。"
                                    busy = false
                                }
                            }, modifier = Modifier.testTag("private-load-remote")) { Text("载入已保存的位置") }
                        }
                        TextButton(onClick = {
                            busy = true
                            scope.launch {
                                val fresh = reader.state(doc.id)
                                val body = fresh.body
                                val currentVersion = (body?.get("document") as? JsonObject)?.string("version")
                                val remote = body?.get("progress") as? JsonObject
                                val freshRevision = (remote?.get("revision") as? JsonPrimitive)?.longOrNull ?: 0L
                                if (fresh.ok && currentVersion == doc.version) {
                                    send(ProgressWrite(draft.page, freshRevision, Uuid.random().toString()))
                                } else {
                                    status = "无法载入，或资料版本已变化；请返回目录重新打开。"
                                    busy = false
                                }
                            }
                        }, modifier = Modifier.testTag("private-keep-page")) { Text("保留本页并覆盖已保存的位置") }
                        TextButton(onClick = onBack, modifier = Modifier.testTag("private-discard")) { Text("放弃未保存位置并返回") }
                    }
                    if (!readingFocus) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { showMap = true }, enabled = !busy && pending == null, modifier = Modifier.testTag("private-open-map")) { Text("章节脉络") }
                        TextButton(onClick = { showNotes = true }, enabled = !busy && pending == null, modifier = Modifier.testTag("private-open-notes")) { Text("本页原文与批注") }
                        if (wide) TextButton(onClick = { spread = !spread }, modifier = Modifier.testTag("private-spread")) { Text(if (spread) "单页" else "双页") }
                    }
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PdfPageImage(reader, doc, page, Modifier.weight(1f).fillMaxHeight(), (highlight as? HighlightState.Shown)?.takeIf { focus?.page == page })
                        if (two) PdfPageImage(reader, doc, page + 1, Modifier.weight(1f).fillMaxHeight(), (highlight as? HighlightState.Shown)?.takeIf { focus?.page == page + 1 })
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { move((page - if (wide && spread) 2 else 1).coerceAtLeast(1)) }, enabled = !busy && pending == null && page > 1, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("private-prev")) { Text("上一页") }
                        OutlinedButton(onClick = { move((page + if (two) 2 else 1).coerceAtMost(doc.pages)) }, enabled = !busy && pending == null && page + (if (two) 1 else 0) < doc.pages, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("private-next")) { Text("下一页") }
                    }
                }
            }
        }
    }
}

/**
 * A source field may list several citations ("W1 课件 p.26；教材 p.8。"), split on the same separators the server uses for page study.
 * Sentence-final punctuation is not part of a citation; anything else is sent to resolve exactly as written.
 */
internal fun citationParts(source: String): List<String> =
    source.split(Regex("[;；\n]")).map { it.trim().trimEnd('。', '．', '.', '，', ',', '、').trimEnd() }.filter { it.isNotEmpty() }

/** Each citation of a composite source is offered on its own; none is picked for the reader, and each resolves exactly or says it cannot. */
@Composable
internal fun PrivateSourceReferences(reader: ReaderHttp, source: String, anchor: String = "") {
    val parts = remember(source) { citationParts(source) }
    if (parts.size <= 1) { parts.singleOrNull()?.let { PrivateSourceReference(reader, it, anchor) }; return }
    Column(Modifier.testTag("private-citations")) {
        Text("出处（${parts.size} 条，分别打开）", style = MaterialTheme.typography.labelMedium)
        parts.forEachIndexed { i, part -> PrivateSourceReference(reader, part, anchor, tag = "private-citation-$i", label = part) }
    }
}

/** A source click resolves through the course catalog; an unregistered or ambiguous source never guesses. */
@Composable
internal fun PrivateSourceReference(reader: ReaderHttp, source: String, anchor: String = "", tag: String = "private-citation", label: String = "出处：$source") {
    var opened by androidx.compose.runtime.saveable.rememberSaveable(source) { mutableStateOf(false) }
    val back = LocalAppBack.current
    AppBackHandler(opened, priority = 15) { opened = false }
    TextButton(onClick = { opened = true }, modifier = Modifier.testTag(tag)) { Text(label) }
    if (opened) Dialog(onDismissRequest = { if (back == null) opened = false else back.back() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            var retry by remember { mutableIntStateOf(0) }
            val target by produceState<ReaderLoad<Pair<PrivateDocument, Int>>>(ReaderLoad.Loading, reader, source, retry) {
                value = ReaderLoad.Loading
                val resolved = reader.resolve(source)
                val body = resolved.body
                val id = body?.string("documentId").orEmpty()
                val version = body?.string("version").orEmpty()
                val page = body?.int("pdfPage")
                val catalog = if (resolved.ok) reader.catalog() else null
                val document = if (catalog?.ok == true) privateDocuments(catalog.body)?.singleOrNull { it.id == id && it.version == version } else null
                value = if (document != null && page != null && page in 1..document.pages) ReaderLoad.Ready(document to page) else ReaderLoad.Failed
            }
            when (val result = target) {
                ReaderLoad.Loading -> CenterMessage("正在定位出处…")
                ReaderLoad.Failed -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("无法定位出处：资料未登记、出处不唯一或 Mac 暂不可用。", Modifier.testTag("private-citation-failed"))
                    TextButton(onClick = { retry++ }) { Text("重试") }
                    TextButton(onClick = { opened = false }) { Text("返回题目") }
                }
                is ReaderLoad.Ready -> PrivatePdfDocument(reader, result.value.first, result.value.second, anchor) { opened = false }
            }
        }
    }
}

private data class ProgressWrite(val page: Int, val baseRevision: Long, val id: String)

@Composable
private fun PdfPageImage(reader: ReaderHttp, doc: PrivateDocument, page: Int, modifier: Modifier, highlight: HighlightState.Shown? = null) {
    var retry by remember(doc, page) { mutableIntStateOf(0) }
    val image by produceState<ReaderLoad<ImageBitmap>>(ReaderLoad.Loading, reader, doc, page, retry) {
        value = ReaderLoad.Loading
        val bytes = reader.image(doc.id, page, doc.version)
        val bitmap = bytes?.let { withContext(Dispatchers.Default) { runCatching { decodeReaderImage(it) }.getOrNull() } }
        value = bitmap?.let { ReaderLoad.Ready(it) } ?: ReaderLoad.Failed
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when (val result = image) {
            ReaderLoad.Loading -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            ReaderLoad.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("第 $page 页暂时无法读取")
                TextButton(onClick = { retry++ }) { Text("重试") }
            }
            is ReaderLoad.Ready -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth()) {
                    Image(result.value, contentDescription = "PDF 第 $page 页", modifier = Modifier.fillMaxWidth().testTag("private-page-$page"), contentScale = ContentScale.FillWidth)
                    if (highlight != null) HighlightOverlay(highlight, result.value.width, result.value.height, Modifier.matchParentSize())
                }
            }
        }
    }
}
