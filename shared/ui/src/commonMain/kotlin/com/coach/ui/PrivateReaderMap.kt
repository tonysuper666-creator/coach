package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.coach.client.ReaderHttp
import kotlinx.serialization.json.*

internal data class MapFact(val id: String, val text: String, val pdfPage: Int, val mastered: Boolean)
internal data class MapQuestion(val id: String, val week: Int, val stem: String, val status: String, val pdfPage: Int)
internal data class MapNode(val title: String, val pdfPage: Int, val endPage: Int, val facts: List<MapFact>, val questions: List<MapQuestion>, val sections: List<MapNode>)
internal data class ChapterMap(val hasOutline: Boolean, val chapters: List<MapNode>, val unlocated: Int, val ambiguous: Int, val outside: Int)

private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.i(key: String) = (this[key] as? JsonPrimitive)?.intOrNull

/** A malformed tree is a failure. Every node must carry a page; nothing is defaulted to page 1. */
internal fun chapterMap(body: JsonObject?, pages: Int): ChapterMap? {
    body ?: return null
    fun node(o: JsonObject): MapNode? {
        val page = o.i("pdfPage")?.takeIf { it in 1..pages } ?: return null
        val end = o.i("endPage")?.takeIf { it in page..pages } ?: return null
        val facts = (o["facts"] as? JsonArray)?.map { f ->
            val x = f as? JsonObject ?: return null
            MapFact(x.s("id")?.takeIf { it.isNotBlank() } ?: return null, x.s("text").orEmpty(), x.i("pdfPage")?.takeIf { it in 1..pages } ?: return null,
                (x["mastered"] as? JsonPrimitive)?.booleanOrNull == true)
        } ?: return null
        val questions = (o["questions"] as? JsonArray)?.map { q ->
            val x = q as? JsonObject ?: return null
            MapQuestion(x.s("id")?.takeIf { it.isNotBlank() } ?: return null, x.i("week") ?: return null, x.s("stem").orEmpty(), x.s("status").orEmpty(),
                x.i("pdfPage")?.takeIf { it in 1..pages } ?: return null)
        } ?: return null
        val sections = (o["sections"] as? JsonArray)?.map { node(it as? JsonObject ?: return null) ?: return null } ?: return null
        return MapNode(o.s("title")?.takeIf { it.isNotBlank() } ?: return null, page, end, facts, questions, sections)
    }
    val chapters = (body["chapters"] as? JsonArray)?.map { node(it as? JsonObject ?: return null) ?: return null } ?: return null
    return ChapterMap((body["hasOutline"] as? JsonPrimitive)?.booleanOrNull ?: return null, chapters, body.i("unlocated") ?: 0, body.i("ambiguous") ?: 0, body.i("outsideOutline") ?: 0)
}

private sealed interface MapRow {
    data class Node(val node: MapNode, val depth: Int) : MapRow
    data class Fact(val fact: MapFact, val depth: Int) : MapRow
    data class Question(val question: MapQuestion, val depth: Int) : MapRow
}

private fun rows(nodes: List<MapNode>, depth: Int, out: MutableList<MapRow> = mutableListOf()): List<MapRow> {
    for (n in nodes) {
        out += MapRow.Node(n, depth)
        n.facts.forEach { out += MapRow.Fact(it, depth + 1) }
        n.questions.forEach { out += MapRow.Question(it, depth + 1) }
        rows(n.sections, depth + 1, out)
    }
    return out
}

/**
 * 章 → 节 → 考点 → 题. Chapter, section and point rows jump the reader to their registered page; a question row opens the existing
 * practice for that one question. The map only describes what the catalog and sources register; it never fills gaps.
 */
@Composable
internal fun ChapterMapDialog(reader: ReaderHttp, documentId: String, pages: Int, onJump: (Int) -> Unit, onClose: () -> Unit,
                              practice: (@Composable (week: Int, ids: Set<String>, close: () -> Unit) -> Unit)? = null) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().testTag("private-map")) {
            var retry by remember(documentId) { mutableIntStateOf(0) }
            val result by produceState<ReaderLoad<ChapterMap>>(ReaderLoad.Loading, reader, documentId, retry) {
                value = ReaderLoad.Loading
                val reply = reader.map(documentId)
                value = (if (reply.ok) chapterMap(reply.body, pages) else null)?.let { ReaderLoad.Ready(it) } ?: ReaderLoad.Failed
            }
            var practicing by remember(documentId) { mutableStateOf<Pair<Int, Set<String>>?>(null) }
            practicing?.let { (week, ids) ->
                Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
                    Surface(Modifier.fillMaxSize().testTag("private-map-practice")) { practice?.invoke(week, ids) { practicing = null; retry++ } }
                }
            }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("private-map-close")) { Text("← 回到阅读") }
                    Text("章节脉络", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                HorizontalDivider()
                when (val r = result) {
                    ReaderLoad.Loading -> CenterMessage("正在读取章节脉络…")
                    ReaderLoad.Failed -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("章节脉络暂时读不到，请检查 Mac 连接。", modifier = Modifier.testTag("private-map-failed"))
                        OutlinedButton(onClick = { retry++ }, modifier = Modifier.testTag("private-map-retry")) { Text("重试") }
                    }
                    is ReaderLoad.Ready -> {
                        val map = r.value
                        if (!map.hasOutline || map.chapters.isEmpty()) Box(Modifier.fillMaxSize().testTag("private-map-empty")) { CenterMessage("这份资料没有登记章节结构，不能生成脉络图。") }
                        else {
                            val flat = remember(map) { rows(map.chapters, 0) }
                            if (map.unlocated + map.ambiguous + map.outside > 0) Text(
                                "有 ${map.unlocated + map.ambiguous + map.outside} 条考点或题不能定位（无可用出处 ${map.unlocated}、出处页码不唯一 ${map.ambiguous}、不在已登记章节内 ${map.outside}），未列入图中。",
                                Modifier.padding(12.dp).testTag("private-map-unplaced"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                                items(flat.size) { index ->
                                    when (val row = flat[index]) {
                                        is MapRow.Node -> TextButton(onClick = { onJump(row.node.pdfPage) }, modifier = Modifier.padding(start = (row.depth * 16).dp).heightIn(min = 48.dp).testTag("private-map-node-${row.node.pdfPage}-${row.node.title}")) {
                                            Text("${row.node.title}  ·  PDF ${row.node.pdfPage}–${row.node.endPage}  ·  考点 ${row.node.facts.size}（已掌握 ${row.node.facts.count { it.mastered }}）题 ${row.node.questions.size}", style = MaterialTheme.typography.titleSmall)
                                        }
                                        is MapRow.Fact -> TextButton(onClick = { onJump(row.fact.pdfPage) }, modifier = Modifier.padding(start = (row.depth * 16).dp).heightIn(min = 48.dp).testTag("private-map-fact-${row.fact.id}")) {
                                            Text("• ${row.fact.text}（PDF ${row.fact.pdfPage}）" + if (row.fact.mastered) "（已掌握）" else "", style = MaterialTheme.typography.bodyMedium)
                                        }
                                        is MapRow.Question -> TextButton(onClick = { practicing = row.question.week to setOf(row.question.id) }, enabled = practice != null,
                                            modifier = Modifier.padding(start = (row.depth * 16).dp).heightIn(min = 48.dp).testTag("private-map-question-${row.question.id}")) {
                                            Text("题 W${row.question.week} ${row.question.id}：${row.question.stem}", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
