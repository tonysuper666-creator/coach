package com.coach.ui.markdown

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.coach.domain.math.MathDelimiters
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
    val horizontal = kind.contains("CODE") || kind.contains("TABLE") || source.trimStart().startsWith("$$") || source.trimStart().startsWith("\\[")
    val scroll = if (horizontal) Modifier.horizontalScroll(rememberScrollState()) else Modifier
    if (!block.sealed) {
        // Delimiter repair and SVG layout happen only when the block seals.
        Text(source, modifier.then(scroll), style = MaterialTheme.typography.bodyLarge)
    } else if (kind.contains("CODE")) {
        Text(source.removePrefix("```\n").removeSuffix("```"), modifier.then(scroll).padding(8.dp), fontFamily = FontFamily.Monospace)
    } else {
        RichMarkdown(source, assets, model, modifier.then(scroll), onFailure)
    }
}

@Composable
private fun RichMarkdown(source: String, assets: Map<String, MathAsset>, model: Boolean, modifier: Modifier, onFailure: (String) -> Unit) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyLarge
    val density = androidx.compose.ui.platform.LocalDensity.current
    val foreground = LocalContentColor.current
    val textLayout = remember(style, density) { measurer.measure(AnnotatedString("Hx中文"), style) }
    val em = with(density) { style.fontSize.toPx() }
    val tokens = remember(source, model) { MathDelimiters.tokenize(source, model).segments }
    val inline = mutableMapOf<String, InlineTextContent>()
    val text = buildAnnotatedString {
        tokens.forEachIndexed { index, token ->
            if (token.text != null) appendStyledMarkdown(token.text!!)
            else {
                val tex = token.math!!
                val asset = assets[mathKey(tex, token.display)]
                val document = remember(asset) { asset?.let { runCatching { MathSvgDocument.parse(it.sha, it.svg) }.getOrNull() } }
                if (document == null) {
                    val key = mathKey(tex, token.display)
                    LaunchedEffect(key) { onFailure(key) }
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = MaterialTheme.colorScheme.surfaceVariant)) { append("⟦$tex⟧") }
                } else {
                    val box = inlineMathBox(document.ascentEm * em, document.descentEm * em, textLayout.firstBaseline, textLayout.size.height.toFloat())
                    val id = "math$index"
                    val w = with(density) { (document.widthEm * em).toSp() }
                    val h = with(density) { box.height.toSp() }
                    inline[id] = InlineTextContent(Placeholder(w, h, PlaceholderVerticalAlign.TextCenter)) {
                        Image(MathSvgPainter(document, foreground, measurer, em, box.baseline, box.height), tex, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.None)
                    }
                    if (token.display && length > 0) append('\n')
                    appendInlineContent(id, tex)
                    if (token.display) append('\n')
                }
            }
        }
    }
    Text(text, modifier, style = style, inlineContent = inline)
}

/** Render inline AST styles; reference definitions/links and raw HTML stay literal. */
private fun AnnotatedString.Builder.appendStyledMarkdown(source: String) {
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
    fun visit(node: ASTNode) {
        val type = node.type.toString()
        val value = source.substring(node.startOffset, node.endOffset)
        when {
            type == "LINK_DEFINITION" || type.contains("REFERENCE_LINK") -> append(value)
            type == "STRONG" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { node.children.filterNot { it.type.toString() in setOf("EMPH", "STRONG") && it.children.isEmpty() }.forEach(::visit) }
            type == "EMPH" && node.children.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { node.children.filterNot { it.type.toString() == "EMPH" && it.children.isEmpty() }.forEach(::visit) }
            type == "CODE_SPAN" -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(value.trim('`')) }
            type == "ATX_CONTENT" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)) { node.children.forEach(::visit) }
            type == "INLINE_LINK" -> {
                val label = node.children.find { it.type.toString() == "LINK_TEXT" }
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { if (label != null) append(source.substring(label.startOffset, label.endOffset).trim('[', ']')) else append(value) }
            }
            node.children.isNotEmpty() -> node.children.forEach(::visit)
            type == "ATX_HEADER" -> Unit
            else -> append(value)
        }
    }
    visit(root)
}
