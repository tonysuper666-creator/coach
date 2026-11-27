package com.coach.desktop.books

/**
 * EPUB 页文本进入文献导读前的清洗：去掉尾注段、花括号夹注和行内注号，
 * 让导读正文与候选句只剩作者自己的话。
 */
internal object LiteratureEpubText {
    private val braceNote = Regex("""\{[^{}]*\}""")
    private val bracketNumber = Regex("""\s?\[\d{1,4}]""")
    private val caretNumber = Regex("""\^\d{1,4}""")
    private val notesHeading = Regex("""(?i)^#{0,6}\s*(?:end\s?notes?|notes?|footnotes?|注释|尾注|脚注)\s*$""")
    private val noteLead = Regex("""^(?:\[\d{1,4}]|\d{1,4}[.)]?|[*†‡§]+|\^\d{1,4})\s+\S""")
    private val noteLeadShort = Regex("""^(?:\[\d{1,4}]|\d{1,4}[.)]|[*†‡§]+|\^\d{1,4})\s""")
    private val backLink = Regex("""[↩↑^]\s*$|\bibid\.|\bop\.\s?cit\.""", RegexOption.IGNORE_CASE)

    fun isNoteParagraph(p: String): Boolean {
        val t = p.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("{") && t.endsWith("}")) return true
        if (!noteLead.containsMatchIn(t)) return false
        // 带编号、较短或带回链标记的段落视为尾注；长段落且无标记仍视为正文。
        return t.length <= 360 && (noteLeadShort.containsMatchIn(t) || backLink.containsMatchIn(t))
    }

    fun stripInline(text: String): String =
        text.replace(braceNote, "")
            .replace(bracketNumber, "")
            .replace(caretNumber, "")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex(""" +([,.;:!?])"""), "$1")

    fun clean(pageText: String): List<GuideRegion> {
        val paragraphs = pageText.replace("\r\n", "\n").replace('\r', '\n').split(Regex("""\n{2,}"""))
        var inNotes = false
        val kept = mutableListOf<String>()
        for (raw in paragraphs) {
            val p = raw.trim()
            if (p.isEmpty()) continue
            if (notesHeading.matches(p)) { inNotes = true; continue }
            if (inNotes) continue
            if (isNoteParagraph(p)) continue
            val stripped = stripInline(p).trim()
            if (stripped.isNotEmpty()) kept += stripped
        }
        return kept.map { GuideRegion(it) }
    }
}
