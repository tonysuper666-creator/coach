package com.coach.ui.reader.v2

import kotlinx.serialization.json.*

data class ReaderColumn(val id: Int, val region: ReaderRegion)
data class ReaderPageLayout(val pageIndex0: Int, val width: Float, val height: Float,
    val trim: ReaderRegion = ReaderRegion.Full, val columns: List<ReaderColumn> = emptyList(),
    /** Smallest body font in the full-page renderer's units, measured by its owner.
     * Null means height-fit readability cannot be proved; never infer this from OCR. */
    val bodyFontSize: Float? = null) {
    init {
        require(pageIndex0 >= 0 && width.isFinite() && height.isFinite() && width > 0 && height > 0)
        require(bodyFontSize == null || bodyFontSize.isFinite() && bodyFontSize > 0)
    }
    fun column(id: Int?) = columns.firstOrNull { it.id == id }
    fun nextColumn(id: Int?): Int? {
        if (columns.isEmpty()) return null
        val index = columns.indexOfFirst { it.id == id }
        return columns[(index + 1).coerceAtMost(columns.lastIndex)].id
    }
}

/** Read-only adapter for TON-1424's schema v1, not a server route or anchor resolver.
 * The supplied page renderer must use the full unrotated MediaBox. Unsupported
 * versions/hash mismatches are rejected; uncertain/rotated geometry stays full-page.
 * Original point boxes are preserved by union so figures/formulas aren't sliced. */
object ReaderLayoutV1 {
    fun decode(json: String, expectedSha256: String): ReaderPageLayout = run {
        val j = Json.parseToJsonElement(json).jsonObject
        require(j.getValue("schema_version").jsonPrimitive.int == 1) { "Unsupported page layout schema" }
        require(j.getValue("doc_sha256").jsonPrimitive.content == expectedSha256) { "Different document version" }
        val index = j.getValue("page_index").jsonPrimitive.int
        require(j.getValue("page_base").jsonPrimitive.int == 1 && j.getValue("page_number").jsonPrimitive.int == index + 1)
        val media = j.getValue("mediabox").jsonArray.map { it.jsonPrimitive.float }
        require(media.size == 4)
        val width = media[2] - media[0]
        val height = media[3] - media[1]
        val fallback = ReaderPageLayout(index, width, height)
        if (j["status"]?.jsonPrimitive?.content != "ok" || j["fallback"] != null ||
            j["rotation"]?.jsonPrimitive?.int != 0 || j["geometry"]?.jsonPrimitive?.content != "page_space" ||
            j["reading_order_status"]?.jsonPrimitive?.content !in listOf("ok", "single_column") ||
            j["coord_space"]?.jsonPrimitive?.content?.startsWith("pdf-points; origin = MediaBox top-left;") != true) return@run fallback
        data class Block(val column: Int, val order: Int, val region: ReaderRegion, val protected: Boolean)
        val blocks = j.getValue("blocks").jsonArray.map { element ->
            val b = element.jsonObject
            val box = b["bbox"] as? JsonArray ?: return@run fallback
            if (box.size != 4) return@run fallback
            val p = box.map { it.jsonPrimitive.float }
            if (p.any { !it.isFinite() } || p[0] < 0 || p[1] < 0 || p[2] > width || p[3] > height || p[0] >= p[2] || p[1] >= p[3]) return@run fallback
            val region = ReaderRegion(p[0] / width, p[1] / height, p[2] / width, p[3] / height)
            val kind = b["kind"]?.jsonPrimitive?.content
            Block(b.getValue("column").jsonPrimitive.int, b.getValue("reading_order").jsonPrimitive.int,
                region, kind !in listOf("text", "heading", "list", "caption", "footnote", "header_footer") || region.width > .6f)
        }
        if (blocks.isEmpty()) return@run fallback
        val trim = blocks.map { it.region }.reduce(ReaderRegion::union)
        val grouped = blocks.filter { it.column > 0 }.groupBy { it.column }.values.sortedBy { group -> group.minOf { it.order } }
        val columns = grouped.map { group ->
            var region = group.map { it.region }.reduce(ReaderRegion::union)
            // Full-width/unassigned content is retained. Expanding the view is safer
            // than chopping a table, formula or heading into either text column.
            blocks.filter { it.column == 0 || it.protected }.forEach { region = region.union(it.region) }
            ReaderColumn(group.first().column, region)
        }
        ReaderPageLayout(index, width, height, trim, columns)
    }
}
