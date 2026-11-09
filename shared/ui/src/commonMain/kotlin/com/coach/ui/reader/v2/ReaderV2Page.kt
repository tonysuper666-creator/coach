package com.coach.ui.reader.v2

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

data class ReaderViewport(val pageWidth: Float, val pageHeight: Float, val x: Float, val y: Float, val contentHeight: Float)

fun readerViewport(page: ReaderPageLayout, region: ReaderRegion, width: Float, height: Float, fitHeight: Boolean): ReaderViewport {
    require(width > 0 && height > 0)
    val scaleWidth = width / (page.width * region.width)
    val scale = if (fitHeight) minOf(scaleWidth, height / (page.height * region.height)) else scaleWidth
    val pw = page.width * scale
    val ph = page.height * scale
    return ReaderViewport(pw, ph, (width - pw * region.width) / 2 - region.left * pw,
        maxOf(0f, (height - ph * region.height) / 2) - region.top * ph, maxOf(height, ph * region.height))
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
    pageContent: @Composable (Int) -> Unit,
) {
    val selected = if (overview) null else layout.column(columnId) ?: if (compact) layout.columns.firstOrNull() else null
    val region = if (source != null) {
        val boxes = source.boxes.reduce(ReaderRegion::union)
        layout.columns.firstOrNull { val r = it.region; r.left <= boxes.left && r.right >= boxes.right && r.top <= boxes.top && r.bottom >= boxes.bottom }?.region
            ?: layout.trim.union(boxes)
    } else if (overview) ReaderRegion.Full else selected?.region ?: layout.trim
    val opacity = remember { Animatable(.18f) }
    LaunchedEffect(source, flash) { if (source != null) { opacity.snapTo(.65f); opacity.animateTo(.18f, tween(850)) } }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().testTag("reader-page-${layout.pageIndex0}")) {
        // A source is a sentence-reading task, including a shallow tabletop pane.
        // Fit its column to width and scroll to the quote instead of shrinking it
        // to the available height. Ordinary expanded pages still fit height.
        val v = readerViewport(layout, region, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), source == null && (!compact || overview))
        val scroll = rememberScrollState()
        LaunchedEffect(layout.pageIndex0, columnId, source, flash, v) {
            val top = source?.boxes?.minOf { it.top } ?: region.top
            scroll.scrollTo(if (source == null) 0 else (top * v.pageHeight + v.y - constraints.maxHeight * .35f).roundToInt().coerceAtLeast(0))
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
        }, modifier = Modifier.fillMaxWidth().verticalScroll(scroll).pointerInput(layout, columnId) {
            detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                if (drag < -48) onColumn(layout.nextColumn(columnId ?: selected?.id))
                if (drag > 48) {
                    val index = layout.columns.indexOfFirst { it.id == (columnId ?: selected?.id) }
                    onColumn(layout.columns.getOrNull((index - 1).coerceAtLeast(0))?.id)
                }
            }) { change, amount -> drag += amount; change.consume() }
        }) { children, limits ->
            val child = children.single().measure(Constraints.fixed(v.pageWidth.roundToInt().coerceAtLeast(1), v.pageHeight.roundToInt().coerceAtLeast(1)))
            layout(limits.maxWidth, v.contentHeight.roundToInt().coerceAtLeast(1)) {
                child.place(v.x.roundToInt(), v.y.roundToInt())
            }
        }
    }
}
