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

/** Sending a book's text off this computer needs a yes from the reader, per book. Nothing is sent before it. */
internal class ExplainConsent(private val directory: Path) {
    private fun file(bookId: String): Path? =
        bookId.takeIf { Regex("[A-Za-z0-9._-]{1,80}").matches(it) }?.let { directory.resolve("explain-consent").resolve(it) }
    fun allowed(bookId: String): Boolean = file(bookId)?.let(Files::isRegularFile) == true
    fun allow(bookId: String) {
        val target = file(bookId) ?: return
        Files.createDirectories(target.parent)
        Files.write(target, "allowed".toByteArray(Charsets.UTF_8))
    }
}

/** The request and reply contract with the paired server; pure so it is testable without a network. */
internal object ExplainWire {
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
        if (text.isBlank() || citations.isEmpty()) return ExplainResult.Refused(text.ifBlank { "这次没有得到可核对的讲解。" })
        val elapsed = (body["elapsedMs"] as? JsonPrimitive)?.longOrNull ?: 0L
        return ExplainResult.Done(Explanation(text, citations, elapsed))
    }
}

private sealed interface BlockState {
    data object Waiting : BlockState
    data class Done(val explanation: Explanation, val context: List<SourceBlock>) : BlockState
    data class Failed(val message: String, val trouble: ExplainTrouble) : BlockState
}

/**
 * 「讲解」视图：本页每一段原文旁一个「讲解」按钮。点了才把这一段和前后各一段发给已配对的 Mac；
 * 讲解里的块编号点开就是这一块原文。
 */
@Composable
internal fun BookExplainPanel(
    book: Textbook, page: Int, consent: ExplainConsent, explainer: BookExplainer, modifier: Modifier = Modifier,
    selection: SourceBlock? = null, onOpenOriginal: ((BookPassage) -> Unit)? = null,
) {
    val blocks = remember(book.id) { StudyCoachCourse.blocks(book.id, book.pages) }
    val onPage = remember(blocks, page, selection) {
        if (selection != null) listOf(selection) else blocks.filter { it.page == page && !it.heading }
    }
    var consented by remember(book.id) { mutableStateOf(consent.allowed(book.id)) }
    val states = remember(book.id) { mutableStateMapOf<String, BlockState>() }
    var pending by remember(book.id, page) { mutableStateOf<SourceBlock?>(null) }
    var opened by remember { mutableStateOf<Pair<Citation, SourceBlock>?>(null) }
    var address by remember(explainer.defaultAddress) { mutableStateOf(explainer.defaultAddress) }
    var code by remember { mutableStateOf("") }
    var pairError by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    LaunchedEffect(explainer) { if (!explainer.loaded) explainer.load() }

    fun start(block: SourceBlock) {
        states[block.id] = BlockState.Waiting
        scope.launch {
            // The selected words can also occur in a neighbouring block. Send the
            // chosen range alone so the server cannot attach their reply to that neighbour.
            val candidates = if (selection == null) blocks else listOf(block)
            val result = explainer.explain(block, candidates)
            states[block.id] = when (result) {
                is ExplainResult.Done -> BlockState.Done(result.explanation, ExplainWire.context(candidates, block))
                is ExplainResult.Refused -> BlockState.Failed(result.message, result.trouble)
            }
        }
    }

    val colors = CoachTheme.colors
    // 讲解长过一屏时，右侧这道 V7 细滚动条跟着同一个 ScrollState 走：不用滚轮也能看出下面还有内容，
    // 拖动滑块能读到底（desktop-polish A11）。条子宽 12dp，落在正文右侧 16dp 的内边距里，不盖住字。
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).testTag("book-explain"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val paired = explainer.identity
            if (paired == null && explainer.loaded) Column(Modifier.testTag("explain-pairing"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val sable = explainer.sableLogin
                if (explainer.revoked) {
                    Text("这台电脑的连接已停用", style = MaterialTheme.typography.titleMedium)
                    Text(if (sable) "授权已撤销，请重新登录。读书和做题照常。" else "请 Mac 那边生成新的配对码，填在下面重新配对。读书和做题照常。",
                        style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary, modifier = Modifier.testTag("explain-revoked"))
                } else {
                    Text("先连上 Mac", style = MaterialTheme.typography.titleMedium)
                    Text(if (sable) "登录后可以提问，也可以让朋友服务讲解选中的原文。" else "填上 Mac 地址和配对码，连上后可以提问，也可以讲解选中的原文。",
                        style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary, modifier = Modifier.testTag("explain-connect-hint"))
                }
                if (sable) {
                    com.coach.ui.SableLoginButton(pairing, { pairing = it }) { open ->
                        explainer.sable(open)
                        states.filterValues { (it as? BlockState.Failed)?.trouble == ExplainTrouble.Revoked }.keys.forEach(states::remove)
                    }
                    Text("没有 Sable 账号？用配对码", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                }
                V7Field(address, { address = it; pairError = null }, "Mac 地址", enabled = !pairing, paper = colors.surface2, modifier = Modifier.fillMaxWidth().testTag("explain-address"))
                V7Field(code, { code = it.filter(Char::isDigit).take(6); pairError = null }, "配对码", enabled = !pairing, paper = colors.surface2,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.width(200.dp).testTag("explain-code"))
                PrimaryButton(onClick = {
                    pairing = true
                    scope.launch {
                        if (!explainer.pair(address, code)) pairError = "没配上。核对 Mac 地址，或请 Mac 那边生成新的配对码。"
                        // 配好了：之前因停用而停下的段落回到「讲解这段」。
                        else states.filterValues { (it as? BlockState.Failed)?.trouble == ExplainTrouble.Revoked }.keys.forEach(states::remove)
                        code = ""; pairing = false
                    }
                }, enabled = code.length == 6 && address.isNotBlank() && !pairing, modifier = Modifier.testTag("explain-pair")) { Text(if (pairing) "正在配对…" else "配对") }
                pairError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = v7Alert(), modifier = Modifier.testTag("explain-pair-error")) }
            } else if (paired != null) Text("已连上 Mac。点一段原文旁的「讲解这段」。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
            if (onPage.isEmpty()) Text("这一页没有可讲的正文。", style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
            for (block in onPage) Column(Modifier.fillMaxWidth().testTag("explain-block-${block.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val state = states[block.id]
                val failed = state as? BlockState.Failed
                val selected = selection?.id == block.id
                val interaction = remember(block.id) { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val canExplain = paired != null && state != BlockState.Waiting
                // 「讲解这段」平时只是段落首行右边一行 faint 小字：鼠标扫到这一段、或这一段就是从阅读器选进来的，
                // 才浮成纸面按钮。它和正文第一行同一基线，段落多长都不会掉到段落下面（desktop-polish A10）。
                Row(Modifier.fillMaxWidth().hoverable(interaction)) {
                    Text(ReadableText.reflow(block.text), Modifier.weight(1f).alignByBaseline().testTag("explain-paragraph-${block.id}"),
                        style = MaterialTheme.typography.bodyLarge, color = colors.fgPrimary)
                    // 失败时这一行留一个看不见的同款盒子：正文的折行不能因为入口消失而变，
                    // 否则段落会缩短一行，把下面的段落整体顶上去。
                    FaintExplainButton(
                        label = if (state == BlockState.Waiting) "讲解中…" else "讲解这段",
                        enabled = canExplain, waiting = state == BlockState.Waiting,
                        emphasised = failed == null && canExplain && (selected || hovered),
                        placeholder = failed != null,
                        modifier = Modifier.alignByBaseline().padding(start = 12.dp)
                            .then(if (failed == null) Modifier.testTag("explain-button") else Modifier),
                        onClick = { if (consented) start(block) else pending = block })
                }
                // 失败时按钮就地换成下一步（再试一次 / 重新配对），右边写那句话。这一行任何时候都占着位置：
                // 平时是留白，失败时才填内容，所以点下去、等回复、失败回来这三种状态里，下面正在读的段落
                // 一个像素都不动（desktop-polish A10、D3、E3）。块编号是内部的，不给读者看。
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (failed != null) {
                        when (failed.trouble) {
                            ExplainTrouble.Revoked -> PaperButton(onClick = { scope.launch { scroll.animateScrollTo(0) } },
                                modifier = Modifier.testTag("explain-repair")) { Text("重新配对") }
                            else -> QuietButton("再试一次", enabled = paired != null, modifier = Modifier.testTag("explain-retry")) { start(block) }
                        }
                        // 连不上、超时、暂时读不到都是会自己好的：中性灰字。只有要读者动手的（连接被停用、要重新配对）才用朱色。
                        Text(failed.message, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            color = if (failed.trouble.needsReader) v7Alert() else colors.fgSecondary,
                            modifier = Modifier.weight(1f).testTag("explain-failed"))
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
        if (scroll.maxValue > 0) BookScrollbar(scroll,
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp).testTag("explain-scrollbar"))
    }
    pending?.let { block ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(ExplainPrivacy.TITLE) },
            text = { Text(ExplainPrivacy.explain(book.title),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("explain-consent")) },
            confirmButton = {
                PrimaryButton({ consent.allow(book.id); consented = true; pending = null; start(block) }, modifier = Modifier.testTag("explain-consent-allow")) { Text("允许并讲解") }
            }, dismissButton = { V7TextButton({ pending = null }, modifier = Modifier.testTag("explain-consent-deny")) { Text("不允许") } })
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
private fun ExplanationView(book: Textbook, blocks: List<SourceBlock>, explanation: Explanation, context: List<SourceBlock>,
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
                V7TextButton({ onOpen(c) }, modifier = Modifier.testTag("explain-citation")) {
                    Text("出处 ${index + 1} · ${bookCitationLabel(book, blocks, c)}$quote", style = MaterialTheme.typography.labelLarge, color = v7Pine(),
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
        BasicTextField(value, onValueChange, enabled = enabled, singleLine = singleLine, minLines = minLines,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.fgPrimary.copy(alpha = if (enabled) 1f else .5f)),
            cursorBrush = SolidColor(colors.fgPrimary), visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
            modifier = modifier.v7FocusRing(shape).clip(shape).background(paper)
                .border(1.dp, if (isError) v7Alert() else colors.borderSubtle, shape).padding(horizontal = 12.dp, vertical = 10.dp))
    }
}
