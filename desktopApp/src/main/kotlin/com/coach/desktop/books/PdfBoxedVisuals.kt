package com.coach.desktop.books

import com.coach.domain.reader.ReaderRect
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Automatic, local-only crops for explicitly painted rectangular enclosures.
 * A border is evidence of an enclosure, not of its semantic kind or reading order.
 * These supplemental assets NEVER carry a replacement interval or hide source text.
 * Model candidates and glyph envelopes alone cannot enter this path.
 */
internal object PdfBoxedVisuals {
    private const val MAX_RECTS = 256
    private const val MAX_OPERATIONS = 100_000
    private const val MAX_PAGE_ASSETS = 32
    private const val MAX_PAGE_BYTES = 16 * 1024 * 1024

    fun extract(document: PDDocument, page: BookPage, control: BookImportControl): List<ExtractedIllustration> {
        control.check()
        if (page.ocr || page.text.isBlank()) return emptyList()
        return try {
            extractSupported(document, page, control)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Unsupported visual extraction must not make a previously readable PDF fail import.
            emptyList()
        }
    }

    private fun extractSupported(document: PDDocument, page: BookPage, control: BookImportControl): List<ExtractedIllustration> {
        val physical = document.getPage(page.number - 1)
        val box = physical.cropBox
        if (Math.floorMod(physical.rotation, 360) != 0 ||
            !box.width.isFinite() || !box.height.isFinite() || box.width <= 0 || box.height <= 0 ||
            box.width > 4096 || box.height > 4096) return emptyList()
        val graphics = Enclosures(physical, control)
        graphics.processPage(physical)
        if (graphics.unsupported || graphics.rectangles.isEmpty()) return emptyList()
        val source = PdfPageTextExtraction.extract(document, page.number)
        if (source.unit.text != page.text || source.unmappedRanges.isNotEmpty()) return emptyList()
        val candidates = graphics.rectangles.distinct().filter { r ->
            r.width >= 60 && r.height >= 18 && r.width * r.height < box.width * box.height * .85 &&
                r.minX > box.lowerLeftX && r.maxX < box.upperRightX &&
                r.minY > box.lowerLeftY && r.maxY < box.upperRightY
        }.filter { r ->
            // Keep the outer enclosure, including inner diagrams. Ambiguous overlapping boxes
            // are refused together; asset ordering must not choose which contents survive.
            graphics.rectangles.none { other -> other != r && other.contains(r) } &&
                graphics.rectangles.none { other -> other != r && other.intersects(r) && !r.contains(other) }
        }
        if (candidates.isEmpty()) return emptyList()
        val scale = minOf(2f, 4096f / maxOf(box.width, box.height))
        val image = PDFRenderer(document).renderImage(page.number - 1, scale)
        try {
            val assets = mutableListOf<ExtractedIllustration>()
            var bytesUsed = 0L
            for (rect in candidates.sortedByDescending { it.maxY }) {
                control.check()
                if (assets.size >= MAX_PAGE_ASSETS || bytesUsed >= MAX_PAGE_BYTES) break
                // Integer raster edges are the authoritative saved region. Round outward and
                // retain two points of white exterior beyond the complete painted stroke.
                val x0 = floor((rect.minX - box.lowerLeftX - 2) * scale).toInt()
                val x1 = ceil((rect.maxX - box.lowerLeftX + 2) * scale).toInt()
                val y0 = floor((box.upperRightY - rect.maxY - 2) * scale).toInt()
                val y1 = ceil((box.upperRightY - rect.minY + 2) * scale).toInt()
                if (x0 < 0 || y0 < 0 || x1 > image.width || y1 > image.height ||
                    x1 <= x0 + 4 || y1 <= y0 + 4 || !whitePerimeter(image, x0, y0, x1, y1)) continue
                // PDFRenderer truncates the full-page raster dimensions; divide by its actual
                // scale, not by the rounded raster width/height (important for fractional pages).
                val region = ReaderRect(x0 / (box.width * scale).toDouble(), y0 / (box.height * scale).toDouble(),
                    (x1 - x0) / (box.width * scale).toDouble(), (y1 - y0) / (box.height * scale).toDouble())
                val mapping = PdfVisualSourceMapping.map(source, page.text, region)
                if (mapping.status != PdfVisualMappingStatus.MAPPED &&
                    mapping.status != PdfVisualMappingStatus.DISCONTIGUOUS) continue
                val anchor = mapping.ranges.minOf { it.startUtf16 }
                val crop = image.getSubimage(x0, y0, x1 - x0, y1 - y0)
                val bytes = ByteArrayOutputStream().also { ImageIO.write(crop, "png", it) }.toByteArray()
                if (bytes.size > BookIllustrationAssets.MAX_IMAGE_BYTES || bytesUsed + bytes.size > MAX_PAGE_BYTES) continue
                assets += ExtractedIllustration(page.number, anchor, bytes, "png", crop.width, crop.height,
                    kind = PageVisualKind.COMPLEX_REGION, sourceRegion = region)
                bytesUsed += bytes.size
            }
            return assets
        } finally { image.flush() }
    }

    private fun whitePerimeter(image: BufferedImage, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        fun white(x: Int, y: Int): Boolean {
            val rgb = image.getRGB(x, y)
            return (rgb shr 16 and 255) >= 250 && (rgb shr 8 and 255) >= 250 && (rgb and 255) >= 250
        }
        for (d in 0..1) {
            for (x in x0 until x1) if (!white(x, y0 + d) || !white(x, y1 - 1 - d)) return false
            for (y in y0 until y1) if (!white(x0 + d, y) || !white(x1 - 1 - d, y)) return false
        }
        return true
    }

    private class Enclosures(page: PDPage, private val control: BookImportControl) : PDFGraphicsStreamEngine(page) {
        val rectangles = mutableListOf<Rectangle2D.Double>()
        var unsupported = false
        private val pending = mutableListOf<Rectangle2D.Double>()
        private var current = Point2D.Float()
        private var operations = 0
        private fun tick() {
            control.check()
            if (++operations > MAX_OPERATIONS) throw IllegalArgumentException("visual operation budget")
        }
        override fun appendRectangle(a: Point2D, b: Point2D, c: Point2D, d: Point2D) {
            tick()
            // Points already include the CTM. Sheared/rotated rectangles are unsupported.
            if (a.y != b.y || b.x != c.x || c.y != d.y || d.x != a.x) return
            if (pending.size + rectangles.size >= MAX_RECTS) throw IllegalArgumentException("visual rectangle budget")
            pending += Rectangle2D.Double(minOf(a.x, c.x), minOf(a.y, c.y), kotlin.math.abs(c.x - a.x), kotlin.math.abs(c.y - a.y))
            current = Point2D.Float(a.x.toFloat(), a.y.toFloat())
        }
        private fun paint(stroke: Boolean) {
            tick()
            val matrix = graphicsState.currentTransformationMatrix
            val norm = sqrt((matrix.scaleX * matrix.scaleX + matrix.scaleY * matrix.scaleY +
                matrix.shearX * matrix.shearX + matrix.shearY * matrix.shearY).toDouble())
            // Frobenius norm bounds stroke expansion under any supported CTM.
            val pad = if (stroke) graphicsState.lineWidth * norm / 2 else 0.0
            if (!pad.isFinite() || pad > 12 || graphicsState.softMask != null) unsupported = true
            else for (r in pending) rectangles += Rectangle2D.Double(r.x - pad, r.y - pad, r.width + 2 * pad, r.height + 2 * pad)
            pending.clear()
        }
        override fun strokePath() = paint(true)
        override fun fillPath(windingRule: Int) = paint(false)
        override fun fillAndStrokePath(windingRule: Int) = paint(true)
        override fun endPath() { pending.clear() }
        override fun clip(windingRule: Int) { unsupported = true }
        override fun shadingFill(shadingName: COSName) { unsupported = true }
        override fun showForm(form: PDFormXObject) { unsupported = true }
        override fun drawImage(image: PDImage) { tick() }
        override fun moveTo(x: Float, y: Float) { tick(); current = Point2D.Float(x, y) }
        override fun lineTo(x: Float, y: Float) { tick(); current = Point2D.Float(x, y) }
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { tick(); current = Point2D.Float(x3, y3) }
        override fun getCurrentPoint(): Point2D = current
        override fun closePath() = Unit
    }
}
