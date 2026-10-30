package com.coach.ai

/**
 * DESIGN §5.8 第 4 条：内部文本不进用户可见流。
 *
 * 可见流只放行带类型的内容块；`x_coach`、重试、修复调用、`【需查原文:…】` 标记、JSON 原始输出
 * 都走内部通道。落库前做一次确定性过滤：命中协议字样的**整行**丢弃并记日志，标记就地剥掉。
 *
 * 流式安全：每次只交出「不可能是协议字样前缀」的安全前缀，所以一个 token 即使跨 chunk 也不会漏出去。
 */
class VisibleStream(private val onDrop: (Drop) -> Unit = {}) {

    data class Drop(val reason: String, val excerpt: String)

    sealed interface Segment {
        data class Text(val text: String) : Segment
        data class Math(val tex: String, val display: Boolean) : Segment
    }

    private val buffer = StringBuilder()
    private var drops = 0

    /** 已被判定为内部文本而丢弃的段落数（回放断言用）。 */
    val droppedCount: Int get() = drops

    fun push(chunk: String): List<Segment> {
        buffer.append(chunk)
        return drain(final = false)
    }

    fun finish(): List<Segment> = drain(final = true)

    private fun drain(final: Boolean): List<Segment> {
        val out = ArrayList<Segment>()
        while (true) {
            val s = buffer.toString()
            // A model can return raw JSON without any of the named protocol tokens.
            // Keep that structured block internal, including pretty-printed / split chunks.
            val leading = s.trimStart()
            if (leading.startsWith("{") || (leading.startsWith("[") &&
                    (leading.length == 1 || leading[1] in "\"{[]0123456789-"))) {
                val end = structuredEnd(s)
                if (end == null) {
                    if (final) { buffer.setLength(0); drop("raw_json", s) }
                    return out
                }
                buffer.delete(0, end)
                drop("raw_json", s.substring(0, end))
                continue
            }
            val newline = s.indexOf('\n')
            if (newline < 0) break
            buffer.delete(0, newline + 1)
            emit(s.substring(0, newline + 1), out)
        }
        if (buffer.isNotEmpty()) {
            val s = buffer.toString()
            if (final) {
                buffer.setLength(0)
                emit(s, out)
            } else {
                val cut = safeCut(s)
                if (cut > 0) {
                    buffer.delete(0, cut)
                    emit(s.substring(0, cut), out)
                }
            }
        }
        return out
    }

    /**
     * 残行里可以安全交出的长度：不把任何标记/token 的前缀切到两边，也不越过未闭合的查原文标记。
     * 没有这两类风险时立刻交出——正文不等人换行。
     */
    private fun safeCut(s: String): Int {
        var cut = s.length - prefixSuffixLength(s)
        val mark = s.indexOf(MARK)
        if (mark in 0 until cut) cut = mark
        // A formula is one typed block even if its delimiters arrive in different deltas.
        var from = 0
        while (true) {
            val open = s.indexOf("$$", from)
            if (open < 0) break
            val close = s.indexOf("$$", open + 2)
            if (close < 0) { cut = minOf(cut, open); break }
            from = close + 2
        }
        if (s.endsWith('$') && !s.endsWith("$$")) cut = minOf(cut, s.length - 1)
        return cut.coerceAtLeast(0)
    }

    /** Find the end of a JSON-shaped block without ever publishing its partial prefix. */
    private fun structuredEnd(s: String): Int? {
        var depth = 0
        var quoted = false
        var escaped = false
        for ((i, c) in s.withIndex()) {
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) return i + 1 }
            }
        }
        return null
    }

    /** 结尾有多少字符可能是某个标记/token 的前缀（这些字符必须留在缓冲里）。 */
    private fun prefixSuffixLength(s: String): Int {
        var hold = 0
        for (probe in 1..minOf(MAX_LENGTH, s.length)) {
            val tail = s.substring(s.length - probe).lowercase()
            if (MARK.startsWith(tail) || TOKENS.any { it.startsWith(tail) }) hold = probe
        }
        return hold
    }

    private fun emit(text: String, out: MutableList<Segment>) {
        var line = text
        // 1. 查原文标记：剥掉，不显示（§5.1：导师只能提出请求，标记由服务端处理）
        while (true) {
            val start = line.indexOf(MARK)
            if (start < 0) break
            val close = line.indexOf('】', start)
            if (close < 0) {
                drop("source_lookup_marker", line.substring(start))
                line = line.substring(0, start)
                break
            }
            drop("source_lookup_marker", line.substring(start, close + 1))
            line = line.substring(0, start) + line.substring(close + 1)
        }
        if (line.isBlank()) return
        // 2. 协议字样：整行丢弃（落库前过滤，回放断言 0 条漏出）
        val hit = TOKENS.firstOrNull { line.lowercase().contains(it) }
        if (hit != null) {
            drop(hit, line)
            return
        }
        // 3. 只放行带类型的内容块：`$$…$$` 走公式块，其余是正文
        splitMath(line, out)
    }

    private fun splitMath(line: String, out: MutableList<Segment>) {
        var rest = line
        while (true) {
            val start = rest.indexOf("$$")
            if (start < 0) break
            val end = rest.indexOf("$$", start + 2)
            if (end < 0) break
            if (start > 0) out.add(Segment.Text(rest.substring(0, start)))
            out.add(Segment.Math(rest.substring(start + 2, end), display = true))
            rest = rest.substring(end + 2)
        }
        if (rest.isNotEmpty()) out.add(Segment.Text(rest))
    }

    private fun drop(reason: String, excerpt: String) {
        drops++
        onDrop(Drop(reason, excerpt.take(200)))
    }

    companion object {
        /** §5.1 的查原文标记：导师在回答末尾输出，服务端剥掉。 */
        const val MARK = "【需查原文"

        /** DESIGN §5.8 第 4 条点名的协议字样（大小写不敏感，按整行命中即丢）。 */
        val TOKENS = listOf("placeholder", "malformed", "tool_call", "<tool_calls>", "x_coach", "finish_reason")

        private const val MAX_LENGTH = 24
    }
}
