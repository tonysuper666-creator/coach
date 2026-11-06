@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.coach.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.coach.client.CourseHttp
import com.coach.client.CourseView
import com.coach.client.MockReply
import com.coach.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlin.math.roundToLong

/**
 * 模拟考与单选速刷：首页 → 考试 → 成绩。卷面、计时与判分都在 Mac 上，交卷前手机拿不到答案和评分细则；
 * 中途退出不停表，回来接着做；到点由这里交卷，没开着的考试由 Mac 到点代交。
 */
@Composable
fun MockScreen(c: CoachController, view: CourseView, onBack: () -> Unit, initialMockId: String? = null) {
    val http = c.course
    var open by remember { mutableStateOf(initialMockId) }
    Column(Modifier.fillMaxSize()) {
        if (http == null) {
            BackRow("模拟考", "← 复习", onBack)
            CenterMessage("模拟考要连接 Mac。")
        } else if (open == null) MockHome(c, http, view, onBack) { open = it }
        // Opened straight from the records page: back goes back there, not to the exam list.
        else key(open) { MockSessionPane(c, http, open!!) { if (initialMockId != null) onBack() else open = null } }
    }
}

@Composable
private fun BackRow(title: String, back: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(back) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        trailing()
    }
    HorizontalDivider()
}

@Composable
private fun MockHome(c: CoachController, http: CourseHttp, view: CourseView, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    val revision by c.sync.revision.collectAsState()
    val reply by produceState<MockReply?>(null, reload, revision) { value = http.mocks() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<JsonObject?>(null) }
    fun start(call: suspend () -> MockReply) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                val r = call()
                val id = r.body?.str("mockId")
                if (!id.isNullOrBlank()) onOpen(id) else { error = r.error ?: "连不上 Mac，没能开考。"; reload++ }
            } finally { busy = false }
        }
    }
    BackRow("模拟考", "← 复习", onBack)
    val home = reply?.body
    if (reply == null) return CenterMessage("正在问 Mac…")
    if (home == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(reply?.error ?: "连不上 Mac。模拟考的卷子和判分都在 Mac 上，要联网才能考。", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { reload++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
        }
        return
    }
    val offset = remember(home) { home.num("serverNow") - c.nowMillis() }
    var now by remember { mutableStateOf(c.nowMillis()) }
    LaunchedEffect(Unit) { while (true) { now = c.nowMillis(); delay(1000) } }
    val papers = (home["papers"] as? JsonArray).orEmpty().map { it.jsonObject }
    val drill = home["drill"] as? JsonObject ?: JsonObject(emptyMap())
    val active = home["active"] as? JsonObject
    LazyColumn(Modifier.fillMaxSize().testTag("mock-home"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (active != null) item {
            Surface(tonalElevation = 3.dp, shape = MaterialTheme.shapes.medium, border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("还有一场没交卷", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(active.str("title"), style = MaterialTheme.typography.titleMedium)
                    val left = active.num("deadline") - (now + offset)
                    Text(if (left > 0) "剩余 ${clock(left)}，计时没有暂停" else "已到时间，打开即交卷", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { onOpen(active.str("mockId")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("继续作答") }
                }
            }
        }
        item { SectionTitle("整卷模拟") }
        if (papers.isEmpty()) item { Hint("还没有可用的仿真卷。卷子放进 Mac 上的卷库后会出现在这里。") }
        items(papers.size) { i ->
            val p = papers[i]
            Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(p.str("title"), style = MaterialTheme.typography.bodyLarge)
                        Text("${p.num("items")} 题 · 满分 ${marks(p.dbl("max"))} 分 · ${p.num("minutes")} 分钟", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { confirm = p }, enabled = !busy && active == null, modifier = Modifier.heightIn(min = 56.dp)) { Text("开考") }
                }
            }
        }
        item { SectionTitle("单选速刷") }
        item {
            val total = drill.num("total")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(if (total == 0L) "题包里还没有单选题。" else "${drill.num("size", 7)} 题一组 · ${drill.num("minutes", 14)} 分钟 · 交卷立即出分和讲解，错题进按周错题本")
                if (total > 0) {
                    Button(onClick = { start { http.startDrill(null) } }, enabled = !busy && active == null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("按押题权重抽题") }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (drill["weeks"] as? JsonArray).orEmpty().map { it.jsonObject }.forEach { w ->
                            OutlinedButton(onClick = { start { http.startDrill(w.num("week").toInt()) } }, enabled = !busy && active == null, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("第 ${w.num("week")} 周 · ${w.num("count")} 题")
                            }
                        }
                    }
                }
                if (active != null) Hint("先交掉进行中的那一场，才能开新的。")
            }
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        val history = (home["history"] as? JsonArray).orEmpty().map { it.jsonObject }
        if (history.isNotEmpty()) {
            item { SectionTitle("历次成绩") }
            items(history.size) { i -> HistoryRow(c, view, history[i]) { onOpen(history[i].str("mockId")) } }
        }
        val invalid = (home["invalid"] as? JsonArray).orEmpty().map { it.jsonObject }
        if (invalid.isNotEmpty()) item {
            Text("有 ${invalid.size} 个卷子文件读不出来，没有列出：\n" + invalid.joinToString("\n") { "· ${it.str("file")}：${it.str("reason")}" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("开始「${p.str("title")}」？") },
            text = { Text("限时 ${p.num("minutes")} 分钟，开考后计时不暂停。中途退出可以回来续考，到点自动交卷。") },
            confirmButton = { Button(onClick = { confirm = null; start { http.startPaper(p.str("id")) } }) { Text("开考") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}

@Composable
internal fun HistoryRow(c: CoachController, view: CourseView, h: JsonObject, onClick: () -> Unit) {
    Surface(onClick = onClick, tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(h.str("title"), style = MaterialTheme.typography.bodyLarge)
                val at = h.num("submittedAt")
                Text(dayLabel(logicalDay(at, view.zone)) + " " + c.formatClock(at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${marks(h.dbl("score"))} / ${marks(h.dbl("max"))}", style = MaterialTheme.typography.titleMedium)
                if (h.num("pending") > 0) Text("${h.num("pending")} 题批改中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One exam: shows the paper while it runs, the marks once it is in. Grades that arrive later (AI marking, review) refresh the result. */
@Composable
private fun MockSessionPane(c: CoachController, http: CourseHttp, mockId: String, onBack: () -> Unit) {
    var reload by remember { mutableStateOf(0) }
    var body by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val revision by c.sync.revision.collectAsState()
    val submitted = body?.str("status") == "submitted"
    // While the exam runs the pane owns the answers; refetching would overwrite unsaved typing.
    LaunchedEffect(reload, if (submitted || body == null) revision else -1L) {
        val r = http.mock(mockId)
        if (r.body != null) { body = r.body; error = null } else error = r.error ?: "连不上 Mac，取不到这场考试。"
    }
    val current = body
    when {
        current == null -> {
            BackRow("模拟考", "← 返回", onBack)
            if (error == null) CenterMessage("正在打开…") else Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error!!, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { reload++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
            }
        }
        current.str("status") == "active" -> MockExam(c, http, current, onBack) { body = it }
        current.str("status") == "submitted" -> MockResult(c, current, onBack)
        else -> { BackRow(current.str("title"), "← 返回", onBack); CenterMessage("已交卷，正在判分…") }
    }
}

private sealed interface ExamRow {
    data class Section(val index: Int, val title: String, val instructions: String) : ExamRow
    data class Item(val number: Int, val item: JsonObject) : ExamRow
}

@Composable
private fun MockExam(c: CoachController, http: CourseHttp, exam: JsonObject, onLeave: () -> Unit, onResult: (JsonObject) -> Unit) {
    val mockId = exam.str("mockId")
    val paper = exam["paper"]!!.jsonObject
    val sections = (paper["sections"] as JsonArray).map { it.jsonObject }
    val rows = remember(paper) {
        var n = 0
        sections.flatMapIndexed { i, s ->
            listOf(ExamRow.Section(i, s.str("title"), s.str("instructions"))) + (s["items"] as JsonArray).map { ExamRow.Item(++n, it.jsonObject) }
        }
    }
    val items = rows.filterIsInstance<ExamRow.Item>()
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val deadline = exam.num("deadline")
    val offset = remember(exam) { exam.num("serverNow") - c.nowMillis() }
    var answers by remember { mutableStateOf(exam["answers"] as? JsonObject ?: JsonObject(emptyMap())) }
    var edits by remember { mutableStateOf(0) }
    var savedEdits by remember { mutableStateOf(0) }
    var saveNote by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(c.nowMillis()) }
    LaunchedEffect(Unit) { while (true) { now = c.nowMillis(); delay(500) } }
    val left = deadline - (now + offset)

    fun update(key: String, field: String, value: JsonElement) {
        val old = answers[key] as? JsonObject ?: JsonObject(emptyMap())
        answers = JsonObject(answers + (key to JsonObject(old + (field to value))))
        edits++
    }
    suspend fun submit(auto: Boolean) {
        if (submitting) return
        submitting = true; submitError = null
        try {
            val r = http.submitMock(mockId, answers, auto)
            if (r.body != null) onResult(r.body!!) else submitError = r.error ?: "交卷没送到 Mac。答案还在这里，连上后再点一次交卷。"
        } catch (e: CancellationException) { throw e }
        finally { submitting = false }
    }
    // Autosave a moment after typing stops; a failed save retries, so the Mac copy catches up after a dropped connection.
    LaunchedEffect(edits, saveNote) {
        if (edits == savedEdits) return@LaunchedEffect
        delay(if (saveNote == null) 1200 else 8000)
        val sent = edits
        val r = http.saveMock(mockId, answers)
        when {
            r.body?.str("status") == "submitted" -> onResult(r.body!!)
            r.body != null -> { savedEdits = sent; saveNote = null }
            else -> saveNote = r.error ?: "未保存：连不上 Mac，答案先留在这里"
        }
    }
    // At zero this screen hands the paper in; if that fails the Mac closes it shortly after on its own.
    LaunchedEffect(left <= 0) {
        if (left > 0) return@LaunchedEffect
        while (true) { submit(auto = true); delay(5000) }
    }

    fun answered(item: JsonObject): Boolean = when (item.str("type")) {
        "mcq" -> (answers[item.str("id")] as? JsonObject)?.get("choice")?.jsonPrimitive?.intOrNull != null
        "analytical" -> (item["parts"] as? JsonArray).orEmpty().any { p -> (answers[p.jsonObject.str("key")] as? JsonObject)?.let { it.str("text").isNotBlank() || it.str("final").isNotBlank() } == true }
        else -> (answers[item.str("id")] as? JsonObject)?.str("text")?.isNotBlank() == true
    }

    BackRow(paper.str("title"), "← 暂离", onLeave) {
        Text(if (left > 0) clock(left) else "00:00", Modifier.testTag("mock-timer"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            color = if (left < 5 * 60_000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Button(onClick = { confirm = true }, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (submitting) "交卷中…" else "交卷") }
    }
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(items) { _, row ->
            val done = answered(row.item)
            FilterChip(selected = done, onClick = { scope.launch { list.animateScrollToItem(rows.indexOf(row)) } },
                label = { Text("${row.number}") }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
    Text(saveNote ?: if (edits == savedEdits) "答案已存到 Mac · 退出不停表，可回来续考" else "正在保存…",
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall,
        color = if (saveNote != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    submitError?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
    HorizontalDivider()
    LazyColumn(Modifier.fillMaxSize().testTag("mock-paper"), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(rows) { _, row ->
            when (row) {
                is ExamRow.Section -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    if (row.instructions.isNotBlank()) Text(row.instructions, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is ExamRow.Item -> ExamItem(c, row, answers, ::update)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (confirm) {
        val notes = sections.mapNotNull { s ->
            val its = (s["items"] as JsonArray).map { it.jsonObject }
            val need = (s["choose"] as? JsonPrimitive)?.intOrNull ?: its.size
            val got = its.count(::answered)
            when {
                got < need -> "${s.str("title")}：还有 ${need - got} 题没答"
                got > need -> "${s.str("title")}：答了 $got 题，只按卷面顺序计前 $need 题"
                else -> null
            }
        }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("交卷？") },
            text = { Text((notes.ifEmpty { listOf("每题都答了。") } + "交卷后不能再改。").joinToString("\n")) },
            confirmButton = { Button(onClick = { confirm = false; scope.launch { submit(auto = false) } }) { Text("交卷") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("继续作答") } },
        )
    }
}

@Composable
private fun ExamItem(c: CoachController, row: ExamRow.Item, answers: JsonObject, update: (String, String, JsonElement) -> Unit) {
    val item = row.item
    val id = item.str("id")
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("第 ${row.number} 题 · ${marks(item.dbl("marks"))} 分", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            QuizText(item.str("stem"), "mock:$id", c.math)
            item.str("figure").takeIf { it.isNotBlank() }?.let { FigureSvg(it, Modifier.padding(vertical = 4.dp)) }
            when (item.str("type")) {
                "mcq" -> {
                    val chosen = (answers[id] as? JsonObject)?.get("choice")?.jsonPrimitive?.intOrNull
                    val letters = optionLetters(item.strings("options").size)
                    item.strings("options").forEachIndexed { i, option ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(chosen == i, role = Role.RadioButton) { update(id, "choice", JsonPrimitive(i)) },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RadioButton(selected = chosen == i, onClick = null)
                            Text(letters[i], style = MaterialTheme.typography.titleSmall)
                            Box(Modifier.weight(1f)) { QuizText(option, "mock:$id:$i", c.math) }
                        }
                    }
                }
                "essay" -> {
                    val text = (answers[id] as? JsonObject)?.str("text").orEmpty()
                    OutlinedTextField(text, { update(id, "text", JsonPrimitive(it)) }, Modifier.fillMaxWidth(), minLines = 8, placeholder = { Text("在这里写论述") })
                    val target = (item["word_target"] as? JsonPrimitive)?.intOrNull
                    Text("已写 ${wordCount(text)} 词" + (target?.let { " · 目标约 $it 词" } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "analytical" -> (item["parts"] as? JsonArray).orEmpty().map { it.jsonObject }.forEach { part ->
                    val key = part.str("key")
                    val a = answers[key] as? JsonObject
                    Text("(${part.str("label")}) ${marks(part.dbl("marks"))} 分", style = MaterialTheme.typography.titleSmall)
                    QuizText(part.str("prompt"), "mock:$key", c.math)
                    OutlinedTextField(a?.str("text").orEmpty(), { update(key, "text", JsonPrimitive(it)) }, Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text("写出步骤") })
                    if (part.flag("numeric")) OutlinedTextField(a?.str("final").orEmpty(), { update(key, "final", JsonPrimitive(it)) }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("最终数值（保留两位小数）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            }
        }
    }
}

@Composable
private fun MockResult(c: CoachController, result: JsonObject, onBack: () -> Unit) {
    val items = (result["items"] as? JsonArray).orEmpty().map { it.jsonObject }
    val numbers = items.map { it.str("itemId") }.distinct().withIndex().associate { it.value to it.index + 1 }
    val lost = (result["lostByKp"] as? JsonArray).orEmpty().map { it.jsonObject }
    BackRow(result.str("title"), "← 模拟考", onBack)
    LazyColumn(Modifier.fillMaxSize().testTag("mock-result"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${marks(result.dbl("score"))} / ${marks(result.dbl("max"))} 分", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                val spent = result.num("submittedAt") - result.num("startedAt")
                Text((if (result.flag("auto")) "到点自动交卷" else "手动交卷") + " · 用时 ${clock(spent)}" + if (result.str("purpose") == "FULL_MOCK") " · 计入整卷模拟证据" else " · 计入限时证据",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (result.num("pending") > 0) Text("还有 ${result.num("pending")} 题在批改：Mac 上按评分细则逐条打分，批完这里自动更新，总分会变。", style = MaterialTheme.typography.bodyMedium)
                if (result.num("drafts") > 0) Text("${result.num("drafts")} 题是 AI 初评，复核后可能调整。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (lost.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("丢分考点")
                lost.forEach { l ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(l.str("kp").ifBlank { "未标考点" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text("丢 ${marks(l.dbl("lost"))} / ${marks(l.dbl("max"))} 分", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item { SectionTitle("逐题") }
        items(items.size) { i -> ResultItem(c, items[i], numbers[items[i].str("itemId")] ?: 0) }
    }
}

private val statusLabels = mapOf("GRADED" to "已判分", "REVIEWED" to "已复核", "DRAFT" to "AI 初评", "PENDING" to "批改中", "FAILED" to "批改失败", "SKIPPED" to "未计分")

@Composable
private fun ResultItem(c: CoachController, it: JsonObject, number: Int) {
    val status = it.str("status")
    val key = it.str("key")
    var showReference by remember(key) { mutableStateOf(false) }
    val full = it["score"] != null && it.dbl("score") >= it.dbl("marks")
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("第 $number 题" + it.str("label").takeIf { l -> l.isNotBlank() }?.let { l -> " ($l)" }.orEmpty(), style = MaterialTheme.typography.labelLarge)
                StatusChip(statusLabels[status] ?: status, when (status) { "FAILED" -> MaterialTheme.colorScheme.error; "PENDING", "SKIPPED" -> MaterialTheme.colorScheme.onSurfaceVariant; else -> MaterialTheme.colorScheme.primary })
                Spacer(Modifier.weight(1f))
                Text(if (it["score"] != null) "${marks(it.dbl("score"))} / ${marks(it.dbl("marks"))}" else "— / ${marks(it.dbl("marks"))}",
                    style = MaterialTheme.typography.titleMedium, color = if (full) successColor() else if (it["score"] != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (it.str("kp").isNotBlank()) Text(it.str("kp"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status == "SKIPPED") { Text("选做题里没选这道，不计分。", style = MaterialTheme.typography.bodyMedium); return@Column }
            QuizText(it.str("stem"), "mockr:$key", c.math)
            val answer = it["answer"] as? JsonObject ?: JsonObject(emptyMap())
            if (it.str("type") == "mcq") {
                val options = it.strings("options")
                val letters = optionLetters(options.size)
                val chosen = answer["choice"]?.jsonPrimitive?.intOrNull
                val correct = it.num("correct", -1).toInt()
                options.forEachIndexed { i, o ->
                    val mark = when { i == correct -> "✓"; i == chosen -> "✗"; else -> " " }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$mark ${letters[i]}", color = when (mark) { "✓" -> successColor(); "✗" -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant }, style = MaterialTheme.typography.titleSmall)
                        Box(Modifier.weight(1f)) { QuizText(o, "mockr:$key:$i", c.math) }
                    }
                }
                if (chosen == null) Text("你没有作答。", color = MaterialTheme.colorScheme.error)
            } else {
                Text("你的作答", style = MaterialTheme.typography.labelLarge)
                Text(answer.str("text").ifBlank { "（空）" }, style = MaterialTheme.typography.bodyMedium)
                if (answer.str("final").isNotBlank()) Text("最终数值：${answer.str("final")}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            (it["reasons"] as? JsonArray).orEmpty().map { r -> r.jsonObject }.takeIf { r -> r.isNotEmpty() }?.let { reasons ->
                Text("逐条给分", style = MaterialTheme.typography.labelLarge)
                reasons.forEach { r ->
                    Column(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                        Text("${r.str("criterion")}：${marks(r.dbl("points"))} / ${marks(r.dbl("max"))}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        if (r.str("reason").isNotBlank()) Text(r.str("reason"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (it.str("feedback").isNotBlank()) QuizText(it.str("feedback"), "mockf:$key", c.math)
            it.str("reviewNote").takeIf { n -> n.isNotBlank() }?.let { n -> Text("复核：$n", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (status == "FAILED") Text("这题自动批改没成功，Mac 上会重试；总分暂不含这题。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            if (it.str("reference").isNotBlank()) {
                TextButton(onClick = { showReference = !showReference }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showReference) "收起参考答案" else "看参考答案") }
                if (showReference) QuizText(it.str("reference"), "mockref:$key", c.math)
            }
        }
    }
}

@Composable private fun SectionTitle(text: String) = Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
@Composable private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun JsonObject.dbl(key: String): Double = (get(key) as? JsonPrimitive)?.doubleOrNull ?: 0.0

/** Marks are kept to two decimals; whole marks print without them. */
internal fun marks(value: Double): String {
    val h = (value * 100).roundToLong()
    val whole = h / 100; val frac = kotlin.math.abs(h % 100)
    return if (frac == 0L) "$whole" else "$whole." + frac.toString().padStart(2, '0').trimEnd('0')
}

internal fun clock(millis: Long): String {
    val s = (millis.coerceAtLeast(0) + 999) / 1000
    val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
    fun two(v: Long) = v.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(sec)}" else "${two(m)}:${two(sec)}"
}

/** English words plus CJK characters, the way an exam word target is counted. */
internal fun wordCount(text: String): Int = Regex("[A-Za-z0-9]+(?:['’\\-.][A-Za-z0-9]+)*").findAll(text).count() + text.count { it.code in 0x4E00..0x9FFF }
