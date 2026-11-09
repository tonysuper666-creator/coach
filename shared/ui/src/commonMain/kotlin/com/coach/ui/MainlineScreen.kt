package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.coach.ui.v7.*
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import coach.design.tokens.CoachV7
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.coach.client.CourseHttp
import com.coach.client.CourseView
import com.coach.client.MockReply
import com.coach.client.SubmissionTrace
import com.coach.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.random.Random

/**
 * 选择题主线：选一周，按铺垫 → 细节 → 桥接一道一道做。答完讲解就在同一页展开，不用再点；
 * 题和答案键都在 Mac 上，答之前手机拿不到答案。选项顺序由 Mac 打乱，这里照收到的顺序显示、按屏幕位置提交。
 */
@Composable
fun MainlineScreen(c: CoachController, view: CourseView, onBack: () -> Unit, onOpenSteps: () -> Unit, onOpenEssay: () -> Unit, onReviewWeek: (Int) -> Unit) {
    val http = c.course
    var week by remember { mutableStateOf((c.sync.mainlineState(c.courseId)["week"] as? JsonPrimitive)?.intOrNull) }
    var entry by remember { mutableStateOf(c.sync.mainlineState(c.courseId).str("entry").ifBlank { null }) }
    fun openWeek(n: Int, quiz: Boolean) {
        entry = if (quiz) "quiz" else "notes"; week = n
        c.sync.saveMainlineState(c.courseId, JsonObject(c.sync.mainlineState(c.courseId) + mapOf("week" to JsonPrimitive(n), "entry" to JsonPrimitive(entry))))
    }
    when {
        http == null -> com.coach.ui.v7.Desk(crumb = "选择题主线", onBack = onBack, backLabel = "← 复习") {
            Column { Header("选择题主线", "← 复习", onBack); CenterMessage("选择题主线要连接 Mac。") }
        }
        week == null -> com.coach.ui.v7.Desk(crumb = "选择题主线", onBack = onBack, backLabel = "← 复习",
            directory = { com.coach.ui.v7.WeekDirectory(view, null, null) }) {
            Column(Modifier.fillMaxSize()) { MainlineHome(c, http, onBack, ::openWeek) }
        }
        else -> key(week) { MainlineWeek(c, http, view, week!!, entry, onBack = { week = null; c.sync.saveMainlineState(c.courseId, JsonObject(c.sync.mainlineState(c.courseId) - "week")) }, onOpenSteps, onOpenEssay, onReviewWeek) }
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
private fun MainlineHome(c: CoachController, http: CourseHttp, onBack: () -> Unit, onOpen: (Int, Boolean) -> Unit) {
    val revision by c.sync.revision.collectAsState()
    val reply by produceState<MockReply?>(null, revision) {
        // Blocking endpoint: fetch off the composition dispatcher so the screen can keep drawing while it waits.
        do { val r = withContext(Dispatchers.IO) { http.mainline() }; value = r; if (!r.retryable) break; delay(1500) } while (true)
    }
    Header("选择题主线", "← 复习", onBack)
    val body = reply?.body
    when {
        reply == null -> CenterMessage("正在读取…")
        body == null -> CenterMessage(reply!!.error ?: "连不上 Mac，正在自动重连…")
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val weeks = (body["weeks"] as? JsonArray).orEmpty().map { it.jsonObject }
            if (weeks.isEmpty()) Text("还没有生成选择题主线。", style = MaterialTheme.typography.bodyMedium)
            else Text("选一周，从铺垫题开始。答对且不是猜的才算掌握。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (w in weeks) {
                val n = w.num("week").toInt()
                Surface(onClick = { c.tutor.telemetry?.record("tap", "mainline_week"); onOpen(n, false) }, tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("mainline-week-$n")) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("第 $n 周 · ${w.str("title")}", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        CoverageLines(w["coverage"] as? JsonObject ?: JsonObject(emptyMap()))
                        TextButton(onClick = { onOpen(n, true) }, modifier = Modifier.testTag("mainline-direct-$n")) { Text("直接做题") }
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
private fun MainlineWeek(c: CoachController, http: CourseHttp, view: CourseView, number: Int, entry: String?, onBack: () -> Unit,
    onOpenSteps: () -> Unit, onOpenEssay: () -> Unit, onReviewWeek: (Int) -> Unit, only: Set<String>? = null) {
    val scope = rememberCoroutineScope()
    var readerState by remember { mutableStateOf(http.readerState(c.sync, number)) }
    // Every reader write goes through a transform of the value held here instead of a whole object captured in an
    // older composition: a control tapped one frame after another would otherwise re-save its stale snapshot and
    // resurrect a flag the first tap just cleared (exiting focus mode and then touching the theme brought focus back,
    // which hides the weekly map).
    fun saveReader(transform: (JsonObject) -> JsonObject) {
        val next = transform(readerState); readerState = next; http.saveReaderState(c.sync, number, next)

    }
    val marks = (readerState["marks"] as? JsonArray).orEmpty().map { it.jsonObject }
    DisposableEffect(Unit) { onDispose { c.readerFocus = false } }
    // Page practice must not overwrite the answer behind the citation overlay.
    val stateKey = if (only == null) "week:$number" else "pagePractice:$number:${only.sorted().joinToString(",")}"
    var saved by remember { mutableStateOf(c.sync.mainlineState(c.courseId, stateKey)) }
    fun save(vararg fields: Pair<String, JsonElement>) {
        saved = JsonObject(saved + fields)
        c.sync.saveMainlineState(c.courseId, saved, stateKey)
    }
    var body by remember { mutableStateOf(saved["body"] as? JsonObject) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryable by remember { mutableStateOf(true) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(retry) {
        if (body == null) do {
            val r = withContext(Dispatchers.IO) { http.mainlineWeek(number) }
            error = r.error ?: if (r.body == null) "连不上 Mac，正在自动重连…" else null
            retryable = r.retryable
            if (r.body != null) { body = r.body; save("body" to r.body!!); break }
            if (!r.retryable) break
            delay(1500)
        } while (true)
    }
    val items = (body?.get("items") as? JsonArray).orEmpty().map { it.jsonObject }.filter { only == null || it.str("id") in only }
    val sections = (body?.get("notes") as? JsonArray).orEmpty().map { it.jsonObject }
    val emphasis = (body?.get("emphasis") as? JsonArray).orEmpty().map { it.jsonObject }
    var reading by remember { mutableStateOf(only == null && (entry?.let { it == "notes" } ?: (saved.str("mode") != "quiz"))) }
    var sectionId by remember { mutableStateOf(saved.str("section")) }
    var filter by remember { mutableStateOf(if (entry == "quiz") "" else saved.str("filter")) }
    var itemId by remember { mutableStateOf(saved.str("item")) }
    var chosen by remember { mutableStateOf((saved["choice"] as? JsonPrimitive)?.intOrNull) }
    var result by remember { mutableStateOf(saved["result"] as? JsonObject) }
    // A fresh answer reveals its feedback once; restoring an answer keeps the reader's scroll.
    var revealFeedback by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(saved["pending"] as? JsonObject) }
    val busy = pending != null
    val now = rememberCourseNow(c)
    val section = sections.firstOrNull { it.str("section_id") == sectionId } ?: sections.firstOrNull()
    val filtered = if (filter.isBlank()) items else {
        val facts = sections.firstOrNull { it.str("section_id") == filter }?.strings("fact_ids").orEmpty()
        items.filter { q -> q.strings("factIds").any { it in facts } }
    }
    val index = filtered.indexOfFirst { it.str("id") == itemId }.takeIf { it >= 0 }
        ?: filtered.indexOfFirst { it.str("status") != "mastered" }.coerceAtLeast(0)
    val item = filtered.getOrNull(index)
    val scroll = rememberScrollState(saved.num("quizScroll").toInt())
    val readingScroll = rememberScrollState(saved.num("readScroll").toInt())
    LaunchedEffect(reading) {
        snapshotFlow { if (reading) readingScroll.value else scroll.value }.collect { offset ->
            save((if (reading) "readScroll" else "quizScroll") to JsonPrimitive(offset))
        }
    }
    fun position(mode: Boolean, id: String = itemId, section: String = sectionId, selectedFilter: String = filter) {
        if (mode && only != null) { onBack(); return }
        reading = mode; itemId = id; sectionId = section; filter = selectedFilter
        save("mode" to JsonPrimitive(if (mode) "notes" else "quiz"), "item" to JsonPrimitive(id),
            "section" to JsonPrimitive(section), "filter" to JsonPrimitive(selectedFilter))
    }
    LaunchedEffect(Unit) {
        save("mode" to JsonPrimitive(if (reading) "notes" else "quiz"), "filter" to JsonPrimitive(filter))
        if (only == null) c.sync.saveMainlineState(c.courseId, JsonObject(c.sync.mainlineState(c.courseId) - "entry"))
    }
    LaunchedEffect(item?.str("id")) { if (item != null && itemId != item.str("id")) position(reading, item.str("id")) }
    LaunchedEffect(pending, retry) {
        val wire = pending ?: return@LaunchedEffect
        val traceId = wire.str("submissionId")
        SubmissionTrace.mark(traceId, "ui.effectStart")
        do {
            val r = withContext(Dispatchers.IO) {
                SubmissionTrace.mark(traceId, "ui.ioStart")
                http.answerMainline(number, wire.str("itemId"), wire.num("choice").toInt(), wire.flag("guessed"), wire.str("submissionId"),
                    (wire["layout"] as? JsonPrimitive)?.intOrNull).also { SubmissionTrace.mark(traceId, "ui.answerReturned") }
            }
            SubmissionTrace.mark(traceId, "ui.mainResumed")
            val b = r.body
            if (b != null) {
                result = b; pending = null; error = null
                SubmissionTrace.mark(traceId, "ui.resultAssigned")
                val updatedItems = JsonArray(items.map { q ->
                    if (q.str("id") == b.str("itemId")) JsonObject(q + ("status" to JsonPrimitive(b.str("status")))) else q
                })
                body = JsonObject(body.orEmpty() + listOfNotNull("items" to updatedItems,
                    b["coverage"]?.let { "coverage" to it }, b["notes"]?.let { "notes" to it }))
                save("result" to b, "pending" to JsonNull, "body" to body!!)
                revealFeedback = true
                SubmissionTrace.mark(traceId, "ui.persisted")
                break
            }
            retryable = r.retryable
            error = if (r.retryable) (r.error?.let { "$it · " } ?: "") + "答案已保存在本机，正在自动重连；不会重复计分。" else r.error
            if (!r.retryable) break
            delay(1500)
        } while (true)
    }
    fun submit(guessed: Boolean) {
        val choice = chosen ?: return
        if (busy || item == null) return
        val wire = json("itemId" to item.str("id"), "choice" to choice, "guessed" to guessed,
            "submissionId" to Random.nextLong().toULong().toString(16), "layout" to item["layout"])
        // Commit the immutable payload and original displayed options BEFORE any network I/O.
        save("pending" to wire, "item" to JsonPrimitive(item.str("id")), "body" to body!!)
        SubmissionTrace.mark(wire.str("submissionId"), "ui.pendingSaved")
        pending = wire; error = null
    }
    fun startQuiz(selectedFilter: String = "") {
        if (pending != null) { position(false); return }
        if (selectedFilter != filter) {
            chosen = null; result = null
            save("choice" to JsonNull, "result" to JsonNull)
        }
        position(false, if (selectedFilter == filter) itemId else "", selectedFilter = selectedFilter)
    }
    LaunchedEffect(reading, readerState.flag("focus")) { c.readerFocus = reading && readerState.flag("focus") }
    if ((reading && !readerState.flag("focus")) || (!reading && (body == null || item == null))) Header("第 $number 周 · ${if (reading) "本周要点" else "选择题"}", "← 选周", onBack)
    if (body == null) {
        CenterMessage(error ?: "正在读取…")
        if (!retryable) TextButton(onClick = { retry++ }) { Text("重试") }
        return
    }
    if (reading) {
        val currentItems = items.map { q -> if (q.str("id") == result?.str("itemId")) JsonObject(q + ("status" to JsonPrimitive(result!!.str("status")))) else q }
        WeeklyReader(c, number, sections, section, currentItems, marks, readerState, readingScroll, body!!.str("notesError"),
            ::saveReader, onSection = { id -> position(true, section = id); scope.launch { readingScroll.scrollTo(0) } },
            onQuiz = { sectionFilter, id ->
                startQuiz(sectionFilter)
                if (id != null && pending == null) {
                    if (id != itemId) { chosen = null; result = null; save("choice" to JsonNull, "result" to JsonNull) }
                    position(false, id, selectedFilter = sectionFilter)
                    scope.launch { scroll.scrollTo(0) }
                }
            }, onRefresh = {
                if (pending == null) {
                body = null; result = null; chosen = null
                save("body" to JsonNull, "result" to JsonNull, "choice" to JsonNull)
                retry++
                }
            }, onFocus = { focused -> saveReader { JsonObject(it + ("focus" to JsonPrimitive(focused))) } },
            emphasis = { facts -> ClassEmphasis(emphasis, facts) })
        return
    }
    if (item == null) {
        if (only != null) { CenterMessage("本页登记的题在这一周的选择题里找不到，请稍后重试或回到选择题主线。"); return }
        Text("这一节暂无对应题目。")
        TextButton(onClick = { position(true) }) { Text("返回本周要点") }
        TextButton(onClick = { startQuiz() }) { Text("做本周全部题") }
        return
    }
    val coverage = body?.get("coverage") as? JsonObject
    val options = item.strings("options")
    val letters = optionLetters(options.size)
    val done = result
    val explanation = done?.get("explanation") as? JsonObject
    // The answer response supplies the real KP link. Before answering, do not show an unrelated active KP's page.
    val sourceKp = done?.str("kpId")?.let(view::kp)
    val d = pageDetails(c, view, number, kp = sourceKp, includeSource = sourceKp != null)
    var sourceOpen by remember { mutableStateOf(false) }
    val openSource = { if (sourceKp != null) sourceOpen = true else onBack() }
    if (sourceOpen && sourceKp != null) androidx.compose.ui.window.Dialog(onDismissRequest = { sourceOpen = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { ResourcesScreen(c, SourceJump(sourceKp.anchorDoc, sourceKp.anchorPdfPage), onBack = { sourceOpen = false }) }
    }
    val railAnchors = remember { MarginAnchors() }
    val answer = done?.num("answer")?.toInt()
    // A lost response may be replayed after a local edit: the recorded choice and outcome belong together.
    val displayedChoice = done?.num("choice")?.toInt() ?: chosen
    val reasons = explanation?.get("options") as? JsonObject
    val nextId = filtered.getOrNull(index + 1)?.str("id")
    fun nextQuestion() {
        if (nextId != null) {
            chosen = null; result = null
            save("choice" to JsonNull, "result" to JsonNull, "quizScroll" to JsonPrimitive(0))
            position(false, nextId)
            scope.launch { scroll.scrollTo(0) }
        } else position(true)
    }
    val actions: @Composable () -> Unit = {
        val metrics = LocalDeskMetrics.current
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val buttonModifier = if (metrics.inner) Modifier else Modifier.weight(1f).height(if (metrics.window == DeskWindow.Outer) 52.dp else 44.dp)
            if (done == null) {
                PrimaryButton(onClick = { submit(false) }, enabled = chosen != null && !busy, modifier = buttonModifier.testTag("mainline-submit")) { Text("提交") }
                PaperButton(onClick = { submit(true) }, enabled = chosen != null && !busy, modifier = buttonModifier.testTag("mainline-guess")) { Text("我是猜的") }
            } else {
                PaperButton(onClick = { position(true) }, modifier = buttonModifier) { Text("回到要点") }
                PrimaryButton(onClick = ::nextQuestion, modifier = buttonModifier.testTag("mainline-next")) {
                    Text(if (nextId != null) "下一题  ›" else if (filter.isBlank()) "这一周做完了" else "这一节做完了，回看要点")
                }
            }
        }
    }
    CompositionLocalProvider(LocalMarginAnchors provides railAnchors, LocalV7PageDetails provides d) {
    Desk(crumb = "${d.course.ifBlank { c.courseId }} · 第 $number 周", status = "这一节 ${filtered.size} 题", compactStatus = "不计时", compactTypeControl = false, onBack = onBack, backLabel = if (only != null) "← 回到阅读" else if (d.sectionLabel.isNotBlank()) "← ${d.sectionLabel}" else "← 选周",

        directory = { WeekDirectory(view, number, null) },
        margin = {
            AlignedRail(listOf("why", null, null), gap = 24.dp) {
                if (d.sourceSvg.isNotBlank() || d.sourceText.isNotBlank()) SourceThumbnail(d, openSource)
                if (d.lecture.isNotBlank()) NoteCard("★ ${d.lectureLabel}", d.lecture)
                if (d.related.isNotEmpty()) PaperCard(contentSpacing = 8.dp) {
                    Text("相关", style = MaterialTheme.typography.labelMedium)
                    d.related.forEach { related -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(related.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Text(related.status, style = MaterialTheme.typography.labelMedium, color = if (related.passed) com.coach.ui.theme.CoachTheme.colors.successFg else com.coach.ui.theme.CoachTheme.colors.fgPrimary)
                    } }
                }
                if (d.sourceSvg.isBlank()) {
                    explanation?.str("background")?.takeIf { it.isNotBlank() }?.let { NoteCard("背景", it) }
                }
            }
        }, footer = {
            Text("‹  回到要点", Modifier.clickable { position(true) }, style = MaterialTheme.typography.labelLarge)
            Text("${index + 1} / ${filtered.size}", style = MaterialTheme.typography.labelMedium, fontFamily = LocalV7Fonts.current.numbers)
            Text(if (nextId != null) "第 ${index + 2} 题  ›" else "", Modifier.clickable(enabled = done != null && nextId != null, onClick = ::nextQuestion), style = MaterialTheme.typography.labelLarge)
        }) {
        val m = LocalDeskMetrics.current
        val density = LocalDensity.current
        val feedbackRequester = remember { BringIntoViewRequester() }
        var feedbackSize by remember(done) { mutableStateOf(IntSize.Zero) }
        LaunchedEffect(revealFeedback, feedbackSize, m.inner) {
            if (revealFeedback && feedbackSize != IntSize.Zero) {
                if (!m.inner) {
                    // Keep the complete card, including its citation, above the fixed bottom fade.
                    val fade = with(density) { 40.dp.toPx() }
                    feedbackRequester.bringIntoView(Rect(0f, 0f, feedbackSize.width.toFloat(), feedbackSize.height + fade))
                }
                revealFeedback = false
            }
        }
        var actionHeight by remember { mutableStateOf(0.dp) }
        val bottomInset = if (m.inner) 0.dp else maxOf(actionHeight, if (m.window == DeskWindow.Outer) 85.dp else 69.dp)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageHeight = maxHeight - m.topPadding - 24.dp - bottomInset
            Column(Modifier.widthIn(max = CoachV7.Layout.colw + m.horizontalPadding * 2).fillMaxSize().align(Alignment.TopCenter)
                .padding(bottom = bottomInset).testTag("mainline-scroll-viewport").verticalScroll(scroll)
                .padding(horizontal = m.horizontalPadding).padding(top = m.topPadding, bottom = 24.dp)) {
                Column(Modifier.heightIn(min = pageHeight.coerceAtLeast(0.dp))) {
                TextButton(onClick = { position(true) }, modifier = Modifier.testTag("mainline-open-notes")) { Text("本周要点") }
                Row(Modifier.fillMaxWidth().testTag("mainline-heading"), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (d.sectionLabel.isBlank()) "${tierNames[item.str("tier")] ?: "题"} · 第 ${index + 1}/${filtered.size} 题" else "${d.sectionLabel} ${d.sectionTitle} · 第 ${index + 1} 题 / ${filtered.size}",
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("不计时", style = MaterialTheme.typography.labelMedium)
                }
                ClassEmphasis(emphasis, item.strings("factIds"))
                Spacer(Modifier.height(12.dp))
                QuizText(item.str("stem"), "mainline:${item.str("id")}", c.math, MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    options.forEachIndexed { i, option ->
                        val verdict = if (done == null) null else if (i == answer) true else if (i == displayedChoice) false else null
                        AnswerOption(letters[i], displayedChoice == i, verdict, done == null && !busy,
                            modifier = Modifier.testTag("mainline-option-$i"), onClick = { chosen = i; save("choice" to JsonPrimitive(i)) }) {
                            QuizText(option, "mainline:${item.str("id")}:$i", c.math)
                        }
                    }
                }
                if (done != null && explanation != null) {
                    Spacer(Modifier.height(24.dp))
                    PaperCard(Modifier.railAnchor("why").bringIntoViewRequester(feedbackRequester)
                        .onGloballyPositioned { feedbackSize = it.size }, contentSpacing = 0.dp, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                        Text("为什么是 ${answer?.let { letters.getOrNull(it) }.orEmpty()}（为什么对）", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(8.dp))
                        Text(explanation.str("correct"), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(16.dp))
                        val wrong = displayedChoice?.takeIf { it != answer }
                        if (wrong != null) {
                            Text("${letters.getOrNull(wrong)} 错在哪", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                            Spacer(Modifier.height(8.dp))
                            Text((reasons?.get("$wrong") as? JsonPrimitive)?.contentOrNull.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(16.dp))
                        }
                        explanation.str("source").ifBlank { done.str("source") }.takeIf { it.isNotBlank() }?.let { source ->
                            PrivateSourceReference(http.reader, source, done.str("anchor"))
                        }
                    }
                    if (!m.inner && (d.sourceSvg.isNotBlank() || d.sourceText.isNotBlank())) { Spacer(Modifier.height(20.dp)); SourceThumbnail(d, openSource) }
                }
                Spacer(Modifier.height(24.dp))
                if (m.inner) actions()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (pending != null && !retryable) TextButton(onClick = { retry++ }) { Text("重试原答案") }
                if (done == null) Text("拿不准就点「我是猜的」：答对也不算掌握，会排进复习。", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium)
                }
                if (done != null) {
                    Spacer(Modifier.height(24.dp))
                    Outcome(done, view.logicalToday(now), onReviewWeek)
                    if (done.str("status") in setOf("missed", "guessed")) PersonalMarks(relatedMarks(marks, sections, item.strings("factIds")), sections)
                    if (explanation == null) done.str("source").takeIf { it.isNotBlank() }?.let { source ->
                        PrivateSourceReference(http.reader, source, done.str("anchor"))
                    }
                    Spacer(Modifier.height(16.dp))
                    // Every unselected option's reason remains immediately available in the same scroll view.
                    options.indices.filter { it != answer && it != displayedChoice }.forEach { i ->
                        (reasons?.get("$i") as? JsonPrimitive)?.contentOrNull?.let { Labeled("${letters[i]} 为什么不选", it) }
                    }
                    explanation?.str("background")?.takeIf { it.isNotBlank() }?.let { Labeled("背景", it) }
                    for (s in sections.filter { s -> s.strings("fact_ids").any { it in item.strings("factIds") } }) {
                        TextButton(onClick = { position(true, section = s.str("section_id")) }, modifier = Modifier.testTag("mainline-return-section")) { Text("回看要点：${s.str("title")}") }
                    }
                    coverage?.let { CoverageLines(it, Modifier.padding(top = 16.dp)) }
                    if (done.str("tier") == "bridge" && only == null) {
                        Text("桥接题连着大题：接着练一道步骤批改，或写一篇限时论述。", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PaperButton(onClick = onOpenSteps, modifier = Modifier.testTag("mainline-steps")) { Text("步骤批改") }
                            PaperButton(onClick = onOpenEssay, modifier = Modifier.testTag("mainline-essay")) { Text("限时论述") }
                        }
                    }
                }
            }
            if (!m.inner) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset).fillMaxWidth().height(40.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, com.coach.ui.theme.CoachTheme.colors.surface1))))
            if (!m.inner) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { actionHeight = with(density) { it.height.toDp() } }.verticalScroll(rememberScrollState()).background(com.coach.ui.theme.CoachTheme.colors.surface1)
                .padding(top = 1.dp).padding(horizontal = if (m.window == DeskWindow.OuterLandscape) ((maxWidth - CoachV7.Layout.colw) / 2).coerceAtLeast(m.horizontalPadding) else m.horizontalPadding,
                    vertical = if (m.window == DeskWindow.Outer) 16.dp else 12.dp)) { actions() }
        }
    }
}
}

/** A modal overlay preserves the underlying question, choices and scroll position. */
@Composable
private fun ClassEmphasis(all: List<JsonObject>, factIds: List<String>) {
    val linked = all.filter { it.str("fact_id") in factIds }
    if (linked.isEmpty()) return
    var opened by remember { mutableStateOf(false) }
    TextButton(onClick = { opened = true }, modifier = Modifier.testTag("mainline-emphasis")) { Text("★ 课上强调") }
    if (opened) AlertDialog(onDismissRequest = { opened = false },
        title = { Text("课上强调") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (m in linked) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${m.str("fact_id")} · 第 ${m.num("week")} 周 · ${if (m.str("recording_kind") == "tutorial") "习题课" else "讲课"} ${m.str("recording_id")} · ${m.str("timestamp")}", style = MaterialTheme.typography.labelLarge)
                Text(m.str("summary"), style = MaterialTheme.typography.bodyMedium)
            }
        } },
        confirmButton = { TextButton(onClick = { opened = false }, modifier = Modifier.testTag("mainline-emphasis-close")) { Text("关闭") } })
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

/** The reader's "去做这几题": the existing weekly walk restricted to the questions registered on one page; closing returns to the reader untouched. */
@Composable
internal fun PagePractice(c: CoachController, week: Int, ids: Set<String>, onClose: () -> Unit) {
    val revision by c.sync.revision.collectAsState()
    val view by produceState<CourseView?>(null, revision) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val events = c.sync.localEvents(c.courseId)
            if (events.isEmpty() && revision == 0L) null else CourseView(events, c.sync.projection(c.courseId))
        }
    }
    PagePracticeBody(c, view, week, ids, onClose)
}

@Composable
internal fun PagePracticeBody(c: CoachController, view: CourseView?, week: Int, ids: Set<String>, onClose: () -> Unit) {
    val http = c.course
    Column(Modifier.fillMaxSize()) {
        when {
            http == null -> { Header("本页练习", "← 回到阅读", onClose); CenterMessage("要连接 Mac 才能做题。") }
            ids.isEmpty() -> { Header("本页练习", "← 回到阅读", onClose); CenterMessage("本页没有登记的题。") }
            view == null -> { Header("本页练习", "← 回到阅读", onClose); CenterMessage("本机还没有这门课的学习记录，暂时不能开始练习。") }
            else -> MainlineWeek(c, http, view, week, "quiz", onBack = onClose, onOpenSteps = onClose, onOpenEssay = onClose, onReviewWeek = { onClose() }, only = ids)
        }
    }
}
