package com.coach.server.reader.v2

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Read-only consumer of the reader-v2 layout cache written by tools/reader-v2/layout
 * (see its README for the contract). Never triggers analysis; a missing page is null.
 * Page JSON contains book text: callers must not log or expose it outside the device owner.
 */
data class LayoutBlock(
    val id: String, val kind: String, val bbox: List<Double>, val readingOrder: Int,
    val column: Int, val status: String, val text: String?, val inReadingFlow: Boolean,
    val fallback: String?,
)

data class LayoutPage(
    val pageNumber: Int, val status: String, val columns: Int?, val readingOrderStatus: String,
    val fallback: String?, val rotation: Int, val size: List<Double>?, val blocks: List<LayoutBlock>,
) {
    /** True only when text order may be used for sentence anchoring; otherwise use the original page. */
    val orderTrusted: Boolean
        get() = status == "ok" && fallback == null && readingOrderStatus in setOf("ok", "single_column") &&
            blocksRunTopDown()

    /** Independent sanity check (does not rely on the producer's verdict): within each column,
     *  text-flow blocks must follow reading order from top to bottom (2pt tolerance). */
    private fun blocksRunTopDown(): Boolean {
        val flow = setOf("text", "heading", "list", "caption", "footnote")
        return blocks.filter { it.kind in flow && it.inReadingFlow && it.bbox.size >= 4 }
            .groupBy { it.column }
            .all { (_, bs) -> bs.sortedBy { it.readingOrder }.zipWithNext().all { (a, b) -> b.bbox[1] >= a.bbox[1] - 2.0 } }
    }
}

class LayoutCache(private val root: Path) {
    private val supportedSchema = 1

    private fun obj(p: Path): JsonObject? =
        if (Files.isRegularFile(p)) Json.parseToJsonElement(Files.readString(p)).jsonObject else null

    /** Current complete version directory name for [docId], or null. */
    fun currentVersion(docId: String): String? {
        require(!docId.contains("/") && !docId.contains("\\") && !docId.contains("..")) { "bad docId" }
        val v = obj(root.resolve(docId).resolve("latest.json"))?.get("version")?.jsonPrimitive?.content ?: return null
        return if (Files.isRegularFile(root.resolve(docId).resolve(v).resolve("manifest.json"))) v else null
    }

    /** 1-based physical page number. Returns null if not analysed or schema is unsupported. */
    fun page(docId: String, pageNumber: Int): LayoutPage? {
        val v = currentVersion(docId) ?: return null
        val o = obj(root.resolve(docId).resolve(v).resolve("pages").resolve("%05d.json".format(pageNumber))) ?: return null
        if (o["schema_version"]?.jsonPrimitive?.int != supportedSchema) return null
        fun s(e: JsonObject, k: String) = e[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        fun nums(e: JsonElement?) = (e as? JsonArray)?.map { it.jsonPrimitive.double }
        val blocks = (o["blocks"] as? JsonArray).orEmpty().map { it.jsonObject }.map { b ->
            LayoutBlock(
                s(b, "id")!!, s(b, "kind")!!, nums(b["bbox"]).orEmpty(), b["reading_order"]!!.jsonPrimitive.int,
                b["column"]?.jsonPrimitive?.int ?: 0, s(b, "status") ?: "unknown", s(b, "text"),
                b["in_reading_flow"]?.jsonPrimitive?.boolean ?: true, s(b, "fallback"),
            )
        }
        return LayoutPage(
            pageNumber, s(o, "status") ?: "missing", o["columns"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int,
            s(o, "reading_order_status") ?: "unknown", s(o, "fallback"),
            o["rotation"]?.jsonPrimitive?.int ?: 0, nums(o["size"]), blocks,
        )
    }
}
