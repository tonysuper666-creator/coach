package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Lives inside the question dialog: closing a card leaves its answer and reader state intact.
 * New answers carry an authorized in-memory snapshot; old page-only answers retain the guarded GET. */
@Composable
internal fun ReaderCitationCards(sources: JsonArray, loadPage: suspend (String, Int) -> String?,
    onCardDrawn: (mode: String, elapsedMs: Long) -> Unit = { mode, ms ->
        // Deliberately omit source IDs, versions, selections and all course text.
        println("COACH reader.citation status=$mode elapsed_ms=$ms")
    }) {
    var selected by remember(sources) { mutableStateOf<JsonObject?>(null) }
    var clicked by remember { mutableStateOf<TimeMark?>(null) }
    for (source in sources.mapNotNull { it as? JsonObject }) {
        TextButton(onClick = { clicked = TimeSource.Monotonic.markNow(); selected = source },
            modifier = Modifier.testTag("reader-source-${source.str("anchor")}")) {
            Text("PDF 第 ${source.num("pdfPage")} 页")
        }
    }
    selected?.let { source ->
        val inline = source.str("text").takeIf { it.isNotBlank() }
        var loaded by remember(source) { mutableStateOf<String?>(inline) }
        var loading by remember(source) { mutableStateOf(inline == null) }
        LaunchedEffect(source) {
            if (inline == null) {
                try { loaded = loadPage(source.str("docId"), source.num("pdfPage").toInt())?.takeIf { it.isNotBlank() } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { loaded = null }
                finally { loading = false }
            }
        }
        val report = remember(source, clicked) { booleanArrayOf(false) }
        val mode = if (source.str("mode") == "block" && inline != null) "block" else "page"
        AlertDialog(onDismissRequest = { selected = null }, modifier = Modifier.widthIn(max = 640.dp),
            title = { Text("原文 · PDF 第 ${source.num("pdfPage")} 页") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (mode == "block") "对应原文段落" else "这一段的位置无法确认，显示已核对的整页。")
                    when {
                        loaded != null -> Text(loaded!!, Modifier.testTag("reader-source-content").drawWithContent {
                            drawContent()
                            // Measure the real click through the first content draw, never an empty dialog or HTTP duration.
                            if (!report[0]) {
                                report[0] = true
                                clicked?.let { onCardDrawn(mode, it.elapsedNow().inWholeMilliseconds) }
                            }
                        })
                        loading -> Text("正在读取原文页…")
                        else -> Text("暂时取不到原文页。可以返回追问或继续阅读。", Modifier.testTag("reader-source-missing").drawWithContent {
                            drawContent()
                            if (!report[0]) {
                                report[0] = true
                                // Missing content is a failure receipt, never a successful content timing.
                                clicked?.let { onCardDrawn("missing", it.elapsedNow().inWholeMilliseconds) }
                            }
                        })
                    }
                }
            }, confirmButton = { TextButton(onClick = { selected = null }) { Text("返回追问") } })
    }
}
