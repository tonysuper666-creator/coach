package com.coach.ui.markdown

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.coach.domain.math.MathDelimiters
import com.coach.domain.math.MathSegment
import com.coach.ui.math.*
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** Assets are server-produced SVGs stored alongside the message; missing assets remain readable. */
data class MathAsset(val tex: String, val display: Boolean, val sha: String, val svg: String)
fun mathKey(tex: String, display: Boolean) = "$display:$tex"

@Composable
fun MarkdownContent(block: MarkdownBlock, assets: Map<String, MathAsset>, model: Boolean = true, modifier: Modifier = Modifier, onFailure: (String) -> Unit = {}) {
    val source = block.source
    val kind = block.ast.children.firstOrNull { source.substring(it.startOffset, it.endOffset).isNotBlank() }?.type.toString()
    val horizontal = kind.contains("CODE") || kind.contains("TABLE")
    val scroll = if (horizontal) Modifier.horizontalScroll(rememberScrollState()) else Modifier
    if (kind.contains("CODE")) {
        Text(source.removePrefix("```\n").removeSuffix("```"), modifier.then(scroll).padding(8.dp), fontFamily = FontFamily.Monospace)
    } else {
        // Structure (headings, lists, quotes) is drawn from the first moment, also while the block is still arriving; formulas wait for the seal.
        RichMarkdown(block, assets, model, live = !block.sealed, modifier.then(scroll), onFailure)
    }
}

/** Test hook: scroll every display formula to its right end (used to prove the end of a wide formula is reachable). */
val LocalScrollMathToEnd = compositionLocalOf { false }

/** One display formula: natural size, horizontally scrollable so a formula wider than the screen is never cut off. */
@Composable
fun ScrollableMath(document: MathSvgDocument, tex: String, emPx: Float, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scroll = rememberScrollState()
    if (LocalScrollMathToEnd.current) LaunchedEffect(scroll.maxValue) { if (scroll.maxValue > 0) scroll.scrollTo(scroll.maxValue) }
    Box(modifier.fillMaxWidth().horizontalScroll(scroll)) {
        val width = with(density) { (document.widthEm * emPx).toDp() }
        val height = with(density) { ((document.ascentEm + document.descentEm) * emPx).toDp() }
        Image(MathSvgPainter(document, color, measurer, emPx), tex, Modifier.size(width, height))
    }
}

internal fun groupMathRuns(tokens: List<MathSegment>, canInline: (MathSegment) -> Boolean): List<List<IndexedValue<MathSegment>>> {
    val out = mutableListOf<List<IndexedValue<MathSegment>>>()
    var open = mutableListOf<IndexedValue<MathSegment>>()
    tokens.withIndex().forEach { token ->
        val value = token.value
        val before = tokens.getOrNull(token.index - 1)?.text.orEmpty().substringAfterLast('\n')
        val after = tokens.getOrNull(token.index + 1)?.text.orEmpty().substringBefore('\n')
        val ownLine = before.isBlank() && after.isBlank()
        if (value.math != null && value.display && (ownLine || !canInline(value))) {
            if (open.isNotEmpty()) out += open
            out += listOf(token)
            open = mutableListOf()
        } else open += token
    }
    if (open.isNotEmpty()) out += open
    return out
}

/** Block-level structure: headings, bullet/numbered lists (nested), quotes and paragraphs; each paragraph text then goes through math + inline styles. */
@Composable
private fun RichMarkdown(block: MarkdownBlock, assets: Map<String, MathAsset>, model: Boolean, live: Boolean, modifier: Modifier, onFailure: (String) -> Unit) {
    val nodes = block.ast.children.filter { block.source.substring(it.startOffset, it.endOffset).isNotBlank() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BlockNodes(block.source, nodes, assets, model, live, onFailure)
    }
}

@Composable
private fun BlockNodes(source: String, nodes: List<ASTNode>, assets: Map<String, MathAsset>, model: Boolean, live: Boolean, onFailure: (String) -> Unit) {
    val body = MaterialTheme.typography.bodyLarge
    nodes.forEach { node ->
        val type = node.type.name
        when {
            type.startsWith("ATX_") || type.startsWith("SETEXT_") -> {
                val level = type.last().digitToIntOrNull() ?: 2
                val content = node.children.firstOrNull { it.type.name.endsWith("_CONTENT") }
                val text = content?.let { source.substring(it.startOffset, it.endOffset).trim() } ?: source.substring(node.startOffset, node.endOffset).trim('#', ' ')
                val size = when (level) { 1 -> 22.sp; 2 -> 20.sp; 3 -> 18.sp; else -> 17.sp }
                MathText(text, assets, model, live, body.copy(fontSize = size, lineHeight = size * 1.4f, fontWeight = FontWeight.Bold), onFailure, Modifier.padding(top = 6.dp))
            }
            type == "UNORDERED_LIST" || type == "ORDERED_LIST" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var number = 0
                node.children.filter { it.type.name == "LIST_ITEM" }.forEach { item ->
                    val marker = item.children.firstOrNull { it.type.name == "LIST_NUMBER" || it.type.name == "LIST_BULLET" }
                    number++
                    val label = if (type == "ORDERED_LIST") (marker?.let { source.substring(it.startOffset, it.endOffset).trim() } ?: "$number.") else "•"
                    val inner = item.children.filter { it.type.name != "LIST_NUMBER" && it.type.name != "LIST_BULLET" && source.substring(it.startOffset, it.endOffset).isNotBlank() }
                    Row(Modifier.fillMaxWidth()) {
                        Text(label, Modifier.widthIn(min = 22.dp), style = body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { BlockNodes(source, inner, assets, model, live, onFailure) }
                    }
                }
            }
            type == "BLOCK_QUOTE" -> {
                val bar = MaterialTheme.colorScheme.outlineVariant
                // A bar drawn behind the column (no intrinsic measurement: the paragraphs inside use BoxWithConstraints).
                Column(Modifier.fillMaxWidth().drawBehind { drawRect(bar, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }.padding(start = 13.dp)) {
                    BlockNodes(source, node.children.filter { it.children.isNotEmpty() && source.substring(it.startOffset, it.endOffset).isNotBlank() }, assets, model, live, onFailure)
                }
            }
            else -> MathText(source.substring(node.startOffset, node.endOffset), assets, model, live, body, onFailure)
        }
    }
}

/** One paragraph of text: display formulas on their own scrollable row, text and inline formulas flowing as one wrapped paragraph. */
@Composable
private fun MathText(text: String, assets: Map<String, MathAsset>, model: Boolean, live: Boolean, style: TextStyle, onFailure: (String) -> Unit, modifier: Modifier = Modifier) {
    if (live) {
        // Still streaming: structure and emphasis are shown, formulas stay as written until the block seals (no half-typed LaTeX flicker).
        Text(remember(text) { renderMarkdownText(text) }, modifier.fillMaxWidth(), style = style)
        return
    }
    val tokens = remember(text, model) { layoutMathSegments(MathDelimiters.tokenize(text, model).segments) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val em = with(density) { style.fontSize.toPx() }
    val foreground = LocalContentColor.current
    val widths = remember { HashMap<String, Float>() }
    BoxWithConstraints(modifier) {
        val available = with(density) { maxWidth.toPx() }
        val runs = groupMathRuns(tokens) { segment ->
            val asset = assets[mathKey(segment.math!!, segment.display)]
            if (asset == null) false else widths.getOrPut(asset.sha) {
                runCatching { MathSvgDocument.parse(asset.sha, asset.svg).widthEm }.getOrDefault(Float.POSITIVE_INFINITY)
            } * em <= available
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            runs.forEachIndexed { runIndex, run ->
                val first = run.first().value
                val asset = if (run.size == 1 && first.math != null && first.display) assets[mathKey(first.math!!, true)] else null
                val document = remember(asset) { asset?.let { runCatching { MathSvgDocument.parse(it.sha, it.svg) }.getOrNull() } }
                if (document != null) ScrollableMath(document, first.math!!, em, foreground)
                else InlineRun(run, assets, onFailure, style, trimLeadingSpace = runIndex > 0 && runs[runIndex - 1].singleOrNull()?.value?.display == true)
            }
        }
    }
}

@Composable
private fun InlineRun(run: List<IndexedValue<MathSegment>>, assets: Map<String, MathAsset>, onFailure: (String) -> Unit, style: TextStyle, trimLeadingSpace: Boolean = false) {
    val source = run.joinToString("") { it.index.toString() }
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val foreground = LocalContentColor.current
    // Fixed line height keeps the line box constant, but the baseline inside it depends on whether the line holds CJK
    // fallback glyphs. Pad for both, then read the real line baseline from the layout (see onTextLayout below).
    val latinLayout = remember(style, density) { measurer.measure(AnnotatedString("Hx"), style) }
    val textLayout = remember(style, density) { measurer.measure(AnnotatedString("Hx中文"), style) }
    val baselines = remember(source) { mutableStateMapOf<String, Float>() }
    val order = mutableListOf<String>()
    val em = with(density) { style.fontSize.toPx() }
    val inline = mutableMapOf<String, InlineTextContent>()
    val text = buildAnnotatedString {
        run.forEachIndexed { part, (index, token) ->
            if (token.text != null) append(renderMarkdownText(if (trimLeadingSpace && part == 0) token.text!!.trimStart(' ', '\t') else token.text!!))
            else {
                val tex = token.math!!
                val asset = assets[mathKey(tex, token.display)]
                val document = remember(asset) { asset?.let { runCatching { MathSvgDocument.parse(it.sha, it.svg) }.getOrNull() } }
                if (document == null) {
                    val key = mathKey(tex, token.display)
                    LaunchedEffect(key) { onFailure(key) }
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = MaterialTheme.colorScheme.surfaceVariant)) { append("⟦$tex⟧") }
                } else {
                    val boxLatin = inlineMathBox(document.ascentEm * em, document.descentEm * em, latinLayout.firstBaseline, latinLayout.size.height.toFloat())
                    val box = inlineMathBox(document.ascentEm * em, document.descentEm * em, textLayout.firstBaseline, textLayout.size.height.toFloat())
                        .let { if (boxLatin.height > it.height) boxLatin.copy(baseline = it.baseline + (boxLatin.height - it.height) / 2) else it }
                    val id = "math$index"
                    val w = with(density) { (document.widthEm * em).toSp() }
                    val h = with(density) { box.height.toSp() }
                    inline[id] = InlineTextContent(Placeholder(w, h, PlaceholderVerticalAlign.TextCenter)) {
                        Image(MathSvgPainter(document, foreground, measurer, em, baselines[id] ?: box.baseline, box.height), tex, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.None)
                    }
                    order += id
                    appendInlineContent(id, tex)
                }
            }
        }
    }
    Text(text, Modifier, style = style, inlineContent = inline, onTextLayout = { layout ->
        layout.placeholderRects.forEachIndexed { i, rect ->
            val id = order.getOrNull(i) ?: return@forEachIndexed
            if (rect == null) return@forEachIndexed
            val baseline = layout.getLineBaseline(layout.getLineForVerticalPosition(rect.center.y)) - rect.top
            if (kotlin.math.abs((baselines[id] ?: Float.MAX_VALUE) - baseline) > .25f) baselines[id] = baseline
        }
    })
}

internal fun renderMarkdownText(source: String): AnnotatedString = buildAnnotatedString { appendStyledMarkdown(source) }

/** Render inline AST styles; reference definitions/links and raw HTML stay literal. */
private fun AnnotatedString.Builder.appendStyledMarkdown(source: String) {
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
    fun visit(node: ASTNode) {
        val type = node.type.name
        val value = source.substring(node.startOffset, node.endOffset)
        when {
            type == "LIST_BULLET" -> append("• ")
            type == "LINK_DEFINITION" || type.contains("REFERENCE_LINK") -> append(value)
            type == "STRONG" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { node.children.filterNot { it.type.name in setOf("EMPH", "STRONG") && it.children.isEmpty() }.forEach(::visit) }
            type == "EMPH" && node.children.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { node.children.filterNot { it.type.name == "EMPH" && it.children.isEmpty() }.forEach(::visit) }
            type == "CODE_SPAN" -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(value.trim('`')) }
            type == "ATX_CONTENT" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)) { node.children.forEach(::visit) }
            type == "INLINE_LINK" -> {
                val label = node.children.find { it.type.name == "LINK_TEXT" }
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { if (label != null) append(source.substring(label.startOffset, label.endOffset).trim('[', ']')) else append(value) }
            }
            node.children.isNotEmpty() -> node.children.forEach(::visit)
            type == "ATX_HEADER" -> Unit
            else -> append(unescapeBackslashes(value))
        }
    }
    visit(root)
}

/** CommonMark backslash escapes: a backslash before ASCII punctuation is dropped. Plain loop, no regex (ICU rejects `{` in classes). */
private fun unescapeBackslashes(text: String): String {
    val punctuation = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in punctuation) { out.append(text[i + 1]); i += 2 } else { out.append(text[i]); i++ }
    }
    return out.toString()
}
