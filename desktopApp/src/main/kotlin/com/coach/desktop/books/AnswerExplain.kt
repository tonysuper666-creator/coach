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
 * [explain] 是唯一的网络入口，测试传一个本地函数即可，不碰网络。
 */
internal class AnswerExplain(
    val consent: ExplainConsent,
    val load: suspend () -> Unit,
    val ready: () -> Boolean,
    val paired: () -> Boolean,
    val explain: suspend (chosen: SourceBlock, blocks: List<SourceBlock>) -> ExplainResult,
    val revoked: () -> Boolean = { false },
)

/** Windows 主机的接线：配对与「这本书允许吗」沿用原文页已有的那一套，不另建通道。 */
internal fun answerExplainWith(explainer: BookExplainer, consent: ExplainConsent) = AnswerExplain(
    consent = consent,
    load = { explainer.load() },
    ready = { explainer.loaded },
    paired = { explainer.identity != null },
    explain = { chosen, blocks -> explainer.explain(chosen, blocks) },
    revoked = { explainer.revoked },
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
        !ready -> Text("正在看这台电脑和 Mac 的连接…", Modifier.testTag("answer-explain-loading"),
            style = MaterialTheme.typography.bodySmall)
        !paired -> Column(Modifier.fillMaxWidth().testTag("answer-explain-unpaired"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (gate.revoked()) ExplainTrouble.REVOKED else "讲解由配对的 Mac 生成，这台电脑还没连上。读书和做题照常。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("answer-explain-unpaired-text"))
            V7TextButton(onOpenSourcePage, modifier = Modifier.testTag("answer-explain-pair")) { Text("去原文页配对") }
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
                if ((reply.value as? BookLearningReply.Unavailable)?.trouble == ExplainTrouble.Revoked && !gate.revoked()) reply.value = null
            }
            BookLessonExplanation(book, lesson, support, reply = reply, sources = sources, onRepair = onOpenSourcePage)
        }
    }
}

/**
 * 小结卡：只写这一节真的发生过的事——做了什么题、对不对、原文在哪一页——再给一条继续走的路。
 * 原文不铺在这里，要回看就点上面练习里的「查看对应原文」。
 */
@Composable
internal fun LessonSummaryCard(
    book: Textbook,
    lesson: BookLesson,
    practice: Practice,
    answer: PracticeAnswer,
    nav: @Composable () -> Unit,
) {
    V7ExpandIn {
        PaperCard(Modifier.v7Resize().testTag("book-summary-card"), contentPadding = PaddingValues(16.dp)) {
            Text("小结", style = MaterialTheme.typography.titleMedium)
            Text("这一节：${lesson.title}", style = MaterialTheme.typography.bodyMedium)
            Text(summaryLine(practice, answer), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("book-summary-detail"))
            Text("原文在「${lesson.title}」这一节，第 ${lesson.firstPage} 页起；要回看时再打开。",
                style = MaterialTheme.typography.bodySmall, color = CoachTheme.colors.fgSecondary)
            nav()
        }
    }
}

private fun summaryLine(practice: Practice, answer: PracticeAnswer): String {
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
