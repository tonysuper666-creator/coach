package com.coach.core

import java.text.Normalizer
import java.util.Locale

/** Whole-corpus word index. Neither diagnostics nor exceptions contain private text. */
class ExamTextIndex(documents: List<String>) {
    internal class Node {
        val next = HashMap<String, Node>()
    }
    internal val root = Node()
    val windowCount: Int
    init {
        val unique = HashSet<List<String>>()
        documents.forEach { document ->
            words(document).map { it.value }.windowed(8).forEach { window ->
                unique.add(window)
                var node = root
                window.forEach { word -> node = node.next.getOrPut(word) { Node() } }
            }
        }
        windowCount = unique.size
    }

    fun overlaps(text: String): Boolean {
        val tokens = words(text)
        return tokens.indices.any { start ->
            var node = root
            var count = 0
            for (i in start until minOf(start + 8, tokens.size)) {
                node = node.next[tokens[i].value] ?: break
                count++
            }
            count == 8
        }
    }

    internal data class Word(val value: String, val start: Int, val end: Int)
    companion object {
        // Keep raw offsets through compatibility normalization (full-width letters/ligatures).
        internal fun words(text: String): List<Word> {
            val normalized = StringBuilder()
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            var offset = 0
            while (offset < text.length) {
                val end = offset + Character.charCount(text.codePointAt(offset))
                val piece = Normalizer.normalize(text.substring(offset, end), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
                piece.forEach { normalized.append(it); starts.add(offset); ends.add(end) }
                offset = end
            }
            return Regex("[a-z]+").findAll(normalized).map {
                Word(it.value, starts[it.range.first], ends[it.range.last])
            }.toList()
        }
    }
}

/**
 * Holds every suffix that could become an indexed eight-word window. A published
 * prefix can therefore never need retracting. One match switches the rest of this
 * message to reasoning, without an extra model call or a copy of the leaked span.
 */
class ExamTextGuard(private val index: ExamTextIndex?) {
    private val pending = StringBuilder()
    var blocked = false
        private set
    var hits = 0
        private set

    fun push(text: String): String {
        if (blocked) return ""
        pending.append(text)
        return drain(false)
    }

    fun finish(): String = if (blocked) "" else drain(true)

    private fun drain(end: Boolean): String {
        if (index == null) return block(0)
        val words = ExamTextIndex.words(pending.toString())
        // A streamed surrogate pair can normalize to an English letter. Keep its
        // first half, and the word touching it, until the complete code point arrives.
        val lexicalEnd = pending.length - if (!end && pending.lastOrNull()?.isHighSurrogate() == true) 1 else 0
        val incomplete = !end && words.lastOrNull()?.end == lexicalEnd
        val complete = words.size - if (incomplete) 1 else 0
        for (start in 0 until complete) {
            var node = index.root
            var count = 0
            for (i in start until minOf(start + 8, complete)) {
                node = node.next[words[i].value] ?: break
                count++
            }
            if (count == 8) { hits++; return block(words[start].start) }
        }
        if (end) return take(pending.length)
        var cut = if (incomplete) words.last().start else lexicalEnd
        // Even an unfinished eighth word must leave the preceding seven unpublished.
        for (start in maxOf(0, complete - 7) until complete) {
            var node = index.root
            var prefix = true
            for (i in start until complete) {
                val next = node.next[words[i].value]
                if (next == null) { prefix = false; break }
                node = next
            }
            if (prefix) cut = minOf(cut, words[start].start)
        }
        // Bound stalled ambiguous output without ever publishing it.
        if (pending.length - cut > 65_536) return block(cut)
        return take(cut)
    }

    private fun take(length: Int): String = pending.substring(0, length).also { pending.delete(0, length) }
    private fun block(start: Int): String {
        blocked = true
        return take(start) + REASONING.also { pending.setLength(0) }
    }

    companion object {
        const val REASONING = "\n我们改用解题思路来练习：先识别考查的概念，列出关键假设或公式，再换一组情境与数字自行推导，最后检查结论是否合理。"
    }
}
