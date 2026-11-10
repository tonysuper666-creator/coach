package com.coach.ui.reader.v2

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

data class ReaderViewport(val pageWidth: Float, val pageHeight: Float, val x: Float, val y: Float,
    val contentHeight: Float, val contentWidth: Float)

// A renderer allocation bound, independent of any claim about native glyph size.
private const val MaximumRenderedSidePx = 16384.0
// Unknown type metrics permit bounded geometry only, never unconstrained column zoom.
private const val UnknownPageLongSideDp = 1200.0

fun readerViewport(page: ReaderPageLayout, region: ReaderRegion, width: Float, height: Float, fitHeight: Boolean,
    minimumScale: Float = 0f, maximumScale: Float = Float.MAX_VALUE, horizontalPadding: Float = 0f): ReaderViewport {
    require(width.isFinite() && width > 0 && height.isFinite() && height > 0 &&
        minimumScale.isFinite() && minimumScale >= 0 && maximumScale.isFinite() && maximumScale >= minimumScale &&
        horizontalPadding.isFinite() && horizontalPadding >= 0)
    // Double intermediates prevent valid but extreme metadata from overflowing
    // before the allocation ceiling is applied. No infinite scale enters a plan.
    val ceiling = minOf(maximumScale.toDouble(), MaximumRenderedSidePx / maxOf(page.width, page.height))
    val scaleWidth = (width - 2.0 * horizontalPadding).coerceAtLeast(1.0) / (page.width.toDouble() * region.width)
    val scale = (if (fitHeight) minOf(scaleWidth, height / (page.height.toDouble() * region.height)) else scaleWidth)
        .coerceIn(minOf(minimumScale.toDouble(), ceiling), ceiling)
    val pw = page.width * scale
    val ph = page.height * scale
    val contentWidth = maxOf(width.toDouble(), pw * region.width + 2.0 * horizontalPadding)
    return ReaderViewport(pw.toFloat(), ph.toFloat(), ((contentWidth - pw * region.width) / 2 - region.left * pw).toFloat(),
        (maxOf(0.0, (height - ph * region.height) / 2) - region.top * ph).toFloat(),
        maxOf(height.toDouble(), ph * region.height).toFloat(), contentWidth.toFloat())
}

data class ReaderPagePlan(val region: ReaderRegion, val viewport: ReaderViewport, val columnId: Int?, val bodySizePx: Float?)

/** Reading starts with width-fit trimmed pages. Measured body text stays in
 * 16..22sp, including system fontScale. Too-small pages enter column focus;
 * selected columns/source excerpts use the same ceiling and centered margins.
 * Missing/unusable native metrics stay unknown: use trimmed pages (compact may
 * focus a column), but cap the full page long side at 1200dp in every reading
 * path. That geometric fallback cannot prove 16..22sp readability.
 * Only explicit overview fits height without the reading font bounds. */
fun readerPagePlan(page: ReaderPageLayout, columnId: Int?, compact: Boolean, source: NativeSourceTarget?,
    overview: Boolean, widthPx: Float, heightPx: Float, density: Float, minimumBodyPx: Float,
    maximumBodyPx: Float = minimumBodyPx * 22f / 16f): ReaderPagePlan {
    require(density.isFinite() && density > 0 && minimumBodyPx.isFinite() && minimumBodyPx > 0 &&
        maximumBodyPx.isFinite() && maximumBodyPx >= minimumBodyPx)
    val bodyFont = page.bodyFontSize?.takeIf { it.isFinite() && it > 0 && it <= minOf(page.width, page.height) &&
        maximumBodyPx.toDouble() / it <= Float.MAX_VALUE &&
        maximumBodyPx.toDouble() / it * maxOf(page.width, page.height) <= MaximumRenderedSidePx }
    if (overview && source == null) {
        val v = readerViewport(page, ReaderRegion.Full, widthPx, heightPx, true)
        return ReaderPagePlan(ReaderRegion.Full, v, null, bodyFont?.let { (it.toDouble() * v.pageWidth / page.width).toFloat() })
    }
    val boxes = source?.boxes?.reduce(ReaderRegion::union)
    val sourceColumn = boxes?.let { box -> page.columns.firstOrNull {
        val r = it.region; r.left <= box.left && r.right >= box.right && r.top <= box.top && r.bottom >= box.bottom
    } }
    var column = if (source != null) sourceColumn else page.column(columnId)
    val fullWidth = readerViewport(page, page.trim, widthPx, heightPx, false)
    val fullWidthBodyPx = bodyFont?.let { (it.toDouble() * fullWidth.pageWidth / page.width).toFloat() }
    val tooSmall = fullWidthBodyPx?.let { it < minimumBodyPx } ?: compact
    if (source == null && column == null && tooSmall) {
        column = page.columns.firstOrNull()
    }
    val region = boxes?.let { sourceColumn?.region ?: page.trim.union(it) } ?: column?.region ?: page.trim
    val minimumScale = bodyFont?.let { minimumBodyPx / it } ?: 0f
    val maximumScale = bodyFont?.let { maximumBodyPx / it } ?: (UnknownPageLongSideDp * density /
        maxOf(page.width, page.height)).coerceAtMost(Float.MAX_VALUE.toDouble()).toFloat()
    val padding = if (source != null || column != null || tooSmall) 16f * density else 0f
    val v = readerViewport(page, region, widthPx, heightPx, false, minimumScale, maximumScale, padding)
    return ReaderPagePlan(region, v, column?.id, bodyFont?.let { (it.toDouble() * v.pageWidth / page.width).toFloat() })
}

/** Renders a full MediaBox page once, applying the same scale/translation to
 * its native highlight. Zoom never changes stored anchor coordinates. */
@Composable
fun ReaderV2Page(
    layout: ReaderPageLayout,
    columnId: Int?,
    compact: Boolean,
    source: NativeSourceTarget?,
    flash: Int,
    onColumn: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    overview: Boolean = false,
    onPlan: (ReaderPagePlan) -> Unit = {},
    pageContent: @Composable (Int) -> Unit,
) {
    val opacity = remember { Animatable(.18f) }
    val density = LocalDensity.current
    LaunchedEffect(source, flash) { if (source != null) { opacity.snapTo(.65f); opacity.animateTo(.18f, tween(850)) } }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().testTag("reader-page-${layout.pageIndex0}")) {
        val plan = readerPagePlan(layout, columnId, compact, source, overview, constraints.maxWidth.toFloat(),
            constraints.maxHeight.toFloat(), density.density, with(density) { 16.sp.toPx() }, with(density) { 22.sp.toPx() })
        val region = plan.region
        val v = plan.viewport
        SideEffect { onPlan(plan) }
        val scroll = rememberScrollState()
        val horizontal = rememberScrollState()
        LaunchedEffect(layout.pageIndex0, columnId, source, flash, v) {
            // Normal reading starts at the first measured column; the page header
            // remains reachable by scrolling up, and overview starts at page top.
            val top = source?.boxes?.minOf { it.top }
                ?: if (!overview) layout.columns.minOfOrNull { it.region.top } ?: region.top else region.top
            scroll.scrollTo((top * v.pageHeight + v.y - if (source != null) constraints.maxHeight * .35f else 0f)
                .roundToInt().coerceAtLeast(0))
            val center = source?.boxes?.reduce(ReaderRegion::union)?.let { (it.left + it.right) / 2 }
            horizontal.scrollTo(if (center == null) 0 else (center * v.pageWidth + v.x - constraints.maxWidth / 2f).roundToInt().coerceAtLeast(0))
        }
        var drag by remember { mutableStateOf(0f) }
        Layout(content = {
            Box(Modifier.fillMaxSize().drawWithContent {
                clipRect(region.left * size.width, region.top * size.height, region.right * size.width, region.bottom * size.height) { this@drawWithContent.drawContent() }
            }
                .pointerInput(layout, v) {
                    detectTapGestures(onDoubleTap = { point ->
                        val x = point.x / v.pageWidth
                        val y = point.y / v.pageHeight
                        onColumn(layout.columns.firstOrNull { x in it.region.left..it.region.right && y in it.region.top..it.region.bottom }?.id)
                    })
                }) {
                pageContent(layout.pageIndex0)
                if (source != null) Canvas(Modifier.fillMaxSize().testTag("reader-source-highlight")) {
                    for (b in source.boxes) {
                        val offset = Offset(b.left * size.width, b.top * size.height)
                        val area = Size(b.width * size.width, b.height * size.height)
                        drawRect(Color(0xFFFFCF55).copy(alpha = opacity.value), offset, area)
                        drawRect(Color(0xFF8C4B13), offset, area, style = Stroke(2f))
                    }
                }
            }
        }, modifier = Modifier.fillMaxWidth().verticalScroll(scroll).horizontalScroll(horizontal).pointerInput(layout, columnId, plan.columnId, v.contentWidth) {
            if (v.contentWidth > constraints.maxWidth + 1) return@pointerInput // pan large full-width figures instead
            detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                if (drag < -48) onColumn(layout.nextColumn(columnId ?: plan.columnId))
                if (drag > 48) {
                    val index = layout.columns.indexOfFirst { it.id == (columnId ?: plan.columnId) }
                    onColumn(layout.columns.getOrNull((index - 1).coerceAtLeast(0))?.id)
                }
            }) { change, amount -> drag += amount; change.consume() }
        }) { children, limits ->
            val child = children.single().measure(Constraints.fixed(v.pageWidth.roundToInt().coerceAtLeast(1), v.pageHeight.roundToInt().coerceAtLeast(1)))
            layout(v.contentWidth.roundToInt().coerceAtLeast(1), v.contentHeight.roundToInt().coerceAtLeast(1)) {
                child.place(v.x.roundToInt(), v.y.roundToInt())
            }
        }
    }
}
