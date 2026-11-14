package com.coach.server

import com.coach.server.reader.v2.*
import com.coach.store.SourcePage
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

data class ReaderCitationPage(val page: LayoutPage, val pdfSha256: String)

/** Pins one complete cache version by FULL file digests, never a filename.
 * Missing, competing, malformed or stale caches cannot establish evidence. No cache text is logged. */
internal class ReaderCitationLayout(private val root: Path) {
    fun page(source: SourcePage): ReaderCitationPage? = runCatching {
        val sourceHash = source.sourceSha256?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return null
        if (!Files.isDirectory(root)) return null
        fun obj(path: Path): JsonObject {
            require(Files.size(path) <= 5_000_000) // Bounded local cache reads remain inside the reader deadline.
            return Json.parseToJsonElement(Files.readString(path)).jsonObject
        }
        // Markdown imports and PDF layout caches have different digest domains. Only an
        // owner-controlled, exact-version provenance binding may bridge them; never infer a title.
        val bindings = root.resolve("source-bindings.json")
        val hash = if (Files.exists(bindings)) {
            val registry = obj(bindings)
            require(registry["schema_version"]?.jsonPrimitive?.int == 1)
            val matches = registry.getValue("bindings").jsonArray.map { it.jsonObject }.filter {
                it["source_doc_id"]?.jsonPrimitive?.content == source.docId &&
                    it["source_sha256"]?.jsonPrimitive?.content == sourceHash
            }
            if (matches.isEmpty()) sourceHash else matches.singleOrNull()?.get("pdf_sha256")?.jsonPrimitive?.content
                ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return null
        } else sourceHash
        val versions = Files.list(root).use { dirs ->
            dirs.limit(1025).toList().also { if (it.size > 1024) return null }.mapNotNull { dir ->
                runCatching {
                    val v = obj(dir.resolve("latest.json")).getValue("version").jsonPrimitive.content
                    if (!v.matches(Regex("[A-Za-z0-9_.-]+")) || v in setOf(".", "..")) return@runCatching null
                    val pinned = dir.resolve(v)
                    val manifest = obj(pinned.resolve("manifest.json"))
                    pinned.takeIf { manifest["schema_version"]?.jsonPrimitive?.int == 1 &&
                        manifest["doc_sha256"]?.jsonPrimitive?.content == hash &&
                        (manifest["pages_total"]?.jsonPrimitive?.int ?: 0) >= source.pdfPage }
                }.getOrNull()
            }
        }
        val pinned = versions.singleOrNull() ?: return null
        val o = obj(pinned.resolve("pages").resolve("%05d.json".format(source.pdfPage)))
        if (o["schema_version"]?.jsonPrimitive?.int != 1 || o["doc_sha256"]?.jsonPrimitive?.content != hash ||
            o["page_number"]?.jsonPrimitive?.int != source.pdfPage || o["page_base"]?.jsonPrimitive?.int != 1 ||
            o["page_index"]?.jsonPrimitive?.int != source.pdfPage - 1) return null
        fun s(e: JsonObject, k: String) = (e[k] as? JsonPrimitive)?.contentOrNull
        fun nums(e: JsonElement?) = (e as? JsonArray)?.map { it.jsonPrimitive.double }
        val blocks = (o["blocks"] as? JsonArray).orEmpty().map { value ->
            val b = value.jsonObject
            LayoutBlock(s(b, "id")!!, s(b, "kind")!!, nums(b["bbox"]).orEmpty(), b.getValue("reading_order").jsonPrimitive.int,
                b["column"]?.jsonPrimitive?.int ?: 0, s(b, "status") ?: "unknown", s(b, "text"),
                // schema1 omits this flag for flowing text; tables/figures explicitly opt out.
                // Match LayoutCache's producer contract, including its independent order check.
                b["in_reading_flow"]?.jsonPrimitive?.boolean ?: true, s(b, "fallback"), s(b, "raw_label"))
        }
        if (blocks.map { it.id }.distinct().size != blocks.size ||
            blocks.map { it.readingOrder }.distinct().size != blocks.size ||
            blocks.any { it.id.isBlank() || it.readingOrder < 0 || it.bbox.size != 4 ||
                it.bbox.any { n -> !n.isFinite() } || it.bbox[2] <= it.bbox[0] || it.bbox[3] <= it.bbox[1] }) return null
        val size = nums(o["size"])?.takeIf { it.size == 2 && it.all { n -> n.isFinite() && n > 0 } } ?: return null
        if (o["rotation"]?.jsonPrimitive?.int?.let { it != 0 } == true) return null
        val page = LayoutPage(source.pdfPage, s(o, "status") ?: "missing", (o["columns"] as? JsonPrimitive)?.intOrNull,
            s(o, "reading_order_status") ?: "unknown", s(o, "fallback"), o["rotation"]?.jsonPrimitive?.int ?: 0,
            size, blocks).takeIf { it.orderTrusted } ?: return null
        // A digest bridge proves the document, not that import page N is PDF page N. A short or repeated
        // page (one title echoed on ten pages) must not inherit a page number its own text cannot confirm.
        if (hash != sourceHash && !pageConfirmed(pinned, hash, source)) return null
        ReaderCitationPage(page, hash)
    }.getOrNull()

    private class Fingerprints(val key: String, val pages: List<LongArray>)
    private val fingerprints = ArrayDeque<Fingerprints>()

    /** The import page must share text fragments with this PDF page that occur on no other page of the
     * pinned version, and they must outweigh fragments exclusive to all other pages together. A missing or
     * foreign page file leaves uniqueness unprovable, so the caller keeps the authorized whole page. */
    private fun pageConfirmed(pinned: Path, hash: String, source: SourcePage): Boolean {
        val pages = versionFingerprints(pinned, hash)
        val exclusive = IntArray(pages.size)
        for (g in grams(source.text)) {
            var holder = -1
            for (i in pages.indices) if (pages[i].binarySearch(g) >= 0) {
                if (holder >= 0) { holder = -1; break }
                holder = i
            }
            if (holder >= 0) exclusive[holder]++
        }
        val own = exclusive.getOrElse(source.pdfPage - 1) { 0 }
        return own > 0 && own > exclusive.sum() - own
    }

    private fun versionFingerprints(pinned: Path, hash: String): List<LongArray> {
        val manifest = Files.readString(pinned.resolve("manifest.json"))
        // Any rewritten, added or removed page file invalidates the cached fingerprints.
        val key = pinned.toRealPath().toString() + "\u0000" + manifest + "\u0000" +
            Files.list(pinned.resolve("pages")).use { files -> files.limit(5001).toList() }.sorted().joinToString("\u0000") {
                "${it.fileName}:${Files.size(it)}:${Files.getLastModifiedTime(it)}"
            }
        synchronized(fingerprints) { fingerprints.firstOrNull { it.key == key }?.let { return it.pages } }
        val total = Json.parseToJsonElement(manifest).jsonObject.getValue("pages_total").jsonPrimitive.int
        require(total in 1..5000)
        val pages = (1..total).map { n ->
            val path = pinned.resolve("pages").resolve("%05d.json".format(n))
            require(Files.size(path) <= 5_000_000)
            val o = Json.parseToJsonElement(Files.readString(path)).jsonObject
            require(o["doc_sha256"]?.jsonPrimitive?.content == hash && o["page_number"]?.jsonPrimitive?.int == n)
            grams((o["blocks"] as? JsonArray).orEmpty().joinToString(" ") {
                ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
            }).toLongArray().apply { sort() }
        }
        synchronized(fingerprints) {
            fingerprints.removeAll { it.key == key }
            fingerprints.addFirst(Fingerprints(key, pages))
            while (fingerprints.size > 4) fingerprints.removeLast()
        }
        return pages
    }

    companion object {
        private const val GRAM = 4
        private const val CJK = "\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff\\uac00-\\ud7af"
        private val TOKEN = Regex("[$CJK]|[[\\p{L}\\p{N}]&&[^$CJK]]+")

        /** Order-sensitive 4-grams of words (each CJK character is a word), NFKC + lowercase. */
        internal fun grams(text: String): Set<Long> {
            val words = TOKEN.findAll(java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).lowercase())
                .map { it.value }.toList()
            return (0..words.size - GRAM).mapTo(HashSet()) { i ->
                var h = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
                for (w in words.subList(i, i + GRAM)) {
                    for (c in w) h = (h xor c.code.toLong()) * 0x100000001b3L
                    h = (h xor 0x1fL) * 0x100000001b3L
                }
                h
            }
        }

        fun environment() = ReaderCitationLayout(Path.of(System.getenv("READER_V2_LAYOUT_CACHE")
            ?: Path.of(System.getProperty("user.home"), "Coach", "state", "reader-v2-layout").toString()))
    }
}
