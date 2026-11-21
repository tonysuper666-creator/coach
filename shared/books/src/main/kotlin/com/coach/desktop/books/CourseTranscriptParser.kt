package com.coach.desktop.books

internal data class TranscriptCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long?,
    val timestampLabel: String,
    val text: String,
)

internal data class TranscriptAnchor(
    val pageNumber: Int,
    val startUtf16: Int,
    val endUtf16: Int,
    val timestampLabel: String,
    val excerpt: String,
)

/**
 * Pure-Kotlin parser for `.srt`, `.vtt`, and inline timestamped `.md`/`.txt` lecture transcripts.
 * Preserves exact `[mm:ss]` / `[hh:mm:ss]` labels in rendered pages so Gate-2 UTF-16 anchor checks
 * and in-app timestamp jumps resolve deterministically.
 */
internal object CourseTranscriptParser {
    private val inlineTimestampRegex = Regex("""\[((?:\d{1,2}:)?\d{1,2}:\d{2})(?:[.,]\d{1,3})?\]""")
    private val cueRangeRegex = Regex("""^\s*((?:\d{1,2}:)?\d{1,2}:\d{2}(?:[.,]\d{1,3})?)\s*-->\s*((?:\d{1,2}:)?\d{1,2}:\d{2}(?:[.,]\d{1,3})?)(?:\s+.*)?$""")
    private val htmlTagRegex = Regex("""</?(?:v(?:\s+[^>]*)?|c(?:\.[^>]*)?|i|b|u|ruby|rt|lang)[^>]*>""")
    private val voiceOpenTagRegex = Regex("""<v\s+([^>]+)>""")

    fun looksLikeTranscript(text: String, format: String): Boolean {
        val fmt = format.lowercase()
        if (fmt == "srt" || fmt == "vtt") return true
        if (fmt != "md" && fmt != "txt") return false
        val normalized = normalizeNewlines(text)
        if (normalized.trimStart('\uFEFF').startsWith("WEBVTT")) return true
        return inlineTimestampRegex.findAll(normalized).take(3).count() >= 3
    }

    fun parse(raw: String, format: String): List<TranscriptCue> {
        val normalized = normalizeNewlines(raw).trimStart('\uFEFF')
        return when (format.lowercase()) {
            "vtt" -> parseVtt(normalized)
            "srt" -> parseSrt(normalized)
            else -> when {
                normalized.trimStart().startsWith("WEBVTT") -> parseVtt(normalized)
                cueRangeRegex.containsMatchIn(normalized) -> parseSrt(normalized)
                else -> parseInlineTimestamped(normalized)
            }
        }
    }

    fun extractToBookPages(
        raw: String,
        format: String,
        maxCharsPerPage: Int = 2000,
        maxCuesPerPage: Int = 24,
    ): List<BookPage> {
        val cues = parse(raw, format)
        if (cues.isEmpty()) {
            val cleaned = normalizeNewlines(raw).trim()
            require(cleaned.isNotBlank()) { "转写文本中没有有效内容。" }
            return TextbookExtraction.paginate(cleaned)
        }
        val pages = mutableListOf<BookPage>()
        val current = mutableListOf<TranscriptCue>()
        var currentChars = 0

        fun flush() {
            if (current.isEmpty()) return
            val first = current.first().timestampLabel
            val last = current.last().timestampLabel
            val header = if (first == last) "课堂转写 [$first]" else "课堂转写 [$first - $last]"
            val body = current.joinToString("\n") { cue ->
                "[${cue.timestampLabel}] ${cue.text}"
            }
            pages += BookPage(pages.size + 1, "$header\n$body")
            current.clear()
            currentChars = 0
        }

        for (cue in cues) {
            val lineLen = cue.timestampLabel.length + cue.text.length + 4
            if (current.isNotEmpty() && (currentChars + lineLen > maxCharsPerPage || current.size >= maxCuesPerPage)) {
                flush()
            }
            current += cue
            currentChars += lineLen
        }
        flush()
        return pages
    }

    fun parseSrt(raw: String): List<TranscriptCue> {
        val blocks = normalizeNewlines(raw).trimStart('\uFEFF').split(Regex("\n{2,}"))
        val cues = mutableListOf<TranscriptCue>()
        for (block in blocks) {
            val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val timingIdx = lines.indexOfFirst { cueRangeRegex.matches(it) }
            if (timingIdx < 0) continue
            val match = cueRangeRegex.matchEntire(lines[timingIdx]) ?: continue
            val startMs = parseTimestampToMs(match.groupValues[1]) ?: continue
            val endMs = parseTimestampToMs(match.groupValues[2])
            val bodyLines = lines.drop(timingIdx + 1)
            val text = cleanCueText(bodyLines.joinToString(" "))
            if (text.isNotBlank()) {
                cues += TranscriptCue(
                    index = cues.size + 1,
                    startMs = startMs,
                    endMs = endMs,
                    timestampLabel = formatTimestamp(startMs),
                    text = text,
                )
            }
        }
        return cues
    }

    fun parseVtt(raw: String): List<TranscriptCue> {
        val text = normalizeNewlines(raw).trimStart('\uFEFF')
        val lines = text.lines()
        val bodyStart = lines.indexOfFirst { it.trim().isEmpty() }.takeIf { it >= 0 } ?: 0
        val content = if (lines.firstOrNull()?.trimStart()?.startsWith("WEBVTT") == true) {
            lines.drop(bodyStart + 1).joinToString("\n")
        } else {
            text
        }
        val blocks = content.split(Regex("\n{2,}"))
        val cues = mutableListOf<TranscriptCue>()
        for (block in blocks) {
            val blockLines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (blockLines.isEmpty()) continue
            val head = blockLines.first().trimStart()
            if (head.startsWith("NOTE") || head.startsWith("STYLE") || head.startsWith("REGION")) continue
            val timingIdx = blockLines.indexOfFirst { cueRangeRegex.matches(it) }
            if (timingIdx < 0) continue
            val match = cueRangeRegex.matchEntire(blockLines[timingIdx]) ?: continue
            val startMs = parseTimestampToMs(match.groupValues[1]) ?: continue
            val endMs = parseTimestampToMs(match.groupValues[2])
            val rawBody = blockLines.drop(timingIdx + 1).joinToString(" ")
            val speakerMatch = voiceOpenTagRegex.find(rawBody)
            val cleaned = cleanCueText(rawBody)
            val combined = if (speakerMatch != null && cleaned.isNotBlank()) {
                val speaker = speakerMatch.groupValues[1].trim()
                if (cleaned.startsWith("$speaker:")) cleaned else "$speaker: $cleaned"
            } else {
                cleaned
            }
            if (combined.isNotBlank()) {
                cues += TranscriptCue(
                    index = cues.size + 1,
                    startMs = startMs,
                    endMs = endMs,
                    timestampLabel = formatTimestamp(startMs),
                    text = combined,
                )
            }
        }
        return cues
    }

    fun parseInlineTimestamped(raw: String): List<TranscriptCue> {
        val normalized = normalizeNewlines(raw).trimStart('\uFEFF')
        val matches = inlineTimestampRegex.findAll(normalized).toList()
        if (matches.isEmpty()) return emptyList()
        val cues = mutableListOf<TranscriptCue>()
        for (i in matches.indices) {
            val match = matches[i]
            val startMs = parseTimestampToMs(match.groupValues[1]) ?: continue
            val bodyStart = match.range.last + 1
            val bodyEnd = matches.getOrNull(i + 1)?.range?.first ?: normalized.length
            val chunk = normalized.substring(bodyStart, bodyEnd)
                .removePrefix("**")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .joinToString(" ")
                .trim()
            if (chunk.isNotBlank()) {
                cues += TranscriptCue(
                    index = cues.size + 1,
                    startMs = startMs,
                    endMs = null,
                    timestampLabel = formatTimestamp(startMs),
                    text = chunk,
                )
            }
        }
        return cues
    }

    fun pageTimeRangeLabel(pageText: String?): String? {
        if (pageText.isNullOrBlank()) return null
        val matches = inlineTimestampRegex.findAll(pageText).toList()
        if (matches.size < 2) return null
        val first = canonicalLabel(matches.first().groupValues[1]) ?: return null
        val last = canonicalLabel(matches.last().groupValues[1]) ?: return null
        return if (first == last) first else "$first–$last"
    }

    fun findTimestampAnchor(pages: List<BookPage>, timestampOrQuote: String): TranscriptAnchor? {
        val targetMs = parseTimestampToMs(timestampOrQuote.trim('[', ']'))
        val targetLabel = targetMs?.let(::formatTimestamp)
        for (page in pages) {
            val text = page.text
            if (targetLabel != null) {
                for (m in inlineTimestampRegex.findAll(text)) {
                    val mMs = parseTimestampToMs(m.groupValues[1])
                    if (mMs == targetMs) {
                        val lineEnd = text.indexOf('\n', m.range.first).let { if (it < 0) text.length else it }
                        return TranscriptAnchor(
                            pageNumber = page.number,
                            startUtf16 = m.range.first,
                            endUtf16 = lineEnd,
                            timestampLabel = targetLabel,
                            excerpt = text.substring(m.range.first, lineEnd),
                        )
                    }
                }
            }
            val needle = timestampOrQuote.trim()
            if (needle.isNotEmpty()) {
                val idx = text.indexOf(needle)
                if (idx >= 0) {
                    val lineLabel = inlineTimestampRegex.findAll(text.substring(0, idx + needle.length))
                        .lastOrNull()?.let { parseTimestampToMs(it.groupValues[1]) }?.let(::formatTimestamp)
                        ?: targetLabel.orEmpty()
                    return TranscriptAnchor(
                        pageNumber = page.number,
                        startUtf16 = idx,
                        endUtf16 = idx + needle.length,
                        timestampLabel = lineLabel,
                        excerpt = text.substring(idx, idx + needle.length),
                    )
                }
            }
        }
        return null
    }

    fun parseTimestampToMs(raw: String): Long? {
        val cleaned = raw.trim().replace(',', '.')
        val parts = cleaned.split(':')
        if (parts.size !in 2..3) return null
        val secPart = parts.last().toDoubleOrNull() ?: return null
        val mins = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) (parts[0].toLongOrNull() ?: return null) else 0L
        if (hours < 0 || mins < 0 || mins >= 60 || secPart < 0.0 || secPart >= 60.0) return null
        return ((hours * 3600L + mins * 60L) * 1000L) + kotlin.math.round(secPart * 1000.0).toLong()
    }

    fun formatTimestamp(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hours > 0L) {
            "%02d:%02d:%02d".format(hours, mins, secs)
        } else {
            "%02d:%02d".format(mins, secs)
        }
    }

    private fun canonicalLabel(raw: String): String? = parseTimestampToMs(raw)?.let(::formatTimestamp)

    private fun cleanCueText(raw: String): String =
        htmlTagRegex.replace(raw, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalizeNewlines(raw: String): String =
        raw.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")
}
