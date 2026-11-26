package com.coach.domain

/**
 * Strips book text, note text, file names, service addresses, tokens and pairing
 * codes before an incident record is written or stored. The same function runs
 * on the desktop and again on the server.
 */
object IncidentRedactor {
    const val BOOK = "合成书页句子不会留下甲乙丙丁戊己庚辛壬癸"
    const val NOTE = "合成笔记原文不会留下子丑寅卯辰巳午未申酉"
    const val FILE_NAME = "synthetic-chapter.pdf"
    const val PATH = "C:\\Users\\learner\\Books\\synthetic-chapter.pdf"
    const val URL = "https://203.0.113.50:8443/v1/pair"
    const val HOST = "service.example.invalid:443"
    const val TOKEN = "Bearer synthetic.token.value.not.real.0123456789abcdefABCDEF"
    const val PAIR = "482913"

    fun syntheticBlob(): String =
        "book=$BOOK note=$NOTE file=$PATH name=$FILE_NAME url=$URL host=$HOST auth=$TOKEN code=$PAIR"

    fun needles(): List<String> = listOf(BOOK, NOTE, FILE_NAME, "learner", "203.0.113.50", "example.invalid", "synthetic.token", "0123456789abcdef", PAIR)

    private val frame = Regex("""(?m)^[ \t]*at com\.coach[\w.$]*\([\w.]+:\d+\)""")
    private val placeholder = Regex("""\u0000(\d+)\u0000""")
    private val patterns = listOf(
        Regex("""(?i)\b(?:https?|wss?|ftp|file)://\S+"""),
        Regex("""\b(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)(?::\d{1,5})?\b"""),
        Regex("""(?i)\b(?:[a-z0-9-]+\.)+(?:invalid|localhost|example\.com|example\.org|example\.net)(?::\d{1,5})?\b"""),
        Regex("""(?i)\b(?:[a-z0-9-]+\.)+[a-z]{2,}:\d{1,5}\b"""),
        Regex("""[A-Za-z]:\\(?:[^\\/:*?"<>|\r\n]+\\)*[^\\/:*?"<>|\s]+"""),
        Regex("""(?<![A-Za-z0-9])/(?:[\w.-]+/){1,}[\w.-]+"""),
        Regex("""(?i)(?<![A-Za-z0-9])[\w.-]+\.(?:pdf|epub|mobi|azw3|docx|doc|pptx|ppt|txt|png|jpe?g|gif|webp|sqlite|db|json|md|markdown|html?|rtf|odt|cbz|djvu|srt|vtt)\b"""),
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}"""),
        Regex("""(?i)\b(?:token|authorization|api[_-]?key|secret|password)\s*[:=]\s*\S+"""),
        Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}"""),
        Regex("""\b[A-Fa-f0-9]{32,}\b"""),
        Regex("""(?<!\d)\d{6}(?!\d)"""),
        Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""),
        Regex("""「[^」]{8,}」|"[^"\n]{8,}"|'[^'\n]{8,}'"""),
        Regex("""\p{IsHan}{12,}"""),
        Regex("""\b[A-Za-z]{2,}(?:\s+[A-Za-z]{2,}){7,}\b"""),
    )

    fun redact(raw: String): String {
        val capped = if (raw.length > 16_000) raw.take(16_000) else raw
        val kept = ArrayList<String>()
        var text = frame.replace(capped.replace("\u0000", "")) { match ->
            kept += match.value
            "\u0000${kept.lastIndex}\u0000"
        }
        for (pattern in patterns) text = pattern.replace(text, "[redacted]")
        return placeholder.replace(text) { match -> kept[match.groupValues[1].toInt()] }
    }
}
