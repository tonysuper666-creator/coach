package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.input.EnterGate
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import com.coach.ui.v7.v7FocusRing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/** Explicit paragraph selection keeps the original page offsets even for Unicode text. */
@Composable
internal fun BookPassageAsk(
    book: Textbook, page: BookPage, support: BookLearningSupport, onRepair: (() -> Unit)? = null,
    initialSelection: SourceBlock? = null, onOpenOriginal: ((BookPassage) -> Unit)? = null,
    onClose: () -> Unit,
) {
    // 行号数的是正文段：跳过章标题，也跳过导入时插在页首的原件页码标记，
    // 和讲解出处里的段号用同一个判断（isBodyParagraph），同一段在两处读到的是同一个数。
    val blocks = remember(book.id, page.number) {
        StudyCoachCourse.blocks(book.id, listOf(page)).filter { it.isBodyParagraph }
    }
    // 回答里的「出处」和原文弹窗要和讲解卡写同一种位置：「第 5 页 · 第 2 段」或「本章第 2 段」。
    // 数章内段号要往前找到章标题，只看这一页数不出来，所以按整本书的块算一次（弹窗开着时算一次）。
    val bookBlocks = remember(book.id, book.pages) { StudyCoachCourse.blocks(book.id, book.pages) }
    var selected by remember(book.id, page.number, initialSelection) { mutableStateOf(initialSelection) }
    var draft by remember(book.id, page.number) { mutableStateOf(TextFieldValue("")) }
    var request by remember(book.id, page.number) { mutableStateOf<BookPassageQuestion?>(null) }
    var reply by remember(book.id, page.number) { mutableStateOf<BookLearningReply?>(null) }
    var revision by remember(book.id, page.number) { mutableIntStateOf(0) }
    var pendingConsent by remember(book.id, page.number) { mutableStateOf<BookPassageQuestion?>(null) }
    var source by remember(book.id, page.number) { mutableStateOf<BookPassage?>(null) }
    fun send(input: BookPassageQuestion) { reply = null; request = input; revision++ }
    LaunchedEffect(request, revision) {
        val input = request ?: return@LaunchedEffect
        reply = try {
            val result = support.ask(input)
            coroutineContext.ensureActive()
            result
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { BookLearningReply.Unavailable("暂时读不到回答，可以保留阅读位置后再试。") }
    }
    // 段落列表和问题框一直都在：点一段只是把那一行就地染色，前后的段落和滚动位置都不动。
    val scroll = rememberScrollState()
    val colors = CoachTheme.colors
    // 发出去的那一段从按下发送起就钉在原地，直到弹窗关掉：回答里的「出处」打开的就是它。
    // 还能接着点别的行的话，读者会把第 2 段的出处当成第 3 段——问第 2 段、等回答时点第 3 段，
    // 高亮落在第 3 段、出处却是第 2 段，两段同页连页码都对不出来。
    val asked = request?.passage
    // 请求里的这一段在本页列表里排第几；-1 表示它不在本页（读者从阅读页里别的段落点进来的）。
    val askedIndex = asked?.let { passage -> blocks.indexOfFirst { it.start == passage.start && it.end == passage.end } } ?: -1
    // 高亮跟着「这次提问的那一段」走；还没提问时跟着读者点的那一段，发送前可以随便换。
    val chosenIndex = if (asked != null) askedIndex else blocks.indexOfFirst { it.id == selected?.id }
    // 段号退回这一段自己的页码：读者能对上原文的位置，内部 block id 不露在外面。
    val chosenText = if (asked != null) asked.text else selected?.text
    val chosenOrdinal = if (asked != null) asked.page else selected?.page
    val hasChoice = asked != null || selected != null
    // V7：弹窗是 surface2 的纸；段落、选中的原文和输入框都是叠在上面的一层 surface1 纸，按钮走纸面按钮，不用 Material 描边。
    AlertDialog(modifier = Modifier.testTag("book-passage-dialog"), onDismissRequest = onClose, title = { Text("就这段问一句") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(scroll).testTag("book-passage-ask"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("点一段，再问一句", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary,
                modifier = Modifier.testTag("book-passage-hint"))
            if (blocks.isEmpty()) Text("这一页没有可选的文字段落，可以先翻到有文字的页面。", style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
            // 一列段落在同一张纸上，行间只用细线分开：读起来是一页原文，不是一排同样的按钮。
            // 点中的那行就地留下松绿线和 `pineWash` 底，前后的段落仍然在列表里，读者能对照着看。
            if (blocks.isNotEmpty()) PaperCard(color = colors.surface1, contentSpacing = 0.dp, contentPadding = PaddingValues(0.dp)) {
                blocks.forEachIndexed { index, block ->
                    val chosen = index == chosenIndex
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderSubtle))
                    // 已经高亮的那行、以及已经发出去一段之后的所有行都不再可点：点它没有别的结果。
                    // 不可点也就不合并语义，段号和原文才能按标签读到。
                    val pick: (() -> Unit)? = if (chosen || asked != null) null
                        // 换一段就把上一条「这段原文的位置无法确认，请重新选择」清掉：它说的是上一次的选段。
                        else ({ selected = block; reply = null })
                    PassageRow(index + 1, ReadableText.reflow(block.text), selected = chosen, rowTag = "ask-paragraph-$index",
                        textTag = if (chosen) "book-selected-passage" else null, onClick = pick)
                }
                // 不在本页列表里的段落（从阅读页直接点进来）：补一行在纸面末尾，段号退回它的页码。
                val outside = chosenText?.takeIf { chosenIndex < 0 }
                if (outside != null) {
                    if (blocks.isNotEmpty()) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderSubtle))
                    PassageRow(chosenOrdinal ?: 1, ReadableText.reflow(outside), selected = true, textTag = "book-selected-passage")
                }
            }
            val question = draft.text
            val typed = question.trim().length
            val tooLong = typed > BookPassageQuestion.MAX_QUESTION_LENGTH
            // Enter 和「发送」按钮共用放行条件。
            fun canSend(text: String) = hasChoice && BookPassageQuestion.accepts(text) && (request == null || reply != null)
            // 文本和放行条件都在按下的这一刻现读：按钮变灰靠的是上一帧的 draft，读者敲字与点发送之间
            // 可能还没重排，用重排时的快照会把刚打的字丢掉，发出一句空问题。
            fun submit() {
                val text = draft.text
                if (!canSend(text)) return
                val passage = asked ?: runCatching { BookPassage.from(book, checkNotNull(selected)) }.getOrNull()
                if (passage == null) {
                    request = null
                    reply = BookLearningReply.Unavailable("这段原文的位置无法确认，请重新选择。")
                } else {
                    val input = BookPassageQuestion(passage, text)
                    if (support.consentRequired(book.id)) pendingConsent = input else send(input)
                }
            }
            // Nothing typed here is truncated: over-length text stays editable and copyable, and
            // the hint below says why sending is closed instead of letting the provider reject it.
            V7QuestionField(draft, { draft = it }, "想问什么？", isError = tooLong,
                onSubmit = { submit() },
                modifier = Modifier.fillMaxWidth().testTag("book-passage-question"))
            Text(
                if (tooLong) "已超过 ${BookPassageQuestion.MAX_QUESTION_LENGTH} 字，删掉 " +
                    "${typed - BookPassageQuestion.MAX_QUESTION_LENGTH} 字才能发送。已输入的文字没有被截断。"
                else "$typed / ${BookPassageQuestion.MAX_QUESTION_LENGTH}",
                modifier = Modifier.testTag("book-passage-length"), style = MaterialTheme.typography.bodySmall,
                color = if (tooLong) v7Alert() else colors.fgSecondary)
            PrimaryButton(onClick = { submit() }, enabled = canSend(question),
                modifier = Modifier.testTag("book-passage-send")) { Text("发送") }
            when (val result = reply) {
                null -> if (request != null) Text("正在读这段问题…", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                is BookLearningReply.Text -> PaperCard(Modifier.testTag("book-passage-reply"), color = colors.surface1) {
                    // 回答里的【S1】是给程序的引用标记，读者看下面那行「出处 · 第 N 页 · 第 M 段」就够了。
                    Text(ReadableText.withoutCitationMarks(result.text), style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
                    result.sources.forEach { passage ->
                        V7TextButton({ if (onOpenOriginal != null) onOpenOriginal(passage) else source = passage },
                            Modifier.testTag("book-passage-citation")) {
                            Text("出处 · ${bookPassageLabel(book, bookBlocks, passage)}")
                        }
                    }
                }
                // 没拿到回答：选中的原文和打好的问题都还在；能重试就原样再发一次。
                is BookLearningReply.Unavailable -> ExplainTroubleLine(result, onRetry = { request?.let(::send) },
                    onRepair = onRepair, modifier = Modifier.testTag("book-passage-unavailable"))
            }
        }
    }, confirmButton = { V7TextButton(onClose, modifier = Modifier.testTag("book-passage-close")) { Text("回到阅读") } })
    pendingConsent?.let { input ->
        AlertDialog(onDismissRequest = { pendingConsent = null }, title = { Text(ExplainPrivacy.TITLE) },
            text = { Text(ExplainPrivacy.ask(book.title), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ask-consent")) },
            confirmButton = { PrimaryButton({
                support.allow(book.id); pendingConsent = null; send(input)
            }, Modifier.testTag("ask-consent-allow")) { Text("开启并提问") } },
            dismissButton = { V7TextButton({ pendingConsent = null }, Modifier.testTag("ask-consent-deny")) { Text("先不用") } })
    }
    source?.let { passage ->
        AlertDialog(onDismissRequest = { source = null }, title = { Text("原文 · ${bookPassageLabel(book, bookBlocks, passage)}", modifier = Modifier.testTag("book-passage-source-title")) },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(passage.text, Modifier.testTag("book-passage-source"))
            } }, confirmButton = { V7TextButton({ source = null }) { Text("回到问题") } })
    }
}

/**
 * 选段就问里的一行原文：行首是段号（数字字体），悬停叠一层浅底，选中时整行 `pineWash` 底、左边一条松绿线。
 * 行是纸面的一部分：不加阴影、不描一圈边，六段排在一起像一页原文，而不是六个一模一样的按钮。
 * 选中的行留在原来的位置、不再可点：前后段落不动，行高也不变，读者看到的是「这一段被标出来了」。
 */
@Composable
private fun PassageRow(
    ordinal: Int, text: String, selected: Boolean = false, rowTag: String? = null, textTag: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = CoachTheme.colors
    val pine = v7Pine()
    val shape = RoundedCornerShape(CoachV7.Radius.s)
    val wash = if (colors.dark) CoachV7.Dark.pineWash else CoachV7.Light.pineWash
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    // 悬停沿用 PaperButton 的叠色（浅一层、按下深一点），和书架行、选项行是同一套手感。
    val hoverWash = if (pressed) (if (colors.dark) CoachV7.Dark.selBg else CoachV7.Light.selBg)
    else (if (colors.dark) CoachV7.Dark.zhuWash else CoachV7.Light.zhuWash)
    val base = Modifier.fillMaxWidth().then(if (rowTag != null) Modifier.testTag(rowTag) else Modifier)
        .clip(shape).background(when {
            selected -> wash
            hovered || pressed -> hoverWash
            else -> Color.Transparent
        })
        // 松绿线按整行高画在最左边，行多长线就多长；选中那行的段号也跟着染松绿。
        .drawBehind { if (selected) drawRect(pine, size = Size(3.dp.toPx(), size.height)) }
    val row = if (onClick != null) base.v7FocusRing(shape).pointerHoverIcon(PointerIcon.Hand)
        .selectable(selected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
    else base
    Row(row.padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$ordinal", Modifier.width(20.dp).alignByBaseline()
            .then(if (textTag != null) Modifier.testTag("$textTag-number") else Modifier),
            fontFamily = LocalV7Fonts.current.numbers, style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End, color = if (selected) pine else colors.fgTertiary)
        Text(text, Modifier.weight(1f).alignByBaseline()
            .then(if (textTag != null) Modifier.testTag(textTag) else Modifier),
            style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
    }
}

/**
 * 选段提问的问题框：和 V7Field 同一套纸面、细描边、焦点环和字号。
 * 这里自己画一遍是因为要按 Enter 发送：只有拿 [TextFieldValue] 才看得见输入法的候选（composition），
 * 「问」字还没选定时按 Enter 是在确认候选，不能当成发送。Shift+Enter 仍是换行。
 */
@Composable
private fun V7QuestionField(
    value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, label: String, isError: Boolean,
    onSubmit: () -> Unit, modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.btn)
    val gate = remember { EnterGate() }
    val current by rememberUpdatedState(value)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
        BasicTextField(value, { next -> gate.onEdit(current.composition != null, next.composition != null); onValueChange(next) },
            singleLine = false, minLines = 3,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.fgPrimary),
            cursorBrush = SolidColor(colors.fgPrimary),
            modifier = modifier.v7FocusRing(shape).clip(shape).background(colors.surface1)
                .border(1.dp, if (isError) v7Alert() else colors.borderSubtle, shape).padding(horizontal = 12.dp, vertical = 10.dp)
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                    val down = event.type == KeyEventType.KeyDown
                    when {
                        gate.shouldSend(true, down, event.isShiftPressed, current.composition != null) -> { onSubmit(); true }
                        // 确认候选或暂时不能发送时，Enter 不插入换行。Shift+Enter 照旧换行。
                        down && !event.isShiftPressed -> true
                        else -> false
                    }
                })
    }
}
