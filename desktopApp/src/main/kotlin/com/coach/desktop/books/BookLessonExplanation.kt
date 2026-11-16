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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

@Composable
internal fun BookLessonExplanation(
    book: Textbook,
    lesson: BookLesson,
    support: BookLearningSupport,
    reply: MutableState<BookLearningReply?> = remember(book.id, lesson.id, support) { mutableStateOf(null) },
    sources: @Composable (List<BookPassage>) -> Unit = {},
) {
    // A caller may hold this across a screen switch so returning does not re-ask the provider.
    LaunchedEffect(book.id, lesson.id, support) {
        if (reply.value != null) return@LaunchedEffect
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
            when (val result = reply.value) {
                null -> Text("正在读取这节的讲解…", color = com.coach.ui.theme.CoachTheme.colors.fgSecondary)
                is BookLearningReply.Text -> {
                    Text(result.text, Modifier.testTag("book-explanation-text"))
                    sources(result.sources)
                }
                is BookLearningReply.Unavailable -> Text(result.message, Modifier.testTag("book-explanation-unavailable"))
            }
        }
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
