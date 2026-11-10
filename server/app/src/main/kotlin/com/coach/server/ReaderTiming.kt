package com.coach.server

import com.coach.ai.*
import com.coach.protocol.ReaderTimeoutPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Independent monotonic deadlines, including queue/header time. Invalid configuration fails at startup. */
data class ReaderTimeouts(val firstMs: Long = 30_000, val gapMs: Long = 30_000, val totalMs: Long = ReaderTimeoutPolicy.DEFAULT_TOTAL_MS) {
    init { require(listOf(firstMs, gapMs, totalMs).all { it in 1..86_400_000 }) { "Reader timeouts must be 1..86400000 ms" } }
    companion object {
        fun environment(env: Map<String, String> = System.getenv()) = ReaderTimeouts(
            value(env, "FIRST_CONTENT", 30_000), value(env, "CONTENT_GAP", 30_000), value(env, "TOTAL", ReaderTimeoutPolicy.DEFAULT_TOTAL_MS))
        private fun value(env: Map<String, String>, name: String, default: Long): Long =
            env["COACH_READER_${name}_TIMEOUT_MS"]?.let { requireNotNull(it.toLongOrNull()) { "Invalid reader timeout: $name" } } ?: default
    }
}

internal class ReaderTimeout(val stage: String) : Exception(stage) {
    val userText get() = when (stage) {
        "first" -> "讲解正文迟迟未开始，已停止等待。可继续阅读或手动重试。"
        "gap" -> "讲解正文中途停止更新，已结束本次追问。可继续阅读或手动重试。"
        else -> "讲解已达到本次总时长上限，已停止。可继续阅读或手动重试。"
    }
}

internal class ReaderFormat : Exception("Invalid reader JSON")

/** Prefix lexer: only decoded, non-whitespace characters at paragraphs[*].text count.
 * Stores keys/grammar only, never source or explanation text. Final citation gate is separate.
 */
internal class ReaderBodyProgress {
    private data class Frame(val kind: Char, val path: List<String>, var phase: Int = 0, var key: String = "")
    private val stack = mutableListOf<Frame>()
    private var string = false
    private var keyString = false
    private var bodyString = false
    private var escape = false
    private var unicode: String? = null
    private var key = StringBuilder()
    private var highSurrogate = false
    var invalid = false; private set
    private var root = false
    private var size = 0
    fun push(delta: String): Boolean {
        var progress = false
        for (c in delta) {
            if (++size > 12_000 || invalid) { invalid = true; break }
            fun decoded(d: Char) {
                if (keyString) { if (key.length < 64) key.append(d) else invalid = true }
                if (bodyString) {
                    if (highSurrogate) {
                        highSurrogate = false
                        if (d.isLowSurrogate()) progress = true else invalid = true
                    } else if (d.isHighSurrogate()) highSurrogate = true
                    else if (d.isLowSurrogate()) invalid = true
                    else if (!d.isWhitespace() && !d.isISOControl() && Character.getType(d) != Character.FORMAT.toInt()) progress = true
                }
            }
            if (string) {
                val hex = unicode
                if (hex != null) {
                    if (c.digitToIntOrNull(16) == null) { invalid = true; continue }
                    val next = hex + c
                    if (next.length == 4) { unicode = null; decoded(next.toInt(16).toChar()) } else unicode = next
                } else if (escape) {
                    escape = false
                    when (c) {
                        'u' -> unicode = ""
                        '"', '\\', '/' -> decoded(c)
                        'b', 'f', 'n', 'r', 't' -> decoded(' ')
                        else -> invalid = true
                    }
                } else when {
                    c == '\\' -> escape = true
                    c == '"' -> {
                        string = false
                        if (highSurrogate) invalid = true
                        val f = stack.last()
                        if (keyString) { f.key = key.toString(); f.phase = 1 } else f.phase = 3
                    }
                    c < ' ' -> invalid = true
                    else -> decoded(c)
                }
                continue
            }
            if (c.isWhitespace()) continue
            val f = stack.lastOrNull()
            when {
                c == '"' && f != null && (f.phase == 0 && f.kind == '{' || f.phase == 2) -> {
                    string = true; keyString = f.kind == '{' && f.phase == 0; key = StringBuilder()
                    bodyString = !keyString && f.kind == '{' && f.path == listOf("paragraphs", "*") && f.key == "text"
                }
                c == ':' && f?.phase == 1 -> f.phase = 2
                c == ',' && f?.phase == 3 -> f.phase = if (f.kind == '{') 0 else 2
                (c == '{' || c == '[') && (f?.phase == 2 || f == null && !root) -> {
                    if (stack.size >= 8) { invalid = true; continue }
                    val path = if (f == null) emptyList() else f.path + if (f.kind == '{') f.key else "*"
                    if (f == null) { root = true; if (c != '{') invalid = true } else f.phase = 3
                    stack.add(Frame(c, path, if (c == '{') 0 else 2))
                }
                f != null && (c == '}' && f.kind == '{' || c == ']' && f.kind == '[') && f.phase in setOf(0, 2, 3) -> stack.removeAt(stack.lastIndex)
                else -> invalid = true // The reader schema contains only objects, arrays and strings.
            }
        }
        return progress
    }
}

internal class ReaderTiming(private val limits: ReaderTimeouts, val started: Long = System.nanoTime()) {
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val parser = ReaderBodyProgress()
    @Volatile var first: Long? = null; private set
    @Volatile var last: Long? = null; private set
    @Volatile private var stopped = false
    @Volatile private var expired: String? = null
    @Volatile private var invalid = false
    @Volatile private var handle: BridgeStreamHandle? = null
    @Volatile private var sourceProcess: Process? = null
    fun source(process: Process?) { sourceProcess = process; if (stopped) process?.destroyForcibly() }
    fun stop() { stopped = true; sourceProcess?.destroyForcibly(); handle?.cancel(); signal.trySend(Unit) }
    suspend fun collect(bridge: BridgeClient, prompt: ChatRequest): BridgeResult = collect(bridge) { prompt }
    suspend fun collect(bridge: BridgeClient, prepare: suspend () -> ChatRequest): BridgeResult = coroutineScope {
        val worker = async(Dispatchers.IO) {
            bridge.streamChat(prepare(), object : BridgeStreamSink {
                override fun onDelta(text: String, atMs: Long) {
                    val progress = parser.push(text)
                    if (parser.invalid) { invalid = true; signal.trySend(Unit) }
                    if (progress && !parser.invalid) {
                        val now = System.nanoTime()
                        val previous = last
                        val bodyDeadline = previous?.let { it + limits.gapMs * 1_000_000 } ?: (started + limits.firstMs * 1_000_000)
                        val totalDeadline = started + limits.totalMs * 1_000_000
                        if (now >= minOf(bodyDeadline, totalDeadline)) {
                            expired = if (totalDeadline <= bodyDeadline) "total" else if (previous == null) "first" else "gap"
                        } else if (expired == null) {
                            if (first == null) first = now
                            last = now
                        }
                        signal.trySend(Unit)
                    }
                }
            }) { handle = it; if (stopped) it.cancel() }
        }
        worker.invokeOnCompletion { signal.trySend(Unit) }
        try {
            while (true) {
                if (stopped) return@coroutineScope worker.await()
                expired?.let { throw ReaderTimeout(it) }
                val previous = last
                val bodyDeadline = previous?.let { it + limits.gapMs * 1_000_000 } ?: (started + limits.firstMs * 1_000_000)
                val totalDeadline = started + limits.totalMs * 1_000_000
                val remaining = minOf(bodyDeadline, totalDeadline) - System.nanoTime()
                if (remaining <= 0) throw ReaderTimeout(if (totalDeadline <= bodyDeadline) "total" else if (previous == null) "first" else "gap")
                if (invalid) throw ReaderFormat()
                if (worker.isCompleted) return@coroutineScope worker.await()
                withTimeoutOrNull((remaining + 999_999) / 1_000_000) { signal.receive() }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        } finally { stop(); worker.cancel() }
    }
}
