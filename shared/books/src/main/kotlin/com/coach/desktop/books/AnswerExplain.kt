package com.coach.desktop.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.CancellationException

/**
 * 「做题学」答完之后的讲解：同一本书只问一次能不能把这段发出去，问过之后才连已配对的 Mac。
 * [load] 每次进入这张卡都重读配对文件，所以在原文页配好对回到这里就能直接用。
 * [explain] 与 [ask] 复用同一配对通道，测试传本地函数即可，不碰网络。
 */
internal class AnswerExplain(
    val consent: ExplainConsent,
    val load: suspend () -> Unit,
    val ready: () -> Boolean,
    val paired: () -> Boolean,
    val explain: suspend (chosen: SourceBlock, blocks: List<SourceBlock>) -> ExplainResult,
    val revoked: () -> Boolean = { false },
    val ask: (suspend (SourceBlock, String) -> ExplainResult)? = null,
    /** 这台电脑放没放出「用 Sable 账号登录」；停用那一句只指向真能用的入口。 */
    val sableLogin: () -> Boolean = { false },
) {
    // Generated practice is deliberately window-local: never grade it with the saved cloze key.
    val practices = mutableStateMapOf<String, GeneratedPractice>()
    val answers = mutableStateMapOf<String, PracticeAnswer>()
    val recaps = mutableStateMapOf<String, com.coach.domain.LadderSession>()
}

/** Windows 主机的接线：配对与「这本书允许吗」沿用原文页已有的那一套，不另建通道。 */
internal fun answerExplainWith(explainer: BookExplainer, consent: ExplainConsent) = AnswerExplain(
    consent = consent,
    load = { explainer.load() },
    ready = { explainer.loaded },
    paired = { explainer.identity != null },
    explain = { chosen, blocks -> explainer.explain(chosen, blocks) },
    revoked = { explainer.revoked },
    ask = { chosen, question -> explainer.ask(chosen, question) },
    sableLogin = { explainer.sableLogin },
)

/**
 * 讲解只从这一节里挑一段发出去：先找题目依据所在的那段，找不到才退回本节第一段正文。
 * 回执里的块编号必须落在发出去的这几段里，否则这次讲解作废。
 */
internal class PairedBookExplainSupport(
    private val gate: AnswerExplain,
    private val focusText: String? = null,
) : BookLearningSupport {
    override suspend fun explain(request: BookExplanationRequest): BookLearningReply {
        if (!gate.consent.allowed(request.book)) return BookLearningReply.Unavailable(CONSENT_FIRST)
        val body = request.blocks.filterNot { it.heading }
        val chosen = focusText?.let { text -> body.firstOrNull { it.text.contains(text) } } ?: body.firstOrNull()
            ?: return BookLearningReply.Unavailable("这一节没有可讲的正文。")
        return try {
            when (val result = gate.explain(chosen, request.blocks)) {
                is ExplainResult.Done -> {
                    val byId = request.blocks.associateBy { it.id }
                    val cited = result.explanation.citations.mapNotNull { citation ->
                        byId[citation.blockId]?.let { block ->
                            BookPassage(request.book, block.page, block.start, block.end, block.text)
                        }
                    }
                    BookLearningReply.Text(ReadableText.withoutCitationMarks(result.explanation.text), cited)
                }
                is ExplainResult.Refused -> BookLearningReply.Unavailable(result.message, result.trouble)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            BookLearningReply.Unavailable("讲解暂时读不到；作答和位置都还在。")
        }
    }

    override suspend fun ask(request: BookPassageQuestion) =
        BookLearningReply.Unavailable("选段问答还没接通；这里的讲解讲的是这一节正文。")

    private companion object {
        const val CONSENT_FIRST = "讲解要先得到你的允许：在原文页点「讲解」并允许一次，或在这里直接允许。"
    }
}

/**
 * 答完题之后的这一块：没配对就指路，没允许就问一次，都齐了才真的去取讲解。
 * 一次都不自动发送原文；跳过之后保留一行可以反悔的入口。
 */
@Composable
internal fun LessonAnswerExplanation(
    book: Textbook,
    lesson: BookLesson,
    practice: Practice,
    gate: AnswerExplain,
    reply: MutableState<BookLearningReply?>,
    sources: @Composable (List<BookPassage>) -> Unit,
    onOpenSourcePage: () -> Unit,
) {
    LaunchedEffect(gate) { gate.load() }
    var allowed by remember(book.id) { mutableStateOf(gate.consent.allowed(book.id)) }
    var skipped by remember(book.id, lesson.id) { mutableStateOf(false) }
    val ready = gate.ready()
    val paired = gate.paired()
    when {
        !ready -> Text("正在检查服务连接…", Modifier.testTag("answer-explain-loading"),
            style = MaterialTheme.typography.bodySmall)
        !paired -> {
            val onRelogin = LocalBookRelogin.current
            Column(Modifier.fillMaxWidth().testTag("answer-explain-unpaired"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (gate.revoked()) ExplainTrouble.revoked(gate.sableLogin()) else "讲解由配对的服务生成，这台电脑还没连上。读书和做题照常。",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("answer-explain-unpaired-text"))
                V7TextButton(
                    onClick = { if (onRelogin != null) onRelogin("授权已撤销，请重新登录。") else onOpenSourcePage() },
                    modifier = Modifier.testTag("answer-explain-pair"),
                ) { Text(if (onRelogin != null) "重新登录" else "去原文页配对") }
            }
        }
        skipped -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("answer-explain-skipped")) {
            Text("这次先不看讲解。", style = MaterialTheme.typography.bodySmall)
            V7TextButton({ skipped = false }, modifier = Modifier.testTag("answer-explain-resume")) { Text("还是看一下") }
        }
        !allowed -> V7ExpandIn {
            PaperCard(Modifier.testTag("answer-explain-consent"), contentPadding = PaddingValues(16.dp)) {
                Text("答完了，看看这段怎么讲", style = MaterialTheme.typography.titleMedium)
                Text(ExplainPrivacy.answer(book.title),
                    style = MaterialTheme.typography.bodySmall, color = CoachTheme.colors.fgSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    PrimaryButton({ gate.consent.allow(book.id); allowed = true }, modifier = Modifier.testTag("answer-explain-allow")) {
                        Text("允许并讲解")
                    }
                    V7TextButton({ skipped = true }, modifier = Modifier.testTag("answer-explain-skip")) { Text("先不看") }
                }
            }
        }
        else -> {
            val support = remember(book.id, lesson.id, practice.reference, gate) {
                PairedBookExplainSupport(gate, practice.reference)
            }
            // 停用之后在原文页重新配好了：留着的那条「已停用」作废，这一节重新去取讲解。
            LaunchedEffect(reply.value) {
                if ((reply.value as? BookLearningReply.Unavailable)?.trouble?.needsPairing == true && !gate.revoked()) reply.value = null
            }
            BookLessonExplanation(book, lesson, support, reply = reply, sources = sources, onRepair = onOpenSourcePage)
        }
    }
}

/**
 * 小结卡：只写这一节真的发生过的事——做了什么题、对不对、原文在哪一页——再给一条继续走的路。
 * 原文不铺在这里，要回看就点上面练习里的「查看对应原文」。
 *
 * 和六题小结同屏时，这张卡在上面，只报刚才这一节，所以标题就写「这一节」；下面的
 * [KnowledgeRecapCard] 才是这一轮的知识。两卡同屏时 [nav] 传 null：这一屏只留最下面「接着做题」一组操作。
 */
@Composable
internal fun LessonSummaryCard(
    book: Textbook,
    lesson: BookLesson,
    practice: Practice,
    answer: PracticeAnswer,
    temporary: Boolean = false,
    nav: (@Composable () -> Unit)? = null,
) {
    V7ExpandIn {
        PaperCard(Modifier.v7Resize().testTag("book-summary-card"), contentPadding = PaddingValues(16.dp)) {
            Text("这一节", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("book-summary-title"))
            Text(lesson.title, style = MaterialTheme.typography.bodyMedium)
            Text(summaryLine(practice, answer, temporary), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("book-summary-detail"))
            Text("原文在「${lesson.title}」这一节，第 ${lesson.firstPage} 页起；要回看时再打开。",
                style = MaterialTheme.typography.bodySmall, color = CoachTheme.colors.fgSecondary)
            nav?.invoke()
        }
    }
}

private fun summaryLine(practice: Practice, answer: PracticeAnswer, temporary: Boolean): String {
    if (temporary) return if (answer.guessed) "你把这次作答标记为猜的，选对也不计作独立答对。题目和作答只保留在这次练习中。"
        else "你选了「${answer.text}」，${if (answer.text == practice.answer) "与本题答案一致" else "本题答案是「${practice.answer}」"}。题目和作答只保留在这次练习中。"
    if (answer.guessed) return "你把这次作答标记为猜的，已留在复习里；选对也不计作独立答对。"
    val picked = answer.text.trim()
    return if (practice.options.isNotEmpty()) {
        if (picked == practice.answer) "你选了「$picked」，和原文一致。"
        else "你选了「$picked」，原句是「${practice.answer}」。错一次不要紧，讲过的这段下次还会碰到。"
    } else {
        val written = picked.length
        when (answer.understood) {
            true -> "你写下了 $written 个字，并标记了已理解。"
            false -> "你写下了 $written 个字，并标记了还需复习。"
            null -> "你写下了 $written 个字，还没标记是否理解。"
        }
    }
}


internal data class GeneratedPractice(val practice: Practice, val explanation: BookLearningReply.Text)

/** The existing cited selection-answer channel returns one plain paragraph, not a new API schema. */
internal object LessonPracticeWire {
    const val question = "为了检验我是否理解这段，请给一道中文四选一练习。把题干、四个选项、正确答案和答后解析写在同一段，依次用这些分隔词：题干：… 选项A：… 选项B：… 选项C：… 选项D：… 答案：A 解析：…。只考这段支持的一个知识点，只有一个正确选项，其他选项是明确错误的理解；解析说明为什么。不要摘抄原文长句，不要换行。依据不够就说明原因。"

    fun parse(book: Textbook, chosen: SourceBlock, result: Explanation): GeneratedPractice? {
        if (result.citations.isEmpty() || result.citations.any { it.blockId != chosen.id || it.page != chosen.page }) return null
        val text = ReadableText.withoutCitationMarks(result.text)
        val pattern = Regex("题干[：:]\\s*(.+?)\\s*选项A[：:]\\s*(.+?)\\s*选项B[：:]\\s*(.+?)\\s*选项C[：:]\\s*(.+?)\\s*选项D[：:]\\s*(.+?)\\s*答案[：:]\\s*([A-D])\\s*[；;。]?\\s*解析[：:]\\s*(.+)", RegexOption.DOT_MATCHES_ALL)
        val match = pattern.matchEntire(text) ?: return null
        val parts = match.groupValues.drop(1).map { it.trim().trimEnd('；', ';') }
        val options = parts.subList(1, 5)
        if (parts.any { it.isBlank() } || options.distinct().size != 4 || parts[0].length > 1000 || options.any { it.length > 600 }) return null
        val source = runCatching { BookPassage.from(book, chosen) }.getOrNull() ?: return null
        return GeneratedPractice(Practice(parts[0], chosen.text, options[parts[5][0] - 'A'], options),
            BookLearningReply.Text(parts[6], listOf(source)))
    }
}

/** One provider call makes both the question and hidden answer explanation; navigation never recharges it. */
@Composable
internal fun GeneratedLessonPractice(
    book: Textbook, lesson: BookLesson, gate: AnswerExplain,
    sourceFocus: androidx.compose.ui.focus.FocusRequester,
    sources: @Composable (List<BookPassage>) -> Unit,
    onSource: (Practice) -> Unit, onRepair: () -> Unit,
    fallback: @Composable () -> Unit,
    nav: @Composable () -> Unit,
) {
    val key = "${book.id}:${lesson.id}"
    var allowed by remember(book.id) { mutableStateOf(gate.consent.allowed(book.id)) }
    var attempt by remember(key) { mutableStateOf(0) }
    var failure by remember(key) { mutableStateOf<BookLearningReply.Unavailable?>(null) }
    val generated = gate.practices[key]
    LaunchedEffect(gate, key, attempt, allowed) {
        gate.load()
        if (generated != null || !allowed || !gate.paired()) return@LaunchedEffect
        failure = null
        val chosen = lesson.blocks.firstOrNull { !it.heading && it.text.trim().length in 40..4000 }
        if (chosen == null) {
            failure = BookLearningReply.Unavailable("这一节没有足够的正文可以出题，请从目录选择有正文的一节。")
            return@LaunchedEffect
        }
        try {
            when (val result = checkNotNull(gate.ask).invoke(chosen, LessonPracticeWire.question)) {
                is ExplainResult.Refused -> failure = BookLearningReply.Unavailable(result.message, result.trouble)
                is ExplainResult.Done -> {
                    val parsed = LessonPracticeWire.parse(book, chosen, result.explanation)
                    if (parsed == null) {
                        failure = BookLearningReply.Unavailable(
                            "这次没有得到格式完整、出处可核对的选择题，可以重试。",
                            ExplainTrouble.Other,
                        )
                    } else gate.practices[key] = parsed
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = BookLearningReply.Unavailable("出题暂时读不到，可以重试或先读原文。") }
    }
    when {
        generated != null -> {
            val answer = gate.answers[key] ?: PracticeAnswer()
            Text("本次练习", Modifier.testTag("generated-practice"), style = MaterialTheme.typography.labelMedium)
            SourcePractice(lesson.id, QuestionMode.CHOICE, generated.practice, answer, { updated ->
                gate.answers[key] = updated
                if (updated.revealed) {
                    val q = com.coach.domain.LadderQuestion(key, lesson.id, lesson.id, "concept", 1, key, key, syntheticOnly = true)
                    gate.recaps[book.id] = com.coach.domain.QuestionLadder.record(gate.recaps[book.id] ?: com.coach.domain.LadderSession(),
                        q, false, generated.explanation.text)
                }
            }, { onSource(generated.practice) }, true, sourceFocus, showReference = false, temporary = true)
            if (answer.revealed) {
                PaperCard(Modifier.testTag("book-lesson-explanation"), contentPadding = PaddingValues(16.dp)) {
                    Text("答后讲解", style = MaterialTheme.typography.titleMedium)
                    Text(generated.explanation.text, Modifier.testTag("book-explanation-text"))
                    sources(generated.explanation.sources)
                }
                val recap = gate.recaps[book.id]?.takeIf { it.recapOpen }
                // 两张小结同屏：这一节在上、六题小结在下，继续/完成只留最下面一组。
                LessonSummaryCard(book, lesson, generated.practice, answer, temporary = true,
                    nav = if (recap == null) nav else null)
                if (recap != null) KnowledgeRecapCard(recap) {
                    gate.recaps[book.id] = com.coach.domain.QuestionLadder.closeRecap(recap)
                }
            } else nav()
        }
        // 连不上的时候不能让这一节空着：配对只管「现出题」，原文出的题和作答照常。
        !gate.ready() -> fallback()
        !gate.paired() -> {
            val onRelogin = LocalBookRelogin.current
            Text(if (gate.revoked()) ExplainTrouble.revoked(gate.sableLogin()) else "现出题和答后讲解要用配对的服务；连上之前，这一节先用原文出的题。")
            V7TextButton({ if (onRelogin != null) onRelogin("授权已撤销，请重新登录。") else onRepair() }) {
                Text(if (onRelogin != null) "重新登录" else "去原文页配对")
            }
            fallback()
        }
        !allowed -> {
            // 允许与否只决定「按本节原文现出题」；这一节原文出的题始终在，读者不必先答应再答题。
            Text(ExplainPrivacy.ask(book.title) + "允许后，这一节会通过服务按原文现出一题并给答后讲解；题目只用于这次练习。")
            PrimaryButton({ gate.consent.allow(book.id); allowed = true }, Modifier.testTag("practice-allow")) { Text("允许并出题") }
            fallback()
        }
        failure != null -> {
            ExplainTroubleLine(checkNotNull(failure), onRetry = { attempt++ }, onRepair = onRepair,
                modifier = Modifier.testTag("practice-unavailable"))
            fallback()
        }
        else -> {
            Text("正在按本节原文出题…", Modifier.testTag("practice-loading"))
            nav()
        }
    }
}
