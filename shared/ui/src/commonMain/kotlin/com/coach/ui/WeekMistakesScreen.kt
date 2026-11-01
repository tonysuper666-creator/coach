package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coach.client.*
import com.coach.domain.*
import com.coach.protocol.CommandBody
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject

/** Reuse verified original questions with fresh attempt IDs and server grading. Never alters the weekly cursor. */
@Composable
fun WeekMistakesScreen(c: CoachController, view: CourseView, week: Int, onBack: () -> Unit) {
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
                val result = withContext(Dispatchers.IO) {
                    val cmd = c.sync.enqueue(CommandBody(type, c.courseId, c.nowMillis(), payload))
                    c.sync.syncOnce()
                    val receipt = c.sync.receipt(cmd.commandId) ?: error("No receipt")
                    check(receipt.status in setOf("OK", "DUPLICATE"))
                    receipt.result
                }
                onResult(result)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "这道题暂时无法练习，请连接 Mac 后重试。" }
            finally { busy = false }
        }
    }
    val group = thread?.let { view.quizGroups(it).lastOrNull() }
    val card = group?.cards?.singleOrNull()
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 今天") }
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
                    OutlinedButton(enabled = !busy, onClick = {
                        command("retry_week_mistake", json("week" to week, "attemptId" to e.payload.str("attemptId"))) { thread = it.str("threadId") }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(e.payload.str("stem")) }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }
}
