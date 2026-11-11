package com.coach.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coach.domain.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.random.Random

/** Exact offsets plus original body/version: updated drafts cannot silently reattach a personal mark. */
internal fun markMatches(mark: JsonObject, section: JsonObject): Boolean {
    val text = section.str("body")
    val start = mark.num("start").toInt(); val end = mark.num("end").toInt()
    return mark.str("section") == section.str("section_id") &&
        mark.str("version") == section.str("content_version") && mark.str("original") == text &&
        start >= 0 && end > start && end <= text.length && text.substring(start, end) == mark.str("quote")
}

internal fun relatedMarks(marks: List<JsonObject>, sections: List<JsonObject>, facts: List<String>): List<JsonObject> =
    marks.filter { mark -> sections.any { it.str("section_id") == mark.str("section") && it.strings("fact_ids").any { f -> f in facts } } }

internal fun factMastered(fact: String, items: List<JsonObject>): Boolean =
    items.filter { fact in it.strings("factIds") }.let { qs -> qs.isNotEmpty() && qs.all { it.str("status") == "mastered" } }

@Composable
internal fun PersonalMarks(marks: List<JsonObject>, sections: List<JsonObject>) {
    for (mark in marks) {
        val section = sections.firstOrNull { it.str("section_id") == mark.str("section") }
        Text("我的划线（个人记录）：" + mark.str("quote"), style = MaterialTheme.typography.bodyMedium)
        if (mark.str("note").isNotBlank()) Text("我的一句话：" + mark.str("note"), style = MaterialTheme.typography.bodyMedium)
        if (section == null || !markMatches(mark, section))
            Text("原文已更新，划线位置待重新确认。保留的是当时的原句和批注。", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun WeeklyReader(
    c: CoachController, number: Int, sections: List<JsonObject>, section: JsonObject?, items: List<JsonObject>,
    marks: List<JsonObject>, state: JsonObject, scroll: ScrollState, error: String,
    updateState: ((JsonObject) -> JsonObject) -> Unit, onSection: (String) -> Unit, onQuiz: (String, String?) -> Unit,
    onRefresh: () -> Unit, onFocus: (Boolean) -> Unit, emphasis: @Composable (List<String>) -> Unit,
) {
    val http = c.course!!
    val dark = state.flag("dark"); val focus = state.flag("focus")
    var selected by remember(section?.str("section_id"), section?.str("body")) { mutableStateOf(TextRange.Zero) }
    // The reader is only selectable once the passage itself holds the caret: a programmatic range that never reaches
    // the field would show no handles and leave the mark buttons hidden, so the passage asks for focus as it selects.
    val passageFocus = remember { FocusRequester() }
    LaunchedEffect(selected) { println("TON1394 reader selection state=$selected") }
    LaunchedEffect(focus) { println("TON1394 reader focus=$focus") }
    var editMark by remember { mutableStateOf<String?>(null) }
    var action by remember { mutableStateOf<JsonObject?>(null) }
    var answer by remember { mutableStateOf<JsonObject?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val formatted = section?.str("body")?.let { it.contains('\n') || it.any { ch -> ch in "*$`#[]_\\" } } == true
    var selectOriginal by remember(section?.str("section_id"), section?.str("body")) { mutableStateOf(false) }
    fun set(key: String, value: JsonElement) = updateState { JsonObject(it + (key to value)) }
    fun addMark(edit: Boolean) {
        val s = section ?: return
        val start = selected.min; val end = selected.max
        if (start < 0 || end > s.str("body").length || start == end) return
        val old = marks.firstOrNull { markMatches(it, s) && it.num("start") == start.toLong() && it.num("end") == end.toLong() }
        val mark = old ?: json("id" to Random.nextLong().toULong().toString(16), "section" to s.str("section_id"),
            "version" to s.str("content_version"), "original" to s.str("body"), "start" to start, "end" to end,
            "quote" to s.str("body").substring(start, end), "note" to "")
        set("marks", JsonArray(marks.filterNot { it.str("id") == mark.str("id") } + mark))
        if (edit) editMark = mark.str("id")
        selected = TextRange.Zero
    }
    fun ask(kind: String) {
        val s = section ?: return
        if (selected.collapsed) return
        answer = null; failure = null
        action = json("requestId" to Random.nextLong().toULong().toString(16), "kind" to kind,
            "sectionId" to s.str("section_id"), "version" to s.str("content_version"),
            "start" to selected.min, "end" to selected.max, "quote" to s.str("body").substring(selected.min, selected.max))
        selected = TextRange.Zero
    }
    LaunchedEffect(action) {
        val request = action ?: return@LaunchedEffect
        try {
            val reply = http.explainSelection(number, request)
            answer = reply.body; failure = reply.error
        } finally {
            // Explicit cancellation reaches the subscription CLI even when HTTP disconnects aren't observable.
            withContext(NonCancellable) { http.cancelSelection(number, request.str("requestId")) }
        }
    }
    val colors = if (dark) darkColorScheme() else lightColorScheme()
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 32.sp)
    MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography.copy(bodyLarge = bodyStyle)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { set("dark", JsonPrimitive(!dark)) }) { Text(if (dark) "浅色阅读" else "深色阅读") }
                    TextButton(onClick = { onFocus(!focus) }) { Text(if (focus) "退出专心读" else "专心读") }
                    TextButton(onClick = { onQuiz("", null) }, modifier = Modifier.testTag("mainline-skip")) { Text("跳过要点，直接做题") }
                    if (section != null) TextButton(onClick = { onQuiz(section.str("section_id"), null) }, modifier = Modifier.testTag("mainline-section-quiz")) { Text("做这一节的题") }
                }
                Column(Modifier.fillMaxSize().testTag("mainline-reading-scroll").verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (!focus) {
                            Text("本周要点", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("mainline-notes"))
                            TextButton(onClick = onRefresh) { Text("重新读取本周内容") }
                            for (s in sections) TextButton(onClick = { onSection(s.str("section_id")) }, modifier = Modifier.fillMaxWidth()) {
                                Text((if (s.flag("mastered")) "✓ " else "○ ") + com.coach.client.displayTitle(s.str("title")), Modifier.fillMaxWidth())
                            }
                        }
                        if (section == null) Text(error.ifBlank { "本周要点尚未提供，可以直接做题。" })
                        else key(section.str("section_id")) {
                            Text(com.coach.client.displayTitle(section.str("title")), style = MaterialTheme.typography.titleLarge)
                            emphasis(section.strings("fact_ids"))
                            val text = section.str("body")
                            if (formatted) TextButton(onClick = { selectOriginal = !selectOriginal; selected = TextRange.Zero }) {
                                Text(if (selectOriginal) "排版阅读" else "选择原文、划线或追问")
                            }
                            val annotated = AnnotatedString.Builder(text).apply {
                                marks.filter { markMatches(it, section) }.forEach {
                                    addStyle(SpanStyle(background = if (dark) Color(0xff66521c) else Color(0xffffec99),
                                        textDecoration = TextDecoration.Underline), it.num("start").toInt(), it.num("end").toInt())
                                }
                            }.toAnnotatedString()
                            if (formatted && !selectOriginal) {
                                // Keep existing Markdown/math rendering; selections use the exact original offsets.
                                Markdown(text, true, "reader:${section.str("section_id")}:${section.str("content_version")}", c.math)
                            } else {
                                // Android resolves ACTION_SET_SELECTION against the node it reaches by
                                // contentDescription and reads that node's own (unmerged) semantics. The field's
                                // SetSelection action sits on the inner text node, so the node a screen reader lands
                                // on reported the selection as changed while nothing got selected. Carry the action
                                // on that outer node and map the requested range onto this reader's own selection.
                                val bodyLength = text.length
                                Box(Modifier.fillMaxWidth().testTag("reader-text").semantics {
                                    contentDescription = "本节正文，可选择文字"
                                    setSelection { start, end, _ ->
                                        val ok = !(start < 0 || end < 0 || start > bodyLength || end > bodyLength)
                                        if (ok) {
                                            selected = TextRange(minOf(start, end), maxOf(start, end))
                                            runCatching { passageFocus.requestFocus() }
                                        }
                                        println("TON1394 reader setSelection $start..$end of $bodyLength -> $ok")
                                        ok
                                    }
                                }) {
                                    BasicTextField(value = TextFieldValue(annotated, selected), onValueChange = {
                                        // Focus loss can collapse the field before a selection action receives its click.
                                        // Keep the chosen range until the user acts or explicitly dismisses it.
                                        if (!it.selection.collapsed) {
                                            selected = it.selection
                                            println("TON1394 reader onValueChange selection=${it.selection}")
                                        }
                                    },
                                        readOnly = true, modifier = Modifier.fillMaxWidth().focusRequester(passageFocus),
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = 20.sp,
                                            lineHeight = 32.sp, color = MaterialTheme.colorScheme.onSurface))
                                }
                            }
                            if (!selected.collapsed) {
                                Text("选中文本", style = MaterialTheme.typography.labelMedium)
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                    TextButton(onClick = { addMark(false) }) { Text("划线") }
                                    TextButton(onClick = { addMark(true) }) { Text("一句批注") }
                                    TextButton(onClick = { ask("explain") }) { Text("再讲一遍") }
                                    TextButton(onClick = { ask("example") }) { Text("举个例子") }
                                    TextButton(onClick = { selected = TextRange.Zero }) { Text("取消选择") }
                                }
                            }
                            Text(section.strings("sources").joinToString("\n"), style = MaterialTheme.typography.labelMedium)
                            val own = marks.filter { it.str("section") == section.str("section_id") }
                            for (mark in own) {
                                PersonalMarks(listOf(mark), sections)
                                Row {
                                    TextButton(onClick = { editMark = mark.str("id") }) { Text("编辑这一句") }
                                    TextButton(onClick = { set("marks", JsonArray(marks.filterNot { it.str("id") == mark.str("id") })) }) { Text("删除划线") }
                                }
                            }
                        }
                        if (!focus) {
                            // The map's open state travels with the rest of the reader state instead of a local
                            // remember: a remembered flag was written but never re-read by the composition that drew
                            // the heading, so tapping it left the rows hidden. Lifting it also keeps the reader's
                            // chosen view across visits, like 深色阅读 and 专心读 below/above.
                            val map = state.flag("map")
                            TextButton(onClick = { set("map", JsonPrimitive(!map)); println("TON1394 reader map=${!map}") }) {
                                Text(if (map) "收起本周脉络" else "本周脉络：节 → 考点 → 题")
                            }
                            if (map) for (s in sections) {
                                TextButton(onClick = { onSection(s.str("section_id")) }) { Text("要点：" + com.coach.client.displayTitle(s.str("title"))) }
                                for (fact in s.strings("fact_ids")) {
                                    val mastered = factMastered(fact, items)
                                    TextButton(onClick = { onSection(s.str("section_id")) }) {
                                        Text((if (mastered) "✓ 相关题均答对 · " else "○ 考点 · ") + com.coach.client.displayTitle(s.str("title")),
                                            color = if (mastered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    emphasis(listOf(fact))
                                    for (q in items.filter { fact in it.strings("factIds") })
                                        TextButton(onClick = { onQuiz(s.str("section_id"), q.str("id")) }, modifier = Modifier.padding(start = 16.dp)) {
                                            Text("这一题 · " + when (q.str("status")) {
                                                "mastered" -> "答对过（非猜）"; "guessed" -> "猜对，待练"; "missed" -> "做错，待练"; else -> "待练"
                                            })
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }
        val edit = marks.firstOrNull { it.str("id") == editMark }
        if (edit != null) AlertDialog(onDismissRequest = { editMark = null }, title = { Text("我的一句话（自动保存）") }, text = {
            Column {
                Text(edit.str("quote"))
                OutlinedTextField(value = edit.str("note"), onValueChange = { note ->
                    set("marks", JsonArray(marks.map { if (it.str("id") == editMark) JsonObject(it + ("note" to JsonPrimitive(note.take(500)))) else it }))
                }, label = { Text("一句批注") }, modifier = Modifier.testTag("reader-note").semantics { contentDescription = "一句批注输入框" })
                Text("个人批注不作为课件或教材证据。")
            }
        }, confirmButton = { TextButton(onClick = { editMark = null }) { Text("返回阅读") } })
        if (action != null) AlertDialog(onDismissRequest = { action = null }, title = { Text("选中追问") }, text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(action!!.str("quote"))
                when {
                    answer != null -> {
                        Text(answer!!.str("text").ifBlank { "这一段对不上课件或教材，暂时没法确认。" })
                        for (p in (answer!!["sources"] as? JsonArray).orEmpty().map { it.jsonObject }) {
                            TextButton(onClick = { page = p.str("docId") to p.num("pdfPage").toInt() }) {
                                Text("PDF 第 ${p.num("pdfPage")} 页")
                            }
                        }
                    }
                    failure != null -> {
                        Text(failure!!)
                        TextButton(onClick = { action = JsonObject(action!! + ("requestId" to JsonPrimitive(Random.nextLong().toULong().toString(16)))); failure = null }) { Text("重试追问") }
                    }
                    else -> Text("正在查课件和教材依据…")
                }
            }
        }, confirmButton = { TextButton(onClick = { action = null }) { Text(if (answer == null && failure == null) "取消，返回阅读" else "返回阅读") } })
        page?.let { key ->
            val source by produceState<com.coach.client.SourcePage?>(null, key) { value = http.page(key.first, key.second) }
            AlertDialog(onDismissRequest = { page = null }, title = { Text("PDF 第 ${key.second} 页") },
                text = { Text(source?.text ?: "暂时取不到原文页。", Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { page = null }) { Text("返回追问") } })
        }
    }
}
