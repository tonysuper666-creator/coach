package com.coach.ui

import androidx.compose.foundation.Image
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
import androidx.compose.ui.unit.dp
import com.coach.client.ReaderHttp
import kotlinx.coroutines.Dispatchers
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

private sealed interface ReaderLoad<out T> {
    data object Loading : ReaderLoad<Nothing>
    data object Failed : ReaderLoad<Nothing>
    data class Ready<T>(val value: T) : ReaderLoad<T>
}

/** Only registered private PDFs, through the existing paired client; no content enters tutor requests. */
@Composable
internal fun PrivateReaderScreen(reader: ReaderHttp, onBack: () -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    val catalog by produceState<ReaderLoad<List<PrivateDocument>>>(ReaderLoad.Loading, reader, retry) {
        value = ReaderLoad.Loading
        val response = reader.catalog()
        value = if (response.ok) privateDocuments(response.body)?.let { ReaderLoad.Ready(it) } ?: ReaderLoad.Failed else ReaderLoad.Failed
    }
    var document by remember { mutableStateOf<PrivateDocument?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (document != null) document = null else onBack() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 返回") }
            Text(document?.title ?: "教材阅读", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        val selected = document
        when {
            selected != null -> PrivatePdfDocument(reader, selected)
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

@Composable
private fun PrivatePdfDocument(reader: ReaderHttp, doc: PrivateDocument) {
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
            var page by remember(doc, loaded.value) { mutableIntStateOf(restored ?: 1) }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 840.dp
                var spread by remember { mutableStateOf(false) }
                val two = wide && spread && page < doc.pages
                Column(Modifier.fillMaxSize()) {
                    if (stale) Text("旧版本的阅读位置未自动套用，请重新定位。", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                    Text("PDF 第 $page${if (two) "–${page + 1}" else ""} 页 / ${doc.pages} · 当前翻页暂不保存", Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("private-page-label"), style = MaterialTheme.typography.labelMedium)
                    if (wide) TextButton(onClick = { spread = !spread }, modifier = Modifier.testTag("private-spread")) { Text(if (spread) "单页" else "双页") }
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PdfPageImage(reader, doc, page, Modifier.weight(1f).fillMaxHeight())
                        if (two) PdfPageImage(reader, doc, page + 1, Modifier.weight(1f).fillMaxHeight())
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { page = (page - if (wide && spread) 2 else 1).coerceAtLeast(1) }, enabled = page > 1, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("private-prev")) { Text("上一页") }
                        OutlinedButton(onClick = { page = (page + if (two) 2 else 1).coerceAtMost(doc.pages) }, enabled = page + (if (two) 1 else 0) < doc.pages, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("private-next")) { Text("下一页") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageImage(reader: ReaderHttp, doc: PrivateDocument, page: Int, modifier: Modifier) {
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
                Image(result.value, contentDescription = "PDF 第 $page 页", modifier = Modifier.fillMaxWidth().testTag("private-page-$page"), contentScale = ContentScale.FillWidth)
            }
        }
    }
}
