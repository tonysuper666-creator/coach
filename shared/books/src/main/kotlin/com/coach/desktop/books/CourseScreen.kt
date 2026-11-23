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
) {
    val colors = CoachTheme.colors
    var courseMenuOpen by remember { mutableStateOf(false) }
    var materialsDialogOpen by remember(detail.courseId) { mutableStateOf(false) }
    var examDialogOpen by remember(detail.courseId) { mutableStateOf(false) }
    var renamingTopic by remember { mutableStateOf<CourseTopicEntry?>(null) }
    var unlinkingTopic by remember { mutableStateOf<CourseTopicEntry?>(null) }
    var excludedExpanded by remember(detail.courseId, detail.excludedTopics.size) {
        mutableStateOf(detail.excludedTopics.size in 1..6)
    }

    val profile = detail.examProfile
    val isExam = profile.isExam
    var weekPlanOpen by remember(detail.courseId) { mutableStateOf(false) }
    val plan = detail.countdownPlan
    val topicGapById = remember(plan) {
        plan?.rankedTopics?.associateBy { it.topicId }.orEmpty()
    }
    val orderedExaminableTopics = remember(detail.examinableTopics, plan) {
        val rankedIds = plan?.rankedTopics?.map { it.topicId }.orEmpty()
        if (rankedIds.isEmpty()) {
            detail.examinableTopics
        } else {
            val rankIndex = rankedIds.withIndex().associate { (idx, tid) -> tid to idx }
            detail.examinableTopics.sortedBy { rankIndex[it.topicId] ?: Int.MAX_VALUE }
        }
    }
    val resumeTarget = remember(detail, plan, orderedExaminableTopics) {
        val focusId = plan?.todayFocusTopicId
        focusId?.let { fid -> orderedExaminableTopics.firstOrNull { it.topicId == fid && it.sources.isNotEmpty() } }
            ?: detail.activeAnchor?.topicId?.let { tid -> orderedExaminableTopics.firstOrNull { it.topicId == tid } }
            ?: orderedExaminableTopics.firstOrNull()
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
                                text = { Text("进入考前限时综合练习") },
                                modifier = Modifier.testTag("course-mock-exam-action"),
                                onClick = {
                                    courseMenuOpen = false
                                    onOpenMockExam()
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
                            text = { Text(if (isExam) "编辑考试时间与题型" else "设置考试日") },
                            modifier = Modifier.testTag("course-exam-profile-action"),
                            onClick = {
                                courseMenuOpen = false
                                examDialogOpen = true
                            },
                        )
                        if (isExam) {
                            DropdownMenuItem(
                                text = { Text("改为不考") },
                                modifier = Modifier.testTag("course-exam-off-action"),
                                onClick = {
                                    courseMenuOpen = false
                                    onUpdateExamProfile(profile.copy(examIntent = false, examDate = ""))
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("重新对齐课程") },
                            modifier = Modifier.testTag("course-realign-action"),
                            onClick = {
                                courseMenuOpen = false
                                onRealign()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("添加课程材料") },
                            modifier = Modifier.testTag("course-add-materials-action"),
                            onClick = {
                                courseMenuOpen = false
                                onAddMaterials()
                            },
                        )
                    }
                }
            }

            if (isExam) {
                PageHeader(
                    kicker = "课程备考 · 要考",
                    title = detail.title,
                    deck = buildExamSummaryLine(detail),
                    meta = buildExamMeta(detail),
                )
                CourseScopeLine(
                    detail = detail,
                    onAddMaterials = onAddMaterials,
                    onUpdateExamProfile = onUpdateExamProfile,
                )
            } else {
                PageHeader(
                    kicker = "自学",
                    title = detail.title,
                    deck = CourseExamMode.arrangementLine(detail),
                    meta = CourseExamMode.selfStudyMeta(detail),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoachCardMeta("这门课有考试？")
                    V7TextButton(
                        onClick = { onUpdateExamProfile(profile.copy(examIntent = true)) },
                        modifier = Modifier.testTag("course-exam-intent-on"),
                    ) {
                        Text("要考")
                    }
                }
            }

            if (hasActiveMockExam) {
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
                CoachLoadingDots(label = "正在对齐或整理课程材料…")
            }

            if (!notice.isNullOrBlank()) {
                CourseNoticeBanner(text = notice, onDismiss = onDismissNotice, tag = "course-notice-banner")
            } else if (detail.spoilerSanitizedCount > 0) {
                CourseNoticeBanner(
                    text = "已自动过滤 ${detail.spoilerSanitizedCount} 处个人笔记中的真题回忆，仅保留知识点线索，不进入练习题干。",
                    onDismiss = null,
                    tag = "course-spoiler-notice",
                )
            }

            // Strictly <= 1 PrimaryButton on the entire screen (UX-4)
            if (isExam) {
                PrimaryButton(
                    onClick = {
                        if (resumeTarget != null) onOpenTopic(resumeTarget) else onAddMaterials()
                    },
                    modifier = Modifier.testTag("course-primary-cta"),
                ) {
                    Text(
                        when {
                            resumeTarget == null -> "导入大纲、讲义或转写开始备考"
                            plan?.todayFocusTopicId == resumeTarget.topicId -> "继续今日冲刺 · ${resumeTarget.weekLabel}"
                            detail.activeAnchor?.topicId == resumeTarget.topicId -> "继续上次专题 · ${resumeTarget.weekLabel}"
                            else -> "开始本周备考 · ${resumeTarget.weekLabel}"
                        },
                    )
                }
                if (orderedExaminableTopics.isNotEmpty()) {
                    PaperButton(
                        onClick = { weekPlanOpen = !weekPlanOpen },
                        modifier = Modifier.testTag("course-plan-entry"),
                    ) {
                        Text(CourseExamMode.planEntryLabel(plan))
                    }
                }
                if (weekPlanOpen) {
                    CourseWeekPlanCard(
                        detail = detail,
                        onAddMaterials = onAddMaterials,
                        onUpdateExamProfile = onUpdateExamProfile,
                        onClose = { weekPlanOpen = false },
                    )
                }
            } else {
                val firstStep = remember(detail) { CourseExamMode.firstStepTopic(detail) }
                PrimaryButton(
                    onClick = {
                        if (firstStep != null) onOpenTopic(firstStep) else onAddMaterials()
                    },
                    modifier = Modifier.testTag("course-primary-cta"),
                ) {
                    Text(CourseExamMode.firstStepLabel(detail) ?: "添加课本开始读")
                }
            }

            val listedTopics = if (isExam) orderedExaminableTopics else CourseExamMode.selfStudyTopics(detail)
            if (listedTopics.isEmpty() && detail.pendingTopics.isEmpty()) {
                if (isExam) {
                    CoachEmptyState(
                        title = "还没有整理出必考专题",
                        hint = "可从右上角 ⋯ 导入大纲、讲义、习题答案或课堂录音转写，也可以拖入多个课程文件。",
                    )
                } else {
                    CoachEmptyState(
                        title = "还没有可读的章节",
                        hint = "从右上角 ⋯ 添加课本或讲义，也可以直接把文件拖进窗口。",
                    )
                }
            } else {
                listedTopics.forEach { topic ->
                    CourseTopicCard(
                        topic = topic,
                        examMode = isExam,
                        planMetaLine = if (isExam) topicGapById[topic.topicId]?.cardMetaLine.orEmpty() else "",
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
    examMode: Boolean = true,
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
                    if (examMode && topic.userLocked) {
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
                        if (examMode) {
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
                                "L${src.authorityRank} ${src.role.labelZh} · ${src.bookTitle}",
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
    var examDate by remember(profile) { mutableStateOf(profile.examDate) }
    var dateTentative by remember(profile) { mutableStateOf(profile.examDateTentative) }
    var examFormat by remember(profile) { mutableStateOf(profile.examFormat) }
    var formatTentative by remember(profile) { mutableStateOf(profile.examFormatTentative) }
    var weightNote by remember(profile) { mutableStateOf(profile.weightingNote) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("考试时间与题型标注") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = examDate,
                    onValueChange = { examDate = it },
                    label = { Text("考试日期（如 2026-12-12）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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
            PaperButton(onClick = {
                onSave(
                    profile.copy(
                        examDate = examDate.trim(),
                        examDateTentative = dateTentative,
                        examFormat = examFormat.trim(),
                        examFormatTentative = formatTentative,
                        weightingNote = weightNote.trim(),
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = {
            V7TextButton(onDismiss) { Text("取消") }
        },
    )
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

private fun buildExamSummaryLine(detail: CourseDetailSnapshot): String {
    val p = detail.examProfile
    val planLine = detail.countdownPlan?.takeIf { detail.examinableTopics.isNotEmpty() }?.headerSummaryLine
    val datePart = if (p.examDate.isNotBlank()) {
        "考试日期：${p.examDate}${if (p.examDateTentative) "（暂定）" else ""}"
    } else {
        "考试日期待确认（暂定）"
    }
    val fmtPart = if (p.examFormat.isNotBlank()) {
        "${p.examFormat}${if (p.examFormatTentative) "（暂定）" else ""}"
    } else {
        "题型暂定"
    }
    return if (!planLine.isNullOrBlank()) "$planLine · $datePart · $fmtPart" else "$datePart · $fmtPart"
}
