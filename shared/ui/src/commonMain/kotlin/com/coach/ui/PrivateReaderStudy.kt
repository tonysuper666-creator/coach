package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.client.ReaderHttp
import kotlinx.serialization.json.*

internal data class PageStudyFact(val id: String, val text: String, val mastered: Boolean, val anchor: String = "")
internal data class PageStudyQuestion(val id: String, val week: Int, val stem: String, val status: String, val anchor: String = "")
internal data class PageStudy(val facts: List<PageStudyFact>, val questions: List<PageStudyQuestion>, val unlocated: Int) {
    val factsMastered get() = facts.count { it.mastered }
    val questionsMastered get() = questions.count { it.status == "mastered" }
}

/** A malformed reply is a failure, not an empty page: an unreadable index must never look like "no exam points here". */
internal fun pageStudy(body: JsonObject?): PageStudy? {
    body ?: return null
    val facts = (body["facts"] as? JsonArray)?.map { f ->
        val o = f as? JsonObject ?: return null
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        PageStudyFact(id, (o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (o["mastered"] as? JsonPrimitive)?.booleanOrNull == true, (o["anchor"] as? JsonPrimitive)?.contentOrNull.orEmpty())
    } ?: return null
    val questions = (body["questions"] as? JsonArray)?.map { q ->
        val o = q as? JsonObject ?: return null
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        PageStudyQuestion(id, (o["week"] as? JsonPrimitive)?.intOrNull ?: return null,
            (o["stem"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (o["status"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (o["anchor"] as? JsonPrimitive)?.contentOrNull.orEmpty())
    } ?: return null
    return PageStudy(facts, questions, (body["unlocated"] as? JsonPrimitive)?.intOrNull ?: 0)
}

private fun statusLabel(status: String) = when (status) {
    "mastered" -> "答对过（非猜）"; "missed" -> "做错过"; "guessed" -> "猜对过"; "pending" -> "答案键待核对"; else -> "未做"
}

/** "本页考点 n 个 · 已掌握 m · 题 k 道": the index of this page only, loaded per page and never from another course. */
@Composable
internal fun PageStudyPanel(reader: ReaderHttp, documentId: String, page: Int,
                            practice: (@Composable (week: Int, ids: Set<String>, close: () -> Unit) -> Unit)? = null,
                            onShowAnchor: ((String) -> Unit)? = null) {
    var retry by remember(documentId, page) { mutableIntStateOf(0) }
    val result by produceState<ReaderLoad<PageStudy>>(ReaderLoad.Loading, reader, documentId, page, retry) {
        value = ReaderLoad.Loading
        val reply = reader.study(documentId, page)
        value = (if (reply.ok) pageStudy(reply.body) else null)?.let { ReaderLoad.Ready(it) } ?: ReaderLoad.Failed
    }
    var open by remember(documentId, page) { mutableStateOf(false) }
    var practicing by remember(documentId, page) { mutableStateOf<Pair<Int, Set<String>>?>(null) }
    practicing?.let { (week, ids) ->
        androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Surface(Modifier.fillMaxSize().testTag("private-practice")) { practice?.invoke(week, ids) { practicing = null; retry++ } }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("private-study")) {
        when (val r = result) {
            ReaderLoad.Loading -> Text("正在读取本页考点…", style = MaterialTheme.typography.labelMedium)
            ReaderLoad.Failed -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("本页考点暂时读不到。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("private-study-failed"))
                TextButton(onClick = { retry++ }, modifier = Modifier.testTag("private-study-retry")) { Text("重试") }
            }
            is ReaderLoad.Ready -> {
                val s = r.value
                Text(
                    if (s.facts.isEmpty() && s.questions.isEmpty()) "本页没有登记的考点或题。"
                    else "本页考点 ${s.facts.size} 个 · 相关题均答对 ${s.factsMastered} · 题 ${s.questions.size} 道（答对 ${s.questionsMastered}）",
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("private-study-summary"))
                if (s.unlocated > 0) Text("另有 ${s.unlocated} 条考点或题缺少可定位的出处，未列入任何页。", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("private-study-unlocated"))
                if (s.facts.isNotEmpty() || s.questions.isNotEmpty()) {
                    TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp).testTag("private-study-toggle")) { Text(if (open) "收起" else "看本页考点和题") }
                    if (practice != null) for ((week, qs) in s.questions.groupBy { it.week }.toSortedMap())
                        OutlinedButton(onClick = { practicing = week to qs.map { it.id }.toSet() }, modifier = Modifier.heightIn(min = 48.dp).testTag("private-study-practice-$week")) { Text("去做这几题（第 $week 周 ${qs.size} 题）") }
                    if (open) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (f in s.facts) {
                            Text("• ${f.text}" + if (f.mastered) "（相关题均答对）" else "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("private-study-fact-${f.id}"))
                            AnchorEntry("fact-${f.id}", f.anchor, onShowAnchor)
                        }
                        for (q in s.questions) {
                            Text("第 ${q.week} 周 · 题：${q.stem}（${statusLabel(q.status)}）", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("private-study-question-${q.id}"))
                            AnchorEntry("question-${q.id}", q.anchor, onShowAnchor)
                        }
                    }
                }
            }
        }
    }
}

/** An item registers either an exact phrase on this page or only the page; the second case says so instead of pointing somewhere. */
@Composable
private fun AnchorEntry(key: String, anchor: String, onShow: ((String) -> Unit)?) {
    if (anchor.isBlank()) Text("只登记到页，没有登记原文位置。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("private-study-noanchor-$key"))
    else if (onShow != null) TextButton(onClick = { onShow(anchor) }, modifier = Modifier.heightIn(min = 48.dp).testTag("private-study-anchor-$key")) { Text("在页上标出原文位置") }
}
