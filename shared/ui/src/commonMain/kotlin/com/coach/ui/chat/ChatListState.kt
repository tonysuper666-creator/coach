package com.coach.ui.chat

import androidx.compose.foundation.gestures.scrollBy
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
    internal var restoring by mutableStateOf(true)
}

/** Follow the tail again (the learner just sent something or asked for the next step). */
suspend fun ChatListState.jumpToTail() {
    anchor.pinned = true
    list.scrollToItem(0, 0)
}

/**
 * Put the item at chronological [index] (of [count] items) at the top edge of the viewport, with the newer items following it, so a new
 * question group is read from question 1. An item taller than the viewport is shown from its own top. Clamped at the tail.
 */
suspend fun ChatListState.scrollItemToTop(index: Int, count: Int) {
    val target = count - 1 - index
    if (target < 0) return
    snapshotFlow { restoring }.first { !it }
    androidx.compose.runtime.withFrameNanos { }
    list.scrollToItem(target, 0)
    val size = snapshotFlow { list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }?.size }.filterNotNull().first()
    val info = list.layoutInfo
    val room = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding - size
    if (room <= 0) list.scrollToItem(target, -room) else list.scrollBy(-room.toFloat())
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
    // Record the reading place. Any move that is not our own restore is the reader's (drag, fling, jump, "回到最新").
    LaunchedEffect(state) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            if (state.restoring) return@collect
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
