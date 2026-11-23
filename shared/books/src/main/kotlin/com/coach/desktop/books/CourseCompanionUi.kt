package com.coach.desktop.books

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachErrorBanner
import com.coach.ui.v7.CoachLoadingDots
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Course companion drawer hosted inside [TextbookReader]:
 * - Top L1 -> L4 authority-ordered source switcher
 * - Authority + tentative exam disclosure notice
 * - Cross-material key points and verified citation pills (including `[mm:ss]` transcripts)
 * - Cross-material discrepancy comparison (`各材料讲法差异`)
 * - 300-word essay practice workspace with live counter and continuous persistence
 */
@Composable
internal fun CourseCompanionPanel(
    courseContext: CourseReaderContext,
    activeBookId: String,
    activePage: Int,
    onSwitchSource: (bookId: String, page: Int) -> Unit,
    onJumpCitation: (CourseCitationRef) -> Unit,
    onSavePracticeDraft: (CourseTopicPracticeDraft) -> Unit,
    onRefreshPack: () -> Unit,
) {
    val colors = CoachTheme.colors
    val pack = courseContext.pack
    val scope = rememberCoroutineScope()
    val gradingHost = LocalPracticeGradingHost.current
    var draftText by remember(courseContext.topicId, courseContext.resumeDraft?.topicId) {
        mutableStateOf(courseContext.resumeDraft?.text.orEmpty())
    }
    var gradingInFlight by remember(courseContext.topicId) { mutableStateOf(false) }
    var gradeResponse by remember(courseContext.topicId) { mutableStateOf<PracticeGradeDiagnoseResponse?>(null) }
    var gradeError by remember(courseContext.topicId) { mutableStateOf<String?>(null) }
    val wordTarget = pack?.practicePrompt?.wordTarget ?: 300

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("course-companion-panel"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${courseContext.weekLabel} · ${courseContext.topicTitle}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.fgPrimary,
                )
                Text(
                    courseContext.authorityNotice.ifBlank { "按 L1 大纲/讲义 -> L2 核心教材 -> L3 文献 -> L4 笔记顺序对照" },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgSecondary,
                )
            }
            V7TextButton(
                onClick = onRefreshPack,
                modifier = Modifier.testTag("course-pack-refresh"),
            ) {
                Text("更新合卷")
            }
        }

        val examMode = courseContext.examProfile.isExam
        if (examMode && (courseContext.examProfile.examDateTentative || courseContext.examProfile.examFormatTentative)) {
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("course-tentative-notice"),
                shape = RoundedCornerShape(8.dp),
                color = colors.warningBg,
                border = BorderStroke(1.dp, colors.borderSubtle),
            ) {
                Text(
                    text = buildString {
                        append("暂定提醒：")
                        if (courseContext.examProfile.examDate.isNotBlank()) {
                            append("考试日期 ${courseContext.examProfile.examDate}（暂定）")
                        } else {
                            append("考试时间待最终确认（暂定）")
                        }
                        if (courseContext.examProfile.examFormat.isNotBlank()) {
                            append(" · 题型 ${courseContext.examProfile.examFormat}")
                            if (courseContext.examProfile.examFormatTentative) append("（暂定）")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.warningFg,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        // Top L1 -> L4 source switcher
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag("course-source-switcher"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            courseContext.orderedSources.forEach { src ->
                val selected = src.bookId == activeBookId
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) colors.actionPrimary.copy(alpha = 0.14f) else colors.surface2,
                    border = BorderStroke(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) colors.actionPrimary else colors.borderSubtle,
                    ),
                ) {
                    V7TextButton(
                        onClick = { onSwitchSource(src.bookId, src.startPage) },
                        modifier = Modifier.testTag("course-source-chip-${src.bookId}"),
                    ) {
                        Text("L${src.authorityRank} ${src.role.labelZh} · ${src.bookTitle}")
                    }
                }
            }
        }

        HorizontalDivider(color = colors.borderSubtle)

        if (pack != null) {
            Text(pack.summary, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)

            if (pack.discrepancies.isNotEmpty()) {
                PaperCard(Modifier.fillMaxWidth().testTag("course-discrepancies-block")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "各材料讲法差异（以 L1 为准）",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.fgPrimary,
                        )
                        pack.discrepancies.forEach { diff ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(diff.point, style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
                                Text("L1 口径：${diff.authoritativeView}", style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
                                Text("对照教材/文献：${diff.secondaryView}", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                                Text(if (examMode) "备考建议：${diff.advice}" else "怎么读：${diff.advice}", style = MaterialTheme.typography.bodySmall, color = colors.accentDeep)
                                if (diff.citations.isNotEmpty()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        diff.citations.forEachIndexed { idx, c ->
                                            V7TextButton(
                                                onClick = { onJumpCitation(c) },
                                                modifier = Modifier.testTag("course-diff-cit-$idx"),
                                            ) {
                                                Text(formatCitationLabel(c))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (pack.keyPoints.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (examMode) "核心考点与多源出处" else "核心要点与多源出处",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.fgPrimary,
                    )
                    pack.keyPoints.forEachIndexed { kpIdx, kp ->
                        PaperCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(kp.title, style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
                                Text(kp.explanation, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                                if (kp.citations.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        kp.citations.forEachIndexed { cIdx, c ->
                                            PaperButton(
                                                onClick = { onJumpCitation(c) },
                                                modifier = Modifier.testTag("course-citation-${kpIdx}-$cIdx"),
                                            ) {
                                                Text(formatCitationLabel(c))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (pack.citations.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("已核验原文出处", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                    pack.citations.forEachIndexed { idx, c ->
                        PaperButton(
                            onClick = { onJumpCitation(c) },
                            modifier = Modifier.testTag("course-citation-$idx"),
                        ) {
                            Text(formatCitationLabel(c))
                        }
                    }
                }
            }
        }

        // 300-word essay continuous practice workspace
        PaperCard(Modifier.fillMaxWidth().testTag("course-essay-section")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "短论述练习",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.fgPrimary,
                    )
                    Text(
                        CourseEssayWordCounter.progressLabel(draftText, wordTarget),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.fgSecondary,
                        modifier = Modifier.testTag("course-essay-wordcount"),
                    )
                }
                Text(
                    pack?.practicePrompt?.stem
                        ?: "请围绕「${courseContext.topicTitle}」写出核心论点、适用条件与关键差异对比（约 $wordTarget 词）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgPrimary,
                )
                OutlinedTextField(
                    value = draftText,
                    onValueChange = { updated ->
                        draftText = updated
                        onSavePracticeDraft(
                            CourseTopicPracticeDraft(
                                topicId = courseContext.topicId,
                                text = updated,
                                activeSourceBookId = activeBookId,
                                activePage = activePage,
                                companionOpen = true,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    },
                    placeholder = { Text("在此输入论述草稿，系统会在每次输入时自动保存以便重开后继续…") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().testTag("course-essay-input"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrimaryButton(
                        onClick = {
                            val host = gradingHost
                            if (host == null) {
                                gradeError = "请先连接学习服务后再提交批改，当前草稿已保存。"
                                return@PrimaryButton
                            }
                            gradingInFlight = true
                            gradeError = null
                            scope.launch {
                                try {
                                    val req = withContext(Dispatchers.IO) {
                                        PracticeGradingJson.buildCourseEssayRequest(
                                            courseContext = courseContext,
                                            activeBookId = activeBookId,
                                            activePage = activePage,
                                            draftText = draftText,
                                            library = host.library,
                                        )
                                    }
                                    when (val res = host.gradeAndDiagnose(req)) {
                                        is PracticeGradeDiagnoseResult.Ready -> {
                                            gradeResponse = res.response
                                            val lib = host.library
                                            if (lib != null) {
                                                withContext(Dispatchers.IO) {
                                                    val ratio = if (res.response.maxMarks > 0.0) {
                                                        (res.response.totalAwarded / res.response.maxMarks).coerceIn(0.0, 1.0)
                                                    } else {
                                                        val items = res.response.rubricItems
                                                        if (items.isEmpty()) 0.5 else items.map {
                                                            when (it.verdict) {
                                                                "FULL" -> 1.0
                                                                "PARTIAL" -> 0.5
                                                                else -> 0.0
                                                            }
                                                        }.average()
                                                    }
                                                    val errCls = res.response.diagnosis?.errorClass?.takeIf { it != MisconceptionErrorClass.NONE }
                                                    lib.recordMasteryEvidence(
                                                        bookId = activeBookId,
                                                        tocNodeId = courseContext.topicId,
                                                        page = activePage.coerceAtLeast(1),
                                                        canonicalName = courseContext.topicTitle,
                                                        signal = LearnerSignalType.RUBRIC_GRADE,
                                                        scoreRatio = ratio,
                                                        guessed = false,
                                                        errorClass = errCls,
                                                        collectionId = courseContext.courseId,
                                                    )
                                                }
                                            }
                                        }
                                        is PracticeGradeDiagnoseResult.Failed -> {
                                            gradeError = res.message
                                        }
                                    }
                                } catch (c: CancellationException) {
                                    throw c
                                } catch (_: Exception) {
                                    gradeError = "逐点批改暂时不可用，你的论述草稿已保存。"
                                } finally {
                                    gradingInFlight = false
                                }
                            }
                        },
                        enabled = draftText.isNotBlank() && !gradingInFlight,
                        modifier = Modifier.testTag("course-essay-submit"),
                    ) {
                        Text(if (gradingInFlight) "批改中…" else "提交批改")
                    }
                }
                if (gradingInFlight) {
                    CoachLoadingDots(label = "正在对照课程原页逐点核对…")
                }
                gradeError?.let { err ->
                    CoachErrorBanner(
                        message = err,
                        retryLabel = "重试",
                        onRetry = { gradeError = null },
                        modifier = Modifier.testTag("course-essay-grade-error"),
                    )
                }
                gradeResponse?.let { resp ->
                    val jumpToPage: (Int, String) -> Unit = { pg, quote ->
                        val matchedSource = courseContext.orderedSources.firstOrNull { pg in it.startPage..it.endPage }
                            ?: courseContext.orderedSources.firstOrNull()
                        val targetBookId = matchedSource?.bookId ?: activeBookId
                        val targetTitle = matchedSource?.bookTitle ?: courseContext.topicTitle
                        onJumpCitation(
                            CourseCitationRef(
                                bookId = targetBookId,
                                bookTitle = targetTitle,
                                role = matchedSource?.role ?: CourseMaterialRole.CORE_TEXTBOOK,
                                page = pg,
                                startUtf16 = 0,
                                endUtf16 = quote.length,
                                quote = quote,
                            ),
                        )
                    }
                    RubricAnnotationSheet(
                        studentDraft = draftText,
                        response = resp,
                        onJumpPage = jumpToPage,
                    )
                    resp.diagnosis?.takeIf { it.errorClass != MisconceptionErrorClass.NONE }?.let { diag ->
                        MisconceptionFeedbackCard(
                            diagnosis = diag,
                            variant = resp.variant,
                            onJumpPage = jumpToPage,
                            onVariantMastered = { variant ->
                                val lib = gradingHost?.library ?: return@MisconceptionFeedbackCard
                                scope.launch(Dispatchers.IO) {
                                    lib.recordMasteryEvidence(
                                        bookId = activeBookId,
                                        tocNodeId = courseContext.topicId,
                                        page = variant.pdfPage.coerceAtLeast(1),
                                        canonicalName = courseContext.topicTitle,
                                        signal = LearnerSignalType.POST_QUIZ_T1,
                                        scoreRatio = 1.0,
                                        guessed = false,
                                        errorClass = MisconceptionErrorClass.NONE,
                                        conceptKeyOverride = variant.conceptKey.takeIf(String::isNotBlank),
                                        collectionId = courseContext.courseId,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

private fun formatCitationLabel(c: CourseCitationRef): String {
    val ts = c.timestampLabel?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
    return "${c.bookTitle}$ts · 第 ${c.page} 页"
}
