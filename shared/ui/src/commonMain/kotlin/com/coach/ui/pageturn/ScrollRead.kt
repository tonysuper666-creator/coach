package com.coach.ui.pageturn

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coach.ui.v7.LocalV7ReducedMotion
import kotlinx.coroutines.launch

/**
 * 连续纵向滚动阅读的状态。页下标和 [PageTurnState.position] 同一套，所以翻页和滚动互切时位置对得上。
 * 页里画什么由 [ScrollReadView] 的调用方给；页高由页内容自己决定，页和页之间留 gap。
 */
@Stable
class ScrollReadState(val pageCount: Int, initial: ScrollReadPosition = ScrollReadPosition(0)) {
    internal val list = LazyListState(
        firstVisibleItemIndex = initial.page.coerceIn(0, maxOf(0, pageCount - 1)),
        firstVisibleItemScrollOffset = 0,
    )
    private var pendingFraction: Float? = initial.fraction.takeIf { it > 0f }
    internal var reduced = false

    /** 视口顶边所在的页和页内进度。滚过页边后自然换成下一页。 */
    val position: ScrollReadPosition
        get() {
            val first = list.layoutInfo.visibleItemsInfo.firstOrNull()
                ?: return ScrollReadPosition(list.firstVisibleItemIndex)
            return ScrollReadPosition(first.index, scrollFraction(list.firstVisibleItemScrollOffset, first.size))
        }

    /** 视口里能看到的页下标范围，给跨页选字、出处框选用。 */
    val visiblePages: IntRange
        get() = list.layoutInfo.visibleItemsInfo.let { if (it.isEmpty()) IntRange.EMPTY else it.first().index..it.last().index }

    /** 整本书的粗进度 0..1，给细进度条用。 */
    val overallProgress: Float
        get() = when {
            !list.canScrollBackward && !list.canScrollForward -> 0f
            !list.canScrollForward -> 1f
            !list.canScrollBackward -> 0f
            else -> ((position.page + position.fraction) / pageCount).coerceIn(0f, 1f)
        }

    /** 滚到某页某处（直接到位，不播动画）。页还没量过时进度先记着，量出来再补。 */
    suspend fun scrollTo(target: ScrollReadPosition) {
        val page = target.page.coerceIn(0, maxOf(0, pageCount - 1))
        list.scrollToItem(page)
        if (target.fraction > 0f) {
            val h = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == page }?.size
            if (h != null) list.scrollToItem(page, scrollOffsetFor(target.fraction, h)) else pendingFraction = target.fraction
        }
    }

    /** Place a content anchor near the upper third of the viewport. The page footer is outside the anchor's coordinates. */
    suspend fun reveal(target: ScrollReadPosition, footerPx: Int) {
        val page = target.page.coerceIn(0, maxOf(0, pageCount - 1))
        list.scrollToItem(page)
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == page } ?: return
        val contentHeight = (item.size - footerPx).coerceAtLeast(0)
        val offset = (scrollOffsetFor(target.fraction, contentHeight) - list.layoutInfo.viewportSize.height / 3).coerceAtLeast(0)
        if (offset > 0) list.scrollToItem(page, offset)
    }

    /** 滚到真正的书尾：最后一页的最后一行可见，不只是末页页首。 */
    suspend fun scrollToEnd() {
        list.scrollToItem(maxOf(0, pageCount - 1))
        list.scrollBy(1_000_000f)
    }

    val canScrollForward: Boolean get() = list.canScrollForward

    /** 列表已经量过、能读出页高了。 */
    val laidOut: Boolean get() = list.layoutInfo.visibleItemsInfo.isNotEmpty()

    /** 用户或惯性正在滚（程序跳转 scrollTo 不算）。 */
    val isScrolling: Boolean get() = list.isScrollInProgress

    suspend fun scrollLine(forward: Boolean) {
        list.scrollBy(if (forward) 48f else -48f)
    }

    /** 往后/往前滚一屏；减弱动画时直接到位。 */
    suspend fun scrollScreen(forward: Boolean) {
        val dy = screenScrollPx(list.layoutInfo.viewportSize.height) * (if (forward) 1 else -1)
        if (reduced) list.scrollBy(dy) else list.animateScrollBy(dy)
    }

    internal suspend fun settlePending() {
        val f = pendingFraction ?: return
        val first = list.layoutInfo.visibleItemsInfo.firstOrNull() ?: return
        pendingFraction = null
        list.scrollToItem(first.index, scrollOffsetFor(f, first.size))
    }
}

/**
 * 竖着滑着读：整本书连成一条往下滚，页与页之间留 gap 的桌面色缝，滚过页边接着读下一页。
 * 滚轮、触控板惯性、空格 / PageDown（一屏）、PageUp、Home / End、方向键都能用；右侧有一条细进度条。
 * 系统关了动画（[LocalV7ReducedMotion]）时键盘滚动直接到位。
 */
@Composable
fun ScrollReadView(
    state: ScrollReadState,
    desk: Color,
    paper: Color,
    modifier: Modifier = Modifier,
    gap: Dp = 16.dp,
    handleKeys: Boolean = true,
    /** 滚动条由宿主给：桌面传 [ReadScrollbarDesktop]（V7Scrollbar），不传就没有。 */
    scrollbar: @Composable BoxScope.(ScrollReadState) -> Unit = {},
    page: @Composable (Int) -> Unit,
) {
    state.reduced = LocalV7ReducedMotion.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.list.layoutInfo.visibleItemsInfo.size) { state.settlePending() }
    Box(
        modifier.background(desk)
            .then(if (handleKeys) Modifier.focusable() else Modifier)
            .onKeyEvent { e ->
                if (!handleKeys || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.PageDown -> { scope.launch { state.scrollScreen(true) }; true }
                    Key.Spacebar -> { scope.launch { state.scrollScreen(!e.isShiftPressed) }; true }
                    Key.PageUp -> { scope.launch { state.scrollScreen(false) }; true }
                    Key.DirectionDown -> { scope.launch { state.scrollLine(true) }; true }
                    Key.DirectionUp -> { scope.launch { state.scrollLine(false) }; true }
                    Key.MoveHome -> { scope.launch { state.scrollTo(ScrollReadPosition(0)) }; true }
                    Key.MoveEnd -> { scope.launch { state.scrollToEnd() }; true }
                    else -> false
                }
            }
    ) {
        LazyColumn(
            state = state.list,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            items(state.pageCount, key = { it }) { index ->
                Box(Modifier.fillMaxWidth().background(paper)) { page(index) }
            }
        }
        // 右侧滚动条：按实际可滚范围和视口比例，由 scrollbar 参数给（桌面 V7Scrollbar：浅深色、悬停加粗、可拖）
        scrollbar(state)
    }
}
