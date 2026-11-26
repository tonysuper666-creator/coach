package com.coach.desktop.books

import com.coach.domain.reader.ReaderRect
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Supplemental bitmaps and captioned line art. Existing enclosure crops take precedence.
 * Geometry is never permission to remove text: these assets carry no replacement interval.
 */
internal object PdfBookImages {
    private data class Caption(val text: String, val offset: Int, val box: ReaderRect)
    private data class Candidate(val box: ReaderRect, val caption: Caption?, val bitmap: Boolean)

    fun extract(document: BookPdfDocument, page: BookPage, enclosed: List<ExtractedIllustration>,
        control: BookImportControl, includeVectorCandidates: Boolean = true): List<ExtractedIllustration> {
        control.check()
        if (page.ocr || page.text.isBlank()) return enclosed
        return try { supported(document, page, enclosed, control, includeVectorCandidates) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { enclosed } // Unsupported supplements cannot break readable books.
    }

    private fun supported(document: BookPdfDocument, page: BookPage, enclosed: List<ExtractedIllustration>,
        control: BookImportControl, includeVectorCandidates: Boolean): List<ExtractedIllustration> {
        val physical = document.getPage(page.number - 1)
        val crop = physical.cropBox
        if (Math.floorMod(physical.rotation, 360) != 0 || !crop.width.isFinite() || !crop.height.isFinite() ||
            crop.width <= 0 || crop.height <= 0 || crop.width > 4096 || crop.height > 4096) return enclosed
        val graphics = Graphics(physical, control)
        graphics.processPage(physical)
        if (graphics.unsupported || graphics.images.isEmpty() && graphics.paths.isEmpty()) return enclosed
        val source = PdfPageTextExtraction.extract(document, page.number)
        if (source.unit.text != page.text || source.unmappedRanges.isNotEmpty()) return enclosed
        fun normalize(r: BookPdfBounds): ReaderRect? {
            if (r.width <= 0 || r.height <= 0 || r.minX <= crop.lowerLeftX || r.maxX >= crop.upperRightX ||
                r.minY <= crop.lowerLeftY || r.maxY >= crop.upperRightY) return null
            return ReaderRect((r.minX - crop.lowerLeftX) / crop.width,
                (crop.upperRightY - r.maxY) / crop.height, r.width / crop.width, r.height / crop.height)
        }
        val captions = captions(page.text, source)
        fun caption(region: ReaderRect): Caption? = captions.filter {
            val overlap = minOf(it.box.x + it.box.width, region.x + region.width) - maxOf(it.box.x, region.x)
            val below = (it.box.y - region.y - region.height) * crop.height
            val above = (region.y - it.box.y - it.box.height) * crop.height
            overlap > 0 && (below in 0.0..48.0 || above in 0.0..24.0)
        }.minByOrNull {
            val below = it.box.y - region.y - region.height
            if (below >= 0) below else region.y - it.box.y - it.box.height + 24 / crop.height
        }
        val candidates = graphics.images.mapNotNull { normalize(it) }.filter { sane(it, crop.width, crop.height) }
            .map { Candidate(it, caption(it), true) }.toMutableList()
        // A nearby numbered caption is required for unboxed vector art; rules, underlines
        // and page furniture are not automatically promoted to illustrations.
        for (path in if (includeVectorCandidates) connected(graphics.paths) else emptyList()) {
            val region = normalize(path) ?: continue
            if (path.width < 40 || path.height < 25 || !sane(region, crop.width, crop.height)) continue
            val label = caption(region) ?: continue
            // Include nearby axis labels, but never the caption itself or surrounding prose.
            val labels = source.unit.spans.filter { it.range.startUtf16 < label.offset ||
                it.range.startUtf16 >= label.offset + label.text.length }.flatMap { it.geometry.boxes }
                .filter { near(region, it, 12 / crop.width.toDouble(), 12 / crop.height.toDouble()) }
            val expanded = (listOf(region) + labels).reduce(::union)
            if (!intersects(expanded, label.box)) candidates += Candidate(expanded, label, false)
        }
        val result = enclosed.map { old ->
            if (old.table != null || old.sourceStart != null) return@map old
            val label = old.sourceRegion?.let(::caption)?.text ?: old.caption
            ExtractedIllustration(old.page, old.anchorOffset, old.bytes, old.extension, old.width, old.height,
                label, old.kind, old.sourceStart, old.sourceEnd, old.sourceRegion)
        }.toMutableList()
        val accepted = enclosed.mapNotNull { it.sourceRegion }.toMutableList()
        // Prefer original image placements over overlapping path candidates.
        val unique = candidates.sortedByDescending { it.bitmap }.filter { candidate ->
            if (accepted.any { intersects(it, candidate.box) }) false
            else { accepted += candidate.box; true }
        }.sortedBy { it.box.y }
        if (unique.isEmpty()) return result
        val scale = minOf(2f, 4096f / maxOf(crop.width, crop.height))
        val image = BookPdfRenderer(document).renderImage(page.number - 1, scale)
        try {
            var bytesUsed = result.sumOf { it.bytes.size.toLong() }
            for (candidate in unique) {
                control.check()
                if (result.size >= 32 || bytesUsed >= 16 * 1024 * 1024) break
                val box = candidate.box
                val x0 = floor((box.x * crop.width - 2) * scale).toInt()
                val y0 = floor((box.y * crop.height - 2) * scale).toInt()
                val x1 = ceil(((box.x + box.width) * crop.width + 2) * scale).toInt()
                val y1 = ceil(((box.y + box.height) * crop.height + 2) * scale).toInt()
                if (x0 < 0 || y0 < 0 || x1 > image.width || y1 > image.height || x1 - x0 < 5 || y1 - y0 < 5) continue
                fun white(x: Int, y: Int): Boolean {
                    val rgb = bookBitmapPixel(image, x, y)
                    return (rgb shr 16 and 255) >= 250 && (rgb shr 8 and 255) >= 250 && (rgb and 255) >= 250
                }
                // Reject crops that cut adjacent labels, captions or unrelated ink.
                if ((x0 until x1).any { !white(it, y0) || !white(it, y1 - 1) } ||
                    (y0 until y1).any { !white(x0, it) || !white(x1 - 1, it) }) continue
                val region = ReaderRect(x0 / (crop.width * scale).toDouble(), y0 / (crop.height * scale).toDouble(),
                    (x1 - x0) / (crop.width * scale).toDouble(), (y1 - y0) / (crop.height * scale).toDouble())
                if (enclosed.any { it.sourceRegion?.let { old -> intersects(old, region) } == true }) continue
                val following = source.unit.spans.filter {
                    it.geometry.boxes.any { glyph -> glyph.y >= box.y + box.height &&
                        glyph.x < box.x + box.width && box.x < glyph.x + glyph.width }
                }.minByOrNull { it.range.startUtf16 }?.range?.startUtf16 ?: page.text.length
                val anchor = candidate.caption?.offset ?: if (following < page.text.length)
                    page.text.lastIndexOf('\n', following - 1) + 1 else page.text.length
                val bytes = bookBitmapCropPng(image, x0, y0, x1 - x0, y1 - y0)
                if (bytes.size > FormatBookImages.MAX_BYTES || bytesUsed + bytes.size > 16 * 1024 * 1024) continue
                bytesUsed += bytes.size
                result += ExtractedIllustration(page.number,
                    BookIllustrationAnchors.snapToValidUtf16Boundary(page.text, anchor), bytes, "png", x1 - x0, y1 - y0,
                    candidate.caption?.text, sourceRegion = region)
            }
        } finally { releaseBookBitmap(image) }
        return result
    }

    private fun captions(text: String, source: PdfPageText): List<Caption> {
        val result = mutableListOf<Caption>()
        val prefix = Regex("^(?:[图表]\\s*\\d|(?:fig(?:ure)?\\.?|table)\\s*[0-9IVX])", RegexOption.IGNORE_CASE)
        var start = 0
        for (line in text.split('\n')) {
            val offset = start + line.length - line.trimStart().length
            val value = line.trim()
            if (prefix.containsMatchIn(value)) {
                val boxes = source.unit.spans.filter { it.range.startUtf16 >= offset &&
                    it.range.endUtf16 <= offset + value.length }.flatMap { it.geometry.boxes }
                if (boxes.isNotEmpty()) result += Caption(value, offset, boxes.reduce(::union))
            }
            start += line.length + 1
        }
        return result
    }

    private fun sane(r: ReaderRect, width: Float, height: Float) = r.x > 0 && r.y > 0 &&
        r.x + r.width < 1 && r.y + r.height < 1 && r.width * width >= 24 &&
        r.height * height >= 18 && r.width * r.height < .85
    fun intersects(a: ReaderRect, b: ReaderRect) = a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
    private fun near(a: ReaderRect, b: ReaderRect, x: Double, y: Double) = a.x - x < b.x + b.width && b.x - x < a.x + a.width && a.y - y < b.y + b.height && b.y - y < a.y + a.height
    private fun union(a: ReaderRect, b: ReaderRect): ReaderRect {
        val x = minOf(a.x, b.x); val y = minOf(a.y, b.y)
        return ReaderRect(x, y, maxOf(a.x + a.width, b.x + b.width) - x, maxOf(a.y + a.height, b.y + b.height) - y)
    }

    private fun connected(paths: List<BookPdfBounds>): List<BookPdfBounds> {
        val result = mutableListOf<BookPdfBounds>()
        for (path in paths) {
            var merged = path.copy()
            var index = 0
            while (index < result.size) {
                val old = result[index]
                if (old.minX <= merged.maxX + 4 && merged.minX <= old.maxX + 4 &&
                    old.minY <= merged.maxY + 4 && merged.minY <= old.maxY + 4) {
                    merged = merged.createUnion(old)
                    result.removeAt(index); index = 0
                } else index++
            }
            result += merged
        }
        return result
    }

    private class Graphics(page: BookPdfPage, private val control: BookImportControl) : BookPdfGraphicsEngine(page) {
        val images = mutableListOf<BookPdfBounds>()
        val paths = mutableListOf<BookPdfBounds>()
        var unsupported = false
        private var path: BookPdfBounds? = null
        private var current = newBookPdfPoint()
        private var subpath = newBookPdfPoint()
        private var operations = 0
        private fun tick() { control.check(); require(++operations <= 100_000 && images.size + paths.size < 2048) }
        private fun point(x: Double, y: Double) {
            tick(); require(x.isFinite() && y.isFinite())
            path?.add(x, y) ?: run { path = BookPdfBounds(x, y, 0.0, 0.0) }
        }
        override fun moveTo(x: Float, y: Float) { point(x.toDouble(), y.toDouble()); current = newBookPdfPoint(x, y); subpath = current }
        override fun lineTo(x: Float, y: Float) { point(x.toDouble(), y.toDouble()); current = newBookPdfPoint(x, y) }
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            point(x1.toDouble(), y1.toDouble()); point(x2.toDouble(), y2.toDouble()); lineTo(x3, y3)
        }
        override fun appendRectangle(a: BookPdfPoint, b: BookPdfPoint, c: BookPdfPoint, d: BookPdfPoint) {
            listOf(a, b, c, d).forEach { point(it.x.toDouble(), it.y.toDouble()) }; current = newBookPdfPoint(a.x.toFloat(), a.y.toFloat()); subpath = current
        }
        private fun paint(stroke: Boolean) {
            tick()
            val matrix = graphicsState.currentTransformationMatrix
            val norm = sqrt((matrix.scaleX * matrix.scaleX + matrix.scaleY * matrix.scaleY + matrix.shearX * matrix.shearX + matrix.shearY * matrix.shearY).toDouble())
            val pad = if (stroke) graphicsState.lineWidth * norm / 2 else 0.0
            if (!pad.isFinite() || pad > 12 || graphicsState.softMask != null) unsupported = true
            else path?.let { paths += BookPdfBounds(it.x - pad, it.y - pad, it.width + pad * 2, it.height + pad * 2) }
            path = null
        }
        override fun strokePath() = paint(true)
        override fun fillPath(windingRule: BookPdfFillRule) = paint(false)
        override fun fillAndStrokePath(windingRule: BookPdfFillRule) = paint(true)
        override fun endPath() { path = null }
        override fun closePath() { current = subpath }
        override fun getCurrentPoint(): BookPdfPoint = current
        override fun clip(windingRule: BookPdfFillRule) { unsupported = true }
        override fun shadingFill(shadingName: BookPdfName) { unsupported = true }
        override fun showForm(form: BookPdfForm) { unsupported = true }
        override fun drawImage(image: BookPdfImage) {
            tick()
            val matrix = graphicsState.currentTransformationMatrix
            val points = listOf(matrix.transformPoint(0f, 0f), matrix.transformPoint(1f, 0f), matrix.transformPoint(1f, 1f), matrix.transformPoint(0f, 1f))
            val x = points.minOf { it.x }.toDouble(); val y = points.minOf { it.y }.toDouble()
            val w = points.maxOf { it.x } - x; val h = points.maxOf { it.y } - y
            if (listOf(x, y, w, h).all { it.isFinite() }) images += BookPdfBounds(x, y, w, h)
        }
    }
}
