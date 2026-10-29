package com.coach.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.coach.ui.markdown.*
import com.coach.ui.math.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@Composable
fun RenderingReplayScreen(state: ReplayState, corpus: ReplayCorpus?, desktop: Boolean, folding: String, save: () -> Unit, report: (String) -> Unit, chinesePage: Int? = null, autoplay: Int = 0, baselineSheet: Boolean = false, tickMs: Long = 50, streamOnly: Int = -1, chunk: Int = 30, runId: Long = 0L) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            if (chinesePage != null && corpus != null) { ChineseSheet(corpus.chinese.drop(chinesePage * 2).take(2)); return@Surface }
            if (baselineSheet && corpus != null) { BaselineSheet(corpus.assets); return@Surface }
            LaunchedEffect(Unit) { report("READY messages=${corpus?.messages?.size ?: 0}") }
            val list = rememberLazyListState(state.scrollIndex, state.scrollOffset)
            // Autoplay always starts from the first answer, before any saved cursor can be read.
            // autoplay: 1 = all answers pinned, 2 = all answers with the reader scrolled away, 3 = one answer (streamOnly), scrolled away, at tickMs.
            // The task's launch Intent survives a process kill, so the reset applies once per runId, not on every restore.
            remember(autoplay) { if (autoplay != 0 && corpus != null && (runId == 0L || state.autoplayRun != runId)) { state.autoplayRun = runId; state.message = maxOf(streamOnly, 0); state.cursor = 0; state.scrollIndex = 0; state.scrollOffset = 0; state.anchorKey = -1; state.anchorFraction = 0f; state.pinned = autoplay == 1; state.running = true } }
            val away = autoplay == 2 || autoplay == 3
            val message = corpus?.messages?.getOrNull(state.message).orEmpty()
            val engine = remember(message) { StreamingMarkdown() }
            var blocks by remember(message) { mutableStateOf(engine.append(message.take(state.cursor), state.cursor >= message.length)) }
            var pinned by remember { mutableStateOf(state.pinned) }
            LaunchedEffect(pinned) { state.pinned = pinned }
            val anchorLock = remember { booleanArrayOf(false) }
            val failures = remember { mutableSetOf<String>() }
            var maxShift by remember { mutableStateOf(0f) }
            // Run-wide metrics for autoplay: sealed-block height change, first visible item shift while the reader is scrolled away.
            val run = remember { RunMetrics() }
            val heights = remember(message) { mutableMapOf<Int, Pair<Int, Int>>() }
            val density = LocalDensity.current
            LaunchedEffect(state.running, message) {
                var scrolledAway = false
                while (state.running && state.cursor < message.length) {
                    delay(tickMs)
                    val end = minOf(state.cursor + chunk, message.length)
                    if (autoplay == 3 && !scrolledAway && state.cursor > message.length * 2 / 5 && blocks.size > 2) {
                        // Read into the middle of the second block; only counts as "away" when content is still waiting below the viewport.
                        list.scrollToItem(1, 0)
                        val size = snapshotFlow { list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 1 }?.size }.filterNotNull().first()
                        list.scrollToItem(1, size * 2 / 5); androidx.compose.runtime.withFrameNanos { }
                        if (list.canScrollForward) { scrolledAway = true; report("SCROLLED_AWAY cursor=${state.cursor} blocks=${blocks.size} blockPx=$size") }
                    }
                    if (autoplay == 2 && !scrolledAway && state.cursor > message.length * 2 / 5 && blocks.size > 2) { list.scrollToItem(1, 0); scrolledAway = true; report("SCROLLED_AWAY cursor=${state.cursor} blocks=${blocks.size}") }
                    val beforeIndex = list.firstVisibleItemIndex; val beforeOffset = list.firstVisibleItemScrollOffset
                    // Content shorter than the viewport leaves nothing below: the list is at its end, which is following the tail, not reading away from it.
                    val awayBefore = list.canScrollForward
                    blocks = engine.append(message.substring(state.cursor, end), end == message.length)
                    state.cursor = end
                    if (pinned && blocks.isNotEmpty()) list.scrollToItem(blocks.lastIndex)
                    else if (scrolledAway && !awayBefore) { run.atEndSkipped++ }
                    else if (scrolledAway) {
                        androidx.compose.runtime.withFrameNanos { }
                        run.firstVisibleShift = maxOf(run.firstVisibleShift, if (list.firstVisibleItemIndex == beforeIndex) kotlin.math.abs(list.firstVisibleItemScrollOffset - beforeOffset).toFloat() else Float.MAX_VALUE)
                        run.firstVisibleSamples++
                        val shift = if (list.firstVisibleItemIndex == beforeIndex) kotlin.math.abs(list.firstVisibleItemScrollOffset - beforeOffset) else -1
                        if (shift != 0) report("SHIFT message=${state.message} cursor=${state.cursor} blocks=${blocks.size} before=($beforeIndex,$beforeOffset) after=(${list.firstVisibleItemIndex},${list.firstVisibleItemScrollOffset}) canScrollForward=${list.canScrollForward} viewportPx=${list.layoutInfo.viewportSize.height} contentPx=${list.layoutInfo.totalItemsCount}")
                    }
                }
                if (state.cursor >= message.length) state.running = false
                save()
                report("REPLAY message=${state.message} chars=${state.cursor} blocks=${blocks.size} sealedHeightShiftPx=$maxShift failures=${failures.size} parsedChars=${engine.parsedCharacters}")
                if (autoplay == 3 && state.cursor >= message.length) report("STREAM_DONE message=${state.message}")
                if (autoplay in 1..2 && corpus != null && state.cursor >= message.length && run.lastCounted != state.message) {
                    run.lastCounted = state.message
                    run.sealedShift = maxOf(run.sealedShift, maxShift); run.failures += failures.size; run.messages++
                    if (state.message + 1 < corpus.messages.size) {
                        state.message += 1; state.cursor = 0; state.scrollIndex = 0; state.scrollOffset = 0; pinned = autoplay == 1; state.running = true
                    } else report("SUMMARY mode=${if (autoplay == 2) "scrolled-away" else "pinned"} messages=${run.messages} sealedHeightShiftPx=${run.sealedShift} firstVisibleShiftPx=${run.firstVisibleShift} firstVisibleSamples=${run.firstVisibleSamples} atEndSkipped=${run.atEndSkipped} failures=${run.failures} ${run.frameSummary()}")
                }
            }
            // Frame pacing while a replay streams: with the loop below requesting a frame every vsync, a long layout/paint shows as a long gap.
            LaunchedEffect(autoplay) {
                if (autoplay in 1..2) { var last = 0L; while (true) { val t = withFrameNanos { it }; if (state.running && last != 0L) { val dt = (t - last) / 1e6; run.frameMs += dt; if (dt > 100) run.hitches += "m${state.message}@${state.cursor}=${dt.toInt()}ms" }; last = t } }
            }
            // Live measurement of what the reader is looking at (not the stored anchor): first visible block, how far into it, viewport width.
            LaunchedEffect(list) {
                while (true) {
                    delay(500)
                    val first = list.layoutInfo.visibleItemsInfo.firstOrNull()
                    if (first != null && !pinned && first.size > 0) report("ANCHOR key=${first.key} fraction=${list.firstVisibleItemScrollOffset.toFloat() / first.size} blockPx=${first.size} viewportWidthPx=${list.layoutInfo.viewportSize.width} cursor=${state.cursor}")
                }
            }
            LaunchedEffect(list) {
                snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.isScrollInProgress) }.distinctUntilChanged().collect { (i, offset, scrolling) ->
                    state.scrollIndex = i; state.scrollOffset = offset
                    if (scrolling) pinned = !list.canScrollForward
                    val item = list.layoutInfo.visibleItemsInfo.firstOrNull()
                    if (!anchorLock[0]) {
                        if (!pinned && item != null && item.size > 0) { state.anchorKey = item.key as Int; state.anchorFraction = offset.toFloat() / item.size }
                        else if (pinned) state.anchorKey = -1
                    }
                }
            }
            LaunchedEffect(state.input.text, state.selected, state.cursor, state.scrollIndex, state.scrollOffset, state.pinned, state.anchorKey, state.anchorFraction) { delay(300); save() }
            BoxWithConstraints {
                val windowWidth = maxWidth.value.toInt()
                val windowHeight = maxHeight.value.toInt()
                // The reader keeps the same content on screen when the window changes shape or the process is restored:
                // jump back to the anchor block at the same fraction of its (new) height instead of the old pixel offset.
                LaunchedEffect(windowWidth) {
                    val key = state.anchorKey; val fraction = state.anchorFraction
                    val index = blocks.indexOfFirst { it.key == key }
                    if (!pinned && key >= 0 && index >= 0) {
                        anchorLock[0] = true
                        try {
                            list.scrollToItem(index, 0)
                            val size = snapshotFlow { list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.size }.filterNotNull().first()
                            list.scrollToItem(index, (fraction * size).toInt().coerceIn(0, maxOf(size - 1, 0)))
                            androidx.compose.runtime.withFrameNanos { }
                        } finally { anchorLock[0] = false }
                    }
                }
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Coach · 渲染回放", style = MaterialTheme.typography.titleLarge)
                    Text("窗口 ${windowWidth} × ${windowHeight} dp · ${density.density} · $folding", style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = corpus != null, onClick = { state.running = !state.running; save() }) { Text(if (state.running) "暂停" else "回放") }
                        TextButton(enabled = corpus != null, onClick = { state.running = false; state.message = (state.message + 1) % corpus!!.messages.size; state.cursor = 0; state.scrollIndex = 0; state.scrollOffset = 0; pinned = true; save() }) { Text("下一条") }
                        TextButton(onClick = { pinned = true }) { Text("钉底") }
                        Text("${state.cursor}/${message.length}")
                    }
                    if (corpus == null) Text("请先加载本机公式与回答语料。")
                    SelectionContainer(Modifier.weight(1f)) {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(blocks, key = { it.key }) { block ->
                                MarkdownContent(block, corpus?.assets.orEmpty(), modifier = Modifier.fillMaxWidth().onSizeChanged {
                                    if (block.sealed) { val old = heights.put(block.key, it.width to it.height); if (old != null && old.first == it.width) maxShift = maxOf(maxShift, kotlin.math.abs(old.second - it.height).toFloat()) }
                                }, onFailure = { failures += it })
                            }
                        }
                    }
                    Row {
                        for (option in listOf("A", "B", "C")) FilterChip(state.selected == option, { state.selected = option; save() }, label = { Text(option) }, modifier = Modifier.padding(end = 8.dp))
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        for (symbol in listOf("−", "×", "÷", "^", "√", "π", "≤", "≥", "≈", "½", "_")) {
                            TextButton(onClick = {
                                val v = state.input; val start = v.selection.min; val end = v.selection.max
                                state.input = TextFieldValue(v.text.replaceRange(start, end, symbol), TextRange(start + symbol.length)); save()
                            }, contentPadding = PaddingValues(4.dp)) { Text(symbol) }
                        }
                    }
                    OutlinedTextField(state.input, { state.input = it }, label = { Text("草稿 · 0.25、-11/3、x^2、sqrt(x)") }, modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                        if (it.key == Key.Enter && shouldSendOnEnter(desktop, it.type == KeyEventType.KeyDown, it.isShiftPressed, state.input.composition != null)) {
                            if (state.input.text.isNotBlank()) { report("INPUT submitted chars=${state.input.text.length}"); state.input = TextFieldValue(); save() }; true
                        } else false
                    }, maxLines = 3)
                    Text("失败 ${failures.size} · 已封口块高度变化 ${maxShift}px", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private class RunMetrics {
    val frameMs = mutableListOf<Double>(); val hitches = mutableListOf<String>()
    fun frameSummary(): String {
        if (frameMs.isEmpty()) return "frames=0"
        val sorted = frameMs.sorted(); val p50 = sorted[sorted.size / 2]; val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)]
        val jank = frameMs.count { it > 25 }
        fun f(v: Double) = (kotlin.math.round(v * 10) / 10).toString()
        return "frames=${frameMs.size} frameP50Ms=${f(p50)} frameP95Ms=${f(p95)} frameMaxMs=${f(sorted.last())} frameOver25Ms=$jank frameOver25Pct=${f(jank * 100.0 / frameMs.size)} frameOver100Ms=${hitches.size} hitches=${hitches.take(6)}"
    }
    var sealedShift = 0f; var firstVisibleShift = 0f; var firstVisibleSamples = 0; var atEndSkipped = 0; var failures = 0; var messages = 0; var lastCounted = -1
}

/** Digits sit on the baseline in both the text font and the MathJax glyphs, so their ink bottoms expose inline baseline error. */
@Composable
private fun BaselineSheet(assets: Map<String, MathAsset>) {
    val blocks = remember { StreamingMarkdown().append("40 $40$ 12 $12$ 3 $3$ 40", true) }
    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White).padding(24.dp)) {
        blocks.forEach { MarkdownContent(it, assets, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun ChineseSheet(assets: List<MathAsset>) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White).padding(24.dp), verticalArrangement = Arrangement.spacedBy(48.dp)) {
        Text("中文公式 · Android", style = MaterialTheme.typography.titleLarge)
        assets.forEach { asset ->
            Text(asset.tex, style = MaterialTheme.typography.bodyMedium)
            val doc = remember(asset.sha) { MathSvgDocument.parse(asset.sha, asset.svg) }
            val em = with(density) { 24.dp.toPx() }
            Image(MathSvgPainter(doc, androidx.compose.ui.graphics.Color.Black, measurer, em), asset.tex, Modifier.size((doc.widthEm * 24).dp, ((doc.ascentEm + doc.descentEm) * 24).dp))
        }
    }
}
