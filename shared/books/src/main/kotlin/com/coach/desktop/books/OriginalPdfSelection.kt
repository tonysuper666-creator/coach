package com.coach.desktop.books

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.coach.domain.reader.ReaderRect
import com.coach.domain.reader.ReaderTextRange
import com.coach.domain.reader.ReaderTextUnit

internal val OriginalPdfSelectionRange = SemanticsPropertyKey<ReaderTextRange>("OriginalPdfSelectionRange")

/** The overlay and the bitmap have identical bounds. Display boxes have already been rotated. */
@Composable
internal fun BoxScope.OriginalPdfSelection(
    unit: ReaderTextUnit,
    text: String,
    selection: ReaderTextRange?,
    highlights: List<ReaderTextRange>,
    selectionColor: Color,
    highlightColor: Color,
    onSelection: (ReaderTextRange?) -> Unit,
    layerTag: String = "original-pdf-text-layer",
) {
    val geometry = remember(unit, text) { PdfSelectionGeometry(unit, text) }
    val marked = remember(geometry, highlights) { highlights.flatMap(geometry::verifiedBoxes) }
    val selected = remember(geometry, selection) { selection?.let(geometry::verifiedBoxes).orEmpty() }
    val update = rememberUpdatedState(onSelection)
    Canvas(Modifier.matchParentSize().testTag(layerTag).semantics {
        if (selected.isNotEmpty() && selection != null) this[OriginalPdfSelectionRange] = selection
    }.pointerHoverIcon(PointerIcon.Text).pointerInput(geometry) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            if (width <= 0 || height <= 0) return@awaitEachGesture
            // Let a press land in the small space around the printed glyph, but not in the margins.
            val start = geometry.nearest(down.position, width, height, 4.dp.toPx()) ?: run {
                update.value(null)
                return@awaitEachGesture
            }
            // The image is in a vertical scroll container. A press on a glyph owns
            // the ensuing drag, while a press in the margin can still pan the page.
            down.consume()
            var last: ReaderTextRange? = null
            var dragging = false
            update.value(null)
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed || !change.pressed) break
                if (!dragging && (change.position - down.position).getDistance() < 2.dp.toPx()) continue
                dragging = true
                change.consume()
                val end = geometry.nearest(change.position, width, height) ?: continue
                val range = ReaderTextRange(minOf(start.startUtf16, end.startUtf16), maxOf(start.endUtf16, end.endUtf16))
                if (range != last) {
                    last = range
                    update.value(range.takeIf { geometry.verifiedBoxes(it).isNotEmpty() })
                }
            }
        }
    }) {
        fun paint(boxes: List<ReaderRect>, color: Color) {
            for (box in boxes) drawRect(
                color,
                Offset((box.x * size.width).toFloat(), (box.y * size.height).toFloat()),
                Size((box.width * size.width).toFloat(), (box.height * size.height).toFloat()),
            )
        }
        paint(marked, highlightColor)
        paint(mergePdfLineBoxes(selected, size.width, size.height), selectionColor)
    }
}

/** Join adjacent glyphs on the same line, leaving distinct columns and lines separate. */
internal fun mergePdfLineBoxes(boxes: List<ReaderRect>, width: Float, height: Float): List<ReaderRect> {
    val lines = mutableListOf<ReaderRect>()
    for (box in boxes) {
        val previous = lines.lastOrNull()
        val sharedHeight = if (previous == null) 0.0 else
            minOf(box.y + box.height, previous.y + previous.height) - maxOf(box.y, previous.y)
        val gap = if (previous == null) 0.0 else
            maxOf(box.x, previous.x) - minOf(box.x + box.width, previous.x + previous.width)
        if (previous != null && sharedHeight > minOf(previous.height, box.height) / 2 &&
            gap * width <= minOf(previous.height, box.height) * height) {
            val left = minOf(previous.x, box.x)
            val top = minOf(previous.y, box.y)
            lines[lines.lastIndex] = ReaderRect(left, top,
                maxOf(previous.x + previous.width, box.x + box.width) - left,
                maxOf(previous.y + previous.height, box.y + box.height) - top)
        } else lines += box
    }
    return lines
}

private class PdfSelectionGeometry(private val unit: ReaderTextUnit, private val text: String) {
    private val glyphs = unit.spans.flatMap { span -> span.geometry.displayBoxes().map { it to span.range } }

    fun verifiedBoxes(range: ReaderTextRange) = PdfTextSelection.boxesForVerifiedRange(unit, text, range)

    fun nearest(position: Offset, width: Float, height: Float, maxDistance: Float = Float.POSITIVE_INFINITY): ReaderTextRange? {
        var distance = maxDistance.toDouble() * maxDistance
        var hit: ReaderTextRange? = null
        for ((box, range) in glyphs) {
            val dx = maxOf(box.x * width - position.x, 0.0, position.x - (box.x + box.width) * width)
            val dy = maxOf(box.y * height - position.y, 0.0, position.y - (box.y + box.height) * height)
            val squared = dx * dx + dy * dy
            if (squared < distance) {
                if (squared == 0.0) return range
                distance = squared
                hit = range
            }
        }
        return hit
    }
}
