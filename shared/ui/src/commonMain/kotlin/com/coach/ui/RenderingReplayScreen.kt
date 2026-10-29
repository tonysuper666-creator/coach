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

@Composable
fun RenderingReplayScreen(state: ReplayState, corpus: ReplayCorpus?, desktop: Boolean, folding: String, save: () -> Unit, report: (String) -> Unit, chinesePage: Int? = null) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            if (chinesePage != null && corpus != null) { ChineseSheet(corpus.chinese.drop(chinesePage * 2).take(2)); return@Surface }
            LaunchedEffect(Unit) { report("READY messages=${corpus?.messages?.size ?: 0}") }
            val list = rememberLazyListState(state.scrollIndex, state.scrollOffset)
            val message = corpus?.messages?.getOrNull(state.message).orEmpty()
            val engine = remember(message) { StreamingMarkdown() }
            var blocks by remember(message) { mutableStateOf(engine.append(message.take(state.cursor), state.cursor >= message.length)) }
            var pinned by remember { mutableStateOf(true) }
            val failures = remember { mutableSetOf<String>() }
            var maxShift by remember { mutableStateOf(0f) }
            val heights = remember(message) { mutableMapOf<Int, Pair<Int, Int>>() }
            val density = LocalDensity.current
            LaunchedEffect(state.running, message) {
                while (state.running && state.cursor < message.length) {
                    delay(50)
                    val end = minOf(state.cursor + 30, message.length)
                    blocks = engine.append(message.substring(state.cursor, end), end == message.length)
                    state.cursor = end
                    if (pinned && blocks.isNotEmpty()) list.scrollToItem(blocks.lastIndex)
                }
                if (state.cursor >= message.length) state.running = false
                save()
                report("REPLAY message=${state.message} chars=${state.cursor} blocks=${blocks.size} sealedHeightShiftPx=$maxShift failures=${failures.size} parsedChars=${engine.parsedCharacters}")
            }
            LaunchedEffect(list) {
                snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.isScrollInProgress) }.distinctUntilChanged().collect { (i, offset, scrolling) ->
                    state.scrollIndex = i; state.scrollOffset = offset
                    if (scrolling) pinned = !list.canScrollForward
                }
            }
            LaunchedEffect(state.input.text, state.selected, state.cursor, state.scrollIndex, state.scrollOffset) { delay(300); save() }
            BoxWithConstraints {
                val windowWidth = maxWidth.value.toInt()
                val windowHeight = maxHeight.value.toInt()
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
