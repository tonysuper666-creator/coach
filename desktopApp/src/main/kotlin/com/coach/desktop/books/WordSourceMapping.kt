package com.coach.desktop.books

import org.w3c.dom.Element
import java.security.MessageDigest
import java.util.IdentityHashMap

/** Canonical UTF-16 provenance only. Nested/overlapping ranges never authorize text hiding. */
internal data class WordTextRange(val page: Int, val start: Int, val end: Int)

internal data class WordElementSource(
    val member: String,
    val memberSha256: String,
    /** Element-child indices from the XML root; compatible with native_payload.py. */
    val nodePath: List<Int>,
    val namespace: String,
    val localName: String,
    /** Empty for properties, omitted content, and elements with no surviving canonical text. */
    val ranges: List<WordTextRange>,
)

internal data class WordSourceMapping(val status: Status, val elements: List<WordElementSource>) {
    enum class Status { MAPPED, STALE_TEXT }
}

/** Captures output from the existing traversal, not a second text search or Word renderer. */
internal class WordTextTrace(private val member: String, bytes: ByteArray, root: Element) {
    private data class Span(val path: List<Int>, val node: Element, var start: Int = 0, var end: Int = 0)
    private data class Slice(val page: Int, val start: Int, val end: Int, val destination: Int, val text: String)
    private val spans = mutableListOf<Span>()
    private val nodes = IdentityHashMap<Element, Span>()
    private val slices = mutableListOf<Slice>()
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    init {
        fun register(node: Element, path: List<Int>) {
            val span = Span(path, node)
            spans += span; nodes[node] = span
            var index = 0
            for (i in 0 until node.childNodes.length) {
                (node.childNodes.item(i) as? Element)?.let { register(it, path + index++) }
            }
        }
        register(root, emptyList())
    }

    fun record(node: Element, start: Int, end: Int) {
        nodes.getValue(node).let { it.start = start; it.end = end }
    }

    fun pages(start: Int, pages: List<BookPage>) {
        var cursor = start
        for (page in pages) {
            slices += Slice(slices.size + 1, cursor, cursor + page.text.length, 0, page.text)
            cursor += page.text.length
        }
    }

    fun copy(page: Int, sourceStart: Int, text: String, destinationStart: Int) {
        slices += Slice(page, sourceStart, sourceStart + text.length, destinationStart, text)
    }

    fun project(raw: String, normalize: Boolean): List<WordElementSource> {
        val text = if (normalize) TextbookExtraction.clean(raw) else raw
        check(slices.all { it.start >= 0 && it.end <= text.length && text.substring(it.start, it.end) == it.text })
        var cursor = 0
        var normalized = 0
        val positions = spans.flatMap { listOf(it.start, it.end) }.distinct().sorted().associateWith { target ->
            while (cursor < target) {
                val c = raw[cursor]
                if (!normalize || (c != '\u0000' && !(c == '\n' && cursor > 0 && raw[cursor - 1] == '\r'))) normalized++
                cursor++
            }
            normalized
        }
        return spans.map { span ->
            val start = positions.getValue(span.start)
            val end = positions.getValue(span.end)
            val ranges = slices.mapNotNull { slice ->
                val from = maxOf(start, slice.start)
                val to = minOf(end, slice.end)
                if (from < to) WordTextRange(slice.page, slice.destination + from - slice.start, slice.destination + to - slice.start) else null
            }.sortedWith(compareBy({ it.page }, { it.start }))
            WordElementSource(member, digest, span.path, span.node.namespaceURI.orEmpty(), span.node.localName ?: span.node.tagName, ranges)
        }
    }
}
