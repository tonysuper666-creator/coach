package com.coach.ui.reader.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Isolated phase-one container. No fixture, transport, production navigation or
 * guessed printed page numbers. The owner supplies verified question links and
 * a native page renderer, then persists the hoisted session privately. */
@Composable
fun ReaderV2Screen(
    window: ReaderWindow,
    pages: List<ReaderPageLayout>,
    questions: List<ReaderQuestion>,
    session: ReaderSession,
    onSession: (ReaderSession) -> Unit,
    modifier: Modifier = Modifier,
    pageContent: @Composable (Int) -> Unit,
) {
    val panes = readerPanes(window, session.railVisible)
    val page = pages.firstOrNull { it.pageIndex0 == session.position.pageIndex0 }
    val source = session.source?.takeIf { it.sha256 == session.sha256 && it.documentId == session.documentId }
    val close = { onSession(session.closeDrawer()) }
    fun column(id: Int?) { onSession(session.copy(position = session.position.copy(columnId = id), overview = false)) }
    var chromeHeight by remember { mutableStateOf(0f) }
    val density = LocalDensity.current.density
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("reader-${panes.arrangement.name.lowercase()}")) {
        Row(Modifier.fillMaxWidth().onSizeChanged { chromeHeight = it.height / density }.horizontalScrollForReader(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onSession(session.copy(focus = ReaderFocus.READING)) }) { Text("读原文") }
            TextButton(onClick = { onSession(session.copy(focus = ReaderFocus.QUESTIONS, railVisible = true)) }) { Text("做题") }
            if (panes.arrangement == ReaderArrangement.EXPANDED) TextButton(onClick = { onSession(session.copy(railVisible = !session.railVisible)) }) {
                Text(if (session.railVisible) "收起题栏" else "显示题栏")
            }
        }
        // The host supplies fold bounds relative to this content area (below chrome).
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val bodyFold = window.fold?.let { f -> f.copy(bounds = f.bounds.copy(top = f.bounds.top - chromeHeight, bottom = f.bounds.bottom - chromeHeight)) }
            val bodyWindow = ReaderWindow(maxWidth.value, maxHeight.value, bodyFold)
            val bodyPanes = readerPanes(bodyWindow, session.railVisible)
            LaunchedEffect(bodyPanes.arrangement, session.drawer) {
                if (bodyPanes.arrangement != ReaderArrangement.COMPACT && session.drawer == ReaderDrawer.QUESTIONS)
                    onSession(session.copy(drawer = ReaderDrawer.NONE))
            }
            val inlineSource = bodyPanes.arrangement != ReaderArrangement.COMPACT && session.drawer == ReaderDrawer.SOURCE
            val displayPage = if (inlineSource) source?.let { target -> pages.firstOrNull { it.pageIndex0 == target.pageIndex0 } } else page
            var displayedColumn by remember(displayPage?.pageIndex0) { mutableStateOf<Int?>(null) }
            ReaderPaneLayout(bodyPanes, Modifier.fillMaxSize(), page = {
                if (bodyPanes.arrangement == ReaderArrangement.COMPACT && session.focus == ReaderFocus.QUESTIONS) {
                    ReaderQuestionPanel(questions, session, onSession)
                } else Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().horizontalScrollForReader()) {
                        if (inlineSource) TextButton(onClick = close, modifier = Modifier.testTag("reader-return")) { Text("返回原页") }
                        else if (page != null && page.columns.isNotEmpty()) {
                            val current = session.position.columnId ?: displayedColumn
                            val next = page.nextColumn(current)
                            TextButton(onClick = { column(next) }, enabled = next != current, modifier = Modifier.testTag("reader-next-column")) { Text("下一栏") }
                            TextButton(onClick = { onSession(session.copy(position = session.position.copy(columnId = null), overview = true)) }) { Text("整页") }
                        }
                        if (bodyPanes.arrangement == ReaderArrangement.COMPACT) TextButton(onClick = { onSession(session.copy(drawer = ReaderDrawer.QUESTIONS)) }, modifier = Modifier.testTag("reader-margin")) { Text("本页题目") }
                        if (!inlineSource && page != null) {
                            val ordered = pages.sortedBy { it.pageIndex0 }
                            val index = ordered.indexOf(page)
                            TextButton(onClick = { onSession(session.copy(position = ReaderPosition(ordered[index - 1].pageIndex0), overview = false)) }, enabled = index > 0) { Text("上一页") }
                            TextButton(onClick = { onSession(session.copy(position = ReaderPosition(ordered[index + 1].pageIndex0), overview = false)) }, enabled = index < ordered.lastIndex) { Text("下一页") }
                        }
                    }
                    if (displayPage == null) Text(if (inlineSource) (session.sourceFailure ?: ReaderSourceFailure.MISSING).message else "这一页暂时打不开", Modifier.padding(16.dp).testTag("reader-source-error"))
                    else ReaderV2Page(displayPage, session.position.columnId, bodyPanes.arrangement == ReaderArrangement.COMPACT,
                        if (inlineSource) source else null, session.flash, ::column, Modifier.weight(1f), session.overview,
                        onPlan = { if (!inlineSource) displayedColumn = it.columnId }, pageContent = pageContent)
                }
            }, questions = { ReaderQuestionPanel(questions, session, onSession) }, navigation = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text("页目录", style = MaterialTheme.typography.titleSmall)
                    for (p in pages.sortedBy { it.pageIndex0 }) TextButton(onClick = {
                        onSession(session.copy(position = ReaderPosition(p.pageIndex0), overview = false))
                    }) { Text("PDF 第${p.pageIndex0 + 1}页") }
                }
            })
            if (bodyPanes.arrangement == ReaderArrangement.COMPACT && session.drawer != ReaderDrawer.NONE) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.5f).testTag("reader-half-sheet"),
                tonalElevation = 4.dp, shadowElevation = 8.dp) {
                Column(Modifier.fillMaxSize()) {
                    var distance by remember { mutableStateOf(0f) }
                    Box(Modifier.fillMaxWidth().height(48.dp).testTag("reader-sheet-handle").pointerInput(session.drawer) {
                        // The handle is 48dp high. Leave room for touch slop before
                        // counting a deliberate downward dismissal gesture.
                        detectVerticalDragGestures(onDragStart = { distance = 0f }, onDragEnd = { if (distance > 24.dp.toPx()) close() }) { change, amount -> distance += amount; change.consume() }
                    }, contentAlignment = Alignment.Center) {
                        TextButton(onClick = close, modifier = Modifier.testTag("reader-return")) { Text(if (session.focus == ReaderFocus.QUESTIONS) "返回题目" else "返回原页") }
                    }
                    if (session.drawer == ReaderDrawer.QUESTIONS) ReaderQuestionPanel(questions.filter {
                        (it.source as? ReaderSourceResolution.Found)?.target?.let { target -> target.documentId == session.documentId && target.sha256 == session.sha256 && target.pageIndex0 == session.position.pageIndex0 } == true
                    }, session, onSession)
                    else {
                        val sourcePage = source?.let { t -> pages.firstOrNull { it.pageIndex0 == t.pageIndex0 } }
                        if (sourcePage != null) ReaderV2Page(sourcePage, null, true, source, session.flash, {}, Modifier.weight(1f), pageContent = pageContent)
                        else Text((session.sourceFailure ?: ReaderSourceFailure.MISSING).message, Modifier.padding(16.dp).testTag("reader-source-error"))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderQuestionPanel(questions: List<ReaderQuestion>, session: ReaderSession, onSession: (ReaderSession) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).testTag("reader-questions")) {
        if (questions.isEmpty()) Text("这一页还没有核实过的关联题")
        for (q in questions) {
            Text(q.text, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(session.answers[q.id] ?: "", { onSession(session.answer(q.id, it)) },
                label = { Text("你的答案") }, modifier = Modifier.fillMaxWidth().testTag("reader-answer-${q.id}"))
            TextButton(onClick = { onSession(session.showSource(q)) }, modifier = Modifier.testTag("reader-source-${q.id}")) { Text("看出处") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Modifier.horizontalScrollForReader(): Modifier = horizontalScroll(rememberScrollState())

@Composable
private fun ReaderPaneLayout(panes: ReaderPanes, modifier: Modifier, page: @Composable () -> Unit,
    questions: @Composable () -> Unit, navigation: @Composable () -> Unit) {
    Layout(content = {
        Box(Modifier.clipToBounds().testTag("reader-page-pane")) { page() }
        if (panes.questions != null) Box(Modifier.clipToBounds().background(MaterialTheme.colorScheme.surfaceVariant).testTag("reader-question-pane")) { questions() }
        if (panes.navigation != null) Box(Modifier.clipToBounds().testTag("reader-navigation-pane")) { navigation() }
    }, modifier = modifier) { children, limits ->
        fun measure(p: ReaderPane, index: Int) = children[index].measure(Constraints.fixed(
            (p.width * density).roundToInt().coerceAtLeast(1), (p.height * density).roundToInt().coerceAtLeast(1)))
        val body = measure(panes.page, 0)
        val rail = panes.questions?.let { measure(it, 1) }
        val nav = panes.navigation?.let { measure(it, if (panes.questions != null) 2 else 1) }
        layout(limits.maxWidth, limits.maxHeight) {
            body.place((panes.page.left * density).roundToInt(), (panes.page.top * density).roundToInt())
            panes.questions?.let { rail?.place((it.left * density).roundToInt(), (it.top * density).roundToInt()) }
            panes.navigation?.let { nav?.place((it.left * density).roundToInt(), (it.top * density).roundToInt()) }
        }
    }
}
