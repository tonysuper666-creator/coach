package com.coach.desktop.reader

import com.coach.client.ReaderReply
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** The two digests have different domains. Never treat an import digest as a PDF digest. */
@ConsistentCopyVisibility
data class ExplanationCitation private constructor(
    val document: String, val sourceVersion: String, val pdfPage: Int,
    val text: String, val blockId: String?, val pdfVersion: String?,
    val startUtf16: Int?, val endUtf16: Int?,
) {
    val isBlock get() = blockId != null
    companion object {
        fun parse(o: JsonObject): ExplanationCitation {
            fun string(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            fun number(key: String) = (o[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            fun digest(key: String) = string(key)?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
            val doc = string("docId")?.takeIf { it.isNotBlank() && it.length <= 128 } ?: error("出处缺少资料身份")
            val version = digest("version") ?: error("出处缺少文本版本")
            val page = number("pdfPage")?.takeIf { it in 1..100_000 } ?: error("出处页码无效")
            val text = string("text")?.takeIf { it.isNotBlank() } ?: error("出处没有可读原文")
            return when (string("mode")) {
                "block" -> {
                    val block = string("blockId")?.takeIf(String::isNotBlank) ?: error("出处缺少段落身份")
                    val pdf = digest("pdfVersion") ?: error("出处缺少 PDF 版本")
                    val start = number("startUtf16") ?: error("出处缺少文本范围")
                    val end = number("endUtf16") ?: error("出处缺少文本范围")
                    require(start >= 0 && end > start && end.toLong() - start == text.length.toLong()) { "出处文本范围不一致" }
                    ExplanationCitation(doc, version, page, text, block, pdf, start, end)
                }
                "page" -> {
                    require(listOf("blockId", "startUtf16", "endUtf16", "pdfVersion").all { o[it] == null || o[it] == JsonNull }) { "整页出处身份不一致" }
                    ExplanationCitation(doc, version, page, text, null, null, null, null)
                }
                else -> error("出处类型无法确认")
            }
        }
    }
}

/** Read-only, single authorized physical page. Adjacent pages and all writes stay unavailable.
 * Block cards require the exact catalog identity AND PDF digest, not a title/hash-only search.
 * Page cards display the authorized source snapshot; they never borrow an unrelated PDF image.
 */
class CitationReaderSource private constructor(
    private val citation: ExplanationCitation, private val delegate: ReaderSource?, private val title: String,
) : ReaderSource {
    private val version = citation.pdfVersion ?: citation.sourceVersion
    private fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf(*fields))
    private fun identity() = obj("documentId" to JsonPrimitive(citation.document), "version" to JsonPrimitive(version), "pdfPage" to JsonPrimitive(citation.pdfPage))
    override suspend fun catalog() = ReaderReply(200, obj("documents" to JsonArray(listOf(obj(
        "id" to JsonPrimitive(citation.document), "title" to JsonPrimitive(title),
        "version" to JsonPrimitive(version), "pages" to JsonPrimitive(citation.pdfPage))))))
    override suspend fun state(document: String) = if (document != citation.document) denied() else ReaderReply(200, obj(
        "document" to obj("id" to JsonPrimitive(document), "version" to JsonPrimitive(version)),
        "notes" to JsonArray(emptyList())))
    override suspend fun page(document: String, pdfPage: Int): ReaderReply {
        if (!permits(document, pdfPage)) return denied()
        val base = delegate
        if (base != null) {
            val r = base.page(document, pdfPage)
            if (!r.ok) return r
            val o = r.body ?: return ReaderReply(409, null)
            if (o["documentId"] != JsonPrimitive(document) || o["version"] != JsonPrimitive(version) || o["pdfPage"] != JsonPrimitive(pdfPage)) return ReaderReply(409, null)
            val text = (o["text"] as? JsonPrimitive)?.contentOrNull ?: return ReaderReply(409, null)
            // Import UTF-16 offsets do not address PDF extraction text. Require a unique exact
            // selector after whitespace normalization, keeping punctuation and case unchanged.
            val haystack = normalized(text); val needle = normalized(citation.text)
            val at = haystack.indexOf(needle)
            if (at < 0 || haystack.indexOf(needle, at + 1) >= 0) return ReaderReply(409, null)
            return r
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(citation.text.toByteArray()).joinToString("") { "%02x".format(it) }
        return ReaderReply(200, JsonObject(identity() + obj("text" to JsonPrimitive(citation.text), "textSha256" to JsonPrimitive(sha))))
    }
    override suspend fun image(document: String, pdfPage: Int, version: String?) =
        if (permits(document, pdfPage) && version == this.version) delegate?.image(document, pdfPage, version) else null
    override suspend fun boxes(document: String, pdfPage: Int, version: String) =
        if (permits(document, pdfPage) && version == this.version) delegate?.boxes(document, pdfPage, version) ?: ReaderReply(404, null) else denied()
    override suspend fun study(document: String, pdfPage: Int) = if (!permits(document, pdfPage)) denied() else ReaderReply(200,
        JsonObject(identity() + obj("questions" to JsonArray(emptyList()), "unlocated" to JsonPrimitive(0), "available" to JsonPrimitive(false))))
    override suspend fun saveNote(document: String, body: JsonObject) = denied()
    override suspend fun saveProgress(document: String, version: String, pdfPage: Int, baseRevision: Long, mutationId: String) = denied()
    private fun permits(doc: String, page: Int) = doc == citation.document && page == citation.pdfPage
    private fun denied() = ReaderReply(403, null)
    companion object {
        private fun normalized(text: String) = text.replace(Regex("[\\s\\u00a0]+"), " ").trim()
        suspend fun create(citation: ExplanationCitation, reader: ReaderSource): CitationReaderSource {
            if (!citation.isBlock) return CitationReaderSource(citation, null, "出处原文")
            val response = reader.catalog()
            require(response.ok) { "无法核对资料目录" }
            val matches = (response.body?.get("documents") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { it["id"] == JsonPrimitive(citation.document) }
            val doc = matches.singleOrNull() ?: error("出处资料没有登记或身份不唯一")
            require(doc["version"] == JsonPrimitive(citation.pdfVersion)) { "出处与资料版本不一致" }
            val pages = (doc["pages"] as? JsonPrimitive)?.intOrNull ?: error("资料缺少页数")
            require(citation.pdfPage in 1..pages) { "出处页码超出资料" }
            val source = CitationReaderSource(citation, reader, (doc["title"] as? JsonPrimitive)?.contentOrNull ?: "出处原文")
            require(source.page(citation.document, citation.pdfPage).ok) { "对应原文无法核对，不能猜测位置" }
            return source
        }
    }
}
