package com.coach.ui.v7

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.sp

@Composable
internal fun VerdictIcon(correct: Boolean, modifier: Modifier, color: Color) {
    Canvas(modifier.semantics { text = AnnotatedString(if (correct) "✓" else "✗") }) {
        fun line(x: Float, y: Float, endX: Float, endY: Float) = drawLine(color,
            Offset(size.width * x, size.height * y), Offset(size.width * endX, size.height * endY),
            size.width / 9, StrokeCap.Round)
        if (correct) { line(.14f, .52f, .4f, .78f); line(.4f, .78f, .88f, .2f) }
        else { line(.2f, .2f, .8f, .8f); line(.2f, .8f, .8f, .2f) }
    }
}

/** Keep the accessible label while drawing the mark without a font fallback. */
@Composable
internal fun PassedLabel(modifier: Modifier, style: TextStyle, color: Color, label: String = com.coach.client.KpStatus.PASSED.label, checks: Int = 1) {
    // 「稳了」画双勾，和中央映射里的符号一致。
    val icon = InlineTextContent(Placeholder(12.sp, 12.sp, PlaceholderVerticalAlign.Center)) { VerdictIcon(true, Modifier.fillMaxSize(), color) }
    Text(buildAnnotatedString { repeat(checks) { appendInlineContent("passed$it", "✓") }; append(" $label") }, modifier,
        color = color, style = style, inlineContent = (0 until checks).associate { "passed$it" to icon })
}
