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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.LocalDeskMetrics
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.paperShadow
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import com.coach.ui.v7.v7FocusRing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** A cited source block: [blockId] is `<bookHash>:p<page>:<index>`, exactly as the library import produced it. */
internal data class Citation(val anchor: String, val blockId: String, val page: Int)
internal data class Explanation(val text: String, val citations: List<Citation>, val elapsedMs: Long)
internal sealed interface ExplainResult {
    data class Done(val explanation: Explanation) : ExplainResult
    /** [message] 是给人看的那一句；[trouble] 决定给不给「再试一次」或「重新配对」。 */
    data class Refused(val message: String, val trouble: ExplainTrouble = ExplainTrouble.Other) : ExplainResult {
        val unauthorized get() = trouble == ExplainTrouble.Revoked
    }
}

/** 每节讲解与选段追问共用的整节上下文。 */
internal data class ExplainSectionContext(
    val location: String,
    val previousRecap: String?,
    val nextTitle: String?,
    val sectionBlocks: List<SourceBlock>,
)

internal data class FollowUpTurn(val question: String, val answer: String)

internal data class SectionHardPoint(
    val blockId: String,
    val anchor: String,
    val page: Int,
    val label: String,
    val explanation: String,
)

internal data class SectionGuide(
    val brief: List<String>,
    val explanation: Explanation,
    val recap: String,
    val hardPoints: List<SectionHardPoint> = emptyList(),
    val summary: List<String> = emptyList(),
) {
    fun toCached(): CachedSectionGuide = CachedSectionGuide(
        brief = brief,
        text = explanation.text,
        citations = explanation.citations.map { CachedCitation(it.anchor, it.blockId, it.page) },
        recap = recap,
        hardPoints = hardPoints.map { CachedHardPoint(it.blockId, it.anchor, it.page, it.label, it.explanation) },
        summary = summary,
    )

    companion object {
        fun fromCached(cached: CachedSectionGuide): SectionGuide = SectionGuide(
            brief = cached.brief,
            explanation = Explanation(
                text = cached.text,
                citations = cached.citations.map { Citation(it.anchor, it.blockId, it.page) },
                elapsedMs = 0L,
            ),
            recap = cached.recap,
            hardPoints = cached.hardPoints.map { SectionHardPoint(it.blockId, it.anchor, it.page, it.label, it.explanation) },
            summary = cached.summary,
        )
    }
}

internal sealed interface SectionGuideResult {
    data class Done(val guide: SectionGuide) : SectionGuideResult
    data class Refused(val message: String, val trouble: ExplainTrouble = ExplainTrouble.Other) : SectionGuideResult
}

/**
 * 2.0 取消逐书 AI 隐私同意墙：改由首次登录页一句说明 + 设置全局开关（`ReadingSettings.aiExplain`）控制。
 * 保留类签名以兼容既有调用方与测试夹具，默认允许发送所选片段，可在设置中全局关闭。
 */
internal class ExplainConsent(private val directory: Path) {
    private val settings by lazy { runCatching { ReadingSettings(directory) }.getOrNull() }
    fun allowed(@Suppress("UNUSED_PARAMETER") bookId: String): Boolean = settings?.aiExplain ?: true
    fun allow(@Suppress("UNUSED_PARAMETER") bookId: String) {
        runCatching { settings?.chooseAiExplain(true) }
    }
}

/** The request and reply contract with the paired server; pure so it is testable without a network. */
internal object ExplainWire {
    fun selectionBlocks(chosen: SourceBlock, context: ExplainSectionContext?): List<SourceBlock> {
        val blocks = context?.sectionBlocks?.filterNot { it.heading }.orEmpty()
        val containsSelection = blocks.any {
            it.id == chosen.id || (it.page == chosen.page && chosen.text.trim() in it.text)
        }
        return if (containsSelection && BookLessonWire.fits(blocks)) blocks else listOf(chosen)
    }

    fun question(requestId: String, chosen: SourceBlock, question: String): JsonObject {
        require(BookPassageQuestion.accepts(question))
        require(chosen.text.trim().length in 1..4_000)
        return JsonObject(request(requestId, chosen, listOf(chosen)) + mapOf(
            "kind" to JsonPrimitive("ask"), "question" to JsonPrimitive(question.trim())))
    }
    /** The chosen block plus its neighbours in book order; the server may cite only these. */
    fun context(blocks: List<SourceBlock>, chosen: SourceBlock): List<SourceBlock> {
        val i = blocks.indexOfFirst { it.id == chosen.id }.takeIf { it >= 0 } ?: return listOf(chosen)
        return listOfNotNull(blocks.getOrNull(i - 1)?.takeIf { !it.heading }, chosen, blocks.getOrNull(i + 1)?.takeIf { !it.heading })
    }

    fun request(requestId: String, chosen: SourceBlock, context: List<SourceBlock>): JsonObject = buildJsonObject {
        put("requestId", requestId)
        put("kind", "explain")
        put("selection", chosen.text.trim().take(4_000))
        put("blocks", buildJsonArray { for (b in context) add(buildJsonObject { put("id", b.id); put("text", b.text) }) })
    }

    fun sectionGuideRequest(
        requestId: String,
        blocks: List<SourceBlock>,
        bookPages: Int,
        sectionContext: ExplainSectionContext,
    ): JsonObject {
        val base = BookLessonWire.request(requestId, blocks, bookPages)
        return JsonObject(base + mapOf(
            "kind" to JsonPrimitive("guide"),
            "sectionContext" to encodeSectionContext(sectionContext),
        ))
    }

    fun bookStructureRequest(
        requestId: String,
        candidates: List<SourceBlock>,
        bookPages: Int,
    ): JsonObject {
        val base = BookLessonWire.request(requestId, candidates, bookPages)
        return JsonObject(base + mapOf("kind" to JsonPrimitive("structure")))
    }

    fun selectionRequest(
        requestId: String,
        kind: String,
        chosen: SourceBlock,
        blocks: List<SourceBlock>,
        sectionContext: ExplainSectionContext? = null,
        question: String? = null,
        history: List<FollowUpTurn> = emptyList(),
    ): JsonObject = buildJsonObject {
        require(kind in setOf("explain", "example", "rephrase", "split", "ask"))
        require(chosen.text.trim().length in 1..4_000)
        put("requestId", requestId)
        put("kind", kind)
        put("selection", chosen.text.trim().take(4_000))
        if (kind == "ask") {
            val q = requireNotNull(question)
            require(BookPassageQuestion.accepts(q))
            put("question", q.trim())
        }
        put("blocks", buildJsonArray { for (b in blocks) add(buildJsonObject { put("id", b.id); put("text", b.text) }) })
        sectionContext?.let { put("sectionContext", encodeSectionContext(it)) }
        if (history.isNotEmpty()) {
            put("history", buildJsonArray {
                history.takeLast(8).forEach { turn ->
                    add(buildJsonObject {
                        put("question", turn.question.trim().take(500))
                        put("answer", turn.answer.trim().take(2000))
                    })
                }
            })
        }
    }

    private fun encodeSectionContext(ctx: ExplainSectionContext): JsonObject = buildJsonObject {
        put("location", ctx.location.trim().take(300))
        ctx.previousRecap?.trim()?.takeIf { it.isNotEmpty() }?.let { put("previousRecap", it.take(600)) }
        ctx.nextTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { put("nextTitle", it.take(200)) }
    }

    fun parseGuide(body: JsonObject, sent: List<SourceBlock>): SectionGuideResult {
        return when (val parsed = parse(body, sent)) {
            is ExplainResult.Refused -> SectionGuideResult.Refused(parsed.message, parsed.trouble)
            is ExplainResult.Done -> {
                val brief = (body["brief"] as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
                }.take(5).ifEmpty {
                    parsed.explanation.text.split("\n\n")
                        .map { ReadableText.withoutCitationMarks(it) }
                        .filter { it.isNotBlank() }
                        .take(4)
                }
                val recap = (body["recap"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
                    ?: brief.joinToString(" ").take(150)
                val summary = (body["summary"] as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
                }.take(3).ifEmpty { brief.take(3) }
                val seenHp = HashSet<String>()
                val hardPoints = (body["hardPoints"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val blockId = (obj["blockId"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@mapNotNull null
                    val block = sent.firstOrNull { it.id == blockId } ?: return@mapNotNull null
                    if (!seenHp.add(block.id)) return@mapNotNull null
                    val page = (obj["pdfPage"] as? JsonPrimitive)?.intOrNull ?: block.page
                    if (page != block.page) return@mapNotNull null
                    val explanation = (obj["explanation"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
                        ?: return@mapNotNull null
                    val anchor = (obj["anchor"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    val label = (obj["label"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) ?: "难点"
                    SectionHardPoint(block.id, anchor, block.page, label, explanation)
                }.take(2)
                SectionGuideResult.Done(SectionGuide(brief, parsed.explanation, recap, hardPoints, summary))
            }
        }
    }

    fun parseStructure(body: JsonObject, sent: List<SourceBlock>): ModelSectionResult {
        val valid = (body["valid"] as? JsonPrimitive)?.booleanOrNull == true
        val rawSections = body["sections"] as? JsonArray
        if (!valid || rawSections == null || rawSections.isEmpty()) {
            val message = (body["text"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                ?: "未识别出明确的章节边界，已按段落边界自动分节。"
            return ModelSectionResult.Failed(message)
        }
        val sentIndex = sent.withIndex().associate { it.value.id to it.index }
        var lastIdx = -1
        val boundaries = mutableListOf<ModelSectionBoundary>()
        for (el in rawSections) {
            val obj = el as? JsonObject ?: return ModelSectionResult.Failed("分节格式无法核对，已退回段落边界切分。")
            val blockId = (obj["startBlockId"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val idx = sentIndex[blockId] ?: return ModelSectionResult.Failed("分节位置不在原文中，已退回段落边界切分。")
            if (idx <= lastIdx) return ModelSectionResult.Failed("分节顺序非单调递增，已退回段落边界切分。")
            lastIdx = idx
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (title.isEmpty()) return ModelSectionResult.Failed("分节标题为空，已退回段落边界切分。")
            val block = sent[idx]
            val page = (obj["pdfPage"] as? JsonPrimitive)?.intOrNull ?: block.page
            if (page != block.page) return ModelSectionResult.Failed("分节页码与原文不一致，已退回段落边界切分。")
            boundaries += ModelSectionBoundary(block.id, title, block.page)
        }
        return ModelSectionResult.Done(boundaries)
    }

    fun parse(body: JsonObject, sent: List<SourceBlock>): ExplainResult {
        val text = (body["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val citations = (body["sources"] as? JsonArray).orEmpty().map { value ->
            val source = value as? JsonObject ?: return ExplainResult.Refused("这次回答的出处无法核对。")
            val id = (source["docId"] as? JsonPrimitive)?.contentOrNull
            val block = sent.firstOrNull { it.id == id } ?: return ExplainResult.Refused("这次回答的出处无法核对。")
            val page = (source["pdfPage"] as? JsonPrimitive)?.intOrNull
            val anchor = (source["anchor"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (page != block.page || anchor.isBlank()) return ExplainResult.Refused("这次回答的出处无法核对。")
            Citation(anchor, block.id, block.page)
        }
        // A reply without a verified block reference is a refusal message, never an explanation.
        if (citations.isEmpty()) return ExplainResult.Refused("这一句书里没有更多可依据的内容。可以问「这段话在说什么？」或「前后两段有什么联系？」")
        if (text.isBlank()) return ExplainResult.Refused("这次没有得到可核对的讲解。")
        val elapsed = (body["elapsedMs"] as? JsonPrimitive)?.longOrNull ?: 0L
        return ExplainResult.Done(Explanation(text, citations, elapsed))
    }
}

private sealed interface BlockState {
    data object Waiting : BlockState
    data class Done(val explanation: Explanation, val context: List<SourceBlock>) : BlockState
    data class Failed(val message: String, val trouble: ExplainTrouble) : BlockState
}

private sealed interface PendingExplain {
    data class Block(val block: SourceBlock) : PendingExplain
    data class Section(val context: ExplainSectionContext, val sectionId: String) : PendingExplain
}

/**
 * 「讲解」视图：按这本书自己的章节来讲，不再每行挂「讲解这段」，也不再放「讲解这一页」。
 * 目录页、版权页、前言/索引/参考文献和空白页只显示灰字提示，不挂讲解入口；
 * 正文节标题旁保留淡淡的「讲讲这一节」，点了展开整节完整讲解与段号出处。
 */
@Composable
internal fun BookExplainPanel(
    book: Textbook, page: Int, consent: ExplainConsent, explainer: BookExplainer, modifier: Modifier = Modifier,
    selection: SourceBlock? = null, library: TextbookLibrary? = null, onOpenOriginal: ((BookPassage) -> Unit)? = null,
) {
    val blocks = remember(book.id, book.pages, book.structure) { book.sourceBlocks() }
    val pageRole = remember(book.id, book.structure, blocks, page) {
        book.structure?.pageRole(page) ?: when (ExplainClassifier.analyzePage(blocks, page).kind) {
            ExplainPageKind.TOC -> BookPageRole.TOC
            ExplainPageKind.COPYRIGHT -> BookPageRole.COPYRIGHT
            ExplainPageKind.BLANK -> BookPageRole.BLANK
            ExplainPageKind.HEADINGS_ONLY -> BookPageRole.FRONT_MATTER
            ExplainPageKind.BODY -> BookPageRole.BODY
        }
    }
    val sectionIndex = remember(book.id, book.lessons, page) {
        book.lessons.indexOfFirst { page in it.firstPage..it.lastPage }
    }
    val currentSection = book.lessons.getOrNull(sectionIndex)
    val onPage = remember(blocks, page, selection) {
        if (selection != null) listOf(selection) else blocks.filter { it.page == page && it.isBodyParagraph }
    }
    val aiEnabled = LocalReadingAiExplain.current
    val states = remember(book.id) { mutableStateMapOf<String, BlockState>() }
    val sectionStates = remember(book.id) { mutableStateMapOf<String, SectionGuideState>() }
    var sectionExpanded by remember(book.id, currentSection?.id) { mutableStateOf(false) }
    var activeHardPointId by remember(book.id, currentSection?.id) { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<Pair<Citation, SourceBlock>?>(null) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    LaunchedEffect(explainer) { if (!explainer.loaded) explainer.load() }

    fun sectionContext(): ExplainSectionContext? {
        val sec = currentSection ?: return null
        val prev = book.lessons.getOrNull(sectionIndex - 1)
        val prevRecap = prev?.let { p ->
            (sectionStates[p.id] as? SectionGuideState.Ready)?.guide?.recap
                ?: library?.sectionGuide(book.id, p.id)?.recap
        }
        return ExplainSectionContext(
            location = sec.breadcrumb,
            previousRecap = prevRecap,
            nextTitle = book.lessons.getOrNull(sectionIndex + 1)?.title,
            sectionBlocks = sec.blocks,
        )
    }

    fun start(block: SourceBlock) {
        states[block.id] = BlockState.Waiting
        scope.launch {
            val candidates = if (selection == null) blocks else listOf(block)
            val result = explainer.explain(block, candidates)
            states[block.id] = when (result) {
                is ExplainResult.Done -> BlockState.Done(result.explanation, ExplainWire.context(candidates, block))
                is ExplainResult.Refused -> BlockState.Failed(result.message, result.trouble)
            }
        }
    }

    fun startSection(context: ExplainSectionContext, sectionId: String) {
        sectionExpanded = true
        sectionStates[sectionId] = SectionGuideState.Loading
        scope.launch {
            val cached = withContext(Dispatchers.IO) { library?.sectionGuide(book.id, sectionId) }
            if (cached != null) {
                sectionStates[sectionId] = SectionGuideState.Ready(SectionGuide.fromCached(cached), context.sectionBlocks)
                return@launch
            }
            val bookPages = maxOf(book.pages.size, book.pages.maxOfOrNull { it.number } ?: 1)
            when (val result = explainer.explainSectionGuide(context, bookPages)) {
                is SectionGuideResult.Done -> {
                    sectionStates[sectionId] = SectionGuideState.Ready(result.guide, context.sectionBlocks)
                    withContext(Dispatchers.IO) { library?.saveSectionGuide(book.id, sectionId, result.guide.toCached()) }
                }
                is SectionGuideResult.Refused -> {
                    sectionStates[sectionId] = SectionGuideState.Failed(result.message, result.trouble)
                }
            }
        }
    }

    fun clearPairingFailures() {
        states.filterValues { (it as? BlockState.Failed)?.trouble?.needsPairing == true }.keys.forEach(states::remove)
        sectionStates.filterValues { (it as? SectionGuideState.Failed)?.trouble?.needsPairing == true }.keys.forEach(sectionStates::remove)
    }

    val colors = CoachTheme.colors
    val onRelogin = LocalBookRelogin.current
    // 讲解长过一屏时，右侧这道 V7 细滚动条跟着同一个 ScrollState 走：不用滚轮也能看出下面还有内容，
    // 拖动滑块能读到底（desktop-polish A11）。条子宽 12dp，落在正文右侧 16dp 的内边距里，不盖住字。
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).testTag("book-explain"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val first = selection?.page ?: currentSection?.firstPage ?: page
            val last = selection?.page ?: currentSection?.lastPage ?: page
            val chapter = remember(book, first) { BookChapterResolver.chapterForPage(book, first) }
            Text(explanationScope(first, last, chapter?.title ?: book.title), style = MaterialTheme.typography.titleSmall)
            val paired = explainer.identity
            if (!aiEnabled) {
                Text(
                    "已在设置中关闭伴读讲解与提问，开启后可在此查看本节讲解。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgSecondary,
                    modifier = Modifier.testTag("explain-ai-disabled"),
                )
            } else if (paired == null && explainer.loaded) {
                Column(Modifier.testTag("explain-revoked-action"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (explainer.revoked) "授权已撤销，请重新登录。阅读照常。" else "尚未登录 Coach 服务，登录后可使用伴读讲解与提问。",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (explainer.revoked) v7Alert() else colors.fgSecondary,
                        modifier = Modifier.testTag(if (explainer.revoked) "explain-revoked" else "explain-connect-hint"),
                    )
                    if (onRelogin != null) {
                        PrimaryButton(
                            onClick = { onRelogin(if (explainer.revoked) "授权已撤销，请重新登录。" else null) },
                            modifier = Modifier.testTag("explain-relogin"),
                        ) { Text(if (explainer.revoked) "重新登录" else "登录 Coach 服务") }
                    }
                }
            }
            if (selection == null) {
                if (pageRole != BookPageRole.BODY || currentSection == null) {
                    Text(pageRole.notice ?: "这一页没有可讲的正文。", style = MaterialTheme.typography.bodyMedium,
                        color = colors.fgSecondary, modifier = Modifier.testTag("explain-page-notice"))
                } else {
                    val secState = sectionStates[currentSection.id] ?: SectionGuideState.Idle
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(currentSection.breadcrumb, style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary, modifier = Modifier.weight(1f))
                        FaintSectionExplainButton(
                            expanded = sectionExpanded && secState is SectionGuideState.Ready,
                            enabled = aiEnabled && paired != null && secState != SectionGuideState.Loading,
                            onClick = {
                                if (secState is SectionGuideState.Ready) {
                                    sectionExpanded = !sectionExpanded
                                } else {
                                    val ctx = sectionContext() ?: return@FaintSectionExplainButton
                                    startSection(ctx, currentSection.id)
                                }
                            },
                        )
                    }
                    if (sectionExpanded || secState != SectionGuideState.Idle) {
                        SectionGuideCard(
                            book = book,
                            blocks = blocks,
                            section = currentSection,
                            state = secState,
                            paired = paired != null,
                            consented = aiEnabled,
                            expanded = sectionExpanded,
                            onToggleExpand = { sectionExpanded = !sectionExpanded },
                            onAllowConsent = {
                                val ctx = sectionContext() ?: return@SectionGuideCard
                                startSection(ctx, currentSection.id)
                            },
                            onRetry = {
                                val ctx = sectionContext() ?: return@SectionGuideCard
                                startSection(ctx, currentSection.id)
                            },
                            onOpenPair = {
                                onRelogin?.invoke("授权已撤销，请重新登录。")
                            },
                            onOpenCitation = { citation, block ->
                                if (onOpenOriginal != null) onOpenOriginal(BookPassage.from(book, block))
                                else opened = citation to block
                            },
                        )
                    }
                }
            }
            for (block in onPage) {
                if (selection == null) {
                    val readyGuide = currentSection?.let { (sectionStates[it.id] as? SectionGuideState.Ready)?.guide }
                    val hardPoint = readyGuide?.hardPoints?.firstOrNull { it.blockId == block.id }
                    if (hardPoint != null && currentSection != null) {
                        val pillShape = RoundedCornerShape(CoachV7.Radius.s)
                        val pillBg = if (colors.dark) CoachV7.Dark.pineWash else CoachV7.Light.pineWash
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    ReadableText.reflow(block.text),
                                    Modifier.weight(1f).testTag("explain-paragraph-${block.id}"),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.fgPrimary,
                                )
                                Text(
                                    "难点",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = v7Pine(),
                                    modifier = Modifier
                                        .clip(pillShape)
                                        .background(pillBg)
                                        .border(1.dp, v7Pine().copy(alpha = 0.35f), pillShape)
                                        .clickable(role = Role.Button) {
                                            activeHardPointId = if (activeHardPointId == block.id) null else block.id
                                        }
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                        .testTag("explain-hard-point-marker-${block.id}"),
                                )
                            }
                            if (activeHardPointId == block.id) {
                                HardPointExplainCard(
                                    book = book,
                                    allBlocks = blocks,
                                    section = currentSection,
                                    hardPoint = hardPoint,
                                    block = block,
                                    onOpenOriginal = onOpenOriginal?.let { open -> { open(BookPassage.from(book, block)) } },
                                    onClose = { activeHardPointId = null },
                                )
                            }
                        }
                    } else {
                        Text(ReadableText.reflow(block.text), Modifier.fillMaxWidth().testTag("explain-paragraph-${block.id}"),
                            style = MaterialTheme.typography.bodyLarge, color = colors.fgPrimary)
                    }
                    continue
                }
                Column(Modifier.fillMaxWidth().testTag("explain-block-${block.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val state = states[block.id]
                    val failed = state as? BlockState.Failed
                    val interaction = remember(block.id) { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    val canExplain = paired != null && state != BlockState.Waiting
                    Row(Modifier.fillMaxWidth().hoverable(interaction)) {
                        Text(ReadableText.reflow(block.text), Modifier.weight(1f).alignByBaseline().testTag("explain-paragraph-${block.id}"),
                            style = MaterialTheme.typography.bodyLarge, color = colors.fgPrimary)
                        FaintExplainButton(
                            label = if (state == BlockState.Waiting) "讲解中…" else "讲讲",
                            enabled = aiEnabled && canExplain, waiting = state == BlockState.Waiting,
                            emphasised = failed == null && aiEnabled && canExplain && (true || hovered),
                            placeholder = failed != null,
                            modifier = Modifier.alignByBaseline().padding(start = 12.dp)
                                .then(if (failed == null) Modifier.testTag("explain-button") else Modifier),
                            onClick = { if (aiEnabled) start(block) })
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        if (failed != null) {
                            when (failed.trouble) {
                                ExplainTrouble.Unpaired, ExplainTrouble.Revoked -> PaperButton(
                                    onClick = { onRelogin?.invoke("授权已撤销，请重新登录。") },
                                    modifier = Modifier.testTag("explain-repair"),
                                ) {
                                    Text("重新登录")
                                }
                                else -> QuietButton("再试一次", enabled = paired != null, modifier = Modifier.testTag("explain-retry")) { start(block) }
                            }
                            Text(failed.message, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                color = if (failed.trouble.needsReader) v7Alert() else colors.fgSecondary,
                                modifier = Modifier.weight(1f).testTag("explain-failed"))
                        }
                    }
                    if (failed != null) {
                        RetrievedPageLinks(listOf(block), { "第 ${it.page} 页" }) {
                            if (onOpenOriginal != null) onOpenOriginal(BookPassage.from(book, it))
                            else opened = Citation("", it.id, it.page) to it
                        }
                    }
                    if (state is BlockState.Done) ExplanationView(book, blocks, state.explanation, state.context) { citation ->
                        state.context.firstOrNull { it.id == citation.blockId }?.let {
                            if (onOpenOriginal != null) onOpenOriginal(BookPassage.from(book, it))
                            else opened = citation to it
                        }
                    }
                }
            }
        }
        if (scroll.maxValue > 0) BookScrollbar(scroll,
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp).testTag("explain-scrollbar"))
    }
    opened?.let { (citation, block) ->
        AlertDialog(onDismissRequest = { opened = null }, title = { Text("原文 · ${bookCitationLabel(book, blocks, citation)}", modifier = Modifier.testTag("explain-source-title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("explain-source-card")) {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        Text(block.text, style = MaterialTheme.typography.bodyLarge, color = colors.fgPrimary, modifier = Modifier.testTag("explain-source-text"))
                    }
                }
            }, confirmButton = { V7TextButton({ opened = null }, modifier = Modifier.testTag("explain-source-close")) { Text("关闭") } })
    }
}

@Composable
internal fun ExplanationView(book: Textbook, blocks: List<SourceBlock>, explanation: Explanation, context: List<SourceBlock>,
    showReaderPage: Boolean = false,
    onOpen: (Citation) -> Unit) {
    val citations = explanation.citations
    // 正文里的【S1】换成主色小号上标数字，点它和点下面「出处 1」一样打开原文；读者看不到任何块编号。
    val shown = ReadableText.cited(explanation.text, citations.map { it.anchor }, v7LinkStyles()) { index -> onOpen(citations[index]) }
    // V7：讲解是一张纸卡；出处是主色下划线的小字，像页边注：第几条 · 第几页 · 第几段 · 开头几个字。
    com.coach.ui.v7.PaperCard(Modifier.testTag("explain-answer"), contentPadding = PaddingValues(16.dp)) {
        Text(shown, style = MaterialTheme.typography.bodyMedium, color = CoachTheme.colors.fgPrimary, modifier = Modifier.testTag("explain-text"))
        Column {
            citations.forEachIndexed { index, c ->
                val quote = context.firstOrNull { it.id == c.blockId }?.let { " · " + shortQuote(it.text) }.orEmpty()
                val readerPage = if (showReaderPage && !book.printsPageNumbers(c.page)) "阅读器第 ${c.page} 页 · " else ""
                V7TextButton({ onOpen(c) }, modifier = Modifier.testTag("explain-citation")) {
                    Text("出处 ${index + 1} · $readerPage${bookCitationLabel(book, blocks, c)}$quote", style = MaterialTheme.typography.labelLarge, color = v7Pine(),
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
                }
            }
        }
    }
}

/**
 * 出处的位置，让读者能按段找回原文：「第 12 页 · 第 3 段」。
 * 段号数的是这一页的正文段（跳过章标题和原件页码标记），和选段就问里行首的段号是同一个数。
 * 书里没有这一页的页码时（文本导入按章节切出来的块），改成按本章正文的先后数：「本章第 3 段」，
 * 页码不编，读者照样能在原文里数到它。
 */
internal fun bookCitationLabel(book: Textbook, blocks: List<SourceBlock>, citation: Citation): String {
    val at = blocks.indexOfFirst { it.id == citation.blockId }
    val printed = citation.page > 0 && book.printsPageNumbers(citation.page)
    // 找不到正文段时只说得到的位置，不编段号，更不把导入时编的块号当页码。
    if (at < 0 || !blocks[at].isBodyParagraph) return if (printed) book.pageLabel(citation.page) else "本章"
    if (printed) {
        val inPage = blocks.subList(0, at + 1).count { it.page == citation.page && it.isBodyParagraph }
        return "${book.pageLabel(citation.page)} · 第 $inPage 段"
    }
    val heading = blocks.subList(0, at).indexOfLast { it.heading }
    return "本章第 ${blocks.subList(heading + 1, at + 1).count { it.isBodyParagraph }} 段"
}

/**
 * 选段就问回答下方的「出处」和原文弹窗：手上只有一段原文（[BookPassage]），没有引用对象，
 * 所以先按页号和字符位置在整本书的块里找回这一段，再按讲解卡同一套写法写（[bookCitationLabel]）。
 * 有页码就写「第 5 页 · 第 2 段」，没有页码就写「本章第 2 段」——不拿导入时编的块号冒充页码。
 */
internal fun bookPassageLabel(book: Textbook, blocks: List<SourceBlock>, passage: BookPassage): String {
    val at = blocks.indexOfFirst {
        it.page == passage.page && it.start == passage.start && it.end == passage.end
    }
    // 找不回这一段时只说得到的位置（页或「本章」），段号不编。
    return bookCitationLabel(book, blocks, Citation("", blocks.getOrNull(at)?.id.orEmpty(), passage.page))
}

/**
 * 这一页的号是不是原件上印着的页码。PDF、幻灯片、扫描页本身就按页装；
 * 文本类导入（txt/md/epub/docx…）是按章节切块再编 1、2、3 的，只有页里带 [PRINTED_PAGE_MARK] 的
 * 才是从原件带过来的页码——该页的页号就是标记里的书页号。内部块编号照旧用来定位原文，
 * 只是不拿去当页码给读者看。
 */
private fun Textbook.printsPageNumbers(number: Int): Boolean {
    if (format in setOf("pdf", "pptx", "png", "jpg", "jpeg")) return true
    return page(number)?.text?.let { PRINTED_PAGE_MARK.containsMatchIn(it) } == true
}

/** V7 提醒色：朱 zhu，随明暗取 token。讲解面板和选段就问里「没配上、超字数、暂时读不到」都用它，不用 Material 红。 */
/** 要读者自己动手才过得去的（连接被停用，只能重新配对）；其余都是等一下或再试就好的。 */
private val ExplainTrouble.needsReader: Boolean get() = this == ExplainTrouble.Revoked

/**
 * 「讲解这段」的常态：段落首行右边一行 faint 小字，和正文同一基线，不占段落下面的一行。
 * [emphasised]（鼠标扫到这一段，或这一段就是从阅读器选进来的）时才浮出纸面底和正常字色，
 * 盒子大小不变，所以段落和下面的讲解都不会因鼠标经过而挪位。
 *
 * 宽度是固定的：这一行和正文抢宽度，字号一变（「讲解中…」）或这一行暂时让给失败提示时，
 * 只有宽度不动，正文才不会重新折行、把下面的段落顶下去。失败时用 [placeholder] 留一个看不见的同款盒子。
 */
@Composable
private fun FaintExplainButton(label: String, enabled: Boolean, waiting: Boolean, emphasised: Boolean,
    placeholder: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = CoachTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(CoachV7.Radius.btn)
    val base = if (c.dark) CoachV7.Dark.btnBg else CoachV7.Light.btnBg
    Text(label, modifier
        .then(if (emphasised) Modifier.paperShadow("button", c.dark).clip(shape).background(base) else Modifier)
        .then(if (placeholder) Modifier else Modifier.v7FocusRing(shape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default))
        .widthIn(min = 84.dp).padding(horizontal = 12.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelLarge, maxLines = 1, textAlign = TextAlign.Center,
        color = when {
            placeholder -> Color.Transparent
            waiting -> c.fgSecondary
            !enabled -> c.fgTertiary.copy(alpha = .45f)
            emphasised -> c.fgPrimary
            else -> c.fgTertiary
        })
}

/**
 * 「再试一次」：讲解失败后和「重新配对」并排的纸面按钮，但悬停只加一层中性灰，不用朱色的底——
 * 失败后鼠标还停在原处，粉底会把一件可恢复的事看成报错。
 */
@Composable
private fun QuietButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = CoachTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(CoachV7.Radius.btn)
    val base = if (c.dark) CoachV7.Dark.btnBg else CoachV7.Light.btnBg
    Row(modifier.v7FocusRing(shape).paperShadow("button", c.dark).clip(shape)
        .background(if (enabled && hovered) c.borderSubtle.copy(alpha = .16f).compositeOver(base) else base)
        .hoverable(interaction, enabled)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .defaultMinSize(minHeight = if (LocalDeskMetrics.current.inner) 36.dp else 44.dp).padding(horizontal = 16.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.fgPrimary.copy(alpha = if (enabled) 1f else .4f))
    }
}

@Composable
internal fun v7Alert(): Color = if (CoachTheme.colors.dark) CoachV7.Dark.zhu else CoachV7.Light.zhu

/**
 * V7 输入框：字段名写在框上方，框是一张无阴影的纸（默认 surface1，放在 surface2 的弹窗里也看得出边），
 * 细描边 borderSubtle，出错时描边换朱色；焦点环和按钮同一套。字号走 typography，不写死。
 */
@Composable
internal fun V7Field(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, isError: Boolean = false, singleLine: Boolean = true, minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    paper: Color = CoachTheme.colors.surface1) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.btn)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.fgPrimary.copy(alpha = if (enabled) 1f else .5f)),
            cursorBrush = SolidColor(colors.fgPrimary),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            modifier = modifier
                .v7FocusRing(shape)
                .clip(shape)
                .background(paper)
                .border(1.dp, if (isError) v7Alert() else colors.borderSubtle, shape),
            decorationBox = { innerTextField ->
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    innerTextField()
                }
            },
        )
    }
}
