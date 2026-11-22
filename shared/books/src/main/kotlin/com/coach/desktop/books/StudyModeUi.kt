package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.V7TextButton

/**
 * Non-blocking, zero-permanent-footprint Study Mode trigger + anchored popover (`TON-1805`):
 * - Uses 1dp subtle border `PaperButton` / text chip (never `PrimaryButton`, preserving UX-4 `PrimaryButton <= 1`).
 * - Click 1 opens popover; Click 2 selects mode (total <= 2 clicks from Home or Reader).
 * - Preserves 100% of reading page, UTF-16 anchor, highlights, notes, and drafts.
 */
@Composable
internal fun StudyModeSelectorButton(
    preference: BookStudyPreference,
    onUpdatePreference: (BookStudyPreference, Boolean) -> Unit,
    compact: Boolean = false,
    tag: String = "study-mode-trigger",
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(CoachV7.Radius.s)

    Box(modifier) {
        Row(
            Modifier
                .clip(shape)
                .background(colors.surface2)
                .border(1.dp, colors.actionPrimary.copy(alpha = 0.35f), shape)
                .clickable(role = Role.Button) { expanded = !expanded }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 4.dp else 6.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (compact) "学法：${preference.mode.titleZh} ▾" else "${preference.compactBadgeText()} ▾",
                style = MaterialTheme.typography.labelMedium,
                color = colors.fgPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surface1,
            modifier = Modifier.widthIn(min = 310.dp, max = 360.dp).testTag("study-mode-popover"),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "这本书怎么学",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.fgPrimary,
                )
                if (preference.defaultReason.isNotBlank()) {
                    CoachCardMeta(
                        preference.defaultReason,
                        Modifier.testTag("study-mode-default-reason"),
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle)

                for (mode in BookStudyMode.entries) {
                    val selected = preference.mode == mode
                    val rowBg = if (selected) colors.actionPrimary.copy(alpha = 0.12f) else Color.Transparent
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(rowBg)
                            .border(
                                1.dp,
                                if (selected) colors.actionPrimary.copy(alpha = 0.38f) else Color.Transparent,
                                shape,
                            )
                            .clickable(role = Role.RadioButton) {
                                val nextDetail = if (mode != BookStudyMode.READ_THROUGH &&
                                    preference.detailLevel == ExplainDetailLevel.ORIGINAL_ONLY
                                ) {
                                    ExplainDetailLevel.DETAILED
                                } else {
                                    preference.detailLevel
                                }
                                expanded = false
                                onUpdatePreference(
                                    preference.copy(
                                        mode = mode,
                                        userLocked = true,
                                        detailLevel = nextDetail,
                                    ),
                                    true,
                                )
                            }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                            .testTag(mode.tag),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                mode.titleZh + if (selected) " · 当前" else "",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) colors.actionPrimary else colors.fgPrimary,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                            )
                            Text(
                                mode.shortSummaryZh,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.fgSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle)

                // Adjustment 1: 讲解详略
                AdjustmentPillRow(
                    label = "详略",
                    options = ExplainDetailLevel.entries,
                    selected = preference.detailLevel,
                    optionLabel = { it.labelZh },
                    optionTag = { it.tag },
                    onSelect = { next ->
                        onUpdatePreference(preference.copy(detailLevel = next, userLocked = true), false)
                    },
                )
                // Adjustment 2: 讲解语言
                AdjustmentPillRow(
                    label = "语言",
                    options = ExplainLanguage.entries,
                    selected = preference.explainLang,
                    optionLabel = { it.shortZh },
                    optionTag = { it.tag },
                    onSelect = { next ->
                        onUpdatePreference(preference.copy(explainLang = next, userLocked = true), false)
                    },
                )
                // Adjustment 3: 投入节奏
                AdjustmentPillRow(
                    label = "节奏",
                    options = StudyPacePreset.entries,
                    selected = preference.pacePreset,
                    optionLabel = {
                        when (it) {
                            StudyPacePreset.UNPACED -> "不限"
                            StudyPacePreset.MIN_30 -> "30分/天"
                            StudyPacePreset.MIN_60 -> "60分/天"
                            StudyPacePreset.TARGET_DATE -> "按目标日"
                        }
                    },
                    optionTag = { it.tag },
                    onSelect = { preset ->
                        val nextPref = when (preset) {
                            StudyPacePreset.UNPACED -> preference.copy(dailyMinutes = null, targetDate = null, userLocked = true)
                            StudyPacePreset.MIN_30 -> preference.copy(dailyMinutes = 30, targetDate = null, userLocked = true)
                            StudyPacePreset.MIN_60 -> preference.copy(dailyMinutes = 60, targetDate = null, userLocked = true)
                            StudyPacePreset.TARGET_DATE -> preference.copy(
                                dailyMinutes = null,
                                targetDate = preference.targetDate ?: "2026-12-15",
                                userLocked = true,
                            )
                        }
                        onUpdatePreference(nextPref, false)
                    },
                )
                CoachCardMeta("切换学法时，当前页码、高亮与批注全部保留。")
            }
        }
    }
}

@Composable
private fun <T> AdjustmentPillRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    optionTag: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val colors = CoachTheme.colors
    val pillShape = RoundedCornerShape(CoachV7.Radius.s)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.fgSecondary,
            modifier = Modifier.width(34.dp),
            maxLines = 1,
        )
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (opt in options) {
                val active = opt == selected
                Text(
                    optionLabel(opt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) colors.actionPrimary else colors.fgSecondary,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(pillShape)
                        .background(if (active) colors.actionPrimary.copy(alpha = 0.14f) else colors.surface2)
                        .border(1.dp, if (active) colors.actionPrimary.copy(alpha = 0.4f) else colors.borderSubtle, pillShape)
                        .clickable(role = Role.Button) { onSelect(opt) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag(optionTag(opt)),
                )
            }
        }
    }
}

/**
 * Renders the genuinely distinct companion surface for non-`READ_THROUGH` modes inside `SingleBookCompanionPanel`,
 * using real book TOC, chapter sections, exercises, and cached companion data.
 */
@Composable
internal fun ModeDistinctCompanionSurface(
    book: Textbook,
    library: TextbookLibrary,
    currentPage: Int,
    preference: BookStudyPreference,
    chapterGroup: ChapterSectionGroup,
    currentSectionIndex: Int,
    pack: BookCompanionPack?,
    draft: ChapterPracticeDraft,
    onUpdateDraft: ((ChapterPracticeDraft) -> ChapterPracticeDraft) -> Unit,
    onUpdateInquiry: (String) -> Unit,
    onJumpPage: (Int) -> Unit,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
) {
    val colors = CoachTheme.colors
    val chapterPos = remember(book.id, book.lessons, book.pages, currentPage, chapterGroup.firstPage) {
        StudyModeChapterNumbering.resolve(book, currentPage, chapterGroup.firstPage)
    }
    val bookMapPack = remember(book.id, chapterPos.chapterNumber, preference.mode) {
        if (preference.mode == BookStudyMode.BOOK_MAP) {
            StudyModeStore.loadBookMapPack(library, book.id, chapterPos.chapterNumber)
        } else null
    }
    val examFocusPack = remember(book.id, chapterPos.chapterNumber, preference.mode) {
        if (preference.mode == BookStudyMode.EXAM_FOCUS) {
            StudyModeStore.loadExamFocusPack(library, book.id, chapterPos.chapterNumber)
        } else null
    }

    when (preference.mode) {
        BookStudyMode.BOOK_MAP -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("mode-surface-BOOK_MAP")) {
                PaperCard(
                    Modifier.fillMaxWidth(),
                    color = colors.surface2,
                    contentPadding = PaddingValues(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "先摸全貌 · 本章一页纸（第 ${chapterPos.chapterNumber} / ${chapterPos.totalChapters} 章）",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.fgPrimary,
                            modifier = Modifier.testTag("book-map-chapter-badge"),
                        )
                        Text(
                            bookMapPack?.bigPictureThesisZh?.ifBlank { null }
                                ?: pack?.coreQuestion?.ifBlank { null }
                                ?: "请先生成本章一页纸摘要以查看全章核心论断与跨章关系。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.fgPrimary,
                            modifier = Modifier.testTag("book-map-thesis"),
                        )
                        CoachCardMeta("不设小测打断 · 约 3 分钟摸清全章骨架。")
                    }
                }
                if (bookMapPack != null && bookMapPack.sectionArchitecture.isNotEmpty()) {
                    Text("各节角色分工", style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
                    bookMapPack.sectionArchitecture.forEach { role ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(CoachV7.Radius.s))
                                .background(colors.surface2)
                                .border(1.dp, colors.borderSubtle, RoundedCornerShape(CoachV7.Radius.s))
                                .clickable { onJumpPage(role.pdfPage) }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    "${role.sectionId} · ${role.roleLabelZh}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.actionPrimary,
                                )
                                Text(
                                    role.oneLineZh,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.fgPrimary,
                                    maxLines = if (preference.detailLevel == ExplainDetailLevel.BRIEF) 1 else 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            CoachCardMeta("p.${role.pdfPage}")
                        }
                    }
                    if (bookMapPack.prerequisiteChapter.isNotBlank() || bookMapPack.downstreamChapter.isNotBlank()) {
                        PaperCard(Modifier.fillMaxWidth(), color = colors.surface2, contentPadding = PaddingValues(10.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("前后章脉络关系", style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
                                if (bookMapPack.prerequisiteChapter.isNotBlank()) {
                                    CoachCardMeta("承上：${bookMapPack.prerequisiteChapter}")
                                }
                                if (bookMapPack.downstreamChapter.isNotBlank()) {
                                    CoachCardMeta("启下：${bookMapPack.downstreamChapter}")
                                }
                            }
                        }
                    }
                } else {
                    Text("本章小节脉络", style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
                    chapterGroup.sections.filterNot { ChapterCompanionGrouping.isExerciseSection(it.title) }
                        .forEachIndexed { idx, sec ->
                            val kp = pack?.keyPoints?.firstOrNull { it.sectionId == sec.id }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(CoachV7.Radius.s))
                                    .background(colors.surface2)
                                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(CoachV7.Radius.s))
                                    .clickable { onJumpPage(sec.firstPage) }
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("${idx + 1}. ${sec.title}", style = MaterialTheme.typography.labelMedium, color = colors.fgPrimary)
                                    if (kp != null) {
                                        Text(kp.summary, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary, maxLines = 2)
                                    }
                                }
                                CoachCardMeta("p.${sec.firstPage}")
                            }
                        }
                }
                val nextChapter = chapterPos.next
                if (nextChapter != null) {
                    PaperButton(
                        onClick = { onJumpPage(nextChapter.firstPage) },
                        modifier = Modifier.fillMaxWidth().testTag("book-map-next-chapter"),
                    ) {
                        Text("下一章一页纸：${nextChapter.title}（第 ${nextChapter.firstPage} 页）", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        BookStudyMode.EXAM_FOCUS -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("mode-surface-EXAM_FOCUS")) {
                if (examFocusPack != null) {
                    PaperCard(Modifier.fillMaxWidth(), color = colors.surface2, contentPadding = PaddingValues(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                examFocusPack.headlineZh.ifBlank { "第 ${chapterPos.chapterNumber} 章考纲权重分流" },
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.fgPrimary,
                            )
                            if (examFocusPack.courseWeekBadgeZh.isNotBlank()) {
                                CoachCardMeta(examFocusPack.courseWeekBadgeZh)
                            }
                        }
                    }
                    examFocusPack.mustStudySections.forEach { sec ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(CoachV7.Radius.s))
                                .background(colors.surface2)
                                .border(1.dp, colors.actionPrimary.copy(alpha = 0.35f), RoundedCornerShape(CoachV7.Radius.s))
                                .clickable { onJumpPage(sec.pdfPage) }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    "${sec.weightTag} · 第 ${sec.sectionId} 节",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.actionPrimary,
                                )
                                Text(sec.examAngleZh, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            CoachCardMeta("p.${sec.pdfPage}")
                        }
                    }
                    examFocusPack.skimOrSkipSections.forEach { skim ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(CoachV7.Radius.s))
                                .background(colors.surface2)
                                .border(1.dp, colors.borderSubtle, RoundedCornerShape(CoachV7.Radius.s))
                                .padding(10.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${skim.weightTag} · 第 ${skim.sectionId} 节", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                                Text(skim.whySkimZh, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                            }
                        }
                    }
                    if (examFocusPack.examPitfallAlertZh.isNotBlank()) {
                        CoachCardMeta("易错警示：${examFocusPack.examPitfallAlertZh}")
                    }
                } else {
                    PaperCard(Modifier.fillMaxWidth(), color = colors.surface2, contentPadding = PaddingValues(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("考点优先 · 待关联考纲或生成权重分流", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                            CoachCardMeta("根据课程大纲与章节内容划分必考节与速览节（不按小节序号硬猜权重）。")
                        }
                    }
                }
            }
        }

        BookStudyMode.DIAGNOSTIC_SKIP -> {
            val quizzes = pack?.pretest.orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("mode-surface-DIAGNOSTIC_SKIP")) {
                PaperCard(Modifier.fillMaxWidth(), color = colors.surface2, contentPadding = PaddingValues(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("先测后读 · 分节摸底跳读门", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                        CoachCardMeta("先不剧透摘要；独立答对的小节自动折起跳过，只读薄弱节。")
                    }
                }
                if (quizzes.isEmpty()) {
                    chapterGroup.sections.filterNot { ChapterCompanionGrouping.isExerciseSection(it.title) }
                        .take(4).forEachIndexed { idx, sec ->
                            val skipped = draft.pretestSubmitted.contains("skip-${sec.id}")
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(CoachV7.Radius.s))
                                    .background(colors.surface2)
                                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(CoachV7.Radius.s))
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        if (skipped) "✓ 已掌握跳过 · ${sec.title}" else "摸底节 ${idx + 1}：${sec.title}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (skipped) colors.fgTertiary else colors.fgPrimary,
                                    )
                                    CoachCardMeta(if (skipped) "已折叠跳过，随时可点开重读。" else "不确定可直接定位第 ${sec.firstPage} 页补读。")
                                }
                                V7TextButton(onClick = {
                                    if (skipped) onJumpPage(sec.firstPage)
                                    else onUpdateDraft { it.copy(pretestSubmitted = it.pretestSubmitted + "skip-${sec.id}") }
                                }) {
                                    Text(if (skipped) "展开" else "已会跳过")
                                }
                            }
                        }
                } else {
                    quizzes.forEachIndexed { idx, q ->
                        val chosen = draft.pretestSelections[q.id]
                        val submitted = q.id in draft.pretestSubmitted
                        val passed = submitted && chosen == q.correctOption && draft.pretestGuessed[q.id] != true
                        PaperCard(Modifier.fillMaxWidth(), color = colors.surface2, contentPadding = PaddingValues(10.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    if (passed) "✓ 本节已掌握可跳过" else "摸底第 ${idx + 1} 题",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (passed) colors.actionPrimary else colors.fgPrimary,
                                )
                                Text(q.stem, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
                                q.options.forEach { opt ->
                                    val isSel = chosen == opt
                                    Text(
                                        opt,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSel) colors.actionPrimary else colors.fgSecondary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(CoachV7.Radius.s))
                                            .background(if (isSel) colors.actionPrimary.copy(alpha = 0.12f) else Color.Transparent)
                                            .clickable {
                                                onUpdateDraft {
                                                    it.copy(
                                                        pretestSelections = it.pretestSelections + (q.id to opt),
                                                        pretestSubmitted = it.pretestSubmitted + q.id,
                                                    )
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                                if (submitted && !passed && q.anchor != null) {
                                    V7TextButton(onClick = { onOpenAnchor(q.anchor) }) {
                                        Text("答错/待补：直达第 ${q.anchor.pdfPage} 页只读这一段")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        BookStudyMode.PROBLEM_DRIVEN -> {
            val exSec = chapterGroup.sections.firstOrNull { ChapterCompanionGrouping.isExerciseSection(it.title) }
            val hints = pack?.exerciseHints.orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("mode-surface-PROBLEM_DRIVEN")) {
                PaperCard(Modifier.fillMaxWidth(), color = colors.surface2, contentPadding = PaddingValues(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("做题带读 · 章末习题实战台", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                        CoachCardMeta("直接从原书章末习题动手，卡住哪步再按提示跳回正文公式。")
                    }
                }
                if (exSec != null) {
                    PaperButton(
                        onClick = { onJumpPage(exSec.firstPage) },
                        modifier = Modifier.fillMaxWidth().testTag("problem-jump-exercises"),
                    ) {
                        Text("翻到本章原书章末习题页（第 ${exSec.firstPage} 页）")
                    }
                }
                if (hints.isNotEmpty()) {
                    hints.take(6).forEach { h ->
                        val lvl = draft.exerciseHintLevels[h.id] ?: 0
                        PaperCard(Modifier.fillMaxWidth(), color = colors.surface2, contentPadding = PaddingValues(10.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(h.problemLabel, style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
                                Text(h.questionSummary, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                                if (lvl >= 1) CoachCardMeta("提示 1（破题方向）：${h.hint1Direction}")
                                if (lvl >= 2) CoachCardMeta("提示 2（列式方法）：${h.hint2Method}")
                                if (lvl >= 3) CoachCardMeta("提示 3（结果自检）：${h.hint3Check}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (lvl < 3) {
                                        V7TextButton(onClick = {
                                            onUpdateDraft { it.copy(exerciseHintLevels = it.exerciseHintLevels + (h.id to (lvl + 1))) }
                                        }) { Text("看第 ${lvl + 1} 级提示") }
                                    }
                                    if (h.anchor != null) {
                                        V7TextButton(onClick = { onOpenAnchor(h.anchor) }) {
                                            Text("回正文 p.${h.anchor.pdfPage} 查公式")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    CoachCardMeta("打开服务连接后自动按题生成三级渐进提示；现在可先跳至习题页边做边查。")
                }
            }
        }

        BookStudyMode.QUESTION_DRIVEN -> {
            var localQuery by remember(preference.bookId, preference.inquiryQuestion) {
                mutableStateOf(preference.inquiryQuestion)
            }
            val matchedLessons = remember(book.id, localQuery) {
                val q = localQuery.trim()
                if (q.length < 2) emptyList()
                else {
                    val tokens = q.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
                    book.lessons.mapNotNull { sec ->
                        val hay = (sec.title + "\n" + sec.text).lowercase()
                        val score = if (hay.contains(q.lowercase())) 10 else tokens.count { hay.contains(it) }
                        if (score > 0) sec to score else null
                    }.sortedByDescending { it.second }.take(5).map { it.first }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("mode-surface-QUESTION_DRIVEN")) {
                PaperCard(Modifier.fillMaxWidth(), color = colors.surface2, contentPadding = PaddingValues(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("带着问题读 · 跨章靶向阅读单", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                        CoachCardMeta("输入具体问题或论文题目，只挑出全书最相关的几节与页码，不替写论文。")
                        val fieldShape = RoundedCornerShape(CoachV7.Radius.s)
                        BasicTextField(
                            value = localQuery,
                            onValueChange = {
                                localQuery = it
                                onUpdateInquiry(it)
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.fgPrimary),
                            cursorBrush = SolidColor(colors.actionPrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(fieldShape)
                                .background(colors.surface1)
                                .border(1.dp, colors.borderSubtle, fieldShape)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("question-driven-input"),
                            decorationBox = { inner ->
                                if (localQuery.isEmpty()) {
                                    Text("输入问题，例如：adverse selection 什么时候不会崩溃…", style = MaterialTheme.typography.bodySmall, color = colors.fgTertiary)
                                }
                                inner()
                            },
                        )
                    }
                }
                if (matchedLessons.isNotEmpty()) {
                    Text("全书最相关的 ${matchedLessons.size} 个章节片段", style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
                    matchedLessons.forEachIndexed { idx, sec ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(CoachV7.Radius.s))
                                .background(colors.surface2)
                                .border(1.dp, colors.borderSubtle, RoundedCornerShape(CoachV7.Radius.s))
                                .clickable { onJumpPage(sec.firstPage) }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${idx + 1}. ${sec.title}", style = MaterialTheme.typography.labelMedium, color = colors.fgPrimary)
                                CoachCardMeta("第 ${sec.firstPage}–${sec.lastPage} 页 · 点击直达原文核对论据")
                            }
                            CoachCardMeta("p.${sec.firstPage}")
                        }
                    }
                } else {
                    CoachCardMeta("在上方输入你的问题或论文关键词，即刻从全书筛出对应章节与页码。")
                }
            }
        }

        BookStudyMode.READ_THROUGH -> Unit
    }
}
