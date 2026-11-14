package com.coach.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

private class FeedbackTextLines {
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
    var layout by mutableStateOf<TextLayoutResult?>(null)
}

/** Keep a row whole at the fixed action boundary; its text and scroll extent stay intact. */
internal class LessonFeedbackClipping {
    private val paragraphs = mutableStateListOf<FeedbackTextLines>()

    internal fun Modifier.viewport(scroll: ScrollState, coordinates: LessonFeedbackCoordinates): Modifier = drawWithContent {
        // Coordinates move in place during scrolling. Observe the scroll as well as text reflow.
        scroll.value
        val viewport = coordinates.viewport?.takeIf { it.isAttached }
        var bottom = size.height
        if (viewport != null) {
            val edge = viewport.positionInRoot().y + viewport.size.height
            for (paragraph in paragraphs) {
                val node = paragraph.coordinates?.takeIf { it.isAttached } ?: continue
                val layout = paragraph.layout ?: continue
                val top = node.positionInRoot().y
                for (line in 0 until layout.lineCount) {
                    val lineTop = top + layout.getLineTop(line)
                    val lineBottom = top + layout.getLineBottom(line)
                    if (lineTop < edge && lineBottom > edge + .5f) {
                        bottom = minOf(bottom, lineTop - viewport.positionInRoot().y)
                    }
                }
            }
        }
        clipRect(bottom = bottom.coerceAtLeast(0f)) { this@drawWithContent.drawContent() }
    }

    @Composable
    internal fun textModifier(onLayout: (TextLayoutResult) -> Unit = {}): Pair<Modifier, (TextLayoutResult) -> Unit> {
        val paragraph = remember { FeedbackTextLines() }
        DisposableEffect(paragraph) {
            paragraphs += paragraph
            onDispose { paragraphs -= paragraph }
        }
        return Modifier.onGloballyPositioned { paragraph.coordinates = it } to { layout ->
            paragraph.layout = layout
            onLayout(layout)
        }
    }
}

internal val LocalLessonFeedbackClipping = staticCompositionLocalOf<LessonFeedbackClipping?> { null }

@Composable
internal fun FeedbackText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    val clipping = LocalLessonFeedbackClipping.current
    val tracking = clipping?.textModifier()
    Text(text, modifier.then(tracking?.first ?: Modifier), style = style, color = color,
        fontWeight = fontWeight, onTextLayout = { tracking?.second?.invoke(it) })
}
