package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coach.client.CourseHttp
import com.coach.client.CourseView
import com.coach.client.MockReply
import com.coach.domain.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlin.random.Random

/**
 * 选择题主线：选一周，按铺垫 → 细节 → 桥接一道一道做。答完讲解就在同一页展开，不用再点；
 * 题和答案键都在 Mac 上，答之前手机拿不到答案。选项顺序由 Mac 打乱，这里照收到的顺序显示、按屏幕位置提交。
 */
@Composable
fun MainlineScreen(c: CoachController, view: CourseView, onBack: () -> Unit, onOpenSteps: () -> Unit, onOpenEssay: () -> Unit, onReviewWeek: (Int) -> Unit) {
    val http = c.course
    var week by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize()) {
        when {
            http == null -> { Header("选择题主线", "← 复习", onBack); CenterMessage("选择题主线要连接 Mac。") }
            week == null -> MainlineHome(c, http, onBack) { week = it }
            else -> key(week) { MainlineWeek(c, http, view, week!!, onBack = { week = null }, onOpenSteps, onOpenEssay, onReviewWeek) }
        }
    }
}

@Composable
private fun Header(title: String, back: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(back) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    HorizontalDivider()
}

@Composable
private fun MainlineHome(c: CoachController, http: CourseHttp, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val revision by c.sync.revision.collectAsState()
    val reply by produceState<MockReply?>(null, revision) { value = http.mainline() }
    Header("选择题主线", "← 复习", onBack)
    val body = reply?.body
    when {
        reply == null -> CenterMessage("正在读取…")
        body == null -> CenterMessage(reply!!.error ?: "连不上 Mac，读不到选择题主线。")
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val weeks = (body["weeks"] as? JsonArray).orEmpty().map { it.jsonObject }
            if (weeks.isEmpty()) Text("还没有生成选择题主线。", style = MaterialTheme.typography.bodyMedium)
            else Text("选一周，从铺垫题开始。答对且不是猜的才算掌握。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (w in weeks) {
                val n = w.num("week").toInt()
                Surface(onClick = { c.tutor.telemetry?.record("tap", "mainline_week"); onOpen(n) }, tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("mainline-week-$n")) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("第 $n 周 · ${w.str("title")}", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        CoverageLines(w["coverage"] as? JsonObject ?: JsonObject(emptyMap()))
                    }
                }
            }
            for (bad in (body["invalid"] as? JsonArray).orEmpty().map { it.jsonObject })
                Text("${bad.str("file")} 读不了：${bad.str("reason")}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 覆盖面板：这周有多少知识点、多少有题、多少已掌握（答对且没标猜的）。 */
@Composable
private fun CoverageLines(cov: JsonObject, modifier: Modifier = Modifier) {
    val facts = cov.num("facts"); val mastered = cov.num("factsMastered")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("知识点 $facts · 有题 ${cov.num("factsWithQuestions")} · 已掌握 $mastered", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("mainline-coverage"))
        LinearProgressIndicator(progress = { if (facts == 0L) 0f else mastered.toFloat() / facts }, modifier = Modifier.fillMaxWidth())
        Text("题 ${cov.num("answered")}/${cov.num("questions")} 已做 · ${cov.num("mastered")} 题掌握" +
            (cov.num("keysPending").takeIf { it > 0 }?.let { " · $it 题答案键待核对" } ?: ""),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val tierNames = mapOf("setup" to "铺垫题", "detail" to "细节题", "bridge" to "桥接题")

@Composable
private fun MainlineWeek(c: CoachController, http: CourseHttp, view: CourseView, number: Int, onBack: () -> Unit,
    onOpenSteps: () -> Unit, onOpenEssay: () -> Unit, onReviewWeek: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    val reply by produceState<MockReply?>(null, reload) { value = http.mainlineWeek(number) }
    val body = reply?.body
    val items = remember(body) { (body?.get("items") as? JsonArray).orEmpty().map { it.jsonObject } }
    var index by remember(body) { mutableStateOf(items.indexOfFirst { it.str("status") != "mastered" }.coerceAtLeast(0)) }
    var coverage by remember(body) { mutableStateOf(body?.get("coverage") as? JsonObject) }
    var chosen by remember(index, body) { mutableStateOf<Int?>(null) }
    var result by remember(index, body) { mutableStateOf<JsonObject?>(null) }
    // One id per answer on screen: a resend after a dropped connection is the same answer, not a second one.
    var submission by remember(index, body) { mutableStateOf(Random.nextLong().toULong().toString(16)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val now = rememberCourseNow(c)
    val scroll = rememberScrollState()
    LaunchedEffect(index) { scroll.scrollTo(0) }
    Header("第 $number 周 · 选择题", "← 选周", onBack)
    when {
        reply == null -> { CenterMessage("正在读取…"); return }
        body == null -> { CenterMessage(reply!!.error ?: "连不上 Mac，读不到这一周的题。"); return }
        items.isEmpty() -> { CenterMessage("这一周还没有题。"); return }
    }
    val item = items[index]
    val options = item.strings("options")
    val letters = optionLetters(options.size)
    fun submit(guessed: Boolean) {
        val choice = chosen ?: return
        if (busy) return
        busy = true; error = null
        c.tutor.telemetry?.record("tap", if (guessed) "mainline_guess" else "mainline_answer")
        scope.launch {
            try {
                val r = http.answerMainline(number, item.str("id"), choice, guessed, submission, (item["layout"] as? JsonPrimitive)?.intOrNull)
                val b = r.body
                if (b != null) { result = b; (b["coverage"] as? JsonObject)?.let { coverage = it } }
                else error = r.error ?: "连不上 Mac，这次没交上；再点一次会接着交，不会算两次。"
            } finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        coverage?.let { CoverageLines(it) }
        HorizontalDivider()
        Text("${tierNames[item.str("tier")] ?: "题"} · 第 ${index + 1}/${items.size} 题", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        QuizText(item.str("stem"), "mainline:${item.str("id")}", c.math)
        val done = result
        val explanation = done?.get("explanation") as? JsonObject
        val reasons = explanation?.get("options") as? JsonObject
        val answer = done?.num("answer")?.toInt()
        // A lost response may be replayed after a local edit: the recorded choice and outcome belong together.
        val displayedChoice = done?.num("choice")?.toInt() ?: chosen
        options.forEachIndexed { i, option ->
            val mark = when {
                done == null -> null
                i == answer -> "✓"
                i == displayedChoice -> "✗"
                else -> null
            }
            Surface(tonalElevation = if (done != null && i == answer) 3.dp else 0.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().selectable(displayedChoice == i, enabled = done == null && !busy, role = Role.RadioButton) { chosen = i }.padding(vertical = 4.dp).testTag("mainline-option-$i")) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RadioButton(selected = displayedChoice == i, onClick = null, enabled = done == null)
                        Text(letters[i], style = MaterialTheme.typography.titleSmall)
                        Box(Modifier.weight(1f)) { QuizText(option, "mainline:${item.str("id")}:$i", c.math) }
                        mark?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = if (it == "✓") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                    }
                    val why = if (done == null) null else if (i == answer) explanation?.str("correct") else (reasons?.get("$i") as? JsonPrimitive)?.contentOrNull
                    if (!why.isNullOrBlank()) Text((if (i == answer) "为什么对：" else "为什么不选：") + why, Modifier.padding(start = 48.dp, end = 8.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (done == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { submit(false) }, enabled = chosen != null && !busy, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("mainline-submit")) { Text("提交") }
                OutlinedButton(onClick = { submit(true) }, enabled = chosen != null && !busy, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("mainline-guess")) { Text("我是猜的") }
            }
            Text("拿不准就点「我是猜的」：答对也不算掌握，会排进复习。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Outcome(done, view.logicalToday(now), onReviewWeek)
            explanation?.str("background")?.takeIf { it.isNotBlank() }?.let { Labeled("背景", it) }
            (explanation?.str("source")?.takeIf { it.isNotBlank() } ?: done.str("source").takeIf { it.isNotBlank() })?.let { Labeled("出处", it) }
            val next = done.str("next").takeIf { it.isNotBlank() }
            if (done.str("tier") == "bridge") {
                Text("桥接题连着大题：接着练一道步骤批改，或写一篇限时论述。", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenSteps, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("mainline-steps")) { Text("步骤批改") }
                    OutlinedButton(onClick = onOpenEssay, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("mainline-essay")) { Text("限时论述") }
                }
            }
            Button(onClick = {
                val at = next?.let { id -> items.indexOfFirst { it.str("id") == id } } ?: -1
                if (at >= 0) index = at else onBack()
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("mainline-next")) { Text(if (next != null) "下一题" else "这一周做完了") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Labeled(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 这道题记成什么、什么时候再看、进没进错题本。 */
@Composable
private fun Outcome(r: JsonObject, today: Long, onReviewWeek: (Int) -> Unit) {
    val due = (r["dueDay"] as? JsonPrimitive)?.longOrNull
    val when_ = due?.let { when (val d = it - today) { 0L -> "今天"; 1L -> "明天"; else -> if (d < 0) "今天" else "$d 天后" } }
    val head = when (r.str("status")) {
        "mastered" -> "答对了，算掌握。"
        "guessed" -> "答对了，但标了猜的：不算掌握。"
        "pending" -> "这题答案键还没核对，这次不记对错。"
        else -> "答错了。"
    }
    val queued = when {
        r.str("kpId").isBlank() -> if (r.str("status") == "mastered") null else "这道题没对上大纲里的知识点，没能排进复习。"
        r.str("status") == "mastered" -> null
        when_ != null -> "已排进复习：${when_}再看一次。"
        else -> null
    }
    val week = (r["mistakeWeek"] as? JsonPrimitive)?.intOrNull
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("mainline-outcome")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(head, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                color = if (r.str("status") == "missed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            queued?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (week != null) TextButton(onClick = { onReviewWeek(week) }, contentPadding = PaddingValues(0.dp)) { Text("已进第 $week 周错题本 ›") }
        }
    }
}
