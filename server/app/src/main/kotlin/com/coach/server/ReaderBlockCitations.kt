package com.coach.server

import com.coach.domain.*
import com.coach.domain.reader.*
import com.coach.store.SourcePage
import com.coach.store.sha256
import kotlinx.serialization.json.*

/** Request-owned bindings. Layout text is a selector, never a replacement for authorized source text. */
internal class ReaderBlockCitations(pack: SourcePack, layout: (SourcePage) -> ReaderCitationPage?) {
    private data class Binding(val pageId: String, val page: SourcePage, val blockId: String? = null,
        val range: ReaderTextRange? = null, val pdfSha256: String? = null) {
        val text get() = range?.let { page.text.substring(it.startUtf16, it.endUtf16) } ?: page.text
        fun card(id: String) = json("anchor" to id, "docId" to page.docId, "pdfPage" to page.pdfPage,
            "mode" to if (range == null) "page" else "block", "text" to text,
            "notice" to if (range == null) "这一段的位置无法确认，显示已核对的整页。" else "对应原文段落",
            "version" to page.sourceSha256, "pdfVersion" to pdfSha256, "blockId" to blockId,
            "startUtf16" to range?.startUtf16, "endUtf16" to range?.endUtf16)
    }
    private val pages = pack.browsable.mapIndexed { i, p -> "S${i + 1}" to Binding("S${i + 1}", p) }.toMap()
    private val blocks = buildMap<String, Binding> {
        for ((pageId, binding) in pages) {
            val p = binding.page
            val hash = p.sourceSha256?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: continue
            val bound = layout(p)?.takeIf { it.pdfSha256.matches(Regex("[a-f0-9]{64}")) } ?: continue
            val page = bound.page.takeIf { it.pageNumber == p.pdfPage && it.orderTrusted } ?: continue
            val resource = ReaderResource(pdfPageIndex0 = p.pdfPage - 1)
            val indexVersion = sha256((hash + "\u0000" + bound.pdfSha256).toByteArray())
            val index = ReaderDocumentIndex(DocumentVersion(p.docId, indexVersion, com.coach.domain.reader.ReaderFormat.PDF),
                listOf(ReaderTextUnit(resource, p.text)), complete = false, pdfPageCount = p.pdfPage)
            val candidates = page.blocks.sortedBy { it.readingOrder }.mapNotNull { b ->
                if (b.status != "ok" || b.fallback != null || !b.inReadingFlow ||
                    b.kind !in setOf("text", "heading", "list", "caption", "footnote", "code")) null
                else b.text?.let { uniqueReaderRange(p.text, it) }?.let { b to it }
            }
            for ((b, range) in candidates) {
                if (candidates.any { (other, r) -> other !== b &&
                        r.startUtf16 < range.endUtf16 && range.startUtf16 < r.endUtf16 }) continue
                // Capture and resolve against the exact same-version, authorized index range.
                val resolved = runCatching {
                    ReaderAnchorResolver.resolve(ReaderAnchorResolver.capture(index, resource, range), index)
                }.getOrNull() ?: continue
                if (resolved.reason != AnchorReason.SAME_VERSION_RANGE || resolved.target?.range != range) continue
                val token = sha256(listOf(p.docId, hash, bound.pdfSha256, p.pdfPage.toString(), b.id,
                    range.startUtf16.toString(), range.endUtf16.toString(), p.text).joinToString("\u0000").toByteArray()).take(32)
                put("${pageId}B$token", binding.copy(blockId = b.id, range = range, pdfSha256 = bound.pdfSha256))
            }
        }
    }

    fun render(): String = buildString {
        for ((id, page) in pages) {
            val candidates = blocks.filterValues { it.pageId == id }
            if (candidates.isEmpty()) appendLine("【$id】（仅整页依据）\n${page.text}\n")
            else candidates.forEach { (blockId, block) -> appendLine("【$blockId】\n${block.text}\n") }
        }
    }

    /** Invalid block IDs may fall back ONLY within an already authorized page namespace.
     * Unknown pages, absent declarations and malformed output still fail the existing citation gate. */
    fun check(payload: String): Pair<String, JsonArray>? {
        if (payload.length > 12_000) return null
        val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
        if (root.keys != setOf("paragraphs")) return null
        val paragraphs = root["paragraphs"] as? JsonArray ?: return null
        if (paragraphs.size !in 1..2) return null
        val used = linkedMapOf<String, Binding>()
        val translated = paragraphs.map { value ->
            val paragraph = value as? JsonObject ?: return null
            if (paragraph.keys != setOf("text", "anchors")) return null
            val refs = paragraph["anchors"] as? JsonArray ?: return null
            if (refs.size !in 1..128) return null
            val ids = refs.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
            if (ids.distinct().size != ids.size) return null
            val mapped = ids.map { id ->
                val binding = blocks[id] ?: pages[id] ?: Regex("(S[1-6])B[A-Za-z0-9_-]{1,128}")
                    .matchEntire(id)?.groupValues?.get(1)?.let(pages::get) ?: return null
                used[id] = binding
                JsonPrimitive("S${used.keys.indexOf(id) + 1}")
            }
            JsonObject(paragraph + ("anchors" to JsonArray(mapped)))
        }
        val gatePack = SourcePack(used.values.map { it.page.copy(text = it.text) })
        val checked = checkedReaderPayload(json("paragraphs" to JsonArray(translated)).toString(), gatePack) ?: return null
        // Opaque model tokens stay out of reading prose; display IDs are assigned by the server too.
        fun display(id: String, b: Binding) = if (b.range == null) b.pageId else
            b.pageId + "B" + (blocks.filterValues { it.pageId == b.pageId }.keys.indexOf(id) + 1)
        val entries = used.entries.toList()
        val text = Regex("【S([0-9]+)】").replace(checked.first) {
            val (id, b) = entries[it.groupValues[1].toInt() - 1]
            "【${display(id, b)}】"
        }
        return text to JsonArray(used.map { (id, b) -> b.card(display(id, b)) }.distinct())
    }
}

/** Exact, case/punctuation preserving match with whitespace collapsed and offsets mapped to UTF-16.
 * Repeated or overlapping occurrences are ambiguous, even if layout order suggests one. */
internal fun uniqueReaderRange(source: String, selector: String): ReaderTextRange? {
    data class Normalized(val text: String, val starts: List<Int>, val ends: List<Int>)
    fun normalize(raw: String): Normalized {
        val text = StringBuilder(); val starts = mutableListOf<Int>(); val ends = mutableListOf<Int>()
        raw.forEachIndexed { i, ch ->
            val space = ch in '\t'..'\r' || ch == ' ' || ch == '\u00a0'
            if (space && text.lastOrNull() == ' ') ends[ends.lastIndex] = i + 1
            else { text.append(if (space) ' ' else ch); starts += i; ends += i + 1 }
        }
        return Normalized(text.toString(), starts, ends)
    }
    val haystack = normalize(source); val needle = normalize(selector).text.trim(' ')
    if (needle.isBlank()) return null
    val at = haystack.text.indexOf(needle)
    if (at < 0 || haystack.text.indexOf(needle, at + 1) >= 0) return null
    return ReaderTextRange(haystack.starts[at], haystack.ends[at + needle.length - 1])
}
