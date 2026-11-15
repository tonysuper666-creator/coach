package com.coach.desktop.books

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

internal sealed interface BookSourceState {
    data object Ready : BookSourceState
    data class Stale(val message: String) : BookSourceState
    data class WrongBook(val message: String) : BookSourceState
}

/** Passage must belong to the explicitly passed current book AND resolve in the real library (same file version, page, range, quote). */
internal fun bookSourceState(p: BookPassage, book: Textbook, resolver: BookSourceResolver): BookSourceState {
    if (p.book != book.id) return BookSourceState.WrongBook("这条出处对不上当前这本书。")
    val r = resolver.resolve(OwnedBookCitation(p.book, p.page, p.start, p.end), p.text)
    return if (r is BookSourceResolution.Resolved) BookSourceState.Ready else BookSourceState.Stale("书的内容变了，这条出处已过期。")
}

/** 「第 12 页 · “前 16 字…”」: no SHA, no offsets. Counts by code point so a surrogate pair is never split. */
internal fun bookSourceLabel(p: BookPassage): String {
    val flat = p.text.replace(Regex("""\s+"""), " ").trim()
    val points = flat.codePointCount(0, flat.length)
    val short = if (points <= 16) flat else flat.substring(0, flat.offsetByCodePoints(0, 16)) + "…"
    return "第 ${p.page} 页 · “$short”"
}

/**
 * Typed-source row. Only [BookSourceState.Ready] rows are buttons (focusable, in Tab order); stale / other-book rows
 * are read-only text. [onOpen] receives the freshly re-resolved [BookSourceTarget] (physical page, quote, UTF-16 range).
 * Click re-resolves, so a file changed since composition is refused and the row turns stale instead of opening.
 */
@Composable
internal fun BookSourceRow(
    sources: List<BookPassage>, book: Textbook, resolver: BookSourceResolver,
    focus: (Int) -> FocusRequester, onOpen: (index: Int, target: BookSourceTarget) -> Unit, modifier: Modifier = Modifier,
) {
    var refreshed by remember(sources, book.id) { mutableStateOf(0) }
    Column(modifier.fillMaxWidth()) {
        if (sources.isEmpty()) {
            Text("这段讲解没有可核对的出处。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("book-source-none"))
            return@Column
        }
        sources.forEachIndexed { i, p ->
            val state = remember(p, book.id, refreshed) { bookSourceState(p, book, resolver) }
            when (state) {
                BookSourceState.Ready -> TextButton(
                    onClick = {
                        val again = resolver.resolve(OwnedBookCitation(p.book, p.page, p.start, p.end), p.text)
                        if (p.book == book.id && again is BookSourceResolution.Resolved) onOpen(i, again.target) else refreshed++
                    },
                    modifier = Modifier.focusRequester(focus(i)).testTag("book-source-$i")
                        .semantics { contentDescription = "打开出处：《${book.title}》第 ${p.page} 页" },
                ) { Text(bookSourceLabel(p)) }
                is BookSourceState.Stale -> Column(Modifier.testTag("book-source-stale-$i")) {
                    Text(bookSourceLabel(p), style = MaterialTheme.typography.bodySmall)
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                is BookSourceState.WrongBook -> Column(Modifier.testTag("book-source-wrong-$i")) {
                    Text(bookSourceLabel(p), style = MaterialTheme.typography.bodySmall)
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
