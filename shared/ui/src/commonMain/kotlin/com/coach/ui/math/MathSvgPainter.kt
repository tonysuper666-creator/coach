package com.coach.ui.math

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Parsed once per sha + SVG variant. Persistent SVG storage belongs to the message store.
 * The cache is touched from the UI thread only; [prewarm] does the slow parsing on a worker and hands the results back to its caller,
 * so a block that seals with a dozen formulas finds them parsed instead of parsing them inside the frame (TON-1142).
 */
class MathSvgDocument private constructor(val root: SvgNode, val box: List<Float>) {
    val widthEm get() = box[2] / 1000f
    val ascentEm get() = -box[1] / 1000f
    val descentEm get() = (box[1] + box[3]) / 1000f
    companion object {
        private val cache = mutableMapOf<Pair<String, String>, MathSvgDocument>()
        private val tokens = Regex("<[^>]+>|[^<]+")
        private val tagName = Regex("^<([A-Za-z]+)")
        private val attribute = Regex("([\\w:-]+)\\s*=\\s*\"([^\"]*)\"")
        private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")
        private val supportedTags = setOf("svg", "g", "path", "rect", "text")
        fun parse(sha: String, svg: String): MathSvgDocument = cache.getOrPut(sha to svg) { build(svg) }
        /** Parse the formulas that are not cached yet off the UI thread, then publish them; call from the UI thread. Unparseable SVG is left for [parse] to report. */
        suspend fun prewarm(formulas: Collection<Pair<String, String>>) {
            val todo = formulas.distinct().filter { it !in cache }
            if (todo.isEmpty()) return
            val built = withContext(Dispatchers.Default) { todo.map { (_, svg) -> runCatching { build(svg) }.getOrNull() } }
            todo.forEachIndexed { i, key -> built[i]?.let { cache.getOrPut(key) { it } } }
        }
        private fun build(svg: String): MathSvgDocument {
            require(!svg.contains("<!")) { "SVG declarations are unsupported" }
            val stack = mutableListOf<SvgNode>(); var root: SvgNode? = null
            for (token in tokens.findAll(svg)) {
                val value = token.value
                if (value.startsWith("</")) {
                    require(stack.isNotEmpty() && stack.last().tag == value.substring(2, value.length - 1).trim()) { "Unbalanced SVG" }
                    stack.removeAt(stack.lastIndex)
                } else if (value.startsWith('<')) {
                    val tag = tagName.find(value)?.groupValues?.get(1) ?: error("Invalid SVG tag")
                    require(tag in supportedTags) { "Unsupported SVG: $tag" }
                    val attrs = attribute.findAll(value).associate { it.groupValues[1] to decode(it.groupValues[2]) }
                    val node = SvgNode(tag, attrs, path = attrs["d"]?.let { PathParser().parsePathString(it).toPath() })
                    // Validate transforms before exposing a Painter; unsupported output becomes source text.
                    node.transforms()
                    if (stack.isEmpty()) { require(root == null && tag == "svg"); root = node } else stack.last().children += node
                    if (!value.endsWith("/>")) stack += node
                } else if (stack.isNotEmpty()) stack.last().text += decode(value)
                else require(value.isBlank())
            }
            require(stack.isEmpty())
            val parsed = requireNotNull(root)
            val box = numbers(parsed.attrs["viewBox"] ?: error("Missing viewBox"))
            require(box.size == 4 && box[2] > 0 && box[3] > 0 && box.all { it.isFinite() })
            return MathSvgDocument(parsed, box)
        }
        private fun decode(s: String): String = if ('&' !in s) s else entity.replace(s) {
            when (val v = it.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> (if (v.startsWith("#x")) v.drop(2).toInt(16) else v.drop(1).toInt()).toChar().toString()
            }
        }
    }
}

private val numberSeparator = Regex("[\\s,]+")
private val transformCall = Regex("(translate|scale)\\s*\\(([^)]*)\\)")
fun numbers(s: String): List<Float> = s.trim().split(numberSeparator).filter { it.isNotEmpty() }.map { it.toFloat() }
data class SvgNode(val tag: String, val attrs: Map<String, String>, val path: Path? = null, val children: MutableList<SvgNode> = mutableListOf(), var text: String = "") {
    private var parsedTransforms: List<Pair<String, List<Float>>>? = null
    /** Parsed once: drawing asks for it on every node of every redraw. */
    fun transforms(): List<Pair<String, List<Float>>> = parsedTransforms ?: run {
        val source = attrs["transform"].orEmpty()
        require(transformCall.replace(source, "").isBlank()) { "Unsupported SVG transform" }
        transformCall.findAll(source).map {
            val args = numbers(it.groupValues[2]); require(args.size in 1..2)
            it.groupValues[1] to args
        }.toList()
    }.also { parsedTransforms = it }
}

/** Subset painter with platform text fallback; no browser or platform SVG engine. */
class MathSvgPainter(
    val document: MathSvgDocument,
    private val foreground: Color,
    private val measurer: TextMeasurer,
    private val emPx: Float,
    private val baselinePx: Float? = null,
    private val paddedHeightPx: Float? = null,
) : Painter() {
    override val intrinsicSize = Size(document.widthEm * emPx, paddedHeightPx ?: document.box[3] / 1000f * emPx)
    override fun DrawScope.onDraw() {
        val canvas = drawContext.canvas
        canvas.save()
        val scale = emPx / 1000f
        canvas.translate(-document.box[0] * scale, baselinePx ?: -document.box[1] * scale)
        canvas.scale(scale, scale)
        drawNode(document.root, foreground, true)
        canvas.restore()
    }
    private fun color(raw: String?, inherited: Color): Color = when {
        raw == null || raw == "currentColor" -> inherited
        raw == "none" -> Color.Transparent
        raw.startsWith('#') -> {
            val hex = raw.drop(1).let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
            Color((0xff000000L or hex.toLong(16)).toInt())
        }
        raw == "black" -> Color.Black
        raw == "white" -> Color.White
        raw == "red" -> Color.Red
        raw == "blue" -> Color.Blue
        else -> error("Unsupported SVG color: $raw")
    }
    private fun DrawScope.drawNode(n: SvgNode, inherited: Color, root: Boolean = false) {
        val canvas = drawContext.canvas
        canvas.save()
        for ((kind, args) in n.transforms()) {
            if (kind == "translate") canvas.translate(args[0], args.getOrElse(1) { 0f })
            else canvas.scale(args[0], args.getOrElse(1) { args[0] })
        }
        fun number(key: String, default: Float = 0f) = n.attrs[key]?.removeSuffix("px")?.toFloat() ?: default
        if (n.tag == "svg" && !root) {
            canvas.translate(number("x"), number("y"))
            val view = n.attrs["viewBox"]?.let(::numbers)
            val w = number("width", view?.get(2) ?: 0f); val h = number("height", view?.get(3) ?: 0f)
            canvas.clipRect(0f, 0f, w, h)
            if (view != null) {
                // MathJax uses preserveAspectRatio="none" for nested stretchable operators.
                val sx = w / view[2]; val sy = h / view[3]
                if (n.attrs["preserveAspectRatio"] == "none") canvas.scale(sx, sy)
                else { val s = minOf(sx, sy); canvas.translate((w - view[2] * s) / 2, (h - view[3] * s) / 2); canvas.scale(s, s) }
                canvas.translate(-view[0], -view[1])
            }
        }
        val fill = color(n.attrs["fill"], inherited)
        when (n.tag) {
            "path" -> drawPath(requireNotNull(n.path), fill)
            "rect" -> drawRect(fill, Offset(number("x"), number("y")), Size(number("width"), number("height")))
            "text" -> {
                // MathJax advances CJK glyph origins by 1000 units; the ink is font-size
                // (usually 884), with the remaining advance kept as spacing.
                val font = number("font-size", 884f)
                val layout = measurer.measure(AnnotatedString(n.text), TextStyle(fontSize = (font / density / fontScale).sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, color = fill), softWrap = false)
                val estimated = n.attrs["textLength"]?.toFloat() ?: n.text.sumOf { if (it.code > 255) 1.0 else 0.5 }.toFloat() * font
                canvas.translate(number("x"), number("y"))
                canvas.scale(estimated / max(1, layout.size.width), 1f)
                drawText(layout, topLeft = Offset(0f, -layout.firstBaseline))
            }
        }
        n.children.forEach { drawNode(it, fill) }
        canvas.restore()
    }
}

/** DESIGN §7.4: placeholder TextCenter, with the baseline at H/2+c. */
data class InlineMathBox(val height: Float, val baseline: Float)
fun inlineMathBox(ascent: Float, descent: Float, textBaseline: Float, textHeight: Float): InlineMathBox {
    val c = textBaseline - textHeight / 2
    val height = 2 * max(ascent - c, descent + c)
    return InlineMathBox(height, height / 2 + c)
}
