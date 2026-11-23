package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.CoachLoadingDots
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

                for (mode in preference.visibleModes()) {
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

private val uiSectionNumRegex = Regex("""\b(\d+\.\d+(?:\.\d+)?)\b""")

internal fun resolveBookMapSectionPage(
    role: BookMapSectionRole,
    roleIndex: Int,
    chapterGroup: ChapterSectionGroup,
    bookMapPack: BookMapOnePagerPack? = null,
): Int {
    if (role.pdfPage in chapterGroup.firstPage..chapterGroup.lastPage) return role.pdfPage
    val nonExSections = chapterGroup.sections
        .filterNot { ChapterCompanionGrouping.isExerciseSection(it.title) }
        .ifEmpty { chapterGroup.sections }
    val targetNum = uiSectionNumRegex.find(role.sectionId)?.groupValues?.get(1)
        ?: uiSectionNumRegex.find(role.roleLabelZh)?.groupValues?.get(1)
    val labelHint = role.sectionId.trim()
    val matchedSec = nonExSections.firstOrNull { sec ->
        val secNum = uiSectionNumRegex.find(sec.title)?.groupValues?.get(1)
            ?: uiSectionNumRegex.find(sec.id)?.groupValues?.get(1)
        (targetNum != null && secNum == targetNum) ||
            (labelHint.length >= 2 && (sec.title.contains(labelHint, ignoreCase = true) || labelHint.contains(sec.title, ignoreCase = true)))
    } ?: nonExSections.getOrNull(roleIndex)
    if (matchedSec != null && matchedSec.firstPage > 0) return matchedSec.firstPage
    return bookMapPack?.coreClaims
        ?.firstOrNull { it.pdfPage in chapterGroup.firstPage..chapterGroup.lastPage }
        ?.pdfPage
        ?: chapterGroup.firstPage.coerceAtLeast(1)
}

internal fun resolveMatchingSectionForGate(
    gate: DiagnosticSkipGateItem,
    chapterGroup: ChapterSectionGroup,
): BookLesson? {
    val nonEx = chapterGroup.sections
        .filterNot { ChapterCompanionGrouping.isExerciseSection(it.title) }
        .ifEmpty { chapterGroup.sections }
    val gateNum = uiSectionNumRegex.find(gate.sectionTitle)?.groupValues?.get(1)
    val gateTitle = gate.sectionTitle.trim()
    return nonEx.firstOrNull { sec ->
        val secNum = uiSectionNumRegex.find(sec.title)?.groupValues?.get(1)
            ?: uiSectionNumRegex.find(sec.id)?.groupValues?.get(1)
        gateNum != null && secNum == gateNum
    } ?: nonEx.firstOrNull { sec ->
        gateTitle.length >= 2 && (sec.title.contains(gateTitle, ignoreCase = true) || gateTitle.contains(sec.title, ignoreCase = true))
    } ?: nonEx.firstOrNull { sec ->
        gate.missJumpPdfPage > 0 && gate.missJumpPdfPage in sec.firstPage..sec.lastPage
    }
}

private val streamPreviewSkipKeys = setOf(
    "studyMode", "chapterRole", "importance", "readingDepth", "sectionId",
    "gateId", "problemId", "hitId", "clusterId", "bookId", "chapterId",
    "detailLevel", "explainLang", "questionType", "examWeight", "weightTag",
    "quoteAnchor", "exerciseQuoteAnchor", "relation", "prerequisites", "unlocks",
)

internal fun extractReadableStreamPreview(raw: String): String {
    if (raw.isBlank()) return ""
    val trimmed = raw.trimStart()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
        return trimmed.takeLast(600)
    }
    data class Container(val kind: Char, val ownerKey: String)
    val stack = mutableListOf<Container>()
    var pendingKey: String? = null
    var sawColon = false
    val segments = mutableListOf<String>()

    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        when {
            c.isWhitespace() -> i++
            c == '{' || c == '[' -> {
                val owner = if (sawColon) pendingKey.orEmpty() else stack.lastOrNull()?.ownerKey.orEmpty()
                stack += Container(c, owner)
                pendingKey = null
                sawColon = false
                i++
            }
            c == '}' || c == ']' -> {
                if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                pendingKey = null
                sawColon = false
                i++
            }
            c == ':' -> {
                sawColon = true
                i++
            }
            c == ',' -> {
                pendingKey = null
                sawColon = false
                i++
            }
            c == '"' -> {
                i++
                val sb = StringBuilder()
                var closed = false
                while (i < raw.length) {
                    val ch = raw[i]
                    if (ch == '\\') {
                        if (i + 1 < raw.length) {
                            val next = raw[i + 1]
                            when (next) {
                                'n' -> sb.append('\n')
                                't' -> sb.append(' ')
                                '\\', '"', '/' -> sb.append(next)
                                else -> sb.append(next)
                            }
                            i += 2
                        } else {
                            i++
                        }
                    } else if (ch == '"') {
                        closed = true
                        i++
                        break
                    } else {
                        sb.append(ch)
                        i++
                    }
                }
                val text = sb.toString().trim()
                val top = stack.lastOrNull()
                val isObjectKey = top?.kind == '{' && !sawColon
                if (isObjectKey) {
                    pendingKey = text
                } else if (text.isNotEmpty() && (closed || text.length >= 2)) {
                    val activeKey = if (sawColon) pendingKey.orEmpty() else top?.ownerKey.orEmpty()
                    if (activeKey !in streamPreviewSkipKeys) {
                        if (segments.lastOrNull() != text) segments += text
                    }
                }
            }
            else -> i++
        }
    }
    if (segments.isEmpty()) return ""
    val joined = segments.takeLast(8).joinToString("\n").trim()
    return if (joined.length <= 600) joined else {
        val tail = joined.takeLast(600)
        val nl = tail.indexOf('\n')
        if (nl in 1 until 120) tail.substring(nl + 1).trimStart() else tail
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
    modePackJson: String? = null,
    modeStreamingChars: Int = 0,
    modeStreamingText: String = "",
    modeLoading: Boolean = false,
    modeError: String? = null,
    bookMapMeta: WholeBookMapMeta? = null,
    bookMapBatchLoading: Boolean = false,
    onRequestBookMapBatch: (() -> Unit)? = null,
    onRetryModePack: (() -> Unit)? = null,
) {
    val colors = CoachTheme.colors
    val chapterPos = remember(book.id, book.lessons, book.pages, currentPage, chapterGroup.firstPage) {
        StudyModeChapterNumbering.resolve(book, currentPage, chapterGroup.firstPage)
    }
    val bookMapPack = remember(book.id, chapterPos.chapterNumber, preference.mode, preference.detailLevel, preference.explainLang, modePackJson) {
        if (preference.mode == BookStudyMode.BOOK_MAP) {
            modePackJson?.let { StudyModeStore.parseBookMapPack(it) }
                ?: StudyModeStore.loadBookMapPack(library, book.id, chapterPos.chapterNumber, preference)
        } else null
    }
    val examFocusPack = remember(book.id, chapterPos.chapterNumber, preference.mode, preference.detailLevel, preference.explainLang, modePackJson) {
        if (preference.mode == BookStudyMode.EXAM_FOCUS) {
            modePackJson?.let { StudyModeStore.parseExamFocusPack(it) }
                ?: StudyModeStore.loadExamFocusPack(library, book.id, chapterPos.chapterNumber, preference)
        } else null
    }

    LiveModeStatusAndExtraDeck(
        book = book,
        library = library,
        chapterNumber = chapterPos.chapterNumber,
        chapterGroup = chapterGroup,
        preference = preference,
        modePackJson = modePackJson,
        modeStreamingChars = modeStreamingChars,
        modeStreamingText = modeStreamingText,
        modeLoading = modeLoading,
        modeError = modeError,
        bookMapPack = bookMapPack,
        bookMapMeta = bookMapMeta,
        bookMapBatchLoading = bookMapBatchLoading,
        draft = draft,
        onUpdateDraft = onUpdateDraft,
        onOpenAnchor = onOpenAnchor,
        onJumpPage = onJumpPage,
        onRequestBookMapBatch = onRequestBookMapBatch,
        onRetryModePack = onRetryModePack,
    )

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
                    bookMapPack.sectionArchitecture.forEachIndexed { roleIdx, role ->
                        val targetPage = resolveBookMapSectionPage(role, roleIdx, chapterGroup, bookMapPack)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(CoachV7.Radius.s))
                                .background(colors.surface2)
                                .border(1.dp, colors.borderSubtle, RoundedCornerShape(CoachV7.Radius.s))
                                .clickable { onJumpPage(targetPage) }
                                .padding(10.dp)
                                .testTag("book-map-section-$roleIdx"),
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
                            CoachCardMeta("p.$targetPage")
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
                                    val isSel = chosen == opt && draft.pretestGuessed[q.id] != true
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
                                                        pretestGuessed = it.pretestGuessed - q.id,
                                                        pretestSubmitted = it.pretestSubmitted + q.id,
                                                    )
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                                val isUnsure = draft.pretestGuessed[q.id] == true && submitted
                                Text(
                                    "不确定，直接标为待读",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isUnsure) colors.actionPrimary else colors.fgTertiary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(CoachV7.Radius.s))
                                        .background(if (isUnsure) colors.actionPrimary.copy(alpha = 0.10f) else Color.Transparent)
                                        .clickable {
                                            onUpdateDraft {
                                                it.copy(
                                                    pretestSelections = it.pretestSelections - q.id,
                                                    pretestGuessed = it.pretestGuessed + (q.id to true),
                                                    pretestSubmitted = it.pretestSubmitted + q.id,
                                                )
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .testTag("diagnostic-unsure-${q.id}"),
                                )
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
                        Text("自测带读 · 章末习题", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
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
            val trimmedLocal = localQuery.trim()
            val submitInquiry = {
                if (trimmedLocal.isEmpty() || trimmedLocal.length >= 3) {
                    onUpdateInquiry(trimmedLocal)
                }
            }
            val matchedLessons = remember(book.id, localQuery) {
                val q = trimmedLocal
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
                        CoachCardMeta("输入具体问题或论文题目后按回车或点击「定位证据」，只挑全书最相关的节与页码，不替写论文。")
                        val fieldShape = RoundedCornerShape(CoachV7.Radius.s)
                        BasicTextField(
                            value = localQuery,
                            onValueChange = { localQuery = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { submitInquiry() },
                                onDone = { submitInquiry() },
                            ),
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.fgPrimary),
                            cursorBrush = SolidColor(colors.actionPrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(fieldShape)
                                .background(colors.surface1)
                                .border(1.dp, colors.borderSubtle, fieldShape)
                                .onPreviewKeyEvent { ev ->
                                    if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) {
                                        submitInquiry()
                                        true
                                    } else {
                                        false
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("question-driven-input"),
                            decorationBox = { inner ->
                                if (localQuery.isEmpty()) {
                                    Text("输入问题，例如：adverse selection 什么时候不会崩溃…", style = MaterialTheme.typography.bodySmall, color = colors.fgTertiary)
                                }
                                inner()
                            },
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PaperButton(
                                onClick = submitInquiry,
                                enabled = trimmedLocal.length >= 3 && !modeLoading,
                                modifier = Modifier.testTag("question-driven-submit"),
                            ) {
                                Text("定位证据")
                            }
                            if (localQuery.isNotEmpty()) {
                                V7TextButton(
                                    onClick = {
                                        localQuery = ""
                                        if (preference.inquiryQuestion.isNotBlank()) onUpdateInquiry("")
                                    },
                                    modifier = Modifier.testTag("question-driven-clear"),
                                ) {
                                    Text("清空")
                                }
                            }
                        }
                        if (trimmedLocal.length >= 3 && trimmedLocal != preference.inquiryQuestion.trim()) {
                            CoachCardMeta(
                                "已输入，按回车或点「定位证据」发起本章原句检索。",
                                Modifier.testTag("question-driven-pending-hint"),
                            )
                        }
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

@Composable
internal fun LiveReadThroughOverviewCard(
    pack: ReadThroughModePack,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
) {
    val colors = CoachTheme.colors
    PaperCard(Modifier.fillMaxWidth().testTag("live-read-through-card"), color = colors.surface2, contentPadding = PaddingValues(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pack.learningArc.isNotBlank()) {
                Text("本章精读主线", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                Text(pack.learningArc, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
            }
            for (sec in pack.sections.take(4)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(sec.title, style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
                    if (sec.keyConcept.isNotBlank()) CoachCardMeta("核心概念：${sec.keyConcept}")
                    Text(sec.plainExplanation, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                    if (sec.formulaNote.isNotBlank()) CoachCardMeta("公式核对：${sec.formulaNote}")
                    if (sec.quoteAnchor.isNotBlank()) {
                        V7TextButton(onClick = { onOpenAnchor(BookCompanionAnchor(sec.pdfPage, sec.quoteAnchor, 0, 0, sec.quoteAnchor)) }) {
                            Text("回原文 p.${sec.pdfPage} 核对原句")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveModeStatusAndExtraDeck(
    book: Textbook,
    library: TextbookLibrary,
    chapterNumber: Int,
    chapterGroup: ChapterSectionGroup,
    preference: BookStudyPreference,
    modePackJson: String?,
    modeStreamingChars: Int,
    modeStreamingText: String,
    modeLoading: Boolean,
    modeError: String?,
    bookMapPack: BookMapOnePagerPack?,
    bookMapMeta: WholeBookMapMeta?,
    bookMapBatchLoading: Boolean,
    draft: ChapterPracticeDraft,
    onUpdateDraft: ((ChapterPracticeDraft) -> ChapterPracticeDraft) -> Unit,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
    onJumpPage: (Int) -> Unit,
    onRequestBookMapBatch: (() -> Unit)?,
    onRetryModePack: (() -> Unit)?,
) {
    val colors = CoachTheme.colors
    if (preference.detailLevel == ExplainDetailLevel.ORIGINAL_ONLY) {
        CoachCardMeta("原书原貌视图 · 仅呈现章节目录与页码定位（零额外生成开销）")
        return
    }
    if (modeLoading) {
        val statusLabel = if (modeStreamingChars > 0) "正在流式整理首屏内容（已收到 $modeStreamingChars 字）…" else "正在准备本模式首屏…"
        val streamPreview = remember(modeStreamingText) { extractReadableStreamPreview(modeStreamingText) }
        PaperCard(Modifier.fillMaxWidth().testTag("mode-streaming-status"), color = colors.surface2, contentPadding = PaddingValues(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CoachLoadingDots(label = statusLabel)
                if (streamPreview.isNotBlank()) {
                    Text(
                        streamPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.fgSecondary,
                        modifier = Modifier.testTag("mode-streaming-preview"),
                    )
                }
            }
        }
    } else if (modeError != null) {
        PaperCard(Modifier.fillMaxWidth().testTag("mode-offline-banner"), color = colors.surface2, contentPadding = PaddingValues(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(modeError, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary, modifier = Modifier.weight(1f))
                if (onRetryModePack != null) {
                    V7TextButton(onClick = onRetryModePack) { Text("重试") }
                }
            }
        }
    }

    when (preference.mode) {
        BookStudyMode.BOOK_MAP -> {
            val claims = bookMapPack?.coreClaims.orEmpty()
            if (claims.isNotEmpty() || bookMapMeta != null || onRequestBookMapBatch != null) {
                PaperCard(Modifier.fillMaxWidth().testTag("book-map-live-extras"), color = colors.surface2, contentPadding = PaddingValues(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (claims.isNotEmpty()) {
                            Text("核心论断与原文锚点（${bookMapPack?.estimatedMinutes ?: 20} 分钟建议）", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                            for (c in claims.take(4)) {
                                val claimPage = c.pdfPage.takeIf { it in chapterGroup.firstPage..chapterGroup.lastPage }
                                    ?: chapterGroup.firstPage.coerceAtLeast(1)
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("• [${c.importance}] ${c.claim}", style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
                                    if (c.quoteAnchor.isNotBlank()) {
                                        V7TextButton(onClick = { onOpenAnchor(BookCompanionAnchor(claimPage, c.quoteAnchor, 0, 0, c.quoteAnchor)) }) {
                                            Text("定位第 $claimPage 页原句")
                                        }
                                    }
                                }
                            }
                        }
                        if (bookMapMeta != null && (bookMapMeta.fastSkimOrder40Min.isNotEmpty() || bookMapMeta.clusters.isNotEmpty())) {
                            Text("全书 ${bookMapMeta.chapterCount} 章航图与 40 分钟速览路线", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                            if (bookMapMeta.clusters.isNotEmpty()) {
                                for (cl in bookMapMeta.clusters.take(4)) {
                                    CoachCardMeta("${cl.title}（第 ${cl.chapterNums.joinToString("/")} 章）：${cl.themeSummary}")
                                }
                            }
                            if (bookMapMeta.fastSkimOrder40Min.isNotEmpty()) {
                                CoachCardMeta(
                                    "推荐速览顺序：" + bookMapMeta.fastSkimOrder40Min.take(5).joinToString(" → ") { "第${it.chapterNum}章(${it.minutes}m)" },
                                )
                            }
                            if (bookMapMeta.glossary.isNotEmpty()) {
                                CoachCardMeta(
                                    "核心双语术语：" + bookMapMeta.glossary.take(6).joinToString(" · ") { "${it.en}(${it.zh})" },
                                )
                            }
                        } else if (onRequestBookMapBatch != null) {
                            PaperButton(onClick = onRequestBookMapBatch, enabled = !bookMapBatchLoading) {
                                Text(if (bookMapBatchLoading) "正在整书批量整理（24 章一页纸）…" else "生成整本书全章一页纸与速览地图")
                            }
                        }
                    }
                }
            }
        }

        BookStudyMode.EXAM_FOCUS -> {
            val extras = remember(modePackJson) {
                modePackJson?.let { raw ->
                    runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }.parseToJsonElement(raw) }
                        .getOrNull()?.let { it as? kotlinx.serialization.json.JsonObject }
                        ?.let { StudyModeStore.parseExamFocusExtras(it) }
                }
            }
            if (extras != null && (extras.first.isNotEmpty() || extras.second.isNotEmpty() || extras.third.isNotEmpty())) {
                val (traps, formulas, checks) = extras
                PaperCard(Modifier.fillMaxWidth().testTag("exam-focus-live-extras"), color = colors.surface2, contentPadding = PaddingValues(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (formulas.isNotEmpty()) {
                            Text("考场精确公式与系数核对", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                            for (f in formulas.take(4)) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${f.name}: ${f.formulaLatex}", style = MaterialTheme.typography.labelMedium, color = colors.fgPrimary)
                                    if (f.coefficientNote.isNotBlank()) CoachCardMeta("系数防错：${f.coefficientNote}")
                                    if (f.quoteAnchor.isNotBlank()) {
                                        V7TextButton(onClick = { onOpenAnchor(BookCompanionAnchor(f.pdfPage, f.quoteAnchor, 0, 0, f.quoteAnchor)) }) {
                                            Text("核对原书 p.${f.pdfPage}")
                                        }
                                    }
                                }
                            }
                        }
                        if (traps.isNotEmpty()) {
                            Text("高频误区纠偏", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                            for (t in traps.take(3)) {
                                Text("• ${t.trap}：${t.correction}", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                            }
                        }
                        if (checks.isNotEmpty()) {
                            Text("评分要点快测（仅列采分点）", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                            for (mc in checks.take(2)) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(mc.prompt, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
                                    if (mc.rubricOnly.isNotBlank()) CoachCardMeta("采分点：${mc.rubricOnly}")
                                }
                            }
                        }
                    }
                }
            }
        }

        BookStudyMode.DIAGNOSTIC_SKIP -> {
            val diagPack = remember(book.id, chapterNumber, preference.detailLevel, preference.explainLang, modePackJson) {
                modePackJson?.let { StudyModeStore.parseDiagnosticSkipPack(it) }
                    ?: StudyModeStore.loadDiagnosticSkipPack(library, book.id, chapterNumber, preference)
            }
            if (diagPack != null && diagPack.gates.isNotEmpty()) {
                val visibleGates = diagPack.gates.take(4)
                val passedCount = visibleGates.count { g ->
                    val gateKey = "diag-live-$chapterNumber-${g.gateId}"
                    val sel = draft.pretestSelections[gateKey]?.toIntOrNull()
                    gateKey in draft.pretestSubmitted && draft.pretestGuessed[gateKey] != true && sel == g.correctIndex
                }
                PaperCard(Modifier.fillMaxWidth().testTag("diagnostic-live-gates"), color = colors.surface2, contentPadding = PaddingValues(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "先测过关直跳门控（已掌握可跳 $passedCount / ${visibleGates.size} 节）",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.fgPrimary,
                            modifier = Modifier.testTag("diagnostic-live-skip-progress"),
                        )
                        if (diagPack.skipPolicy.isNotBlank()) CoachCardMeta(diagPack.skipPolicy)
                        for (g in visibleGates) {
                            val gateKey = "diag-live-$chapterNumber-${g.gateId}"
                            val matchedSec = remember(g.gateId, g.sectionTitle, g.missJumpPdfPage, chapterGroup) {
                                resolveMatchingSectionForGate(g, chapterGroup)
                            }
                            val skipKey = if (matchedSec != null) "skip-${matchedSec.id}" else "skip-gate-$chapterNumber-${g.gateId}"
                            val selectedIdx = draft.pretestSelections[gateKey]?.toIntOrNull()
                            val isUnsure = draft.pretestGuessed[gateKey] == true && gateKey in draft.pretestSubmitted
                            val submitted = gateKey in draft.pretestSubmitted
                            val passed = submitted && !isUnsure && selectedIdx != null && selectedIdx == g.correctIndex
                            val missed = submitted && (isUnsure || (selectedIdx != null && selectedIdx != g.correctIndex))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${g.sectionTitle} · ${g.questionPrompt}", style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
                                g.options.forEachIndexed { oIdx, optText ->
                                    val isSel = !isUnsure && selectedIdx == oIdx
                                    Text(
                                        optText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSel) colors.actionPrimary else colors.fgSecondary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(CoachV7.Radius.s))
                                            .background(if (isSel) colors.actionPrimary.copy(alpha = 0.12f) else Color.Transparent)
                                            .clickable(role = Role.RadioButton) {
                                                onUpdateDraft { d ->
                                                    val nextSubmitted = if (oIdx == g.correctIndex) {
                                                        d.pretestSubmitted + gateKey + skipKey
                                                    } else {
                                                        (d.pretestSubmitted + gateKey) - skipKey
                                                    }
                                                    d.copy(
                                                        pretestSelections = d.pretestSelections + (gateKey to oIdx.toString()),
                                                        pretestGuessed = d.pretestGuessed - gateKey,
                                                        pretestSubmitted = nextSubmitted,
                                                    )
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                            .testTag("diagnostic-live-opt-${g.gateId}-$oIdx"),
                                    )
                                }
                                Text(
                                    "不确定，直接标为待读",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isUnsure) colors.actionPrimary else colors.fgTertiary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(CoachV7.Radius.s))
                                        .background(if (isUnsure) colors.actionPrimary.copy(alpha = 0.10f) else Color.Transparent)
                                        .clickable(role = Role.RadioButton) {
                                            onUpdateDraft { d ->
                                                d.copy(
                                                    pretestSelections = d.pretestSelections - gateKey,
                                                    pretestGuessed = d.pretestGuessed + (gateKey to true),
                                                    pretestSubmitted = (d.pretestSubmitted + gateKey) - skipKey,
                                                )
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                        .testTag("diagnostic-live-unsure-${g.gateId}"),
                                )
                                if (passed) {
                                    val jumpLabel = matchedSec?.title?.let { "已标为跳过「$it」" } ?: "已记入跳读进度"
                                    CoachCardMeta("✓ 已掌握（$jumpLabel）：${g.passSkipNote}")
                                } else if (missed) {
                                    CoachCardMeta("纠偏：${g.missRemediation}")
                                    val targetPage = g.missJumpPdfPage.takeIf { it in chapterGroup.firstPage..chapterGroup.lastPage }
                                        ?: matchedSec?.firstPage
                                        ?: chapterGroup.firstPage.coerceAtLeast(1)
                                    V7TextButton(onClick = { onOpenAnchor(BookCompanionAnchor(targetPage, g.quoteAnchor, 0, 0, g.quoteAnchor)) }) {
                                        Text("答错/待补直达 p.$targetPage 补读原段")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        BookStudyMode.PROBLEM_DRIVEN -> {
            val probPack = remember(book.id, chapterNumber, preference.detailLevel, preference.explainLang, modePackJson) {
                modePackJson?.let { StudyModeStore.parseProblemDrivenPack(it) }
                    ?: StudyModeStore.loadProblemDrivenPack(library, book.id, chapterNumber, preference)
            }
            if (probPack != null && probPack.problems.isNotEmpty()) {
                PaperCard(Modifier.fillMaxWidth().testTag("problem-driven-live-cards"), color = colors.surface2, contentPadding = PaddingValues(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("自测带读工作台", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                        if (probPack.strategyNote.isNotBlank()) CoachCardMeta(probPack.strategyNote)
                        for (p in probPack.problems.take(4)) {
                            val probKey = "prob-live-$chapterNumber-${p.problemId}"
                            val unlocked = draft.exerciseHintLevels[probKey] ?: 0
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(p.title, style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
                                Text(p.attemptPrompt, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                                for (h in p.scaffoldingHints.take(unlocked)) {
                                    CoachCardMeta("提示：$h")
                                }
                                if (unlocked < p.scaffoldingHints.size) {
                                    V7TextButton(
                                        onClick = {
                                            onUpdateDraft { d ->
                                                d.copy(exerciseHintLevels = d.exerciseHintLevels + (probKey to (unlocked + 1)))
                                            }
                                        },
                                        modifier = Modifier.testTag("problem-live-hint-${p.problemId}"),
                                    ) {
                                        Text("展开第 ${unlocked + 1} 步脚手架")
                                    }
                                }
                                if (unlocked > 0 && p.keyDerivationCheck.isNotBlank()) {
                                    CoachCardMeta("推导核对：${p.keyDerivationCheck}")
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (p.exerciseQuoteAnchor.isNotBlank()) {
                                        V7TextButton(onClick = { onOpenAnchor(BookCompanionAnchor(p.exercisePdfPage, p.exerciseQuoteAnchor, 0, 0, p.exerciseQuoteAnchor)) }) {
                                            Text("看原题 p.${p.exercisePdfPage}")
                                        }
                                    }
                                    if (p.quoteAnchor.isNotBlank()) {
                                        V7TextButton(onClick = { onOpenAnchor(BookCompanionAnchor(p.bodyPdfPage, p.quoteAnchor, 0, 0, p.quoteAnchor)) }) {
                                            Text("回查正文 p.${p.bodyPdfPage}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        BookStudyMode.QUESTION_DRIVEN -> {
            val qPack = remember(book.id, chapterNumber, preference.detailLevel, preference.explainLang, preference.inquiryQuestion, modePackJson) {
                modePackJson?.let { StudyModeStore.parseQuestionDrivenPack(it) }
                    ?: StudyModeStore.loadQuestionDrivenPack(library, book.id, chapterNumber, preference)
            }
            if (qPack != null && qPack.evidenceHits.isNotEmpty()) {
                PaperCard(Modifier.fillMaxWidth().testTag("question-driven-live-evidence"), color = colors.surface2, contentPadding = PaddingValues(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("核心结论与原书证据索引", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
                        if (qPack.directConclusion.isNotBlank()) {
                            Text(qPack.directConclusion, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
                        }
                        for (hit in qPack.evidenceHits.take(4)) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("• p.${hit.bodyJumpPage}: ${hit.howItAnswersQuestion}", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                                if (hit.limitOrCounterpoint.isNotBlank()) CoachCardMeta("边界条件：${hit.limitOrCounterpoint}")
                                V7TextButton(onClick = { onOpenAnchor(BookCompanionAnchor(hit.bodyJumpPage, hit.quoteAnchor, 0, 0, hit.quoteAnchor)) }) {
                                    Text("直达 p.${hit.bodyJumpPage} 核对原句")
                                }
                            }
                        }
                        if (qPack.argumentationScaffold.isNotEmpty()) {
                            Text("自写论证骨架（不代写全文）", style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
                            qPack.argumentationScaffold.forEachIndexed { sIdx, step ->
                                CoachCardMeta("${sIdx + 1}. $step")
                            }
                        }
                        if (qPack.antiGhostwriteNotice.isNotBlank()) {
                            CoachCardMeta(qPack.antiGhostwriteNotice)
                        }
                    }
                }
            }
        }

        BookStudyMode.READ_THROUGH -> Unit
    }
}
