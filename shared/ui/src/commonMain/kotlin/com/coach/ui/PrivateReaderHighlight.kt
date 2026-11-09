package com.coach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import coach.design.tokens.CoachV7
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.coach.client.ReaderHttp
import kotlinx.serialization.json.*

/** One word in PDF points; origin at the top-left of the page as displayed (rotation already applied by the server's Poppler). */
internal data class WordBox(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val text: String)
internal data class PageBoxes(val width: Double, val height: Double, val words: List<WordBox>)

/** A malformed reply, another page, or another document version is rejected: a highlight is only drawn from a verified frame. */
internal fun pageBoxes(body: JsonObject?, version: String, page: Int): PageBoxes? {
    body ?: return null
    fun d(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
    if ((body["version"] as? JsonPrimitive)?.contentOrNull != version || (body["pdfPage"] as? JsonPrimitive)?.intOrNull != page) return null
    val width = d(body, "width")?.takeIf { it > 0 } ?: return null
    val height = d(body, "height")?.takeIf { it > 0 } ?: return null
    val words = (body["words"] as? JsonArray)?.map { w ->
        val o = w as? JsonObject ?: return null
        val b = WordBox(d(o, "x0") ?: return null, d(o, "y0") ?: return null, d(o, "x1") ?: return null, d(o, "y1") ?: return null,
            (o["text"] as? JsonPrimitive)?.contentOrNull ?: return null)
        if (b.x0 < 0 || b.y0 < 0 || b.x1 > width + 0.5 || b.y1 > height + 0.5 || b.x1 <= b.x0 || b.y1 <= b.y0) return null
        b
    } ?: return null
    return PageBoxes(width, height, words)
}

internal sealed interface Located {
    data class Found(val rects: List<WordBox>) : Located
    data object NoTextLayer : Located
    data object NotFound : Located
    data object Ambiguous : Located
}

/** The quote's whitespace-separated tokens must appear as exactly one consecutive run of words. Nothing is fuzzily guessed. */
internal fun locateQuote(boxes: PageBoxes, quote: String): Located {
    if (boxes.words.isEmpty()) return Located.NoTextLayer
    val tokens = quote.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty() || tokens.size > boxes.words.size) return Located.NotFound
    val starts = (0..boxes.words.size - tokens.size).filter { i -> tokens.indices.all { boxes.words[i + it].text == tokens[it] } }
    if (starts.isEmpty()) return Located.NotFound
    if (starts.size > 1) return Located.Ambiguous
    val run = boxes.words.subList(starts.single(), starts.single() + tokens.size)
    // One rectangle per visual line: words that overlap vertically by more than half the smaller height share a line.
    val lines = mutableListOf<WordBox>()
    for (w in run) {
        val last = lines.lastOrNull()
        val overlap = last?.let { minOf(it.y1, w.y1) - maxOf(it.y0, w.y0) } ?: 0.0
        if (last != null && overlap > 0.5 * minOf(last.y1 - last.y0, w.y1 - w.y0))
            lines[lines.lastIndex] = WordBox(minOf(last.x0, w.x0), minOf(last.y0, w.y0), maxOf(last.x1, w.x1), maxOf(last.y1, w.y1), "")
        else lines += w.copy(text = "")
    }
    return Located.Found(lines)
}

internal data class HighlightFocus(val page: Int, val quote: String)

internal sealed interface HighlightState {
    data object Loading : HighlightState
    data class Shown(val boxes: PageBoxes, val rects: List<WordBox>) : HighlightState
    data class Unplaced(val message: String, val retry: Boolean = false) : HighlightState
}

@Composable
internal fun rememberHighlight(reader: ReaderHttp, doc: PrivateDocument, focus: HighlightFocus?, retry: Int): State<HighlightState?> =
    produceState<HighlightState?>(null, reader, doc, focus, retry) {
        if (focus == null) { value = null; return@produceState }
        value = HighlightState.Loading
        val reply = reader.boxes(doc.id, focus.page, doc.version)
        value = when {
            reply.status == 409 -> HighlightState.Unplaced("资料版本已变化，不在页图上标注位置。")
            !reply.ok -> HighlightState.Unplaced("暂时读不到这一页的文字坐标。", retry = true)
            else -> {
                val boxes = pageBoxes(reply.body, doc.version, focus.page)
                if (boxes == null) HighlightState.Unplaced("文字坐标不可用，不标注。", retry = true)
                else when (val found = locateQuote(boxes, focus.quote)) {
                    is Located.Found -> HighlightState.Shown(boxes, found.rects)
                    Located.NoTextLayer -> HighlightState.Unplaced("这一页没有文字层（可能是扫描页），无法在页图上标出。")
                    Located.NotFound -> HighlightState.Unplaced("在页上找不到这句话，不标注。")
                    Located.Ambiguous -> HighlightState.Unplaced("这句话在页上出现多处，无法唯一确定，不标注。")
                }
            }
        }
    }

@Composable
internal fun HighlightStatus(state: HighlightState?, onRetry: () -> Unit, onClear: () -> Unit) {
    when (state) {
        null -> {}
        HighlightState.Loading -> Text("正在定位原文位置…", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
        is HighlightState.Shown -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("已在页上标出原文位置。", Modifier.weight(1f).testTag("private-highlight-status"), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onClear, modifier = Modifier.testTag("private-highlight-clear")) { Text("取消标注") }
        }
        is HighlightState.Unplaced -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.message, Modifier.weight(1f).testTag("private-highlight-status"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            if (state.retry) TextButton(onClick = onRetry, modifier = Modifier.testTag("private-highlight-retry")) { Text("重试") }
            TextButton(onClick = onClear, modifier = Modifier.testTag("private-highlight-clear")) { Text("关闭") }
        }
    }
}

/** Highlights scale with the rendered width (dp per PDF point); a page image whose aspect disagrees with the boxes is not marked. */
@Composable
internal fun HighlightOverlay(shown: HighlightState.Shown, imageWidthPx: Int, imageHeightPx: Int, modifier: Modifier) {
    val aspectPage = shown.boxes.width / shown.boxes.height
    val aspectImage = imageWidthPx.toDouble() / imageHeightPx
    if (kotlin.math.abs(aspectPage - aspectImage) > 0.02 * aspectPage) return
    val ink = if (com.coach.ui.theme.CoachTheme.colors.dark) CoachV7.Dark.hl else CoachV7.Light.hl
    BoxWithConstraints(modifier) {
        val scale = maxWidth.value / shown.boxes.width.toFloat()
        shown.rects.forEachIndexed { i, r ->
            Box(Modifier.offset((r.x0 * scale).dp, (r.y0 * scale).dp).size(((r.x1 - r.x0) * scale).dp, ((r.y1 - r.y0) * scale).dp)
                .background(ink).semantics { testTag = "private-highlight-$i" })
        }
    }
}
