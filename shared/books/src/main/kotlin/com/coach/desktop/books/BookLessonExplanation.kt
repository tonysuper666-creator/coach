package com.coach.desktop.books

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.v7.LocalV7ReducedMotion
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PrimaryButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

@Composable
internal fun BookLessonExplanation(
    book: Textbook,
    lesson: BookLesson,
    support: BookLearningSupport,
    reply: MutableState<BookLearningReply?> = remember(book.id, lesson.id, support) { mutableStateOf(null) },
    sources: @Composable (List<BookPassage>) -> Unit = {},
    onRepair: (() -> Unit)? = null,
) {
    // A caller may hold this across a screen switch so returning does not re-ask the provider.
    var attempt by remember(book.id, lesson.id, support) { mutableIntStateOf(0) }
    var allowed by remember(book.id, support) { mutableStateOf(!support.consentRequired(book.id)) }
    // 外面清掉旧结果（比如重新配对之后）也会重新取一次。只认这一帧看到的「空」：外面刚清掉、key 还没跟上时
    // 先不发，等 key 变过来再发，避免同一次重新配对重复请求。
    val waiting = reply.value == null
    LaunchedEffect(book.id, lesson.id, support, attempt, waiting, allowed) {
        if (!allowed || !waiting || reply.value != null) return@LaunchedEffect
        reply.value = try {
            val result = support.explain(BookExplanationRequest(book.id, lesson.id, lesson.blocks))
            coroutineContext.ensureActive()
            if (result is BookLearningReply.Text && (result.text.isBlank() || result.text.trim() == lesson.text.trim()))
                BookLearningReply.Unavailable("这节还没有可用的讲解，可以主动打开对应原文。")
            else result
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { BookLearningReply.Unavailable("讲解暂时读不到，原有作答和位置已保留。") }
    }
    V7ExpandIn {
        PaperCard(Modifier.v7Resize().testTag("book-lesson-explanation"), contentPadding = PaddingValues(16.dp)) {
            Text("讲解", style = MaterialTheme.typography.titleMedium)
            if (!allowed) {
                Text("讲解这一节需要发送原文", style = MaterialTheme.typography.titleSmall)
                Text("这一节的原文会经服务发给 Anthropic 或 OpenAI 的 API，不发到别处。API 默认不拿它训练模型。整本书留在这台电脑上。你可以继续读书和做题，允许后才会请求讲解。",
                    style = MaterialTheme.typography.bodySmall,
                    color = com.coach.ui.theme.CoachTheme.colors.fgSecondary,
                    modifier = Modifier.testTag("book-lesson-consent"))
                PrimaryButton({ support.allow(book.id); allowed = !support.consentRequired(book.id) },
                    modifier = Modifier.testTag("book-lesson-consent-allow")) { Text("允许并讲解") }
            } else {
                when (val result = reply.value) {
                    null -> Text("正在读取这节的讲解…", color = com.coach.ui.theme.CoachTheme.colors.fgSecondary)
                    is BookLearningReply.Text -> {
                        Text(result.text, Modifier.testTag("book-explanation-text"))
                        sources(result.sources)
                    }
                    is BookLearningReply.Unavailable -> LessonTroubleLine(result, onRetry = { reply.value = null; attempt++ },
                        onRepair = onRepair, modifier = Modifier.testTag("book-explanation-unavailable"))
                }
            }
        }
    }
}

@Composable
private fun LessonTroubleLine(reply: BookLearningReply.Unavailable, onRetry: () -> Unit,
    onRepair: (() -> Unit)?, modifier: Modifier = Modifier) {
    val trouble = reply.trouble
    Row(modifier.heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        when {
            trouble?.needsPairing == true && onRepair != null ->
                PaperButton(onRepair, modifier = Modifier.testTag("explain-trouble-repair")) {
                    Text(if (trouble == ExplainTrouble.Revoked) "去重新配对" else "去配对")
                }
            trouble?.retry == true -> PaperButton(onRetry, modifier = Modifier.testTag("explain-trouble-retry")) { Text("再试一次") }
        }
        // 和选段讲解保持一致：暂时连不上用次要色，只有需要重新配对时才用朱色。
        Text(reply.message, style = MaterialTheme.typography.bodySmall,
            color = if (trouble == ExplainTrouble.Revoked) v7Alert() else com.coach.ui.theme.CoachTheme.colors.fgSecondary,
            modifier = Modifier.weight(1f).testTag("explain-trouble"))
    }
}

/**
 * V7 卡片展开：讲解、小结、知识回顾第一次出现时用 260ms（`CoachV7.Motion.expandMs`）从上往下展开并淡入。
 * Windows 关掉「显示动画」时直接出现。只管出现，收起照旧立刻消失，不让旧卡多停一会儿。
 */
@Composable
internal fun V7ExpandIn(content: @Composable () -> Unit) {
    val reduced = LocalV7ReducedMotion.current
    val shown = remember { MutableTransitionState(reduced) }.apply { targetState = true }
    AnimatedVisibility(shown, Modifier.fillMaxWidth(),
        enter = if (reduced) EnterTransition.None
            else expandVertically(tween(CoachV7.Motion.expandMs, easing = CoachV7.Motion.easing), expandFrom = Alignment.Top) +
                fadeIn(tween(CoachV7.Motion.expandMs))) { content() }
}

/** 卡里内容换了（讲解读到了、小结多了一行），高度也用同样的 260ms 过渡；减少动态时不动。 */
@Composable
internal fun Modifier.v7Resize(): Modifier =
    if (LocalV7ReducedMotion.current) this else this.animateContentSize(tween(CoachV7.Motion.expandMs, easing = CoachV7.Motion.easing))
