package com.coach.desktop.books

import com.coach.domain.reader.ReaderRect
import kotlinx.serialization.json.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import javax.imageio.ImageIO
import kotlin.math.min

/** Pixel edges in the production PDFBox raster (2x, reduced to at most 4096px per edge).
 * Select the ENTIRE object, including its title, caption, notes and credits. Geometry does
 * not establish that ownership: a local reviewer must inspect the full-size preview.
 */
internal data class PdfVisualSelection(
    val page: Int, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val kind: PageVisualKind,
) {
    fun overlaps(other: PdfVisualSelection): Boolean = page == other.page &&
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

internal enum class PdfVisualDecision { UNREVIEWED, COMPLETE_OBJECT_WITH_LABELS, REJECTED }

/** A local review declaration, NOT a signature or an automated semantic certificate.
 * Digests bind that declaration to the immutable PDF, canonical UTF-16 text and exact PNG.
 * Merely passing the boundary probe, or opening a contact sheet, grants no approval.
 */
internal data class PdfVisualReview(
    val schema: Int = 1,
    val documentSha256: String,
    val pageTextSha256: String,
    val pngSha256: String,
    val selection: PdfVisualSelection,
    val decision: PdfVisualDecision = PdfVisualDecision.UNREVIEWED,
)

internal data class PdfVisualPreview(val review: PdfVisualReview, val image: ExtractedIllustration)

/** Desktop does not apply the serialization compiler plugin; keep this small local schema explicit. */
internal object PdfVisualReviewCodec {
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.let {
        require(it.isString); it.content
    }
    private fun selection(value: JsonObject) = PdfVisualSelection(
        value.getValue("page").jsonPrimitive.int, value.getValue("left").jsonPrimitive.int,
        value.getValue("top").jsonPrimitive.int, value.getValue("right").jsonPrimitive.int,
        value.getValue("bottom").jsonPrimitive.int, PageVisualKind.valueOf(value.string("kind")))
    fun decodeSelection(text: String): PdfVisualSelection = selection(Json.parseToJsonElement(text).jsonObject)
    fun decode(text: String): PdfVisualReview = Json.parseToJsonElement(text).jsonObject.let { value ->
        PdfVisualReview(value.getValue("schema").jsonPrimitive.int, value.string("documentSha256"),
            value.string("pageTextSha256"), value.string("pngSha256"),
            selection(value.getValue("selection").jsonObject), PdfVisualDecision.valueOf(value.string("decision")))
    }
    fun encode(review: PdfVisualReview): String = buildJsonObject {
        put("schema", review.schema); put("documentSha256", review.documentSha256)
        put("pageTextSha256", review.pageTextSha256); put("pngSha256", review.pngSha256)
        put("decision", review.decision.name)
        putJsonObject("selection") {
            val s = review.selection
            put("page", s.page); put("left", s.left); put("top", s.top)
            put("right", s.right); put("bottom", s.bottom); put("kind", s.kind.name)
        }
    }.toString()
}

/** Explicit, on-demand import of reviewed objects. No sidecar discovery, model download,
 * largest-box heuristic, caption inference, text replacement or rendering integration.
 */
internal object PdfReviewedVisuals {
    private const val MAX_OBJECTS = 128
    private const val MAX_BYTES = 32 * 1024 * 1024

    fun preview(
        pdfBytes: ByteArray, pages: List<BookPage>, selections: List<PdfVisualSelection>,
        control: BookImportControl = BookImportControl(),
    ): List<PdfVisualPreview> {
        require(selections.isNotEmpty() && selections.size <= MAX_OBJECTS) { "请分批复核裁图。" }
        require(selections.groupingBy { it.page }.eachCount().values.all { it <= 32 }) { "单页裁图过多。" }
        val canonical = pages.associateBy { it.number }
        require(canonical.size == pages.size) { "教材页码重复。" }
        val digest = BookIllustrationAssets.sha256(pdfBytes)
        var byteCount = 0L
        return Loader.loadPDF(pdfBytes).use { document ->
            val renderer = PDFRenderer(document)
            val results = mutableMapOf<PdfVisualSelection, PdfVisualPreview>()
            for ((number, selected) in selections.groupBy { it.page }) {
                control.check()
                val page = requireNotNull(canonical[number]) { "裁图指向了不存在的页码。" }
                require(number in 1..document.numberOfPages && !page.ocr) { "这页暂不能批准裁图。" }
                val physical = document.getPage(number - 1)
                val frame = physical.cropBox
                require(physical.rotation == 0 && frame.width.isFinite() && frame.height.isFinite() &&
                    frame.width > 0 && frame.height > 0 && maxOf(frame.width, frame.height) <= 4096) { "暂不支持这页的几何信息。" }
                val scale = min(2f, 4096f / maxOf(frame.width, frame.height))
                val source = PdfPageTextExtraction.extractForVisualReview(document, number, scale.toDouble())
                require(source.unit.text == page.text) { "页文字已变化，请重新复核裁图。" }
                val raster = renderer.renderImage(number - 1, scale)
                try {
                    for (selection in selected) {
                        control.check()
                        require(selection.left >= 0 && selection.top >= 0 && selection.right <= raster.width &&
                            selection.bottom <= raster.height && selection.left < selection.right && selection.top < selection.bottom) { "裁图超出页框。" }
                        // Use the physical frame times scale, not the truncated raster dimensions.
                        val region = ReaderRect(selection.left / (frame.width.toDouble() * scale),
                            selection.top / (frame.height.toDouble() * scale),
                            (selection.right - selection.left) / (frame.width.toDouble() * scale),
                            (selection.bottom - selection.top) / (frame.height.toDouble() * scale))
                        val mapping = PdfVisualSourceMapping.map(source, page.text, region)
                        require(mapping.status in setOf(PdfVisualMappingStatus.MAPPED, PdfVisualMappingStatus.DISCONTIGUOUS)) { "裁图切到了文字或无法可靠定位，请重新复核。" }
                        val crop = raster.getSubimage(selection.left, selection.top,
                            selection.right - selection.left, selection.bottom - selection.top)
                        val png = try { ByteArrayOutputStream().use { out ->
                            check(ImageIO.write(crop, "png", out)); out.toByteArray()
                        } } finally { crop.flush() }
                        byteCount += png.size
                        require(png.size <= BookIllustrationAssets.MAX_IMAGE_BYTES && byteCount <= MAX_BYTES) { "裁图总量过大，请分批复核。" }
                        val image = ExtractedIllustration(number, mapping.ranges.minOf { it.startUtf16 }, png,
                            "png", selection.right - selection.left, selection.bottom - selection.top,
                            kind = selection.kind, sourceRegion = region)
                        val review = PdfVisualReview(documentSha256 = digest,
                            pageTextSha256 = BookIllustrationAssets.sha256(page.text.toByteArray(StandardCharsets.UTF_16LE)),
                            pngSha256 = image.contentSha256, selection = selection)
                        results[selection] = PdfVisualPreview(review, image)
                    }
                } finally { raster.flush() }
            }
            selections.map { results.getValue(it) }
        }
    }

    fun extract(
        pdfBytes: ByteArray, pages: List<BookPage>, reviews: List<PdfVisualReview>,
        control: BookImportControl = BookImportControl(),
    ): List<ExtractedIllustration> {
        require(reviews.isNotEmpty() && reviews.size <= MAX_OBJECTS) { "请分批批准裁图。" }
        val digest = BookIllustrationAssets.sha256(pdfBytes)
        require(reviews.all { it.schema == 1 && it.decision == PdfVisualDecision.COMPLETE_OBJECT_WITH_LABELS &&
            it.documentSha256 == digest && BookIllustrationAssets.isValidSha256(it.pageTextSha256) &&
            BookIllustrationAssets.isValidSha256(it.pngSha256) }) { "裁图尚未完整复核，或原文件已变化。" }
        for ((index, review) in reviews.withIndex()) require(reviews.take(index).none {
            review.selection.overlaps(it.selection)
        }) { "同页裁图重叠，请复核并保留完整的一份。" }
        return preview(pdfBytes, pages, reviews.map { it.selection }, control).zip(reviews).map { (draft, review) ->
            require(draft.review == review.copy(decision = PdfVisualDecision.UNREVIEWED)) { "裁图或页文字已变化，请重新复核。" }
            draft.image // Supplemental only: sourceStart/sourceEnd remain null, even when contiguous.
        }
    }
}
