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
                b["in_reading_flow"]?.jsonPrimitive?.boolean ?: false, s(b, "fallback"), s(b, "raw_label"))
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
        ReaderCitationPage(page, hash)
    }.getOrNull()

    companion object {
        fun environment() = ReaderCitationLayout(Path.of(System.getenv("READER_V2_LAYOUT_CACHE")
            ?: Path.of(System.getProperty("user.home"), "Coach", "state", "reader-v2-layout").toString()))
    }
}
