package com.coach.desktop.books

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.util.Matrix
import org.apache.pdfbox.util.Vector
import java.awt.geom.Point2D
import kotlin.math.abs
import kotlin.math.roundToInt

/** Evidence for a printed table with segmented side rules and a ruled first row.
 * Coordinates are in points from the CropBox top left. Only solid, visible simple strokes count. */
internal class PdfTableFrames(page: PDPage, private val control: BookImportControl) : PDFGraphicsStreamEngine(page) {
    data class Frame(val left: Float, val right: Float, val boundaries: List<Float>)
    private data class Rule(val fixed: Float, val start: Float, val end: Float, val horizontal: Boolean)
    private data class FontSample(val x: Float, val y: Float, val size: Int)
    private val fonts = mutableListOf<FontSample>()
    private val rules = mutableListOf<Rule>()
    private val pending = mutableListOf<Rule>()
    private val box = page.cropBox
    private var point = Point2D.Float()
    private var usablePath = true
    private var unsupported = false
    private var operations = 0

    private fun tick() {
        control.check()
        require(++operations <= 100_000)
    }

    override fun moveTo(x: Float, y: Float) {
        tick()
        point = Point2D.Float(x, y)
    }

    override fun lineTo(x: Float, y: Float) {
        tick()
        val x0 = point.x - box.lowerLeftX
        val x1 = x - box.lowerLeftX
        val y0 = box.upperRightY - point.y
        val y1 = box.upperRightY - y
        if (abs(y0 - y1) < .01f && abs(x0 - x1) >= 24)
            pending += Rule(y0, minOf(x0, x1), maxOf(x0, x1), true)
        else if (abs(x0 - x1) < .01f && abs(y0 - y1) >= 2)
            pending += Rule(x0, minOf(y0, y1), maxOf(y0, y1), false)
        else usablePath = false
        point = Point2D.Float(x, y)
    }

    override fun strokePath() {
        val state = graphicsState
        val width = state.lineWidth * maxOf(state.currentTransformationMatrix.scalingFactorX, state.currentTransformationMatrix.scalingFactorY)
        if (usablePath && width in 0f..2f && state.softMask == null && state.alphaConstant == 1.0 &&
            state.lineDashPattern.dashArray.isEmpty() && state.strokingColor.toRGB() and 0xffffff != 0xffffff) {
            require(rules.size + pending.size <= 4096)
            rules += pending
        }
        endPath()
    }
    override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
        tick()
        if (graphicsState.textState.renderingMode.isFill && !font.isVertical) {
            val m = textRenderingMatrix
            if (m.shearX == 0f && m.shearY == 0f && m.scaleY in 1f..128f)
                fonts += FontSample(m.translateX - box.lowerLeftX, box.upperRightY - m.translateY, (m.scaleY * 100).roundToInt())
        }
    }
    fun fontSizes(frame: Frame): Pair<Float, Float>? {
        val body = mutableMapOf<Int, Int>()
        val table = mutableMapOf<Int, Int>()
        for (s in fonts) {
            val counts = if (s.x in frame.left..frame.right && s.y in frame.boundaries.first()..frame.boundaries.last()) table else body
            counts[s.size] = counts.getOrDefault(s.size, 0) + 1
        }
        return (body.maxByOrNull { it.value }?.key ?: return null) / 100f to
            (table.maxByOrNull { it.value }?.key ?: return null) / 100f
    }

    fun frames(): List<Frame> {
        if (unsupported) return emptyList()
        val horizontal = rules.filter { it.horizontal }.distinct().sortedBy { it.fixed }
        if (horizontal.size > 64) return emptyList()
        val result = mutableListOf<Frame>()
        for (top in horizontal) {
            val below = horizontal.filter { it.fixed > top.fixed && abs(it.start - top.start) < .8 && abs(it.end - top.end) < .8 }
            // Only the printed header is separated by a full-width rule in this table style.
            val header = below.getOrNull(0) ?: continue
            val bottom = below.getOrNull(1) ?: continue
            fun side(x: Float) = rules.filter { !it.horizontal && abs(it.fixed - x) < .8 &&
                it.start >= top.fixed - .8 && it.end <= bottom.fixed + .8 }.sortedBy { it.start }
            val left = side(top.start)
            val right = side(top.end)
            if (left.size !in 3..ImportedTable.MAX_ROWS || left.size != right.size ||
                abs(left.first().start - top.fixed) > .8 || abs(left.first().end - header.fixed) > .8 ||
                abs(left.last().end - bottom.fixed) > .8 ||
                left.zipWithNext().any { (a, b) -> abs(a.end - b.start) > .8 } ||
                left.zip(right).any { (a, b) -> abs(a.start - b.start) > .8 || abs(a.end - b.end) > .8 }) continue
            result += Frame(top.start, top.end, listOf(top.fixed, header.fixed) +
                left.drop(1).dropLast(1).map { it.end } + bottom.fixed)
        }
        return result
    }
    override fun getCurrentPoint(): Point2D = point
    override fun endPath() {
        pending.clear()
        usablePath = true
    }
    override fun fillPath(windingRule: Int) = endPath()
    override fun fillAndStrokePath(windingRule: Int) = endPath()
    override fun closePath() { usablePath = false }
    override fun appendRectangle(a: Point2D, b: Point2D, c: Point2D, d: Point2D) {
        tick()
        usablePath = false
    }
    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        tick()
        usablePath = false
        point = Point2D.Float(x3, y3)
    }
    override fun clip(windingRule: Int) { unsupported = true }
    override fun shadingFill(shadingName: COSName) { unsupported = true }
    override fun showForm(form: PDFormXObject) { unsupported = true }
    override fun drawImage(image: PDImage) { unsupported = true }
}
