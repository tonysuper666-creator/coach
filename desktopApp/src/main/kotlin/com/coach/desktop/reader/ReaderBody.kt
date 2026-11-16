package com.coach.desktop.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun BodyPane(session: ReaderSession) {
    val state = session.state
    if (session.remote != null) { RemoteBody(session, session.remote); return }
    val page = session.fixture.pages.getOrNull(state.position.page)
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (page == null) {
            Text("页数未知，无法显示正文", fontSize = 14.sp)
            return@Column
        }
        val size = (16 * state.zoomPercent / 100f).sp
        for (s in page) {
            val flashing = state.flashAnchor == s.anchorId
            Text(
                s.text,
                Modifier.testTag("sentence-${s.anchorId}").fillMaxWidth().then(if (flashing) Modifier.background(Flash) else Modifier).padding(vertical = 4.dp),
                fontSize = size,
                color = Ink,
            )
        }
        session.message?.let { Text(it, Modifier.testTag("message").padding(top = 8.dp), color = Color(0xFFA33B2D), fontSize = 13.sp) }
    }
}

@Composable
private fun RemoteBody(session: ReaderSession, remote: ReaderRemote) {
    val state = session.state
    // 滚轮先在页内滚动，到边界才翻页（由外层 Initial 阶段判断）；换页/换书时滚动位置归零，往回翻则停在末尾。
    val scroll = remember(remote.current?.id, state.position.page) { androidx.compose.foundation.ScrollState(0) }
    SideEffect { session.bodyScroll = scroll }
    LaunchedEffect(scroll.maxValue, remote.page, session.scrollToEndOnArrive) {
        if (session.scrollToEndOnArrive && remote.page is PageState.Ready) { scroll.scrollTo(scroll.maxValue); session.scrollToEndOnArrive = false }
    }
    Column(Modifier.verticalScroll(scroll)) {
        when (val p = remote.page) {
            PageState.Idle -> Text(if (remote.current == null) "请先在书架里选一本" else "", Modifier.testTag("remote-idle"), fontSize = 14.sp)
            is PageState.Loading -> Text("正在加载 PDF 第${p.pdfPage}页", Modifier.testTag("remote-loading"), fontSize = 13.sp, color = Color(0xFF666666))
            is PageState.Failed -> Column {
                Text(p.failure.text, Modifier.testTag("remote-failure"), color = Color(0xFFA33B2D), fontSize = 14.sp)
                val scope = rememberCoroutineScope()
                Text("重试", Modifier.testTag("remote-retry").clickable { scope.launch { session.retryPage() } }.padding(top = 4.dp), color = Accent, fontSize = 14.sp)
            }
            is PageState.Ready -> RemotePage(session, remote, p.view)
        }
        when (val s = remote.save) {
            is SaveState.Conflict -> Column(Modifier.testTag("save-conflict").padding(top = 8.dp)) {
                Text(
                    if (s.versionChanged) "资料版本变了，本机位置未保存，已保留在本机" else "另一台设备已保存了新的位置，本机位置没有覆盖",
                    color = Color(0xFFA33B2D), fontSize = 13.sp,
                )
                if (s.refreshFailed) Text("连不上服务，稍后重试", Modifier.testTag("conflict-refresh-failed"), color = Color(0xFF8A6D1F), fontSize = 12.sp)
                if (!s.versionChanged) {
                    val scope = rememberCoroutineScope()
                    Text("保留本机位置", Modifier.testTag("keep-local").clickable { scope.launch { remote.keepLocalAfterConflict() } }.padding(top = 4.dp), color = Accent, fontSize = 13.sp)
                    if (s.remote != null) Text("用另一台的位置", Modifier.testTag("use-remote").clickable { session.useRemotePosition() }.padding(top = 4.dp), color = Accent, fontSize = 13.sp)
                }
            }
            is SaveState.Offline -> Text("连不上服务，位置稍后重试保存", Modifier.testTag("save-offline").padding(top = 8.dp), color = Color(0xFF8A6D1F), fontSize = 12.sp)
            is SaveState.Failed -> Text("位置没有保存：${s.failure.text}", Modifier.testTag("save-failed").padding(top = 8.dp), color = Color(0xFFA33B2D), fontSize = 12.sp)
            else -> {}
        }
    }
}

/** 页面：页图（可叠原句高亮）+ 按行的正文 + 本页批注 + 批注编辑。 */
@Composable
private fun RemotePage(session: ReaderSession, remote: ReaderRemote, view: ReaderPageView) {
    val state = session.state
    val bitmap = remember(view.image) { view.image?.let { b -> runCatching { org.jetbrains.skia.Image.makeFromEncoded(b).toComposeImageBitmap() }.getOrNull() } }
    // 高亮的引文：题目点出处时优先查题目的原句；若未命中题目 ID 则直接作为文本锚点；否则是用户点选的批注。
    val quote = remember(state.flashAnchor, session.highlightNoteId, remote.notes, remote.study) {
        state.flashAnchor?.takeIf { it.isNotBlank() }?.let { raw ->
            remote.studyQuestions.firstOrNull { it.id == raw }?.anchor ?: raw
        } ?: session.highlightNoteId?.let { id ->
            remote.notes.firstOrNull { it.id == id && remote.noteValid(it, view) }?.quote
        }
    }
    val located: Located? = when (val b = remote.boxes) {
        is BoxesState.Ready -> quote?.let { QuoteLocator.locate(b.boxes, it) }
        else -> null
    }
    if (bitmap != null) {
        val boxes = (remote.boxes as? BoxesState.Ready)?.boxes
        val aspectOk = boxes != null && kotlin.math.abs(bitmap.width.toDouble() / bitmap.height - boxes.width / boxes.height) < 0.02
        Box(Modifier.fillMaxWidth()) {
            Image(bitmap, "PDF 第${view.pdfPage}页", Modifier.testTag("remote-image").fillMaxWidth(), contentScale = ContentScale.FillWidth)
            if (located is Located.Found && aspectOk && boxes != null) {
                Canvas(Modifier.testTag("remote-highlight").matchParentSize()) {
                    val k = size.width / boxes.width.toFloat()
                    for (r in located.rects) drawRect(
                        Flash.copy(alpha = 0.55f),
                        topLeft = androidx.compose.ui.geometry.Offset(r.x0.toFloat() * k, r.y0.toFloat() * k),
                        size = androidx.compose.ui.geometry.Size((r.x1 - r.x0).toFloat() * k, (r.y1 - r.y0).toFloat() * k),
                    )
                }
            }
        }
    }
    val status = if (quote == null) null else {
        when {
            remote.boxes !is BoxesState.Ready -> "文字坐标不可用，不标注原句"
            located == Located.NoTextLayer -> "这一页没有文字层，无法标出原句"
            located == Located.NotFound -> "没找到这句原文，无法确认位置"
            located == Located.Ambiguous -> "这句原文在页上出现多处，无法确认是哪一处"
            bitmap == null -> "没有页图，无法在页上标出"
            located is Located.Found -> "已在页上标出原句"
            else -> "无法确认原句位置"
        }
    }
    SideEffect { session.uiHighlight = status }
    if (status != null) Text(status, Modifier.testTag("highlight-status").padding(vertical = 4.dp), fontSize = 12.sp, color = Color(0xFF555555))
    val size = (16 * state.zoomPercent / 100f).sp
    val lines = session.pageLines()
    var offset = 0
    view.text.split('\n').forEachIndexed { i, line ->
        val range = offset until offset + line.length
        val picked = session.noteLine >= 0 && lines.getOrNull(session.noteLine) == range && line.isNotBlank()
        val noted = remote.notes.any { remote.noteValid(it, view) && it.start >= range.first && it.end <= range.last + 1 }
        Text(
            line,
            Modifier.testTag("line-$i").fillMaxWidth().onGloballyPositioned { session.uiLineBounds[i] = it.boundsInRoot() }
                .then(if (picked) Modifier.background(Color(0xFFCFE3F6)) else if (noted) Modifier.background(Color(0xFFFFF4C2)) else Modifier)
                .padding(vertical = 3.dp),
            fontSize = size, color = Ink,
        )
        offset += line.length + 1
    }
    if (session.noteLine >= 0) Text(if (session.draftsKept()) "上下键选一行，回车确认，Esc 关闭" else "上下键选一行，回车确认，Esc 放弃", Modifier.testTag("note-pick-hint").padding(top = 4.dp), fontSize = 12.sp, color = Accent)
    session.message?.let { Text(it, Modifier.testTag("message").padding(top = 8.dp), color = Color(0xFFA33B2D), fontSize = 13.sp) }
    NotesSection(session, remote, view)
    if (session.noteDraft != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { session.requestNoteSave() }, enabled = !session.noteSaving,
                modifier = Modifier.testTag("reader-note-save")) { Text("保存批注") }
            TextButton(onClick = { session.cancelNote() },
                modifier = Modifier.testTag("reader-note-close")) { Text("关闭编辑区") }
        }
    }
}
