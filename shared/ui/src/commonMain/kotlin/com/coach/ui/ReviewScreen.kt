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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.client.CourseView
import com.coach.client.NoVariantCandidates
import com.coach.client.ReviewBoard
import com.coach.client.ReviewItem
import com.coach.client.reviewBoard
import com.coach.client.reviewCursor
import com.coach.client.saveReviewCursor
import com.coach.domain.*
import com.coach.domain.reviewqueue.VariantCandidatePort
import kotlinx.serialization.json.*

/**
 * 复习入口：到期日程、答错、猜对合成一队（身份由已验队列合同去重），开始即进独立复习线程。
 * 队列是账本回放出来的纯函数结果，离开再回来会接着上次点开的那一项。
 *
 * [variantCandidates] 是题库候选端口：本机有题级候选通道时从它取同考点、已核键的新题；
 * 默认 [NoVariantCandidates] 表示这台设备还没有这条通道，缺口保持「待生成变式」，不造题。
 *
 * 点「开始」只做两件事：把这一行的变式作答身份写进本机光标，再用 [onOpenKp] 打开考点。
 * 练习路由目前只收到 `kpId` 和 `startNow`，还没有读取这个身份的消费者，所以这里能证的是
 * 「候选注入 → 分配 → 显示 → 本机保存身份」，不是「练习真的开始那道变式」。
 */
@Composable
fun ReviewScreen(c: CoachController, view: CourseView, onOpenKp: (kpId: String, startNow: Boolean) -> Unit, onBack: (() -> Unit)?, onOpenMock: (() -> Unit)? = null, onOpenEssay: (() -> Unit)? = null, onOpenMainline: ((Int?, Boolean) -> Unit)? = null, reminderSettings: @Composable () -> Unit = {}, variantCandidates: VariantCandidatePort = NoVariantCandidates) {
    val now = rememberCourseNow(c)
    val day = view.logicalToday(now)
    // 账本回放一次一块，只在换天或账本变（view 是新实例）时重算，不跟着秒针每秒重放。
    val board = remember(view, day, variantCandidates) { reviewBoard(c.courseId, view, day, variantCandidates) }
    var cursor by remember(view, day) { mutableStateOf(reviewCursor(c.sync, c.courseId)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 16.dp else 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("← 今天") }
            Text("复习", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            reminderSettings()
        }
        HorizontalDivider()
        // The entry cards and due queue share the available height. Keep them in
        // one scroll so short windows cannot squeeze the queue out of reach.
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(examReviewNotice(view, now), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            OfflinePractice(c)
            if (onOpenMainline != null) MainlineEntry(c, view, onOpenMainline)
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
            if (board.snapshotRequired) {
                // 账本里有这一版读不了的记录：不能猜、也不能显示成「没有复习」。
                CenterMessage("今天复习什么还确认不了。")
                Text(
                    "这台设备上有当前版本读不了的账本记录，要先取服务端的权威快照才能排。" +
                        "本机已经做过的练习还在，不会丢；连上服务端同步后这里会重新列出要复习的题。",
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            } else if (board.items.isEmpty()) {
                CenterMessage("今天没有到期的复习。")
                // 低频/无题也要说得清：是还没到期、还有未核题，还是根本还没有可复习的题。
                Text(emptyReviewHint(board), Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } else {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(board.headline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (board.unverifiedAnswered > 0) Text("另有 ${board.unverifiedAnswered} 道题答案还没核，只提醒再看一次，不计分",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 同一知识点可能同时有「错题」和「未知原题的到期」两行；标签要唯一，首行沿用旧 review-<kpId>。
                    val rowOfKp = mutableMapOf<String, Int>()
                    board.items.forEach { item ->
                        key(item.entryId) {
                            val occurrence = rowOfKp.getOrElse(item.kp.id) { 0 }
                            rowOfKp[item.kp.id] = occurrence + 1
                            val rowTag = if (occurrence == 0) "review-${item.kp.id}" else "review-${item.kp.id}-${occurrence + 1}"
                            val resumed = item.entryId == cursor
                            val (section, main) = splitTitle(item.kp.title)
                            Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag(rowTag)) {
                                Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        if (section.isNotBlank()) Text(sourceLine(section), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(main, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        Text((if (item.revisit) "再看一次 · " else "") + if (item.overdueDays == 0) "今天到期" else "逾期 ${item.overdueDays} 天", style = MaterialTheme.typography.labelMedium, color = if (item.overdueDays == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                                        Text(reviewItemMeta(item), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        if (resumed) Text("上次练到这里", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Button(onClick = {
                                        saveReviewCursor(c.sync, c.courseId, item.entryId, board.day, item.variantAttemptId)
                                        cursor = item.entryId
                                        onOpenKp(item.kp.id, true)
                                    }, modifier = Modifier.heightIn(min = 56.dp)) { Text(if (resumed) "继续" else "开始") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 低频或没题时，说清是哪一种「没有」，而不是只留一句空话。 */
private fun emptyReviewHint(board: ReviewBoard): String = board.nextDueDay?.let { "下一次复习在 ${dayLabel(it)}。" }
    ?: if (board.unverifiedAnswered > 0) "还有 ${board.unverifiedAnswered} 道题答案没核，不计分，所以没排进错题队列。"
    else "还没有排进复习的题：先学一节或做一组题，答错、猜对的会自动排进来。"

/** 来源 · 原题/变式身份 · 先修 · 考纲权重：只写账本里真实有的输入，缺什么就不写什么。 */
private fun reviewItemMeta(item: ReviewItem): String = listOfNotNull(
    item.reasonLabel.takeIf { it.isNotBlank() },
    item.identityLabel,
    item.prereqTitles.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "先修："),
    item.examWeight.takeIf { it > 0 }?.let { "考纲权重 $it" },
).joinToString(" · ")

/** Existing Review entry: one click to the current week's question, a separate reading route. */
@Composable
private fun MainlineEntry(c: CoachController, view: CourseView, onOpen: (Int?, Boolean) -> Unit) {
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
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("open-mainline")) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("选择题主线", style = MaterialTheme.typography.bodyLarge)
            Text("按周从基础题做起，答完立刻看讲解", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Swap the placeholder for the real entry instead of renaming one button in place: the label of the
                // week is then carried by a button that already exists, so the screen reader hears the current week.
                if (number != null) {
                    Button(onClick = { onOpen(number, true) }, modifier = Modifier.testTag("mainline-continue")) { Text("继续第 $number 周") }
                    TextButton(onClick = { onOpen(number, false) }) { Text("看本周要点") }
                } else {
                    Text("正在连接…", Modifier.padding(vertical = 14.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onOpen(null, false) }) { Text("选其他周") }
            }
            if (number == null && error != null) Text("Mac 暂时不可用，正在自动重连…", color = MaterialTheme.colorScheme.error)
            else if (overview != null && weeks.isEmpty()) Text("还没有生成选择题主线。")
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
