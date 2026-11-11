package com.coach.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coach.client.*
import com.coach.domain.*
import com.coach.protocol.CommandBody
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** 正在写（或刚交卷）的一篇。[deadline] 是本机时钟：从 Mac 发回题目那一刻起算。 */
internal class EssaySession(val attemptId: String, val posed: JsonObject, val deadline: Long) {
    var text by mutableStateOf("")
    var handedIn by mutableStateOf(false)
    /** 交卷没有当场送到 Mac 的原因；命令已在本机排队，连上后自动送达。 */
    var note by mutableStateOf<String?>(null)
}

/**
 * 论述限时练习：选题库里的题或自己贴题 → 限时写作（倒计时、实时字数，到点自动交卷）→ 结构、论点、证据、例子逐项批改与改写示范。
 * 练习走自己的线程，不改「继续」；结果记在账本里，记录页「限时论述」可以回看。
 */
@Composable
fun EssayScreen(c: CoachController, view: CourseView, onBack: () -> Unit, backLabel: String = "← 复习") {
    val session = c.essay
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(backLabel) }
            Text("论述限时练习", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (session != null && !session.handedIn) Countdown(c, session)
        }
        HorizontalDivider()
        when {
            session == null -> EssayPicker(c, c::startEssay)
            !session.handedIn -> EssayWriting(c, session)
            else -> EssayResult(c, view, session, c::closeEssay)
        }
    }
}

@Composable
private fun Countdown(c: CoachController, s: EssaySession) {
    val left = remainingSec(c, s)
    Text(minSec(left), Modifier.testTag("essay-countdown"), style = MaterialTheme.typography.headlineSmall,
        color = if (left <= 120) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun remainingSec(c: CoachController, s: EssaySession): Long {
    var now by remember(s) { mutableStateOf(c.nowMillis()) }
    LaunchedEffect(s) { while (true) { now = c.nowMillis(); delay(250) } }
    return ((s.deadline - now + 999) / 1000).coerceAtLeast(0)
}

private fun minSec(sec: Long) = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"

@OptIn(ExperimentalUuidApi::class, ExperimentalLayoutApi::class)
@Composable
private fun EssayPicker(c: CoachController, onStarted: (EssaySession) -> Unit) {
    val scope = rememberCoroutineScope()
    var bank by remember { mutableStateOf<EssayBankView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(c) {
        bank = runCatching { withContext(Dispatchers.IO) { c.course?.essays() } }.getOrNull()
        loaded = true
    }
    var pasting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    var stem by remember { mutableStateOf("") }
    var week by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val minutes = (c.essayLimitSec + 59) / 60
    val prompts = bank?.prompts.orEmpty()
    val weeks = bank?.weeks.orEmpty()
    LaunchedEffect(loaded, prompts.isEmpty()) { if (loaded && prompts.isEmpty()) pasting = true }
    val ready = !busy && if (pasting) stem.trim().length in 15..4000 && week != null else picked != null

    fun start() {
        val attemptId = Uuid.random().toString()
        val payload = if (pasting) json("attemptId" to attemptId, "stem" to stem.trim(), "week" to week, "timeLimitSec" to c.essayLimitSec, "threadId" to "essay:$attemptId")
            else json("attemptId" to attemptId, "essayId" to picked, "timeLimitSec" to c.essayLimitSec, "threadId" to "essay:$attemptId")
        c.tutor.telemetry?.record("tap", "essay_start")
        busy = true; error = null
        scope.launch {
            try {
                val receipt = withContext(Dispatchers.IO) {
                    val cmd = c.sync.enqueue(CommandBody("pose_essay", c.courseId, c.nowMillis(), payload))
                    c.sync.syncOnce()
                    c.sync.receipt(cmd.commandId)
                }
                val posed = receipt?.result?.get("posedSnapshot") as? JsonObject
                when {
                    receipt == null -> error = "Mac 未连接，题目发不出来；连上后再开始。"
                    receipt.status !in setOf("OK", "DUPLICATE") || posed == null -> error = "这道题现在练不了（题库刚更新，或这一周还没有知识点）。换一道试试。"
                    // 计时从题目到手这一刻开始：Mac 判超时用它自己的时钟，另有 ${Essay.GRACE_SEC} 秒宽限。
                    else -> onStarted(EssaySession(attemptId, posed, c.nowMillis() + c.essayLimitSec * 1000L))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Mac 未连接，题目发不出来；连上后再开始。" }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$minutes 分钟写一篇，目标约 ${Essay.WORD_TARGET} 词。交卷后按结构、论点、证据、例子逐项给分，每项都引用你写的原句，再给一段在你论证上的改写示范。",
            style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !pasting, onClick = { pasting = false }, enabled = prompts.isNotEmpty(), label = { Text("题库") }, modifier = Modifier.heightIn(min = 48.dp))
            FilterChip(selected = pasting, onClick = { pasting = true }, label = { Text("自己贴题") }, modifier = Modifier.heightIn(min = 48.dp))
        }
        if (!pasting) {
            if (!loaded) Text("正在从 Mac 取题库…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            prompts.forEach { p ->
                val selected = picked == p.id
                Surface(onClick = { picked = p.id }, tonalElevation = if (selected) 6.dp else 1.dp, shape = MaterialTheme.shapes.medium,
                    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("essay-prompt")) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("第 ${p.week} 周 · ${trimMarks(p.marks)} 分" + if (p.fromMockPaper) " · 模拟卷里的题" else "",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(p.stem, style = MaterialTheme.typography.bodyMedium, maxLines = if (selected) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if ((bank?.unmatched ?: 0) > 0) Text("还有 ${bank!!.unmatched} 道题所在的周课程里还没有知识点，暂时不列。",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            if (loaded && bank == null) Text("取不到 Mac 上的题库（Mac 未连接，或服务还没更新）。也可以先贴一道题。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(stem, { stem = it }, Modifier.fillMaxWidth().testTag("essay-stem"), label = { Text("题目（英文原样贴进来）") }, minLines = 3)
            Text("属于第几周", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                weeks.forEach { w -> FilterChip(selected = week == w, onClick = { week = w }, label = { Text("第 $w 周") }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            if (weeks.isEmpty() && loaded) Text("要连上 Mac 才知道课程有哪几周。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("自己贴的题没有核验过的参考答案：照样逐项批改、给示范，但不计入掌握。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = ::start, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(if (busy) "正在发题…" else "开始限时写作（$minutes 分钟）")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private fun trimMarks(x: Double) = if (x == x.toLong().toDouble()) x.toLong().toString() else x.toString()

@Composable
private fun EssayWriting(c: CoachController, s: EssaySession) {
    val words = Essay.wordCount(s.text)
    // 到点交卷由 controller 盯着（CoachController.startEssay），这一页只管写。
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
            Text(s.posed.str("stem"), style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$words / 约 ${s.posed.num("wordTarget", Essay.WORD_TARGET.toLong())} 词", Modifier.testTag("essay-words"), style = MaterialTheme.typography.labelLarge,
                color = if (words >= s.posed.num("wordTarget", Essay.WORD_TARGET.toLong()) * 0.8) successColor() else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("到点自动交卷", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(s.text, { if (!s.handedIn) { s.text = it; c.persistEssay() } }, Modifier.fillMaxWidth().weight(1f).testTag("essay-text"),
            placeholder = { Text("先一句话直接回答，再定义、机制、证据（作者、年份、数字）、评价、结论。") })
        Button(onClick = { c.handInEssay(s) }, enabled = s.text.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("交卷") }
    }
}

@Composable
private fun EssayResult(c: CoachController, view: CourseView, s: EssaySession, onAgain: () -> Unit) {
    val detail = remember(view, s.attemptId) { RecordsView(view.events).attempt(s.attemptId)?.takeIf { it.submittedAt != null } }
    val pending = detail == null || detail.grade == null || detail.review == null
    // 批改在 Mac 后台跑：等结果期间隔几秒同步一次，出了就显示。
    LaunchedEffect(s.attemptId, pending) {
        while (pending) { delay(5000); runCatching { withContext(Dispatchers.IO) { c.sync.syncOnce() } } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val status = when {
            detail == null -> s.note ?: "已交卷，正在送到 Mac…"
            detail.grade == null -> "正在批改：结构、论点、证据、例子逐项给分，一般一两分钟。"
            detail.review == null -> "初评出来了，正在由另一家模型复核；复核后的分数才算数。"
            else -> null
        }
        if (status != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.note == null || detail != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(status, Modifier.testTag("essay-status"), style = MaterialTheme.typography.bodyMedium)
        }
        if (detail != null) {
            val used = (detail.submittedAt ?: 0L) - s.posed.num("posedAt")
            Text("用时 ${minSec((used / 1000).coerceAtLeast(0))} · ${Essay.wordCount(detail.answer)} 词", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            AttemptCard(c.math, c.course != null, { id -> c.course?.attemptRecord(id)?.let(::rubricFrom) }, 1, 1, detail)
        } else if (s.note != null) {
            Text("你写的原文", style = MaterialTheme.typography.labelLarge)
            Text(s.text, style = MaterialTheme.typography.bodyLarge)
        }
        Button(onClick = onAgain, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("再写一篇") }
        Text("以后在「记录 · 限时论述」里也能回看这篇。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
