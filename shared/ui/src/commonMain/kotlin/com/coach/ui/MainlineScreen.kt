package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coach.client.CourseHttp
import com.coach.client.CourseView
import com.coach.client.weekName
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
fun MainlineScreen(c: CoachController, view: CourseView, onBack: () -> Unit, onOpenSteps: () -> Unit, onOpenEssay: () -> Unit, onReviewWeek: (Int) -> Unit, directEntry: Boolean = false, parentLabel: String = "复习", initialWeek: Int? = null, browseOnly: Boolean = false, onBrowseWeek: ((Int) -> Unit)? = null, onStartWeek: ((Int) -> Unit)? = null, onNextSection: ((String) -> Unit)? = null) {
    val http = c.course
    var week by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (browseOnly) null else initialWeek ?: (c.sync.mainlineState(c.courseId)["week"] as? JsonPrimitive)?.intOrNull) }
    var entry by remember { mutableStateOf(c.sync.mainlineState(c.courseId).str("entry").ifBlank { null }) }
    fun openWeek(n: Int, quiz: Boolean) {
        entry = if (quiz) "quiz" else "notes"; week = n
        c.sync.saveMainlineState(c.courseId, JsonObject(c.sync.mainlineState(c.courseId) + mapOf("week" to JsonPrimitive(n), "entry" to JsonPrimitive(entry))))
    }
    val closeWeek: () -> Unit = {
        if (directEntry) onBack()
        else { week = null; c.sync.saveMainlineState(c.courseId, JsonObject(c.sync.mainlineState(c.courseId) - "week")) }
    }
    AppBackHandler(week != null) { closeWeek() }
    when {
        http == null -> com.coach.ui.v7.Desk(crumb = "选择题主线", onBack = onBack, backLabel = "← 复习") {
            Column { Header("选择题主线", "← 复习", onBack); CenterMessage("选择题主线要连接 Mac。") }
        }
        week == null -> com.coach.ui.v7.Desk(crumb = "选择题主线", onBack = onBack, backLabel = "← 复习",
            directory = { com.coach.ui.v7.WeekDirectory(view, null, onBrowseWeek) }) {
            Column(Modifier.fillMaxSize()) { MainlineHome(c, http, view, onBack) { n, quiz ->
                if (quiz && onStartWeek != null) onStartWeek(n)
                else if (!quiz && onBrowseWeek != null) onBrowseWeek(n)
                else openWeek(n, quiz)
            } }
        }
        else -> key(week) { MainlineWeek(c, http, view, week!!, entry, onBack = closeWeek, onOpenSteps, onOpenEssay, onReviewWeek, directEntry = directEntry, parentLabel = parentLabel, targetKpId = if (directEntry) c.sync.mainlineState(c.courseId).str("targetKp").ifBlank { null } else null, onBrowseWeek = onBrowseWeek, onNextSection = onNextSection) }
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
private fun MainlineHome(c: CoachController, http: CourseHttp, view: CourseView, onBack: () -> Unit, onOpen: (Int, Boolean) -> Unit) {
    val revision by c.sync.revision.collectAsState()
    val cached = c.sync.mainlineState(c.courseId, "home")["body"] as? JsonObject
    val knownWeeks = json("weeks" to JsonArray(view.weeks.map { n -> json("week" to n, "title" to weekName(view.weekKps(n))) }))
    val reply by produceState<MockReply?>(MockReply(cached ?: knownWeeks, null), revision) {
        // Blocking endpoint: fetch off the composition dispatcher so the screen can keep drawing while it waits.
        do {
            val r = withContext(Dispatchers.IO) { http.mainline() }
            if (r.body != null) { value = r; c.sync.saveMainlineState(c.courseId, json("body" to r.body), "home") }
            if (!r.retryable) break
            delay(1500)
        } while (true)
    }
    Header("选择题主线", "← 复习", onBack)
    val body = reply?.body
    when {
        reply == null -> CenterMessage("正在读取…")
        body == null -> CenterMessage(reply!!.error ?: "连不上 Mac，正在自动重连…")
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val weeks = (body["weeks"] as? JsonArray).orEmpty().map { it.jsonObject }
            if (weeks.isEmpty()) Text("还没有生成选择题主线。", style = MaterialTheme.typography.bodyMedium)
            else Text("选一周，从基础题开始。答对且不是猜的才算会了。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (w in weeks) {
                val n = w.num("week").toInt()
                Surface(onClick = { c.tutor.telemetry?.record("tap", "mainline_week"); onOpen(n, true) }, tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("mainline-week-$n")) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("第 $n 周 · ${w.str("title")}", style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        val coverage = w["coverage"] as? JsonObject
                        if (coverage != null) CoverageLines(coverage)
                        else Text("题目还未同步，连接后可读取。", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { onOpen(n, true) }, modifier = Modifier.testTag("mainline-direct-$n")) { Text("直接做题") }
                    }
                }
            }
            for (bad in (body["invalid"] as? JsonArray).orEmpty().map { it.jsonObject })
                Text("${bad.str("file")} 读不了：${bad.str("reason")}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 覆盖面板：这周有多少考点、多少有题、多少会了（答对且没标猜的）。 */
@Composable
private fun CoverageLines(cov: JsonObject, modifier: Modifier = Modifier) {
    val facts = cov.num("facts"); val mastered = cov.num("factsMastered")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("考点 $facts · 有题 ${cov.num("factsWithQuestions")} · 会了 $mastered", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("mainline-coverage"))
        LinearProgressIndicator(progress = { if (facts == 0L) 0f else mastered.toFloat() / facts }, modifier = Modifier.fillMaxWidth())
        Text("题 ${cov.num("answered")}/${cov.num("questions")} 已做 · ${cov.num("mastered")} 题答对" +
            (cov.num("keysPending").takeIf { it > 0 }?.let { " · $it 题答案未核对，不计分" } ?: ""),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val tierNames = mapOf("setup" to "基础题", "detail" to "细节题", "bridge" to "综合题")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainlineWeek(c: CoachController, http: CourseHttp, view: CourseView, number: Int, entry: String?, onBack: () -> Unit,
    onOpenSteps: () -> Unit, onOpenEssay: () -> Unit, onReviewWeek: (Int) -> Unit, only: Set<String>? = null, directEntry: Boolean = false, parentLabel: String = "复习", targetKpId: String? = null, onBrowseWeek: ((Int) -> Unit)? = null, onNextSection: ((String) -> Unit)? = null) {
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
    var ladderSession by remember { mutableStateOf((saved["ladderSession"] as? JsonObject)?.let {
        runCatching { LedgerJson.decodeFromJsonElement<LadderSession>(it) }.getOrNull()
    } ?: LadderSession()) }
    fun saveSession(next: LadderSession) { ladderSession = next; save("ladderSession" to LedgerJson.encodeToJsonElement(next)) }
    var recapVisible by remember { mutableStateOf(saved.flag("recapVisible")) }
    var asked by remember { mutableStateOf(saved.flag("asked")) }
    var askOpen by remember { mutableStateOf(false) }
    var body by remember { mutableStateOf(saved["body"] as? JsonObject) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryable by remember { mutableStateOf(true) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(retry) {
        // A persisted in-flight answer owns its original displayed options until its receipt arrives.
        if (saved["pending"] is JsonObject && body != null) return@LaunchedEffect
        do {
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
    var reading by remember { mutableStateOf(only == null && when (entry) {
        "notes" -> true
        "quiz", "resume-quiz" -> false
        else -> saved.str("mode") == "notes"
    }) }
    var sectionId by remember { mutableStateOf(saved.str("section")) }
    var filter by remember { mutableStateOf(if (entry == "quiz") "" else saved.str("filter")) }
    var itemId by remember { mutableStateOf(saved.str("item")) }
    var chosen by remember { mutableStateOf((saved["choice"] as? JsonPrimitive)?.intOrNull) }
    var result by remember { mutableStateOf(saved["result"] as? JsonObject) }
    // A fresh answer reveals its feedback once; restoring an answer keeps the reader's scroll.
    var revealFeedback by remember { mutableStateOf(false) }
    val feedbackTrace = LocalFeedbackLayoutTrace.current
    var feedbackSubmissionId by remember { mutableStateOf("") }
    LaunchedEffect(reading) { if (reading) revealFeedback = false }
    var pending by remember { mutableStateOf(saved["pending"] as? JsonObject) }
    // Reading is a separate view of the material, not a replacement for a paused answer.
    var quizStarted by remember { mutableStateOf(saved.str("mode") == "quiz" || saved.flag("quizStarted") || chosen != null || result != null || pending != null) }
    val busy = pending != null
    val now = rememberCourseNow(c)
    val section = sections.firstOrNull { it.str("section_id") == sectionId } ?: sections.firstOrNull()
    val filtered = if (filter.isBlank() && targetKpId == null) items else {
        val facts = sections.firstOrNull { it.str("section_id") == filter }?.strings("fact_ids").orEmpty()
        items.filter { q -> q.strings("factIds").any { it in facts } || (q["ladder"] as? JsonObject)?.str("sectionId") == filter ||
            (q["ladder"] as? JsonObject)?.str("kpId") == targetKpId || (q["ladder"] as? JsonObject)?.str("kind") == "try_first" }
    }
    val learning = body?.flag("learning") == true
    val ladderQuestions = filtered.mapNotNull { q -> (q["ladder"] as? JsonObject)?.let { raw -> runCatching { LedgerJson.decodeFromJsonElement<LadderQuestion>(raw) }.getOrNull() } }
    val previouslyLearned = view.kps.filter { it.status in setOf(com.coach.client.KpStatus.PASSED, com.coach.client.KpStatus.SOLID) }.map { it.id }.toSet()
    val ladderStep = QuestionLadder.next(ladderQuestions, ladderSession, previouslyLearned)
    val index = filtered.indexOfFirst { it.str("id") == itemId }.takeIf { it >= 0 }
        ?: (if (learning) filtered.indexOfFirst { it.str("id") == ladderStep.question?.id }.takeIf { it >= 0 } else null)
        ?: filtered.indexOfFirst { it.str("status") != "mastered" }.coerceAtLeast(0)
    val item = filtered.getOrNull(index)
    val displayedNumber = if (learning) ladderSession.responses.size + if (result == null) 1 else 0 else index + 1
    val scroll = rememberScrollState(saved.num("quizScroll").toInt())
    val readingScroll = rememberScrollState(saved.num("readScroll").toInt())
    LaunchedEffect(reading) {
        snapshotFlow { if (reading) readingScroll.value else scroll.value }.collect { offset ->
            feedbackTrace?.invoke("save submission=$feedbackSubmissionId reading=$reading reveal=$revealFeedback scroll=$offset")
            save((if (reading) "readScroll" else "quizScroll") to JsonPrimitive(offset))
        }
    }
    fun position(mode: Boolean, id: String = itemId, section: String = sectionId, selectedFilter: String = filter) {
        if (mode && only != null) { onBack(); return }
        if (!mode) quizStarted = true
        reading = mode; itemId = id; sectionId = section; filter = selectedFilter
        save("mode" to JsonPrimitive(if (mode) "notes" else "quiz"), "quizStarted" to JsonPrimitive(quizStarted), "item" to JsonPrimitive(id),
            "section" to JsonPrimitive(section), "filter" to JsonPrimitive(selectedFilter))
    }
    LaunchedEffect(body != null) {
        if (body != null && targetKpId != null && entry in setOf("quiz", "notes")) {
            // Use actual section/fact IDs from the imported course, never infer an answer link from ordering.
            val concepts = view.kp(targetKpId)?.conceptIds.orEmpty()
            val match = sections.firstOrNull { it.str("section_id") == targetKpId || it.strings("fact_ids").any { f -> f in concepts } }
            if (match == null && learning && !reading) {
                val next = QuestionLadder.next(ladderQuestions, ladderSession, previouslyLearned).question?.id.orEmpty()
                if (next != itemId) { chosen = null; result = null; save("choice" to JsonNull, "result" to JsonNull) }
                if (filter != targetKpId) saveSession(ladderSession.copy(complete = false))
                position(false, next, selectedFilter = targetKpId)
            } else if (match != null) {
                if (reading) {
                    if (sectionId != match.str("section_id")) readingScroll.scrollTo(0)
                    position(true, section = match.str("section_id"))
                } else {
                    val candidates = items.filter { q -> q.strings("factIds").any { it in match.strings("fact_ids") } }
                    val next = if (learning) {
                        val scoped = ladderQuestions.filter { it.kpId == targetKpId || it.sectionId == match.str("section_id") || it.kind == "try_first" }
                        val selected = QuestionLadder.next(scoped, ladderSession, previouslyLearned).question?.id
                        items.firstOrNull { it.str("id") == selected }
                    } else candidates.firstOrNull { it.str("status") != "mastered" } ?: candidates.firstOrNull()
                    val id = next?.str("id").orEmpty()
                    if (filter != match.str("section_id")) saveSession(ladderSession.copy(complete = false))
                    if (id != itemId) { chosen = null; result = null; save("choice" to JsonNull, "result" to JsonNull); scroll.scrollTo(0) }
                    position(false, id, match.str("section_id"), match.str("section_id"))
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (only == null) c.sync.saveMainlineState(c.courseId, json("kind" to "mainline", "week" to number), "learning")
        save("mode" to JsonPrimitive(if (reading) "notes" else "quiz"), "quizStarted" to JsonPrimitive(quizStarted), "filter" to JsonPrimitive(filter))
        if (only == null) c.sync.saveMainlineState(c.courseId, JsonObject(c.sync.mainlineState(c.courseId) - "entry"))
    }
    LaunchedEffect(item?.str("id"), reading) { if (!reading && item != null && itemId != item.str("id")) position(false, item.str("id")) }
    LaunchedEffect(pending, retry) {
        val wire = pending ?: return@LaunchedEffect
        val traceId = wire.str("submissionId")
        SubmissionTrace.mark(traceId, "ui.effectStart")
        do {
            val r = withContext(Dispatchers.IO) {
                SubmissionTrace.mark(traceId, "ui.ioStart")
                http.answerMainline(number, wire.str("itemId"), wire.num("choice").toInt(), wire.flag("guessed"), wire.str("submissionId"),
                    (wire["layout"] as? JsonPrimitive)?.intOrNull, wire.flag("dontKnow"), wire.flag("learning"), wire.flag("assisted")).also { SubmissionTrace.mark(traceId, "ui.answerReturned") }
            }
            SubmissionTrace.mark(traceId, "ui.mainResumed")
            val b = r.body
            if (b != null) {
                ladderQuestions.firstOrNull { it.id == b.str("itemId") }?.let { q -> saveSession(QuestionLadder.record(ladderSession, q, b.flag("independentCorrect"), b.str("remember"))) }
                result = b; pending = null; error = null
                SubmissionTrace.mark(traceId, "ui.resultAssigned")
                val updatedItems = JsonArray(items.map { q ->
                    if (q.str("id") == b.str("itemId")) JsonObject(q + ("status" to JsonPrimitive(b.str("status")))) else q
                })
                body = JsonObject(body.orEmpty() + listOfNotNull("items" to updatedItems,
                    b["coverage"]?.let { "coverage" to it }, b["notes"]?.let { "notes" to it }))
                save("result" to b, "pending" to JsonNull, "body" to body!!)
                feedbackSubmissionId = wire.str("submissionId")
                revealFeedback = !reading
                feedbackTrace?.invoke("reply submission=$feedbackSubmissionId reading=$reading reveal=$revealFeedback scroll=${scroll.value} max=${scroll.maxValue}")
                SubmissionTrace.mark(traceId, "ui.persisted")
                break
            }
            retryable = r.retryable
            error = if (r.retryable) (r.error?.let { "$it · " } ?: "") + "答案已保存在本机，正在自动重连；不会重复计分。" else r.error
            if (!r.retryable) break
            delay(1500)
        } while (true)
    }
    fun submit(guessed: Boolean, dontKnow: Boolean = false) {
        val choice = if (dontKnow) -1 else chosen ?: return
        if (busy || item == null) return
        val wire = json("itemId" to item.str("id"), "choice" to choice, "guessed" to guessed,
            "submissionId" to Random.nextLong().toULong().toString(16), "layout" to item["layout"], "dontKnow" to dontKnow, "learning" to true, "assisted" to asked)
        // Commit the immutable payload and original displayed options BEFORE any network I/O.
        save("pending" to wire, "item" to JsonPrimitive(item.str("id")), "body" to body!!)
        SubmissionTrace.mark(wire.str("submissionId"), "ui.pendingSaved")
        pending = wire; error = null
    }
    fun startQuiz(selectedFilter: String = "") {
        if (pending != null) { position(false); return }
        val facts = sections.firstOrNull { it.str("section_id") == selectedFilter }?.strings("fact_ids").orEmpty()
        val candidates = if (selectedFilter.isBlank()) items else items.filter { q -> q.strings("factIds").any { it in facts } }
        val next = candidates.firstOrNull { it.str("id") == itemId }
            ?: candidates.firstOrNull { it.str("status") != "mastered" } ?: candidates.firstOrNull()
        val id = next?.str("id").orEmpty()
        if (id != itemId) {
            chosen = null; result = null
            save("choice" to JsonNull, "result" to JsonNull)
            scope.launch { scroll.scrollTo(0) }
        }
        position(false, id, selectedFilter = selectedFilter)
    }
    val goUp: () -> Unit = { if (!reading && !directEntry) position(true) else onBack() }
    AppBackHandler(!reading, priority = 10, onBack = goUp)
    TaskChrome(!reading || readerState.flag("focus"))
    AppBackHandler(reading && readerState.flag("focus"), priority = 100) { saveReader { JsonObject(it + ("focus" to JsonPrimitive(false))) } }
    LaunchedEffect(reading, readerState.flag("focus")) { c.readerFocus = reading && readerState.flag("focus") }
    if ((reading && !readerState.flag("focus")) || (!reading && (body == null || item == null))) Header("第 $number 周 · ${if (reading) "本周要点" else "选择题"}", if (reading) "← 选择题主线" else "← 第 $number 周", goUp)
    if (reading && readerState.flag("focus")) Header("第 $number 周", "退出专心读") { saveReader { JsonObject(it + ("focus" to JsonPrimitive(false))) } }
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
    if (learning && (recapVisible || ladderSession.complete)) {
        val points = if (targetKpId == null) view.weekKps(number) else view.weekKps(number).filter { it.id == targetKpId }
        val nextKp = view.weekKps(number).let { rows -> rows.getOrNull(rows.indexOfFirst { it.id == targetKpId } + 1) }?.takeIf { it.id != targetKpId }
        LadderCheckpoint(ladderSession, points, ladderStep.blockedKps.isNotEmpty() || points.any { it.id !in previouslyLearned }, recapVisible, onContinue = {
            saveSession(QuestionLadder.closeRecap(ladderSession)); recapVisible = false; save("recapVisible" to JsonPrimitive(false))
            val next = QuestionLadder.next(ladderQuestions, ladderSession, previouslyLearned).question?.id
            if (next == null) saveSession(ladderSession.copy(complete = true))
            else { chosen = null; result = null; asked = false; save("choice" to JsonNull, "result" to JsonNull, "asked" to JsonPrimitive(false)); position(false, next); scope.launch { scroll.scrollTo(0) } }
        }, onNotes = { position(true) }, onNext = if (nextKp != null && onNextSection != null) ({ onNextSection(nextKp.id) }) else null, onBack = onBack)
        return
    }
    if (item == null) {
        if (only != null) { CenterMessage("本页登记的题在这一周的选择题里找不到，请稍后重试或回到选择题主线。"); return }
        Text(if (learning) "这一节的题仍在核对，进度已保留。" else "这一节暂无对应题目。")
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
    var sourceOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    AppBackHandler(sourceOpen, priority = 250) { sourceOpen = false }
    val openSource = { if (sourceKp != null) sourceOpen = true else onBack() }
    if (sourceOpen && sourceKp != null) androidx.compose.ui.window.Dialog(onDismissRequest = { sourceOpen = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { ResourcesScreen(c, SourceJump(sourceKp.anchorDoc, sourceKp.anchorPdfPage), onBack = { sourceOpen = false }, backLabel = "← 第 $number 周 · 第 $displayedNumber 题") }
    }
    val railAnchors = remember { MarginAnchors() }
    val answer = done?.num("answer")?.toInt()
    // A lost response may be replayed after a local edit: the recorded choice and outcome belong together.
    val displayedChoice = done?.num("choice")?.toInt() ?: chosen
    val reasons = explanation?.get("options") as? JsonObject
    val nextId = if (learning) ladderStep.question?.id else filtered.getOrNull(index + 1)?.str("id")
    fun nextQuestion() {
        if (learning && ladderSession.recapOpen) { recapVisible = true; save("recapVisible" to JsonPrimitive(true)); return }
        if (learning && nextId == null) { saveSession(ladderSession.copy(complete = true)); return }
        if (nextId != null) {
            chosen = null; result = null; asked = false
            save("choice" to JsonNull, "result" to JsonNull, "quizScroll" to JsonPrimitive(0), "asked" to JsonPrimitive(false))
            position(false, nextId)
            scope.launch { scroll.scrollTo(0) }
        } else position(true)
    }
    val actions: @Composable () -> Unit = {
        val metrics = LocalDeskMetrics.current
        Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val buttonModifier = if (metrics.window == DeskWindow.Inner) Modifier else Modifier.weight(1f).height(if (metrics.window == DeskWindow.Outer) 52.dp else 44.dp)
            if (done == null) {
                PrimaryButton(onClick = { submit(false) }, enabled = chosen != null && !busy, modifier = buttonModifier.testTag("mainline-submit")) { Text("提交") }
                PaperButton(onClick = { submit(true) }, enabled = chosen != null && !busy, modifier = buttonModifier.testTag("mainline-guess")) { Text("我是猜的") }
            } else {
                PaperButton(onClick = { position(true) }, modifier = buttonModifier) { Text("回到要点") }
                PrimaryButton(onClick = ::nextQuestion, modifier = buttonModifier.testTag("mainline-next")) {
                    Text(if (learning && ladderSession.recapOpen) "看看刚学会的" else if (nextId != null) "下一题  ›" else "本节脉络")
                }
            }
        }
        if (done == null) TextButton(onClick = { submit(false, true) }, enabled = !busy, modifier = Modifier.testTag("mainline-dont-know")) { Text("不会，直接看讲解") }
        }
    }
    CompositionLocalProvider(LocalMarginAnchors provides railAnchors, LocalV7PageDetails provides d) {
    Desk(crumb = "${d.course.ifBlank { c.courseId }} · 第 $number 周", status = "${if (only == null && filter.isBlank()) "本周" else "这一节"} ${filtered.size} 题", compactStatus = "不计时", compactTypeControl = false, showNavigation = false, onBack = goUp, backLabel = if (only != null) "暂离 · 阅读" else if (directEntry) "暂离 · $parentLabel" else "暂离 · 第 $number 周",
        directory = { WeekDirectory(view, number, onBrowseWeek) },
        margin = {
            if (askOpen) LadderQuestionAsk(http, number, item.str("id"), { if (done == null) { asked = true; save("asked" to JsonPrimitive(true)) } }, { askOpen = false })
            else
            AlignedRail(listOf("why", null, null), gap = 24.dp) {
                if (d.sourceSvg.isNotBlank() || d.sourceText.isNotBlank()) SourceThumbnail(d, openSource)
                if (d.lecture.isNotBlank()) NoteCard(d.lectureLabel, d.lecture)
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
            Text(if (learning) "已做 ${ladderSession.responses.size} 题" else "${index + 1} / ${filtered.size}", style = MaterialTheme.typography.labelMedium, fontFamily = LocalV7Fonts.current.numbers)
            Text(if (nextId != null) "下一题  ›" else "", Modifier.clickable(enabled = done != null && nextId != null, onClick = ::nextQuestion), style = MaterialTheme.typography.labelLarge)
        }) {
        val m = LocalDeskMetrics.current
        val density = LocalDensity.current
        // Medium describes columns, not spare height: keep submission reachable in these windows too.
        val dockActions = m.window != DeskWindow.Inner
        val coordinates = remember { LessonFeedbackCoordinates() }
        remember(done) { coordinates.feedback = null; coordinates.citation = null; Unit }
        var geometry by remember(done) { mutableStateOf<LessonFeedbackGeometry?>(null) }
        var inlineActionsBottom by remember(done) { mutableStateOf<Float?>(null) }
        var actionHeight by remember { mutableStateOf(0.dp) }
        val minInset = if (m.window == DeskWindow.Outer) 85.dp else 69.dp
        val bottomInset = if (dockActions) maxOf(actionHeight, minInset) else 0.dp
        fun captureGeometry() {
            val sample = coordinates.snapshot(scroll.value, scroll.maxValue, with(density) { bottomInset.roundToPx() }, dockActions) ?: return
            geometry = sample
            if (revealFeedback) feedbackTrace?.invoke("placement submission=$feedbackSubmissionId reading=$reading reveal=$revealFeedback $sample")
        }
        LaunchedEffect(revealFeedback, geometry, dockActions, inlineActionsBottom, reading) {
            val sample = geometry
            if (revealFeedback && !reading && sample != null) {
                if (dockActions) {
                    // The answered action row can shrink before its inset reaches the viewport.
                    // Do not consume a new answer against a previous placement's visible height.
                    if (!sample.ready(with(density) { minInset.roundToPx() })) {
                        feedbackTrace?.invoke("waiting submission=$feedbackSubmissionId $sample")
                        return@LaunchedEffect
                    }
                    val tail = with(density) { 12.dp.toPx() }
                    val clearance = with(density) { 40.dp.toPx() }
                    val target = sample.target(tail, clearance)
                    if (!sample.settled(target, tail, clearance)) {
                        feedbackTrace?.invoke("request submission=$feedbackSubmissionId target=$target $sample")
                        scroll.scrollTo(target)
                        return@LaunchedEffect // The next completed placement must verify the result.
                    }
                } else {
                    val bottom = inlineActionsBottom ?: return@LaunchedEffect
                    val viewport = sample.viewportBottom
                    // Inner windows keep inline actions. Include them and the page's bottom padding
                    // in the first answer, even when the heading already fits. Apply the small
                    // correction immediately rather than leaving clipped glyphs during an animation.
                    val overflow = bottom - scroll.value - viewport + with(density) { 24.dp.toPx() }
                    if (overflow > 0f) scroll.scrollTo(scroll.value + kotlin.math.ceil(overflow).toInt())
                }
                revealFeedback = false
                feedbackTrace?.invoke("consumed submission=$feedbackSubmissionId reading=$reading reveal=$revealFeedback $sample")
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { coordinates.container = it; captureGeometry() }) {
            val pageHeight = maxHeight - m.topPadding - 24.dp - bottomInset
            Column(Modifier.widthIn(max = CoachV7.Layout.colw + m.horizontalPadding * 2).fillMaxSize().align(Alignment.TopCenter)
                .padding(bottom = bottomInset).testTag("mainline-scroll-viewport")
                .onGloballyPositioned { coordinates.viewport = it; captureGeometry() }.verticalScroll(scroll)
                .padding(horizontal = m.horizontalPadding).padding(top = m.topPadding, bottom = 24.dp)) {
                Column(Modifier.heightIn(min = pageHeight.coerceAtLeast(0.dp))) {
                Row {
                    TextButton(onClick = { position(true) }, modifier = Modifier.testTag("mainline-open-notes")) { Text("先看要点") }
                    TextButton(onClick = { askOpen = true }, modifier = Modifier.testTag("mainline-ask")) { Text("问一句") }
                }
                if ((item["ladder"] as? JsonObject)?.str("kind") == "try_first") Text("先试试 · 还没学过，错了正常，不计会没会。")
                Row(Modifier.fillMaxWidth().testTag("mainline-heading"), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (d.sectionLabel.isBlank()) "第 $displayedNumber 题" else "${d.sectionLabel} ${d.sectionTitle} · 第 $displayedNumber 题",
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
                    PaperCard(Modifier.railAnchor("why"), contentSpacing = 0.dp, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                        Column(Modifier.onGloballyPositioned {
                            coordinates.feedback = it
                            captureGeometry()
                        }) {
                            Column(Modifier.onGloballyPositioned {
                                coordinates.citation = it
                                captureGeometry()
                            }) {
                                done.str("remember").takeIf { it.isNotBlank() }?.let { Labeled("记住这个", it); Spacer(Modifier.height(12.dp)) }
                                if (done.flag("dontKnow")) Text("这次先看讲解，算学习，不算答对。")
                                (done["tryFirstRefs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { ref ->
                                    val primers = items.filter { (it["ladder"] as? JsonObject)?.str("kind") == "try_first" }.take(4)
                                    val position = primers.indexOfFirst { it.str("id") == ref.str("question_id") }
                                    if (position >= 0) Text("开头『先试试』的第${position + 1}题说的就是这个。")
                                }
                                Text("为什么是 ${answer?.let { letters.getOrNull(it) }.orEmpty()}（为什么对）", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                                Spacer(Modifier.height(8.dp))
                                Text(explanation.str("correct"), style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(16.dp))
                                explanation.str("source").ifBlank { done.str("source") }.takeIf { it.isNotBlank() }?.let { source ->
                                    PrivateSourceReferences(http.reader, source, done.str("anchor"))
                                }
                            }
                            val wrong = displayedChoice?.takeIf { it != answer }
                            if (wrong != null) {
                                Spacer(Modifier.height(16.dp))
                                Text("${letters.getOrNull(wrong)} 错在哪", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                                Spacer(Modifier.height(8.dp))
                                Text((reasons?.get("$wrong") as? JsonPrimitive)?.contentOrNull.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                        Labeled("背景", explanation.str("background"))
                    }
                    if (!m.inner && (d.sourceSvg.isNotBlank() || d.sourceText.isNotBlank())) { Spacer(Modifier.height(20.dp)); SourceThumbnail(d, openSource) }
                }
                Spacer(Modifier.height(24.dp))
                if (!dockActions) Box(Modifier.onGloballyPositioned {
                    inlineActionsBottom = it.positionInRoot().y + it.size.height + scroll.value
                }) { actions() }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (pending != null && !retryable) TextButton(onClick = { retry++ }) { Text("重试原答案") }
                if (done == null) Text("拿不准就点「我是猜的」：答对也不算掌握，会排进复习。", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium)
                }
                if (done != null) {
                    Spacer(Modifier.height(24.dp))
                    Outcome(done, view.logicalToday(now), onReviewWeek)
                    if (done.str("status") in setOf("missed", "guessed")) PersonalMarks(relatedMarks(marks, sections, item.strings("factIds")), sections)
                    if (explanation == null) done.str("source").takeIf { it.isNotBlank() }?.let { source ->
                        PrivateSourceReferences(http.reader, source, done.str("anchor"))
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
                        Text("这是连着大题的综合题：可以接着做模拟考，或写一篇限时论述。", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PaperButton(onClick = onOpenSteps, modifier = Modifier.testTag("mainline-steps")) { Text("模拟考") }
                            PaperButton(onClick = onOpenEssay, modifier = Modifier.testTag("mainline-essay")) { Text("限时论述") }
                        }
                    }
                }
            }
            if (dockActions) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { coordinates.actionHeight = it.height; actionHeight = with(density) { it.height.toDp() } }
                .onGloballyPositioned { coordinates.actions = it; captureGeometry() }
                .verticalScroll(rememberScrollState()).background(com.coach.ui.theme.CoachTheme.colors.surface1)
                .padding(top = 1.dp).padding(horizontal = if (m.window == DeskWindow.OuterLandscape) ((maxWidth - CoachV7.Layout.colw) / 2).coerceAtLeast(m.horizontalPadding) else m.horizontalPadding,
                    vertical = if (m.window == DeskWindow.Outer) 16.dp else 12.dp)) { actions() }
        }
    }
}
    if (askOpen && LocalDeskMetrics.current.window != DeskWindow.Inner) ModalBottomSheet(onDismissRequest = { askOpen = false }) {
        LadderQuestionAsk(http, number, item.str("id"), { if (done == null) { asked = true; save("asked" to JsonPrimitive(true)) } }, { askOpen = false })
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
        "missed" -> if (r.flag("dontKnow")) "已看讲解，不算答对。" else "答错。"
        "mastered" -> "答对。"
        "guessed" -> "猜对：不算会。"
        "pending" -> "这题答案还没核对，这次不计分。"
        else -> "答错。"
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
