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

fun readerViewport(page: ReaderPageLayout, region: ReaderRegion, width: Float, height: Float, fitHeight: Boolean, minimumScale: Float = 0f): ReaderViewport {
    require(width > 0 && height > 0)
    val scaleWidth = width / (page.width * region.width)
    val scale = maxOf(minimumScale, if (fitHeight) minOf(scaleWidth, height / (page.height * region.height)) else scaleWidth)
    val pw = page.width * scale
    val ph = page.height * scale
    val contentWidth = maxOf(width, pw * region.width)
    return ReaderViewport(pw, ph, (contentWidth - pw * region.width) / 2 - region.left * pw,
        maxOf(0f, (height - ph * region.height) / 2) - region.top * ph, maxOf(height, ph * region.height), contentWidth)
}

data class ReaderPagePlan(val region: ReaderRegion, val viewport: ReaderViewport, val columnId: Int?, val bodySizePx: Float?)

/** Decide after chrome, hinge reservation and pane controls have been measured.
 * Explicit overview is a thumbnail. Reading requires a measured 16sp body floor;
 * unknown fonts conservatively use width-fit without claiming a measured pass. */
fun readerPagePlan(page: ReaderPageLayout, columnId: Int?, compact: Boolean, source: NativeSourceTarget?,
    overview: Boolean, widthPx: Float, heightPx: Float, density: Float, minimumBodyPx: Float): ReaderPagePlan {
    require(density > 0 && minimumBodyPx > 0)
    val boxes = source?.boxes?.reduce(ReaderRegion::union)
    val sourceColumn = boxes?.let { box -> page.columns.firstOrNull {
        val r = it.region; r.left <= box.left && r.right >= box.right && r.top <= box.top && r.bottom >= box.bottom
    } }
    var column = if (source != null) sourceColumn else if (overview) null else page.column(columnId)
    var region = boxes?.let { sourceColumn?.region ?: page.trim.union(it) }
        ?: if (overview) ReaderRegion.Full else column?.region ?: page.trim
    val fitted = readerViewport(page, region, widthPx, heightPx, true)
    val fittedBodyPx = page.bodyFontSize?.let { it * fitted.pageWidth / page.width }
    val widthReading = source != null || !overview && (compact || heightPx / density < 480f || columnId != null ||
        fittedBodyPx == null || fittedBodyPx < minimumBodyPx)
    if (source == null && !overview && widthReading && column == null) {
        column = page.columns.firstOrNull()
        region = column?.region ?: page.trim
    }
    val minimumScale = if (!overview || source != null) page.bodyFontSize?.let { minimumBodyPx / it } ?: 0f else 0f
    val v = readerViewport(page, region, widthPx, heightPx, !widthReading, minimumScale)
    return ReaderPagePlan(region, v, column?.id, page.bodyFontSize?.let { it * v.pageWidth / page.width })
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
            constraints.maxHeight.toFloat(), density.density, with(density) { 16.sp.toPx() })
        val region = plan.region
        val v = plan.viewport
        SideEffect { onPlan(plan) }
        val scroll = rememberScrollState()
        val horizontal = rememberScrollState()
        LaunchedEffect(layout.pageIndex0, columnId, source, flash, v) {
            val top = source?.boxes?.minOf { it.top } ?: region.top
            scroll.scrollTo(if (source == null) 0 else (top * v.pageHeight + v.y - constraints.maxHeight * .35f).roundToInt().coerceAtLeast(0))
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
