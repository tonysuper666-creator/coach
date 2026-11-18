package com.coach.desktop.books

import com.coach.domain.reader.ReaderRect
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Recognize numbered tables with independently ruled row edges. Uncertain pages
 * continue through ordinary figure extraction and retain all canonical text. */
internal object PdfPrintedTables {
    private val caption = Regex("""(?im)^\s*(?:table\s+|表\s*)\d+""")
    private data class Label(val box: ReaderRect, val text: String)

    fun extract(
        pdf: PDDocument,
        page: BookPage,
        control: BookImportControl,
        existing: List<ExtractedIllustration> = emptyList(),
    ): List<ExtractedIllustration> {
        if (page.ocr || !caption.containsMatchIn(page.text)) return emptyList()
        return try {
            supported(pdf, page, control, existing.mapNotNull { it.sourceRegion })
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun supported(
        pdf: PDDocument,
        page: BookPage,
        control: BookImportControl,
        occupied: List<ReaderRect>,
    ): List<ExtractedIllustration> {
        val physical = pdf.getPage(page.number - 1)
        val box = physical.cropBox
        if (physical.rotation % 360 != 0 || box.width !in 1f..4096f || box.height !in 1f..4096f) return emptyList()
        val lines = PdfTableFrames(physical, control)
        lines.processPage(physical)
        val frames = lines.frames()
        if (frames.isEmpty() || frames.size > 16) return emptyList()
        val source = PdfPageTextExtraction.extract(pdf, page.number)
        if (source.unit.text != page.text || source.unmappedRanges.isNotEmpty() || source.unit.spans.size > 8000) return emptyList()
        val labels = caption.findAll(page.text).mapNotNull { match ->
            source.unit.spans.firstOrNull { match.range.last in it.range.startUtf16 until it.range.endUtf16 }
                ?.geometry?.boxes?.singleOrNull()?.let { bounds ->
                    val start = match.range.last
                    val titleStart = page.text.lastIndexOf('\n', start) + 1
                    val titleEnd = page.text.indexOf('\n', start).takeIf { it >= 0 } ?: page.text.length
                    Label(bounds, page.text.substring(titleStart, titleEnd).trim())
                }
        }.toList()
        if (frames.sumOf { it.boundaries.size } * source.unit.spans.size > 1_000_000) return emptyList()
        val nearLabels = frames.associateWith { frame ->
            labels.filter { label ->
                val gap = frame.boundaries.first() - (label.box.y + label.box.height) * box.height
                gap in 0.0..48.0 && label.box.x * box.width in frame.left - 16.0..frame.right.toDouble()
            }
        }
        val scale = minOf(3f, 4096f / maxOf(box.width, box.height))
        val raster = lazy { PDFRenderer(pdf).renderImage(page.number - 1, scale) }
        try {
            return buildList {
                var bytesUsed = 0L
                for ((frame, nearby) in nearLabels) {
                    val label = nearby.singleOrNull() ?: continue
                    if (nearLabels.values.count { label in it } != 1) continue
                    control.check()
                    val (body, cell) = lines.fontSizes(frame) ?: continue
                    val cuts = frame.boundaries
                    val left = floor((frame.left - 2) * scale).toInt()
                    val top = floor((cuts.first() - 2) * scale).toInt()
                    val right = ceil((frame.right + 2) * scale).toInt()
                    val bottom = ceil((cuts.last() + 2) * scale).toInt()
                    if (left < 0 || top < 0 || right > floor(box.width * scale) || bottom > floor(box.height * scale)) continue
                    val pixels = listOf(top) + cuts.drop(1).dropLast(1).map { (it * scale).roundToInt() } + bottom
                    fun region(a: Int, b: Int) = ReaderRect(left / (box.width * scale).toDouble(),
                        a / (box.height * scale).toDouble(), (right - left) / (box.width * scale).toDouble(),
                        (b - a) / (box.height * scale).toDouble())
                    val bounds = region(top, bottom)
                    if (occupied.any { PdfBookImages.intersects(it, bounds) }) continue
                    val rows = pixels.zipWithNext().mapNotNull { (a, b) ->
                        control.check()
                        val range = PdfVisualSourceMapping.map(source, page.text, region(a, b)).singleRange
                        range?.let { ImportedTableRow(b - top, it) }
                    }
                    val table = ImportedTable(rows, body * scale, cell * scale)
                    if (rows.size + 1 != pixels.size || !table.valid(bottom - top, page.text)) continue
                    if (!PdfBoxedVisuals.whitePerimeter(raster.value, left, top, right, bottom)) continue
                    val crop = raster.value.getSubimage(left, top, right - left, bottom - top)
                    val bytes = ByteArrayOutputStream().also { ImageIO.write(crop, "png", it) }.toByteArray()
                    if (bytes.size > BookIllustrationAssets.MAX_IMAGE_BYTES || bytesUsed + bytes.size > 16 * 1024 * 1024) continue
                    bytesUsed += bytes.size
                    add(ExtractedIllustration(
                        page.number, rows.first().source.startUtf16, bytes, "png", crop.width, crop.height,
                        caption = label.text, kind = PageVisualKind.TABLE, sourceRegion = bounds, table = table,
                    ))
                }
            }
        } finally {
            if (raster.isInitialized()) raster.value.flush()
        }
    }
}
