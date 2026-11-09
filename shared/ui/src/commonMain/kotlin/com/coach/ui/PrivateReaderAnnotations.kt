package com.coach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.coach.client.ReaderHttp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
private fun JsonObject.flag(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.validAnchor(doc: PrivateDocument, page: Int, text: String, hash: String): Boolean {
    val start = l("start"); val end = l("end")
    return !flag("stale") && s("version") == doc.version && l("pdfPage") == page.toLong() && s("textSha256") == hash &&
        start >= 0 && end > start && end <= text.length && text.substring(start.toInt(), end.toInt()) == s("quote")
}

/** Select against the server's exact text; PDF image coordinates are deliberately not inferred. */
@OptIn(ExperimentalUuidApi::class)
@Composable
internal fun PrivateReaderAnnotations(reader: ReaderHttp, doc: PrivateDocument, page: Int, onClose: () -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var selection by remember { mutableStateOf(TextRange.Zero) }
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(reader, doc, page, reload) {
        loaded = false; failed = false
        val p = reader.page(doc.id, page)
        val state = reader.state(doc.id)
        val body = p.body
        val document = state.body?.get("document") as? JsonObject
        if (!p.ok || !state.ok || body == null || body.s("version") != doc.version || document?.s("version") != doc.version || body.s("textSha256").isBlank()) {
            failed = true
        } else {
            text = body.s("text"); hash = body.s("textSha256")
            notes = (state.body?.get("notes") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            loaded = true
        }
    }
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("本页原文与批注", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose, enabled = editing == null, modifier = Modifier.testTag("private-notes-close")) { Text("返回阅读") }
                }
                if (failed) {
                    Text("无法读取页面原文，或资料版本已变化；返回目录重新打开。", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { reload++ }) { Text("重试") }
                } else if (!loaded) Text("正在读取原文和批注…")
                else {
                    Text("选择下方提取文字后添加批注。此处标记在文字中，PDF 页图尚不显示高亮。", style = MaterialTheme.typography.labelMedium)
                    val annotated = AnnotatedString.Builder(text).apply {
                        notes.filter { it.validAnchor(doc, page, text, hash) }.forEach {
                            addStyle(SpanStyle(background = Color(0xffffec99)), it.l("start").toInt(), it.l("end").toInt())
                        }
                    }.toAnnotatedString()
                    BasicTextField(value = TextFieldValue(annotated, selection), onValueChange = {
                        if (!it.selection.collapsed) selection = it.selection
                    }, readOnly = true, modifier = Modifier.fillMaxWidth().testTag("private-note-text"),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface))
                    val start = minOf(selection.start, selection.end); val end = maxOf(selection.start, selection.end)
                    if (start >= 0 && end > start && end <= text.length && end - start <= 4000) {
                        TextButton(onClick = {
                            editing = buildJsonObject {
                                put("id", Uuid.random().toString()); put("version", doc.version); put("pdfPage", page)
                                put("textSha256", hash); put("start", start); put("end", end); put("quote", text.substring(start, end))
                                put("note", ""); put("revision", 0)
                            }
                            selection = TextRange.Zero
                        }, modifier = Modifier.testTag("private-note-add")) { Text("为选中文字添加批注") }
                    }
                    val visible = notes.filter { it.l("pdfPage") == page.toLong() || it.flag("stale") || it.s("version") != doc.version }
                    if (visible.isEmpty()) Text("本页还没有批注。")
                    for (note in visible) {
                        val valid = note.validAnchor(doc, page, text, hash)
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!valid) Text("原文或资料版本已变化，位置待重新确认（原 PDF 第 ${note.l("pdfPage")} 页）。", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("private-note-stale"))
                            Text("我的原句：${note.s("quote")}")
                            if (note.s("note").isNotEmpty()) Text("我的批注：${note.s("note")}")
                            if (valid) TextButton(onClick = { editing = note }, modifier = Modifier.testTag("private-note-edit-${note.s("id")}")) { Text("编辑批注") }
                        }
                    }
                }
            }
            editing?.let { note ->
                PrivateNoteEditor(reader, doc, note, onSaved = { saved ->
                    notes = notes.filterNot { it.s("id") == saved.s("id") } + saved
                }, onClose = { editing = null })
            }
        }
    }
}

/** Serialize edits and preserve the exact pending mutation across retries. */
@OptIn(ExperimentalUuidApi::class)
@Composable
private fun PrivateNoteEditor(reader: ReaderHttp, doc: PrivateDocument, initial: JsonObject, onSaved: (JsonObject) -> Unit, onClose: () -> Unit) {
    var text by remember(initial) { mutableStateOf(initial.s("note")) }
    var savedText by remember(initial) { mutableStateOf(if (initial.l("revision") == 0L) null else initial.s("note")) }
    var revision by remember(initial) { mutableLongStateOf(initial.l("revision")) }
    var pending by remember(initial) { mutableStateOf<JsonObject?>(null) }
    var busy by remember(initial) { mutableStateOf(false) }
    var error by remember(initial) { mutableStateOf("") }
    var conflict by remember(initial) { mutableStateOf<JsonObject?>(null) }
    val scope = rememberCoroutineScope()
    fun body(note: String, base: Long) = buildJsonObject {
        for (key in listOf("id", "version", "pdfPage", "textSha256", "start", "end", "quote")) put(key, initial.getValue(key))
        put("note", note); put("deleted", false); put("baseRevision", base); put("mutationId", Uuid.random().toString())
    }
    fun send(write: JsonObject) {
        pending = write; busy = true; error = ""
        scope.launch {
            val result = reader.saveNote(doc.id, write)
            val next = (result.body?.get("revision") as? JsonPrimitive)?.longOrNull
            if (result.ok && next != null) {
                revision = next; savedText = write.s("note"); pending = null; conflict = null
                onSaved(JsonObject(initial + mapOf("note" to JsonPrimitive(write.s("note")), "revision" to JsonPrimitive(next))))
            } else {
                error = if (result.status == 409) "批注或资料已变化；本地内容尚未保存。" else "批注尚未保存，可重试。"
            }
            busy = false
        }
    }
    LaunchedEffect(text, savedText, busy, pending) {
        if (!busy && pending == null && text != savedText) { delay(500); send(body(text, revision)) }
    }
    AlertDialog(onDismissRequest = {}, title = { Text("批注（自动保存）") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("我的原句：${initial.s("quote")}")
            OutlinedTextField(value = text, onValueChange = { text = it.take(8000) }, label = { Text("我的批注") }, modifier = Modifier.testTag("private-note-editor"))
            Text(if (busy) "正在保存…" else if (pending != null) error else if (text != savedText) "等待保存…" else "已保存", modifier = Modifier.testTag("private-note-status"))
            if (pending != null && !busy) {
                TextButton(onClick = { send(pending!!) }, modifier = Modifier.testTag("private-note-retry")) { Text("重试原保存") }
                TextButton(onClick = {
                    busy = true
                    scope.launch {
                        val state = reader.state(doc.id)
                        val currentVersion = (state.body?.get("document") as? JsonObject)?.s("version")
                        val current = (state.body?.get("notes") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.singleOrNull { it.s("id") == initial.s("id") }
                        if (state.ok && currentVersion == doc.version && current != null && !current.flag("stale") &&
                            listOf("version", "pdfPage", "textSha256", "start", "end", "quote").all { current[it] == initial[it] }) {
                            conflict = current
                        } else error = "无法载入同一版本的批注；请保留本地内容并检查资料。"
                        busy = false
                    }
                }, modifier = Modifier.testTag("private-note-conflict-load")) { Text("查看服务端批注") }
                conflict?.let { current ->
                    Text("服务端：${current.s("note")}")
                    TextButton(onClick = { text = current.s("note"); savedText = text; revision = current.l("revision"); pending = null; conflict = null; onSaved(current) }, modifier = Modifier.testTag("private-note-use-remote")) { Text("使用服务端内容") }
                    TextButton(onClick = { conflict = null; send(body(text, current.l("revision"))) }, modifier = Modifier.testTag("private-note-keep-local")) { Text("用我的内容覆盖") }
                }
                TextButton(onClick = onClose, modifier = Modifier.testTag("private-note-discard")) { Text("放弃未保存内容") }
            }
        }
    }, confirmButton = {
        TextButton(onClick = onClose, enabled = !busy && pending == null && text == savedText, modifier = Modifier.testTag("private-note-editor-close")) { Text("完成") }
    })
}
