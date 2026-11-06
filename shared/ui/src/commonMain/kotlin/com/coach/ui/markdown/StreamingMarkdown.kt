package com.coach.ui.markdown

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** A sealed block is never reparsed or replaced. Keys are source offsets, not list indices. */
data class MarkdownBlock(val key: Int, val source: String, val ast: ASTNode, val sealed: Boolean)
class StreamingMarkdown {
    private val parser = MarkdownParser(GFMFlavourDescriptor())
    private val frozen = mutableListOf<MarkdownBlock>()
    private var tail = ""
    private var offset = 0
    var parsedCharacters: Long = 0; private set
    fun append(delta: String, end: Boolean = false): List<MarkdownBlock> {
        tail += delta
        parsedCharacters += tail.length
        val tree = parser.buildMarkdownTreeFromString(tail)
        val nodes = tree.children.filter { tail.substring(it.startOffset, it.endOffset).isNotBlank() }
        val sealCount = if (end) nodes.size else maxOf(0, nodes.size - 1)
        // The final AST block remains mutable (setext headings, list continuations, tables, fences).
        // Reference definitions are rendered literally; they never rewrite frozen text.
        var consumed = 0
        for (node in nodes.take(sealCount)) {
            val source = tail.substring(node.startOffset, node.endOffset)
            frozen += MarkdownBlock(offset + node.startOffset, source, parser.buildMarkdownTreeFromString(source), true)
            consumed = node.endOffset
        }
        if (consumed > 0) { tail = tail.substring(consumed); offset += consumed }
        // Nothing sealed: the tail is the string already parsed above, so its tree is the pending block's tree (one parse per tick, not two).
        val pending = if (tail.isNotBlank()) listOf(MarkdownBlock(offset, tail, if (consumed == 0) tree else parser.buildMarkdownTreeFromString(tail), end)) else emptyList()
        if (end) { tail = "" }
        return frozen.toList() + pending
    }
}
