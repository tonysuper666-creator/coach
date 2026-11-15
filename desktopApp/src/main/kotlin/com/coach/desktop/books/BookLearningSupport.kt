package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private const val BOOK_EXPLANATION_OFF = "这本书没开讲解，可以直接读原文或先做题。"
private enum class BookConsentPhase { READY, CHANGING, STOPPED }

/** UI intent is separate from authorization: only the affirmative notice button grants anything. */
private class BookConsentUi(val book: String, val support: ConsentingBookLearningSupport, val clear: () -> Unit) {
    val phase = MutableStateFlow(BookConsentPhase.READY)
    var notice by mutableStateOf<NeedsConsent?>(null)
    var offered by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var stopped by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun offer(reply: BookLearningReply?) {
        val offer = (reply as? BookLearningReply.Unavailable)?.needsConsent ?: return
        if (!offered && !stopped && offer.offerAutomatically) {
            offered = true
            notice = offer
        }
    }

    suspend fun change(enable: Boolean, operation: suspend () -> Unit) {
        if (busy) return
        phase.value = BookConsentPhase.CHANGING
        busy = true
        stopped = true
        notice = null
        error = null
        clear() // Hide an already displayed answer before waiting for cancellation or disk I/O.
        try { operation(); stopped = !enable }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "没能保存讲解设置，请重试；原文和做题不受影响。" }
        finally {
            busy = false
            phase.value = if (stopped) BookConsentPhase.STOPPED else BookConsentPhase.READY
        }
    }
}

private data class BookRequestState(val epoch: Long, val busy: Boolean, val stopped: Boolean)

private fun currentRequestState(support: ConsentingBookLearningSupport?, consent: BookConsentUi?): BookRequestState =
    if (consent?.phase?.value == BookConsentPhase.CHANGING) BookRequestState(0, busy = true, stopped = true)
    else BookRequestState(support?.changes?.value ?: 0L, busy = false, stopped = consent?.phase?.value == BookConsentPhase.STOPPED)

/** Normalize delayed notifications to the current state before deciding whether to restart a request. */
private fun bookRequestStates(support: ConsentingBookLearningSupport?, consent: BookConsentUi?): Flow<BookRequestState> =
    if (support == null) flowOf(currentRequestState(null, null))
    else combine(support.changes, consent?.phase ?: flowOf(BookConsentPhase.READY)) { _, _ ->
        currentRequestState(support, consent)
    }.distinctUntilChanged()

@Composable
private fun BookConsentPanel(state: BookConsentUi?, reply: BookLearningReply?) {
    if (state == null) return
    val scope = rememberCoroutineScope()
    LaunchedEffect(reply, state.busy) { if (!state.busy) state.offer(reply) }
    val offer = state.notice
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (offer != null) {
            OutlinedCard(Modifier.fillMaxWidth().testTag("book-consent-notice")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("讲解要把这一节（或你选的这段）原文发给 ${offer.identity.model}，用的是这台电脑上已登录的 Codex 账号。只发这一段，不发整本书、你的作答和笔记。只对这本书有效，随时可以在这本书里关掉。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { state.change(true) {
                            state.support.grant(offer, setOf(OwnedBookAction.EXPLAIN, OwnedBookAction.ASK))
                        } } }, enabled = !state.busy, modifier = Modifier.testTag("book-consent-grant")) { Text("这本书允许") }
                        TextButton(onClick = { scope.launch { state.change(false) { state.support.decline(state.book) } } },
                            enabled = !state.busy, modifier = Modifier.testTag("book-consent-decline")) { Text("先不用") }
                    }
                }
            }
        } else if (state.stopped || (reply as? BookLearningReply.Unavailable)?.needsConsent != null) {
            TextButton(onClick = { state.offered = true; state.notice = state.support.consentOffer(state.book) },
                enabled = !state.busy, modifier = Modifier.testTag("book-consent-reopen")) { Text("重新打开讲解") }
        } else {
            TextButton(onClick = { scope.launch { state.change(false) { state.support.revoke(state.book) } } },
                enabled = !state.busy, modifier = Modifier.testTag("book-consent-revoke")) { Text("关掉讲解") }
        }
        state.error?.let { Text(it, Modifier.testTag("book-consent-error")) }
    }
}

/** Local content identities, never substitutes for a paired course, KP or HTTP document ID. */
internal data class BookExplanationRequest(val book: String, val lesson: String, val blocks: List<SourceBlock>)
internal data class BookPassage(val book: String, val page: Int, val start: Int, val end: Int, val text: String) {
    companion object {
        fun from(book: Textbook, block: SourceBlock): BookPassage {
            val page = book.pages.firstOrNull { it.number == block.page }
            require(page != null && !block.heading && block.start >= 0 && block.end <= page.text.length &&
                block.end > block.start && block.id == "${book.id}:p${block.page}:${block.start}" &&
                page.text.substring(block.start, block.end) == block.text) {
                "这段原文的位置无法确认，请重新选择。"
            }
            return BookPassage(book.id, page.number, block.start, block.end, block.text)
        }
    }
}
internal data class BookPassageQuestion(val passage: BookPassage, val question: String)
internal sealed interface BookLearningReply {
    data class Text(val text: String, val sources: List<BookPassage> = emptyList()) : BookLearningReply
    data class Unavailable(val message: String, val needsConsent: NeedsConsent? = null) : BookLearningReply
}

/** A real, authorized explanation/answer provider must be supplied here; source text is not a reply. */
internal interface BookLearningSupport {
    suspend fun explain(request: BookExplanationRequest): BookLearningReply
    suspend fun ask(request: BookPassageQuestion): BookLearningReply
}

internal object UnconnectedBookLearningSupport : BookLearningSupport {
    override suspend fun explain(request: BookExplanationRequest) =
        BookLearningReply.Unavailable("这节的讲解尚未接通。可以主动打开对应原文，或先试着做题。")
    override suspend fun ask(request: BookPassageQuestion) =
        BookLearningReply.Unavailable("选段问答尚未接通，暂时不能回答。阅读位置已保留。")
}

@Composable
internal fun BookLessonExplanation(
    book: Textbook,
    lesson: BookLesson,
    support: BookLearningSupport,
    reply: MutableState<BookLearningReply?> = remember(book.id, lesson.id, support) { mutableStateOf(null) },
    replyEpoch: MutableState<Long> = remember(reply, support) {
        mutableStateOf((support as? ConsentingBookLearningSupport)?.changes?.value ?: 0L)
    },
    sources: @Composable (List<BookPassage>) -> Unit = {},
) {
    val consenting = support as? ConsentingBookLearningSupport
    val epoch = consenting?.changes?.collectAsState()?.value ?: 0L
    val consent = remember(book.id, support, reply) { consenting?.let { BookConsentUi(book.id, it) { reply.value = null } } }
    // A caller may hold this across a screen switch so returning does not re-ask the provider.
    LaunchedEffect(book.id, lesson.id, support, reply, replyEpoch, consent) {
        bookRequestStates(consenting, consent).collectLatest { state ->
            if (state != currentRequestState(consenting, consent) || state.busy) return@collectLatest
            if (replyEpoch.value != state.epoch) { reply.value = null; replyEpoch.value = state.epoch }
            if (state.stopped) {
                reply.value = BookLearningReply.Unavailable(BOOK_EXPLANATION_OFF, consenting?.consentOffer(book.id))
                return@collectLatest
            }
            if (reply.value != null) return@collectLatest
            val answer = try {
                val result = support.explain(BookExplanationRequest(book.id, lesson.id, lesson.blocks))
                coroutineContext.ensureActive()
                if (result is BookLearningReply.Text && (result.text.isBlank() || result.text.trim() == lesson.text.trim()))
                    BookLearningReply.Unavailable("这节还没有可用的讲解，可以主动打开对应原文。")
                else result
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { BookLearningReply.Unavailable("讲解暂时读不到，原有作答和位置已保留。") }
            if (state == currentRequestState(consenting, consent) && replyEpoch.value == state.epoch) reply.value = answer
        }
    }
    Column(Modifier.fillMaxWidth().testTag("book-lesson-explanation"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("讲解", style = MaterialTheme.typography.titleMedium)
        val current = if (consent?.stopped == true) BookLearningReply.Unavailable(BOOK_EXPLANATION_OFF)
            else reply.value.takeIf { replyEpoch.value == (consenting?.changes?.value ?: epoch) && consent?.busy != true }
        BookConsentPanel(consent, current)
        when (val result = current) {
            null -> Text("正在读取这节的讲解…")
            is BookLearningReply.Text -> {
                Text(result.text, Modifier.testTag("book-explanation-text"))
                sources(result.sources)
            }
            is BookLearningReply.Unavailable -> Text(result.message, Modifier.testTag("book-explanation-unavailable"))
        }
    }
}

/** Explicit paragraph selection keeps the original page offsets even for Unicode text. */
@Composable
internal fun BookPassageAsk(book: Textbook, page: BookPage, support: BookLearningSupport, onClose: () -> Unit) {
    val blocks = remember(book.id, page.number) { StudyCoachCourse.blocks(book.id, listOf(page)).filterNot { it.heading } }
    var selected by remember(book.id, page.number) { mutableStateOf<SourceBlock?>(null) }
    var question by remember(book.id, page.number) { mutableStateOf("") }
    var request by remember(book.id, page.number) { mutableStateOf<BookPassageQuestion?>(null) }
    var reply by remember(book.id, page.number) { mutableStateOf<BookLearningReply?>(null) }
    var revision by remember(book.id, page.number) { mutableIntStateOf(0) }
    val consenting = support as? ConsentingBookLearningSupport
    val epoch = consenting?.changes?.collectAsState()?.value ?: 0L
    var replyEpoch by remember(book.id, page.number, support) { mutableLongStateOf(epoch) }
    val consent = remember(book.id, page.number, support) { consenting?.let { BookConsentUi(book.id, it) { reply = null } } }
    LaunchedEffect(book.id, page.number, support, consent) {
        combine(bookRequestStates(consenting, consent), snapshotFlow { request to revision }) { _, _ ->
            Triple(currentRequestState(consenting, consent), request, revision)
        }.distinctUntilChanged().collectLatest { (state, input, attempt) ->
            if (state != currentRequestState(consenting, consent) || state.busy) return@collectLatest
            if (replyEpoch != state.epoch) { reply = null; replyEpoch = state.epoch }
            if (state.stopped || input == null) return@collectLatest
            val answer = try {
                val result = support.ask(input)
                coroutineContext.ensureActive()
                result
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { BookLearningReply.Unavailable("暂时读不到回答，可以保留阅读位置后再试。") }
            if (state == currentRequestState(consenting, consent) && request == input && revision == attempt) reply = answer
        }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("就这段问一句") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()).testTag("book-passage-ask"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val current = reply.takeIf { replyEpoch == (consenting?.changes?.value ?: epoch) && consent?.busy != true && consent?.stopped != true }
            BookConsentPanel(consent, current)
            if (consent?.stopped == true) Text(BOOK_EXPLANATION_OFF)
            if (selected == null) {
                Text("选择要问的原文段落")
                if (blocks.isEmpty()) Text("这一页没有可选的文字段落，可以先翻到有文字的页面。")
                blocks.forEachIndexed { index, block ->
                    OutlinedButton({ selected = block }, Modifier.fillMaxWidth().testTag("ask-paragraph-$index")) {
                        Text(block.text)
                    }
                }
            } else {
                Text(checkNotNull(selected).text, Modifier.testTag("book-selected-passage"))
                OutlinedTextField(question, { question = it.take(1000) }, label = { Text("想问什么？") },
                    modifier = Modifier.fillMaxWidth().testTag("book-passage-question"))
                Button(onClick = {
                    val passage = runCatching { BookPassage.from(book, checkNotNull(selected)) }.getOrNull()
                    if (passage == null) {
                        request = null
                        reply = BookLearningReply.Unavailable("这段原文的位置无法确认，请重新选择。")
                    } else {
                        reply = null
                        request = BookPassageQuestion(passage, question)
                        revision++
                    }
                }, enabled = question.isNotBlank() && consent?.busy != true && consent?.stopped != true &&
                    (request == null || reply != null), modifier = Modifier.testTag("book-passage-send")) { Text("发送") }
                when (val result = current) {
                    null -> if (request != null && consent?.stopped != true) Text("正在读这段问题…")
                    is BookLearningReply.Text -> Text(result.text, Modifier.testTag("book-passage-answer"))
                    is BookLearningReply.Unavailable -> Text(result.message, Modifier.testTag("book-passage-unavailable"))
                }
            }
        }
    }, confirmButton = { TextButton(onClose) { Text("回到阅读") } })
}
