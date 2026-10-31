package com.coach.ui.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Where the reader is, in terms that survive a window change (fold/unfold, Pad rotation, Mac resize) and a process restore.
 * An item key plus the fraction of that item already scrolled past the top edge: an index or pixel offset moves when text reflows.
 * [pinned] = following the tail (the reader is at the bottom); otherwise the reader keeps the same place on screen.
 */
@Stable
class ChatAnchor(pinned: Boolean = true, key: Any? = null, fraction: Float = 0f) {
    var pinned by mutableStateOf(pinned)
    var key by mutableStateOf(key)
    var fraction by mutableStateOf(fraction)
}

/** Own this above every layout branch (ViewModel, or a `remember` outside `movableContentOf`): it must not die when the pane layout changes. */
@Stable
class ChatListState(val list: LazyListState = LazyListState(), val anchor: ChatAnchor = ChatAnchor()) {
    /** True until the first restore has run, so the initial (0,0) position never overwrites a stored anchor. */
    internal var restoring = true

    /** Newest item key seen, and whether the reader was at the tail when a newer item arrived (the list keeps the old first item on screen, hiding the new one). */
    internal var newestKey: Any? = null
    internal var followNewest = false

    /** The reader sent something or asked for "回到最新": follow the tail again. */
    suspend fun scrollToTail() { anchor.pinned = true; list.scrollToItem(0, 0) }

    /**
     * Bring the top of the item with [key] to the top of the viewport (a new question group is read from question 1).
     * Taller than the viewport: scroll past its bottom edge; shorter: it simply sits at the tail.
     */
    suspend fun scrollItemTopIntoView(key: Any) {
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val viewport = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
        if (item.size > viewport) list.scrollToItem(item.index, item.size - viewport)
    }
}

/**
 * Streaming-friendly conversation list, laid out from the bottom (`reverseLayout`): the newest item sits at offset 0, so a growing
 * streamed item pushes older content up while the reader is at the tail, and never moves a reader who scrolled up (the list keeps
 * the first visible item and its offset). No scroll-to-bottom code runs per delta. [items] are in chronological order.
 * When the window reshapes ([windowKey] changes, e.g. the width in px) a reader away from the tail is put back at the same fraction
 * of the same item, since text reflow changes every item's height.
 */
@Composable
fun <T> AnchoredChatList(
    items: List<T>,
    key: (T) -> Any,
    state: ChatListState,
    windowKey: Any?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    spacing: Dp = 12.dp,
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) {
    val list = state.list
    val anchor = state.anchor
    val newestFirst = remember(items) { items.asReversed() }
    val currentItems by rememberUpdatedState(newestFirst)
    val currentKey by rememberUpdatedState(key)
    // A new item arrives at index 0. Lazy lists keep the previously first item on screen, so a reader at the tail would be left looking
    // at the old item with the new one below the edge: remember that they were following, and bring the tail back after layout.
    val newest = items.lastOrNull()?.let(key)
    // Plain fields, set before the effect below starts and idempotent per key, so a repeated composition changes nothing.
    if (newest != state.newestKey) {
        state.followNewest = state.newestKey != null && anchor.pinned
        state.newestKey = newest
    }
    LaunchedEffect(newest) {
        if (state.followNewest) {
            androidx.compose.runtime.withFrameNanos { }
            list.scrollToItem(0, 0)
            anchor.pinned = true
            state.followNewest = false
        }
    }
    // Record the reading place. Any move that is not our own restore is the reader's (drag, fling, jump, "回到最新").
    LaunchedEffect(state) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            if (state.restoring || state.followNewest) return@collect
            anchor.pinned = index == 0 && offset == 0
            // The first *visible* item: content padding can leave an extra measured item before it in visibleItemsInfo.
            val first = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (!anchor.pinned && first != null && first.size > 0) { anchor.key = first.key; anchor.fraction = offset.toFloat() / first.size }
        }
    }
    // Window reshaped (or first composition, possibly after a process restore): put the reader back where they were reading.
    LaunchedEffect(windowKey) {
        if (currentItems.isEmpty()) { state.restoring = false; return@LaunchedEffect }
        state.restoring = true
        try {
            // This effect starts while BoxWithConstraints is still measuring; scrolling is only legal after layout has finished.
            androidx.compose.runtime.withFrameNanos { }
            if (anchor.pinned) list.scrollToItem(0, 0)
            else {
                val wanted = anchor.key
                val index = currentItems.indexOfFirst { currentKey(it) == wanted }
                if (index >= 0) {
                    list.scrollToItem(index, 0)
                    val size = snapshotFlow { list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == wanted }?.size }.filterNotNull().first()
                    list.scrollToItem(index, (anchor.fraction * size).toInt().coerceIn(0, maxOf(size - 1, 0)))
                    androidx.compose.runtime.withFrameNanos { }
                }
            }
        } finally { state.restoring = false }
    }
    LazyColumn(state = list, modifier = modifier, reverseLayout = true, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(spacing)) {
        items(newestFirst, key = key) { itemContent(it) }
    }
}
