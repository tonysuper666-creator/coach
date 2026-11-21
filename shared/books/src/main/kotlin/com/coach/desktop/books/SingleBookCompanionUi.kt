package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.CoachEmptyState
import com.coach.ui.v7.CoachErrorBanner
import com.coach.ui.v7.CoachLoadingDots
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface CompanionPackState {
    data object Idle : CompanionPackState
    data object Loading : CompanionPackState
    data class Ready(val pack: BookCompanionPack) : CompanionPackState
    data class Failed(val message: String, val trouble: ExplainTrouble) : CompanionPackState
}

/**
 * Modular single-book companion panel inside the right drawer ("讲解" tab):
 * - 5 modules: 本章要点 / 读前自测 / 推导看图模板 / 选做练习 / 原书章末习题三级提示
 * - Real-time draft persistence into `book_chapter_progress.practice_draft_json`
 * - Enforces UX-4 (`PrimaryButton <= 1`), UX-9/10/11 (six-state primitives), UX-12/13 (plain Chinese copy)
 */
@Composable
internal fun SingleBookCompanionPanel(
    book: Textbook,
    currentPage: Int,
    currentSectionIndex: Int,
    explainer: BookExplainer,
    library: TextbookLibrary,
    draft: ChapterPracticeDraft,
    onDraftChange: (ChapterPracticeDraft) -> Unit,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
    onCommitQuizAnswer: (BookLesson, Int, Practice, PracticeAnswer) -> Unit,
    onAskAboutText: (String) -> Unit,
    onOpenLogin: () -> Unit,
    onInputFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val aiEnabled = LocalReadingAiExplain.current
    val scope = rememberCoroutineScope()
    val chapterGroup = remember(book.id, book.lessons, currentSectionIndex) {
        ChapterCompanionGrouping.groupForSection(book, currentSectionIndex)
    }
    val currentSection = book.lessons.getOrNull(currentSectionIndex)
    var packState by remember(book.id, chapterGroup?.chapterId) { mutableStateOf<CompanionPackState>(CompanionPackState.Idle) }
    var fetchNonce by remember(book.id, chapterGroup?.chapterId) { mutableIntStateOf(0) }
    val activeModule = CompanionModuleTab.fromWire(draft.activeModule)
    val scroll = rememberScrollState()

    LaunchedEffect(explainer) { if (!explainer.loaded) explainer.load() }

    LaunchedEffect(book.id, chapterGroup?.chapterId, explainer.loaded, explainer.identity, aiEnabled, fetchNonce) {
        val group = chapterGroup ?: return@LaunchedEffect
        val cached = withContext(Dispatchers.IO) { library.cachedChapterCompanion(book.id, group.chapterId) }
        if (cached != null) {
            packState = CompanionPackState.Ready(cached)
            return@LaunchedEffect
        }
        if (!aiEnabled || !explainer.loaded || explainer.identity == null) {
            packState = CompanionPackState.Idle
            return@LaunchedEffect
        }
        packState = CompanionPackState.Loading
        try {
            val wire = ChapterCompanionGrouping.buildWireRequest(book, group)
            val blocks = ChapterCompanionGrouping.wireBlocks(group)
            when (val res = explainer.fetchChapterCompanion(wire, blocks)) {
                is ChapterCompanionFetchResult.Ready -> {
                    withContext(Dispatchers.IO) { library.saveChapterCompanion(book.id, group.chapterId, res.pack) }
                    packState = CompanionPackState.Ready(res.pack)
                }
                is ChapterCompanionFetchResult.Failed -> {
                    packState = CompanionPackState.Failed(res.message, res.trouble)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            packState = CompanionPackState.Failed("本章伴读暂时连不上，已有练习草稿已保留。", ExplainTrouble.Other)
        }
    }

    fun updateDraft(transform: (ChapterPracticeDraft) -> ChapterPracticeDraft) {
        val nodeId = chapterGroup?.chapterId ?: currentSection?.id ?: "p$currentPage"
        val updated = transform(draft).copy(
            bookId = book.id,
            tocNodeId = nodeId,
            page = currentPage,
            companionOpen = true,
            updatedAt = System.currentTimeMillis(),
        )
        onDraftChange(updated)
    }

    Column(modifier.fillMaxSize().testTag("single-book-companion")) {
        // 5-module selector strip (scrollable, zero PrimaryButton here to obey UX-4)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("companion-module-strip"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pillShape = RoundedCornerShape(CoachV7.Radius.s)
            val activeBg = colors.actionPrimary.copy(alpha = 0.14f)
            for (mod in CompanionModuleTab.entries) {
                val selected = activeModule == mod
                Text(
                    mod.shortLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) colors.actionPrimary else colors.fgSecondary,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(pillShape)
                        .background(if (selected) activeBg else Color.Transparent)
                        .border(1.dp, if (selected) colors.actionPrimary.copy(alpha = 0.35f) else colors.borderSubtle, pillShape)
                        .clickable(role = Role.Tab) {
                            updateDraft { it.copy(activeModule = mod.name) }
                        }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag(mod.tag),
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.7f))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(14.dp)
                    .testTag("companion-module-body"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (chapterGroup == null) {
                    CoachEmptyState(
                        title = "当前页暂未归入正文小节。",
                        hint = "翻到正文第 1 章后，这里会自动呈现本章伴读与练习。",
                    )
                    return@Column
                }

                // Header meta line
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${activeModule.fullTitle} · ${chapterGroup.chapterTitle}",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.fgPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("companion-module-title"),
                    )
                    CoachCardMeta(
                        "第 ${chapterGroup.firstPage}–${chapterGroup.lastPage} 页 · 随时可停，进度与草稿自动保存",
                        Modifier.testTag("companion-module-meta"),
                    )
                }

                when (val state = packState) {
                    CompanionPackState.Loading -> {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            CoachLoadingDots(label = "正在按节整理本章伴读…")
                        }
                    }
                    is CompanionPackState.Failed -> {
                        CoachErrorBanner(
                            message = state.message,
                            retryLabel = if (state.trouble.needsPairing) "去登录" else "重试",
                            onRetry = {
                                if (state.trouble.needsPairing) onOpenLogin()
                                else fetchNonce++
                            },
                            modifier = Modifier.testTag("companion-error-banner"),
                        )
                    }
                    CompanionPackState.Idle -> {
                        when {
                            !aiEnabled -> CoachEmptyState(
                                title = "伴读整理已在设置中关闭。",
                                hint = "打开设置里的伴读开关后，即可自动生成本章要点与练习。",
                            )
                            explainer.loaded && explainer.identity == null -> CoachEmptyState(
                                title = "登录后可使用本章伴读。",
                                hint = "本地阅读与已缓存草稿不受影响，登录后可按节生成要点与练习。",
                                actionLabel = "去连接服务",
                                onAction = onOpenLogin,
                            )
                            else -> Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                                CoachLoadingDots(label = "正在准备本章伴读…")
                            }
                        }
                    }
                    is CompanionPackState.Ready -> {
                        val pack = state.pack
                        when (activeModule) {
                            CompanionModuleTab.KEY_POINTS -> KeyPointsModuleView(
                                pack = pack,
                                onOpenAnchor = onOpenAnchor,
                                onAskTerm = onAskAboutText,
                                onJumpPretest = { updateDraft { it.copy(activeModule = CompanionModuleTab.PRETEST.name) } },
                            )
                            CompanionModuleTab.PRETEST -> PretestModuleView(
                                pack = pack,
                                draft = draft,
                                currentSection = currentSection,
                                onSelectOption = { qId, opt ->
                                    updateDraft {
                                        it.copy(
                                            activeQuestionId = qId,
                                            pretestSelections = it.pretestSelections + (qId to opt),
                                        )
                                    }
                                },
                                onToggleGuessed = { qId ->
                                    updateDraft {
                                        val cur = it.pretestGuessed[qId] == true
                                        it.copy(
                                            activeQuestionId = qId,
                                            pretestGuessed = it.pretestGuessed + (qId to !cur),
                                        )
                                    }
                                },
                                onSubmitQuestion = { idx, quiz ->
                                    val chosen = draft.pretestSelections[quiz.id].orEmpty()
                                    val guessed = draft.pretestGuessed[quiz.id] == true
                                    updateDraft {
                                        it.copy(
                                            activeQuestionId = quiz.id,
                                            pretestSubmitted = it.pretestSubmitted + quiz.id,
                                        )
                                    }
                                    val sec = book.lessons.firstOrNull { it.id == quiz.sectionId } ?: currentSection
                                    if (sec != null && chosen.isNotBlank()) {
                                        onCommitQuizAnswer(
                                            sec,
                                            idx,
                                            Practice(quiz.stem, quiz.anchor?.quoteAnchor.orEmpty(), quiz.correctOption, quiz.options),
                                            PracticeAnswer(text = chosen, revealed = true, guessed = guessed),
                                        )
                                    }
                                },
                                onOpenAnchor = onOpenAnchor,
                            )
                            CompanionModuleTab.DERIVATION -> DerivationModuleView(
                                pack = pack,
                                onOpenAnchor = onOpenAnchor,
                                onAskStep = onAskAboutText,
                            )
                            CompanionModuleTab.PRACTICE -> OptionalPracticeModuleView(
                                pack = pack,
                                draft = draft,
                                currentSection = currentSection,
                                onSelectOption = { qId, opt ->
                                    updateDraft {
                                        it.copy(
                                            activeQuestionId = qId,
                                            practiceSelections = it.practiceSelections + (qId to opt),
                                        )
                                    }
                                },
                                onEditText = { qId, text, cursor ->
                                    updateDraft {
                                        it.copy(
                                            activeQuestionId = qId,
                                            practiceTextDrafts = it.practiceTextDrafts + (qId to text),
                                            practiceCursorPositions = it.practiceCursorPositions + (qId to cursor),
                                        )
                                    }
                                },
                                onToggleGuessed = { qId ->
                                    updateDraft {
                                        val cur = it.practiceGuessed[qId] == true
                                        it.copy(
                                            activeQuestionId = qId,
                                            practiceGuessed = it.practiceGuessed + (qId to !cur),
                                        )
                                    }
                                },
                                onSubmit = { idx, quiz ->
                                    val chosen = if (quiz.options.isNotEmpty()) {
                                        draft.practiceSelections[quiz.id].orEmpty()
                                    } else {
                                        draft.practiceTextDrafts[quiz.id].orEmpty()
                                    }
                                    val guessed = draft.practiceGuessed[quiz.id] == true
                                    updateDraft {
                                        it.copy(
                                            activeQuestionId = quiz.id,
                                            practiceSubmitted = it.practiceSubmitted + quiz.id,
                                        )
                                    }
                                    val sec = book.lessons.firstOrNull { it.id == quiz.sectionId } ?: currentSection
                                    if (sec != null && chosen.isNotBlank()) {
                                        onCommitQuizAnswer(
                                            sec,
                                            100 + idx,
                                            Practice(quiz.stem, quiz.anchor?.quoteAnchor.orEmpty(), quiz.correctOption, quiz.options),
                                            PracticeAnswer(text = chosen, revealed = true, understood = chosen == quiz.correctOption, guessed = guessed),
                                        )
                                    }
                                },
                                onOpenAnchor = onOpenAnchor,
                                onInputFocusChanged = onInputFocusChanged,
                            )
                            CompanionModuleTab.EXERCISE_HINTS -> ExerciseHintsModuleView(
                                pack = pack,
                                draft = draft,
                                onRevealTier = { exId, nextTier ->
                                    updateDraft {
                                        it.copy(
                                            activeQuestionId = exId,
                                            exerciseHintLevels = it.exerciseHintLevels + (exId to nextTier.coerceIn(0, 3)),
                                        )
                                    }
                                },
                                onOpenAnchor = onOpenAnchor,
                            )
                        }
                    }
                }
            }
            if (scroll.maxValue > 0) {
                BookScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun KeyPointsModuleView(
    pack: BookCompanionPack,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
    onAskTerm: (String) -> Unit,
    onJumpPretest: () -> Unit,
) {
    val colors = CoachTheme.colors
    if (pack.coreQuestion.isNotBlank()) {
        PaperCard(Modifier.fillMaxWidth().testTag("companion-core-question"), contentPadding = PaddingValues(12.dp), contentSpacing = 6.dp) {
            Text("核心问题", style = MaterialTheme.typography.labelMedium, color = v7Pine())
            Text(pack.coreQuestion, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
        }
    }
    if (pack.keyPoints.isEmpty()) {
        CoachEmptyState(title = "本章暂无提取要点。", hint = "可直接阅读原页，或划选句子点「讲清」。")
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("companion-keypoints-list")) {
            pack.keyPoints.forEachIndexed { idx, item ->
                PaperCard(Modifier.fillMaxWidth().testTag("companion-kp-$idx"), contentPadding = PaddingValues(12.dp), contentSpacing = 6.dp) {
                    Text("${idx + 1}. ${item.summary}", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
                    item.anchor?.let { anchor ->
                        AnchorChip(anchor = anchor, onClick = { onOpenAnchor(anchor) })
                    }
                }
            }
        }
    }
    if (pack.jargonGlosses.isNotEmpty()) {
        PaperCard(Modifier.fillMaxWidth().testTag("companion-jargon-card"), contentPadding = PaddingValues(12.dp), contentSpacing = 8.dp) {
            Text("专业词扫障", style = MaterialTheme.typography.labelMedium, color = v7Pine())
            for (g in pack.jargonGlosses) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        g.term,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.fgPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.widthIn(min = 72.dp, max = 130.dp),
                    )
                    Text(g.plainMeaning, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary, modifier = Modifier.weight(1f))
                    V7TextButton({ onAskTerm("解释术语 ${g.term} 在本节里的准确含义。") }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Text("问", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    if (pack.pretest.isNotEmpty()) {
        PaperButton(onClick = onJumpPretest, modifier = Modifier.fillMaxWidth().testTag("companion-go-pretest")) {
            Text("做 ${pack.pretest.size} 道读前自测")
        }
    }
}

@Composable
private fun PretestModuleView(
    pack: BookCompanionPack,
    draft: ChapterPracticeDraft,
    currentSection: BookLesson?,
    onSelectOption: (String, String) -> Unit,
    onToggleGuessed: (String) -> Unit,
    onSubmitQuestion: (Int, BookCompanionQuiz) -> Unit,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
) {
    val colors = CoachTheme.colors
    if (pack.pretest.isEmpty()) {
        CoachEmptyState(title = "本章暂无读前自测题。", hint = "直接阅读原页正文，随时可切到「选做练习」。")
        return
    }
    val activeIdx = pack.pretest.indexOfFirst { it.id == draft.activeQuestionId }.takeIf { it >= 0 }
        ?: pack.pretest.indexOfFirst { it.id !in draft.pretestSubmitted }.takeIf { it >= 0 }
        ?: 0

    Text(
        "先猜一猜不计错题惩罚，提交后标出原书对应页码供带着问题去读。",
        style = MaterialTheme.typography.bodySmall,
        color = colors.fgSecondary,
    )
    pack.pretest.forEachIndexed { idx, quiz ->
        val selected = draft.pretestSelections[quiz.id]
        val guessed = draft.pretestGuessed[quiz.id] == true
        val submitted = quiz.id in draft.pretestSubmitted
        val isPrimaryCard = idx == activeIdx && !submitted
        PaperCard(Modifier.fillMaxWidth().testTag("pretest-card-$idx"), contentPadding = PaddingValues(12.dp), contentSpacing = 8.dp) {
            Text("第 ${idx + 1} / ${pack.pretest.size} 题", style = MaterialTheme.typography.labelSmall, color = v7Pine())
            Text(quiz.stem, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
            quiz.options.forEachIndexed { optIdx, opt ->
                val chosen = selected == opt
                val borderColor = when {
                    submitted && opt == quiz.correctOption -> v7Pine()
                    submitted && chosen && opt != quiz.correctOption -> v7Alert()
                    chosen -> colors.actionPrimary
                    else -> colors.borderSubtle
                }
                val shape = RoundedCornerShape(CoachV7.Radius.s)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(if (chosen) colors.actionPrimary.copy(alpha = 0.08f) else colors.surface1)
                        .border(1.dp, borderColor, shape)
                        .clickable(enabled = !submitted) { onSelectOption(quiz.id, opt) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("pretest-opt-$idx-$optIdx"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(opt, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary, modifier = Modifier.weight(1f))
                }
            }
            if (!submitted) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    V7TextButton(
                        onClick = { onToggleGuessed(quiz.id) },
                        modifier = Modifier.testTag("pretest-guess-$idx"),
                    ) {
                        Text(if (guessed) "✓ 这题是猜的" else "这题是猜的", color = if (guessed) colors.actionPrimary else colors.fgSecondary)
                    }
                    Spacer(Modifier.weight(1f))
                    if (isPrimaryCard) {
                        PrimaryButton(
                            onClick = { onSubmitQuestion(idx, quiz) },
                            enabled = !selected.isNullOrBlank(),
                            modifier = Modifier.testTag("pretest-submit-$idx"),
                        ) { Text("提交") }
                    } else {
                        PaperButton(
                            onClick = { onSubmitQuestion(idx, quiz) },
                            enabled = !selected.isNullOrBlank(),
                            modifier = Modifier.testTag("pretest-submit-$idx"),
                        ) { Text("提交") }
                    }
                }
            } else {
                val ok = selected == quiz.correctOption && !guessed
                Text(
                    when {
                        ok -> "回答准确。${quiz.revealHint}"
                        guessed && selected == quiz.correctOption -> "猜对了，已记入今日复习巩固。${quiz.revealHint}"
                        else -> "带着悬念去读原文。${quiz.revealHint}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) v7Pine() else colors.fgSecondary,
                    modifier = Modifier.testTag("pretest-feedback-$idx"),
                )
                if (quiz.explanation.isNotBlank()) {
                    Text(quiz.explanation, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
                }
                quiz.anchor?.let { AnchorChip(it) { onOpenAnchor(it) } }
            }
        }
    }
}

@Composable
private fun DerivationModuleView(
    pack: BookCompanionPack,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
    onAskStep: (String) -> Unit,
) {
    val colors = CoachTheme.colors
    if (pack.derivations.isEmpty()) {
        CoachEmptyState(title = "本章暂未检测到公式或图形推导。", hint = "读到含模型与坐标图的章节时，这里会展示分步模板。")
        return
    }
    pack.derivations.forEachIndexed { idx, drv ->
        PaperCard(Modifier.fillMaxWidth().testTag("derivation-card-$idx"), contentPadding = PaddingValues(12.dp), contentSpacing = 8.dp) {
            Text(drv.title, style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
            if (drv.setup.isNotBlank()) {
                Text("设定：${drv.setup}", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
            }
            drv.steps.forEachIndexed { sIdx, step ->
                Text("${sIdx + 1}. $step", style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
            }
            if (drv.graphCheck.isNotBlank()) {
                Text("看图核对：${drv.graphCheck}", style = MaterialTheme.typography.bodySmall, color = v7Pine())
            }
            if (drv.pitfall.isNotBlank()) {
                Text("常见失分点：${drv.pitfall}", style = MaterialTheme.typography.bodySmall, color = v7Alert())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                drv.anchor?.let { AnchorChip(it) { onOpenAnchor(it) } }
                V7TextButton({ onAskStep("请一步步拆解「${drv.title}」的推导逻辑。") }) { Text("追问推导") }
            }
        }
    }
}

@Composable
private fun OptionalPracticeModuleView(
    pack: BookCompanionPack,
    draft: ChapterPracticeDraft,
    currentSection: BookLesson?,
    onSelectOption: (String, String) -> Unit,
    onEditText: (String, String, Int) -> Unit,
    onToggleGuessed: (String) -> Unit,
    onSubmit: (Int, BookCompanionQuiz) -> Unit,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
    onInputFocusChanged: (Boolean) -> Unit = {},
) {
    val colors = CoachTheme.colors
    if (pack.optionalPractice.isEmpty()) {
        CoachEmptyState(title = "本章暂无选做练习。", hint = "可先看「本章要点」或「原书章末习题三级提示」。")
        return
    }
    val activeIdx = pack.optionalPractice.indexOfFirst { it.id == draft.activeQuestionId }.takeIf { it >= 0 }
        ?: pack.optionalPractice.indexOfFirst { it.id !in draft.practiceSubmitted }.takeIf { it >= 0 }
        ?: 0

    Text(
        "每题独立选做、随时可停，输入内容实时落盘，退出后点首页「继续」精确回到此处。",
        style = MaterialTheme.typography.bodySmall,
        color = colors.fgSecondary,
    )
    pack.optionalPractice.forEachIndexed { idx, quiz ->
        val isMcq = quiz.options.size >= 2
        val submitted = quiz.id in draft.practiceSubmitted
        val guessed = draft.practiceGuessed[quiz.id] == true
        val isPrimaryCard = idx == activeIdx && !submitted
        val chosen = if (isMcq) draft.practiceSelections[quiz.id].orEmpty() else draft.practiceTextDrafts[quiz.id].orEmpty()

        PaperCard(Modifier.fillMaxWidth().testTag("practice-card-$idx"), contentPadding = PaddingValues(12.dp), contentSpacing = 8.dp) {
            Text("选做 ${idx + 1} / ${pack.optionalPractice.size}", style = MaterialTheme.typography.labelSmall, color = v7Pine())
            Text(quiz.stem, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
            if (isMcq) {
                quiz.options.forEachIndexed { optIdx, opt ->
                    val selected = chosen == opt
                    val shape = RoundedCornerShape(CoachV7.Radius.s)
                    val borderColor = when {
                        submitted && opt == quiz.correctOption -> v7Pine()
                        submitted && selected && opt != quiz.correctOption -> v7Alert()
                        selected -> colors.actionPrimary
                        else -> colors.borderSubtle
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(if (selected) colors.actionPrimary.copy(alpha = 0.08f) else colors.surface1)
                            .border(1.dp, borderColor, shape)
                            .clickable(enabled = !submitted) { onSelectOption(quiz.id, opt) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("practice-opt-$idx-$optIdx"),
                    ) {
                        Text(opt, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
                    }
                }
            } else {
                val savedText = draft.practiceTextDrafts[quiz.id].orEmpty()
                val savedCursor = (draft.practiceCursorPositions[quiz.id] ?: savedText.length).coerceIn(0, savedText.length)
                var tfv by remember(quiz.id) { mutableStateOf(TextFieldValue(savedText, TextRange(savedCursor))) }
                LaunchedEffect(savedText) {
                    if (tfv.text != savedText) {
                        val c = (draft.practiceCursorPositions[quiz.id] ?: savedText.length).coerceIn(0, savedText.length)
                        tfv = TextFieldValue(savedText, TextRange(c))
                    }
                }
                val shape = RoundedCornerShape(CoachV7.Radius.btn)
                BasicTextField(
                    value = tfv,
                    onValueChange = { next ->
                        tfv = next
                        onEditText(quiz.id, next.text, next.selection.end)
                    },
                    enabled = !submitted,
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.fgPrimary),
                    cursorBrush = SolidColor(colors.fgPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(colors.surface1)
                        .border(1.dp, colors.borderSubtle, shape)
                        .onFocusChanged { onInputFocusChanged(it.isFocused) }
                        .padding(10.dp)
                        .testTag("practice-draft-input-$idx"),
                )
            }
            if (!submitted) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    V7TextButton(
                        onClick = { onToggleGuessed(quiz.id) },
                        modifier = Modifier.testTag("practice-guess-$idx"),
                    ) {
                        Text(if (guessed) "✓ 这题是猜的" else "这题是猜的", color = if (guessed) colors.actionPrimary else colors.fgSecondary)
                    }
                    Spacer(Modifier.weight(1f))
                    if (isPrimaryCard) {
                        PrimaryButton(
                            onClick = { onSubmit(idx, quiz) },
                            enabled = chosen.isNotBlank(),
                            modifier = Modifier.testTag("practice-submit-$idx"),
                        ) { Text("核对") }
                    } else {
                        PaperButton(
                            onClick = { onSubmit(idx, quiz) },
                            enabled = chosen.isNotBlank(),
                            modifier = Modifier.testTag("practice-submit-$idx"),
                        ) { Text("核对") }
                    }
                }
            } else {
                Text("参考结论：${quiz.correctOption}", style = MaterialTheme.typography.bodySmall, color = v7Pine(), modifier = Modifier.testTag("practice-answer-$idx"))
                if (quiz.explanation.isNotBlank()) {
                    Text(quiz.explanation, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
                }
                quiz.anchor?.let { AnchorChip(it) { onOpenAnchor(it) } }
            }
        }
    }
}

@Composable
private fun ExerciseHintsModuleView(
    pack: BookCompanionPack,
    draft: ChapterPracticeDraft,
    onRevealTier: (String, Int) -> Unit,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
) {
    val colors = CoachTheme.colors
    if (pack.exerciseHints.isEmpty()) {
        CoachEmptyState(title = "本章未识别到原书章末习题。", hint = "含 Exercises / 习题 节的章节会自动生成三级渐进提示。")
        return
    }
    Text(
        "按需逐级点开提示，已展开层级自动保存。",
        style = MaterialTheme.typography.bodySmall,
        color = colors.fgSecondary,
    )
    pack.exerciseHints.forEachIndexed { idx, ex ->
        val level = (draft.exerciseHintLevels[ex.id] ?: 0).coerceIn(0, 3)
        PaperCard(Modifier.fillMaxWidth().testTag("exercise-hint-card-$idx"), contentPadding = PaddingValues(12.dp), contentSpacing = 8.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ex.problemLabel, style = MaterialTheme.typography.labelLarge, color = v7Pine(), modifier = Modifier.weight(1f))
                ex.anchor?.let { AnchorChip(it) { onOpenAnchor(it) } }
            }
            Text(ex.questionSummary, style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
            if (level >= 1) {
                Text(ex.hint1Direction, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary, modifier = Modifier.testTag("exercise-hint1-$idx"))
            }
            if (level >= 2) {
                Text(ex.hint2Method, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary, modifier = Modifier.testTag("exercise-hint2-$idx"))
            }
            if (level >= 3) {
                Text(ex.hint3Check, style = MaterialTheme.typography.bodySmall, color = v7Pine(), modifier = Modifier.testTag("exercise-hint3-$idx"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (level) {
                    0 -> PaperButton({ onRevealTier(ex.id, 1) }, modifier = Modifier.testTag("exercise-reveal-$idx")) { Text("提示 1") }
                    1 -> PaperButton({ onRevealTier(ex.id, 2) }, modifier = Modifier.testTag("exercise-reveal-$idx")) { Text("提示 2") }
                    2 -> PaperButton({ onRevealTier(ex.id, 3) }, modifier = Modifier.testTag("exercise-reveal-$idx")) { Text("核对结论") }
                    else -> V7TextButton({ onRevealTier(ex.id, 0) }, modifier = Modifier.testTag("exercise-reset-$idx")) { Text("收起提示") }
                }
            }
        }
    }
}

@Composable
private fun AnchorChip(anchor: BookCompanionAnchor, onClick: () -> Unit) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.s)
    Text(
        "原文第 ${anchor.pdfPage} 页",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = LocalV7Fonts.current.numbers,
        color = v7Pine(),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(shape)
            .background(v7Pine().copy(alpha = 0.08f))
            .border(1.dp, v7Pine().copy(alpha = 0.30f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("companion-anchor-p${anchor.pdfPage}"),
    )
}
