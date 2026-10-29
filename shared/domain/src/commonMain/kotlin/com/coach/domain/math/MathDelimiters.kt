package com.coach.domain.math

/** Offset ranges refer to the scanned text; model repair deliberately changes offsets. */
data class MathSegment(val text: String? = null, val math: String? = null, val display: Boolean = false, val start: Int = 0, val end: Int = 0)
data class MathTokens(val segments: List<MathSegment>, val unresolved: Int)

/** Kotlin port of tools/mathsvc/delims/reference.ts. Library text is never repaired. */
object MathDelimiters {
    private fun space(c: Char?) = c == null || c.isWhitespace()
    private fun digit(c: Char?) = c != null && c in '0'..'9'
    private val html = Regex("^</?[A-Za-z][^<>\\n]*>")
    private val han = Regex("[\\u3400-\\u9fff]")
    private val greek = "\\u0370-\\u03ff\\u1f00-\\u1fff"
    private val words = setOf("ATE", "ATT", "Cov", "E", "FOC", "GDP", "IV", "MRS", "MRT", "OLS", "Pr", "SE", "Var")
    private fun valid(body: String) = !Regex("</?[A-Za-z][^<>]*>").containsMatchIn(body) &&
        !han.containsMatchIn(body.replace(Regex("\\\\(?:text|operatorname|mathrm|textbf|textit)\\s*\\{[^{}]*}"), ""))
    private fun literal(s: String, needle: String, from: Int): Int {
        var i = from
        while (i <= s.length - needle.length) {
            if (s.startsWith(needle, i)) {
                var n = 0; var p = i - 1
                while (p >= 0 && s[p--] == '\\') n++
                if (n % 2 == 0) return i
            }
            if (s.startsWith("\n\n", i)) return -1
            i++
        }
        return -1
    }
    private fun closingDollar(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            when (s[i]) {
                '\n' -> return -1
                '\\' -> i += 2
                '$' -> return if (!space(s.getOrNull(i - 1)) && !digit(s.getOrNull(i + 1))) i else -1
                else -> i++
            }
        }
        return -1
    }
    private fun scan(s: String): MathTokens {
        val out = mutableListOf<MathSegment>()
        val buffer = StringBuilder(); var begin = 0; var i = 0; var unresolved = 0
        fun append(chunk: String) { if (buffer.isEmpty()) begin = i; buffer.append(chunk) }
        fun flush() { if (buffer.isNotEmpty()) { out += MathSegment(text = buffer.toString(), start = begin, end = begin + buffer.length); buffer.clear() } }
        while (i < s.length) {
            val c = s[i]
            if ((c == '`' || c == '~') && (i == 0 || s[i - 1] == '\n')) {
                val fence = Regex("^(`{3,}|~{3,})").find(s.substring(i))?.value
                if (fence != null) {
                    var p = s.indexOf('\n', i).let { if (it < 0) s.length else it + 1 }
                    var end = s.length
                    while (p < s.length) {
                        val e = s.indexOf('\n', p).let { if (it < 0) s.length else it }
                        val line = s.substring(p, e).trimEnd()
                        if (line.startsWith(fence) && Regex("(`{3,}|~{3,})").matches(line)) { end = e; break }
                        p = if (e == s.length) e else e + 1
                    }
                    append(s.substring(i, end)); i = end; continue
                }
            }
            if (c == '`') {
                var run = 1; while (s.getOrNull(i + run) == '`') run++
                val close = s.indexOf("`".repeat(run), i + run)
                val end = if (close >= 0 && !s.substring(i + run, close).contains("\n\n")) close + run else i + run
                append(s.substring(i, end)); i = end; continue
            }
            if (c == '\\' && s.getOrNull(i + 1) in listOf('(', '[')) {
                val display = s[i + 1] == '['
                val limit = s.indexOf('\n', i + 2).let { if (it < 0) s.length else it }
                val close = literal(s.substring(0, limit), if (display) "\\]" else "\\)", i + 2)
                if (close >= 0 && s.substring(i + 2, close).isNotBlank()) {
                    flush(); out += MathSegment(math = s.substring(i + 2, close).trim(), display = display, start = i, end = close + 2); i = close + 2
                } else { unresolved++; append(s.substring(i, i + 2)); i += 2 }
                continue
            }
            if (c == '\\') { val end = minOf(i + 2, s.length); append(s.substring(i, end)); i = end; continue }
            if (c == '$') {
                val display = s.getOrNull(i + 1) == '$'
                val marker = if (display) 2 else 1
                if (!display && space(s.getOrNull(i + 1))) { append("$"); i++; continue }
                val close = if (display) literal(s, "$$", i + 2) else closingDollar(s, i + 1)
                val body = if (close >= 0) s.substring(i + marker, close) else ""
                if (close >= 0 && body.isNotBlank() && (display || valid(body))) {
                    flush(); out += MathSegment(math = if (display) body.trim() else body, display = display, start = i, end = close + marker); i = close + marker
                } else { unresolved++; append("$".repeat(marker)); i += marker }
                continue
            }
            append(c.toString()); i++
        }
        flush(); return MathTokens(out, unresolved)
    }
    fun tokenize(text: String, model: Boolean = false): MathTokens {
        val first = scan(text)
        if (!model) return first
        val parts = first.segments.map { if (it.text != null) scan(repair(it.text)) else MathTokens(listOf(it), 0) }
        return MathTokens(parts.flatMap { it.segments }, parts.sumOf { it.unresolved })
    }
    private fun brace(s: String) = s.replace(Regex("([_^])([A-Za-z]{2,}|[$greek]{2,})")) { "${it.groupValues[1]}{${it.groupValues[2]}}" }
    private fun identifier(s: String, start: Int): Int? {
        val tail = s.substring(start)
        val g = Regex("^[$greek]").find(tail)?.value
        val w = Regex("^[A-Za-z]+").find(tail)?.value.orEmpty()
        if (g == null && (w.isEmpty() || (w.length > 1 && w !in words))) return null
        var end = start + (g?.length ?: w.length)
        while (s.getOrNull(end) == '_' || s.getOrNull(end) == '^') {
            val p = end + 1
            if (s.getOrNull(p) == '{') {
                val close = s.indexOf('}', p + 1)
                if (close < 0 || close - p > 42 || !Regex("[A-Za-z0-9,$greek]+").matches(s.substring(p + 1, close))) break
                end = close + 1
            } else if (s.getOrNull(p)?.toString()?.matches(Regex("[A-Za-z0-9$greek]")) == true) end = p + 1 else break
        }
        return end
    }
    private fun rhs(s: String, start: Int): Int? {
        val stack = mutableListOf<Char>(); val pairs = mapOf('(' to ')', '[' to ']', '{' to '}')
        var i = start; var safe: Int? = null; var atom = false; var token = ""
        val limit = minOf(s.length, start + 400)
        while (i < limit) {
            val c = s[i]
            if (c == '\n' || c == '\r') break
            if (c.isWhitespace()) { i++; continue }
            if ((c == '<' && html.containsMatchIn(s.substring(i))) || c in "$`|\\") break
            val number = Regex("^(?:\\d+(?:\\.\\d+)?|\\.\\d+)").find(s.substring(i))?.value
            val id = if (number == null) identifier(s, i) else null
            if (number != null || id != null) {
                i = id ?: (i + number!!.length); atom = true; token = "atom"; if (stack.isEmpty()) safe = i; continue
            }
            if (c in pairs) { stack += pairs.getValue(c); i++; token = "open"; continue }
            if (c in pairs.values) {
                if (stack.lastOrNull() != c) break
                stack.removeAt(stack.lastIndex); i++; token = "close"; if (stack.isEmpty() && atom) safe = i; continue
            }
            if (c == ',') { if (stack.isEmpty()) break; i++; token = "comma"; continue }
            if (c in "+-−–*/×·⋅=≈≠≤≥%") { i++; token = "operator"; continue }
            break
        }
        if (i == limit && limit < s.length || stack.isNotEmpty() || token in listOf("operator", "open", "comma")) return null
        return if (atom) safe else null
    }
    private fun equations(s: String): String {
        val out = StringBuilder(); var copied = 0; var i = 0
        while (i < s.length) {
            if (!Regex("[A-Za-z$greek]").matches(s[i].toString()) || s.getOrNull(i - 1)?.toString()?.matches(Regex("[\\p{L}0-9_$\\\\`]")) == true) { i++; continue }
            var p = i; var depth = 0; var eq = -1
            while (p < minOf(s.length, i + 60)) {
                val c = s[p]
                if (c in "([") depth++
                else if (c in ")]") { depth--; if (depth < 0) break }
                else if (c == '=' && depth == 0) { eq = p; break }
                else if (!Regex("[A-Za-z0-9${greek}_^{}()\\[\\]+*/×·⋅%\\- ]").matches(c.toString())) break
                p++
            }
            if (eq < 0) { i++; continue }
            val lhs = s.substring(i, eq).trimEnd()
            val plain = lhs.replace(Regex("[_^](?:\\{[^{}]*}|[A-Za-z]+)"), "")
            if (lhs.isEmpty() || !Regex("[A-Za-z${greek}0-9)\\]]").matches(lhs.last().toString()) || Regex("[A-Za-z]{2,}").findAll(plain).any { it.value !in words }) { i++; continue }
            var r = eq + 1; while (s.getOrNull(r) == ' ' || s.getOrNull(r) == '\t') r++
            val end = rhs(s, r)
            if (end == null) { i++; continue }
            out.append(s.substring(copied, i)).append('$').append(brace(lhs)).append(" = ").append(s.substring(r, end).trim()).append('$')
            copied = end; i = end
        }
        return out.append(s.substring(copied)).toString()
    }
    private fun repairPart(input: String): String {
        var s = input.replace(Regex("\\$(?:\\\\)?(?:times|imes)", RegexOption.IGNORE_CASE), "×")
        s = s.replace(Regex("^(\\s*[-*+]\\s+)([^:：\\n]+?)(\\s*)([:：])", RegexOption.MULTILINE)) {
            val v = it.groupValues[2].trim()
            val single = Regex("^[A-Za-z]").containsMatchIn(v) || Regex("^[$greek]$").matches(v)
            val list = Regex("^[A-Za-z$greek](?:\\s*,\\s*[A-Za-z$greek])+$").matches(v)
            if (v.length in 1..220 && !v.contains('$') && !v.contains('`') && !han.containsMatchIn(v) && (single || list || Regex("[_^=≤≥≈≠]").containsMatchIn(v)))
                "${it.groupValues[1]}\$$v\$${it.groupValues[3]}${it.groupValues[4]}" else it.value
        }
        s = s.replace(Regex("(E\\s*\\[[^\\]\\n]+]\\s*(?:=|≈|≠|≤|≥|<|>)\\s*[-+]?(?:\\d+(?:\\.\\d+)?|[A-Za-z$greek][A-Za-z0-9]*(?:_(?:\\{[^{}\\n]+}|[A-Za-z0-9$greek]))?))")) { "\$${it.value.trim()}\$" }
        s = s.replace(Regex("([A-Za-z$greek][A-Za-z0-9]*(?:_(?:\\{[^{}\\n]+}|[A-Za-z0-9$greek]+))?\\s*(?:≈|≠|≤|≥|<|>)\\s*[-+−]?\\d+(?:\\.\\d+)?)")) { "\$${brace(it.value.trim())}\$" }
        return equations(s)
    }
    private fun repair(s: String): String {
        val out = StringBuilder(); var i = 0; var start = 0
        while (i < s.length) {
            val boundary = if (s[i] == '<') html.find(s.substring(i))?.value else if (s.startsWith("\\$", i)) "\\$" else null
            if (boundary == null) { i++; continue }
            out.append(repairPart(s.substring(start, i))).append(boundary); i += boundary.length; start = i
        }
        return out.append(repairPart(s.substring(start))).toString()
    }
}
