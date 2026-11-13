package com.coach.ui.markdown

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.CompositeASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** A sealed block is never reparsed or replaced. Keys are source offsets, not list indices. */
data class MarkdownBlock(val key: Int, val source: String, val ast: ASTNode, val sealed: Boolean)
class StreamingMarkdown {
    private val parser = MarkdownParser(GFMFlavourDescriptor())
    private val frozen = mutableListOf<MarkdownBlock>()
    private var tail = ""
    private var offset = 0
    private var tailTree: ASTNode? = null
    var parsedCharacters: Long = 0; private set
    var parseCalls: Long = 0; private set

    private fun parse(source: String): ASTNode {
        parseCalls++
        parsedCharacters += source.length
        // Preserve the library's complete tree on the fallback path: block consumers also
        // inspect paragraph children (for example while rendering a block quote).
        return parser.buildMarkdownTreeFromString(source)
    }

    private fun tree(): ASTNode {
        tailTree?.let { return it }
        // A single line beginning with a letter cannot become a heading, list, fence, quote,
        // definition or table until a line break arrives. Keep the WHOLE paragraph mutable:
        // the next newline (including a split CRLF) falls back to the real GFM block parser.
        // Inline delimiters may span any delta boundary; the renderer still sees all of them.
        val start = tail.indexOfFirst { it != '\n' && it != '\r' }.let { if (it < 0) tail.length else it }
        val plainLine = start < tail.length && tail[start].isLetter() && tail.substring(start).none { it == '\n' || it == '\r' }
        return (if (plainLine) CompositeASTNode(MarkdownElementTypes.MARKDOWN_FILE, listOf(
            CompositeASTNode(MarkdownElementTypes.PARAGRAPH, listOf(LeafASTNode(MarkdownTokenTypes.TEXT, start, tail.length)))
        )) else parse(tail)).also { tailTree = it }
    }

    fun append(delta: String, end: Boolean = false): List<MarkdownBlock> {
        if (delta.isNotEmpty()) tailTree = null
        tail += delta
        val tree = tree()
        val nodes = tree.children.filter { tail.substring(it.startOffset, it.endOffset).isNotBlank() }
        val sealCount = if (end) nodes.size else maxOf(0, nodes.size - 1)
        // The final AST block remains mutable (setext headings, list continuations, tables, fences).
        // Reference definitions are rendered literally; they never rewrite frozen text.
        var consumed = 0
        for (node in nodes.take(sealCount)) {
            val source = tail.substring(node.startOffset, node.endOffset)
            // Rebase the block subtree already parsed above instead of parsing the seal again.
            frozen += MarkdownBlock(offset + node.startOffset, source, blockTree(tree, listOf(node), node.startOffset), true)
            consumed = node.endOffset
        }
        if (consumed > 0) {
            tail = tail.substring(consumed); offset += consumed
            tailTree = blockTree(tree, tree.children.filter { it.startOffset >= consumed }, consumed)
        }
        val pending = if (tail.isNotBlank()) listOf(MarkdownBlock(offset, tail, tailTree ?: tree, end)) else emptyList()
        if (end) { tail = ""; tailTree = null }
        return frozen.toList() + pending
    }
}

private fun blockTree(root: ASTNode, nodes: List<ASTNode>, offset: Int): ASTNode =
    CompositeASTNode(root.type, nodes.map { rebase(it, offset) })

private fun rebase(node: ASTNode, offset: Int): ASTNode =
    if (node.children.isEmpty()) LeafASTNode(node.type, node.startOffset - offset, node.endOffset - offset)
    else CompositeASTNode(node.type, node.children.map { rebase(it, offset) })
