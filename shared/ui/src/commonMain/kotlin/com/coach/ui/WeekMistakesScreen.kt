package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.coach.client.*
import com.coach.domain.*
import com.coach.domain.math.MathDelimiters
import com.coach.protocol.CommandBody
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray

/** Reuse verified original questions with fresh attempt IDs and server grading. Never alters the weekly cursor. */
@Composable
fun WeekMistakesScreen(c: CoachController, view: CourseView, week: Int, back: String = "← 今天", onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var thread by remember(week) { mutableStateOf<String?>(null) }
    var choice by remember(thread) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun command(type: String, payload: JsonObject, onResult: (JsonObject) -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                val receipt = withContext(Dispatchers.IO) {
                    val cmd = c.sync.enqueue(CommandBody(type, c.courseId, c.nowMillis(), payload))
                    c.sync.syncOnce()
                    c.sync.receipt(cmd.commandId)
                }
                when {
                    receipt == null -> error = "请求尚未确认，已保留；稍后会自动重试。"
                    receipt.status in setOf("OK", "DUPLICATE") -> onResult(receipt.result)
                    receipt.error == "QUESTION_OPTIONS_UNAVAILABLE" -> error = "这道历史题没有可用的选项，无法再练。请继续其他错题。"
                    receipt.error == "QUESTION_UNAVAILABLE" -> error = "这道题目前没有可用的已核验答案，请继续其他错题。"
                    receipt.error == "MISTAKE_CHANGED" -> error = "这道题已不在本周待练错题中，请刷新后继续。"
                    else -> error = "这次请求未被接受，请刷新后重试。"
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "暂时未能确认请求，请检查连接；已保存的请求会自动重试。" }
            finally { busy = false }
        }
    }
    val group = thread?.let { view.quizGroups(it).lastOrNull() }
    val card = group?.cards?.singleOrNull()
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(back) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("第 $week 周错题", style = MaterialTheme.typography.titleLarge) }
            if (card != null) {
                item { QuizCardView(1, 1, card, group.submitted, choice, c.math) { choice = it } }
                item {
                    if (!group.submitted) Button(enabled = choice != null && !busy, onClick = {
                        val posed = view.events.last { it.type == "question_posed" && it.payload.str("attemptId") == card.attemptId }
                        command("submit_answer", json("attemptId" to card.attemptId, "answer" to choice, "posedSnapshot" to posed.payload)) {}
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("提交答案") }
                    else TextButton(onClick = { thread = null; choice = null }) { Text("继续练错题") }
                }
            } else {
                val mistakes = weekMistakes(view.events, week)
                if (mistakes.isEmpty()) item { Text("这一周没有待练的选择题错题。") }
                items(mistakes, key = { it.id }) { e ->
                    WeekMistakeItem(e.payload.str("stem"), e.payload.str("attemptId"), c.math, !busy,
                        unavailable = (e.payload["options"] as? JsonArray).orEmpty().size < 2) { attemptId ->
                        command("retry_week_mistake", json("week" to week, "attemptId" to attemptId)) { thread = it.str("threadId") }
                    }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }
}

// A preview is intentionally short; the untouched stem is shown on the retry card.
private val embeddedImage = Regex("!\\[[^\\]]*]\\(\\s*data:image/[^\\s)]+\\s*\\)", RegexOption.IGNORE_CASE)
private val imageData = Regex("data:image/[^\\s)\\]<>\"']+", RegexOption.IGNORE_CASE)
internal fun mistakePreview(stem: String): String {
    val clean = stem.replace(embeddedImage, "［图片］").replace(imageData, "［图片］")
        .replace(Regex("\\s+"), " ").trim()
    val result = StringBuilder()
    var shortened = false
    for (segment in MathDelimiters.tokenize(clean).segments) {
        val remaining = 180 - result.length
        val chunk = clean.substring(segment.start, segment.end)
        if (chunk.length <= remaining) result.append(chunk)
        else {
            if (segment.math == null && remaining > 0) {
                var end = remaining
                if (end < chunk.length && end > 0 && chunk[end - 1].isHighSurrogate() && chunk[end].isLowSurrogate()) end--
                result.append(chunk.take(end).trimEnd())
            }
            shortened = true
            break
        }
    }
    return result.toString().trim().ifEmpty { if (shortened) "［公式］" else "［题目］" } + if (shortened) "…" else ""
}

@Composable
internal fun WeekMistakeItem(stem: String, attemptId: String, math: MathAssets, enabled: Boolean, unavailable: Boolean = false, onOpen: (String) -> Unit) {
    val preview = remember(stem) { mistakePreview(stem) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled = enabled, role = Role.Button) { onOpen(attemptId) }
            .testTag("week-mistake-$attemptId"),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            QuizText(preview, "mistake:$attemptId:$preview", math)
            Text(if (unavailable) "历史记录缺少选项，无法再练" else "点按再练原题", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
