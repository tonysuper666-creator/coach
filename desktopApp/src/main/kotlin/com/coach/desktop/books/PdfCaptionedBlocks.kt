package com.coach.desktop.books

import com.coach.domain.reader.ReaderRect
import com.coach.domain.reader.ReaderTextRange
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Printed titles, rules, and actual raster ink delimit the block. Only a contiguous, exact
 * mapping back to the same canonical page can remove text from the visible reflow. */
internal object PdfCaptionedBlocks {
    private val tableTitle = Regex("""^(?:table\s+|表\s*)\d""", RegexOption.IGNORE_CASE)
    private val figureTitle = Regex("""^(?:fig(?:ure)?\.?\s*|图\s*)\d""", RegexOption.IGNORE_CASE)
    private val words = Regex("""\p{L}{3,}""")
    private val sentence = Regex("""\p{L}{3,}(?:[ \t]+\p{L}{3,}){3,}""")
    private val equationNumber = Regex("""\(\d{1,3}\.\d{1,3}[a-z]?\)""")
    private fun hasLimits(text: String) = '∑' in text || '∫' in text
    private fun hasEquation(text: String) = '=' in text && (hasLimits(text) || equationNumber.containsMatchIn(text))
    private data class Line(val start: Int, val end: Int, val text: String, val x: Double,
        val y: Double, val right: Double, val bottom: Double, val size: Double, val angle: Int)

    fun extract(pdf: PDDocument, page: BookPage, tables: List<ExtractedIllustration>,
        control: BookImportControl): List<ExtractedIllustration> {
        if (page.ocr || (!hasEquation(page.text) && page.text.lineSequence().none {
                tableTitle.containsMatchIn(it.trimStart()) || figureTitle.containsMatchIn(it.trimStart())
            })) return emptyList()
        return try {
            supported(pdf, page, tables, control)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun supported(pdf: PDDocument, page: BookPage, tables: List<ExtractedIllustration>,
        control: BookImportControl): List<ExtractedIllustration> {
        val physical = pdf.getPage(page.number - 1)
        if (physical.rotation % 360 != 0 || physical.cropBox.width !in 1f..4096f ||
            physical.cropBox.height !in 1f..4096f) return emptyList()
        val source = PdfPageTextExtraction.extract(pdf, page.number)
        if (source.unit.text != page.text || source.unit.spans.size !in 1..8000) return emptyList()
        if (source.unmappedRanges.isEmpty()) return Extractor(pdf, page, source, control).collect(tables)
        // Some printed symbols have zero advance but a real embedded outline. Only
        // use this second extraction when it accounts for every missing source offset.
        if (!hasEquation(page.text)) return emptyList()
        val precise = PdfPageTextExtraction.extractForVisualReview(pdf, page.number, 2.0)
        if (precise.unit.text != page.text || precise.frame != source.frame || precise.unmappedRanges.isNotEmpty() ||
            precise.unit.spans.size !in 1..8000) return emptyList()
        return Extractor(pdf, page, precise, control).collect(emptyList(), equationsOnly = true)
    }

    private class Extractor(
        private val pdf: PDDocument, private val page: BookPage, private val source: PdfPageText,
        private val control: BookImportControl,
    ) {
        private val physical = pdf.getPage(page.number - 1)
        private val box = physical.cropBox
        private val w = box.width.toDouble()
        private val h = box.height.toDouble()
        private val glyphs = source.unit.spans.sortedBy { it.range.startUtf16 }
        private val lines = buildList {
            var glyph = 0
            for (match in Regex("""[^\r\n]+""").findAll(page.text)) {
                val end = match.range.last + 1
                val first = glyph
                while (glyph < glyphs.size && glyphs[glyph].range.startUtf16 < end) glyph++
                if (first == glyph) continue
                val spans = glyphs.subList(first, glyph)
                val boxes = spans.flatMap { it.geometry.boxes }
                if (boxes.isEmpty()) continue
                val dx = (boxes.last().x - boxes.first().x) * w
                val dy = (boxes.last().y - boxes.first().y) * h
                val angle = when {
                    abs(dy) > abs(dx) * 2 && abs(dy) > 4 -> if (dy < 0) 90 else 270
                    dx < -4 -> 180
                    else -> 0
                }
                val heights = boxes.map { it.height * h }.sorted()
                add(Line(spans.first().range.startUtf16, spans.last().range.endUtf16, match.value.trim(),
                    boxes.minOf { it.x } * w, boxes.minOf { it.y } * h, boxes.maxOf { it.x + it.width } * w,
                    boxes.maxOf { it.y + it.height } * h, heights[heights.size / 2], angle))
            }
        }
        private val scale = minOf(3f, 4096f / maxOf(box.width, box.height))
        private val raster = lazy { PDFRenderer(pdf).renderImage(page.number - 1, scale) }
        private val rules = lazy { PdfTableFrames(physical, control).also { it.processPage(physical) } }
        private val blocks = mutableListOf<ExtractedIllustration>()
        private var totalBytes = 0
        fun collect(tables: List<ExtractedIllustration>, equationsOnly: Boolean = false): List<ExtractedIllustration> {
            try {
                if (!equationsOnly) {
                    addTables(tables)
                    addChart()
                }
                if (hasEquation(page.text)) addEquations(if (equationsOnly) source else
                    PdfPageTextExtraction.extractForVisualReview(pdf, page.number, 2.0))
                return blocks
            } finally {
                if (raster.isInitialized()) raster.value.flush()
            }
        }

        private fun add(expected: ReaderTextRange, above: Double, below: Double, kind: PageVisualKind,
            rotation: Int = 0, old: ExtractedIllustration? = null, geometry: PdfPageText = source) {
            if (above >= below || totalBytes >= 16 * 1024 * 1024) return
            control.check()
            val image = raster.value
            val top = whiteEdge(image, floor(above * scale).toInt(), -1, ceil(4 * scale).toInt()) ?: return
            val bottom = whiteEdge(image, ceil(below * scale).toInt(), 1, ceil(4 * scale).toInt()) ?: return
            var left = image.width
            var right = -1
            for (y in top..bottom) {
                if (y % 64 == 0) control.check()
                for (x in 0 until image.width) if (ink(image.getRGB(x, y))) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
            if (right <= left || left < 2 || right >= image.width - 2) return
            left = maxOf(0, left - ceil(2 * scale).toInt())
            right = minOf(image.width, right + ceil(2 * scale).toInt() + 1)
            val height = bottom + 1 - top
            val region = ReaderRect(left / (w * scale), top / (h * scale), (right - left) / (w * scale), height / (h * scale))
            if (region.width * region.height > .85 || blocks.any { PdfBookImages.intersects(it.sourceRegion!!, region) }) return
            val range = PdfVisualSourceMapping.map(geometry, page.text, region).singleRange ?: return
            if (range != expected) return
            val raw = image.getSubimage(left, top, right - left, height)
            val bitmap = if (rotation == 0) raw else turnUpright(raw, rotation)
            val bytes = try { ByteArrayOutputStream().also { ImageIO.write(bitmap, "png", it) }.toByteArray() }
                finally { if (bitmap !== raw) bitmap.flush() }
            if (bytes.size > BookIllustrationAssets.MAX_IMAGE_BYTES || bytes.size + totalBytes > 16 * 1024 * 1024) return
            val table = old?.table?.let { saved ->
                val oldTop = (old.sourceRegion!!.y * h * scale).roundToInt() - top
                if (oldTop < 0 || oldTop + old.height > height) return
                val rows = saved.rows.mapIndexed { index, row ->
                    row.copy(bottomPx = if (index == saved.rows.lastIndex) height else oldTop + row.bottomPx)
                }
                saved.copy(rows = rows, headerTopPx = oldTop, dataBottomPx = oldTop + old.height)
            }
            blocks += ExtractedIllustration(page.number, range.startUtf16, bytes, "png", bitmap.width, bitmap.height,
                kind = kind, sourceStart = range.startUtf16, sourceEnd = range.endUtf16, sourceRegion = region, table = table)
            totalBytes += bytes.size
        }
        private fun addTables(tables: List<ExtractedIllustration>) {
            for ((i, title) in lines.withIndex()) {
                if (blocks.size >= 16) break
                if (title.angle != 0 || !tableTitle.containsMatchIn(title.text)) continue
                val following = lines.drop(i + 1).takeWhile { line ->
                    line.angle == 0 && !tableTitle.containsMatchIn(line.text) && !figureTitle.containsMatchIn(line.text) &&
                        !(line.text.length >= 25 && line.size > title.size * 1.075)
                }
                val stop = lines.getOrNull(i + following.size + 1)?.y ?: h
                val panels = rules.value.ruledPanels(title.bottom, title.x, stop)
                if (panels.isEmpty()) continue
                val lowerRule = panels.maxOf { it.boundaries.last().toDouble() }
                val inGrid = following.takeWhile { it.y < lowerRule }
                val leftEdge = panels.first().left - 4.0
                val rightEdge = panels.last().right + 4.0
                if (inGrid.size < 2 || inGrid.any { it.y < title.y || it.bottom > lowerRule + 1 ||
                        it.x < leftEdge || it.right > rightEdge }) continue
                var lower = lowerRule
                val notes = following.drop(inGrid.size).takeWhile {
                    val note = it.size < title.size * .93 && it.y - lower in -2.0..16.0 &&
                        it.x >= leftEdge && it.right <= rightEdge
                    if (note) lower = maxOf(lower, it.bottom)
                    note
                }
                val last = notes.lastOrNull() ?: inGrid.last()
                val old = if (panels.size == 1) tables.singleOrNull { t ->
                    val r = t.sourceRegion ?: return@singleOrNull false
                    abs(r.x * w - panels[0].left) < 4 && abs((r.y + r.height) * h - lowerRule) < 4
                } else null
                add(ReaderTextRange(title.start, last.end), title.y - 2, lower + 2, PageVisualKind.TABLE, old = old)
            }
        }

        private fun addChart() {
            // A dedicated chart plate has a numbered caption, plotted labels in more than one
            // direction and no running prose. Keep all of its ink, including ticks, panels and
            // credits, together. Running heads and folios stay in the normal text flow.
            val body = lines.dropWhile { it.bottom < .065 * h && it.angle == 0 }
                .dropLastWhile { it.y > .94 * h && it.text.all(Char::isDigit) }
            val caption = body.singleOrNull { figureTitle.containsMatchIn(it.text) }
            if (caption != null && body.size >= 6 && body.count { it.angle != 0 } >= 1 &&
                body.none { it !== caption && it.angle == 0 && it.text.length > 60 } &&
                body.none { it !== caption && it.angle == 0 && it.text.count { c -> c == ' ' } > 9 } &&
                body.none { tableTitle.containsMatchIn(it.text) }) {
                val previous = lines.getOrNull(lines.indexOf(body.first()) - 1)
                val next = lines.getOrNull(lines.indexOf(body.last()) + 1)
                val above = previous?.bottom?.plus(5) ?: 1.0
                val below = next?.y?.minus(5) ?: h - 1
                if (body.all { it.y > above && it.bottom < below }) {
                    // Trim the whitespace between the header and the illustration as well.
                    val image = raster.value
                    val band = ceil(above * scale).toInt()..floor(below * scale).toInt()
                    fun hasInk(y: Int) = (0 until image.width).any { ink(image.getRGB(it, y)) }
                    val y1 = band.firstOrNull(::hasInk)
                    val y2 = band.lastOrNull(::hasInk)
                    if (y1 != null && y2 != null && hasChartAxes(image, y1, y2, scale)) add(ReaderTextRange(body.first().start, body.last().end), y1 / scale.toDouble() - 2,
                        y2 / scale.toDouble() + 2, PageVisualKind.DIAGRAM, caption.angle)
                }
            }
        }

        private fun addEquations(measured: PdfPageText) {
            // PDFBox may append a superscript or a large operator to the preceding prose
            // line. Use letter boxes for the prose boundaries and the original painted ink
            // between them for the equation, without splitting the expression at a baseline.
            // A CFF operator can extend below its baseline. Measure its actual outline.
            // Keep the ordinary positions for other glyphs, including rotated labels
            // elsewhere on the page. Every glyph hidden by an equation needs ink coverage.
            if (measured.unit.text != page.text || measured.frame != source.frame) return
            val inkByRange = measured.unit.spans.groupBy { it.range }
            val equationGlyphs = glyphs.map { inkByRange[it.range]?.singleOrNull() ?: it }
            val geometry = source.copy(unit = source.unit.copy(spans = equationGlyphs))
            val prose = lines.filter { it.angle == 0 && words.findAll(it.text).take(3).count() == 3 }
            if (prose.size < 2) return
            val bodyLines = prose.filter { it.right - it.x >= w * .3 && words.findAll(it.text).take(5).count() == 5 }
                .ifEmpty { prose }
            val bodySize = bodyLines.map { it.size }.sorted()[bodyLines.size / 2]
            val paragraphLeft = bodyLines.map { it.x }.sorted()[bodyLines.size / 2]
            // Printed equation numbers sit to the right of the expression with an
            // actual gap. A parenthetical citation inside a line of prose fails this test.
            val labels = equationNumber.findAll(page.text).mapNotNull { match ->
                val boxes = equationGlyphs.filter { it.range.startUtf16 in match.range }.flatMap { it.geometry.boxes }
                val left = boxes.minOfOrNull { it.x } ?: return@mapNotNull null
                if (left < .65) return@mapNotNull null
                val middle = (boxes.minOf { it.y } + boxes.maxOf { it.y + it.height }) / 2
                val neighbor = equationGlyphs.any { glyph ->
                    glyph.range.startUtf16 !in match.range && page.text.substring(glyph.range.startUtf16, glyph.range.endUtf16).isNotBlank() &&
                        glyph.geometry.boxes.any { it.y <= middle && it.y + it.height >= middle && it.x + it.width > left - 24 / w }
                }
                if (neighbor) null else match.range to middle * h
            }.toList()
            val fences = prose.filter { it.size >= bodySize * .8 && it.x <= paragraphLeft + 2.8 * bodySize }.mapNotNull { line ->
                val wordRanges = words.findAll(page.text.substring(line.start, line.end)).map {
                    (line.start + it.range.first)..(line.start + it.range.last)
                }.toList()
                if (wordRanges.size < 3) return@mapNotNull null
                val boxes = equationGlyphs.asSequence().filter { g -> g.range.startUtf16 in line.start until line.end &&
                    wordRanges.any { g.range.startUtf16 in it } }.flatMap { it.geometry.boxes.asSequence() }.toList()
                if (boxes.isEmpty()) null else boxes.minOf { it.y } * h to boxes.maxOf { it.y + it.height } * h
            }.sortedBy { it.first }
            for ((before, after) in fences.zipWithNext()) {
                if (blocks.size >= 16 || after.first - before.second !in 18.0..120.0) continue
                val hasOperator = equationGlyphs.any { glyph ->
                    val box = glyph.geometry.boxes.first()
                    (box.y + box.height / 2) * h in before.second..after.first &&
                        hasLimits(page.text.substring(glyph.range.startUtf16, glyph.range.endUtf16))
                }
                val label = labels.singleOrNull { it.second in before.second..after.first }
                if (!hasOperator && label == null) continue
                val image = raster.value
                val above = gutter(image, before.second, before.second + 12, scale, forward = true) ?: continue
                val below = gutter(image, after.first - 12, after.first, scale, forward = false) ?: continue
                val members = equationGlyphs.filter { glyph -> glyph.geometry.boxes.all {
                    val center = (it.y + it.height / 2) * h
                    center > above && center < below
                } }
                if (members.isEmpty()) continue
                val expected = ReaderTextRange(members.first().range.startUtf16, members.last().range.endUtf16)
                if (measured.unmappedRanges.any { it.startUtf16 < expected.endUtf16 && it.endUtf16 > expected.startUtf16 }) continue
                val expression = page.text.substring(expected.startUtf16, expected.endUtf16)
                if ('=' !in expression) continue
                if (label == null) {
                    if (!hasLimits(expression) || words.containsMatchIn(expression)) continue
                } else {
                    if (label.first.first < expected.startUtf16 || label.first.last >= expected.endUtf16) continue
                    // An indented display can contain named variables, brace labels and
                    // limits at several font sizes. Reject paragraph-aligned content or a
                    // run of prose between the two independently located body lines.
                    if (members.minOf { g -> g.geometry.boxes.minOf { it.x } } * w <= paragraphLeft + bodySize ||
                        sentence.containsMatchIn(expression)) continue
                }
                add(expected, above, below, PageVisualKind.FORMULA, geometry = geometry)
            }
        }
    }

    private fun ink(rgb: Int) = (rgb shr 16 and 255) < 245 || (rgb shr 8 and 255) < 245 || (rgb and 255) < 245
    /** Include axes painted inside a PDF form or bitmap as well as stroked PDF paths. */
    private fun hasChartAxes(image: BufferedImage, top: Int, bottom: Int, scale: Float): Boolean {
        val minimum = (24 * scale).roundToInt()
        val vertical = IntArray(image.width)
        var across = false
        var down = false
        for (y in top..bottom) {
            var run = 0
            for (x in vertical.indices) {
                if (ink(image.getRGB(x, y))) {
                    run++
                    vertical[x]++
                    if (run >= minimum) across = true
                    if (vertical[x] >= minimum) down = true
                    if (across && down) return true
                } else {
                    run = 0
                    vertical[x] = 0
                }
            }
        }
        return false
    }
    private fun gutter(image: BufferedImage, from: Double, to: Double, scale: Float, forward: Boolean): Double? {
        val rows = ceil(from * scale).toInt().coerceAtLeast(0)..floor(to * scale).toInt().coerceAtMost(image.height - 1)
        var clear = 0
        for (y in if (forward) rows else rows.reversed()) {
            clear = if ((0 until image.width).none { ink(image.getRGB(it, y)) }) clear + 1 else 0
            if (clear >= ceil(scale * 1.5).toInt()) return y / scale.toDouble()
        }
        return null
    }
    private fun whiteEdge(image: BufferedImage, start: Int, step: Int, maxDistance: Int): Int? {
        for (n in 0..maxDistance) {
            val y = start + n * step
            if (y !in 0 until image.height) return null
            if ((0 until image.width).none { ink(image.getRGB(it, y)) }) return y
        }
        return null
    }
    private fun turnUpright(src: BufferedImage, angle: Int): BufferedImage {
        val sideways = angle != 180
        val target = BufferedImage(if (sideways) src.height else src.width,
            if (sideways) src.width else src.height, BufferedImage.TYPE_INT_RGB)
        val g = target.createGraphics()
        try {
            when (angle) {
                90 -> { g.translate(target.width, 0); g.rotate(Math.PI / 2) }
                270 -> { g.translate(0, target.height); g.rotate(-Math.PI / 2) }
                else -> { g.translate(target.width, target.height); g.rotate(Math.PI) }
            }
            g.drawImage(src, 0, 0, null)
        } finally { g.dispose() }
        return target
    }
}
