package com.coach.ui.pageturn

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.v7.LocalV7ReducedMotion
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 翻页效果：翻转（绕书脊转）、卷页（拖着页角把纸掀起来）、滑动、无。 */
enum class PageTurnStyle { Flip, Curl, Slide, None }

/**
 * 翻页状态：现在翻到哪、正在翻哪一页。位置 = 左边第一页的下标（双页时总是偶数）。
 * 页里画什么由 [PageTurnView] 的调用方给，这里只管「怎么翻」。
 */
@Stable
class PageTurnState(val pageCount: Int, initialPosition: Int = 0, private val alignToSpread: Boolean = true) {
    private fun step(from: Int, fwd: Boolean) = stepPosition(from, pageCount, columns, fwd, alignToSpread)
    private fun norm(p: Int, cols: Int = columns) = normalizePosition(p, pageCount, cols, alignToSpread)
    var position by mutableIntStateOf(initialPosition.coerceIn(0, maxOf(0, pageCount - 1)))
        private set
    var columns by mutableIntStateOf(1)
        private set
    var turning by mutableStateOf(false)
        private set
    var forward by mutableStateOf(true)
        private set
    /** 跳页时还要快速翻过几张；0 = 没有。 */
    var riffle by mutableIntStateOf(0)
        private set
    /** 快翻时画面上沿途的位置；不快翻时等于 [position]。 */
    private var riffleAt by mutableIntStateOf(0)
    val shownPosition: Int get() = if (riffle > 0) riffleAt else position
    private val progressAnim = Animatable(0f)
    internal var reduced = false
    /** 当前效果；None 时 turn/jump 直接换页，拖动也不起。 */
    var style by mutableStateOf(PageTurnStyle.Flip)
    /** 布局改变（单双页切换）或手势取消时加一，让旧的翻页动画作废、不再提交位置。 */
    private var epoch = 0
    private var dragging = false
    /** 最近一次被翻页手势（横滑、拖页角、点边缘）消费掉的按下时间戳，避免正文 readingSpot 覆盖新页锚点。 */
    var lastHandledPointerDownUptimeMs: Long = Long.MIN_VALUE
        internal set

    private val instant get() = reduced || style == PageTurnStyle.None

    /**
     * 卷页时被拖着的页角在哪（翻起页坐标，按页宽、页高归一，见 PageCurlMath）；
     * null = 不是手拖的（键盘、点页边、滚轮），页角走默认弧线。
     */
    var curlAnchor by mutableStateOf<Offset?>(null)
        private set
    /** 卷页拖的是上角还是下角。 */
    var curlTop by mutableStateOf(false)
        private set

    /** 0..1，当前翻起的页翻到哪了。 */
    val progress: Float get() = progressAnim.value

    val canGoForward get() = step(position, true) != null
    val canGoBack get() = step(position, false) != null

    /** 自动翻一页（键盘、点页边、拖选到页边停住时都调它）。返回是否真的翻了。 */
    suspend fun turn(forward: Boolean, durationMs: Int = if (instant) 0 else CoachV7.Motion.pageMs): Boolean {
        if (turning) return false
        if (step(position, forward) == null) return false
        if (durationMs <= 0) { commitStep(forward); return true }
        this.forward = forward; turning = true; curlAnchor = null; curlTop = false
        val mine = epoch
        try {
            progressAnim.snapTo(0f)
            progressAnim.animateTo(1f, tween(durationMs, easing = LinearOutSlowInEasing))
            if (mine == epoch) commitStep(forward)
        } finally { if (mine == epoch) { turning = false; progressAnim.snapTo(0f) } }
        return true
    }

    /** 按提交那一刻的列数算落点，所以动画中途切单双页也不会写回旧落点。 */
    private fun commitStep(fwd: Boolean) {
        step(position, fwd)?.let { position = it }
    }

    /** 跳页：中间几页只快速翻过去（最多 6 张），不一页一页播；画面上沿途换不同的页。 */
    suspend fun jumpTo(page: Int) {
        val target = norm(page)
        if (target == position || turning) return
        val fwd = target > position
        val leaves = if (instant) 0 else riffleLeaves(position, target, columns)
        if (leaves == 0) { position = target; return }
        val mine = epoch
        val from = position
        var finished = false
        try {
            for (i in 0 until leaves) {
                if (mine != epoch) return
                riffle = leaves - i
                // 第 i 张从沿途的第 i 个位置翻起，最后一张从离目标一步处翻到目标
                riffleAt = norm(from + (target - from) * i / leaves)
                forward = fwd; turning = true; curlAnchor = null; curlTop = false
                progressAnim.snapTo(0f)
                progressAnim.animateTo(1f, tween(RIFFLE_MS, easing = LinearOutSlowInEasing))
            }
            finished = mine == epoch
        } finally {
            if (mine == epoch) {
                if (finished) position = target
                riffle = 0; turning = false; progressAnim.snapTo(0f)
            }
        }
    }

    /** 外部直接改了位置（目录跳转、改字号重排）：不动画，作废在途翻页。 */
    fun snapTo(page: Int) {
        epoch++; dragging = false; turning = false; riffle = 0
        position = norm(page)
    }

    internal fun clampTo(columnsNow: Int) {
        if (columnsNow != columns) {
            // 布局变了：作废正在进行的翻页和拖动，按新列数规整位置
            epoch++; dragging = false; turning = false; riffle = 0
        }
        columns = columnsNow
        position = norm(position, columnsNow)
    }

    internal suspend fun beginDrag(fwd: Boolean): Boolean {
        if (instant || turning || step(position, fwd) == null) return false
        forward = fwd; turning = true; dragging = true; curlAnchor = null; curlTop = false
        progressAnim.snapTo(0f); return true
    }

    internal suspend fun dragTo(p: Float) { if (dragging) progressAnim.snapTo(p) }

    /** 卷页拖动：页角跟着手走（[anchor] 归一坐标），[p] 是由页角位置换出来的进度。 */
    internal suspend fun dragCurl(p: Float, anchor: Offset, top: Boolean) {
        if (!dragging) return
        curlAnchor = anchor; curlTop = top
        progressAnim.snapTo(p)
    }

    /** 往回翻时，被倒着卷回来的那一张所在的位置（上一个位置）。 */
    internal fun previousOf(p: Int): Int = step(p, false) ?: p

    /** 手势被取消（布局切换、窗口失焦、手势协程被撤）：回弹，不留 turning。 */
    internal fun abortDrag() {
        if (!dragging) return
        dragging = false; epoch++; turning = false
    }

    internal suspend fun endDrag(complete: Boolean) {
        if (!dragging) return
        dragging = false
        val mine = epoch
        try {
            val ms = ((if (complete) 1f - progress else progress) * CoachV7.Motion.pageMs).toInt().coerceAtLeast(60)
            progressAnim.animateTo(if (complete) 1f else 0f, tween(ms, easing = LinearOutSlowInEasing))
            if (complete && mine == epoch) commitStep(forward)
        } finally { if (mine == epoch) { turning = false; progressAnim.snapTo(0f) } }
    }

    private companion object { const val RIFFLE_MS = 90 }
}

/**
 * 书页翻转：宽屏两页对开，窄屏单页。纸绕书脊转，带投影和书脊阴影，翻过 90° 后背面淡淡透出字。
 * 翻起的页只做图层旋转，不重排文字。[page] 给出第 i 页的内容，越界下标不会被调用。
 *
 * 手势：拖页角（过半松手翻过去，否则回弹）、点页边、← → 空格 PageUp PageDown、Home End、触控板横扫。
 * 自动翻页的时长走 V7 动效 token，跟手拖动不设时长；系统关了动画（[LocalV7ReducedMotion]）直接换页。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PageTurnView(
    state: PageTurnState,
    paper: Color,
    modifier: Modifier = Modifier,
    style: PageTurnStyle = PageTurnStyle.Flip,
    shadow: Color = Color.Black,
    spreadMinDp: Int = PAGE_TURN_SPREAD_MIN_DP,
    showThrough: Boolean = true,
    handleKeys: Boolean = true,
    /** 调用方已定好单双页（如书页按字号算分界）时传入；不传就按宽度和 [spreadMinDp] 判断。 */
    columns: Int? = null,
    /** Set to false when a caller arbitrates touch with its own content gestures. */
    handleTouch: Boolean = true,
    /** Set to false on selectable text views so mouse drags near corners select text instead of flipping pages. */
    handleMouseDrag: Boolean = true,
    /** Optional predicate checked on mouse press; returning false lets text selection own the press/drag/click. */
    canMouseTurnAt: ((Offset) -> Boolean)? = null,
    page: @Composable (Int) -> Unit,
) {
    state.reduced = LocalV7ReducedMotion.current
    state.style = style
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableIntStateOf(0) }
    val cols = columns ?: pageTurnColumns(with(density) { widthPx.toDp().value }, spreadMinDp)
    if (cols != state.columns) state.clampTo(cols)
    val pageW = with(density) { (widthPx.toFloat() / cols).toDp() }
    var scrollAcc by remember { mutableFloatStateOf(0f) }
    val travelFactor = if (cols >= 2) 0.9f else 0.6f
    val latestCanMouseTurnAt = androidx.compose.runtime.rememberUpdatedState(canMouseTurnAt)

    fun launchTurn(fwd: Boolean) { scope.launch { try { state.turn(fwd) } catch (_: CancellationException) { } } }

    Box(
        modifier
            .onSizeChanged { widthPx = it.width }
            .clipToBounds()
            .then(if (handleKeys) Modifier.focusable() else Modifier)
            .onKeyEvent { e ->
                if (!handleKeys || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionRight, Key.PageDown, Key.Spacebar -> { launchTurn(true); true }
                    Key.DirectionLeft, Key.PageUp -> { launchTurn(false); true }
                    Key.MoveHome -> { scope.launch { state.jumpTo(0) }; true }
                    Key.MoveEnd -> { scope.launch { state.jumpTo(state.pageCount - 1) }; true }
                    else -> false
                }
            }
            // 桌面滚轮翻页。onPointerEvent 只有桌面端有，commonMain 里改用通用事件循环读
            // scrollDelta；触屏不会产生 Scroll 事件，手机端等于不挂这个分支。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type != PointerEventType.Scroll) continue
                        val dx = ev.changes.firstOrNull()?.scrollDelta?.x ?: 0f
                        if (dx == 0f) continue
                        scrollAcc += dx
                        if (abs(scrollAcc) >= 3f) { launchTurn(scrollAcc > 0); scrollAcc = 0f }
                    }
                }
            }
            .pointerInput(cols, handleTouch, handleMouseDrag) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    if (!handleTouch && down.type == PointerType.Touch) return@awaitEachGesture
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val touch = down.type == PointerType.Touch
                    if (!touch && latestCanMouseTurnAt.value?.invoke(down.position) == false) return@awaitEachGesture
                    // 手指：单页左右 28% / 双页左右 14%（各页 28%）点一下翻页，整页任何位置横滑都能翻；鼠标：只认窄边和页角
                    val edge = w * (if (touch) (if (cols >= 2) 0.14f else 0.28f) else if (cols >= 2) 0.08f else 0.14f)
                    var fwd = down.position.x > w / 2f
                    val inEdge = down.position.x < edge || down.position.x > w - edge
                    val inCorner = inEdge && (down.position.y < h * 0.18f || down.position.y > h * 0.82f)
                    if (!inEdge && !touch) return@awaitEachGesture
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val instantMode = state.reduced || state.style == PageTurnStyle.None
                    var dragging = false
                    var swiped = false
                    var moved = false
                    var upConsumed = false
                    var lastPos = down.position
                    // 卷页：页角跟着手在平面上走。翻起页坐标的原点在书脊上沿。
                    val curl = state.style == PageTurnStyle.Curl && cols <= 2
                    val leafW = w / cols
                    val curlTopCorner = down.position.y < h / 2f
                    fun curlPointer(at: Offset): Offset {
                        val start = Offset(if (fwd) leafW else -leafW, if (curlTopCorner) 0f else h)
                        // 单页往回翻：上一页从屏幕外卷回来，横向走两倍才跟得上手
                        val k = if (cols == 1 && !fwd) 2f else 1f
                        return Offset(start.x + (at.x - down.position.x) * k, start.y + (at.y - down.position.y))
                    }
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (touch && !dragging && !swiped && (c.uptimeMillis - down.uptimeMillis) >= viewConfiguration.longPressTimeoutMillis) {
                                return@awaitEachGesture
                            }
                            if (!c.pressed) {
                                upConsumed = c.isConsumed
                                if (!dragging && !swiped && !moved && inEdge && !upConsumed) {
                                    state.lastHandledPointerDownUptimeMs = down.uptimeMillis
                                    c.consume()
                                }
                                break
                            }
                            if (!dragging && !swiped && c.isConsumed) return@awaitEachGesture
                            tracker.addPosition(c.uptimeMillis, c.position)
                            lastPos = c.position
                            val dx = c.position.x - down.position.x
                            val dy = c.position.y - down.position.y
                            if (!moved && (c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                moved = true
                            }
                            val slop = abs(dx) > viewConfiguration.touchSlop && (!touch || abs(dx) > abs(dy))
                            if (slop && ((handleMouseDrag && inCorner) || c.type == PointerType.Touch)) {
                                if (!dragging && !swiped && !inEdge) fwd = dx < 0f
                                if (instantMode) {
                                    swiped = true
                                    state.lastHandledPointerDownUptimeMs = down.uptimeMillis
                                    c.consume()
                                } else if (!dragging) {
                                    dragging = true
                                    state.lastHandledPointerDownUptimeMs = down.uptimeMillis
                                    scope.launch { if (!state.beginDrag(fwd)) dragging = false }
                                }
                            }
                            if (dragging) {
                                c.consume()
                                if (curl) {
                                    val q = curlPointer(c.position)
                                    val p = curlDragProgress(q.x, leafW, fwd)
                                    scope.launch { state.dragCurl(p, Offset(q.x / leafW, q.y / h), curlTopCorner) }
                                } else {
                                    val p = dragProgress(down.position.x, c.position.x, fwd, w * travelFactor)
                                    scope.launch { state.dragTo(p) }
                                }
                            }
                        }
                        val p = if (curl) curlDragProgress(curlPointer(lastPos).x, leafW, fwd)
                            else dragProgress(down.position.x, lastPos.x, fwd, w * travelFactor)
                        val vx = tracker.calculateVelocity().x
                        val complete = shouldCompleteTurn(p, if (fwd) -vx else vx)
                        when {
                            dragging -> { dragging = false; scope.launch { state.endDrag(complete) } }
                            swiped -> if (complete) launchTurn(fwd)
                            !moved && inEdge && !upConsumed -> launchTurn(fwd) // 没拖动的轻点，只有落在边缘且未被子控件消费才翻页
                        }
                    } finally {
                        // 手势协程被撤（缩放窗口、pointerInput 重启）：没走到 endDrag 就回弹，不留 turning
                        if (dragging) state.abortDrag()
                    }
                }
            }
            .background(paper)
    ) {
        val pos = state.shownPosition
        val n = state.pageCount
        val fwd = state.forward
        val turning = state.turning
        val c = state.columns

        if (style == PageTurnStyle.Curl && turning && c <= 2) {
            // 卷页只在绘制阶段读进度，跟手拖动不触发重组
            CurlLayers(state, if (fwd) pos else state.previousOf(pos), c, n, pageW, paper, shadow, showThrough, page)
            return@Box
        }
        val t = state.progress

        if (style == PageTurnStyle.Slide || (style != PageTurnStyle.None && c > 2)) {
            val sgn = if (fwd) -1f else 1f
            val step = if (turning) (if (fwd) pos + c else pos - c) else pos
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = if (turning) sgn * t * widthPx else 0f }) {
                SpreadBox(pos, c, n, paper, shadow, page)
            }
            if (turning) Box(Modifier.fillMaxSize().graphicsLayer { translationX = -sgn * (1f - t) * widthPx }) {
                SpreadBox(step, c, n, paper, shadow, page)
            }
            return@Box
        }

        // 底层：翻起期间，两侧露出的是翻完之后的另一张
        if (c == 2) {
            val l = if (turning && !fwd) pos - 2 else pos
            val r = if (turning && fwd) pos + 3 else pos + 1
            PageBox(l, n, pageW, paper, Alignment.CenterStart, page)
            PageBox(r, n, pageW, paper, Alignment.CenterEnd, page)
            Box(
                Modifier.align(Alignment.Center).width(28.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, shadow.copy(alpha = 0.16f), Color.Transparent)))
            )
        } else {
            PageBox(if (turning) (if (fwd) pos + 1 else pos - 1) else pos, n, pageW, paper, Alignment.CenterStart, page)
        }

        if (turning) {
            val angle = leafAngle(t, fwd, c)
            val back = showingBack(t, c)
            val side = if (c == 2 && fwd) Alignment.CenterEnd else Alignment.CenterStart
            val origin = if (c == 2 && !fwd) TransformOrigin(1f, 0.5f) else TransformOrigin(0f, 0.5f)
            val frontIdx = if (c == 2 && fwd) pos + 1 else pos
            val backIdx = if (fwd) pos + 2 else pos - 1
            val shown = if (back) backIdx else frontIdx
            val dens = density.density
            Box(Modifier.align(side).width(pageW).fillMaxHeight().graphicsLayer {
                // 整张纸绕书脊转到另一侧；背面朝向只在内层校正，位置不动
                rotationY = angle
                transformOrigin = origin
                cameraDistance = 128f * dens
                alpha = if (c == 1) 1f - t * 0.7f else 1f
            }.background(paper)) {
                Box(Modifier.fillMaxSize().graphicsLayer { rotationY = if (back) 180f else 0f }) {
                    if (shown in 0 until n) page(shown)
                    // 背面隐约透字：纸的反面，字淡一层
                    if (back) Box(Modifier.fillMaxSize().background(paper.copy(alpha = if (showThrough) 0.78f else 1f)))
                }
                // 翻起页自己的明暗：越接近垂直越暗
                Box(Modifier.fillMaxSize().background(shadow.copy(alpha = 0.12f * castShadowAlpha(t))))
            }
            if (c == 2) {
                // 翻起页投在底下那页上的影
                val a = 0.24f * castShadowAlpha(t)
                val onSide = if (fwd) Alignment.CenterEnd else Alignment.CenterStart
                val colors = if (fwd) listOf(shadow.copy(alpha = a), Color.Transparent) else listOf(Color.Transparent, shadow.copy(alpha = a))
                Box(Modifier.align(onSide).width(pageW).fillMaxHeight().background(Brush.horizontalGradient(colors)))
            }
        }
    }
}

/**
 * 卷页的几层，从下往上：翻完后露出的页、留在原处的那部分纸、书脊阴影、掀起处投在下一页上的影、
 * 翻过来的那片纸（背面）。[base] 是被卷起那张所在的位置：往前翻是当前位置，往回翻是上一个位置、进度倒着用。
 */
@Composable
private fun BoxScope.CurlLayers(
    state: PageTurnState, base: Int, c: Int, n: Int, pageW: Dp,
    paper: Color, shadow: Color, showThrough: Boolean, page: @Composable (Int) -> Unit,
) {
    val double = c == 2
    val leafSide = if (double) Alignment.CenterEnd else Alignment.CenterStart
    val frontIdx = if (double) base + 1 else base
    val backIdx = if (double) base + 2 else base
    val minReach = with(LocalDensity.current) { 24.dp.toPx() }
    fun foldProgress() = if (state.forward) state.progress else 1f - state.progress
    fun fold(size: Size): CurlFold {
        val pt = curlPointAt(foldProgress(), size.width, size.height, state.curlTop, state.curlAnchor)
        return curlFold(pt, size.width, size.height, state.curlTop)
    }

    if (double) {
        PageBox(base, n, pageW, paper, Alignment.CenterStart, page)
        PageBox(base + 3, n, pageW, paper, Alignment.CenterEnd, page)
    } else {
        PageBox(base + 1, n, pageW, paper, Alignment.CenterStart, page)
    }
    // 留在原处的那部分纸
    Box(Modifier.align(leafSide).width(pageW).fillMaxHeight().drawWithContent {
        val g = fold(size)
        if (g.front.size >= 3) clipPath(g.front.toPath()) { this@drawWithContent.drawContent() }
    }.background(paper)) {
        if (frontIdx in 0 until n) page(frontIdx)
    }
    if (double) Box(
        Modifier.align(Alignment.Center).width(28.dp).fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color.Transparent, shadow.copy(alpha = 0.16f), Color.Transparent)))
    )
    // 纸掀起来的地方，下一页靠折线处压着一道影
    Box(Modifier.align(leafSide).width(pageW).fillMaxHeight().drawBehind {
        val g = fold(size)
        if (g.depth <= 0f || g.removed.size < 3) return@drawBehind
        val reach = g.depth.coerceIn(minReach, size.width * 0.3f)
        val a = 0.30f * (g.depth / minReach).coerceAtMost(1f)
        clipPath(g.removed.toPath()) {
            drawRect(Brush.linearGradient(listOf(shadow.copy(alpha = a), Color.Transparent), start = g.mid, end = g.mid + g.normal * reach))
        }
    })
    // 翻过来的那片纸：背面内容沿折线镜像，靠折线处暗一点、往外渐亮，像纸在折痕处弯过来
    Box(Modifier.align(leafSide).width(pageW).fillMaxHeight().drawWithContent {
        val g = fold(size)
        if (g.flap.size < 3) return@drawWithContent
        val p = foldProgress()
        // 双页：背面是下一张的左页，落地时要和静止的样子一样，所以越翻越清楚；单页：同一张纸的反面，字隐约透出
        val wash = if (double) (if (showThrough) 0.5f else 1f) * (1f - p) else if (showThrough) 0.82f else 1f
        val reach = g.depth.coerceIn(minReach, size.width * 0.45f)
        clipPath(g.flap.toPath()) {
            withTransform({ transform(curlBackMatrix(g.mid, g.normal, size.width, otherPage = double)) }) {
                this@drawWithContent.drawContent()
                if (wash > 0f) drawRect(paper.copy(alpha = wash))
            }
            drawRect(Brush.linearGradient(
                0f to shadow.copy(alpha = 0.18f), 0.35f to shadow.copy(alpha = 0.04f), 1f to Color.Transparent,
                start = g.mid, end = g.mid - g.normal * reach,
            ))
        }
    }.background(paper)) {
        if (backIdx in 0 until n) page(backIdx)
    }
}

private fun List<Offset>.toPath(): Path {
    val pts = this
    return Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        close()
    }
}

@Composable
private fun SpreadBox(first: Int, c: Int, n: Int, paper: Color, shadow: Color, page: @Composable (Int) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            for (i in 0 until c) {
                Box(Modifier.weight(1f).fillMaxHeight().background(paper)) {
                    if (first + i in 0 until n) page(first + i)
                }
            }
        }
        if (c == 2) Box(Modifier.align(Alignment.Center).width(28.dp).fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color.Transparent, shadow.copy(alpha = 0.16f), Color.Transparent))))
    }
}

@Composable
private fun PageBox(
    index: Int, pageCount: Int, width: Dp, paper: Color,
    align: Alignment, page: @Composable (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = align) {
        Box(Modifier.width(width).fillMaxHeight().background(paper)) {
            if (index in 0 until pageCount) page(index)
        }
    }
}
