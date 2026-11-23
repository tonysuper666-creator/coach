package com.coach.desktop.books

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.V7TextButton

/**
 * TON-1820: one line on how an exam course is arranged. Without a syllabus it is clickable and offers to narrow
 * the scope; the answer to 「有考纲或往年题吗」 stays visible and editable right below it.
 */
@Composable
internal fun CourseScopeLine(
    detail: CourseDetailSnapshot,
    onAddMaterials: () -> Unit,
    onUpdateExamProfile: (CourseExamProfile) -> Unit,
) {
    val colors = CoachTheme.colors
    val profile = detail.examProfile
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (CourseExamMode.hasSyllabus(detail.materials)) {
            Text(
                CourseExamMode.arrangementLine(detail),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgSecondary,
                modifier = Modifier.testTag("course-scope-line"),
            )
        } else {
            Text(
                CourseExamMode.SCOPE_HINT,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgPrimary,
                modifier = Modifier
                    .clickable { onAddMaterials() }
                    .padding(vertical = 4.dp)
                    .testTag("course-scope-hint"),
            )
        }
        CourseExamMode.scopeAnswerLine(profile, detail.materials)?.let { answer ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("course-scope-answer"),
            ) {
                CoachCardMeta(answer)
                if (profile.scopeAsk == CourseExamProfile.SCOPE_ASK_SKIPPED) {
                    V7TextButton(
                        onClick = {
                            onUpdateExamProfile(profile.copy(scopeAsk = CourseExamProfile.SCOPE_ASK_PROVIDED))
                            onAddMaterials()
                        },
                        modifier = Modifier.testTag("course-scope-answer-provide"),
                    ) { Text("放进来") }
                } else {
                    V7TextButton(
                        onClick = onAddMaterials,
                        modifier = Modifier.testTag("course-scope-answer-provide"),
                    ) { Text("现在导入") }
                    V7TextButton(
                        onClick = { onUpdateExamProfile(profile.copy(scopeAsk = CourseExamProfile.SCOPE_ASK_SKIPPED)) },
                        modifier = Modifier.testTag("course-scope-answer-skip"),
                    ) { Text("先不放") }
                }
            }
        }
    }
}

/**
 * The ask at the top of the plan and mock screens the first time an exam course opens them.
 * It never blocks what is below it; 跳过 is remembered per course.
 */
@Composable
internal fun CourseScopeAskBar(
    profile: CourseExamProfile,
    materials: List<CourseMaterialEntry>,
    onAddMaterials: () -> Unit,
    onUpdateExamProfile: (CourseExamProfile) -> Unit,
    tagPrefix: String = "course-scope-ask",
) {
    if (!CourseExamMode.shouldAskScope(profile, materials)) return
    val colors = CoachTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(tagPrefix),
        shape = RoundedCornerShape(8.dp),
        color = colors.surface1,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                CourseExamMode.SCOPE_ASK,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgPrimary,
                modifier = Modifier.weight(1f),
            )
            V7TextButton(
                onClick = {
                    onUpdateExamProfile(profile.copy(scopeAsk = CourseExamProfile.SCOPE_ASK_PROVIDED))
                    onAddMaterials()
                },
                modifier = Modifier.testTag("$tagPrefix-provide"),
            ) { Text("放进来") }
            V7TextButton(
                onClick = { onUpdateExamProfile(profile.copy(scopeAsk = CourseExamProfile.SCOPE_ASK_SKIPPED)) },
                modifier = Modifier.testTag("$tagPrefix-skip"),
            ) { Text("跳过") }
        }
    }
}

/** 「排本周计划」/「考前排程」: the next seven days from the countdown plan, with the scope ask on top. */
@Composable
internal fun CourseWeekPlanCard(
    detail: CourseDetailSnapshot,
    onAddMaterials: () -> Unit,
    onUpdateExamProfile: (CourseExamProfile) -> Unit,
    onClose: () -> Unit,
) {
    val colors = CoachTheme.colors
    val plan = detail.countdownPlan
    val titleById = remember(detail) { detail.examinableTopics.associate { it.topicId to it.title } }
    PaperCard(Modifier.fillMaxWidth().testTag("course-week-plan")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CourseScopeAskBar(
                profile = detail.examProfile,
                materials = detail.materials,
                onAddMaterials = onAddMaterials,
                onUpdateExamProfile = onUpdateExamProfile,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    CourseExamMode.planEntryLabel(plan),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.fgPrimary,
                )
                V7TextButton(onClose, modifier = Modifier.testTag("course-week-plan-close")) { Text("收起") }
            }
            val days = plan?.dailySlots.orEmpty().take(7).filter { it.topicIds.isNotEmpty() || it.includeTimedMock }
            if (days.isEmpty()) {
                CoachCardMeta("这几天还排不出内容，先从上面的按钮开始读。")
            } else {
                days.forEach { slot ->
                    val what = buildList {
                        if (slot.includeTimedMock) add("限时模考")
                        addAll(slot.topicIds.mapNotNull { titleById[it] })
                    }.joinToString("、")
                    Column(
                        modifier = Modifier.fillMaxWidth().testTag("course-week-plan-day-${slot.dayOffset}"),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            if (slot.dayOffset == 0) "今天 · ${slot.dateLabel}" else slot.dateLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.fgSecondary,
                        )
                        Text(
                            if (slot.totalMinutes > 0) "$what · 约 ${slot.totalMinutes} 分钟" else what,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.fgPrimary,
                        )
                    }
                }
            }
        }
    }
}

internal fun buildExamMeta(detail: CourseDetailSnapshot): String = buildList {
    if (detail.materials.isNotEmpty()) add("已纳入 ${detail.materials.size} 份材料")
    if (detail.examinableTopics.isNotEmpty()) add("必考专题 ${detail.examinableTopics.size} 项")
}.joinToString(" · ")
