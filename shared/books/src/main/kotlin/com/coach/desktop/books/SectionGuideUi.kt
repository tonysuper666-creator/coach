package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 选段浮动工具条的动作：讲讲 / 拆句 / 举个例子 / 换个说法 / 问…。 */
internal enum class SelectionExplainAction(val label: String, val kind: String, val tag: String) {
    EXPLAIN("讲讲", "explain", "selection-action-explain"),
    SPLIT("拆句", "split", "selection-action-split"),
    EXAMPLE("举个例子", "example", "selection-action-example"),
    REPHRASE("换个说法", "rephrase", "selection-action-rephrase"),
    ASK("问…", "ask", "selection-action-ask"),
}

internal sealed interface SectionGuideState {
    data object Idle : SectionGuideState
    data object Loading : SectionGuideState
    data class Ready(val guide: SectionGuide, val sectionBlocks: List<SourceBlock>) : SectionGuideState
    data class Failed(val message: String, val trouble: ExplainTrouble) : SectionGuideState
}

/**
 * 每节导读卡的按需预生成与本地缓存控制器：
 * - 只为真正读到的当前节和下一节预生成，导入时不跑全书；
 * - 每一节请求都带四样上下文：位置路径、上一节前情提要、本节全文、下一节标题；
 * - 任何请求都严格受逐书同意（[ExplainConsent]）保护。
 */
@Stable
internal class SectionGuideController(
    private val book: Textbook,
    private val library: TextbookLibrary?,
    private val consent: ExplainConsent,
    private val explainer: BookExplainer,
    private val scope: CoroutineScope,
) {
    val states = mutableStateMapOf<String, SectionGuideState>()
    private val inFlight = mutableSetOf<String>()

    fun sectionIndexForPage(page: Int): Int =
        book.lessons.indexOfFirst { page in it.firstPage..it.lastPage }

    fun sectionIndexForAnchor(page: Int, offsetUtf16: Int): Int {
        val exact = book.lessons.indexOfFirst { lesson ->
            lesson.blocks.any { b -> b.page == page && offsetUtf16 in b.start..b.end }
        }
        return if (exact >= 0) exact else sectionIndexForPage(page)
    }

    fun sectionForPage(page: Int): BookLesson? =
        sectionIndexForPage(page).takeIf { it >= 0 }?.let { book.lessons[it] }

    fun contextForSection(index: Int): ExplainSectionContext? {
        val section = book.lessons.getOrNull(index) ?: return null
        val prevSection = book.lessons.getOrNull(index - 1)
        val prevRecap = prevSection?.let { prev ->
            (states[prev.id] as? SectionGuideState.Ready)?.guide?.recap
                ?: library?.sectionGuide(book.id, prev.id)?.recap
        }
        val nextTitle = book.lessons.getOrNull(index + 1)?.title
        return ExplainSectionContext(
            location = section.breadcrumb,
            previousRecap = prevRecap,
            nextTitle = nextTitle,
            sectionBlocks = section.blocks,
        )
    }

    /** 读到 [page] 时：若开启自动导读且已连上并同意，后台按需预生成当前节和下一节。 */
    fun prefetchForPage(page: Int, autoGuide: Boolean, sectionIndexOverride: Int? = null) {
        if (!autoGuide) return
        val index = sectionIndexOverride?.takeIf { it in book.lessons.indices } ?: sectionIndexForPage(page)
        if (index < 0) return
        ensureSection(index, prefetchNext = true)
    }

    fun requestSection(index: Int) {
        ensureSection(index, prefetchNext = false)
    }

    internal fun ensureSection(index: Int, prefetchNext: Boolean = false) {
        val section = book.lessons.getOrNull(index) ?: return
        if (states[section.id] is SectionGuideState.Ready) {
            if (prefetchNext) ensureSection(index + 1, prefetchNext = false)
            return
        }
        if (section.id in inFlight) return
        if (explainer.identity == null || !consent.allowed(book.id)) return
        inFlight += section.id
        states[section.id] = SectionGuideState.Loading
        scope.launch {
            try {
                val cached = withContext(Dispatchers.IO) { library?.sectionGuide(book.id, section.id) }
                if (cached != null) {
                    states[section.id] = SectionGuideState.Ready(SectionGuide.fromCached(cached), section.blocks)
                    if (prefetchNext) ensureSection(index + 1, prefetchNext = false)
                    return@launch
                }
                val ctx = contextForSection(index) ?: return@launch
                val bookPages = maxOf(book.pages.size, book.pages.maxOfOrNull { it.number } ?: 1)
                when (val result = explainer.explainSectionGuide(ctx, bookPages)) {
                    is SectionGuideResult.Done -> {
                        states[section.id] = SectionGuideState.Ready(result.guide, section.blocks)
                        withContext(Dispatchers.IO) { library?.saveSectionGuide(book.id, section.id, result.guide.toCached()) }
                        if (prefetchNext) ensureSection(index + 1, prefetchNext = false)
                    }
                    is SectionGuideResult.Refused -> {
                        states[section.id] = SectionGuideState.Failed(result.message, result.trouble)
                    }
                }
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) {
                states[section.id] = SectionGuideState.Failed("导读暂时读不到，可以稍后重试。", ExplainTrouble.Other)
            } finally {
                inFlight -= section.id
            }
        }
    }
}

/**
 * 每节开头的导读窄卡：默认 3–5 行（本节讲什么、承接上一节、2–3 个要点），点开看完整讲解与段号出处。
 */
@Composable
internal fun SectionGuideCard(
    book: Textbook,
    blocks: List<SourceBlock>,
    section: BookLesson,
    state: SectionGuideState,
    paired: Boolean,
    consented: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onAllowConsent: () -> Unit,
    onRetry: () -> Unit,
    onOpenPair: () -> Unit,
    onOpenCitation: (Citation, SourceBlock) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    PaperCard(modifier.fillMaxWidth().testTag("section-guide-card"), contentPadding = PaddingValues(14.dp), contentSpacing = 8.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本节导读", style = MaterialTheme.typography.labelLarge, color = v7Pine(), modifier = Modifier.testTag("section-guide-label"))
            Text(section.breadcrumb, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("section-guide-breadcrumb"))
            if (state is SectionGuideState.Ready) {
                V7TextButton(onToggleExpand, modifier = Modifier.testTag("section-guide-toggle")) {
                    Text(if (expanded) "收起" else "展开完整讲解")
                }
            }
        }
        when {
            state is SectionGuideState.Failed -> {
                val onRelogin = LocalBookRelogin.current
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.message, style = MaterialTheme.typography.bodySmall,
                        color = if (state.trouble == ExplainTrouble.Revoked) v7Alert() else colors.fgSecondary,
                        modifier = Modifier.weight(1f).testTag("section-guide-failed"))
                    if (state.trouble.needsPairing) PaperButton(
                        onClick = { if (onRelogin != null) onRelogin("授权已撤销，请重新登录。") else onOpenPair() },
                    ) { Text("重新登录") }
                    else V7TextButton(onRetry, modifier = Modifier.testTag("section-guide-retry")) { Text("重试") }
                }
            }
            !paired -> {
                val onRelogin = LocalBookRelogin.current
                if (onRelogin != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("登录 Coach 服务后自动生成本节导读。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary, modifier = Modifier.weight(1f))
                    V7TextButton({ onRelogin(null) }, modifier = Modifier.testTag("section-guide-pair")) { Text("登录") }
                }
            }
            !consented -> Text("已在设置中关闭伴读讲解与提问。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
            state is SectionGuideState.Loading || state is SectionGuideState.Idle -> {
                Text("正在整理本节导读…", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary, modifier = Modifier.testTag("section-guide-loading"))
            }
            state is SectionGuideState.Ready -> {
                if (!expanded) {
                    Column(Modifier.fillMaxWidth().testTag("section-guide-brief"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.guide.brief.take(5).forEach { line ->
                            Text("· $line", style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth().testTag("section-guide-full")) {
                        ExplanationView(book, blocks, state.guide.explanation, state.sectionBlocks) { citation ->
                            state.sectionBlocks.firstOrNull { it.id == citation.blockId }?.let { onOpenCitation(citation, it) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 节标题旁淡淡的「讲讲这一节」按钮：平时淡字，悬停浮起，点了展开整节完整讲解。
 */
@Composable
internal fun FaintSectionExplainButton(
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(CoachV7.Radius.btn)
    val base = if (colors.dark) CoachV7.Dark.btnBg else CoachV7.Light.btnBg
    Text(
        if (expanded) "收起本节讲解" else "讲讲这一节",
        modifier
            .hoverable(interaction, enabled)
            .then(if (hovered && enabled) Modifier.paperShadow("button", colors.dark).clip(shape).background(base) else Modifier)
            .v7FocusRing(shape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("explain-section-trigger"),
        style = MaterialTheme.typography.labelLarge,
        color = when {
            !enabled -> colors.fgTertiary.copy(alpha = 0.45f)
            hovered -> colors.fgPrimary
            else -> colors.fgTertiary
        },
    )
}

/**
 * 选中任意文字后浮出的小工具条：「讲讲」「举个例子」「换个说法」「问…」。
 * 桌面拖选和安卓长按选中共用同一套组件。
 */
@Composable
internal fun SelectionExplainToolbar(
    onAction: (SelectionExplainAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.m)
    Row(
        modifier
            .paperShadow("button", colors.dark)
            .clip(shape)
            .background(if (colors.dark) CoachV7.Dark.menuBg else CoachV7.Light.menuBg)
            .border(1.dp, colors.borderSubtle, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("selection-explain-toolbar"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (action in SelectionExplainAction.entries) {
            V7TextButton(
                onClick = { onAction(action) },
                modifier = Modifier.testTag(action.tag),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(action.label, style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
            }
        }
        V7TextButton(
            onClick = onDismiss,
            modifier = Modifier.testTag("selection-toolbar-close"),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text("×", style = MaterialTheme.typography.labelLarge, color = colors.fgTertiary)
        }
    }
}

internal data class InlineExplainThread(
    val selection: SourceBlock,
    val context: ExplainSectionContext?,
    val turns: List<InlineExplainEntry> = emptyList(),
    val loading: Boolean = false,
    val error: Pair<String, ExplainTrouble>? = null,
    val askingOpen: Boolean = false,
)

internal data class InlineExplainEntry(
    val label: String,
    val explanation: Explanation,
    val sentBlocks: List<SourceBlock>,
)

/**
 * 选中文字后的就地回答与连续追问卡：共用这一节的上下文和前情提要。
 */
@Composable
internal fun InlineSelectionExplainCard(
    book: Textbook,
    allBlocks: List<SourceBlock>,
    thread: InlineExplainThread,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSubmitQuestion: () -> Unit,
    onRetry: () -> Unit,
    onOpenPair: () -> Unit,
    onOpenCitation: (Citation, SourceBlock) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    PaperCard(
        modifier.fillMaxWidth().heightIn(max = 460.dp).testTag("inline-explain-card"),
        contentPadding = PaddingValues(14.dp),
        contentSpacing = 10.dp,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "选段 · ${shortQuote(thread.selection.text)}",
                style = MaterialTheme.typography.labelLarge,
                color = colors.fgSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("inline-explain-quote"),
            )
            thread.context?.let { ctx ->
                Text(ctx.location, style = MaterialTheme.typography.labelSmall, color = colors.fgTertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            }
            V7TextButton(onClose, modifier = Modifier.testTag("inline-explain-close")) { Text("收起") }
        }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((index, entry) in thread.turns.withIndex()) {
                Column(Modifier.fillMaxWidth().testTag("inline-explain-turn-$index"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.label, style = MaterialTheme.typography.labelMedium, color = v7Pine())
                    ExplanationView(book, allBlocks, entry.explanation, entry.sentBlocks) { citation ->
                        entry.sentBlocks.firstOrNull { it.id == citation.blockId }?.let { onOpenCitation(citation, it) }
                    }
                }
            }
            if (thread.loading) {
                Text("思考中…", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary,
                    modifier = Modifier.testTag("inline-explain-loading"))
            }
            thread.error?.let { (message, trouble) ->
                val onRelogin = LocalBookRelogin.current
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(message, style = MaterialTheme.typography.bodySmall,
                        color = if (trouble == ExplainTrouble.Revoked) v7Alert() else colors.fgSecondary,
                        modifier = Modifier.weight(1f).testTag("inline-explain-error"))
                    if (trouble.needsPairing) PaperButton(
                        onClick = { if (onRelogin != null) onRelogin("授权已撤销，请重新登录。") else onOpenPair() },
                    ) { Text("重新登录") }
                    else V7TextButton(onRetry, modifier = Modifier.testTag("inline-explain-retry")) { Text("重试") }
                }
            }
        }
        if (thread.askingOpen || thread.turns.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    V7Field(
                        value = draft,
                        onValueChange = onDraftChange,
                        label = if (thread.turns.isEmpty()) "就这段问一句" else "继续追问",
                        enabled = !thread.loading,
                        modifier = Modifier.fillMaxWidth().testTag("inline-followup-input").onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed &&
                                BookPassageQuestion.accepts(draft) && !thread.loading) {
                                onSubmitQuestion(); true
                            } else false
                        },
                    )
                }
                PrimaryButton(
                    onClick = onSubmitQuestion,
                    enabled = !thread.loading && BookPassageQuestion.accepts(draft),
                    modifier = Modifier.testTag("inline-followup-send"),
                ) { Text("发送") }
            }
        }
    }
}

/**
 * 难点页边标记点开后的就地讲解卡：直接复用导读同一次调用返回的整节语境难点讲解，不额外调模型。
 */
@Composable
internal fun HardPointExplainCard(
    book: Textbook,
    allBlocks: List<SourceBlock>,
    section: BookLesson,
    hardPoint: SectionHardPoint,
    block: SourceBlock,
    onAskFollowUp: (() -> Unit)? = null,
    onOpenOriginal: (() -> Unit)? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    PaperCard(
        modifier.fillMaxWidth().testTag("hard-point-card"),
        contentPadding = PaddingValues(14.dp),
        contentSpacing = 8.dp,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "难点 · ${hardPoint.label}",
                style = MaterialTheme.typography.labelLarge,
                color = v7Pine(),
                modifier = Modifier.testTag("hard-point-title"),
            )
            Text(
                section.breadcrumb,
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            V7TextButton(onClose, modifier = Modifier.testTag("hard-point-close")) { Text("收起") }
        }
        Text(
            "${bookCitationLabel(book, allBlocks, Citation(hardPoint.anchor, block.id, block.page))} · ${shortQuote(block.text)}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.fgSecondary,
            modifier = Modifier.testTag("hard-point-source"),
        )
        Text(
            ReadableText.withoutCitationMarks(hardPoint.explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.fgPrimary,
            modifier = Modifier.testTag("hard-point-explanation"),
        )
        if (onAskFollowUp != null || onOpenOriginal != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onAskFollowUp != null) {
                    V7TextButton(onAskFollowUp, modifier = Modifier.testTag("hard-point-followup")) { Text("追问这段") }
                }
                if (onOpenOriginal != null) {
                    V7TextButton(onOpenOriginal, modifier = Modifier.testTag("hard-point-open-original")) { Text("查看原页") }
                }
            }
        }
    }
}

/**
 * 节末三行小结与可选 1–2 道选择题（接上现有做题主线，设置里可关）。
 */
@Composable
internal fun SectionEndSummaryCard(
    section: BookLesson,
    summaryLines: List<String>,
    practices: List<Practice>,
    answers: Map<Int, PracticeAnswer>,
    onAnswer: (Int, Practice, PracticeAnswer) -> Unit,
    onOpenMainline: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    var quizOpen by remember(section.id) { mutableStateOf(false) }
    val shownLines = summaryLines.take(3)
    PaperCard(
        modifier.fillMaxWidth().heightIn(max = if (quizOpen) 340.dp else 210.dp).testTag("section-end-summary-card"),
        contentPadding = PaddingValues(12.dp),
        contentSpacing = 6.dp,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本节小结", style = MaterialTheme.typography.labelLarge, color = v7Pine(), modifier = Modifier.testTag("section-end-summary-label"))
            Text(
                section.title,
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            V7TextButton(onDismiss, modifier = Modifier.testTag("section-end-dismiss"), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("收起")
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(Modifier.fillMaxWidth().testTag("section-end-summary-lines"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                shownLines.forEach { line ->
                    Text("· $line", style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (practices.isNotEmpty()) {
                    PaperButton(
                        onClick = { quizOpen = !quizOpen },
                        modifier = Modifier.testTag("section-end-start-quiz"),
                    ) {
                        Text(if (quizOpen) "收起自测" else "开始 ${practices.size} 道自测")
                    }
                }
                if (onOpenMainline != null) {
                    V7TextButton(onOpenMainline, modifier = Modifier.testTag("section-end-open-mainline")) {
                        Text("进入自测")
                    }
                }
            }
            if (quizOpen && practices.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().testTag("section-end-quiz-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    practices.forEachIndexed { qIdx, practice ->
                        val ans = answers[qIdx] ?: PracticeAnswer()
                        Column(Modifier.fillMaxWidth().testTag("section-end-quiz-$qIdx"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("第 ${qIdx + 1} 题 · ${practice.prompt}", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
                            practice.options.forEachIndexed { optIdx, option ->
                                val selected = ans.revealed && ans.text == option
                                val correct = if (selected) option == practice.answer else null
                                AnswerOption(
                                    letter = ('A' + optIdx).toString(),
                                    selected = selected,
                                    correct = correct,
                                    enabled = !ans.revealed,
                                    modifier = Modifier.testTag("section-quiz-$qIdx-choice-$optIdx"),
                                    onClick = { onAnswer(qIdx, practice, PracticeAnswer(text = option, revealed = true, guessed = false)) },
                                ) {
                                    Text(option, color = when (correct) { true -> colors.successFg; false -> colors.dangerFg; null -> colors.fgPrimary })
                                }
                            }
                            if (ans.revealed) {
                                val right = ans.text == practice.answer
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        if (right) "选对了。" else "正确答案：${practice.answer}（已加入复习队列）",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (right) colors.successFg else colors.fgSecondary,
                                        modifier = Modifier.weight(1f).testTag("section-quiz-$qIdx-feedback"),
                                    )
                                    V7TextButton({ onAnswer(qIdx, practice, PracticeAnswer()) }) { Text("重答") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
