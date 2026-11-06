package com.coach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.coach.ui.math.numbers

/**
 * 卷面配图（供需曲线、坐标轴这类线稿）：只画线、折线、多边形、路径、矩形、圆和文字，不认识的元素整枝跳过。
 * 黑色与 currentColor 跟随正文颜色，深色模式下也看得清。整张读不懂时退回一句说明，不让卷面空着。
 */
@Composable
internal fun FigureSvg(svg: String, modifier: Modifier = Modifier) {
    val figure = remember(svg) { runCatching { Figure.parse(svg) }.getOrNull() }
    if (figure == null) {
        Text("（本题配图无法显示，请按题干文字作答）", modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        return
    }
    val ink = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = maxWidth.coerceAtMost(figure.box[2].coerceAtLeast(240f).let { androidx.compose.ui.unit.Dp(it * 1.4f) })
        Canvas(Modifier.width(width).aspectRatio(figure.box[2] / figure.box[3])) {
            val s = size.width / figure.box[2]
            withTransform({ scale(s, s, Offset.Zero); translate(-figure.box[0], -figure.box[1]) }) {
                figure.root.children.forEach { draw(it, Paint(ink, null, 1f, 16f), ink, measurer) }
            }
        }
    }
}

private data class Paint(val fill: Color?, val stroke: Color?, val strokeWidth: Float, val fontSize: Float, val dash: FloatArray? = null, val anchor: String = "start")

private class Figure(val root: Node, val box: List<Float>) {
    class Node(val tag: String, val attrs: Map<String, String>, val children: MutableList<Node> = mutableListOf(), var text: String = "")
    companion object {
        private val tokens = Regex("<!--[\\s\\S]*?-->|<\\?[\\s\\S]*?\\?>|<[^>]+>|[^<]+")
        private val tagName = Regex("^</?\\s*([A-Za-z][\\w:-]*)")
        private val attribute = Regex("([\\w:-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
        fun parse(svg: String): Figure {
            require(!svg.contains("<!DOCTYPE", ignoreCase = true) && !svg.contains("<!ENTITY", ignoreCase = true))
            val stack = mutableListOf<Node>(); var root: Node? = null
            for (token in tokens.findAll(svg)) {
                val v = token.value
                if (v.startsWith("<!--") || v.startsWith("<?")) continue
                if (v.startsWith("</")) { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex); continue }
                if (v.startsWith("<")) {
                    val tag = tagName.find(v)?.groupValues?.get(1) ?: error("bad tag")
                    val attrs = attribute.findAll(v).associate { it.groupValues[1] to (it.groupValues[3].ifEmpty { it.groupValues[4] }).decoded() }
                    val node = Node(tag, styleMerged(attrs))
                    if (stack.isEmpty()) { require(tag == "svg" && root == null); root = node } else stack.last().children += node
                    if (!v.endsWith("/>")) stack += node
                } else if (stack.isNotEmpty()) stack.last().text += v.decoded()
            }
            val r = requireNotNull(root)
            val box = r.attrs["viewBox"]?.let(::numbers)
                ?: listOf(0f, 0f, r.attrs["width"]?.removeSuffix("px")?.toFloat() ?: error("no size"), r.attrs["height"]?.removeSuffix("px")?.toFloat() ?: error("no size"))
            require(box.size == 4 && box[2] > 0 && box[3] > 0)
            return Figure(r, box)
        }
        private fun styleMerged(attrs: Map<String, String>): Map<String, String> {
            val style = attrs["style"] ?: return attrs
            return attrs + style.split(';').mapNotNull { d -> d.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
        }
        private fun String.decoded() = replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
}

private fun color(raw: String?, ink: Color, inherited: Color?): Color? = when {
    raw == null -> inherited
    raw == "none" || raw == "transparent" -> null
    raw == "currentColor" || raw == "black" || raw == "#000" || raw == "#000000" -> ink
    raw.startsWith("#") -> {
        val hex = raw.drop(1).let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
        hex.toLongOrNull(16)?.let { Color((0xff000000L or it).toInt()) } ?: inherited
    }
    else -> when (raw.lowercase()) {
        "red" -> Color(0xFFD32F2F); "blue" -> Color(0xFF1976D2); "green" -> Color(0xFF388E3C); "orange" -> Color(0xFFF57C00)
        "gray", "grey" -> Color.Gray; "white" -> Color.White
        else -> inherited
    }
}

private fun DrawScope.draw(n: Figure.Node, parent: Paint, ink: Color, measurer: TextMeasurer) {
    if (n.tag !in setOf("g", "line", "polyline", "polygon", "path", "rect", "circle", "ellipse", "text")) return
    fun f(key: String, default: Float = 0f) = n.attrs[key]?.removeSuffix("px")?.toFloatOrNull() ?: default
    val paint = Paint(
        fill = color(n.attrs["fill"], ink, parent.fill),
        stroke = color(n.attrs["stroke"], ink, parent.stroke),
        strokeWidth = f("stroke-width", parent.strokeWidth),
        fontSize = f("font-size", parent.fontSize),
        dash = n.attrs["stroke-dasharray"]?.takeIf { it != "none" }?.let { numbers(it).toFloatArray() } ?: parent.dash,
        anchor = n.attrs["text-anchor"] ?: parent.anchor,
    )
    val transform = n.attrs["transform"].orEmpty()
    withTransform({
        Regex("(translate|scale|rotate)\\s*\\(([^)]*)\\)").findAll(transform).forEach { m ->
            val a = numbers(m.groupValues[2])
            when (m.groupValues[1]) {
                "translate" -> translate(a.getOrElse(0) { 0f }, a.getOrElse(1) { 0f })
                "scale" -> scale(a.getOrElse(0) { 1f }, a.getOrElse(1) { a.getOrElse(0) { 1f } }, Offset.Zero)
                else -> rotate(a.getOrElse(0) { 0f }, Offset(a.getOrElse(1) { 0f }, a.getOrElse(2) { 0f }))
            }
        }
    }) {
        val shape: Path? = when (n.tag) {
            "line" -> Path().apply { moveTo(f("x1"), f("y1")); lineTo(f("x2"), f("y2")) }
            "polyline", "polygon" -> numbers(n.attrs["points"].orEmpty()).chunked(2).filter { it.size == 2 }.let { pts ->
                Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p[0], p[1]) else lineTo(p[0], p[1]) }; if (n.tag == "polygon") close() }
            }
            "path" -> n.attrs["d"]?.let { runCatching { PathParser().parsePathString(it).toPath() }.getOrNull() }
            "rect" -> Path().apply { addRect(androidx.compose.ui.geometry.Rect(Offset(f("x"), f("y")), Size(f("width"), f("height")))) }
            "circle" -> Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(f("cx"), f("cy")), f("r"))) }
            "ellipse" -> Path().apply { addOval(androidx.compose.ui.geometry.Rect(f("cx") - f("rx"), f("cy") - f("ry"), f("cx") + f("rx"), f("cy") + f("ry"))) }
            else -> null
        }
        if (shape != null) {
            // A line has no inside: SVG never fills it, even with the inherited black fill.
            if (n.tag != "line" && n.tag != "polyline") paint.fill?.let { drawPath(shape, it) }
            paint.stroke?.let { drawPath(shape, it, style = Stroke(paint.strokeWidth, pathEffect = paint.dash?.let { d -> PathEffect.dashPathEffect(d) })) }
        }
        if (n.tag == "text") {
            val content = (n.text + n.children.joinToString("") { it.text }).trim()
            if (content.isNotEmpty()) {
                val layout = measurer.measure(content, TextStyle(fontSize = (paint.fontSize / density / fontScale).sp, color = paint.fill ?: ink), softWrap = false)
                val dx = when (paint.anchor) { "middle" -> layout.size.width / 2f; "end" -> layout.size.width.toFloat(); else -> 0f }
                drawText(layout, topLeft = Offset(f("x") - dx, f("y") - layout.firstBaseline))
            }
        } else n.children.forEach { draw(it, paint, ink, measurer) }
    }
}
