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

    private fun norm(t: String): String =
        t.replace("↩︎", " ").replace("↩", " ").replace(Regex("""\s+"""), " ").trim()

    /** 从原 EPUB 里按 epub:type / role 收集尾注、脚注正文（注号在 li 上，段落本身不带编号）。 */
    fun noteTexts(epub: java.nio.file.Path): List<String> = runCatching {
        val out = mutableListOf<String>()
        java.util.zip.ZipFile(epub.toFile()).use { zip ->
            for (entry in zip.entries().asSequence()) {
                val n = entry.name.lowercase()
                if (!(n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")) || entry.size > 16L * 1024 * 1024) continue
                val doc = runCatching { EpubXml.parse(zip.getInputStream(entry).use { it.readBytes() }) }.getOrNull() ?: continue
                fun isNote(e: org.w3c.dom.Element): Boolean {
                    val types = (e.getAttribute("epub:type") + " " + e.getAttribute("role")).lowercase().split(Regex("""\s+"""))
                    return types.any { it in noteTypes } ||
                        e.getAttribute("class").lowercase().split(Regex("""\s+""")).any { it in noteTypes }
                }
                fun walk(node: org.w3c.dom.Node) {
                    if (node is org.w3c.dom.Element && isNote(node)) {
                        // 整块是尾注容器时，逐个 li/p 收，避免把标题也当成注。
                        val parts = mutableListOf<org.w3c.dom.Element>()
                        fun leaves(x: org.w3c.dom.Node) {
                            for (i in 0 until x.childNodes.length) {
                                val c = x.childNodes.item(i)
                                if (c is org.w3c.dom.Element) {
                                    val nm = c.localName?.lowercase().orEmpty()
                                    if (nm == "p" || nm == "li" && c.getElementsByTagName("p").length == 0) parts += c else leaves(c)
                                }
                            }
                        }
                        leaves(node)
                        if (parts.isEmpty()) out += norm(node.textContent) else parts.forEach { out += norm(it.textContent) }
                        return
                    }
                    for (i in 0 until node.childNodes.length) walk(node.childNodes.item(i))
                }
                walk(doc)
            }
        }
        out.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    private val noteTypes = setOf("endnote", "endnotes", "footnote", "footnotes", "rearnote", "rearnotes", "doc-endnote", "doc-endnotes", "doc-footnote")

    fun clean(pageText: String, notes: List<String> = emptyList()): List<GuideRegion> {
        val blob = if (notes.isEmpty()) "" else notes.joinToString("\n")
        fun isKnownNote(p: String): Boolean {
            if (blob.isEmpty()) return false
            val q = norm(p)
            return q.length >= 12 && blob.contains(q)
        }
        val paragraphs = pageText.replace("\r\n", "\n").replace('\r', '\n').split(Regex("""\n{2,}"""))
        var inNotes = false
        val kept = mutableListOf<String>()
        for (raw in paragraphs) {
            val p = raw.trim()
            if (p.isEmpty()) continue
            if (notesHeading.matches(p)) { inNotes = true; continue }
            if (inNotes) continue
            if (isNoteParagraph(p) || isKnownNote(p)) continue
            val stripped = stripInline(p).trim()
            if (stripped.isNotEmpty()) kept += stripped
        }
        return kept.map { GuideRegion(it) }
    }
}
