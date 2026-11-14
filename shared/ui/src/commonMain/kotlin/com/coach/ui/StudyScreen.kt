package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.coach.client.*
import com.coach.domain.*
import kotlinx.serialization.json.*
import com.coach.ui.chat.AnchoredChatList
import com.coach.ui.chat.ChatListState
import com.coach.ui.input.ComposerField
import com.coach.ui.input.EnterGate
import kotlinx.coroutines.launch

/** 阅读栏最大宽度：内屏/桌面宽窗口下正文不拉成一整行。 */
private val READING_WIDTH = coach.design.tokens.CoachV7.Layout.colw

@Composable
fun StudyScreen(c: CoachController, kp: KpRow, view: CourseView, ui: TutorUiState, autoStart: Boolean, onBack: (() -> Unit)?, initialDraft: String? = null, onOpenReference: ((KpRow) -> Unit)? = null, weekActions: WeekActions? = null, reviewMode: Boolean = false, initialFocus: Boolean = false, backLabel: String? = null) {
    val thread = if (reviewMode) view.reviewThreadId(kp.id) else view.tutorThreadId(kp.id)
    val tv = ui.threads[thread] ?: ThreadView()
    val groups = view.quizGroups(thread)
    SideEffect { if(groups.isNotEmpty()) PoolTrace.mark(thread,"composed") }
    val chosen = rememberSaveable(kp.id, saver = mapSaver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>>(
        save = { it.toMap() }, restore = { it.map { e -> e.key to e.value as String }.toMutableStateMap() })) {
        (c.sync.mainlineState(c.courseId, "choices:$thread")).map { (key, v) -> key to (v as? JsonPrimitive)?.content.orEmpty() }.toMutableStateMap()
    }
    val pending = groups.lastOrNull { !it.submitted }
    val additionalScope = remember(kp.title) { additionalTitleScope(kp.title) }
    var showScope by remember(kp.id) { mutableStateOf(false) }
    var askOpen by remember(kp.id) { mutableStateOf(false) }
    var draft by rememberSaveable(kp.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initialDraft.orEmpty())) }
    val enterGate = remember(kp.id) { EnterGate() }
    val focus = LocalFocusManager.current
    var claimReferenceOpen by remember(c, kp) { mutableStateOf(false) }
    val openReference: ((KpRow) -> Unit)? = if (kp.sourceReferences.structured) ({
        focus.clearFocus()
        claimReferenceOpen = true
    }) else onOpenReference
    if (claimReferenceOpen) ClaimSourceDialog(c.course?.reader, kp, "学习") { claimReferenceOpen = false }
    var opened by remember(kp.id) { mutableStateOf(false) }
    var started by rememberSaveable(kp.id) { mutableStateOf(false) }
    var pinned by remember(kp.id) { mutableStateOf(false) }
    val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0

    fun open() = c.tutor.open(thread, c.courseId, kp.id, kp.conceptIds, kp.title, review = reviewMode)
    fun fail() = c.tutor.model.onFailure(thread, "Mac 未连接，这一步没有发出去。")
    fun next(test: Boolean, recordTap: Boolean = true) {
        pinned = true
        focus.clearFocus()
        if (!open()) return fail()
        pinned = true
        c.tutor.model.onSent(thread, if (test) "test" else "next", null)
        if (!c.tutor.next(thread, c.courseId, c.packFor(kp.id), test, recordTap)) fail()
    }
    fun send(text: String): Boolean {
        if (tv.busy || !ui.connected) return false
        // 发出后收起键盘：回答和「下一步 / 出题」按钮马上可见。
        focus.clearFocus()
        if (!open()) { fail(); return false }
        pinned = true
        return if (c.tutor.ask(thread, c.courseId, text, c.packFor(kp.id))) true else { fail(); false }
    }

    // 每次（重新）连上都声明一次线程；服务端按 kpId 幂等。
    val startRequest = c.startRequests[kp.id]
    LaunchedEffect(thread, ui.connected, tv.historyLoaded, tv.busy, startRequest) {
        if (!ui.connected) { opened = false; return@LaunchedEffect }
        if (!opened) opened = open()
        // 面板上点了一次就开始一次：已有历史也照样开讲 / 出题；还有没答完的题就先答题，不再另起。
        if (startRequest != null && opened && tv.historyLoaded && !tv.busy) {
            c.startRequests.remove(kp.id)
            started = true
            if (pending == null) next(startRequest, recordTap = false)
        }
        if (autoStart && !started && opened && tv.historyLoaded && !tv.busy) {
            started = true
            if (pending == null && (reviewMode || tv.items.isEmpty())) next(true, recordTap = false)
        }
    }

    // 没等到开始就离开了：这次的启动作废，下次从别处打开不会突然开讲。
    DisposableEffect(kp.id) { onDispose { c.startRequests.remove(kp.id) } }

    // A newly passed queue item opens its successor after grading/streaming settles.
    LaunchedEffect(kp.status, view.weekTarget?.id, tv.busy, pending) {
        if (weekActions != null && view.weekProgress(kp.id) != null && kp.status in setOf(KpStatus.PASSED, KpStatus.SOLID) && !tv.busy && pending == null) {
            weekActions.onOpen(view.weekTarget?.id)
        }
    }

    var readingFocus by rememberSaveable(kp.id) { mutableStateOf(initialFocus) }
    var conversation by rememberSaveable(kp.id) { mutableStateOf(initialDraft != null || c.mainlineState("learning").str("kind") == "tutor") }
    val completed = tv.items.filterIsInstance<ChatItem.Tutor>().lastOrNull { it.message.status == "complete" }
    if (!reviewMode && !conversation && completed != null && pending == null && !tv.busy) {
        com.coach.ui.v7.ReadingPage(c, kp, view, completed.message.text, readingFocus, { readingFocus = !readingFocus }, onBack,
            onAsk = { conversation = true }, onQuiz = { conversation = true; next(true) },
            onSource = { openReference?.invoke(kp) }, weekActions = weekActions,
            onSelectionAsk = { draft = TextFieldValue(it); conversation = true }, backLabel = backLabel)
        return
    }
    LaunchedEffect(kp.id, reviewMode) {
        if (!reviewMode) c.saveMainlineState(json("kind" to "tutor", "kpId" to kp.id), "learning")
    }
    val marginAnchors = remember(kp.id) { com.coach.ui.v7.MarginAnchors() }
    val notes = remember(tv.items, kp.reference) {
        tv.items.filterIsInstance<ChatItem.Tutor>().flatMap { item -> com.coach.ui.v7.footnotes(item.message.text).map { note -> note.copy(id = "${item.message.id}:${item.message.epoch}:${note.id}") } }.distinctBy { it.id }
    }
    CompositionLocalProvider(com.coach.ui.v7.LocalMarginAnchors provides marginAnchors) {
    val parentLabel = backLabel ?: if (reviewMode) "← 复习" else "← 今天"
    com.coach.ui.v7.Desk(crumb = moduleDisplayName(kp.module).ifBlank { "课程" }, focus = readingFocus, onFocus = { readingFocus = !readingFocus }, onBack = onBack, backLabel = if (pending != null) "暂离 · ${parentLabel.removePrefix("← ")}" else parentLabel, showNavigation = pending == null,
        directory = { com.coach.ui.v7.WeekDirectory(view, view.projection.studyScope?.week, weekActions?.let { it::select }) },
        margin = { com.coach.ui.v7.MarginNotes(notes) {
            if (kp.reference.isNotBlank()) com.coach.ui.v7.PaperCard {
                Text("出处", style = MaterialTheme.typography.labelMedium)
                Text(kp.reference, style = MaterialTheme.typography.bodyMedium)
                if (openReference != null && (kp.anchorDoc.isNotBlank() || kp.sourceReferences.structured)) TextButton(onClick = { openReference(kp) }) { Text("看原文") }
            }
        } }) {
    Column(Modifier.fillMaxSize().padding(horizontal = com.coach.ui.v7.LocalDeskMetrics.current.horizontalPadding).padding(top = if (pending != null) 8.dp else com.coach.ui.v7.LocalDeskMetrics.current.topPadding), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = READING_WIDTH).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                // Back button and the title's first line sit on one row; the scope toggle lines up with the title's left edge.

                Column(Modifier.weight(1f)) {
                    val (tag, main) = splitTitle(kp.title)
                    if (pending != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(main, Modifier.weight(1f).testTag("study-quiz-heading"),
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!readingFocus) TextButton(onClick = { askOpen = true }, modifier = Modifier.testTag("study-ask")) { Text("问一句") }
                    }
                    else com.coach.ui.v7.PageHeader(if (reviewMode) "到期复习" else view.weekProgress(kp.id).orEmpty(), main)
                    // 范围、看原文（点开就是本知识点锚点所在的教材页）、状态放在同一组里，放不下就换行，不压标题。
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    if (!readingFocus && pending == null) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                        TextButton(onClick = { c.tutor.telemetry?.record("tap", "scope"); showScope = !showScope }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text(if (showScope) "收起出处" else sourceLine(tag).ifBlank { "出处" } + if (additionalScope.isNotEmpty()) " · 另 ${additionalScope.size} 处 ▾" else " ▾", style = MaterialTheme.typography.labelMedium)
                        }
                        if (openReference != null && (kp.anchorDoc.isNotBlank() || kp.sourceReferences.structured)) com.coach.ui.v7.PaperButton(onClick = { openReference(kp) }, modifier = Modifier.heightIn(min = 48.dp).testTag("open-source"), contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Text(if (kp.sourceReferences.structured) "查看主张出处" else "看原文 · ${kp.reference.ifBlank { "教材" }}", style = MaterialTheme.typography.labelLarge)
                        } else if (kp.reference.isNotBlank()) Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { Text("本次参考：${kp.reference}", style = MaterialTheme.typography.labelMedium) }
                        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { StatusChip(kp.status.label, statusColor(kp.status)) }
                    }

                    if (showScope) {
                        Text(main, style = MaterialTheme.typography.bodyMedium)
                        additionalScope.forEach { Text("• " + com.coach.client.displayTitle(it).ifBlank { com.coach.client.titleSource(it) } + com.coach.client.titleSource(it).takeIf { s -> s.isNotBlank() }?.let { s -> "（$s）" }.orEmpty(), style = MaterialTheme.typography.bodyMedium) }
                        if (weekActions != null) WeekPicker(view, weekActions, ui.connected)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Reading position lives with the screen state (and this screen is `movableContentOf` in CoachApp): a fold/unfold keeps it.
        val savedAnchor = remember(thread) { c.sync.mainlineState(c.courseId, "anchor:$thread") }
        val chat = remember(kp.id) { ChatListState(anchor = com.coach.ui.chat.ChatAnchor(
            pinned = !savedAnchor.containsKey("key") || savedAnchor.flag("pinned"),
            key = savedAnchor.str("key").ifBlank { null }, fraction = (savedAnchor["fraction"] as? JsonPrimitive)?.floatOrNull ?: 0f)) }
        LaunchedEffect(chat) {
            snapshotFlow { Triple(chat.anchor.pinned, chat.anchor.key, chat.anchor.fraction) }.collect { (atEnd, key, fraction) ->
                if (key != null) c.sync.saveMainlineState(c.courseId, json("pinned" to atEnd, "key" to key.toString(), "fraction" to fraction), "anchor:$thread")
            }
        }
        val scope = rememberCoroutineScope()
        // 讲解/追问消息和题组按「发生的先后」交错：消息出现时记下当时已有几组题，排在那几组之后。
        val anchors = remember(kp.id) { c.quizMessageAnchors.getOrPut(thread) { HashMap() } }
        val rows = remember(tv.items, groups, tv.notice, tv.poolText, tv.error) {
            val placed = tv.items.map { item -> item to anchors.getOrPut(item.key) { groups.size }.coerceAtMost(groups.size) }
            buildList<StudyRow> {
                for (g in 0..groups.size) {
                    placed.filter { it.second == g }.forEach { add(StudyRow.Message(it.first)) }
                    groups.getOrNull(g)?.let { group ->
                        group.cards.forEachIndexed { index, card -> add(StudyRow.Question(group, index, card)) }
                        add(StudyRow.GroupFooter(group))
                    }
                }
                tv.notice?.let { add(StudyRow.Note("notice", it, NoteKind.Notice)) }
                tv.poolText?.let { add(StudyRow.Note("pool", it, NoteKind.Progress)) }
                tv.error?.let { add(StudyRow.Note("error", it, NoteKind.Error)) }
            }
        }
        // 发送、点「下一步/出题」后回到底部继续跟随。
        LaunchedEffect(pinned) { if (pinned) { chat.scrollToTail(); pinned = false } }
        // 新题组出现，或刚打开线程时还没答完的那组：把第 1 题放在最上面，从头读起。
        var seenGroups by remember(kp.id) { mutableStateOf(if (savedAnchor.containsKey("key")) groups.size else 0) }
        val focusKey = if (groups.size > seenGroups && (pending != null || seenGroups > 0))
            (groups.last().cards.firstOrNull { it.answer == null } ?: groups.last().cards.firstOrNull())?.let { "q:${it.attemptId}" } else null
        SideEffect {
            if (focusKey != null) {
                val index = rows.indexOfFirst { it.key == focusKey }
                if (index >= 0) chat.requestItemTop(focusKey, rows.lastIndex - index)
            }
        }
        LaunchedEffect(groups.size) {
            if (groups.size > seenGroups && (pending != null || seenGroups > 0)) {
                PoolTrace.mark(thread,"focus_requested")
                (groups.last().cards.firstOrNull { it.answer == null } ?: groups.last().cards.firstOrNull())?.let { chat.scrollItemTopIntoView("q:${it.attemptId}") }
                PoolTrace.mark(thread,"focus_settled")
            }
            seenGroups = groups.size
        }
        Box(Modifier.weight(1f).widthIn(max = READING_WIDTH).fillMaxWidth().testTag("study-scroll-viewport")) {
            if (rows.isEmpty()) Text(
                if (reviewMode) "正在准备复习题；也可以点「开始复习」。" else if (kp.explained) "这一节讲过了。点「下一步」继续出题，或直接问一句。" else "还没开始。点下面的「开始讲解」。",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BoxWithConstraints(Modifier.fillMaxSize()) {
                AnchoredChatList(
                    items = rows, key = { it.key }, state = chat, windowKey = constraints.maxWidth,
                    modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = if (pending != null) 8.dp else 16.dp), spacing = 20.dp,
                ) { row ->
                    when (row) {
                        is StudyRow.Message -> when (val item = row.item) {
                            is ChatItem.Learner -> LearnerBubble(item.text)
                            is ChatItem.Tutor -> TutorBubble(item.message, c.math) { c.tutor.cancel(item.message.id) }
                        }
                        is StudyRow.Question -> QuizCardView(row.index + 1, row.group.cards.size, row.card, row.group.submitted, chosen[row.card.attemptId], c.math, onFirstDraw={ PoolTrace.mark(thread,"first_draw") }) { letter ->
                            if (!row.group.submitted) {
                                c.tutor.telemetry?.record("tap", "select_option"); chosen[row.card.attemptId] = letter
                                c.sync.saveMainlineState(c.courseId, JsonObject(chosen.mapValues { JsonPrimitive(it.value) }), "choices:$thread")
                            }
                        }
                        is StudyRow.GroupFooter -> QuizGroupFooter(row.group, kp, c, view, thread, chosen) { c.tutor.model.onSent(thread, "answer", null); pinned = true }
                        is StudyRow.Note -> when (row.kind) {
                            NoteKind.Notice -> Text(row.text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                            NoteKind.Progress -> ProgressLine(row.text)
                            NoteKind.Error -> Text(row.text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (!chat.anchor.pinned && tv.streaming) {
                FilledTonalButton(onClick = { scope.launch { chat.scrollToTail() } }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).heightIn(min = 48.dp)) { Text("回到最新") }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!readingFocus && pending == null) Column(Modifier.widthIn(max = READING_WIDTH).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 答题时「下一步 / 出题」本来就点不了，不占高度，把空间让给题目；键盘弹出时同样收起。
            if (!imeOpen) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                // 服务端只在需要时给特殊文字（「换个例子再讲」）；还没讲过、线程里什么都没有时按钮就叫「开始讲解」。
                val nextText = when {
                    reviewMode -> "开始复习"
                    tv.nextLabel == "换个例子再讲" -> "换个例子再讲"
                    !kp.explained && tv.items.isEmpty() && groups.isEmpty() -> "开始讲解"
                    else -> "下一步"
                }
                com.coach.ui.v7.PrimaryButton(onClick = { next(false) }, enabled = ui.connected && !tv.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(nextText) }
                com.coach.ui.v7.PaperButton(onClick = { next(true) }, enabled = ui.connected && !tv.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("出题") }
            }
            if (!imeOpen && weekActions != null && view.weekTarget?.id == kp.id) {
                com.coach.ui.v7.PaperButton(onClick = { weekActions.next(view, kp.id) }, enabled = ui.connected && !tv.busy && !weekActions.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("下一节 →") }
                weekActions.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            // 输入框始终可用（讲解进行中也能打字，键盘不会被收起）；只有「发送」会按状态禁用。
            // 组字回车只上屏、不发送；硬件键盘/桌面回车发送，Shift+回车换行；手机回车换行，点击按钮发送（§7.6）。
            ComposerField(
                value = draft, onValueChange = { draft = it }, onSend = { send(it) }, gate = enterGate,
                placeholder = "问一句…", maxLines = 4, canSend = ui.connected && !tv.busy,
            )
        }
    }
    if (askOpen && pending != null && !readingFocus) AlertDialog(onDismissRequest = { askOpen = false },
        title = { Text("问一句") }, text = {
            Column { Text("现在问，这题不算进「${com.coach.client.KpStatus.SOLID.label}」", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ComposerField(value = draft, onValueChange = { draft = it }, onSend = { text -> send(text).also { if (it) askOpen = false } },
                gate = enterGate, placeholder = "问一句…", maxLines = 4, canSend = ui.connected && !tv.busy) }
        }, confirmButton = { TextButton(onClick = { askOpen = false }) { Text("继续答题") } })
}

}
}

private sealed interface StudyRow {
    val key: String
    data class Message(val item: ChatItem) : StudyRow { override val key get() = item.key }
    data class Question(val group: QuizGroup, val index: Int, val card: QuizCard) : StudyRow { override val key get() = "q:${card.attemptId}" }
    data class GroupFooter(val group: QuizGroup) : StudyRow { override val key get() = "g:${group.groupId}" }
    data class Note(override val key: String, val text: String, val kind: NoteKind) : StudyRow
}

private enum class NoteKind { Notice, Progress, Error }

@Composable
private fun ProgressLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
