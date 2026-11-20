package com.coach.desktop.books

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.V7TextButton

internal sealed interface BookSourceState {
    data object Ready : BookSourceState
    data class Stale(val message: String) : BookSourceState
    data class WrongBook(val message: String) : BookSourceState
}

/** Passage must belong to the explicitly passed current book AND resolve in the real library (same file version, page, range, quote). */
internal fun bookSourceState(p: BookPassage, book: Textbook, resolver: BookSourceResolver): BookSourceState =
    bookSourceStates(listOf(p), book, resolver).single()

/** Batch check, blocking: call off the UI thread. The book file is hashed once for the whole batch. */
internal fun bookSourceStates(sources: List<BookPassage>, book: Textbook, resolver: BookSourceResolver): List<BookSourceState> {
    val mine = sources.filter { it.book == book.id }
    val results = resolver.resolveAll(mine.map { OwnedBookCitation(it.book, it.page, it.start, it.end) to it.text }).iterator()
    return sources.map { p ->
        if (p.book != book.id) BookSourceState.WrongBook("这条出处对不上当前这本书。")
        else if (results.next() is BookSourceResolution.Resolved) BookSourceState.Ready
        else BookSourceState.Stale("书的内容变了，这条出处已过期。")
    }
}

/** 「第 12 页 · 第 3 段 · “前 16 字…”」或「本章第 3 段 · “前 16 字…”」: no SHA, no offsets. Counts by code point so a surrogate pair is never split. */
internal fun bookSourceLabel(
    p: BookPassage,
    book: Textbook? = null,
    blocks: List<SourceBlock>? = null,
): String {
    val location = if (book != null && p.book == book.id) {
        bookPassageLabel(book, blocks ?: StudyCoachCourse.blocks(book.id, book.pages), p)
    } else {
        "第 ${p.page} 页"
    }
    return "$location · ${shortQuote(p.text)}"
}

/**
 * Typed-source row. Verification never runs during composition: rows show as plain labels until a background
 * batch check ([Dispatchers.IO], one file hash per batch) finishes. Only [BookSourceState.Ready] rows are buttons
 * (focusable, in Tab order); stale / other-book rows are read-only text. A click re-verifies off the UI thread;
 * on success [onOpen] gets the fresh [BookSourceTarget] (physical page, quote, UTF-16 range), on refusal the row
 * turns stale and [onRefused] fires so the caller can move focus off the button that vanished.
 */
@Composable
internal fun BookSourceRow(
    sources: List<BookPassage>, book: Textbook, resolver: BookSourceResolver,
    focus: (Int) -> FocusRequester, onOpen: (index: Int, target: BookSourceTarget) -> Unit, modifier: Modifier = Modifier,
    onRefused: (index: Int) -> Unit = {},
) {
    var refreshed by remember(sources, book.id) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val blocks = remember(book.id, book.pages) { StudyCoachCourse.blocks(book.id, book.pages) }
    val states by produceState<List<BookSourceState>?>(null, sources, book.id, refreshed) {
        value = withContext(Dispatchers.IO) { bookSourceStates(sources, book, resolver) }
    }
    Column(modifier.fillMaxWidth()) {
        if (sources.isEmpty()) {
            Text("这段讲解没有可核对的出处。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("book-source-none"))
            return@Column
        }
        sources.forEachIndexed { i, p ->
            val label = bookSourceLabel(p, book, blocks)
            when (val state = states?.getOrNull(i)) {
                null -> Column(Modifier.testTag("book-source-pending-$i").nonButtonRow(), verticalArrangement = Arrangement.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
                BookSourceState.Ready -> V7TextButton(
                    onClick = {
                        scope.launch {
                            val again = withContext(Dispatchers.IO) { resolver.resolve(OwnedBookCitation(p.book, p.page, p.start, p.end), p.text) }
                            if (p.book == book.id && again is BookSourceResolution.Resolved) onOpen(i, again.target)
                            else { refreshed++; onRefused(i) }
                        }
                    },
                    modifier = Modifier.focusRequester(focus(i)).testTag("book-source-$i")
                        .semantics { contentDescription = "打开出处：《${book.title}》$label" },
                ) {
                    // V7 出处：主色下划线小字，像书页边的注，不再是一排和正文抢眼的按钮。
                    Text("出处 · " + label, style = MaterialTheme.typography.labelLarge, color = v7Pine(), textDecoration = TextDecoration.Underline)
                }
                is BookSourceState.Stale -> Column(Modifier.testTag("book-source-stale-$i").nonButtonRow(), verticalArrangement = Arrangement.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = CoachTheme.colors.dangerFg)
                }
                is BookSourceState.WrongBook -> Column(Modifier.testTag("book-source-wrong-$i").nonButtonRow(), verticalArrangement = Arrangement.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = CoachTheme.colors.dangerFg)
                }
            }
        }
    }
}

/** Same height and text edge as a button row, so a row never jumps when it turns into (or out of) a button. */
@Composable
private fun Modifier.nonButtonRow(): Modifier =
    this.fillMaxWidth().minimumInteractiveComponentSize().padding(ButtonDefaults.TextButtonContentPadding)

/** V7 出处链接色：松绿 pine，随明暗取 token，不用 Material 主色（主色是墨色，给主按钮用）。 */
@Composable
internal fun v7Pine(): androidx.compose.ui.graphics.Color =
    if (CoachTheme.colors.dark) coach.design.tokens.CoachV7.Dark.pine else coach.design.tokens.CoachV7.Light.pine
