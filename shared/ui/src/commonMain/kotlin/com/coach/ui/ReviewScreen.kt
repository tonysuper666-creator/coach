package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.client.CourseView
import com.coach.domain.*
import kotlinx.serialization.json.*

/**
 * 错题卡片的标题：错题是历史，退休点上的错题仍要显示知识点名，
 * 只有活跃队列/选点才排除退休点。
 */
internal fun mistakeTitle(view: CourseView, kpId: String): String = view.kpAny(kpId)?.title ?: kpId

/** Due queue from the ledger; Start opens the independent review tutor thread. */
@Composable
fun ReviewScreen(c: CoachController, view: CourseView, onOpenKp: (kpId: String, startNow: Boolean) -> Unit, onBack: (() -> Unit)?, onOpenMock: (() -> Unit)? = null, onOpenEssay: (() -> Unit)? = null, onOpenMainline: ((Int?, Boolean) -> Unit)? = null, reminderSettings: @Composable () -> Unit = {}) {
    val now = rememberCourseNow(c)
    val due = view.dueReviews(now)
    var tab by remember(c.courseId) { mutableStateOf(if (due.isNotEmpty()) 1 else 0) }
    // 离线练习不再是单独一页，两页都在；选到一半的答案由这里持有，换页、滚走都不丢。
    val offline = remember(c.courseId) { OfflineDraft() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 16.dp else 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 今天") }
            Text("复习", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            reminderSettings()
        }
        HorizontalDivider()
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("错题 ${view.mistakes.items.size}", "到期 ${due.size}").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        // 提醒、页签固定；说明、入口、列表和离线练习在同一个滚动里，短窗口也能滚到每一行（TON-1495）。
        val notice: @Composable () -> Unit = {
            Text(examReviewNotice(view, now), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        val extras: @Composable () -> Unit = {
            OfflinePractice(c, offline)
            ReviewShortcuts(c, view, onOpenMainline, onOpenMock, onOpenEssay)
        }
        // 错题页的主体是错题：考期提示（无到期时连同「今天没有到期的复习。」）之后紧跟列表，
        // 离线练习和入口排在列表之后，展开屏首屏就能看到错题和无到期说明。
        if (tab == 0) MistakesPane(c, view, Modifier.weight(1f), top = {
            Column(Modifier.fillMaxWidth()) {
                notice()
                if (due.isEmpty()) Text("今天没有到期的复习。", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }) { Column(Modifier.fillMaxWidth()) { extras() } }
        else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            notice()
            extras()
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (due.isEmpty()) Text("今天没有到期的复习。")
                else {
                    Text("${due.size} 节到期", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    due.forEach { item -> key(item.kp.id) {
                        val (tag, main) = splitTitle(item.kp.title)
                        Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("review-${item.kp.id}")) {
                            Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    if (tag.isNotBlank()) Text(sourceLine(tag), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(main, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text((if (item.revisit) "再看一次 · " else "") + if (item.overdueDays == 0) "今天到期" else "逾期 ${item.overdueDays} 天", style = MaterialTheme.typography.labelMedium, color = if (item.overdueDays == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                                }
                                Button(onClick = { onOpenKp(item.kp.id, true) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("开始") }
                            }
                        }
                    } }
                }
            }
        }
    }
}

/** 离线练习进行中的状态，提到复习页这一层持有。 */
private class OfflineDraft {
    var active by mutableStateOf<String?>(null)
    var chosen by mutableStateOf<Map<String, String>>(emptyMap())
    var error by mutableStateOf<String?>(null)
    var downloading by mutableStateOf(false)
}

@Composable
private fun MistakesPane(c: CoachController, view: CourseView, modifier: Modifier, top: @Composable () -> Unit, bottom: @Composable () -> Unit) {
    val ui by c.tutor.model.state.collectAsState()
    var activeKp by remember { mutableStateOf<String?>(null) }
    var priorGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val chosen = remember { mutableStateMapOf<String, String>() }
    val scroll = rememberScrollState()
    var sectionTop by remember { mutableStateOf(0) }
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
    // 再练时滚到错题区开头（其上是考期提示），返回时回到页顶。
    LaunchedEffect(activeKp, group?.groupId) { scroll.scrollTo(if (activeKp != null) sectionTop else 0) }
    LaunchedEffect(group, view.mistakes) {
        if (group?.cards?.all { it.grade != null } == true && view.mistakes.items.none { it.kpId == activeKp }) {
            activeKp = null; notice = "连续答对 3 道，这个知识点的错题已移出。"
        }
    }
    // 普通滚动而非 LazyColumn：错题后面的离线练习和入口始终已组合，短窗口里查找和无障碍树都能到达（TON-1495）。
    Column(modifier.fillMaxWidth().verticalScroll(scroll).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        top()
        Column(Modifier.onPlaced { sectionTop = it.positionInParent().y.toInt() }
            .padding(horizontal = 16.dp).widthIn(max = 680.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("同一知识点连续答对 3 道新题后，自动移出。", style = MaterialTheme.typography.bodyMedium)
            if (!ui.connected) Text("离线可回看错题；连接 Mac 后可再练。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (activeKp != null) TextButton(onClick = { activeKp = null }) { Text("← 返回错题") }
        }
        if (activeKp != null) {
            if (group == null) {
                Column(Modifier.padding(horizontal = 16.dp).widthIn(max = 680.dp).fillMaxWidth()) {
                    Text(tv?.error ?: "正在取出同一知识点的 3 道新题…")
                    if (tv?.error != null) Button(onClick = { activeKp?.let(::retry) }, enabled = ui.connected,
                        modifier = Modifier.heightIn(min = 56.dp)) { Text("重试") }
                }
            } else {
                group.cards.forEach { card -> key(card.attemptId) {
                    Box(Modifier.padding(horizontal = 16.dp).widthIn(max = 680.dp).fillMaxWidth()) {
                        QuizCardView(group.cards.indexOf(card) + 1, group.cards.size, card, group.submitted,
                            chosen[card.attemptId], c.math) { chosen[card.attemptId] = it }
                    }
                } }
                Column(Modifier.padding(horizontal = 16.dp).widthIn(max = 680.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        } else if (view.mistakes.items.isEmpty()) {
            Text(view.mistakes.emptyText, Modifier.padding(horizontal = 16.dp, vertical = 32.dp))
        } else {
            view.mistakes.items.forEach { mistake -> key("${mistake.kpId}:${mistake.attemptId}") {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    MistakeCard(mistake, mistakeTitle(view, mistake.kpId), c.math, ui.connected) { retry(mistake.kpId) }
                }
            } }
        }
        bottom()
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

/**
 * 主线、模拟考、论述三个入口并排一行：叠成三张整宽卡片时，手机首屏只剩一百来 dp 给错题和到期列表（TON-1254）。
 * 说明文字只在宽窗口显示。
 */
@Composable
private fun ReviewShortcuts(c: CoachController, view: CourseView, onOpenMainline: ((Int?, Boolean) -> Unit)?, onOpenMock: (() -> Unit)?, onOpenEssay: (() -> Unit)?) {
    if (onOpenMainline == null && onOpenMock == null && onOpenEssay == null) return
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val wide = maxWidth >= 600.dp
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onOpenMainline != null) MainlineTile(c, view, wide, onOpenMainline)
            if (onOpenMock != null) ShortcutTile("模拟考与单选速刷", "限时整卷看现在能拿几分、丢在哪；单选 7 题 14 分钟".takeIf { wide }, "open-mock", onOpenMock)
            if (onOpenEssay != null) EssayEntry(c, wide, onOpenEssay)
        }
    }
}

/**
 * 主线入口（main 的 MainlineEntry）放进并排的一格：一键继续本周、看本周要点、选其他周；
 * 窄屏这一格多给一点宽度，三个按钮竖排，说明文字只在宽窗口显示。
 */
@Composable
private fun RowScope.MainlineTile(c: CoachController, view: CourseView, wide: Boolean, onOpen: (Int?, Boolean) -> Unit) {
    var overview by remember { mutableStateOf(c.sync.mainlineState(c.courseId, "home")["body"] as? JsonObject) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(c) {
        val http = c.course ?: return@LaunchedEffect
        do {
            // The course endpoint is a blocking call: keep it off the composition dispatcher, or the frame that
            // has to show the answer waits for the socket and the entry stays on 正在连接… while it does.
            val reply = withContext(Dispatchers.IO) { http.mainline() }
            error = reply.error
            val body = reply.body
            if (body != null) {
                overview = body
                c.sync.saveMainlineState(c.courseId, json("body" to body), "home")
                break
            }
            if (!reply.retryable) break
            delay(1500)
        } while (true)
    }
    val weeks = (overview?.get("weeks") as? JsonArray).orEmpty().map { it.jsonObject }
    val number = c.mainlineContinuationWeek(view, overview)
    val compactButton = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.weight(if (wide) 1f else 1.4f).fillMaxHeight().testTag("open-mainline")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("选择题主线", style = MaterialTheme.typography.bodyLarge)
            if (wide) Text("按周从基础题做起，答完立刻看讲解", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val links: @Composable () -> Unit = {
                // Swap the placeholder for the real entry instead of renaming one button in place: the label of the
                // week is then carried by a button that already exists, so the screen reader hears the current week.
                if (number != null) {
                    Button(onClick = { onOpen(number, true) }, contentPadding = compactButton, modifier = Modifier.testTag("mainline-continue")) { Text("继续第 $number 周") }
                    TextButton(onClick = { onOpen(number, false) }, contentPadding = compactButton) { Text("看本周要点") }
                } else {
                    Text("正在连接…", Modifier.padding(vertical = 14.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onOpen(null, false) }, contentPadding = compactButton) { Text("选其他周") }
            }
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { links() } else links()
            if (number == null && error != null) Text("Mac 暂时不可用，正在自动重连…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            else if (overview != null && weeks.isEmpty()) Text("还没有生成选择题主线。", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun RowScope.ShortcutTile(title: String, detail: String?, tag: String, onClick: () -> Unit, status: String? = null) {
    Surface(onClick = onClick, tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp).testTag(tag)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            status?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            detail?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** 论述限时练习的入口；正在写的那篇在这里显示剩余时间。 */
@Composable
private fun RowScope.EssayEntry(c: CoachController, wide: Boolean, onOpen: () -> Unit) {
    val session = c.essay
    var now by remember { mutableStateOf(c.nowMillis()) }
    LaunchedEffect(session) { while (session != null && !session.handedIn) { now = c.nowMillis(); delay(1000) } }
    val label = when {
        session == null -> "开始一篇"
        session.handedIn -> "看批改"
        else -> {
            val left = ((session.deadline - now + 999) / 1000).coerceAtLeast(0)
            "继续写（剩 ${left / 60}:${(left % 60).toString().padStart(2, '0')}）"
        }
    }
    ShortcutTile("论述限时练习", "限时写一篇，结构、论点、证据、例子逐项批改".takeIf { wide }, "essay-entry", onOpen, status = label)
}

/** Independent from the learning thread: a downloaded review never changes Continue. */
@Composable
private fun OfflinePractice(c: CoachController, draft: OfflineDraft) {
    val revision by c.sync.revision.collectAsState()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(c.nowMillis()) }
    LaunchedEffect(c) { while (true) { tick = c.nowMillis(); delay(1000) } }
    val groups = remember(revision,tick) { c.sync.offlineGroups(c.courseId,tick) }
    var active by draft::active
    var chosen by draft::chosen
    var error by draft::error
    var downloading by draft::downloading
    val group = groups.firstOrNull { it.groupId == active }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("离线练习", style = MaterialTheme.typography.titleMedium)
        if (group == null) {
            Text("${groups.filter { !it.submitted }.sumOf { it.cards.size }} 题可做 · 存到本机后没网也能做")
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
                },enabled = !downloading,modifier = Modifier.heightIn(min = 56.dp)) { Text(if (downloading) "正在存到本机…" else "存到本机") }
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
