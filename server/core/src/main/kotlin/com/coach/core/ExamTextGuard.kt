package com.coach.core

import java.text.Normalizer
import java.util.Locale

/** Whole-corpus word index. Neither diagnostics nor exceptions contain private text. */
class ExamTextIndex(documents: List<String>) {
    internal class Node {
        val next = HashMap<String, Node>()
        private val prefixes: Set<String> by lazy {
            next.keys.flatMapTo(HashSet()) { word -> (1..word.length).map { word.substring(0, it) } }
        }
        fun hasPrefix(word: String): Boolean =
            if (next.size < 64) next.keys.any { it.startsWith(word) } else word in prefixes
    }
    internal val root = Node()
    internal var incompleteIndex = false
        private set
    val windowCount: Int
    init {
        val unique = HashSet<List<String>>()
        fun add(window: List<String>) {
            if (!unique.add(window)) return
            var node = root
            window.forEach { word -> node = node.next.getOrPut(word) { Node() } }
        }
        documents.forEach { document ->
            scans(document).forEach { (_, scan) ->
                scan.words.filter { it.matchable }.map { it.value }.windowed(8).forEach { window ->
                    add(window)
                }
            }
            if (!indexSoftReference(document, ::add)) incompleteIndex = true
        }
        windowCount = unique.size
    }

    fun overlaps(text: String): Boolean {
        return incompleteIndex || softMatches(text, true).hit != null || scans(text).any { (root, scan) ->
            val tokens = scan.words.filter { it.matchable }
            tokens.indices.any { start ->
                var node = root
                var count = 0
                for (i in start until minOf(start + 8, tokens.size)) {
                    node = node.next[tokens[i].value] ?: break
                    count++
                }
                count == 8
            }
        }
    }

    // A reference and a reply may use different formatting. All reply views
    // must match every reference view, including literal words in TeX wrappers.
    internal fun scans(text: String): List<Pair<Node, Scan>> = listOf(
        root to scan(text), root to scan(text, visible = false), root to scan(text, controlWords = false),
        root to scan(text, markupSeparates = true), root to scan(text, controlWords = false, markupSeparates = true)
    )
    internal data class Word(val value: String, val start: Int, val end: Int, val matchable: Boolean)
    internal data class Symbol(val char: Char, val start: Int)
    internal data class Scan(val words: List<Word>, val openWord: Boolean, val stableEnd: Int, val symbols: List<Symbol>)
    internal data class SoftResult(val hit: Int?, val hold: Int?)

    // Reference formatting is ambiguous too. Index every reachable eight-word
    // window, bounded to avoid unbounded work on adversarial markup. If the
    // bound is reached, protection fails closed instead of using a partial index.
    private fun indexSoftReference(text: String, add: (List<String>) -> Unit): Boolean {
        if (Normalizer.normalize(text, Normalizer.Form.NFKD).none { it.code in MARKUP }) return true
        data class RefState(val history: List<String>, val partial: String)
        for (controlWords in listOf(true, false)) {
            var states = setOf(RefState(emptyList(), ""))
            fun finish(state: RefState): RefState {
                val word = state.partial
                if (word.isEmpty() || (word.length == 1 && word != "a" && word != "i"))
                    return state.copy(partial = "")
                val words = state.history + word
                if (words.size == 8) add(words)
                return RefState(words.takeLast(7), "")
            }
            for (symbol in scan(text, controlWords = controlWords, softMarkup = true).symbols) {
                states = when (val c = symbol.char) {
                    in 'a'..'z' -> states.mapTo(mutableSetOf()) { it.copy(partial = it.partial + c) }
                    '\u0001' -> states.flatMapTo(mutableSetOf()) { listOf(it, finish(it)) }
                    else -> states.mapTo(mutableSetOf(), ::finish)
                }
                if (states.size > 16_384 || states.any { it.partial.length > 1024 }) return false
            }
            states.forEach(::finish)
        }
        return true
    }

    // A formatting mark can either join letters in a word or separate two
    // words. Traverse only paths that can still reach an indexed window; this
    // avoids enumerating the exponential set of formatting interpretations.
    internal fun softMatches(text: String, end: Boolean): SoftResult {
        data class State(val node: Node, val partial: String, val start: Int)
        var hit: Int? = null
        var hold: Int? = null
        for (controlWords in listOf(true, false)) {
            val scan = scan(text, controlWords = controlWords, softMarkup = true)
            var states = emptySet<State>()
            var boundary = true
            fun advance(state: State): State? {
                val word = state.partial
                if (word.isEmpty()) return state
                if (word.length == 1 && word != "a" && word != "i") return state.copy(partial = "")
                val next = state.node.next[word] ?: return null
                if (next.next.isEmpty()) {
                    hit = minOf(hit ?: text.length, state.start)
                    return null
                }
                return State(next, "", state.start)
            }
            for (symbol in scan.symbols) {
                val c = symbol.char
                if (c in 'a'..'z') {
                    val candidates = states.toMutableSet()
                    if (boundary) candidates.add(State(root, "", symbol.start))
                    states = candidates.mapNotNullTo(mutableSetOf()) { state ->
                        val word = state.partial + c
                        val possible = state.node.hasPrefix(word)
                        val ignoredVariable = state.node !== root && word.length == 1 && word != "a" && word != "i"
                        if (possible || ignoredVariable) state.copy(partial = word) else null
                    }
                    boundary = false
                } else if (c == '\u0001') {
                    val joined = states.toMutableSet()
                    states.mapNotNullTo(joined, ::advance)
                    states = joined
                    boundary = true
                } else {
                    states = states.mapNotNullTo(mutableSetOf(), ::advance)
                    boundary = true
                }
            }
            if (end) states.forEach(::advance)
            else for (state in states) {
                // A state with no indexed word yet is handled by the normal
                // open-word guard; keep only an actual indexed prefix here.
                if (state.node !== root) hold = minOf(hold ?: text.length, state.start)
            }
        }
        return SoftResult(hit, hold)
    }
    companion object {
        internal fun words(text: String): List<Word> = scan(text).words

        // Match the reader's words, retaining raw UTF-16 offsets for the stream cut.
        // Only the matching view is folded; normal replies are published unchanged.
        internal fun scan(text: String, visible: Boolean = true, controlWords: Boolean = visible, markupSeparates: Boolean = !visible, softMarkup: Boolean = false): Scan {
            val normalized = StringBuilder()
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            var stableEnd = text.length
            var offset = 0
            while (offset < text.length) {
                // A partial control word must not be published: the next delta can
                // turn "\\te" into "\\text", changing how subsequent letters tokenize.
                if (controlWords && text[offset] == '\\' && (offset + 1 == text.length || asciiLetter(text[offset + 1]))) {
                    var end = offset + 1
                    while (end < text.length && asciiLetter(text[end])) end++
                    if (end == text.length) stableEnd = minOf(stableEnd, offset)
                    offset = end
                    continue
                }
                if (offset == text.lastIndex && text[offset].isHighSurrogate()) {
                    stableEnd = minOf(stableEnd, offset)
                    break
                }
                var end = offset + Character.charCount(text.codePointAt(offset))
                if (!visible) while (end < text.length && Character.getType(text.codePointAt(end)) in MARK_TYPES)
                    end += Character.charCount(text.codePointAt(end))
                // Invisible characters and accents cannot split readable words in
                // any view, including words immediately following a backslash.
                val piece = Normalizer.normalize(text.substring(offset, end), Normalizer.Form.NFKD)
                piece.codePoints().forEach { cp ->
                    if (softMarkup && cp in MARKUP) {
                        normalized.append('\u0001'); starts.add(offset); ends.add(end)
                    } else if (Character.getType(cp) !in IGNORED_TYPES && cp != 0xAD && (markupSeparates || cp !in MARKUP)) {
                        val folded = ((if (visible) CONFUSABLES[cp] else null)?.toString() ?: String(Character.toChars(cp))).lowercase(Locale.ROOT)
                        folded.forEach { normalized.append(it); starts.add(offset); ends.add(end) }
                    }
                }
                offset = end
            }
            val words = Regex("[a-z]+").findAll(normalized).map {
                val start = starts[it.range.first]
                // Single-letter formula variables are not English words, except
                // articles/pronouns. Keep all raw offsets for partial words and
                // retain backslash-prefixed words in the literal view.
                val matchable = it.value.length > 1 || it.value == "a" || it.value == "i"
                Word(it.value, start, ends[it.range.last], matchable)
            }.toList()
            // Ignored suffixes do not finish a word (e.g. "Am**" or "Am\u200B").
            return Scan(words, normalized.lastOrNull()?.let { it in 'a'..'z' } == true, stableEnd,
                normalized.indices.map { Symbol(normalized[it], starts[it]) })
        }
        private fun asciiLetter(char: Char) = char in 'a'..'z' || char in 'A'..'Z'
        private val MARK_TYPES = setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())
        private val IGNORED_TYPES = MARK_TYPES + Character.FORMAT.toInt()
        // Formatting can occur within a word. Braces/control words and math fences
        // are wrappers, not visible letters. Whitespace/punctuation still separate.
        private val MARKUP = "*_~`{}$".map { it.code }.toSet()
        private val CONFUSABLES = buildMap<Int, Char> {
            fun letters(from: String, to: String) { from.zip(to).forEach { (a, b) -> put(a.code, b) } }
            letters("АВЕКМНОРСТХУІЈЅ", "ABEKMHOPCTXYIJS")
            letters("авекмнорстхуіјѕԁԛһӏѵԝ", "abekmhopctxyijsdqhlvw")
            letters("ΑΒΕΖΗΙΚΜΝΟΡΤΥΧ", "ABEZHIKMNOPTYX")
            letters("αβεικνορτυχϲϳ", "abeikvoptuxcj")
        }
    }
}

/**
 * Holds every suffix that could become an indexed eight-word window. A published
 * prefix can therefore never need retracting. One match switches the rest of this
 * message to reasoning, without an extra model call or a copy of the leaked span.
 */
class ExamTextGuard @JvmOverloads constructor(
    private val index: ExamTextIndex?,
    private val onHold: ((Hold) -> Unit)? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** Count-only observations. Reasons may overlap; elapsed time is not additive. */
    data class Hold(val reasons: Set<String>, val chars: Int, val elapsedMs: Long, val released: Boolean)
    private val pending = StringBuilder()
    private var holdStarted: Long? = null
    private fun observe(cut: Int, reasons: Set<String>, final: Boolean = false) {
        val observer = onHold ?: return
        val now = nanoTime()
        val left = pending.length - cut
        if (left > 0 && !final && holdStarted == null) holdStarted = now
        val start = holdStarted ?: return
        val released = final || left == 0
        observer(Hold(reasons.toSet(), left, ((now - start) / 1_000_000).coerceAtLeast(0), released))
        if (released) holdStarted = null
    }
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
        if (index == null || index.incompleteIndex) return block(0)
        var cut = pending.length
        val reasons = if (onHold == null) null else mutableSetOf<String>()
        var hitStart: Int? = null
        val soft = index.softMatches(pending.toString(), end)
        hitStart = soft.hit
        if (!end && soft.hold != null) { cut = minOf(cut, soft.hold); reasons?.add("indexed_prefix") }
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
                if (incomplete) reasons?.add("open_word")
                if (scan.stableEnd < pending.length) reasons?.add("unstable_encoding")
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
                    // The unfinished word is already long enough to count as an
                    // English word, and its stable prefix cannot become any next
                    // indexed word. No continuation can complete this window.
                    // A different single-letter variable may be ignored entirely,
                    // so it must not disqualify the preceding prefix.
                    if (prefix && incomplete && scan.stableEnd == pending.length) {
                        val partial = scan.words.last().value
                        val alwaysMatchable = partial.length > 1 || partial == "a" || partial == "i"
                        if (alwaysMatchable && !node.hasPrefix(partial)) prefix = false
                    }
                    if (prefix) { reasons?.add("indexed_prefix"); cut = minOf(cut, words[start].start) }
                }
            }
        }
        hitStart?.let { hits++; return block(it) }
        if (end) { observe(pending.length, setOf("end"), true); return take(pending.length) }
        // A literal word can start inside a control word; never publish its backslash alone.
        if (cut > 0 && pending[cut - 1] == '\\') cut--
        // Bound stalled ambiguous output without ever publishing it.
        if (pending.length - cut > 65_536) return block(cut)
        observe(cut, reasons ?: emptySet())
        return take(cut)
    }

    private fun take(length: Int): String = pending.substring(0, length).also { pending.delete(0, length) }
    private fun block(start: Int): String {
        observe(pending.length, setOf("blocked"), true)
        blocked = true
        return take(start) + REASONING.also { pending.setLength(0) }
    }

    companion object {
        const val REASONING = "\n我们改用解题思路来练习：先识别考查的概念，列出关键假设或公式，再换一组情境与数字自行推导，最后检查结论是否合理。"
    }
}
