package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.V7TextButton

internal const val DEFAULT_BOOK_SEARCH_LIMIT = 200

/** 书内查找的单条命中：始终记录原文页码与精确 UTF-16 偏移，供四格读法就地跳转并高亮。 */
internal data class BookSearchHit(
    val page: Int,
    val start: Int,
    val end: Int,
    val quote: String,
    val snippet: String,
    val snippetMatchStart: Int,
    val snippetMatchEnd: Int,
) {
    fun toPassage(bookId: String): BookPassage = BookPassage(bookId, page, start, end, quote)
}

internal data class BookSearchResult(
    val query: String,
    val hits: List<BookSearchHit>,
    val truncated: Boolean,
)

private val HtmlCommentMarker = Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL))

/** 在当前这本书的全部页面里做大小写不敏感子串查找，跳过内部页标记注释，返回精确 UTF-16 偏移。 */
internal fun searchBookPages(
    pages: List<BookPage>,
    rawQuery: String,
    limit: Int = DEFAULT_BOOK_SEARCH_LIMIT,
): BookSearchResult {
    val query = rawQuery.trim()
    if (query.isEmpty() || limit <= 0) return BookSearchResult(query, emptyList(), truncated = false)
    val hits = mutableListOf<BookSearchHit>()
    var truncated = false
    for (page in pages) {
        val text = page.text
        if (text.isEmpty()) continue
        val hiddenRanges = if ("<!--" in text) HtmlCommentMarker.findAll(text).map { it.range }.toList() else emptyList()
        var from = 0
        while (from <= text.length - query.length) {
            val found = text.indexOf(query, startIndex = from, ignoreCase = true)
            if (found < 0) break
            val end = found + query.length
            val insideComment = hiddenRanges.any { found < it.last + 1 && end > it.first }
            if (!insideComment) {
                if (hits.size >= limit) {
                    truncated = true
                    return BookSearchResult(query, hits, truncated = true)
                }
                val (snippet, matchStart, matchEnd) = buildSearchSnippet(text, found, end)
                hits += BookSearchHit(
                    page = page.number,
                    start = found,
                    end = end,
                    quote = text.substring(found, end),
                    snippet = snippet,
                    snippetMatchStart = matchStart,
                    snippetMatchEnd = matchEnd,
                )
            }
            from = end.coerceAtLeast(found + 1)
        }
    }
    return BookSearchResult(query, hits, truncated)
}

private fun buildSearchSnippet(text: String, matchStart: Int, matchEnd: Int): Triple<String, Int, Int> {
    val windowStart = (matchStart - 24).coerceAtLeast(0)
    val windowEnd = (matchEnd + 48).coerceAtMost(text.length)
    val builder = StringBuilder()
    if (windowStart > 0) builder.append('…')
    var mappedStart = -1
    var mappedEnd = -1
    var prevSpace = false
    for (i in windowStart until windowEnd) {
        if (i == matchStart) mappedStart = builder.length
        val ch = text[i]
        val isWs = ch == '\n' || ch == '\r' || ch == '\t' || ch == ' '
        if (isWs) {
            if (!prevSpace && builder.isNotEmpty()) {
                builder.append(' ')
                prevSpace = true
            }
        } else {
            builder.append(ch)
            prevSpace = false
        }
        if (i + 1 == matchEnd) mappedEnd = builder.length
    }
    if (windowEnd < text.length) builder.append('…')
    val s = builder.toString()
    val start = mappedStart.coerceIn(0, s.length)
    val end = mappedEnd.coerceIn(start, s.length)
    return Triple(s, start, end)
}

@Composable
internal fun BookSearchPanel(
    book: Textbook,
    query: String,
    result: BookSearchResult,
    activeIndex: Int,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onPick: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.m)
    val highlight = if (colors.dark) CoachV7.Dark.hl else CoachV7.Light.hl
    val activeBg = if (colors.dark) CoachV7.Dark.pineWash else CoachV7.Light.pineWash
    val countText = when {
        result.query.isEmpty() -> ""
        result.hits.isEmpty() -> "没找到匹配内容"
        result.truncated -> "${(activeIndex.coerceAtLeast(0) + 1).coerceAtMost(result.hits.size)} / ${result.hits.size}+"
        activeIndex in result.hits.indices -> "${activeIndex + 1} / ${result.hits.size}"
        else -> "共 ${result.hits.size} 处"
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface2)
            .border(1.dp, colors.borderSubtle, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("book-search-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text("在书内查找…", style = MaterialTheme.typography.bodySmall, color = colors.fgTertiary) },
                textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.fgPrimary),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { onFocusChange(it.isFocused) }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter -> {
                                if (result.hits.isNotEmpty()) {
                                    if (event.isShiftPressed) onPrevious() else onNext()
                                }
                                true
                            }
                            Key.Escape -> {
                                onClose()
                                true
                            }
                            else -> false
                        }
                    }
                    .testTag("book-search-input"),
            )
            if (countText.isNotEmpty()) {
                Text(
                    countText,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = LocalV7Fonts.current.numbers,
                    color = colors.fgSecondary,
                    modifier = Modifier.testTag("book-search-count"),
                )
            }
            V7TextButton(
                onClick = onPrevious,
                enabled = result.hits.isNotEmpty(),
                modifier = Modifier.testTag("book-search-prev"),
                ringInside = true,
            ) { Text("上一处") }
            V7TextButton(
                onClick = onNext,
                enabled = result.hits.isNotEmpty(),
                modifier = Modifier.testTag("book-search-next"),
                ringInside = true,
            ) { Text("下一处") }
            V7TextButton(
                onClick = onClose,
                modifier = Modifier.testTag("book-search-close"),
                ringInside = true,
            ) { Text("关闭") }
        }
        if (result.hits.isNotEmpty()) {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 180.dp).testTag("book-search-results"),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(result.hits) { index, hit ->
                    val selected = index == activeIndex
                    val rowShape = RoundedCornerShape(CoachV7.Radius.s)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(rowShape)
                            .background(if (selected) activeBg else colors.surface2)
                            .clickable { onPick(index) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("book-search-hit-$index"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            book.pageLabel(hit.page),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = LocalV7Fonts.current.numbers,
                            color = colors.fgSecondary,
                        )
                        val annotated = buildAnnotatedString {
                            append(hit.snippet.substring(0, hit.snippetMatchStart))
                            withStyle(SpanStyle(background = highlight, fontWeight = FontWeight.SemiBold, color = colors.fgPrimary)) {
                                append(hit.snippet.substring(hit.snippetMatchStart, hit.snippetMatchEnd))
                            }
                            append(hit.snippet.substring(hit.snippetMatchEnd))
                        }
                        Text(
                            annotated,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.fgPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
