package com.coach.ui.v7

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.*
import com.coach.ui.theme.CoachTheme

@Immutable
data class MarginNote(val id: String, val number: String, val label: String, val text: String, val lecture: Boolean = false, val personal: Boolean = false)
class MarginAnchors {
    val positions = mutableStateMapOf<String, Float>()
}
val LocalMarginAnchors = staticCompositionLocalOf<MarginAnchors?> { null }

/** One forward pass: align to the marked line, and let later cards flow down without overlap. */
fun marginOffsets(anchors: List<Int?>, heights: List<Int>, gap: Int, header: Int): List<Int> {
    var cursor = header
    return heights.mapIndexed { index, height ->
        val y = maxOf(cursor, anchors.getOrNull(index) ?: cursor)
        cursor = y + height + gap
        y
    }
}

@Composable
fun MarginNotes(notes: List<MarginNote>, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    val anchors = LocalMarginAnchors.current
    var origin by remember { mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = with(density) { 12.dp.roundToPx() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).onGloballyPositioned { origin = it.positionInRoot().y }) {
        Text("页边", style = MaterialTheme.typography.labelMedium, color = CoachTheme.colors.fgSecondary)
        Spacer(Modifier.height(8.dp))
        Layout(content = {
            notes.forEach { note ->
                PaperCard {
                    Text((if (note.lecture) "★ " else "") + note.number + "  " + note.label,
                        style = MaterialTheme.typography.labelMedium, color = CoachTheme.colors.fgSecondary)
                    Text(note.text, style = MaterialTheme.typography.bodyMedium,
                        fontFamily = if (note.personal) LocalV7Fonts.current.note else LocalV7Fonts.current.reading)
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
            val children = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
            val desired = notes.map { note -> anchors?.positions?.get(note.id)?.let { (it - origin - 26.dp.toPx()).toInt().coerceAtLeast(0) } }
            val ys = marginOffsets(desired, children.map { it.height }, gap, 0)
            val height = children.lastOrNull()?.let { ys.last() + it.height } ?: 0
            layout(constraints.maxWidth, height) { children.forEachIndexed { i, placeable -> placeable.placeRelative(0, ys[i]) } }
        }
        Spacer(Modifier.height(12.dp))
        extra()
    }
}

/** Existing Markdown footnotes become superscripts; strong spans get the approved quiet emphasis dots. */
@Composable
fun ReadingText(text: AnnotatedString, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val anchors = LocalMarginAnchors.current
    val prefix = LocalFootnotePrefix.current
    val numbers = LocalV7Fonts.current.numbers
    val secondary = CoachTheme.colors.fgSecondary
    val marked = remember(text, numbers, secondary, prefix) {
        val regex = Regex("\\[\\^([A-Za-z0-9_-]+)]")
        val b = AnnotatedString.Builder()
        var from = 0
        regex.findAll(text.text).forEach { match ->
            b.append(text.subSequence(from, match.range.first))
            val start = b.length
            val id = match.groupValues[1]
            b.append(id)
            b.addStyle(SpanStyle(fontFamily = numbers, fontSize = .6.em, baselineShift = BaselineShift.Superscript, color = secondary), start, b.length)
            b.addStringAnnotation("margin", prefix + id, start, b.length)
            from = match.range.last + 1
        }
        b.append(text.subSequence(from, text.length))
        b.toAnnotatedString()
    }
    var result by remember { mutableStateOf<TextLayoutResult?>(null) }
    var y by remember { mutableFloatStateOf(0f) }
    val emphasized = remember(marked) { marked.spanStyles.filter { it.item.fontWeight == FontWeight.Bold || it.item.fontWeight == FontWeight.SemiBold } }
    fun updateAnchors() { result?.let { layout ->
        marked.getStringAnnotations("margin", 0, marked.length).forEach { a ->
            anchors?.positions?.set(a.item, y + layout.getLineTop(layout.getLineForOffset(a.start)))
        }
    } }
    Text(marked, modifier.fillMaxWidth().onGloballyPositioned { y = it.positionInRoot().y; updateAnchors() }.drawWithContent {
        drawContent()
        result?.let { layout ->
            emphasized.forEach { span ->
                for (i in span.start until span.end.coerceAtMost(marked.length)) {
                    if (!marked[i].isWhitespace() && marked[i].isLetterOrDigit()) {
                        val box = layout.getBoundingBox(i)
                        val line = layout.getLineForOffset(i)
                        drawCircle(secondary, radius = 1.dp.toPx(), center = Offset(box.center.x, layout.getLineBaseline(line) + 5.dp.toPx()))
                    }
                }
            }
        }
    }, style = style, onTextLayout = { result = it; updateAnchors() })
}

/** Definitions are supplied by existing content; no citation, quotation or lecture emphasis is invented. */
fun footnotes(text: String): List<MarginNote> = Regex("(?m)^\\[\\^([A-Za-z0-9_-]+)]:[ \\t]*(.+)$")
    .findAll(text).map { MarginNote(it.groupValues[1], it.groupValues[1], "出处", it.groupValues[2]) }.toList()
fun withoutFootnoteDefinitions(text: String): String = text.replace(Regex("(?m)^\\[\\^[A-Za-z0-9_-]+]:[^\\n]*(?:\\n|$)"), "")
