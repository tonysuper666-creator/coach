package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.client.CourseHttp
import com.coach.client.KpRow
import com.coach.domain.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.random.Random

@Composable
internal fun LadderCheckpoint(session: LadderSession, points: List<KpRow>, gap: Boolean, recap: Boolean,
                              onContinue: () -> Unit, onNotes: () -> Unit, onNext: (() -> Unit)?, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("ladder-checkpoint"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (recap) "刚学会的" else "本节脉络", style = MaterialTheme.typography.headlineSmall)
        val memories = if (recap) QuestionLadder.recap(session) else session.responses.filterNot { it.tryFirst }.map { it.remember }.filter { it.isNotBlank() }.distinct()
        memories.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        if (!recap) {
            if (gap) Text("这一节已核对的题暂时不够，进度已保留。还没会的地方会继续补题。")
            Text("这轮做了 ${session.responses.size} 题。看过讲解和答对是两回事，学习状态按真实作答保留。")
            points.forEach { point -> Text("${splitTitle(point.title).second} · ${point.status.label}") }
            if (session.responses.any { !it.independentCorrect && !it.tryFirst }) Text("答错、不会和猜对的原题保留在学习记录，后续复习会接着练。")
        }
        TextButton(onClick = onNotes) { Text("展开要点原文") }
        if (recap) Button(onClick = onContinue, modifier = Modifier.testTag("ladder-recap-continue")) { Text("接着做题") }
        else if (onNext != null) Button(onClick = onNext, modifier = Modifier.testTag("ladder-next-section")) { Text("下一节") }
        TextButton(onClick = onBack) { Text("回本周") }
    }
}

/** A companion panel uses the same cited reader service as paragraph questions. */
@Composable
internal fun LadderQuestionAsk(http: CourseHttp, week: Int, itemId: String, onAsked: () -> Unit, onClose: () -> Unit) {
    var question by remember(itemId) { mutableStateOf("") }
    var request by remember(itemId) { mutableStateOf<JsonObject?>(null) }
    var answer by remember(itemId) { mutableStateOf<JsonObject?>(null) }
    var error by remember(itemId) { mutableStateOf<String?>(null) }
    LaunchedEffect(request) {
        val wire = request ?: return@LaunchedEffect
        try {
            val response = http.explainSelection(week, wire)
            answer = response.body; error = response.error
        } finally {
            withContext(NonCancellable) { http.cancelSelection(week, wire.str("requestId")) }
        }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("问一句", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(question, { question = it.take(500) }, label = { Text("问一句…") }, modifier = Modifier.fillMaxWidth().testTag("ladder-question"))
        Button(onClick = {
            onAsked(); answer = null; error = null
            request = json("requestId" to Random.nextLong().toULong().toString(16), "kind" to "explain", "itemId" to itemId, "question" to question)
        }, enabled = question.isNotBlank() && (request == null || answer != null || error != null)) { Text("发送") }
        if (request != null && answer == null && error == null) Text("正在查课件和教材依据…")
        error?.let { Text(it) }
        answer?.let { reply ->
            Text(reply.str("text"))
            (reply["sources"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { source ->
                Text("${source.str("docId")} · PDF 第 ${source.num("pdfPage")} 页")
            }
        }
        TextButton(onClick = onClose) { Text("回到这道题") }
    }
}
