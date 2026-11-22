package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.domain.fsrs.FsrsRating
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.CoachErrorBanner
import com.coach.ui.v7.CoachLoadingDots
import com.coach.ui.v7.Desk
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import com.coach.ui.v7.v7FocusRing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun LocalBookReviews(ui: LibraryUi, onBack: () -> Unit, onOpen: (LocalBookReview) -> Unit) {
    val queue = ui.todayQueue
    Desk(crumb = "今日复习", onBack = onBack, backLabel = "返回首页", showNavigation = false, hasMargin = false, showFocusToggle = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("local-reviews"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("今日复习", style = MaterialTheme.typography.headlineMedium)
                Text("答错、猜的和到期题都在这里；原答和每次复习都保留。", color = CoachTheme.colors.fgSecondary)
                if (ui.reviews.isEmpty()) {
                    CoachCardMeta("今日复习已完成。", Modifier.testTag("local-review-done"))
                } else if (queue.cards.isNotEmpty()) {
                    CoachCardMeta(queue.summary, Modifier.testTag("local-review-summary"))
                    PrimaryButton(
                        onClick = { onOpen(queue.cards.first()) },
                        enabled = !ui.busy,
                        modifier = Modifier.testTag("local-review-start"),
                    ) { Text("开始今日复习") }
                }
                ui.reviews.forEachIndexed { index, review ->
                    PaperTile(!ui.busy, Modifier.fillMaxWidth().testTag("local-review-item-$index"), { onOpen(review) },
                        PaddingValues(20.dp), 8.dp) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(review.bookTitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                            Text(review.sources.joinToString(" · ") { it.label }, style = MaterialTheme.typography.labelLarge,
                                color = v7Zhu(), modifier = Modifier.testTag("local-review-reasons-$index"))
                        }
                        CoachCardMeta("${review.lessonTitle} · ${review.mode.label} · 上次作答：${review.originalAnswer}")
                        Text(review.practice.prompt, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun LocalBookReviewQuestion(
    review: LocalBookReview,
    enabled: Boolean,
    sourceFocus: FocusRequester,
    returnFocus: SourceFocus?,
    onFocusRestored: () -> Unit,
    onBack: () -> Unit,
    onSource: () -> Unit,
    onAnswer: (PracticeAnswer) -> Unit,
    position: Pair<Int, Int>? = null,
    deferred: Int = 0,
    onNext: (() -> Unit)? = null,
    onRate: ((FsrsRating, PracticeAnswer) -> Unit)? = null,
) {
    LaunchedEffect(returnFocus, enabled) {
        if (enabled && returnFocus != null) {
            withFrameNanos { }
            sourceFocus.requestFocus()
            onFocusRestored()
        }
    }
    val colors = CoachTheme.colors
    val scope = rememberCoroutineScope()
    val gradingHost = LocalPracticeGradingHost.current
    var reviewGradeLoading by remember(review.book, review.lesson, review.mode) { mutableStateOf(false) }
    var reviewGradeResponse by remember(review.book, review.lesson, review.mode) { mutableStateOf<PracticeGradeDiagnoseResponse?>(null) }
    var reviewGradeError by remember(review.book, review.lesson, review.mode) { mutableStateOf<String?>(null) }
    val baseLessonId = remember(review.lesson) { review.lesson.substringBefore("#q") }

    fun triggerReviewDiagnose(answerText: String, mode: String) {
        val host = gradingHost ?: return
        if (reviewGradeLoading) return
        reviewGradeLoading = true
        reviewGradeError = null
        scope.launch {
            try {
                val req = withContext(Dispatchers.IO) {
                    PracticeGradingJson.buildReviewDiagnoseRequest(
                        review = review,
                        studentAnswer = answerText,
                        library = host.library,
                        mode = mode,
                    )
                }
                when (val res = host.gradeAndDiagnose(req)) {
                    is PracticeGradeDiagnoseResult.Ready -> {
                        reviewGradeResponse = res.response
                        val errCls = res.response.diagnosis?.errorClass?.takeIf { it != MisconceptionErrorClass.NONE }
                        if (errCls != null && host.library != null) {
                            withContext(Dispatchers.IO) {
                                host.library.recordMasteryEvidence(
                                    bookId = review.book,
                                    tocNodeId = baseLessonId,
                                    page = res.response.variant?.pdfPage?.coerceAtLeast(1) ?: 1,
                                    canonicalName = review.lessonTitle,
                                    signal = LearnerSignalType.MISCONCEPTION_DIAG,
                                    errorClass = errCls,
                                )
                            }
                        }
                    }
                    is PracticeGradeDiagnoseResult.Failed -> reviewGradeError = res.message
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                reviewGradeError = "错因诊断暂时不可用，已保留当前复习进度。"
            } finally {
                reviewGradeLoading = false
            }
        }
    }

    LaunchedEffect(review.book, review.lesson, review.answer.revealed, review.answer.text, review.answer.understood) {
        val wrongChoice = review.mode == QuestionMode.CHOICE &&
            review.answer.revealed &&
            review.answer.text.isNotBlank() &&
            review.answer.text != review.practice.answer
        val missedShort = review.mode == QuestionMode.SHORT_ANSWER &&
            review.answer.revealed &&
            review.answer.understood == false
        if ((wrongChoice || missedShort) && reviewGradeResponse == null && gradingHost != null) {
            triggerReviewDiagnose(review.answer.text, "DIAGNOSE_ONLY")
        }
    }
    Desk(
        crumb = review.bookTitle,
        onBack = onBack,
        backLabel = "暂停退出",
        showNavigation = false,
        hasMargin = false,
        showFocusToggle = false,
        status = position?.let { "${it.first} / ${it.second}" } ?: "",
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("local-review-question"),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoachCardMeta("${review.bookTitle} · ${review.lessonTitle}")
                    if (position != null) {
                        CoachCardMeta("${position.first} / ${position.second}", Modifier.testTag("local-review-position"))
                    }
                }
                Text("复习 · ${review.lessonTitle}", style = MaterialTheme.typography.headlineMedium)
                Text("上次作答：${review.originalAnswer}", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)

                if (review.mode == QuestionMode.CHOICE) {
                    SourcePractice(
                        review.lesson,
                        review.mode,
                        review.practice,
                        review.answer,
                        onAnswer,
                        onSource,
                        enabled,
                        sourceFocus,
                        showReference = false,
                    )
                    if (!review.answer.revealed) {
                        PrimaryButton(
                            onClick = { onAnswer(review.answer.copy(revealed = true)) },
                            enabled = enabled,
                            modifier = Modifier.testTag("review-show-answer"),
                        ) { Text("看答案") }
                    } else {
                        if (review.answer.text.isBlank() && review.practice.answer.isNotBlank()) {
                            PaperCard(Modifier.testTag("review-choice-answer"), contentPadding = PaddingValues(16.dp)) {
                                Text("参考答案：${review.practice.answer}", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
                            }
                        }
                        if (review.completed) {
                            Text("这题已复习。", color = colors.successFg, modifier = Modifier.testTag("local-review-completed"))
                            Text(
                                "下次回顾：${LocalDate.ofEpochDay(review.dueDay).format(DateTimeFormatter.ofPattern("M月d日"))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.fgSecondary,
                                modifier = Modifier.testTag("local-review-next-due"),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                PaperButton(
                                    onClick = {
                                        val rated = review.answer.copy(revealed = true, understood = false)
                                        if (onRate != null) onRate(FsrsRating.AGAIN, rated) else onAnswer(rated)
                                    },
                                    enabled = enabled,
                                    modifier = Modifier.testTag("review-rate-again"),
                                ) { Text("没记住") }
                                if (onNext != null) {
                                    PrimaryButton(
                                        onClick = onNext,
                                        enabled = enabled,
                                        modifier = Modifier.testTag("local-review-next"),
                                    ) {
                                        Text(if (position != null && position.first < position.second) "下一题" else "完成今日复习")
                                    }
                                }
                            }
                        } else {
                            Text(
                                "对照结果后可直接自评，或点击原文对照。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.fgSecondary,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                PaperButton(
                                    onClick = {
                                        val rated = review.answer.copy(revealed = true, understood = false)
                                        if (onRate != null) onRate(FsrsRating.AGAIN, rated) else onAnswer(rated)
                                    },
                                    enabled = enabled,
                                    modifier = Modifier.testTag("review-rate-again"),
                                ) { Text("没记住") }
                                PrimaryButton(
                                    onClick = {
                                        val rated = review.answer.copy(revealed = true, understood = true)
                                        if (onRate != null) onRate(FsrsRating.GOOD, rated) else onAnswer(rated)
                                    },
                                    enabled = enabled,
                                    modifier = Modifier.testTag("review-rate-good"),
                                ) { Text("记住了") }
                            }
                        }
                    }
                } else {
                    // 单卡回忆流：先想答案（可选写下要点或标记「猜的」），再点「看答案」→「没记住」/「记住了」。
                    var draft by remember(review.book, review.lesson, review.mode) { mutableStateOf(review.answer.text) }
                    var typing by remember(review.book, review.lesson, review.mode) { mutableStateOf(false) }
                    LaunchedEffect(review.answer.text, typing) { if (!typing) draft = review.answer.text }
                    Text(review.practice.prompt, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        V7TextButton(onSource, enabled = enabled, modifier = Modifier.focusRequester(sourceFocus).testTag("open-practice-source")) {
                            Text("查看对应原文")
                        }
                        FilterChip(
                            selected = review.answer.guessed,
                            onClick = { onAnswer(review.answer.copy(text = draft, guessed = !review.answer.guessed)) },
                            enabled = !review.answer.revealed && enabled,
                            label = { Text("这题我是猜的") },
                            border = null,
                            modifier = Modifier.testTag("practice-guessed"),
                        )
                    }
                    val shape = RoundedCornerShape(CoachV7.Radius.btn)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("先在心里想答案，或简单记下要点（可选）", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it; onAnswer(review.answer.copy(text = it, revealed = false, understood = null)) },
                            minLines = 2,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.fgPrimary),
                            cursorBrush = SolidColor(colors.fgPrimary),
                            modifier = Modifier.fillMaxWidth().onFocusChanged { typing = it.isFocused }
                                .semantics { contentDescription = "写下你的理解" }
                                .v7FocusRing(shape).clip(shape).background(colors.surface2)
                                .border(1.dp, colors.borderSubtle, shape).padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("short-answer"),
                        )
                    }
                    if (!review.answer.revealed) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            PrimaryButton(
                                onClick = { onAnswer(review.answer.copy(text = draft, revealed = true)) },
                                enabled = enabled,
                                modifier = Modifier.testTag("review-show-answer"),
                            ) { Text("看答案") }
                            if (gradingHost != null && draft.isNotBlank()) {
                                PaperButton(
                                    onClick = {
                                        onAnswer(review.answer.copy(text = draft, revealed = true))
                                        triggerReviewDiagnose(draft, "GRADE_AND_DIAGNOSE")
                                    },
                                    enabled = enabled && !reviewGradeLoading,
                                    modifier = Modifier.testTag("review-submit-grade"),
                                ) { Text(if (reviewGradeLoading) "批改中…" else "提交批改") }
                            }
                        }
                    } else {
                        PaperCard(Modifier.testTag("review-reference-card"), contentPadding = PaddingValues(16.dp)) {
                            Text("对照原文", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                            Text(review.practice.reference, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
                        }
                        if (review.completed) {
                            Text("这题已复习。", color = colors.successFg, modifier = Modifier.testTag("local-review-completed"))
                            Text(
                                "下次回顾：${LocalDate.ofEpochDay(review.dueDay).format(DateTimeFormatter.ofPattern("M月d日"))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.fgSecondary,
                                modifier = Modifier.testTag("local-review-next-due"),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            PaperButton(
                                onClick = {
                                    val rated = review.answer.copy(text = draft, revealed = true, understood = false)
                                    if (onRate != null) onRate(FsrsRating.AGAIN, rated) else onAnswer(rated)
                                },
                                enabled = enabled,
                                modifier = Modifier.testTag("review-rate-again"),
                            ) { Text("没记住") }
                            if (review.completed && onNext != null) {
                                PrimaryButton(
                                    onClick = onNext,
                                    enabled = enabled,
                                    modifier = Modifier.testTag("local-review-next"),
                                ) {
                                    Text(if (position != null && position.first < position.second) "下一题" else "完成今日复习")
                                }
                            } else {
                                PrimaryButton(
                                    onClick = {
                                        val rated = review.answer.copy(text = draft, revealed = true, understood = true)
                                        if (onRate != null) onRate(FsrsRating.GOOD, rated) else onAnswer(rated)
                                    },
                                    enabled = enabled,
                                    modifier = Modifier.testTag("review-rate-good"),
                                ) { Text("记住了") }
                            }
                        }
                    }
                }

                if (reviewGradeLoading) {
                    CoachLoadingDots(label = "正在分析错因与生成同类变式…")
                }
                reviewGradeError?.let { err ->
                    CoachErrorBanner(
                        message = err,
                        retryLabel = "重试",
                        onRetry = { triggerReviewDiagnose(review.answer.text, if (review.mode == QuestionMode.SHORT_ANSWER && review.answer.text.isNotBlank()) "GRADE_AND_DIAGNOSE" else "DIAGNOSE_ONLY") },
                        modifier = Modifier.testTag("review-grade-error"),
                    )
                }
                reviewGradeResponse?.let { resp ->
                    if (resp.rubricItems.isNotEmpty()) {
                        RubricAnnotationSheet(
                            studentDraft = review.answer.text,
                            response = resp,
                            onJumpPage = { _, _ -> onSource() },
                        )
                    }
                    resp.diagnosis?.takeIf { it.errorClass != MisconceptionErrorClass.NONE }?.let { diag ->
                        MisconceptionFeedbackCard(
                            diagnosis = diag,
                            variant = resp.variant,
                            onJumpPage = { _, _ -> onSource() },
                            onVariantMastered = { variant ->
                                val lib = gradingHost?.library ?: return@MisconceptionFeedbackCard
                                scope.launch(Dispatchers.IO) {
                                    lib.recordMasteryEvidence(
                                        bookId = review.book,
                                        tocNodeId = baseLessonId,
                                        page = variant.pdfPage.coerceAtLeast(1),
                                        canonicalName = review.lessonTitle,
                                        signal = LearnerSignalType.POST_QUIZ_T1,
                                        scoreRatio = 1.0,
                                        guessed = false,
                                        errorClass = MisconceptionErrorClass.NONE,
                                        conceptKeyOverride = variant.conceptKey.takeIf(String::isNotBlank),
                                    )
                                }
                            },
                        )
                    }
                }

                if (deferred > 0 && position != null) {
                    CoachCardMeta(
                        "今日优先复习 ${position.second} 题，其余 $deferred 题顺延至明天。",
                        Modifier.testTag("local-review-deferred"),
                    )
                }

                if (review.history.isNotEmpty()) PaperCard(contentPadding = PaddingValues(16.dp)) {
                    Text("作答记录 · ${review.history.size} 次", style = MaterialTheme.typography.titleMedium)
                    review.history.forEach { attempt ->
                        Text(
                            "第 ${attempt.number} 次${if (attempt.answer.guessed) " · 猜的" else ""}：${attempt.answer.text}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.fgSecondary,
                            modifier = Modifier.testTag("local-review-history-${attempt.number}"),
                        )
                    }
                }
            }
        }
    }
}

/** V7 朱色：标出复习原因（答错、猜的、到期），随明暗取 token。 */
@Composable
private fun v7Zhu(): androidx.compose.ui.graphics.Color = if (CoachTheme.colors.dark) CoachV7.Dark.zhu else CoachV7.Light.zhu
