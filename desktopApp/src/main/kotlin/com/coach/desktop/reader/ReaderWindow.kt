package com.coach.desktop.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed as ptrCtrl
import androidx.compose.ui.input.pointer.isMetaPressed as ptrMeta
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ReaderWindow(session: ReaderSession, modifier: Modifier = Modifier, onPractice: ((StudyQuestion) -> Unit)? = null) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().background(Paper)) {
        val widthDp = maxWidth.value.toInt()
        LaunchedEffect(widthDp) { session.resize(widthDp) }
        val remote = session.remote
        if (remote != null) {
            LaunchedEffect(remote) { remote.loadCatalog() }
            LaunchedEffect(session.noteSaveTick) { if (session.noteSaveTick > 0) session.commitNote() }
            // 切页/切书：只留最后一次导航；重开同书同页依 navGeneration 刷新；页成功后再保存进度，保存失败不影响阅读。
            LaunchedEffect(remote.current?.id, session.state.position.page, session.navGeneration) {
                if (remote.current != null) {
                    val pdf = PdfPages.toPdf(session.state.position.page)
                    remote.show(pdf)
                    if (remote.page is PageState.Ready) remote.saveProgressAuto(pdf)
                }
            }
        }
        val state = session.state
        val shown = session.shownPanes()
        SideEffect { session.renderedPanes = shown }
        val requesters = remember { ReaderPane.entries.associateWith { FocusRequester() } }
        LaunchedEffect(state.focus, shown) { runCatching { requesters.getValue(state.focus).requestFocus() } }
        // 批注编辑区消失（保存后按 Esc / 放弃）时，焦点回到所在栏，键盘不会因为输入框被移除而失效。
        LaunchedEffect(session.noting) { if (!session.noting) runCatching { requesters.getValue(session.state.focus).requestFocus() } }

        Column(
            Modifier.fillMaxSize().testTag("reader-root").onPreviewKeyEvent { e ->
                val (input, digit) = e.toReaderInput() ?: return@onPreviewKeyEvent false
                session.onKey(input, digit)
            },
        ) {
            ReaderBar(session, shown)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (pane in shown) {
                    val weight = when (pane) { ReaderPane.Shelf -> 0.22f; ReaderPane.Body -> 0.5f; ReaderPane.Related -> 0.28f }
                    val focused = state.focus == pane
                    Box(
                        Modifier.weight(if (shown.size == 1) 1f else weight).fillMaxHeight().testTag("pane-${pane.name}")
                            .border(BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Accent else Color(0xFFD8D4CA)))
                            .focusRequester(requesters.getValue(pane))
                            .onFocusChanged { if (it.isFocused) session.focusPane(pane) }
                            .clickable { requesters.getValue(pane).requestFocus() }
                            .then(
                                if (pane == ReaderPane.Body) Modifier.onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { ev ->
                                    val c = ev.changes.first()
                                    val ctrl = ev.keyboardModifiers.ptrCtrl || ev.keyboardModifiers.ptrMeta
                                    // 页内还能滚（或正在写批注）就交给下面的滚动区；只有到页边界才翻页。Ctrl+滚轮始终是缩放。
                                    if (!ctrl && session.bodyCanScroll(c.scrollDelta.y)) return@onPointerEvent
                                    session.onWheel(c.scrollDelta.y, ctrl)
                                    c.consume()
                                } else Modifier,
                            )
                            .padding(12.dp),
                    ) {
                        when (pane) {
                            ReaderPane.Shelf -> ShelfPane(session)
                            ReaderPane.Body -> {
                                BodyPane(session)
                                session.bodyScroll?.let { sc ->
                                    androidx.compose.foundation.VerticalScrollbar(
                                        androidx.compose.foundation.rememberScrollbarAdapter(sc),
                                        Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight().testTag("body-scrollbar"),
                                    )
                                }
                            }
                            ReaderPane.Related -> RelatedPane(session, onPractice)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderBar(session: ReaderSession, shown: List<ReaderPane>) {
    val state = session.state
    Row(Modifier.fillMaxWidth().background(Color(0xFFEFEBE0)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        // 窄窗口下被收拢的栏用文字按钮切换；宽窗口下按钮只表示当前焦点所在栏。
        for (pane in ReaderPane.entries) {
            val label = when (pane) { ReaderPane.Shelf -> "书架"; ReaderPane.Body -> "正文"; ReaderPane.Related -> "题目" }
            Text(
                label,
                Modifier.testTag("bar-${pane.name}").clickable { session.focusPane(pane) }.padding(end = 16.dp),
                color = if (state.focus == pane) Accent else Ink,
                fontWeight = if (state.focus == pane) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
            )
        }
        val remotePage = (session.remote?.page as? PageState.Ready)?.view
        val pos = when {
            session.remote != null && state.pageCount <= 0 -> "未打开文档"
            session.remote != null -> "PDF第${PdfPages.toPdf(state.position.page)}页 / 共${state.pageCount}页" + (remotePage?.printedPage?.let { "（印刷第${it}页）" } ?: "")
            state.pageCount > 0 -> "第${state.position.page + 1}页 / 共${state.pageCount}页（样张页）"
            else -> "页数未知"
        }
        Text(pos, Modifier.testTag("bar-position"), fontSize = 12.sp, color = Color(0xFF666666))
        if (state.pageCount > 0) {
            val curPage = PdfPages.toPdf(state.position.page)
            Text("$curPage / ${state.pageCount}", Modifier.testTag("reader-page-number").padding(start = 8.dp), fontSize = 12.sp, color = Color(0xFF666666))
            val canPrev = state.position.page > 0
            val canNext = state.position.page < state.pageCount - 1
            Text(
                "上一页",
                Modifier.testTag("bar-prev-page").testTag("previous-page").clickable(enabled = canPrev) { session.prevPage() }.padding(start = 12.dp),
                color = if (canPrev) Accent else Color(0xFF999999),
                fontSize = 12.sp,
            )
            Text(
                "下一页",
                Modifier.testTag("bar-next-page").testTag("next-page").clickable(enabled = canNext) { session.nextPage() }.padding(start = 8.dp),
                color = if (canNext) Accent else Color(0xFF999999),
                fontSize = 12.sp,
            )
        }
        session.onBack?.let { back ->
            Text(
                session.backLabel ?: "返回",
                Modifier.testTag("reader-back").clickable { back() }.padding(start = 16.dp),
                color = Accent,
                fontSize = 14.sp,
            )
        }
        if (state.provenanceStack.isNotEmpty()) {
            Text("  返回题目", Modifier.testTag("bar-return").clickable { session.returnFromProvenance() }, color = Accent, fontSize = 14.sp)
        }
    }
}
