package com.coach.desktop.books.exam

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.desktop.books.*
import com.coach.domain.*
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.AnswerOption
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.CoachEmptyState
import com.coach.ui.v7.CoachErrorBanner
import com.coach.ui.v7.CoachLoadingDots
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PageHeader
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7Checkbox
import com.coach.ui.v7.V7TextButton
import com.coach.ui.v7.paperShadow
import com.coach.ui.v7.v7FocusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun points(value: Long): String = BigDecimal.valueOf(value, 2).stripTrailingZeros().toPlainString()

private val startedFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault())

/** 「作答记录」里的开始时间，用本地时区写成人认得的样子。 */
internal fun localExamStartedLabel(startedAt: Long): String = startedFormat.format(Instant.ofEpochMilli(startedAt))
internal fun localExamRemaining(deadline: Long, now: Long): String {
    val seconds = ((deadline - now).coerceAtLeast(0) + 999) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
private fun paperItems(attempt: JsonObject): List<JsonObject> =
    attempt.getValue("paper").jsonObject.getValue("sections").jsonArray.flatMap { section ->
        section.jsonObject.getValue("items").jsonArray.map { it.jsonObject }
    }

private fun itemTypeLabel(type: String): String = when (type) {
    "mcq" -> "选择"
    "multi_part_calc" -> "多小问计算与推导"
    "graph_analysis" -> "图形移动与机制分析"
    else -> "论述"
}

/** 作答和题源页自己铺一张纸（课程页的小结已经在宿主的纸上），和今天、课程同宽同阴影。 */
@Composable
private fun ExamSheet(content: @Composable () -> Unit) {
    val colors = CoachTheme.colors
    Box(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 900.dp).fillMaxWidth().fillMaxHeight().paperShadow("sheet", colors.dark)
            .clip(RoundedCornerShape(CoachV7.Radius.sheet)).background(colors.surface1)) { content() }
    }
}

/** 页眉下一行小字：题卷类型、作答记录这类说明，不和正文抢。 */
@Composable
private fun Caption(text: String, modifier: Modifier = Modifier, numbers: Boolean = false) =
    Text(text, modifier, style = MaterialTheme.typography.labelMedium, color = CoachTheme.colors.fgSecondary,
        fontFamily = if (numbers) LocalV7Fonts.current.numbers else null)

@Composable
private fun TentativeFormatBadge(tag: String) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.s)
    Box(
        Modifier
            .clip(shape)
            .background(colors.surface2)
            .border(1.dp, colors.borderSubtle, shape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(tag),
    ) {
        Text(
            "按课程手册暂定题型组卷",
            style = MaterialTheme.typography.labelMedium,
            color = colors.fgSecondary,
        )
    }
}

@Composable
private fun ExamSaveStatus(ui: LocalExamUi, controller: LocalExamController) {
    val scope = rememberCoroutineScope()
    var confirmReload by remember { mutableStateOf(false) }
    if (ui.error != null) {
        // 朱色小字是提醒，不是报警红；后面紧跟能做的那一步。
        Text(ui.error, style = MaterialTheme.typography.bodySmall, color = v7Alert(), modifier = Modifier.testTag("local-exam-error"))
        if (ui.conflict) V7TextButton({ confirmReload = true }, enabled = !ui.busy,
            modifier = Modifier.testTag("local-reload-saved")) { Text("读取已保存作答") }
        else V7TextButton({ scope.launch { controller.flush() } }, enabled = !ui.busy,
            modifier = Modifier.testTag("local-save-retry")) { Text("重试保存") }
    } else if (ui.saving) Caption("正在保存…", Modifier.testTag("local-save-status"))
    else if (ui.attempt?.str("status") == "active") Caption("作答已保存", Modifier.testTag("local-save-status"))
    // 重新载入会丢掉本页没保存的字：确认是朱色文字按钮，「保留当前内容」是纸面按钮，默认留住。
    if (confirmReload) AlertDialog(onDismissRequest = { confirmReload = false },
        title = { Text("读取已保存作答？") },
        text = { Text("会丢弃本页尚未保存的内容，重新读取这台电脑已保存的作答。请先复制需要保留的文字。", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { V7TextButton({ confirmReload = false; scope.launch { controller.reloadSaved() } },
            modifier = Modifier.testTag("local-confirm-reload")) { Text("重新载入", color = v7Alert()) } },
        dismissButton = { PaperButton({ confirmReload = false }) { Text("保留当前内容") } })
}

@Composable
internal fun CourseMockHubPane(
    detail: CourseDetailSnapshot,
    controller: LocalExamController,
    library: TextbookLibrary,
    onBackToCourse: () -> Unit,
    onJumpToBookPage: (bookId: String, page: Int, quote: String) -> Unit,
    onMasteryUpdated: suspend (String) -> Unit = {},
) {
    val ui by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val colors = CoachTheme.colors
    val mockHost = LocalCourseMockHost.current

    LaunchedEffect(detail.courseId, detail.title) {
        controller.loadCourse(detail.courseId, detail.title)
    }

    if (ui.attempt != null) {
        LocalExamPane(
            controller = controller,
            library = library,
            grader = mockHost?.gradeAndDiagnose,
            onJumpToBookPage = onJumpToBookPage,
            onMasteryUpdated = onMasteryUpdated,
        )
        return
    }

    ExamSheet {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 20.dp)
                .testTag("course-mock-hub"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            V7TextButton(onBackToCourse, enabled = !ui.busy, modifier = Modifier.testTag("course-mock-hub-back")) {
                Text("← 返回课程")
            }

            val closedStr = if (detail.examProfile.closedBook) "闭卷" else "开卷"
            val calcStr = detail.examProfile.calculatorAllowed.takeIf { it.isNotBlank() }?.let { " · 计算器：$it" }.orEmpty()
            PageHeader(
                kicker = "考前限时综合练习",
                title = "${detail.title} · 全真模拟",
                deck = detail.examProfile.examFormat.ifBlank { "按当前课程专题权重与薄弱点跨周综合组卷" },
                meta = "$closedStr · 限时 ${detail.examProfile.durationMinutes} 分钟$calcStr",
            )

            if (detail.examProfile.examFormatTentative) {
                TentativeFormatBadge("course-mock-tentative-badge")
            }

            if (ui.generatingCourseMock || ui.busy) {
                CoachLoadingDots(
                    label = if (ui.generatingCourseMock) "正在依据课程讲义与考纲组卷并验算题目…" else "正在准备模考记录…",
                    modifier = Modifier.testTag("course-mock-loading"),
                )
            }

            ui.error?.let { errMsg ->
                CoachErrorBanner(
                    message = errMsg,
                    retryLabel = "重新组卷",
                    onRetry = if (mockHost != null && detail.examinableTopics.isNotEmpty() && ui.active == null) {
                        {
                            scope.launch {
                                controller.generateAndStartCourseMock(detail, mockHost.generateCourseMock)
                            }
                        }
                    } else null,
                    retryTag = "course-mock-retry-btn",
                )
            }

            val active = ui.active
            if (active != null) {
                PaperCard(Modifier.fillMaxWidth().testTag("course-mock-active-card")) {
                    Text("还有一套未交卷模拟卷", style = MaterialTheme.typography.titleMedium)
                    CoachCardMeta("作答草稿与倒计时正在本地实时保存。")
                    PrimaryButton(
                        onClick = { scope.launch { controller.open(active.str("mockId")) } },
                        enabled = !ui.busy,
                        modifier = Modifier.testTag("course-mock-resume-btn"),
                    ) {
                        Text("继续未交卷模考")
                    }
                }
            } else if (detail.examinableTopics.isEmpty()) {
                CoachEmptyState(
                    title = "当前还没有可用于组卷的必考专题",
                    hint = "先在课程里导入大纲或讲义并整理出必考专题，再来生成限时模拟卷。",
                )
            } else {
                PrimaryButton(
                    onClick = {
                        if (mockHost != null) {
                            scope.launch {
                                controller.generateAndStartCourseMock(detail, mockHost.generateCourseMock)
                            }
                        }
                    },
                    enabled = !ui.busy && mockHost != null,
                    modifier = Modifier.testTag("course-mock-generate-btn"),
                ) {
                    Text("生成并开始限时模考")
                }
            }

            if (ui.history.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("历次模考记录", style = MaterialTheme.typography.titleMedium, color = colors.fgPrimary)
                ui.history.forEachIndexed { index, record ->
                    val result = record.getValue("result").jsonObject
                    val gradeStr = if (result.num("pending") > 0) {
                        "尚有题目待逐点核对"
                    } else {
                        "得分 ${points(result.num("score"))} / ${points(result.num("max"))}"
                    }
                    PaperButton(
                        onClick = { scope.launch { controller.open(record.str("mockId")) } },
                        enabled = !ui.busy,
                        modifier = Modifier.fillMaxWidth().testTag("course-mock-history-$index"),
                    ) {
                        Text(
                            "${localExamStartedLabel(record.num("startedAt"))} · ${record.getValue("paper").jsonObject.str("title")} · $gradeStr",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LocalExamsSummary(controller: LocalExamController) {
    val ui by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val colors = CoachTheme.colors
    val note = MaterialTheme.typography.bodySmall
    Column(Modifier.fillMaxWidth().testTag("course-exams-summary"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("本地练习与记录", style = MaterialTheme.typography.titleLarge)
        Text("教材参考练习，答案尚未独立核对，不计时。", style = note, color = colors.fgSecondary)
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.fgPrimary, trackColor = colors.surface2)
        ExamSaveStatus(ui, controller)
        ui.active?.let { active ->
            PaperCard {
                Text("还有一份未交卷练习", style = MaterialTheme.typography.titleMedium)
                PrimaryButton({ scope.launch { controller.open(active.str("mockId")) } }, enabled = !ui.busy,
                    modifier = Modifier.testTag("local-resume")) { Text("继续未交卷练习") }
            }
        }
        val reference = ui.reference
        val current = reference?.paper
        val gaps = reference?.gaps.orEmpty()
        // 缺题说清是哪几节、去哪儿补；不写「暂无」这类系统腔，也不摆生成规则的细节。
        val gapNames = gaps.joinToString("、")
        if (reference != null && current == null) {
            Text("这本教材暂时生成不了练习。")
            if (gaps.isNotEmpty()) Text("「$gapNames」里还没有能出题的原文。回教材补一段完整的原文，再来这里就能生成。",
                style = note, color = colors.fgSecondary, modifier = Modifier.testTag("local-exam-gaps"))
        } else if (current != null && ui.papers.none { it.str("id") == current.id }) {
            PrimaryButton({ scope.launch { controller.generateAndStart() } }, enabled = !ui.busy && ui.active == null,
                modifier = Modifier.testTag("local-generate-start")) { Text("生成并开始练习") }
        }
        if (current != null && gaps.isNotEmpty()) {
            Text("这份练习只收了有题的部分，「$gapNames」还没出题。回教材补一段完整的原文，再回来重新生成就会带上。",
                style = note, color = colors.fgSecondary, modifier = Modifier.testTag("local-exam-gaps"))
        }
        // Old reference versions remain in attempts/history; only the current generated version is offered for a new start.
        ui.papers.filter { it.str("origin") == "AUTHORED" || it.str("id") == current?.id }.forEachIndexed { index, paper ->
            var timed by remember(paper.str("id")) { mutableStateOf(false) }
            var minutes by remember(paper.str("id")) { mutableStateOf("30") }
            val duration = minutes.toIntOrNull()?.takeIf { it in 1..1440 }
            val canStart = !ui.busy && ui.active == null
            PaperCard(contentSpacing = 10.dp) {
                Text("${paper.str("title")} · ${paper.num("items")} 题", style = MaterialTheme.typography.titleMedium)
                if (paper.str("origin") == "AUTHORED") {
                    Text("自编题卷，参考答案尚未独立核对。", style = note, color = colors.fgSecondary)
                    // 整行可点：方框和「计时作答」是同一个开关，键盘空格也能切；焦点只画 V7 墨色环，不叠 Material 灰底。
                    Row(Modifier.v7FocusRing().toggleable(timed, remember { MutableInteractionSource() }, indication = null,
                        enabled = canStart, role = Role.Checkbox) { timed = it }
                        .testTag("local-timed-$index").padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        V7Checkbox(timed, enabled = canStart)
                        Text("计时作答", style = MaterialTheme.typography.labelLarge)
                    }
                    if (timed) {
                        V7Field(minutes, { minutes = it }, "时长（分钟）", isError = duration == null, enabled = canStart,
                            modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth().testTag("local-minutes-$index"))
                        Text("1–1440 分钟；离开课程或关闭软件后继续计时。", style = note,
                            color = if (duration == null) v7Alert() else colors.fgSecondary)
                    }
                }
                PaperButton({ scope.launch { controller.start(paper.str("id"), if (timed) duration else null) } },
                    enabled = canStart && (!timed || duration != null),
                    modifier = Modifier.testTag("local-paper-start-$index")) { Text("开始作答") }
            }
        }
        if (ui.history.isNotEmpty()) Text("历次结果", style = MaterialTheme.typography.titleMedium)
        ui.history.forEachIndexed { index, record ->
            val result = record.getValue("result").jsonObject
            val grade = if (result.num("pending") > 0) "总分待评" else
                "参考得分 ${points(result.num("score"))} / ${points(result.num("max"))}"
            PaperButton({ scope.launch { controller.open(record.str("mockId")) } }, enabled = !ui.busy,
                modifier = Modifier.fillMaxWidth().testTag("local-history-$index")) {
                Text("${record.getValue("paper").jsonObject.str("title")} · $grade · 查看结果", Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun LocalExamPane(
    controller: LocalExamController,
    library: TextbookLibrary? = null,
    grader: (suspend (JsonObject) -> PracticeGradeDiagnoseResult)? = null,
    onJumpToBookPage: ((bookId: String, page: Int, quote: String) -> Unit)? = null,
    onMasteryUpdated: (suspend (String) -> Unit)? = null,
) = ExamSheet {
    LocalExamPage(
        controller = controller,
        library = library,
        grader = grader,
        onJumpToBookPage = onJumpToBookPage,
        onMasteryUpdated = onMasteryUpdated,
    )
}

@Composable
private fun LocalExamPage(
    controller: LocalExamController,
    library: TextbookLibrary? = null,
    grader: (suspend (JsonObject) -> PracticeGradeDiagnoseResult)? = null,
    onJumpToBookPage: ((bookId: String, page: Int, quote: String) -> Unit)? = null,
    onMasteryUpdated: (suspend (String) -> Unit)? = null,
) {
    val ui by controller.state.collectAsState()
    val attempt = checkNotNull(ui.attempt)
    val paperObj = attempt.getValue("paper").jsonObject
    val isCourseMock = paperObj.str("courseId").isNotBlank() || !ui.courseId.isNullOrBlank()
    val isTentativeFormat = (paperObj["isTentativeFormat"] as? JsonPrimitive)?.booleanOrNull == true
    val scope = rememberCoroutineScope()
    val source = ui.source
    val focusManager = LocalFocusManager.current
    val colors = CoachTheme.colors
    val numbers = LocalV7Fonts.current.numbers
    if (source != null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp).testTag("local-frozen-source"),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            V7TextButton(controller::closeSource, modifier = Modifier.testTag("local-source-back")) {
                Text(if (attempt.str("status") == "active") "返回作答" else "返回结果")
            }
            Text(source.str("bookTitle"), style = MaterialTheme.typography.headlineMedium)
            Caption("本次题卷保存的教材片段 · 第 ${source.num("pdfPage")} 页")
            // 题目引用的那一句放在一张卡里，下面是当时冻结的整页，可选中复制。
            PaperCard { Text(source.str("quote"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("local-source-quote")) }
            SelectionContainer { Text(source.str("pageText"), style = MaterialTheme.typography.bodyLarge, color = colors.fgPrimary) }
        }
        return
    }
    val submitted = attempt.str("status") == "submitted"
    val timeUp = !submitted && attempt.str("timing") == "TIMED" && ui.nowMillis >= attempt.num("deadline")
    val editable = !ui.busy && !ui.conflict && !timeUp
    var confirm by remember(attempt.str("mockId")) { mutableStateOf(false) }
    val scroll = remember(attempt.str("mockId"), submitted) { ScrollState(0) }
    LaunchedEffect(ui.error) { if (ui.error != null) scroll.scrollTo(0) }
    val result = attempt["result"] as? JsonObject
    val answers = attempt.getValue("answers").jsonObject
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 28.dp, vertical = 20.dp)
        .testTag(if (submitted) "local-exam-result" else "local-exam-attempt"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        V7TextButton({ scope.launch { controller.back() } }, enabled = !ui.busy && !ui.gradingInFlight,
            modifier = Modifier.testTag("local-exam-back")) {
            Text(if (isCourseMock) "返回模考主页" else "返回课程")
        }
        Text(paperObj.str("title"), style = MaterialTheme.typography.headlineMedium)
        val closedBook = (paperObj["closedBook"] as? JsonPrimitive)?.booleanOrNull
        val examSpecPrefix = buildString {
            append(
                when {
                    isCourseMock && closedBook == true -> "课程全真模考（闭卷）"
                    isCourseMock && closedBook == false -> "课程全真模考（开卷）"
                    isCourseMock -> "课程全真模考"
                    attempt.str("origin") == "TEXTBOOK_REFERENCE" -> "教材参考练习"
                    else -> "自编题卷"
                },
            )
            append(" · ")
            append(if (attempt.str("timing") == "TIMED") "计时 ${attempt.num("durationMinutes")} 分钟" else "不计时")
        }
        Caption(examSpecPrefix)
        if (isTentativeFormat) {
            TentativeFormatBadge("local-exam-tentative-badge")
        }
        if (attempt.str("timing") == "TIMED") {
            if (!submitted) Text("剩余 ${localExamRemaining(attempt.num("deadline"), ui.nowMillis)}",
                style = MaterialTheme.typography.titleLarge, fontFamily = numbers,
                color = if (timeUp) v7Alert() else colors.fgPrimary, modifier = Modifier.testTag("local-countdown"))
            else if (attempt.flag("autoSubmitted")) Text("计时结束，已自动交卷。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
        }
        // 内部 id 只在库里；界面写开始时间和第几次，用户对得上自己的记录。
        val record = ui.history.firstOrNull { it.str("mockId") == attempt.str("mockId") }
        val attemptNo = record?.let { ui.history.size - ui.history.indexOf(it) } ?: (ui.history.size + 1)
        Caption("作答记录：${localExamStartedLabel(attempt.num("startedAt"))} · 第 $attemptNo 次",
            Modifier.testTag("local-attempt-id"))
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.fgPrimary, trackColor = colors.surface2)
        ExamSaveStatus(ui, controller)

        if (ui.gradingInFlight) {
            CoachLoadingDots(
                label = "正在按评分要点逐点核对并诊断…",
                modifier = Modifier.testTag("local-exam-grading-loading"),
            )
        }
        ui.gradingError?.let { gErr ->
            CoachErrorBanner(
                message = gErr,
                retryLabel = "重新批改",
                onRetry = if (grader != null) {
                    { scope.launch { controller.retryGrading(grader, onMasteryUpdated) } }
                } else null,
                retryTag = "local-exam-retry-grade-btn",
            )
        }

        if (result != null) {
            PaperCard {
                val gradeItemsList = (result["items"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
                val hasGradedItems = gradeItemsList.any { it.str("status") == "GRADED" }
                if (result.num("pending") > 0) {
                    Text("总分待评", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("local-exam-total-score"))
                    Text("已计分题目：${points(result.num("knownScore"))} 分 · ${result.num("pending")} 题待评")
                    Text("参考答案尚未独立核对，结果仅供对照。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                } else if (hasGradedItems || isCourseMock) {
                    Text(
                        "模考核对得分：${points(result.num("score"))} / ${points(result.num("max"))}",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.testTag("local-exam-total-score"),
                    )
                    Text(
                        "按课程讲义评分要点与数值核对生成诊断；点击页码标签可回到原书对应页。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.fgSecondary,
                    )
                } else {
                    Text(
                        "参考得分：${points(result.num("score"))} / ${points(result.num("max"))}",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.testTag("local-exam-total-score"),
                    )
                    Text("参考答案尚未独立核对，结果仅供对照。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                }
            }
        } else Text("答案尚未独立核对。可以随时返回课程，再继续这次作答。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)

        val paperSections = (paperObj["sections"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
        var runningItemIndex = 0
        paperSections.forEachIndexed { secIndex, section ->
            if (paperSections.size > 1 || isCourseMock) {
                if (secIndex > 0) Spacer(Modifier.height(4.dp))
                Text(section.str("title"), style = MaterialTheme.typography.titleMedium, color = colors.fgPrimary)
                section.str("instructions").takeIf { it.isNotBlank() }?.let { instr ->
                    Caption(instr)
                }
            }
            val secItems = (section["items"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
            secItems.forEach { item ->
                val index = runningItemIndex++
                val key = item.str("id")
                val type = item.str("type")
                val answer = answers[key] as? JsonObject ?: json()
                val mcq = type == "mcq"
                val subQuestions = (item["subQuestions"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                Spacer(Modifier.height(6.dp))
                val weekMeta = item.str("weekLabel").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                Caption("第 ${index + 1} 题 · ${itemTypeLabel(type)}$weekMeta")
                Text(item.str("stem"), style = MaterialTheme.typography.titleMedium)
                var clear: (() -> Unit)? = null
                if (!submitted && mcq) {
                    item.strings("options").forEachIndexed { option, text ->
                        val selected = (answer["choice"] as? JsonPrimitive)?.intOrNull == option
                        AnswerOption(('A' + option).toString(), selected = selected, correct = null, enabled = editable,
                            modifier = Modifier.semantics { role = Role.RadioButton }.testTag("local-choice-$key-$option"),
                            onClick = { controller.answer(key, json("choice" to option)) }) {
                            Text(text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    clear = { controller.answer(key, json("choice" to null)) }
                } else if (!submitted && subQuestions.isNotEmpty()) {
                    val partsObj = answer["parts"] as? JsonObject ?: json()
                    val shape = RoundedCornerShape(CoachV7.Radius.btn)
                    val enabled = !ui.busy
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        subQuestions.forEach { sq ->
                            val subId = sq.str("subId")
                            val subLabel = sq.str("label").ifBlank { "($subId)" }
                            val subPrompt = sq.str("prompt")
                            val subText = partsObj.str(subId)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("$subLabel $subPrompt", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
                                var editor by remember(attempt.str("mockId"), key, subId) { mutableStateOf(TextFieldValue(subText)) }
                                val currentEditor = editor.copy(text = subText)
                                SideEffect { if (editor != currentEditor) editor = currentEditor }
                                BasicTextField(
                                    value = currentEditor,
                                    onValueChange = { next ->
                                        editor = next
                                        if (next.text != subText) controller.answerSubPart(key, subId, next.text)
                                    },
                                    enabled = enabled,
                                    readOnly = ui.conflict || timeUp,
                                    minLines = 2,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.fgPrimary.copy(alpha = if (enabled) 1f else .5f)),
                                    cursorBrush = SolidColor(colors.fgPrimary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onPreviewKeyEvent { event ->
                                            if (event.key == Key.Tab && event.type == KeyEventType.KeyDown) {
                                                if (currentEditor.composition == null) {
                                                    focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                                                }
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                        .v7FocusRing(shape)
                                        .clip(shape)
                                        .background(colors.surface2)
                                        .border(1.dp, colors.borderSubtle, shape)
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                        .testTag("local-subtext-$key-$subId"),
                                )
                            }
                        }
                    }
                    clear = { controller.answer(key, json("parts" to json(), "text" to "")) }
                } else if (!submitted) {
                    val answerText = answer.str("text")
                    var editor by remember(attempt.str("mockId"), key) { mutableStateOf(TextFieldValue(answerText)) }
                    // Keep selection and IME composition while following externally reloaded or cleared drafts.
                    val currentEditor = editor.copy(text = answerText)
                    SideEffect { if (editor != currentEditor) editor = currentEditor }
                    val shape = RoundedCornerShape(CoachV7.Radius.btn)
                    val enabled = !ui.busy
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Caption("写下你的回答")
                        BasicTextField(currentEditor, { next ->
                            editor = next
                            if (next.text != answerText) controller.answer(key, json("text" to next.text))
                        }, enabled = enabled, readOnly = ui.conflict || timeUp, minLines = 3,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.fgPrimary.copy(alpha = if (enabled) 1f else .5f)),
                            cursorBrush = SolidColor(colors.fgPrimary),
                            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                                if (event.key == Key.Tab && event.type == KeyEventType.KeyDown) {
                                    // Do not move focus or insert a literal tab during composition.
                                    if (currentEditor.composition == null) {
                                        focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                                    }
                                    true
                                } else false
                            }.v7FocusRing(shape).clip(shape).background(colors.surface2)
                                .border(1.dp, colors.borderSubtle, shape).padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("local-text-$key"))
                    }
                    clear = { controller.answer(key, json("text" to "")) }
                } else {
                    val chosen = (answer["choice"] as? JsonPrimitive)?.intOrNull
                    val grade = result?.get("items")?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.str("key") == key }
                    val partsObj = answer["parts"] as? JsonObject
                    val displayDraft = when {
                        mcq -> chosen?.let { item.strings("options").getOrNull(it) } ?: "未作答"
                        subQuestions.isNotEmpty() -> {
                            val combined = subQuestions.mapNotNull { sq ->
                                val sid = sq.str("subId")
                                val slabel = sq.str("label").ifBlank { "($sid)" }
                                val txt = partsObj?.str(sid)?.trim().orEmpty()
                                if (txt.isBlank()) null else "$slabel $txt"
                            }.joinToString("\n")
                            val extra = answer.str("text").trim()
                            listOf(combined, extra).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "未作答" }
                        }
                        else -> answer.str("text").ifBlank { "未作答" }
                    }
                    PaperCard(color = colors.surface2, contentSpacing = 6.dp) {
                        Text("本次作答：$displayDraft", style = MaterialTheme.typography.bodyLarge)
                        when (grade?.str("status")) {
                            "PENDING_REVIEW" -> {
                                Text("这题待评，尚未计入总分。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                            }
                            "GRADED" -> {
                                val scoreNum = grade.num("score")
                                val maxNum = grade.num("max")
                                Text(
                                    "核对得分：${points(scoreNum)} / ${points(maxNum)} 分",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (scoreNum >= maxNum) v7Pine() else colors.fgSecondary,
                                    modifier = Modifier.testTag("local-item-grade-$key"),
                                )
                            }
                            else -> {
                                grade?.let {
                                    Text(
                                        "参考得分：${points(it.num("score"))} / ${points(it.num("max"))}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (it.num("score") >= it.num("max")) colors.successFg else colors.fgSecondary,
                                    )
                                }
                            }
                        }
                        if (mcq) {
                            (item["answer"] as? JsonPrimitive)?.intOrNull?.let { correct ->
                                Text("参考答案：" + item.strings("options")[correct], style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                            }
                        }
                    }

                    val gradeRespObj = grade?.get("gradingResponse") as? JsonObject
                    if (gradeRespObj != null) {
                        val gradeResp = remember(gradeRespObj) { PracticeGradingJson.decodeResponse(gradeRespObj) }
                        val criteriaList = (item["rubricCriteria"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                        val itemSource = item.getValue("source").jsonObject
                        val jumpHandler: (Int, String) -> Unit = { pg, quote ->
                            val targetBook = criteriaList.firstOrNull { cr ->
                                cr.num("pdfPage").toInt() == pg && cr.str("sourceBookId").isNotBlank()
                            }?.str("sourceBookId") ?: itemSource.str("docId")
                            if (onJumpToBookPage != null && targetBook.isNotBlank()) {
                                onJumpToBookPage(targetBook, pg, quote)
                            } else {
                                controller.showSource(itemSource)
                            }
                        }
                        if (gradeResp.rubricItems.isNotEmpty()) {
                            RubricAnnotationSheet(
                                studentDraft = grade.str("studentDraft").ifBlank { displayDraft },
                                response = gradeResp,
                                onJumpPage = jumpHandler,
                                modifier = Modifier.testTag("local-rubric-sheet-$key"),
                            )
                        }
                        gradeResp.diagnosis?.takeIf { it.errorClass != MisconceptionErrorClass.NONE }?.let { diag ->
                            MisconceptionFeedbackCard(
                                diagnosis = diag,
                                variant = gradeResp.variant,
                                onJumpPage = jumpHandler,
                                onVariantMastered = { variant ->
                                    if (library != null) {
                                        scope.launch(Dispatchers.IO) {
                                            val bookId = itemSource.str("docId")
                                            val topicId = item.str("topicId").ifBlank { key }
                                            val courseId = paperObj.str("courseId")
                                            if (bookId.isNotBlank()) {
                                                library.recordMasteryEvidence(
                                                    bookId = bookId,
                                                    tocNodeId = topicId,
                                                    page = variant.pdfPage.coerceAtLeast(1),
                                                    canonicalName = item.str("weekLabel").ifBlank { topicId },
                                                    signal = LearnerSignalType.POST_QUIZ_T1,
                                                    scoreRatio = 1.0,
                                                    guessed = false,
                                                    errorClass = MisconceptionErrorClass.NONE,
                                                    conceptKeyOverride = variant.conceptKey.takeIf { it.isNotBlank() }
                                                        ?: item.str("conceptKey").takeIf { it.isNotBlank() },
                                                    collectionId = courseId.takeIf { it.isNotBlank() },
                                                )
                                            }
                                            if (courseId.isNotBlank()) {
                                                withContext(Dispatchers.Main) {
                                                    onMasteryUpdated?.invoke(courseId)
                                                }
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("local-misconception-card-$key"),
                            )
                        }
                    }
                }
                // 清空和题源同一行；Tab 顺序仍是 作答 → 清空 → 题源 → 下一题。
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    clear?.let { onClear ->
                        V7TextButton(onClear, enabled = editable, modifier = Modifier.testTag("local-clear-$key")) { Text("清空作答") }
                    }
                    V7TextButton({ controller.showSource(item.getValue("source").jsonObject) }, enabled = !ui.busy,
                        modifier = Modifier.testTag("local-source-$key")) {
                        Text("查看题源", style = MaterialTheme.typography.labelLarge, color = v7Pine(), textDecoration = TextDecoration.Underline)
                    }
                }
            }
        }
        if (!submitted) PrimaryButton({ confirm = true }, enabled = !ui.busy && !ui.conflict,
            modifier = Modifier.testTag("local-submit")) { Text("交卷") }
    }
    if (confirm && !submitted) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("交卷吗？") },
        text = { Text("交卷后不能修改本次作答，仍可查看结果和当时保存的题源。", style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            PrimaryButton({ confirm = false; scope.launch { controller.submit(grader, onMasteryUpdated) } },
                modifier = Modifier.testTag("local-confirm-submit")) { Text("确认交卷") }
        }, dismissButton = { V7TextButton({ confirm = false }) { Text("继续作答") } })
}
