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

internal enum class ReaderTimeoutStage { FIRST, GAP, TOTAL }

internal class ReaderTimeout(val phase: ReaderTimeoutStage) : Exception(phase.name.lowercase()) {
    val stage get() = phase.name.lowercase()
    val userText get() = when (phase) {
        ReaderTimeoutStage.FIRST -> "讲解正文迟迟未开始，已停止等待。可继续阅读或手动重试。"
        ReaderTimeoutStage.GAP -> "讲解正文中途停止更新，已结束本次追问。可继续阅读或手动重试。"
        ReaderTimeoutStage.TOTAL -> "讲解已达到本次总时长上限，已停止。可继续阅读或手动重试。"
    }
}

/** Fixed codes only: never include model text, source text, paths or exception messages. */
internal enum class ReaderFormatReason {
    STREAM_JSON, PAYLOAD_SIZE, PAYLOAD_JSON, ROOT_FIELDS, PARAGRAPH_COUNT,
    PARAGRAPH_FIELDS, PARAGRAPH_TEXT, ANCHORS, DUPLICATE_ANCHORS, ANSWER_LOCATOR, ANSWER_CITATION
}
internal class ReaderFormat(
    val servedBy: String? = null,
    val reason: ReaderFormatReason = ReaderFormatReason.STREAM_JSON,
    val streamError: StreamJsonError? = null,
) : Exception("Invalid reader JSON")

/** Fixed sub-codes for [ReaderFormatReason.STREAM_JSON]: never include model text, chars or excerpts. */
internal enum class StreamJsonError {
    MAX_SIZE,
    KEY_TOO_LONG,
    BAD_SURROGATE,
    BAD_NUMBER,
    BAD_UNICODE_ESCAPE,
    BAD_ESCAPE,
    MAX_DEPTH,
    ROOT_NON_OBJECT,
    FENCE_BEFORE_ROOT,
    PROSE_ASCII_BEFORE_ROOT,
    PROSE_CJK_BEFORE_ROOT,
    BEFORE_ROOT,
    AFTER_ROOT,
    UNEXPECTED_AFTER_VALUE,
    UNEXPECTED_NUMBER,
    UNEXPECTED_LITERAL,
    SYNTAX,
}

/** Prefix lexer: only decoded, non-whitespace characters at configured body paths count.
 * Stores keys/grammar only, never source or explanation text. Final citation gate is separate.
 */
internal class ReaderBodyProgress(
    private val maxSize: Int = 12_000,
    private val progressPaths: Set<Pair<List<String>, String>> = DEFAULT_PROGRESS_PATHS,
    private val progressArrayPaths: Set<List<String>> = emptySet(),
    private val allowNumbers: Boolean = false,
) {
    companion object {
        val DEFAULT_PROGRESS_PATHS = setOf(listOf("paragraphs", "*") to "text")
        val GUIDE_PROGRESS_PATHS = setOf(
            listOf("paragraphs", "*") to "text",
            listOf("hardPoints", "*") to "explanation",
            emptyList<String>() to "recap",
        )
        val GUIDE_PROGRESS_ARRAY_PATHS = setOf(listOf("brief"), listOf("summary"))
        val STRUCTURE_PROGRESS_PATHS = setOf(
            listOf("sections", "*") to "title",
            listOf("sections", "*") to "startBlockId",
        )
    }

    private data class Frame(val kind: Char, val path: List<String>, var phase: Int = 0, var key: String = "")
    private val stack = mutableListOf<Frame>()
    private var string = false
    private var number = false
    private var numberHasDigit = false
    private var keyString = false
    private var bodyString = false
    private var escape = false
    private var unicode: String? = null
    private var key = StringBuilder()
    private var highSurrogate = false
    var invalid = false; private set
    var invalidReason: StreamJsonError? = null; private set
    private var root = false
    private var size = 0
    private var fenced = false
    private var openTicks = 0
    private var openInfo = StringBuilder()
    private var inOpenFenceLine = false
    private var closeTicks = 0
    private var closedFence = false

    private fun fail(reason: StreamJsonError) {
        if (invalidReason == null) invalidReason = reason
        invalid = true
    }

    fun push(delta: String): Boolean {
        var progress = false
        for (c in delta) {
            if (invalid) break
            if (++size > maxSize) { fail(StreamJsonError.MAX_SIZE); break }
            fun decoded(d: Char) {
                if (keyString) { if (key.length < 64) key.append(d) else fail(StreamJsonError.KEY_TOO_LONG) }
                if (bodyString) {
                    if (highSurrogate) {
                        highSurrogate = false
                        if (d.isLowSurrogate()) progress = true else fail(StreamJsonError.BAD_SURROGATE)
                    } else if (d.isHighSurrogate()) highSurrogate = true
                    else if (d.isLowSurrogate()) fail(StreamJsonError.BAD_SURROGATE)
                    else if (!d.isWhitespace() && !d.isISOControl() && Character.getType(d) != Character.FORMAT.toInt()) progress = true
                }
            }
            if (number) {
                if (c in '0'..'9') {
                    numberHasDigit = true
                    continue
                }
                number = false
                if (!numberHasDigit) { fail(StreamJsonError.BAD_NUMBER); continue }
                stack.lastOrNull()?.phase = 3
            }
            if (string) {
                val hex = unicode
                if (hex != null) {
                    if (c.digitToIntOrNull(16) == null) { fail(StreamJsonError.BAD_UNICODE_ESCAPE); continue }
                    val next = hex + c
                    if (next.length == 4) { unicode = null; decoded(next.toInt(16).toChar()) } else unicode = next
                } else if (escape) {
                    escape = false
                    when (c) {
                        'u' -> unicode = ""
                        '"', '\\', '/' -> decoded(c)
                        'b', 'f', 'n', 'r', 't' -> decoded(' ')
                        else -> fail(StreamJsonError.BAD_ESCAPE)
                    }
                } else when {
                    c == '\\' -> escape = true
                    c == '"' -> {
                        string = false
                        if (highSurrogate) fail(StreamJsonError.BAD_SURROGATE)
                        val f = stack.last()
                        if (keyString) { f.key = key.toString(); f.phase = 1 } else f.phase = 3
                    }
                    // Match the final JSON parser; decoded() excludes controls from body progress.
                    else -> decoded(c)
                }
                continue
            }
            if (inOpenFenceLine) {
                if (c == '\n') {
                    inOpenFenceLine = false
                    val tag = openInfo.toString().trim().lowercase()
                    if (tag.isNotEmpty() && tag != "json") fail(StreamJsonError.FENCE_BEFORE_ROOT)
                } else if (openInfo.length >= 16) {
                    fail(StreamJsonError.FENCE_BEFORE_ROOT)
                } else {
                    openInfo.append(c)
                }
                continue
            }
            if (openTicks in 1..2) {
                if (c == '`') {
                    openTicks++
                    if (openTicks == 3) { fenced = true; inOpenFenceLine = true }
                } else {
                    fail(StreamJsonError.FENCE_BEFORE_ROOT)
                }
                continue
            }
            if (closeTicks in 1..2) {
                if (c == '`') {
                    closeTicks++
                    if (closeTicks == 3) closedFence = true
                } else {
                    fail(StreamJsonError.AFTER_ROOT)
                }
                continue
            }
            if (c.isWhitespace()) continue
            val f = stack.lastOrNull()
            when {
                f == null && !root && !fenced && c == '`' -> openTicks = 1
                f == null && root && fenced && !closedFence && c == '`' -> closeTicks = 1
                c == '"' && f != null && (f.phase == 0 && f.kind == '{' || f.phase == 2) -> {
                    string = true; keyString = f.kind == '{' && f.phase == 0; key = StringBuilder()
                    bodyString = !keyString && (
                        (f.kind == '{' && (f.path to f.key) in progressPaths) ||
                        (f.kind == '[' && f.path in progressArrayPaths)
                    )
                }
                (c == '-' || c in '0'..'9') && allowNumbers && f?.phase == 2 -> {
                    number = true
                    numberHasDigit = c in '0'..'9'
                }
                c == ':' && f?.phase == 1 -> f.phase = 2
                c == ',' && f?.phase == 3 -> f.phase = if (f.kind == '{') 0 else 2
                (c == '{' || c == '[') && (f?.phase == 2 || f == null && !root) -> {
                    if (stack.size >= 8) { fail(StreamJsonError.MAX_DEPTH); continue }
                    val path = if (f == null) emptyList() else f.path + if (f.kind == '{') f.key else "*"
                    if (f == null) { root = true; if (c != '{') fail(StreamJsonError.ROOT_NON_OBJECT) } else f.phase = 3
                    stack.add(Frame(c, path, if (c == '{') 0 else 2))
                }
                f != null && (c == '}' && f.kind == '{' || c == ']' && f.kind == '[') && f.phase in setOf(0, 2, 3) -> stack.removeAt(stack.lastIndex)
                else -> fail(when {
                    f == null && !root -> when {
                        c == '`' -> StreamJsonError.FENCE_BEFORE_ROOT
                        c in 'A'..'Z' || c in 'a'..'z' -> StreamJsonError.PROSE_ASCII_BEFORE_ROOT
                        c.code in 0x4E00..0x9FFF || c.code in 0x3000..0x303F || c.code in 0xFF00..0xFFEF -> StreamJsonError.PROSE_CJK_BEFORE_ROOT
                        else -> StreamJsonError.BEFORE_ROOT
                    }
                    f == null && root -> StreamJsonError.AFTER_ROOT
                    f != null && f.phase == 3 -> StreamJsonError.UNEXPECTED_AFTER_VALUE
                    f != null && f.phase == 2 && (c == '-' || c in '0'..'9') -> StreamJsonError.UNEXPECTED_NUMBER
                    f != null && f.phase == 2 && c in setOf('t', 'f', 'n') -> StreamJsonError.UNEXPECTED_LITERAL
                    else -> StreamJsonError.SYNTAX
                })
            }
        }
        return progress
    }
}

internal class ReaderTiming(
    private val limits: ReaderTimeouts,
    val started: Long = System.nanoTime(),
    private val bodyProgressFactory: () -> ReaderBodyProgress = { ReaderBodyProgress() },
) {
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var parser = bodyProgressFactory()
    @Volatile private var attemptStarted = System.nanoTime()
    @Volatile private var servedBy: String? = null
    @Volatile var first: Long? = null; private set
    @Volatile var last: Long? = null; private set
    @Volatile private var stopped = false
    @Volatile private var expired: ReaderTimeoutStage? = null
    @Volatile private var invalid = false
    @Volatile private var streamError: StreamJsonError? = null
    @Volatile private var handle: BridgeStreamHandle? = null
    @Volatile private var sourceProcess: Process? = null
    fun source(process: Process?) { sourceProcess = process; if (stopped) process?.destroyForcibly() }
    fun stop() { stopped = true; sourceProcess?.destroyForcibly(); handle?.cancel(); signal.trySend(Unit) }
    suspend fun collect(bridge: BridgeClient, prompt: ChatRequest): BridgeResult = collect(bridge) { prompt }
    suspend fun collect(bridge: BridgeClient, prepare: suspend () -> ChatRequest): BridgeResult = coroutineScope {
        val worker = async(Dispatchers.IO) {
            bridge.streamChat(prepare(), object : BridgeStreamSink {
                override val supportsReset = true
                override fun onMeta(servedBy: String) { this@ReaderTiming.servedBy = servedBy }
                override fun onReset() {
                    parser = bodyProgressFactory()
                    invalid = false
                    streamError = null
                    last = null
                    attemptStarted = System.nanoTime()
                    signal.trySend(Unit)
                }
                override fun onDelta(text: String, atMs: Long) {
                    val progress = parser.push(text)
                    if (parser.invalid) { streamError = parser.invalidReason; invalid = true; signal.trySend(Unit) }
                    if (progress && !parser.invalid) {
                        val now = System.nanoTime()
                        val previous = last
                        val bodyDeadline = previous?.let { it + limits.gapMs * 1_000_000 } ?: (attemptStarted + limits.firstMs * 1_000_000)
                        val totalDeadline = started + limits.totalMs * 1_000_000
                        if (now >= minOf(bodyDeadline, totalDeadline)) {
                            expired = timeoutStage(totalDeadline, bodyDeadline, previous)
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
                val bodyDeadline = previous?.let { it + limits.gapMs * 1_000_000 } ?: (attemptStarted + limits.firstMs * 1_000_000)
                val totalDeadline = started + limits.totalMs * 1_000_000
                val remaining = minOf(bodyDeadline, totalDeadline) - System.nanoTime()
                if (remaining <= 0) throw ReaderTimeout(timeoutStage(totalDeadline, bodyDeadline, previous))
                if (invalid) throw ReaderFormat(servedBy, ReaderFormatReason.STREAM_JSON, streamError)
                if (worker.isCompleted) return@coroutineScope worker.await()
                withTimeoutOrNull((remaining + 999_999) / 1_000_000) { signal.receive() }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        } finally { stop(); worker.cancel() }
    }

    private fun timeoutStage(totalDeadline: Long, bodyDeadline: Long, previous: Long?) = when {
        totalDeadline <= bodyDeadline -> ReaderTimeoutStage.TOTAL
        previous == null -> ReaderTimeoutStage.FIRST
        else -> ReaderTimeoutStage.GAP
    }
}
