package com.coach.desktop.books

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.CoachEmptyState
import com.coach.ui.v7.CoachLoadingDots
import com.coach.ui.v7.PageHeader
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton

private enum class CourseSubView {
    OVERVIEW,
    WEEKLY_PLAN,
    COUNTDOWN_SCHEDULE,
}

internal fun estimateTopicMinutes(topic: CourseTopicEntry): Int {
    val pages = topic.sources.sumOf { (it.endPage - it.startPage + 1).coerceAtLeast(1) }
    if (pages <= 0) return 20
    return (pages * 2).coerceIn(10, 35)
}

@Composable
internal fun CourseSyllabusPromptBar(
    onAddSyllabus: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth().testTag("course-syllabus-prompt-bar"),
        shape = RoundedCornerShape(8.dp),
        color = colors.surface2,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "有考纲或往年题吗？没有也能先学",
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgPrimary,
                modifier = Modifier.weight(1f),
            )
            PaperButton(
                onClick = onAddSyllabus,
                modifier = Modifier.testTag("course-syllabus-prompt-add"),
            ) {
                Text("放进来")
            }
            V7TextButton(
                onClick = onSkip,
                modifier = Modifier.testTag("course-syllabus-prompt-skip"),
            ) {
                Text("跳过")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CourseScreen(
    detail: CourseDetailSnapshot,
    busy: Boolean,
    notice: String?,
    onBack: () -> Unit,
    onOpenTopic: (CourseTopicEntry) -> Unit,
    onAddMaterials: () -> Unit,
    onRealign: () -> Unit,
    onUpdateMaterialAssignment: (bookId: String, weekNum: Int?, role: CourseMaterialRole) -> Unit,
    onRemoveMaterial: (bookId: String) -> Unit,
    onRunHandwrittenVision: (bookId: String) -> Unit,
    onRenameTopic: (topicId: String, newTitle: String) -> Unit,
    onUnlinkTopicSource: (topicId: String, bookId: String) -> Unit,
    onSetTopicStatus: (topicId: String, status: CourseTopicStatus) -> Unit,
    onUpdateExamProfile: (CourseExamProfile) -> Unit,
    onDismissNotice: () -> Unit = {},
    hasActiveMockExam: Boolean = false,
    onOpenMockExam: () -> Unit = {},
    onOpenLiteratureGuide: (() -> Unit)? = null,
) {
    val colors = CoachTheme.colors
    val isExam = detail.isExam
    var subView by remember(detail.courseId, isExam) { mutableStateOf(CourseSubView.OVERVIEW) }
    var courseMenuOpen by remember { mutableStateOf(false) }
    var materialsDialogOpen by remember(detail.courseId) { mutableStateOf(false) }
    var examDialogOpen by remember(detail.courseId) { mutableStateOf(false) }
    var renamingTopic by remember { mutableStateOf<CourseTopicEntry?>(null) }
    var unlinkingTopic by remember { mutableStateOf<CourseTopicEntry?>(null) }
    var excludedExpanded by remember(detail.courseId, detail.excludedTopics.size) {
        mutableStateOf(detail.excludedTopics.size in 1..6)
    }

    val plan = if (isExam) detail.countdownPlan else null
    val topicGapById = remember(plan) {
        plan?.rankedTopics?.associateBy { it.topicId }.orEmpty()
    }
    val orderedExaminableTopics = remember(detail.examinableTopics, plan, isExam) {
        val rankedIds = plan?.rankedTopics?.map { it.topicId }.orEmpty()
        if (!isExam || rankedIds.isEmpty()) {
            detail.examinableTopics
        } else {
            val rankIndex = rankedIds.withIndex().associate { (idx, tid) -> tid to idx }
            detail.examinableTopics.sortedBy { rankIndex[it.topicId] ?: Int.MAX_VALUE }
        }
    }
    val resumeTarget = remember(detail, plan, orderedExaminableTopics, isExam) {
        if (isExam) {
            val focusId = plan?.todayFocusTopicId
            focusId?.let { fid -> orderedExaminableTopics.firstOrNull { it.topicId == fid && it.sources.isNotEmpty() } }
                ?: detail.activeAnchor?.topicId?.let { tid -> orderedExaminableTopics.firstOrNull { it.topicId == tid } }
                ?: orderedExaminableTopics.firstOrNull()
        } else {
            detail.activeAnchor?.topicId?.let { tid -> orderedExaminableTopics.firstOrNull { it.topicId == tid } }
                ?: orderedExaminableTopics.firstOrNull()
        }
    }

    if (isExam && subView != CourseSubView.OVERVIEW) {
        CourseScheduleSubScreen(
            detail = detail,
            subView = subView,
            orderedTopics = orderedExaminableTopics,
            topicGapById = topicGapById,
            onBackToCourse = { subView = CourseSubView.OVERVIEW },
            onOpenTopic = onOpenTopic,
            onAddSyllabus = onAddMaterials,
            onSkipSyllabusPrompt = {
                onUpdateExamProfile(
                    detail.examProfile.copy(syllabusPromptState = CourseSyllabusPromptState.SKIPPED),
                )
            },
            onOpenMockExam = onOpenMockExam,
        )
        return
    }

    Box(Modifier.fillMaxSize().testTag("course-screen"), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                V7TextButton(onBack, modifier = Modifier.testTag("course-back")) {
                    Text("← 书架")
                }
                Box {
                    V7TextButton(
                        onClick = { courseMenuOpen = true },
                        modifier = Modifier.testTag("course-overflow"),
                    ) {
                        Text("⋯")
                    }
                    DropdownMenu(expanded = courseMenuOpen, onDismissRequest = { courseMenuOpen = false }) {
                        if (isExam) {
                            DropdownMenuItem(
                                text = { Text("排本周计划") },
                                modifier = Modifier.testTag("course-menu-weekly-plan-action"),
                                onClick = {
                                    courseMenuOpen = false
                                    subView = CourseSubView.WEEKLY_PLAN
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("考前排程") },
                                modifier = Modifier.testTag("course-menu-countdown-action"),
                                onClick = {
                                    courseMenuOpen = false
                                    subView = CourseSubView.COUNTDOWN_SCHEDULE
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("进入考前限时综合练习") },
                                modifier = Modifier.testTag("course-mock-exam-action"),
                                onClick = {
                                    courseMenuOpen = false
                                    onOpenMockExam()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("编辑考试日与安排") },
                                modifier = Modifier.testTag("course-exam-profile-action"),
                                onClick = {
                                    courseMenuOpen = false
                                    examDialogOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("改为不考") },
                                modifier = Modifier.testTag("course-disable-exam-action"),
                                onClick = {
                                    courseMenuOpen = false
                                    onUpdateExamProfile(detail.examProfile.copy(examEnabled = false, examDate = ""))
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("要考") },
                                modifier = Modifier.testTag("course-enable-exam-action"),
                                onClick = {
                                    courseMenuOpen = false
                                    onUpdateExamProfile(detail.examProfile.copy(examEnabled = true))
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("查看本课材料清单") },
                            modifier = Modifier.testTag("course-materials-action"),
                            onClick = {
                                courseMenuOpen = false
                                materialsDialogOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (isExam) "重新对齐课程" else "重新整理目录") },
                            modifier = Modifier.testTag("course-realign-action"),
                            onClick = {
                                courseMenuOpen = false
                                onRealign()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (isExam) "添加课程材料" else "添加材料") },
                            modifier = Modifier.testTag("course-add-materials-action"),
                            onClick = {
                                courseMenuOpen = false
                                onAddMaterials()
                            },
                        )
                    }
                }
            }

            PageHeader(
                kicker = when {
                    !isExam -> "课程学习"
                    detail.hasSyllabusOrScope -> "课程备考 · L1 大纲/讲义优先"
                    else -> "课程备考"
                },
                title = detail.title,
                deck = if (isExam) buildExamSummaryLine(detail) else buildRegularSummaryLine(detail, orderedExaminableTopics),
                meta = buildCourseMetaLine(detail, orderedExaminableTopics),
            )

            if (onOpenLiteratureGuide != null) {
                PaperButton(onOpenLiteratureGuide, modifier = Modifier.testTag("course-literature-guide")) {
                    Text("本周文献导读")
                }
            }

            if (isExam) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAddMaterials)
                        .testTag("course-exam-scope-line"),
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surface2,
                    border = BorderStroke(1.dp, colors.borderSubtle),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when {
                                detail.syllabusNarrowsScope -> "已按考纲安排范围，可继续补充考纲或往年题"
                                detail.hasSyllabusOrScope -> "已收到考纲，暂按整本书安排"
                                else -> "按整本书安排，放进考纲后可以缩小范围"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.fgPrimary,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        Text(
                            if (detail.hasSyllabusOrScope) "补充材料" else "放进考纲",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.actionPrimary,
                        )
                    }
                }

                // Visible, editable record of the user's exam & syllabus answers
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("course-exam-status-bar"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val statusSummary = buildString {
                        append("要考")
                        if (detail.examProfile.examDate.isNotBlank()) {
                            append(" · 考试日 ${detail.examProfile.examDate}")
                        }
                        when {
                            detail.hasSyllabusOrScope -> append(" · 已放考纲")
                            detail.examProfile.syllabusPromptState == CourseSyllabusPromptState.SKIPPED -> append(" · 暂不放考纲")
                        }
                    }
                    CoachCardMeta(
                        text = statusSummary,
                        modifier = Modifier.weight(1f).testTag("course-exam-status-text"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        V7TextButton(
                            onClick = { examDialogOpen = true },
                            modifier = Modifier.testTag("course-edit-exam-date-btn"),
                        ) {
                            Text("改考试日")
                        }
                        V7TextButton(
                            onClick = { onUpdateExamProfile(detail.examProfile.copy(examEnabled = false, examDate = "")) },
                            modifier = Modifier.testTag("course-switch-non-exam-btn"),
                        ) {
                            Text("改为不考")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().testTag("course-exam-tools-row"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaperButton(
                        onClick = { subView = CourseSubView.WEEKLY_PLAN },
                        modifier = Modifier.testTag("course-weekly-plan-btn"),
                    ) {
                        Text("排本周计划")
                    }
                    PaperButton(
                        onClick = { subView = CourseSubView.COUNTDOWN_SCHEDULE },
                        modifier = Modifier.testTag("course-countdown-plan-btn"),
                    ) {
                        Text("考前排程")
                    }
                    PaperButton(
                        onClick = onOpenMockExam,
                        modifier = Modifier.testTag("course-mock-exam-btn"),
                    ) {
                        Text("模考")
                    }
                }
            }

            if (isExam && hasActiveMockExam) {
                PaperCard(Modifier.fillMaxWidth().testTag("course-active-mock-banner")) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("还有一套未交卷模考", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                            CoachCardMeta("作答草稿与计时仍在保存。")
                        }
                        PaperButton(
                            onClick = onOpenMockExam,
                            modifier = Modifier.testTag("course-resume-mock-btn"),
                        ) {
                            Text("继续未交卷模考")
                        }
                    }
                }
            }

            if (busy) {
                CoachLoadingDots(label = if (isExam) "正在对齐或整理课程材料…" else "正在整理阅读目录…")
            }

            if (!notice.isNullOrBlank()) {
                CourseNoticeBanner(text = notice, onDismiss = onDismissNotice, tag = "course-notice-banner")
            } else if (isExam && detail.spoilerSanitizedCount > 0) {
                CourseNoticeBanner(
                    text = "已自动过滤 ${detail.spoilerSanitizedCount} 处个人笔记中的真题回忆，仅保留知识点线索，不进入练习题干。",
                    onDismiss = null,
                    tag = "course-spoiler-notice",
                )
            }

            // Strictly <= 1 PrimaryButton on the entire screen (UX-4)
            PrimaryButton(
                onClick = {
                    if (resumeTarget != null) onOpenTopic(resumeTarget) else onAddMaterials()
                },
                modifier = Modifier.testTag("course-primary-cta"),
            ) {
                Text(
                    if (!isExam) {
                        when {
                            resumeTarget == null -> "导入课本或材料开始学习"
                            detail.activeAnchor?.topicId == resumeTarget.topicId ->
                                "继续学习 · ${resumeTarget.title} · 约 ${estimateTopicMinutes(resumeTarget)} 分钟"
                            resumeTarget == orderedExaminableTopics.firstOrNull() ->
                                "从第一章开始 · 约 ${estimateTopicMinutes(resumeTarget)} 分钟"
                            else ->
                                "开始学习 · ${resumeTarget.title} · 约 ${estimateTopicMinutes(resumeTarget)} 分钟"
                        }
                    } else {
                        when {
                            resumeTarget == null -> "导入课本、讲义或考纲开始学习"
                            plan?.todayFocusTopicId == resumeTarget.topicId -> "继续今日冲刺 · ${resumeTarget.weekLabel}"
                            detail.activeAnchor?.topicId == resumeTarget.topicId -> "继续上次专题 · ${resumeTarget.weekLabel}"
                            !detail.syllabusNarrowsScope && resumeTarget == orderedExaminableTopics.firstOrNull() ->
                                "从第一章开始 · 约 ${estimateTopicMinutes(resumeTarget)} 分钟"
                            else -> "开始本周学习 · ${resumeTarget.weekLabel}"
                        }
                    },
                )
            }

            if (orderedExaminableTopics.isEmpty() && detail.pendingTopics.isEmpty()) {
                CoachEmptyState(
                    title = if (isExam) "还没有放入课程材料" else "还没有放入学习材料",
                    hint = if (isExam) {
                        "可从右上角 ⋯ 导入课本、讲义或考纲，也可以拖入多个文件。"
                    } else {
                        "可从右上角 ⋯ 添加课本、讲义或笔记，也可以直接把文件拖进窗口。"
                    },
                )
            } else {
                orderedExaminableTopics.forEach { topic ->
                    val cardMeta = if (isExam) {
                        topicGapById[topic.topicId]?.cardMetaLine.orEmpty()
                    } else {
                        val rangeText = topic.sources.firstOrNull()?.let { "第 ${it.startPage}–${it.endPage} 页 · " }.orEmpty()
                        "${rangeText}约 ${estimateTopicMinutes(topic)} 分钟"
                    }
                    CourseTopicCard(
                        topic = topic,
                        isExam = isExam,
                        planMetaLine = cardMeta,
                        onOpen = { onOpenTopic(topic) },
                        onRenameClick = { renamingTopic = topic },
                        onUnlinkClick = { unlinkingTopic = topic },
                        onExcludeClick = { onSetTopicStatus(topic.topicId, CourseTopicStatus.EXCLUDED) },
                    )
                }
            }

            if (detail.pendingTopics.isNotEmpty()) {
                Text(
                    "缺本周材料（${detail.pendingTopics.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.fgPrimary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                detail.pendingTopics.forEach { pending ->
                    PaperCard(Modifier.fillMaxWidth().testTag("course-pending-${pending.topicId}")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${pending.weekLabel} · ${pending.title}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.fgPrimary,
                                )
                                CourseTagChip(text = "缺本周材料", warm = true)
                            }
                            Text(
                                "大纲已列出本周专题，但尚未导入讲义、转写或阅读材料。把文件拖入窗口或点击补充。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.fgSecondary,
                            )
                            PaperButton(onClick = onAddMaterials) {
                                Text("补充本周材料")
                            }
                        }
                    }
                }
            }

            if (isExam && detail.excludedTopics.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("course-excluded-section"),
                    shape = RoundedCornerShape(10.dp),
                    color = colors.surface1,
                    border = BorderStroke(1.dp, colors.borderSubtle),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { excludedExpanded = !excludedExpanded }
                                .testTag("course-excluded-toggle"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "本课不考内容（${detail.excludedTopics.size}）",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.fgPrimary,
                            )
                            Text(
                                if (excludedExpanded) "收起" else "展开",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.fgSecondary,
                            )
                        }
                        if (excludedExpanded) {
                            HorizontalDivider(color = colors.borderSubtle)
                            detail.excludedTopics.forEach { excluded ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text(
                                            excluded.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.fgPrimary,
                                        )
                                        val srcDesc = excluded.sources.firstOrNull()?.let {
                                            "《${it.bookTitle}》第 ${it.startPage}–${it.endPage} 页"
                                        } ?: "未在当前大纲中出现"
                                        Text(
                                            srcDesc,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.fgSecondary,
                                        )
                                    }
                                    V7TextButton(
                                        onClick = { onSetTopicStatus(excluded.topicId, CourseTopicStatus.EXAMINABLE) },
                                        modifier = Modifier.testTag("restore-topic-${excluded.topicId}"),
                                    ) {
                                        Text("恢复为必考")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (materialsDialogOpen) {
        CourseMaterialsDialog(
            detail = detail,
            onDismiss = { materialsDialogOpen = false },
            onUpdateMaterialAssignment = onUpdateMaterialAssignment,
            onRemoveMaterial = onRemoveMaterial,
            onRunVision = onRunHandwrittenVision,
        )
    }

    if (examDialogOpen) {
        CourseExamProfileDialog(
            profile = detail.examProfile,
            onDismiss = { examDialogOpen = false },
            onSave = { updated ->
                examDialogOpen = false
                onUpdateExamProfile(updated)
            },
        )
    }

    renamingTopic?.let { target ->
        var draftTitle by remember(target.topicId) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renamingTopic = null },
            title = { Text("重命名专题") },
            text = {
                OutlinedTextField(
                    value = draftTitle,
                    onValueChange = { draftTitle = it },
                    singleLine = true,
                    label = { Text("专题名称") },
                    modifier = Modifier.fillMaxWidth().testTag("topic-rename-input"),
                )
            },
            confirmButton = {
                PaperButton(onClick = {
                    onRenameTopic(target.topicId, draftTitle)
                    renamingTopic = null
                }) { Text("保存") }
            },
            dismissButton = {
                V7TextButton({ renamingTopic = null }) { Text("取消") }
            },
        )
    }

    unlinkingTopic?.let { target ->
        AlertDialog(
            onDismissRequest = { unlinkingTopic = null },
            title = { Text("移除误挂材料") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "移除后该材料不会在重新对齐时自动挂回「${target.title}」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.fgSecondary,
                    )
                    target.sources.forEach { src ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "L${src.authorityRank} ${src.bookTitle}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                            )
                            V7TextButton(
                                onClick = {
                                    onUnlinkTopicSource(target.topicId, src.bookId)
                                    unlinkingTopic = null
                                },
                                modifier = Modifier.testTag("topic-unlink-src-${target.topicId}-${src.bookId}"),
                            ) {
                                Text("移除关联")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                V7TextButton({ unlinkingTopic = null }) { Text("完成") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseTopicCard(
    topic: CourseTopicEntry,
    isExam: Boolean = true,
    planMetaLine: String = "",
    onOpen: () -> Unit,
    onRenameClick: () -> Unit,
    onUnlinkClick: () -> Unit,
    onExcludeClick: () -> Unit,
) {
    val colors = CoachTheme.colors
    var menuOpen by remember(topic.topicId) { mutableStateOf(false) }

    PaperCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag("course-topic-${topic.topicId}"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CourseTagChip(text = topic.weekLabel, warm = false)
                    if (topic.userLocked) {
                        CourseTagChip(text = "已锁定", warm = true)
                    }
                    if (!topic.practiceDraft?.text.isNullOrBlank()) {
                        CourseTagChip(text = "有作答草稿", warm = false)
                    }
                }
                Box {
                    V7TextButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag("topic-menu-${topic.topicId}"),
                    ) {
                        Text("⋯")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("重命名专题") },
                            modifier = Modifier.testTag("topic-rename-action-${topic.topicId}"),
                            onClick = {
                                menuOpen = false
                                onRenameClick()
                            },
                        )
                        if (topic.sources.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("移除误挂材料") },
                                modifier = Modifier.testTag("topic-unlink-action-${topic.topicId}"),
                                onClick = {
                                    menuOpen = false
                                    onUnlinkClick()
                                },
                            )
                        }
                        if (isExam) {
                            DropdownMenuItem(
                                text = { Text("移入本课不考") },
                                modifier = Modifier.testTag("topic-exclude-action-${topic.topicId}"),
                                onClick = {
                                    menuOpen = false
                                    onExcludeClick()
                                },
                            )
                        }
                    }
                }
            }

            Text(
                topic.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.fgPrimary,
            )

            if (planMetaLine.isNotBlank()) {
                CoachCardMeta(
                    text = planMetaLine,
                    modifier = Modifier.testTag("topic-plan-meta-${topic.topicId}"),
                )
            }

            if (topic.sources.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    topic.sources.forEach { src ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = colors.surface1,
                            border = BorderStroke(1.dp, colors.borderSubtle),
                        ) {
                            Text(
                                if (isExam) "L${src.authorityRank} ${src.role.labelZh} · ${src.bookTitle}" else "${src.role.labelZh} · ${src.bookTitle}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.fgSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseMaterialsDialog(
    detail: CourseDetailSnapshot,
    onDismiss: () -> Unit,
    onUpdateMaterialAssignment: (bookId: String, weekNum: Int?, role: CourseMaterialRole) -> Unit,
    onRemoveMaterial: (bookId: String) -> Unit,
    onRunVision: (bookId: String) -> Unit,
) {
    val colors = CoachTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 640.dp).testTag("course-materials-dialog"),
        title = { Text("本课材料清单（${detail.materials.size} 份）") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (detail.materials.isEmpty()) {
                    Text("当前还没有材料，拖入或添加文件后会出现在这里。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    detail.materials.forEach { mat ->
                        var weekExpanded by remember(mat.bookId) { mutableStateOf(false) }
                        var roleExpanded by remember(mat.bookId) { mutableStateOf(false) }
                        var pendingWeek by remember(mat.bookId, mat.weekNum) { mutableStateOf(mat.weekNum) }
                        var pendingRole by remember(mat.bookId, mat.role) { mutableStateOf(mat.role) }

                        Surface(
                            modifier = Modifier.fillMaxWidth().testTag("course-material-row-${mat.bookId}"),
                            shape = RoundedCornerShape(8.dp),
                            color = colors.surface1,
                            border = BorderStroke(1.dp, colors.borderSubtle),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        mat.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = colors.fgPrimary,
                                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                                    )
                                    if (mat.userLocked) {
                                        CourseTagChip(text = "已锁定", warm = true)
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box {
                                        PaperButton(
                                            onClick = { weekExpanded = true },
                                            modifier = Modifier.testTag("material-week-picker-${mat.bookId}"),
                                        ) {
                                            Text(pendingWeek?.let { "第 $it 周" } ?: "未分周")
                                        }
                                        DropdownMenu(expanded = weekExpanded, onDismissRequest = { weekExpanded = false }) {
                                            DropdownMenuItem(
                                                text = { Text("未分周") },
                                                modifier = Modifier.testTag("material-week-opt-${mat.bookId}-none"),
                                                onClick = {
                                                    pendingWeek = null
                                                    weekExpanded = false
                                                },
                                            )
                                            for (wk in 1..12) {
                                                DropdownMenuItem(
                                                    text = { Text("第 $wk 周") },
                                                    modifier = Modifier.testTag("material-week-opt-${mat.bookId}-$wk"),
                                                    onClick = {
                                                        pendingWeek = wk
                                                        weekExpanded = false
                                                    },
                                                )
                                            }
                                        }
                                    }

                                    Box {
                                        PaperButton(
                                            onClick = { roleExpanded = true },
                                            modifier = Modifier.testTag("material-role-picker-${mat.bookId}"),
                                        ) {
                                            Text("L${pendingRole.authorityRank} ${pendingRole.labelZh}")
                                        }
                                        DropdownMenu(expanded = roleExpanded, onDismissRequest = { roleExpanded = false }) {
                                            CourseMaterialRole.entries.forEach { role ->
                                                DropdownMenuItem(
                                                    text = { Text("L${role.authorityRank} ${role.labelZh}") },
                                                    onClick = {
                                                        pendingRole = role
                                                        roleExpanded = false
                                                    },
                                                )
                                            }
                                        }
                                    }

                                    if (pendingWeek != mat.weekNum || pendingRole != mat.role) {
                                        PaperButton(
                                            onClick = {
                                                onUpdateMaterialAssignment(mat.bookId, pendingWeek, pendingRole)
                                            },
                                            modifier = Modifier.testTag("material-week-save-${mat.bookId}"),
                                        ) {
                                            Text("确认归属")
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${mat.format.uppercase()} · ${mat.pageCount} 页",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.fgSecondary,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (mat.needsVision || (mat.format == "pdf" && mat.pageCount <= MAX_HANDWRITTEN_VISION_PAGES)) {
                                            V7TextButton(
                                                onClick = { onRunVision(mat.bookId) },
                                                modifier = Modifier.testTag("material-vision-${mat.bookId}"),
                                            ) {
                                                Text("识别手写页")
                                            }
                                        }
                                        V7TextButton(
                                            onClick = { onRemoveMaterial(mat.bookId) },
                                            modifier = Modifier.testTag("material-remove-${mat.bookId}"),
                                        ) {
                                            Text("移出本课")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            PaperButton(onClick = onDismiss, modifier = Modifier.testTag("course-materials-close")) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun CourseExamProfileDialog(
    profile: CourseExamProfile,
    onDismiss: () -> Unit,
    onSave: (CourseExamProfile) -> Unit,
) {
    var examEnabled by remember(profile) { mutableStateOf(profile.isExam) }
    var examDate by remember(profile) { mutableStateOf(profile.examDate) }
    var dateTentative by remember(profile) { mutableStateOf(profile.examDateTentative) }
    var examFormat by remember(profile) { mutableStateOf(profile.examFormat) }
    var formatTentative by remember(profile) { mutableStateOf(profile.examFormatTentative) }
    var weightNote by remember(profile) { mutableStateOf(profile.weightingNote) }

    AlertDialog(
        modifier = Modifier.testTag("course-exam-profile-dialog"),
        onDismissRequest = onDismiss,
        title = { Text("考试日与安排") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = examEnabled || examDate.isNotBlank(),
                        onCheckedChange = { checked ->
                            examEnabled = checked
                            if (!checked) examDate = ""
                        },
                        modifier = Modifier.testTag("exam-profile-enabled-checkbox"),
                    )
                    Text("这门课要考")
                }
                OutlinedTextField(
                    value = examDate,
                    onValueChange = {
                        examDate = it
                        if (it.isNotBlank()) examEnabled = true
                    },
                    label = { Text("考试日期（如 2026-12-12）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("exam-profile-date-input"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dateTentative, onCheckedChange = { dateTentative = it })
                    Text("日期暂定（未正式官宣）")
                }
                OutlinedTextField(
                    value = examFormat,
                    onValueChange = { examFormat = it },
                    label = { Text("考核形式（如 选择题 + 300 词短论述）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = formatTentative, onCheckedChange = { formatTentative = it })
                    Text("题型暂定")
                }
                OutlinedTextField(
                    value = weightNote,
                    onValueChange = { weightNote = it },
                    label = { Text("权重或备考备注") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            PaperButton(
                onClick = {
                    val trimmedDate = examDate.trim()
                    onSave(
                        profile.copy(
                            examDate = trimmedDate,
                            examDateTentative = dateTentative,
                            examFormat = examFormat.trim(),
                            examFormatTentative = formatTentative,
                            weightingNote = weightNote.trim(),
                            examEnabled = examEnabled || trimmedDate.isNotBlank(),
                        ),
                    )
                },
                modifier = Modifier.testTag("exam-profile-save"),
            ) { Text("保存") }
        },
        dismissButton = {
            V7TextButton(onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun CourseScheduleSubScreen(
    detail: CourseDetailSnapshot,
    subView: CourseSubView,
    orderedTopics: List<CourseTopicEntry>,
    topicGapById: Map<String, TopicCountdownGap>,
    onBackToCourse: () -> Unit,
    onOpenTopic: (CourseTopicEntry) -> Unit,
    onAddSyllabus: () -> Unit,
    onSkipSyllabusPrompt: () -> Unit,
    onOpenMockExam: () -> Unit,
) {
    val colors = CoachTheme.colors
    val plan = detail.countdownPlan
    val isWeekly = subView == CourseSubView.WEEKLY_PLAN
    val topicById = remember(orderedTopics) { orderedTopics.associateBy { it.topicId } }
    val slots = remember(plan, isWeekly) {
        val all = plan?.dailySlots.orEmpty()
        if (isWeekly) all.take(7) else all
    }

    Box(
        Modifier
            .fillMaxSize()
            .testTag(if (isWeekly) "course-weekly-plan-view" else "course-countdown-plan-view"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            V7TextButton(onBackToCourse, modifier = Modifier.testTag("course-schedule-back")) {
                Text("← 返回课程")
            }

            if (detail.shouldShowSyllabusPrompt) {
                CourseSyllabusPromptBar(
                    onAddSyllabus = onAddSyllabus,
                    onSkip = onSkipSyllabusPrompt,
                )
            }

            PageHeader(
                kicker = if (isWeekly) "本周学习计划" else "考前排程",
                title = detail.title,
                deck = when {
                    detail.syllabusNarrowsScope -> plan?.headerSummaryLine ?: "按考纲与讲义排定学习顺序。"
                    detail.hasSyllabusOrScope -> "已收到考纲，暂按整本书安排。"
                    else -> "按整本书安排，放进考纲后可以缩小范围。"
                },
                meta = buildString {
                    if (plan?.daysRemaining != null) append("距考试 ${plan.daysRemaining} 天 · ")
                    append("每日预算 ${plan?.dailyBudgetMinutes ?: ExamCountdownPlanner.DEFAULT_DAILY_BUDGET_MINUTES} 分钟")
                    if (plan != null) append(" · 每日复习上限 ${plan.fsrsDailyCap} 题")
                },
            )

            val firstTopic = orderedTopics.firstOrNull()
            if (firstTopic != null) {
                PrimaryButton(
                    onClick = { onOpenTopic(firstTopic) },
                    modifier = Modifier.testTag("course-schedule-primary-cta"),
                ) {
                    Text("开始今天这一步 · ${firstTopic.title}")
                }
            }

            if (slots.isEmpty() && orderedTopics.isEmpty()) {
                CoachEmptyState(
                    title = "还没有可安排的章节",
                    hint = "先导入课本或讲义，系统会自动排好每天的阅读与复习。",
                    actionLabel = "添加材料",
                    onAction = onAddSyllabus,
                )
            } else {
                val activeSlots = slots.filter { it.topicIds.isNotEmpty() || it.includeTimedMock }
                if (activeSlots.isNotEmpty()) {
                    activeSlots.forEach { slot ->
                        PaperCard(Modifier.fillMaxWidth().testTag("course-plan-slot-${slot.dayOffset}")) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        if (slot.dayOffset == 0) "今天 · ${slot.dateLabel}" else "第 ${slot.dayOffset + 1} 天 · ${slot.dateLabel}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = colors.fgPrimary,
                                    )
                                    CoachCardMeta("约 ${slot.totalMinutes.coerceAtLeast(20)} 分钟")
                                }
                                if (slot.includeTimedMock) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("建议完成一套限时模考", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                                        V7TextButton(onClick = onOpenMockExam) { Text("去模考") }
                                    }
                                }
                                slot.topicIds.forEach { tid ->
                                    val t = topicById[tid] ?: return@forEach
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onOpenTopic(t) }
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text("${t.weekLabel} · ${t.title}", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
                                            topicGapById[tid]?.cardMetaLine?.takeIf(String::isNotBlank)?.let { CoachCardMeta(it) }
                                        }
                                        Text("去学 →", style = MaterialTheme.typography.labelSmall, color = colors.actionPrimary)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    orderedTopics.take(7).forEach { t ->
                        PaperCard(
                            Modifier.fillMaxWidth().clickable { onOpenTopic(t) },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("${t.weekLabel} · ${t.title}", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
                                    CoachCardMeta("约 ${estimateTopicMinutes(t)} 分钟")
                                }
                                Text("开始 →", style = MaterialTheme.typography.labelSmall, color = colors.actionPrimary)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CourseNoticeBanner(text: String, onDismiss: (() -> Unit)?, tag: String) {
    val colors = CoachTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(8.dp),
        color = colors.warningBg,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = colors.warningFg, modifier = Modifier.weight(1f))
            if (onDismiss != null) {
                V7TextButton(onDismiss) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun CourseTagChip(text: String, warm: Boolean) {
    val colors = CoachTheme.colors
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (warm) colors.warningBg else colors.surface1,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (warm) colors.warningFg else colors.fgSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun buildRegularSummaryLine(detail: CourseDetailSnapshot, topics: List<CourseTopicEntry>): String {
    if (topics.isEmpty()) return "放入课本或材料后，自动排好阅读顺序。"
    val onlyTextbooks = detail.materials.isNotEmpty() && detail.materials.all { it.role == CourseMaterialRole.CORE_TEXTBOOK }
    return if (onlyTextbooks) {
        "按整本书顺序学习，共 ${topics.size} 章"
    } else {
        "按材料顺序学习，共 ${topics.size} 节"
    }
}

private fun buildCourseMetaLine(detail: CourseDetailSnapshot, topics: List<CourseTopicEntry>): String {
    val allChapterTopics = topics.isNotEmpty() &&
        topics.all { it.weekNum == null && it.weekLabel.startsWith("第 ") && it.weekLabel.endsWith(" 章") }
    if (!detail.isExam) {
        return when {
            topics.isEmpty() -> "已纳入 ${detail.materials.size} 份材料"
            allChapterTopics -> "已纳入 ${detail.materials.size} 份材料 · 共 ${topics.size} 章"
            else -> "已纳入 ${detail.materials.size} 份材料 · 共 ${topics.size} 项"
        }
    }
    return when {
        topics.isEmpty() -> "已纳入 ${detail.materials.size} 份材料"
        detail.syllabusNarrowsScope -> "已纳入 ${detail.materials.size} 份材料 · 专题 ${topics.size} 项"
        allChapterTopics -> "已纳入 ${detail.materials.size} 份材料 · 全书共 ${topics.size} 章"
        else -> "已纳入 ${detail.materials.size} 份材料 · 全书共 ${topics.size} 项"
    }
}

private fun buildExamSummaryLine(detail: CourseDetailSnapshot): String {
    val p = detail.examProfile
    val planLine = detail.countdownPlan?.takeIf { detail.examinableTopics.isNotEmpty() }?.headerSummaryLine
    val datePart = if (p.examDate.isNotBlank()) {
        "考试日期：${p.examDate}${if (p.examDateTentative) "（暂定）" else ""}"
    } else {
        "未设考试日"
    }
    val fmtPart = p.examFormat.takeIf { it.isNotBlank() }?.let {
        "$it${if (p.examFormatTentative) "（暂定）" else ""}"
    }
    return listOfNotNull(planLine?.takeIf(String::isNotBlank), datePart, fmtPart).joinToString(" · ")
}
