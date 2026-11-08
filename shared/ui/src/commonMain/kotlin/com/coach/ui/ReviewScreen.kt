package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.client.CourseView

/**
 * 错题卡片的标题：错题是历史，退休点上的错题仍要显示知识点名，
 * 只有活跃队列/选点才排除退休点。
 */
internal fun mistakeTitle(view: CourseView, kpId: String): String = view.kpAny(kpId)?.title ?: kpId

/** Due queue from the ledger; Start opens the independent review tutor thread. */
@Composable
fun ReviewScreen(c: CoachController, view: CourseView, onOpenKp: (kpId: String, startNow: Boolean) -> Unit, onBack: (() -> Unit)?, onOpenMock: (() -> Unit)? = null, onOpenEssay: (() -> Unit)? = null, onOpenMainline: (() -> Unit)? = null, reminderSettings: @Composable () -> Unit = {}) {
    val now = rememberCourseNow(c)
    val due = view.dueReviews(now)
    var tab by remember(c.courseId) { mutableStateOf(if (due.isNotEmpty()) 1 else 0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 16.dp else 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 今天") }
            Text("复习", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            reminderSettings()
        }
        HorizontalDivider()
        Text(examReviewNotice(view, now), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        if (onOpenMainline != null) Surface(onClick = onOpenMainline, tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).heightIn(min = 56.dp).testTag("open-mainline")) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("选择题主线", style = MaterialTheme.typography.bodyLarge)
                    Text("按周从铺垫题做到桥接题，答完立刻看讲解", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }
        if (onOpenMock != null) Surface(onClick = onOpenMock, tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 56.dp).testTag("open-mock")) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("模拟考与单选速刷", style = MaterialTheme.typography.bodyLarge)
                    Text("限时整卷看现在能拿几分、丢在哪；单选 7 题 14 分钟", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }
        if (onOpenEssay != null) EssayEntry(c, onOpenEssay)
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("错题 ${view.mistakes.items.size}", "到期 ${due.size}", "离线练习").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        if (due.isEmpty() && tab != 1) {
            Text("今天没有到期的复习。", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        when (tab) {
            0 -> MistakesPane(c, view)
            2 -> OfflinePractice(c)
            else -> {
                if (due.isEmpty()) {
                    CenterMessage("今天没有到期的复习。")
                } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("${due.size} 个知识点到期", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(due, key = { it.kp.id }) { item ->
                        val (tag, main) = splitTitle(item.kp.title)
                        Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    if (tag.isNotBlank()) Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(main, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text((if (item.revisit) "再看一次 · " else "") + if (item.overdueDays == 0) "今天到期" else "逾期 ${item.overdueDays} 天", style = MaterialTheme.typography.labelMedium, color = if (item.overdueDays == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                                }
                                Button(onClick = { onOpenKp(item.kp.id, true) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("开始") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MistakesPane(c: CoachController, view: CourseView) {
    val ui by c.tutor.model.state.collectAsState()
    var activeKp by remember { mutableStateOf<String?>(null) }
    var priorGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val chosen = remember { mutableStateMapOf<String, String>() }
    val listState = rememberLazyListState()
    val thread = activeKp?.let(view::reviewThreadId).orEmpty()
    val tv = ui.threads[thread]
    val group = if (activeKp == null) null else view.quizGroups(thread).lastOrNull { it.groupId !in priorGroups }
    fun retry(kpId: String) {
        // 错题可能是已退休点上的：名称和概念仍要能取到，否则会退回原始 id。
        val kp = view.kpAny(kpId)
        val target = view.reviewThreadId(kpId)
        val groups = view.quizGroups(target)
        priorGroups = groups.filter { it.submitted }.map { it.groupId }.toSet()
        activeKp = kpId; chosen.clear(); notice = null
        c.tutor.model.onSent(target, "retry", null)
        if (!c.tutor.open(target, c.courseId, kpId, kp?.conceptIds ?: listOf(kpId), kp?.title ?: kpId, review = true) ||
            !c.tutor.retryMistakes(target, c.courseId)) {
            c.tutor.model.onFailure(target, "Mac 未连接，连上后可再练；错题已保存在本机。")
        }
    }
    LaunchedEffect(activeKp, group?.groupId) { listState.scrollToItem(0) }
    LaunchedEffect(group, view.mistakes) {
        if (group?.cards?.all { it.grade != null } == true && view.mistakes.items.none { it.kpId == activeKp }) {
            activeKp = null; notice = "连续答对 3 道，这个知识点的错题已移出。"
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("同一知识点连续答对 3 道新题后，自动移出。", style = MaterialTheme.typography.bodyMedium)
                if (!ui.connected) Text("离线可回看错题；连接 Mac 后可再练。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (activeKp != null) TextButton(onClick = { activeKp = null }) { Text("← 返回错题") }
            }
        }
        if (activeKp != null) {
            if (group == null) item {
                Column(Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                    Text(tv?.error ?: "正在取出同一知识点的 3 道新题…")
                    if (tv?.error != null) Button(onClick = { activeKp?.let(::retry) }, enabled = ui.connected,
                        modifier = Modifier.heightIn(min = 56.dp)) { Text("重试") }
                }
            } else {
                items(group.cards, key = { it.attemptId }) { card ->
                    Box(Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
                        QuizCardView(group.cards.indexOf(card) + 1, group.cards.size, card, group.submitted,
                            chosen[card.attemptId], c.math) { chosen[card.attemptId] = it }
                    }
                }
                item {
                    Column(Modifier.widthIn(max = 680.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tv?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (!group.submitted) Button(onClick = {
                            if (!c.tutor.answer(thread, c.courseId, group.groupId,
                                    group.cards.associate { it.attemptId to chosen.getValue(it.attemptId) }, "retry-${group.groupId}"))
                                c.tutor.model.onFailure(thread, "Mac 未连接，答案尚未提交，选择已保留。")
                        }, enabled = ui.connected && group.cards.all { chosen[it.attemptId] != null },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("提交本组答案") }
                        else if (group.cards.all { it.grade != null }) {
                            Text("本组 ${group.cards.count { it.grade?.correct == true }} / ${group.cards.size} 题正确")
                            Button(onClick = { activeKp?.let(::retry) }, enabled = ui.connected,
                                modifier = Modifier.heightIn(min = 56.dp)) { Text("再练一组") }
                        } else Text("答案已提交，正在判分…")
                    }
                }
            }
        } else if (view.mistakes.items.isEmpty()) {
            item { Text(view.mistakes.emptyText, Modifier.padding(vertical = 32.dp)) }
        } else {
            items(view.mistakes.items, key = { "${it.kpId}:${it.attemptId}" }) { mistake ->
                MistakeCard(mistake, mistakeTitle(view, mistake.kpId), c.math, ui.connected) { retry(mistake.kpId) }
            }
        }
    }
}

@Composable
internal fun MistakeCard(mistake: com.coach.client.Mistake, title: String, math: MathAssets, online: Boolean, onRetry: () -> Unit) {
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Markdown(mistake.card.stem, true, "mistake:${mistake.attemptId}", math)
            val answer = mistake.card.answer.orEmpty()
            Text("当时的答案：" + (mistake.card.options.firstOrNull { it.first == answer }?.let { "${it.first} · ${it.second}" } ?: answer))
            Text("正确答案：${mistake.correctAnswer}")
            Markdown("判分依据：${mistake.reasonSentence}", true, "reason:${mistake.attemptId}", math)
            Button(onClick = onRetry, enabled = online, modifier = Modifier.heightIn(min = 56.dp)) { Text("再练一组") }
        }
    }
}

/** 论述限时练习的入口；正在写的那篇在这里显示剩余时间。 */
@Composable
private fun EssayEntry(c: CoachController, onOpen: () -> Unit) {
    val session = c.essay
    var now by remember { mutableStateOf(c.nowMillis()) }
    LaunchedEffect(session) { while (session != null && !session.handedIn) { now = c.nowMillis(); delay(1000) } }
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("论述限时练习", style = MaterialTheme.typography.bodyLarge)
                Text("限时写一篇，结构、论点、证据、例子逐项批改", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val label = when {
                session == null -> "开始一篇"
                session.handedIn -> "看批改"
                else -> {
                    val left = ((session.deadline - now + 999) / 1000).coerceAtLeast(0)
                    "继续写（剩 ${left / 60}:${(left % 60).toString().padStart(2, '0')}）"
                }
            }
            Button(onClick = onOpen, modifier = Modifier.heightIn(min = 56.dp).testTag("essay-entry")) { Text(label) }
        }
    }
}

/** Independent from the learning thread: a downloaded review never changes Continue. */
@Composable
private fun OfflinePractice(c: CoachController) {
    val revision by c.sync.revision.collectAsState()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(c.nowMillis()) }
    LaunchedEffect(c) { while (true) { tick = c.nowMillis(); delay(1000) } }
    val groups = remember(revision,tick) { c.sync.offlineGroups(c.courseId,tick) }
    var active by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<Map<String,String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    val group = groups.firstOrNull { it.groupId == active }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("离线练习", style = MaterialTheme.typography.titleMedium)
        if (group == null) {
            Text("${groups.filter { !it.submitted }.sumOf { it.cards.size }} 题可做 · 下载后无需连接 Mac")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { active = groups.firstOrNull { !it.submitted }?.groupId; chosen = emptyMap() },
                    enabled = groups.any { !it.submitted }, modifier = Modifier.heightIn(min = 56.dp)) { Text("复习 ${groups.firstOrNull { !it.submitted }?.cards?.size ?: 3} 题") }
                OutlinedButton(onClick = {
                    c.tutor.telemetry?.record("tap", "offline_pack")
                    downloading = true; error = null
                    scope.launch {
                        try { withContext(Dispatchers.IO) { c.sync.downloadOfflinePack(c.courseId) } }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { c.tutor.telemetry?.record("error", "offline_pack"); error = "Mac 未连接，下载未完成；已有题仍可练习。" }
                        finally { downloading = false }
                    }
                },enabled = !downloading,modifier = Modifier.heightIn(min = 56.dp)) { Text(if (downloading) "正在下载…" else "下载练习包") }
            }
            groups.lastOrNull { it.submitted }?.let { last ->
                TextButton(onClick = { active = last.groupId }) { Text("查看上组结果") }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(group.cards,key = { it.attemptId }) { card ->
                    QuizCardView(group.cards.indexOf(card)+1,group.cards.size,card,group.submitted,chosen[card.attemptId],c.math) {
                        c.tutor.telemetry?.record("tap", "select_option")
                        chosen = chosen + (card.attemptId to it)
                    }
                }
                item {
                    if (!group.submitted) Button(onClick = {
                        c.tutor.telemetry?.record("tap", "answer")
                        try { c.sync.submitOfflineGroup(c.courseId,group.groupId,chosen,c.nowMillis()); error = null }
                        catch (_: IllegalArgumentException) { c.tutor.telemetry?.record("error", "offline_pack"); error = "题组已提交或已过期，请重新下载。" }
                    },enabled = group.cards.all { chosen[it.attemptId] != null },modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("提交本组答案") }
                    else TextButton(onClick = { active = null; chosen = emptyMap() }) { Text("返回练习队列") }
                }
            }
        }
        error?.let { Text(it,color = MaterialTheme.colorScheme.error) }
    }
}
