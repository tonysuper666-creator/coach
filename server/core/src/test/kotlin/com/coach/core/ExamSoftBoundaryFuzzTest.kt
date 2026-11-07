package com.coach.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Fixed-seed cross-review corpus, using synthetic reference text only.
class ExamSoftBoundaryFuzzTest {
    private val exam = "Amber badgers carry delicate emerald feathers gently home. Ivory jaguars keep lemons near old purple quails."
    private val second = "Rivers shine through unusual valleys while xenon yields zephyrs."
    private val index = ExamTextIndex(listOf(exam, second))
    private val words = (exam + " " + second).split(Regex("[^A-Za-z]+")).filter { it.isNotEmpty() }
    private val diverge = listOf("am", "amb", "ambient", "bad", "badminton", "car", "zo", "zoology", "ca", "de", "em", "fe", "ge", "ho", "iv", "ja", "x", "z", "q", "a", "i", "an", "it")
    private val noise = listOf(" ", " ", " ", "  ", "\n", ", ", ". ", "*", "**", "_", "~", "`", "{", "}", "$", "$$", "\\text{", "\\", "\\te", "\\x ", "​", "­", "́", " z ", " q ", " x ", " a ", " i ", "1", "-", "'")

    private fun disguise(word: String, r: Random): String {
        val sb = StringBuilder()
        for (c in word) {
            when (r.nextInt(14)) {
                0 -> sb.append(when (c) { 'a' -> 'а'; 'e' -> 'е'; 'o' -> 'ο'; 'A' -> 'Α'; else -> c })
                1 -> sb.append(if (c in 'a'..'z') (c - 'a' + 0xFF41).toChar() else c)
                2 -> { sb.append(c); sb.append(noise[r.nextInt(noise.size)].takeIf { it.isNotBlank() && !it.contains(' ') } ?: "") }
                3 -> if (c == 'A') sb.append("𝔄") else sb.append(c)
                4 -> sb.append(c.uppercaseChar())
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun generate(r: Random): String {
        val sb = StringBuilder()
        var pos = r.nextInt(words.size)
        repeat(r.nextInt(4, 16)) {
            when (r.nextInt(10)) {
                0 -> sb.append(diverge[r.nextInt(diverge.size)])
                1 -> pos = r.nextInt(words.size)
                2 -> sb.append(words[r.nextInt(words.size)].take(r.nextInt(1, 4)))
                else -> { sb.append(disguise(words[pos % words.size], r)); pos++ }
            }
            sb.append(if (r.nextInt(3) == 0) noise[r.nextInt(noise.size)] else " ")
        }
        return sb.toString()
    }

    private fun stream(input: String, r: Random): Triple<String, Boolean, String> {
        val guard = ExamTextGuard(index)
        val seen = StringBuilder()
        var offset = 0
        while (offset < input.length) {
            val end = minOf(input.length, offset + r.nextInt(1, 6))
            seen.append(guard.push(input.substring(offset, end)))
            val published = seen.toString().substringBefore(ExamTextGuard.REASONING)
            if (index.overlaps(published)) return Triple(published, true, "LEAK")
            offset = end
        }
        seen.append(guard.finish())
        val published = seen.toString().substringBefore(ExamTextGuard.REASONING)
        if (index.overlaps(published)) return Triple(published, true, "LEAK")
        return Triple(seen.toString(), guard.blocked, "")
    }

    @Test fun fuzzNeverPublishesAnIndexedWindow() {
        var leaks = 0; var missed = 0; var changed = 0; var blockedCount = 0; var overlapping = 0
        repeat(40_000) { seed ->
            val r = Random(seed)
            val input = generate(r)
            val full = index.overlaps(input)
            if (full) overlapping++
            val (out, blocked, tag) = stream(input, Random(seed * 31 + 7))
            if (tag == "LEAK") { leaks++; if (leaks <= 3) println("LEAK seed=$seed") }
            if (blocked) blockedCount++
            if (full && !blocked) { missed++; if (missed <= 3) println("MISSED seed=$seed") }
            if (!blocked && out != input) { changed++; if (changed <= 3) println("CHANGED seed=$seed") }
        }
        println("ARCH_FUZZ total=40000 overlapping=$overlapping blocked=$blockedCount leaks=$leaks missed=$missed changed=$changed")
        assertEquals(0, leaks); assertEquals(0, missed); assertEquals(0, changed)
    }

    @Test fun releasedPrefixNeverLaterJoinsAWindowViaIgnoredLettersOrMarkup() {
        // Target the new rule: 1-7 indexed words, then a diverging partial that the
        // next chunk turns into something else; then the rest of the window.
        var leaks = 0; var cases = 0
        val quote = exam.split(Regex("[^A-Za-z]+")).filter { it.isNotEmpty() }
        val tails = listOf("", "​", "­", "́", "*", "_", "{", "}", "\\", "\\text{", "$", "ο", "е", "1", " ", "\uD835")
        for (k in 1..7) for (p in diverge) for (t in tails) for (split in 1..p.length) {
            cases++
            val head = quote.take(k).joinToString(" ") + " "
            val restAfter = quote.drop(k).joinToString(" ")
            // diverging partial, optional tail, then continue the quotation from word k (the partial is inserted, not replacing)
            val input = head + p + t + " " + restAfter + " "
            val guard = ExamTextGuard(index)
            val seen = StringBuilder()
            seen.append(guard.push(head + p.take(split)))
            seen.append(guard.push(p.drop(split) + t))
            seen.append(guard.push(" " + restAfter + " "))
            seen.append(guard.finish())
            val published = seen.toString().substringBefore(ExamTextGuard.REASONING)
            if (index.overlaps(published)) { leaks++; println("LEAK2 k=$k p=$p t=${t.map { it.code }}") }
            if (index.overlaps(input) && !guard.blocked) { leaks++; println("MISS2 k=$k p=$p t=${t.map { it.code }}") }
        }
        println("ARCH_TARGETED cases=$cases leaks=$leaks")
        assertEquals(0, leaks)
    }

    @Test fun denseFuzzAndDifferentialAgainstPreviousGuard() {
        val quote = (exam + " " + second).split(Regex("[^A-Za-z]+")).filter { it.isNotEmpty() }
        var leaks = 0; var missed = 0; var overlapping = 0; var earlier = 0; var later = 0; var blockDiff = 0
        repeat(40_000) { seed ->
            val r = Random(seed)
            val start = r.nextInt(quote.size - 9)
            val sb = StringBuilder()
            for (w in quote.subList(start, start + r.nextInt(6, 10))) {
                if (r.nextInt(4) == 0) sb.append(listOf("z ", "q ", "x ", "zo", "a ", "i ")[r.nextInt(6)].let { if (it == "zo") "zoology " else it })
                sb.append(disguise(w, r))
                sb.append(listOf(" ", " ", " ", "  ", "\n", "*", "_", "\u200B ", "$ ", " \\text{x} ", ", ")[r.nextInt(11)].replace("\\n", "\n"))
            }
            val input = sb.toString()
            if (index.overlaps(input)) overlapping++
            val nu = ExamTextGuard(index); val old = OldGuardZz(index)
            val seenN = StringBuilder(); val seenO = StringBuilder()
            var offset = 0
            val cr = Random(seed * 17 + 3)
            while (offset < input.length) {
                val end = minOf(input.length, offset + cr.nextInt(1, 5))
                seenN.append(nu.push(input.substring(offset, end))); seenO.append(old.push(input.substring(offset, end)))
                val pn = seenN.toString().substringBefore(ExamTextGuard.REASONING)
                if (index.overlaps(pn)) {
                    val po = seenO.toString().substringBefore(OldGuardZz.REASONING)
                    leaks++; println("LEAK3 seed=$seed oldLeaks=${index.overlaps(po)} input=" + input.map { if (it.code in 32..126) it.toString() else "<" + it.code.toString(16) + ">" }.joinToString("") + " publishedNew=" + pn.map { if (it.code in 32..126) it.toString() else "<" + it.code.toString(16) + ">" }.joinToString(""))
                    return@repeat
                }
                // A vulnerable mixed-mark input legitimately requires the new
                // guard to hold more. Compare release speed only where there
                // are no ambiguous markup boundaries to fix.
                if (!nu.blocked && !old.blocked && input.none { it in "*_~`{}\$" }) {
                    if (seenN.length > seenO.length) earlier++
                    if (seenN.length < seenO.length) later++
                }
                offset = end
            }
            seenN.append(nu.finish()); seenO.append(old.finish())
            if (index.overlaps(seenN.toString().substringBefore(ExamTextGuard.REASONING))) leaks++
            if (index.overlaps(input) && !nu.blocked) missed++
            if (nu.blocked != old.blocked) { blockDiff++; if (blockDiff <= 3) println("BLOCKDIFF seed=$seed new=${nu.blocked} old=${old.blocked}") }
        }
        println("ARCH_DENSE total=40000 overlapping=$overlapping leaks=$leaks missed=$missed newEarlierSteps=$earlier newLaterSteps=$later blockDiff=$blockDiff")
        assertEquals(0, leaks); assertEquals(0, missed); assertEquals(0, later)
    }
}

class OldGuardZz(private val index: ExamTextIndex?) {
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
        var cut = pending.length
        var hitStart: Int? = null
        // Preserve literal eight-word protection as well: joining inline markup
        // must not create a new bypass for a quotation separated by underscores.
        for ((root, scan) in index.scans(pending.toString())) {
            val incomplete = !end && scan.openWord
            val rawComplete = scan.words.size - if (incomplete) 1 else 0
            val words = scan.words.take(rawComplete).filter { it.matchable }
            val complete = words.size
            for (start in 0 until complete) {
                var node = root
                var count = 0
                for (i in start until minOf(start + 8, complete)) {
                    node = node.next[words[i].value] ?: break
                    count++
                }
                if (count == 8) hitStart = minOf(hitStart ?: pending.length, words[start].start)
            }
            if (!end) {
                cut = minOf(cut, if (incomplete) scan.words.last().start else pending.length, scan.stableEnd)
                // An unfinished eighth word must leave the preceding seven unpublished.
                for (start in maxOf(0, complete - 7) until complete) {
                    var node = root
                    var prefix = true
                    for (i in start until complete) {
                        val next = node.next[words[i].value]
                        if (next == null) { prefix = false; break }
                        node = next
                    }
                    if (prefix) cut = minOf(cut, words[start].start)
                }
            }
        }
        hitStart?.let { hits++; return block(it) }
        if (end) return take(pending.length)
        // A literal word can start inside a control word; never publish its backslash alone.
        if (cut > 0 && pending[cut - 1] == '\\') cut--
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
