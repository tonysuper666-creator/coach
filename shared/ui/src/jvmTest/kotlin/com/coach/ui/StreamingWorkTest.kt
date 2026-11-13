package com.coach.ui

import com.coach.ui.markdown.MarkdownBlock
import com.coach.ui.markdown.StreamingMarkdown
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.io.File
import kotlin.test.*

class StreamingWorkTest {
    private val parser = MarkdownParser(GFMFlavourDescriptor())

    // Compare everything consumed by block layout. The single-line fast path leaves inline
    // styles to MarkdownContent, which renders them from the complete paragraph text.
    private fun shape(node: ASTNode, source: String): String {
        val type = node.type.name
        val text = source.substring(node.startOffset, node.endOffset)
        if (text.isBlank()) return ""
        if (type == "PARAGRAPH" || type == "CELL" || type.endsWith("_CONTENT")) return "$type{$text}"
        return "$type{$text}[${node.children.joinToString("") { shape(it, source) }}]"
    }
    private fun shape(block: MarkdownBlock) = block.ast.children.joinToString("") { shape(it, block.source) }

    private fun check(source: String, chunks: List<String>) {
        val engine = StreamingMarkdown()
        val legacy = LegacyStreamingMarkdown()
        val sealed = mutableMapOf<Int, MarkdownBlock>()
        var arrived = ""
        var actual = emptyList<MarkdownBlock>()
        for ((i, delta) in chunks.withIndex()) {
            arrived += delta
            val end = i == chunks.lastIndex
            actual = engine.append(delta, end)
            val expected = legacy.append(delta, end)
            assertEquals(expected.map { it.key to it.source }, actual.map { it.key to it.source }, "chunk=$i source=$source")
            assertEquals(expected.map(::shape), actual.map(::shape), "structure chunk=$i source=$source")
            actual.forEach { block ->
                assertEquals(block.source, arrived.substring(block.key, block.key + block.source.length))
                if (block.sealed) {
                    sealed[block.key]?.let { assertSame(it, block) }
                    sealed[block.key] = block
                }
            }
        }
        assertEquals(source.filterNot(Char::isWhitespace), actual.joinToString("") { it.source }.filterNot(Char::isWhitespace))
        val whole = parser.buildMarkdownTreeFromString(source).children.joinToString("") { shape(it, source) }
        assertEquals(whole, actual.joinToString("") { shape(it) }, "whole document source=$source")
    }

    @Test fun arbitraryBoundariesRetainWholeBlockSemantics() {
        val samples = listOf(
            "Long **emphasis** with [a link](https://example.org), `code` and \$x+1\$.",
            "Title\n====\n\nNext paragraph.",
            "| A | B |\n|---|---|\n| 1 | 2 |\n\nEnd.",
            "- first\n\n  continued **here**\n  - nested\n- second\n\nDone.",
            "1. first\n2. second\n\n> quote\n> continued\n\nEnd.",
            "```kotlin\nval x = 1\n\n// inside\n```\n\nEnd.",
            "~~~\ncode\n~~~~\n\n    indented\n\nEnd.",
            "段落中有 **强调** 和 \$\$x^2\$\$。\n\n\$\$\nx=2\n\$\$\n\n结束。",
            "Title\r\n---\r\n\r\nNext **line**.",
            "[label]: https://example.org\n\n[label] remains literal.",
            "<div>\nraw html\n</div>\n\nEnd.",
            "Words  ", "\n\nWords  ",
            "a".repeat(1967),
        )
        for (source in samples) {
            for (size in listOf(1, 2, 7, 30)) check(source, source.chunked(size) + "")
            // Every possible single split catches multi-character openers/closers cut by a delta.
            if (source.length < 200) for (split in 0..source.length) check(source, listOf(source.take(split), source.drop(split), ""))
        }
    }

    @Test fun unchangedTailAndSealDoNotReparseAndInlineFormattingStillUpdates() {
        val engine = StreamingMarkdown()
        engine.append("Start **em")
        val last = engine.append("phasis** and [link](https://example.org).", true).single()
        assertEquals("Start emphasis and link.", com.coach.ui.markdown.renderMarkdownText(last.source).text)
        assertEquals(0L, engine.parseCalls)
        val multiline = StreamingMarkdown()
        multiline.append("- first\n- second")
        val work = multiline.parseCalls to multiline.parsedCharacters
        multiline.append("")
        multiline.append("", true)
        assertEquals(work, multiline.parseCalls to multiline.parsedCharacters)
    }

    @Test fun sameOfflineCorpusReportsAllParserWork() {
        val input = File(System.getProperty("user.home"), "Coach/corpus/render/replay.json")
        org.junit.Assume.assumeTrue("Real replay corpus is local acceptance input", input.isFile)
        val corpus = ReplayCorpus.load(input.readText())
        var oldChars = 0L; var newChars = 0L; var oldCalls = 0L; var newCalls = 0L; var reportedChars = 0L
        corpus.messages.forEachIndexed { index, source ->
            check(source, source.chunked(30))
            val old = LegacyStreamingMarkdown(); val candidate = StreamingMarkdown()
            old.append("", false); candidate.append("", false) // replay initialization
            source.chunked(30).forEachIndexed { tick, delta ->
                val end = (tick + 1) * 30 >= source.length
                old.append(delta, end); candidate.append(delta, end)
            }
            oldChars += old.characters; newChars += candidate.parsedCharacters
            oldCalls += old.calls; newCalls += candidate.parseCalls
            reportedChars += old.reportedCharacters
            println("STREAM_WORK m$index chars=${source.length} chunk=30 tickMs=50 baselineCalls=${old.calls} candidateCalls=${candidate.parseCalls} baselineChars=${old.characters} candidateChars=${candidate.parsedCharacters} oldReportedChars=${old.reportedCharacters}")
        }
        println("STREAM_WORK total baselineCalls=$oldCalls candidateCalls=$newCalls baselineChars=$oldChars candidateChars=$newChars oldReportedChars=$reportedChars")
        assertTrue(newChars < oldChars && newCalls < oldCalls, "Must reduce real parser work on the same input")
    }
}

/** PR49 algorithm, with BOTH previously omitted seal paths counted. Test-only A/B oracle. */
private class LegacyStreamingMarkdown {
    private val parser = MarkdownParser(GFMFlavourDescriptor())
    private val frozen = mutableListOf<MarkdownBlock>()
    private var tail = ""
    private var offset = 0
    var characters = 0L
    var calls = 0L
    var reportedCharacters = 0L
    private fun parse(source: String): ASTNode {
        calls++; characters += source.length
        return parser.buildMarkdownTreeFromString(source)
    }
    fun append(delta: String, end: Boolean): List<MarkdownBlock> {
        tail += delta
        reportedCharacters += tail.length
        val tree = parse(tail)
        val nodes = tree.children.filter { tail.substring(it.startOffset, it.endOffset).isNotBlank() }
        val sealCount = if (end) nodes.size else maxOf(0, nodes.size - 1)
        var consumed = 0
        for (node in nodes.take(sealCount)) {
            val source = tail.substring(node.startOffset, node.endOffset)
            frozen += MarkdownBlock(offset + node.startOffset, source, parse(source), true)
            consumed = node.endOffset
        }
        if (consumed > 0) { tail = tail.substring(consumed); offset += consumed }
        val pending = if (tail.isNotBlank()) listOf(MarkdownBlock(offset, tail, if (consumed == 0) tree else parse(tail), end)) else emptyList()
        if (end) tail = ""
        return frozen.toList() + pending
    }
}
