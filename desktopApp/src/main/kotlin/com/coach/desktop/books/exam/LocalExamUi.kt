package com.coach.desktop.books.exam

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.coach.domain.*
import com.coach.ui.v7.PrimaryButton
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.math.BigDecimal

private fun points(value: Long): String = BigDecimal.valueOf(value, 2).stripTrailingZeros().toPlainString()
private fun paperItems(attempt: JsonObject): List<JsonObject> =
    attempt.getValue("paper").jsonObject.getValue("sections").jsonArray.flatMap { section ->
        section.jsonObject.getValue("items").jsonArray.map { it.jsonObject }
    }

@Composable
private fun ExamSaveStatus(ui: LocalExamUi, controller: LocalExamController) {
    val scope = rememberCoroutineScope()
    var confirmReload by remember { mutableStateOf(false) }
    if (ui.error != null) {
        Text(ui.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("local-exam-error"))
        if (ui.conflict) TextButton({ confirmReload = true }, enabled = !ui.busy,
            modifier = Modifier.testTag("local-reload-saved")) { Text("读取已保存作答") }
        else TextButton({ scope.launch { controller.flush() } }, enabled = !ui.busy,
            modifier = Modifier.testTag("local-save-retry")) { Text("重试保存") }
    } else if (ui.saving) Text("正在保存…", modifier = Modifier.testTag("local-save-status"))
    else if (ui.attempt?.str("status") == "active") Text("作答已保存", modifier = Modifier.testTag("local-save-status"))
    if (confirmReload) AlertDialog(onDismissRequest = { confirmReload = false },
        title = { Text("读取已保存作答？") },
        text = { Text("会丢弃本页尚未保存的内容，重新读取这台电脑已保存的作答。请先复制需要保留的文字。") },
        confirmButton = { TextButton({ confirmReload = false; scope.launch { controller.reloadSaved() } },
            modifier = Modifier.testTag("local-confirm-reload")) { Text("重新载入") } },
        dismissButton = { TextButton({ confirmReload = false }) { Text("保留当前内容") } })
}

@Composable
internal fun LocalExamsSummary(controller: LocalExamController) {
    val ui by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().testTag("course-exams-summary"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("本地练习与记录", style = MaterialTheme.typography.titleLarge)
        Text("教材参考练习，答案尚未独立核对，不计时。")
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ExamSaveStatus(ui, controller)
        ui.active?.let { active ->
            Text("还有一份未交卷练习", style = MaterialTheme.typography.titleMedium)
            PrimaryButton({ scope.launch { controller.open(active.str("mockId")) } }, enabled = !ui.busy,
                modifier = Modifier.testTag("local-resume")) { Text("继续未交卷练习") }
        }
        val reference = ui.reference
        val current = reference?.paper
        if (reference != null && current == null) {
            Text("这本教材暂时生成不了练习。")
            reference.gaps.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        } else if (current != null && ui.papers.none { it.str("id") == current.id }) {
            PrimaryButton({ scope.launch { controller.generateAndStart() } }, enabled = !ui.busy && ui.active == null,
                modifier = Modifier.testTag("local-generate-start")) { Text("生成并开始练习") }
        }
        if (current != null && reference.gaps.isNotEmpty()) {
            Text("部分章节暂无可用题，这份练习只包含已有题。", style = MaterialTheme.typography.bodySmall)
        }
        // Old reference versions remain in attempts/history; only the current generated version is offered for a new start.
        ui.papers.filter { it.str("origin") == "AUTHORED" || it.str("id") == current?.id }.forEachIndexed { index, paper ->
            Text("${paper.str("title")} · ${paper.num("items")} 题",
                style = MaterialTheme.typography.titleMedium)
            if (paper.str("origin") == "AUTHORED") Text("自编题卷，参考答案尚未独立核对。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ scope.launch { controller.start(paper.str("id")) } }, enabled = !ui.busy && ui.active == null,
                modifier = Modifier.testTag("local-paper-start-$index")) { Text("开始作答") }
        }
        if (ui.history.isNotEmpty()) Text("历次结果", style = MaterialTheme.typography.titleMedium)
        ui.history.forEachIndexed { index, record ->
            val result = record.getValue("result").jsonObject
            val grade = if (result.num("pending") > 0) "总分待评" else
                "参考得分 ${points(result.num("score"))} / ${points(result.num("max"))}"
            OutlinedButton({ scope.launch { controller.open(record.str("mockId")) } }, enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth().testTag("local-history-$index")) {
                Text("${record.getValue("paper").jsonObject.str("title")} · $grade · 查看结果")
            }
        }
    }
}

@Composable
internal fun LocalExamPane(controller: LocalExamController) {
    val ui by controller.state.collectAsState()
    val attempt = checkNotNull(ui.attempt)
    val scope = rememberCoroutineScope()
    val source = ui.source
    if (source != null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp).testTag("local-frozen-source"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(controller::closeSource, modifier = Modifier.testTag("local-source-back")) {
                Text(if (attempt.str("status") == "active") "返回作答" else "返回结果")
            }
            Text(source.str("bookTitle"), style = MaterialTheme.typography.headlineMedium)
            Text("本次题卷保存的教材片段 · 第 ${source.num("pdfPage")} 页")
            Text(source.str("quote"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("local-source-quote"))
            HorizontalDivider()
            SelectionContainer { Text(source.str("pageText")) }
        }
        return
    }
    val submitted = attempt.str("status") == "submitted"
    var confirm by remember(attempt.str("mockId")) { mutableStateOf(false) }
    val scroll = remember(attempt.str("mockId"), submitted) { ScrollState(0) }
    LaunchedEffect(ui.error) { if (ui.error != null) scroll.scrollTo(0) }
    val result = attempt["result"] as? JsonObject
    val answers = attempt.getValue("answers").jsonObject
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(28.dp)
        .testTag(if (submitted) "local-exam-result" else "local-exam-attempt"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton({ scope.launch { controller.back() } }, enabled = !ui.busy,
            modifier = Modifier.testTag("local-exam-back")) { Text("返回课程") }
        Text(attempt.getValue("paper").jsonObject.str("title"), style = MaterialTheme.typography.headlineMedium)
        Text(if (attempt.str("origin") == "TEXTBOOK_REFERENCE") "教材参考练习 · 不计时" else "自编题卷 · 不计时")
        Text("作答记录：${attempt.str("mockId")}", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("local-attempt-id"))
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ExamSaveStatus(ui, controller)
        if (result != null) {
            if (result.num("pending") > 0) {
                Text("总分待评", style = MaterialTheme.typography.titleLarge)
                Text("已计分题目：${points(result.num("knownScore"))} 分 · ${result.num("pending")} 题待评")
            } else Text("参考得分：${points(result.num("score"))} / ${points(result.num("max"))}",
                style = MaterialTheme.typography.titleLarge)
            Text("参考答案尚未独立核对，结果仅供对照。")
        } else Text("答案尚未独立核对。可以随时返回课程，再继续这次作答。")
        paperItems(attempt).forEachIndexed { index, item ->
            val key = item.str("id")
            val answer = answers[key] as? JsonObject ?: json()
            HorizontalDivider()
            Text("第 ${index + 1} 题 · ${if (item.str("type") == "mcq") "选择" else "论述"}",
                style = MaterialTheme.typography.titleMedium)
            Text(item.str("stem"))
            if (!submitted && item.str("type") == "mcq") {
                item.strings("options").forEachIndexed { option, text ->
                    val selected = (answer["choice"] as? JsonPrimitive)?.intOrNull == option
                    Row(Modifier.fillMaxWidth().selectable(selected, enabled = !ui.busy && !ui.conflict, role = Role.RadioButton,
                        onClick = { controller.answer(key, json("choice" to option)) })
                        .testTag("local-choice-$key-$option").padding(8.dp)) {
                        RadioButton(selected, onClick = null, enabled = !ui.busy && !ui.conflict)
                        Text(text, Modifier.weight(1f).padding(start = 8.dp))
                    }
                }
                TextButton({ controller.answer(key, json("choice" to null)) }, enabled = !ui.busy && !ui.conflict,
                    modifier = Modifier.testTag("local-clear-$key")) { Text("清空作答") }
            } else if (!submitted) {
                OutlinedTextField(answer.str("text"), { controller.answer(key, json("text" to it)) },
                    label = { Text("写下你的回答") }, enabled = !ui.busy, readOnly = ui.conflict, minLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("local-text-$key"))
                TextButton({ controller.answer(key, json("text" to "")) }, enabled = !ui.busy && !ui.conflict,
                    modifier = Modifier.testTag("local-clear-$key")) { Text("清空作答") }
            } else {
                val chosen = (answer["choice"] as? JsonPrimitive)?.intOrNull
                Text("本次作答：" + if (item.str("type") == "mcq")
                    (chosen?.let { item.strings("options").getOrNull(it) } ?: "未作答") else answer.str("text").ifBlank { "未作答" })
                val grade = result?.get("items")?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.str("key") == key }
                if (grade?.str("status") == "PENDING_REVIEW") Text("这题待评，尚未计入总分。")
                else grade?.let { Text("参考得分：${points(it.num("score"))} / ${points(it.num("max"))}") }
                if (item.str("type") == "mcq") (item["answer"] as? JsonPrimitive)?.intOrNull?.let { correct ->
                    Text("参考答案：" + item.strings("options")[correct])
                }
            }
            TextButton({ controller.showSource(item.getValue("source").jsonObject) }, enabled = !ui.busy,
                modifier = Modifier.testTag("local-source-$key")) { Text("查看题源") }
        }
        if (!submitted) PrimaryButton({ confirm = true }, enabled = !ui.busy && !ui.conflict,
            modifier = Modifier.testTag("local-submit")) { Text("交卷") }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("交卷吗？") },
        text = { Text("交卷后不能修改本次作答，仍可查看结果和当时保存的题源。") },
        confirmButton = {
            TextButton({ confirm = false; scope.launch { controller.submit() } },
                modifier = Modifier.testTag("local-confirm-submit")) { Text("确认交卷") }
        }, dismissButton = { TextButton({ confirm = false }) { Text("继续作答") } })
}
