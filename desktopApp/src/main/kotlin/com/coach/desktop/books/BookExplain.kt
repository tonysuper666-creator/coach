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
import com.coach.client.CourseHttp
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.desktop.DesktopPairing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

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
        Files.writeString(target, "allowed")
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

/**
 * Pairing with the Mac server and asking it to explain a block of a book that lives on this computer.
 * Open only so a screenshot test can stand in for the Mac without a network; the app uses this class as is.
 */
internal open class BookExplainer(private val settings: DesktopConnectionSettings = DesktopConnectionSettings(),
    library: TextbookLibrary? = null) {
    private val imports = library?.let(::ImportedBookWire)
    private val pairing = DesktopPairing(settings)
    var identity by mutableStateOf<DesktopIdentity?>(null)
        protected set
    var loaded by mutableStateOf(false)
        protected set
    var defaultAddress by mutableStateOf("")
        protected set
    /** 服务端说这台电脑的连接已停用（401）；重新配对成功前，配对表单上先说清这一句。 */
    var revoked by mutableStateOf(false)
        protected set

    /** 别处（选段提问、答后讲解）先碰到了 401：讲解面板也回到配对，不再显示「已连上」。 */
    fun markRevoked() {
        identity?.let { stopped += it.key() }
        identity = null; revoked = true
    }

    open suspend fun load() {
        val result = withContext(Dispatchers.IO) { runCatching { settings.serverUrl() to pairing.load() } }
        result.getOrNull()?.let { (address, saved) ->
            defaultAddress = address.orEmpty()
            // 配对文件 401 之后仍在磁盘上；同一份凭据在这个进程里停用过，就别再当成「已连上」。
            val stale = saved != null && saved.key() in stopped
            identity = if (stale) null else saved
            if (stale) revoked = true else if (saved != null) revoked = false
        }
        loaded = true
    }

    open suspend fun pair(address: String, code: String): Boolean = try {
        identity = withContext(Dispatchers.IO) { pairing.pair(address, code, "Windows 电脑") }
        revoked = false
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    private companion object {
        /** 原文页、答后讲解、选段提问各有一个 explainer；谁先碰到 401，其他的读到同一份凭据时也算停用。 */
        val stopped: MutableSet<Triple<String, String, String>> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        fun DesktopIdentity.key() = Triple(serverUrl, credentials.deviceId, credentials.token)
    }

    open suspend fun explain(chosen: SourceBlock, blocks: List<SourceBlock>, requestId: String = UUID.randomUUID().toString().replace("-", "")): ExplainResult {
        val context = ExplainWire.context(blocks, chosen)
        return send(ExplainWire.request(requestId, chosen, context), context, requestId)
    }

    open suspend fun explainLesson(blocks: List<SourceBlock>, bookPages: Int,
        requestId: String = UUID.randomUUID().toString().replace("-", "")): ExplainResult {
        if (!BookLessonWire.fits(blocks)) return ExplainResult.Refused(BookLessonWire.TOO_LONG)
        return send(BookLessonWire.request(requestId, blocks, bookPages), blocks, requestId)
    }

    suspend fun ask(chosen: SourceBlock, question: String, requestId: String = UUID.randomUUID().toString().replace("-", "")): ExplainResult =
        send(ExplainWire.question(requestId, chosen, question), listOf(chosen), requestId)

    private suspend fun send(input: JsonObject, context: List<SourceBlock>, requestId: String): ExplainResult {
        val paired = identity ?: return ExplainResult.Refused("还没连上你的 Mac。先在讲解页配对。")
        val http = CourseHttp(paired.serverUrl, paired.credentials.deviceId, paired.credentials.token, "library")
        return try {
            val snapshot = withContext(Dispatchers.IO) { imports?.snapshot(context) }
            val lesson = input["kind"]?.jsonPrimitive?.contentOrNull == "lesson"
            val wire = if (lesson) {
                if (snapshot == null) return ExplainResult.Refused("请先打开个人导入的书并允许讲解。")
                try { BookLessonWire.withSource(input, snapshot) }
                catch (_: IllegalArgumentException) { return ExplainResult.Refused(BookLessonWire.TOO_LONG) }
            } else if (snapshot == null) input else JsonObject(input + ("localSource" to snapshot))
            val reply = http.explainLibrary(wire)
            if (snapshot != null && withContext(Dispatchers.IO) { imports!!.snapshot(context) } != snapshot)
                return ExplainResult.Refused("保存的原文已变化，请重新打开这本书后重试。")
            val body = reply.body
            val trouble = ExplainTrouble.of(reply.statusCode, reply.code)
            when {
                trouble == ExplainTrouble.Revoked -> { markRevoked(); ExplainResult.Refused(ExplainTrouble.REVOKED, trouble) }
                body == null -> ExplainResult.Refused(ExplainTrouble.message(trouble, reply.error), trouble)
                else -> ExplainWire.parse(body, context)
            }
        } catch (cancelled: CancellationException) {
            runCatching { http.cancelLibraryExplain(requestId) }
            throw cancelled
        } catch (_: Exception) {
            ExplainResult.Refused("保存的原文或发送许可无法核对，请重新打开这本书后重试。")
        } finally { http.close() }
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
    Column(modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).testTag("book-explain"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val paired = explainer.identity
        if (paired == null && explainer.loaded) Column(Modifier.testTag("explain-pairing"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (explainer.revoked) {
                Text("这台电脑的连接已停用", style = MaterialTheme.typography.titleMedium)
                Text("请找给你配对码的人重新配对：输入 Mac 地址和新的 6 位配对码。读书和做题照常。", style = MaterialTheme.typography.bodySmall,
                    color = colors.fgSecondary, modifier = Modifier.testTag("explain-revoked"))
            } else {
                Text("先连上 Mac", style = MaterialTheme.typography.titleMedium)
                Text("讲解由配对的 Mac 生成。输入 Mac 地址和 Mac 那边生成的 6 位配对码。", style = MaterialTheme.typography.bodySmall,
                    color = colors.fgSecondary)
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
                        ExplainTrouble.Quota -> PaperButton(onClick = {}, enabled = false, modifier = Modifier.testTag("explain-button")) { Text("讲解这段") }
                        else -> QuietButton("再试一次", enabled = paired != null, modifier = Modifier.testTag("explain-retry")) { start(block) }
                    }
                    // 连不上、超时、暂时读不到都是会自己好的：中性灰字。只有要读者动手的（连接被停用、要重新配对）才用朱色。
                    Text(failed.message, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        color = if (failed.trouble.needsReader) v7Alert() else colors.fgSecondary,
                        modifier = Modifier.weight(1f).testTag("explain-failed"))
                }
            }
            if (state is BlockState.Done) ExplanationView(book, state.explanation, state.context) { citation ->
                state.context.firstOrNull { it.id == citation.blockId }?.let {
                    if (onOpenOriginal != null) onOpenOriginal(BookPassage.from(book, it))
                    else opened = citation to it
                }
            }
        }
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
        AlertDialog(onDismissRequest = { opened = null }, title = { Text("原文 · ${book.pageLabel(citation.page)}") },
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
private fun ExplanationView(book: Textbook, explanation: Explanation, context: List<SourceBlock>, onOpen: (Citation) -> Unit) {
    val citations = explanation.citations
    // 正文里的【S1】换成主色小号上标数字，点它和点下面「出处 1」一样打开原文；读者看不到任何块编号。
    val shown = ReadableText.cited(explanation.text, citations.map { it.anchor }, v7LinkStyles()) { index -> onOpen(citations[index]) }
    // V7：讲解是一张纸卡；出处是主色下划线的小字，像页边注：第几条 · 第几页 · 开头几个字。
    com.coach.ui.v7.PaperCard(Modifier.testTag("explain-answer"), contentPadding = PaddingValues(16.dp)) {
        Text(shown, style = MaterialTheme.typography.bodyMedium, color = CoachTheme.colors.fgPrimary, modifier = Modifier.testTag("explain-text"))
        Column {
            citations.forEachIndexed { index, c ->
                val quote = context.firstOrNull { it.id == c.blockId }?.let { " · " + shortQuote(it.text) }.orEmpty()
                V7TextButton({ onOpen(c) }, modifier = Modifier.testTag("explain-citation")) {
                    Text("出处 ${index + 1} · ${book.pageLabel(c.page)}$quote", style = MaterialTheme.typography.labelLarge, color = v7Pine(),
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
                }
            }
        }
    }
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
