package com.coach.desktop.books

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.coach.domain.reader.ReaderRect
import com.coach.domain.reader.ReaderTextRange

internal data class PdfSelectionHandle(val start: Boolean, val anchor: Offset, val center: Offset)

internal fun pdfSelectionHandles(boxes: List<ReaderRect>, width: Float, height: Float, radius: Float): List<PdfSelectionHandle> {
    if (boxes.isEmpty()) return emptyList()
    return listOf(true to boxes.first(), false to boxes.last()).map { (start, box) ->
        val x = (if (start) box.x else box.x + box.width).toFloat() * width
        val y = (box.y + box.height).toFloat() * height
        val anchor = Offset(x, y)
        val center = Offset(x.coerceIn(radius, maxOf(radius, width - radius)),
            (y + radius).coerceIn(radius, maxOf(radius, height - radius)))
        PdfSelectionHandle(start, anchor, center)
    }
}

@Stable
internal class PdfTouchZoom {
    var scale by mutableFloatStateOf(1f)
        private set
    private var origin by mutableStateOf(Offset.Zero)

    fun translation(width: Float, height: Float) = Offset(origin.x * width, origin.y * height)

    fun pagePoint(position: Offset, width: Float, height: Float) =
        (position - translation(width, height)) / scale

    fun transform(previous: Offset, current: Offset, factor: Float, viewport: IntSize) {
        if (!previous.isSpecified || !current.isSpecified || !factor.isFinite()) return
        val width = viewport.width.toFloat()
        val height = viewport.height.toFloat()
        if (width == 0f || height == 0f) return
        val nextScale = (scale * factor).coerceIn(1f, 4f)
        val next = current - pagePoint(previous, width, height) * nextScale
        scale = nextScale
        origin = Offset((next.x / width).coerceIn(1f - nextScale, 0f),
            (next.y / height).coerceIn(1f - nextScale, 0f))
    }
}

private class PdfTouchDrag(
    private val anchor: ReaderTextRange,
    private val handle: PdfSelectionHandle?,
    private val contact: Offset,
) {
    fun rangeAt(geometry: PdfSelectionGeometry, point: Offset, width: Float, height: Float): ReaderTextRange? {
        // Use the glyph adjacent to the handle, even when the finger starts below or beside it.
        val target = if (handle == null) point else handle.anchor + (point - contact)
        val hit = geometry.nearest(target, width, height) ?: return null
        return when (handle?.start) {
            true -> hit.takeIf { it.startUtf16 < anchor.endUtf16 }
                ?.let { ReaderTextRange(it.startUtf16, anchor.endUtf16) }
            false -> hit.takeIf { it.endUtf16 > anchor.startUtf16 }
                ?.let { ReaderTextRange(anchor.startUtf16, it.endUtf16) }
            null -> ReaderTextRange(minOf(hit.startUtf16, anchor.startUtf16), maxOf(hit.endUtf16, anchor.endUtf16))
        }
    }
}

@Composable
internal fun Modifier.originalPdfTouch(
    zoom: PdfTouchZoom,
    geometry: PdfSelectionGeometry?,
    selection: ReaderTextRange?,
    onSelection: (ReaderTextRange?) -> Unit,
): Modifier {
    val currentGeometry by rememberUpdatedState(geometry)
    val currentSelection by rememberUpdatedState(selection)
    val update by rememberUpdatedState(onSelection)
    return pointerInput(zoom) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            if (width == 0f || height == 0f) return@awaitEachGesture

            val glyphs = currentGeometry
            val selected = currentSelection
            val contact = zoom.pagePoint(down.position, width, height)
            val handle = if (glyphs != null && selected != null) {
                val reach = 24.dp.toPx() / zoom.scale
                pdfSelectionHandles(glyphs.verifiedBoxes(selected), width, height, 5.dp.toPx() / zoom.scale)
                    .filter { (it.center - contact).getDistance() <= reach }
                    .minByOrNull { (it.center - contact).getDistanceSquared() }
            } else null
            var drag = if (handle != null && selected != null) PdfTouchDrag(selected, handle, contact) else null
            var reported = selected
            if (drag != null) down.consume()

            val hit = if (drag == null) glyphs?.nearest(contact, width, height, 7.dp.toPx() / zoom.scale) else null
            var pending: PointerEvent? = null
            if (hit != null && glyphs != null) {
                pending = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitPdfMotion(down) }
                if (pending == null) {
                    val word = glyphs.wordAt(hit)
                    if (word != null && glyphs.verifiedBoxes(word).isNotEmpty()) {
                        update(word)
                        reported = word
                        drag = PdfTouchDrag(word, null, contact)
                    }
                }
            }

            var transformed = false
            var moved = false
            while (true) {
                val event = pending ?: awaitPointerEvent(PointerEventPass.Initial)
                pending = null
                val pressed = event.changes.filter { it.pressed }
                val finger = event.changes.firstOrNull { it.id == down.id }
                if (finger != null && (finger.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    moved = true
                }
                when {
                    pressed.size >= 2 -> {
                        drag = null
                        transformed = true
                        zoom.transform(event.calculateCentroid(useCurrent = false), event.calculateCentroid(),
                            event.calculateZoom(), size)
                        event.changes.forEach { it.consume() }
                    }
                    drag != null && glyphs != null && finger != null -> {
                        if (finger.position != finger.previousPosition) {
                            val point = zoom.pagePoint(finger.position, width, height)
                            val range = drag.rangeAt(glyphs, point, width, height)
                            if (range != null && range != reported && glyphs.verifiedBoxes(range).isNotEmpty()) {
                                update(range)
                                reported = range
                            }
                        }
                        finger.consume()
                    }
                    pressed.size == 1 && zoom.scale > 1f && (moved || transformed) -> {
                        val pan = pressed.single()
                        if (pan.previousPressed) zoom.transform(pan.previousPosition, pan.position, 1f, size)
                        transformed = true
                        pan.consume()
                    }
                    transformed -> event.changes.forEach { it.consume() }
                    pressed.isEmpty() && !moved && selected != null && finger?.isConsumed == false -> update(null)
                }
                if (pressed.isEmpty()) break
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitPdfMotion(down: PointerInputChange): PointerEvent {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val finger = event.changes.firstOrNull { it.id == down.id }
        if (finger == null || !finger.pressed || finger.isConsumed || event.changes.count { it.pressed } > 1 ||
            (finger.position - down.position).getDistance() > viewConfiguration.touchSlop) return event
        val final = awaitPointerEvent(PointerEventPass.Final)
        if (finger.isConsumed) return final
    }
}
