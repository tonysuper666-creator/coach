package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PaperButton
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
    data class Refused(val message: String, val unauthorized: Boolean = false) : ExplainResult
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

    /** `0123abcd…:p12:340` — short enough to read, the dialog shows the full id. */
    fun shortId(id: String): String = id.substringBefore(':').let { if (it.length > 8) it.take(8) + "…" else it } + ":" + id.substringAfter(':')
}

/**
 * Pairing with the Mac server and asking it to explain a block of a book that lives on this computer.
 * Open only so a screenshot test can stand in for the Mac without a network; the app uses this class as is.
 */
internal open class BookExplainer(private val settings: DesktopConnectionSettings = DesktopConnectionSettings()) {
    private val pairing = DesktopPairing(settings)
    var identity by mutableStateOf<DesktopIdentity?>(null)
        protected set
    var loaded by mutableStateOf(false)
        protected set
    var defaultAddress by mutableStateOf("")
        protected set

    open suspend fun load() {
        val result = withContext(Dispatchers.IO) { runCatching { settings.serverUrl() to pairing.load() } }
        result.getOrNull()?.let { defaultAddress = it.first.orEmpty(); identity = it.second }
        loaded = true
    }

    open suspend fun pair(address: String, code: String): Boolean = try {
        identity = withContext(Dispatchers.IO) { pairing.pair(address, code, "Windows 电脑") }
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    open suspend fun explain(chosen: SourceBlock, blocks: List<SourceBlock>, requestId: String = UUID.randomUUID().toString().replace("-", "")): ExplainResult {
        val context = ExplainWire.context(blocks, chosen)
        return send(ExplainWire.request(requestId, chosen, context), context, requestId)
    }

    suspend fun ask(chosen: SourceBlock, question: String, requestId: String = UUID.randomUUID().toString().replace("-", "")): ExplainResult =
        send(ExplainWire.question(requestId, chosen, question), listOf(chosen), requestId)

    private suspend fun send(input: JsonObject, context: List<SourceBlock>, requestId: String): ExplainResult {
        val paired = identity ?: return ExplainResult.Refused("还没连上你的 Mac。先在讲解页配对。")
        val http = CourseHttp(paired.serverUrl, paired.credentials.deviceId, paired.credentials.token, "library")
        return try {
            val reply = http.explainLibrary(input)
            val body = reply.body
            when {
                reply.statusCode == 401 -> { identity = null; ExplainResult.Refused(reply.error.orEmpty(), unauthorized = true) }
                body == null -> ExplainResult.Refused(reply.error ?: "暂时没连上 Mac，可继续阅读或稍后重试。")
                else -> ExplainWire.parse(body, context)
            }
        } catch (cancelled: CancellationException) {
            runCatching { http.cancelLibraryExplain(requestId) }
            throw cancelled
        } finally { http.close() }
    }
}

private sealed interface BlockState {
    data object Waiting : BlockState
    data class Done(val explanation: Explanation, val context: List<SourceBlock>) : BlockState
    data class Failed(val message: String) : BlockState
}

/**
 * 「讲解」视图：本页每一段原文旁一个「讲解」按钮。点了才把这一段和前后各一段发给已配对的 Mac；
 * 讲解里的块编号点开就是这一块原文。
 */
@Composable
internal fun BookExplainPanel(book: Textbook, page: Int, consent: ExplainConsent, explainer: BookExplainer, modifier: Modifier = Modifier) {
    val blocks = remember(book.id) { StudyCoachCourse.blocks(book.id, book.pages) }
    val onPage = remember(blocks, page) { blocks.filter { it.page == page && !it.heading } }
    var consented by remember(book.id) { mutableStateOf(consent.allowed(book.id)) }
    val states = remember(book.id) { mutableStateMapOf<String, BlockState>() }
    var pending by remember(book.id, page) { mutableStateOf<SourceBlock?>(null) }
    var opened by remember { mutableStateOf<Pair<Citation, SourceBlock>?>(null) }
    var address by remember(explainer.defaultAddress) { mutableStateOf(explainer.defaultAddress) }
    var code by remember { mutableStateOf("") }
    var pairError by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(explainer) { if (!explainer.loaded) explainer.load() }

    fun start(block: SourceBlock) {
        states[block.id] = BlockState.Waiting
        scope.launch {
            val result = explainer.explain(block, blocks)
            states[block.id] = when (result) {
                is ExplainResult.Done -> BlockState.Done(result.explanation, ExplainWire.context(blocks, block))
                is ExplainResult.Refused -> BlockState.Failed(result.message)
            }
        }
    }

    val colors = CoachTheme.colors
    val numbers = LocalV7Fonts.current.numbers
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("book-explain"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val paired = explainer.identity
        if (paired == null && explainer.loaded) Column(Modifier.testTag("explain-pairing"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("先连上你的 Mac", style = MaterialTheme.typography.titleMedium)
            Text("讲解由你配对的 Mac 生成。打开 Tailscale，输入 Mac 地址和 Mac 上生成的 6 位配对码。", style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary)
            V7Field(address, { address = it; pairError = null }, "Mac 地址", enabled = !pairing, paper = colors.surface2, modifier = Modifier.fillMaxWidth().testTag("explain-address"))
            V7Field(code, { code = it.filter(Char::isDigit).take(6); pairError = null }, "配对码", enabled = !pairing, paper = colors.surface2,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.width(200.dp).testTag("explain-code"))
            PrimaryButton(onClick = {
                pairing = true
                scope.launch {
                    if (!explainer.pair(address, code)) pairError = "没配上。确认 Mac 地址、Tailscale，并在 Mac 上生成新的配对码。"
                    code = ""; pairing = false
                }
            }, enabled = code.length == 6 && address.isNotBlank() && !pairing, modifier = Modifier.testTag("explain-pair")) { Text(if (pairing) "正在配对…" else "配对") }
            pairError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = v7Alert(), modifier = Modifier.testTag("explain-pair-error")) }
        } else if (paired != null) Text("已连上你的 Mac。点一段原文旁的「讲解」。", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
        if (onPage.isEmpty()) Text("这一页没有可讲的正文。", style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
        for (block in onPage) Column(Modifier.fillMaxWidth().testTag("explain-block-${block.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(block.text, style = MaterialTheme.typography.bodyLarge, color = colors.fgPrimary)
            val state = states[block.id]
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                PaperButton(onClick = {
                    if (consented) start(block) else pending = block
                }, enabled = paired != null && state != BlockState.Waiting, modifier = Modifier.testTag("explain-button")) {
                    Text(if (state == BlockState.Waiting) "讲解中…" else "讲解这段")
                }
                Text(ExplainWire.shortId(block.id), style = MaterialTheme.typography.labelMedium, fontFamily = numbers, color = colors.fgSecondary)
            }
            when (state) {
                is BlockState.Done -> ExplanationView(state.explanation) { citation ->
                    state.context.firstOrNull { it.id == citation.blockId }?.let { opened = citation to it }
                }
                is BlockState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = v7Alert(), modifier = Modifier.testTag("explain-failed"))
                else -> {}
            }
        }
    }
    pending?.let { block ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("把这段发到你的 Mac？") },
            text = { Text("讲解会把你选的这一段和前后各一段原文发给你配对的 Mac，由它的 AI 生成。这本书之后不再询问。《${book.title}》允许吗？",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("explain-consent")) },
            confirmButton = {
                PrimaryButton({ consent.allow(book.id); consented = true; pending = null; start(block) }, modifier = Modifier.testTag("explain-consent-allow")) { Text("允许并讲解") }
            }, dismissButton = { V7TextButton({ pending = null }, modifier = Modifier.testTag("explain-consent-deny")) { Text("不允许") } })
    }
    opened?.let { (citation, block) ->
        AlertDialog(onDismissRequest = { opened = null }, title = { Text("原文 · ${book.pageLabel(citation.page)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("explain-source-card")) {
                    Text(citation.blockId, style = MaterialTheme.typography.labelMedium, fontFamily = numbers, color = colors.fgSecondary,
                        modifier = Modifier.testTag("explain-source-id"))
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        Text(block.text, style = MaterialTheme.typography.bodyLarge, color = colors.fgPrimary, modifier = Modifier.testTag("explain-source-text"))
                    }
                }
            }, confirmButton = { V7TextButton({ opened = null }, modifier = Modifier.testTag("explain-source-close")) { Text("关闭") } })
    }
}

@Composable
private fun ExplanationView(explanation: Explanation, onOpen: (Citation) -> Unit) {
    val shown = explanation.citations.fold(explanation.text) { text, c -> text.replace("【${c.anchor}】", "【${ExplainWire.shortId(c.blockId)}】") }
    // V7：讲解是一张纸卡；块编号是主色下划线的「出处」小字，像页边注，不再是一排描边按钮。
    com.coach.ui.v7.PaperCard(Modifier.testTag("explain-answer"), contentPadding = PaddingValues(16.dp)) {
        Text(shown, style = MaterialTheme.typography.bodyMedium, color = CoachTheme.colors.fgPrimary, modifier = Modifier.testTag("explain-text"))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            for (c in explanation.citations) V7TextButton({ onOpen(c) }, modifier = Modifier.testTag("explain-citation")) {
                Text("出处 · " + ExplainWire.shortId(c.blockId), style = MaterialTheme.typography.labelLarge, color = v7Pine(),
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
            }
        }
    }
}

/** V7 提醒色：朱 zhu，随明暗取 token。讲解面板和选段就问里「没配上、超字数、暂时读不到」都用它，不用 Material 红。 */
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
