package com.coach.desktop.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachEmptyState
import com.coach.ui.v7.CoachErrorBanner
import com.coach.ui.v7.CoachLoadingDots
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One open question for the current chapter. Generated once, graded point by point,
 * and a second try rejudges only the points that were missed.
 */
@Composable
internal fun ChapterOpenQuestionCard(
    bookId: String,
    chapterId: String,
    chapterTitle: String,
    blocks: List<SourceBlock>,
    draft: ChapterPracticeDraft,
    explainer: BookExplainer,
    aiEnabled: Boolean,
    onDraftChange: (ChapterPracticeDraft) -> Unit,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
    onOpenLogin: () -> Unit,
    onPointsGraded: (ChapterOpenQuestion, ChapterOpenAttempt) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val question = remember(draft.openQuestionJson) { ChapterOpenJson.decodeQuestion(draft.openQuestionJson) }
    val attempts = remember(draft.openAttemptsJson) { ChapterOpenJson.decodeAttempts(draft.openAttemptsJson) }
    var busy by remember(chapterId) { mutableStateOf(false) }
    var error by remember(chapterId) { mutableStateOf<String?>(null) }
    var trouble by remember(chapterId) { mutableStateOf(ExplainTrouble.Other) }
    var fetchNonce by remember(chapterId) { mutableIntStateOf(0) }
    val prose = remember(blocks) { blocks.filter { !it.heading && it.text.isNotBlank() } }

    LaunchedEffect(chapterId, draft.openQuestionJson, fetchNonce, aiEnabled, prose.size) {
        if (question != null || !aiEnabled || prose.isEmpty()) return@LaunchedEffect
        if (fetchNonce == 0 && draft.openQuestionJson.isNotBlank()) {
            error = "保存的开放题读不出来，可以重新出一道。"
            return@LaunchedEffect
        }
        busy = true
        error = null
        try {
            val wire = ChapterOpenJson.buildGenerateRequest(bookId, chapterId, prose)
            val res = withContext(Dispatchers.IO) { explainer.generateChapterOpenQuestion(wire) }
            val body = res.body
            if (body == null) {
                error = res.error ?: "这一章的开放题需要联网。读过的内容还在。"
                trouble = res.trouble ?: ExplainTrouble.Other
            } else {
                val saved = ChapterOpenJson.decodeQuestion(body.toString())
                if (saved == null) {
                    error = "这一章暂时没能整理出开放题，请稍后重试。"
                } else {
                    onDraftChange(draft.copy(openQuestionJson = ChapterOpenJson.encodeQuestion(saved)))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "这一章的开放题需要联网。读过的内容还在。"
        } finally {
            busy = false
        }
    }

    PaperCard(modifier.fillMaxWidth().padding(top = 8.dp).testTag("chapter-open-card")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "这一章，用自己的话说",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.fgPrimary,
            )
            if (chapterTitle.isNotBlank()) {
                Text(chapterTitle, style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
            }
            when {
                prose.isEmpty() -> CoachEmptyState(
                    title = "这一章还没有可以出题的正文。",
                    hint = "翻到有正文的一页后再试。",
                )
                question == null && busy -> CoachLoadingDots(label = "正在整理这一章的开放题…")
                question == null && !aiEnabled -> CoachEmptyState(
                    title = "打开伴读后，才会出这一章的开放题。",
                    hint = "题只生成一次，存在这台电脑上。",
                )
                question == null -> {
                    error?.let { message ->
                        CoachErrorBanner(
                            message = message,
                            retryLabel = if (trouble.needsPairing) "去登录" else "重试",
                            onRetry = {
                                if (trouble.needsPairing) onOpenLogin() else fetchNonce++
                            },
                            modifier = Modifier.testTag("chapter-open-error"),
                        )
                    } ?: CoachEmptyState(
                        title = "这一章的开放题还没准备好。",
                        hint = "出题只会发生一次，之后留在这一章里。",
                        actionLabel = "出题",
                        onAction = { fetchNonce++ },
                    )
                }
                else -> ChapterOpenAnswerBody(
                    bookId = bookId,
                    chapterId = chapterId,
                    question = question,
                    attempts = attempts,
                    blocks = prose,
                    draft = draft,
                    explainer = explainer,
                    onDraftChange = onDraftChange,
                    onOpenAnchor = onOpenAnchor,
                    onOpenLogin = onOpenLogin,
                    onPointsGraded = onPointsGraded,
                )
            }
        }
    }
}

@Composable
private fun ChapterOpenAnswerBody(
    bookId: String,
    chapterId: String,
    question: ChapterOpenQuestion,
    attempts: List<ChapterOpenAttempt>,
    blocks: List<SourceBlock>,
    draft: ChapterPracticeDraft,
    explainer: BookExplainer,
    onDraftChange: (ChapterPracticeDraft) -> Unit,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
    onOpenLogin: () -> Unit,
    onPointsGraded: (ChapterOpenQuestion, ChapterOpenAttempt) -> Unit,
) {
    val colors = CoachTheme.colors
    val scope = rememberCoroutineScope()
    var grading by remember(chapterId, attempts.size) { mutableStateOf(false) }
    var error by remember(chapterId, attempts.size) { mutableStateOf<String?>(null) }
    var trouble by remember(chapterId, attempts.size) { mutableStateOf(ExplainTrouble.Other) }
    val latest = attempts.lastOrNull()
    val missed = ChapterOpenJson.missedIds(latest)
    val canRetry = attempts.size == 1 && missed.isNotEmpty()
    val answering = attempts.isEmpty() || canRetry
    val history = remember(attempts) { ChapterOpenJson.historyOf(attempts) }

    Text(question.stem, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
    attempts.forEachIndexed { index, attempt ->
        Text(
            if (index == 0) "第一次" else "再答一次",
            style = MaterialTheme.typography.labelMedium,
            color = colors.fgSecondary,
            modifier = Modifier.testTag("chapter-open-attempt-$index"),
        )
        RubricAnnotationSheet(
            studentDraft = attempt.answer,
            response = attempt.toGradeResponse(question),
            onJumpPage = { page, quote ->
                val point = question.points.firstOrNull { it.page == page && it.quoteAnchor == quote }
                    ?: question.points.firstOrNull { it.id == attempt.items.getOrNull(0)?.id }
                if (point != null) {
                    onOpenAnchor(
                        BookCompanionAnchor(
                            pdfPage = point.page,
                            blockId = point.blockId,
                            startUtf16 = point.startUtf16,
                            endUtf16 = point.endUtf16,
                            quoteAnchor = point.quoteAnchor,
                        ),
                    )
                }
            },
        )
    }
    if (answering && attempts.size < 2) {
        OutlinedTextField(
            value = draft.openAnswerDraft,
            onValueChange = { onDraftChange(draft.copy(openAnswerDraft = it)) },
            placeholder = {
                Text(if (canRetry) "只补上次没答到的点" else "用自己的话讲这一章")
            },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth().testTag("chapter-open-input"),
        )
        PrimaryButton(
            onClick = {
                if (draft.openAnswerDraft.isBlank() || grading) return@PrimaryButton
                grading = true
                error = null
                scope.launch {
                    try {
                        val retryIds = if (canRetry) missed else emptyList()
                        val wire = ChapterOpenJson.buildGradeRequest(
                            bookId, chapterId, question, draft.openAnswerDraft, retryIds, blocks,
                        )
                        when (val res = withContext(Dispatchers.IO) { explainer.gradeAndDiagnosePractice(wire) }) {
                            is PracticeGradeDiagnoseResult.Ready -> {
                                val items = ChapterOpenJson.judgementsFrom(res.response)
                                val next = attempts + ChapterOpenAttempt(
                                    n = attempts.size + 1,
                                    answer = draft.openAnswerDraft,
                                    items = items,
                                )
                                onPointsGraded(question, next.last())
                                onDraftChange(
                                    draft.copy(
                                        openAttemptsJson = ChapterOpenJson.encodeAttempts(next),
                                        openAnswerDraft = "",
                                    ),
                                )
                            }
                            is PracticeGradeDiagnoseResult.Failed -> {
                                error = res.message
                                trouble = res.trouble
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = "批改需要联网。你的回答已留在这台电脑上。"
                    } finally {
                        grading = false
                    }
                }
            },
            enabled = draft.openAnswerDraft.isNotBlank() && !grading,
            modifier = Modifier.testTag("chapter-open-submit"),
        ) {
            Text(when {
                grading -> "批改中…"
                canRetry -> "再答一次"
                else -> "提交"
            })
        }
    }
    if (grading) CoachLoadingDots(label = "正在逐点看你的回答…")
    error?.let { message ->
        CoachErrorBanner(
            message = message,
            retryLabel = if (trouble.needsPairing) "去登录" else "知道了",
            onRetry = {
                if (trouble.needsPairing) onOpenLogin() else error = null
            },
            modifier = Modifier.testTag("chapter-open-grade-error"),
        )
    }
    if (history.attempts.size > 1) {
        Text(
            "两次结果都留在学习记录里。",
            style = MaterialTheme.typography.labelSmall,
            color = colors.fgSecondary,
        )
    }
}

private fun ChapterOpenAttempt.toGradeResponse(question: ChapterOpenQuestion): PracticeGradeDiagnoseResponse {
    val byId = question.points.associateBy { it.id }
    return PracticeGradeDiagnoseResponse(
        mode = "CHAPTER_OPEN",
        rubricItems = items.map { item ->
            val point = byId[item.id]
            PracticeRubricItemGrade(
                cid = item.id,
                verdict = item.verdict,
                feedbackNoteZh = item.reason,
                pdfPage = point?.page ?: 0,
                quoteAnchor = point?.quoteAnchor.orEmpty(),
                anchorVerified = point != null,
                missCause = item.missCause,
            )
        },
        summaryMetaLine = "已核对 ${items.size} 个得分点。",
    )
}
