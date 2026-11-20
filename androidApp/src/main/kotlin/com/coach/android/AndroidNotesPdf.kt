package com.coach.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Base64
import androidx.compose.ui.graphics.asAndroidPath
import com.coach.client.RenderedMath
import com.coach.domain.math.MathDelimiters
import com.coach.ui.math.MathSvgDocument
import com.coach.ui.math.SvgNode
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.DeflaterOutputStream
import kotlin.math.ceil

internal data class AndroidNotesPdfResult(
    val bytes: ByteArray,
    val pageCount: Int,
    val unresolvedFormulas: Int = 0,
    val unresolvedImages: Int = 0,
)

private val imageMarkdownRegex = Regex("!\\[([^]\\n]*)]\\(([^)\\s]+)\\)")

internal suspend fun renderAndroidNotesPdf(
    markdown: String,
    fetchMath: suspend (String, Boolean) -> RenderedMath? = { _, _ -> null },
): AndroidNotesPdfResult {
    val layout = AndroidNotesPageLayout()
    val formulaCache = HashMap<Pair<String, Boolean>, Bitmap>()
    var unresolvedFormulas = 0
    var unresolvedImages = 0
    try {
        fun addPicture(alt: String, source: String) {
            val bitmap = decodeInlineAndroidNotesImage(source)
            if (bitmap == null) {
                unresolvedImages++
                layout.text("\n【图片未嵌入，原引用保留在 Markdown】$alt\n")
            } else {
                try {
                    layout.picture(bitmap)
                } finally {
                    bitmap.recycle()
                }
                if (alt.isNotBlank()) layout.text("\n$alt\n")
            }
        }

        suspend fun addTextWithMath(segmentText: String) {
            for (segment in MathDelimiters.tokenize(segmentText).segments) {
                val tex = segment.math
                if (tex == null) {
                    layout.text(segment.text.orEmpty())
                } else {
                    val key = tex to segment.display
                    val bitmap = formulaCache[key] ?: fetchMath(tex, segment.display)?.let { asset ->
                        rasterizeAndroidFormula(asset.sha, asset.svg, 64f)?.also { formulaCache[key] = it }
                    }
                    if (bitmap == null || !layout.formula(bitmap, segment.display)) {
                        unresolvedFormulas++
                        layout.text("\n【公式未能清晰绘制，保留原始 TeX】\n$tex\n")
                    }
                }
            }
        }

        var cursor = 0
        for (match in imageMarkdownRegex.findAll(markdown)) {
            addTextWithMath(markdown.substring(cursor, match.range.first))
            addPicture(match.groupValues[1], match.groupValues[2])
            cursor = match.range.last + 1
        }
        addTextWithMath(markdown.substring(cursor))
        val pages = layout.finish()
        return AndroidNotesPdfResult(
            bytes = encodeAndroidImagePdf(pages),
            pageCount = pages.size,
            unresolvedFormulas = unresolvedFormulas,
            unresolvedImages = unresolvedImages,
        )
    } finally {
        formulaCache.values.forEach { if (!it.isRecycled) it.recycle() }
        layout.dispose()
    }
}

/** Inline PNG/JPEG only; inspect bounds before decoding. External files and URLs are refused. */
internal fun decodeInlineAndroidNotesImage(source: String): Bitmap? = runCatching {
    if (source.length > 2_800_000) return@runCatching null
    val prefix = listOf("data:image/png;base64,", "data:image/jpeg;base64,", "data:image/jpg;base64,")
        .firstOrNull { source.startsWith(it) } ?: return@runCatching null
    val raw = Base64.decode(source.removePrefix(prefix), Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    if (bounds.outWidth !in 1..16000 || bounds.outHeight !in 1..16000) return@runCatching null
    if (bounds.outWidth.toLong() * bounds.outHeight > 16_000_000L) return@runCatching null
    BitmapFactory.decodeByteArray(raw, 0, raw.size)
}.getOrNull()

/** Render a parsed MathJax SVG document directly onto Android Canvas at [em] pixels per em. */
internal fun rasterizeAndroidFormula(sha: String, svg: String, em: Float = 64f): Bitmap? = runCatching {
    val doc = MathSvgDocument.parse(sha, svg)
    val width = ceil(doc.widthEm * em).toInt().coerceAtLeast(1)
    val height = ceil(doc.box[3] / 1000f * em).toInt().coerceAtLeast(1)
    require(width <= 16000 && height <= 16000 && width.toLong() * height <= 32_000_000L) {
        "公式过大，请缩小导出范围"
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val scale = em / 1000f
    canvas.save()
    canvas.translate(-doc.box[0] * scale, -doc.box[1] * scale)
    canvas.scale(scale, scale)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    drawAndroidSvgNode(canvas, paint, doc.root, Color.BLACK, root = true)
    canvas.restore()
    bitmap
}.getOrNull()

private fun drawAndroidSvgNode(canvas: Canvas, paint: Paint, node: SvgNode, inheritedColor: Int, root: Boolean = false) {
    canvas.save()
    for ((kind, args) in node.transforms()) {
        if (kind == "translate") canvas.translate(args[0], args.getOrElse(1) { 0f })
        else canvas.scale(args[0], args.getOrElse(1) { args[0] })
    }
    fun num(key: String, default: Float = 0f) = node.attrs[key]?.removeSuffix("px")?.toFloatOrNull() ?: default
    if (node.tag == "svg" && !root) {
        canvas.translate(num("x"), num("y"))
        val view = node.attrs["viewBox"]?.let { com.coach.ui.math.numbers(it) }
        val w = num("width", view?.getOrNull(2) ?: 0f)
        val h = num("height", view?.getOrNull(3) ?: 0f)
        canvas.clipRect(0f, 0f, w, h)
        if (view != null && view.size >= 4 && view[2] > 0f && view[3] > 0f) {
            val sx = w / view[2]
            val sy = h / view[3]
            if (node.attrs["preserveAspectRatio"] == "none") canvas.scale(sx, sy)
            else {
                val s = minOf(sx, sy)
                canvas.translate((w - view[2] * s) / 2f, (h - view[3] * s) / 2f)
                canvas.scale(s, s)
            }
            canvas.translate(-view[0], -view[1])
        }
    }
    val fill = svgColor(node.attrs["fill"], inheritedColor)
    when (node.tag) {
        "path" -> node.path?.let { p ->
            paint.color = fill
            paint.style = Paint.Style.FILL
            canvas.drawPath(p.asAndroidPath(), paint)
        }
        "rect" -> {
            paint.color = fill
            paint.style = Paint.Style.FILL
            val x = num("x")
            val y = num("y")
            canvas.drawRect(x, y, x + num("width"), y + num("height"), paint)
        }
        "text" -> if (node.text.isNotEmpty()) {
            paint.color = fill
            paint.style = Paint.Style.FILL
            paint.typeface = Typeface.SERIF
            paint.textSize = num("font-size", 884f)
            val estimated = node.attrs["textLength"]?.toFloatOrNull()
                ?: (node.text.sumOf { if (it.code > 255) 1.0 else 0.5 }.toFloat() * paint.textSize)
            val measured = paint.measureText(node.text).coerceAtLeast(1f)
            canvas.save()
            canvas.translate(num("x"), num("y"))
            canvas.scale(estimated / measured, 1f)
            canvas.drawText(node.text, 0f, 0f, paint)
            canvas.restore()
        }
    }
    node.children.forEach { drawAndroidSvgNode(canvas, paint, it, fill) }
    canvas.restore()
}

private fun svgColor(raw: String?, inherited: Int): Int = when {
    raw == null || raw == "currentColor" -> inherited
    raw == "none" -> Color.TRANSPARENT
    raw.startsWith('#') -> {
        val hex = raw.drop(1).let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
        (0xFF000000L or hex.toLong(16)).toInt()
    }
    raw == "black" -> Color.BLACK
    raw == "white" -> Color.WHITE
    raw == "red" -> Color.RED
    raw == "blue" -> Color.BLUE
    else -> inherited
}

internal class AndroidNotesPageLayout {
    private val width = 1190
    private val height = 1684
    private val margin = 85
    private val body = width - 2 * margin
    private var page: Bitmap? = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private var canvas: Canvas = Canvas(checkNotNull(page))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var y = 100f
    private var x = 0f
    private var lineHeight = 0f
    private var fontSize = 22f
    private var bold = false
    private val atoms = mutableListOf<PageAtom>()
    private val pages = mutableListOf<ByteArray>()
    private val headingRegex = Regex("^(#{1,6})\\s+")

    private sealed interface PageAtom {
        val width: Float
        val height: Float
        data class Glyph(val text: String, val size: Float, val bold: Boolean, override val width: Float, override val height: Float) : PageAtom
        data class ImageAtom(val bitmap: Bitmap, val ownsCopy: Boolean, override val width: Float, override val height: Float) : PageAtom
    }

    init {
        startPage()
    }

    private fun startPage() {
        canvas.drawColor(Color.WHITE)
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.SANS_SERIF
        paint.textSize = 18f
        paint.color = Color.rgb(62, 109, 114)
        canvas.drawText("COACH  /  我的复习提纲", margin.toFloat(), 57f, paint)
        paint.color = Color.rgb(217, 228, 230)
        paint.strokeWidth = 1.5f
        canvas.drawLine(margin.toFloat(), 70f, (width - margin).toFloat(), 70f, paint)
    }

    fun text(value: String) {
        value.split('\n').forEachIndexed { index, raw ->
            if (index > 0) flush(blank = true)
            val heading = if (x == 0f) headingRegex.find(raw) else null
            fontSize = when (heading?.groupValues?.get(1)?.length) {
                1 -> 34f
                2 -> 29f
                3 -> 25f
                else -> 22f
            }
            bold = heading != null
            val clean = raw.removePrefix(heading?.value.orEmpty()).replace("**", "").replace("`", "")
            paint.typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
            paint.textSize = fontSize
            val fm = paint.fontMetrics
            val glyphHeight = (fm.descent - fm.ascent).coerceAtLeast(fontSize)
            if (heading != null && y + glyphHeight + 125f > height - 90f) nextPage()
            val iter = clean.codePoints().iterator()
            while (iter.hasNext()) {
                val letter = String(Character.toChars(iter.nextInt()))
                val w = paint.measureText(letter)
                if (x + w > body) flush()
                atoms += PageAtom.Glyph(letter, fontSize, bold, w, glyphHeight)
                x += w
                lineHeight = maxOf(lineHeight, glyphHeight)
            }
        }
    }

    fun formula(image: Bitmap, display: Boolean): Boolean {
        val natural = if (display) 0.62 else 0.43
        val scale = minOf(natural, body.toDouble() / image.width, (height - 240.0) / image.height)
        if (scale < 0.18) return false
        if (display) flush()
        val w = (image.width * scale).toFloat().coerceAtLeast(1f)
        val h = (image.height * scale).toFloat().coerceAtLeast(1f)
        if (x + w > body) flush()
        if (display) x = (body - w) / 2f
        atoms += PageAtom.ImageAtom(image, ownsCopy = false, width = w, height = h)
        x += w + 6f
        lineHeight = maxOf(lineHeight, h + 6f)
        if (display) {
            flush()
            y += 10f
        }
        return true
    }

    fun picture(image: Bitmap) {
        flush()
        val scale = minOf(1.0, body.toDouble() / image.width, 700.0 / image.height)
        val w = (image.width * scale).toInt().coerceAtLeast(1)
        val h = (image.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (w == image.width && h == image.height) image.copy(Bitmap.Config.ARGB_8888, false)
        else Bitmap.createScaledBitmap(image, w, h, true)
        atoms += PageAtom.ImageAtom(scaled, ownsCopy = true, width = w.toFloat(), height = h.toFloat())
        lineHeight = h + 12f
        flush()
    }

    private fun flush(blank: Boolean = false) {
        if (atoms.isEmpty()) {
            if (blank) y += 13f
            return
        }
        if (y + lineHeight > height - 90f) nextPage()
        var left = if (atoms.size == 1 && atoms[0] is PageAtom.ImageAtom) {
            maxOf(0f, (body - atoms[0].width) / 2f)
        } else 0f
        paint.color = Color.rgb(24, 43, 51)
        paint.style = Paint.Style.FILL
        for (atom in atoms) {
            when (atom) {
                is PageAtom.ImageAtom -> {
                    val top = y + (lineHeight - atom.height) / 2f
                    canvas.drawBitmap(atom.bitmap, null, RectF(margin + left, top, margin + left + atom.width, top + atom.height), paint)
                    if (atom.ownsCopy && !atom.bitmap.isRecycled) atom.bitmap.recycle()
                    left += atom.width + 6f
                }
                is PageAtom.Glyph -> {
                    paint.typeface = if (atom.bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
                    paint.textSize = atom.size
                    val fm = paint.fontMetrics
                    val textH = fm.descent - fm.ascent
                    val baseline = y + (lineHeight - textH) / 2f - fm.ascent
                    canvas.drawText(atom.text, margin + left, baseline, paint)
                    left += atom.width
                }
            }
        }
        y += lineHeight + 7f
        atoms.clear()
        x = 0f
        lineHeight = 0f
    }

    private fun nextPage() {
        endPage()
        val fresh = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        page = fresh
        canvas = Canvas(fresh)
        y = 100f
        startPage()
    }

    private fun endPage() {
        check(pages.size < 100) { "导出超过 100 页，请按章节或周次分别导出" }
        val bmp = checkNotNull(page)
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.SANS_SERIF
        paint.textSize = 18f
        paint.color = Color.rgb(85, 100, 106)
        canvas.drawText("私人学习资料 · 仅保存在本机", margin.toFloat(), (height - 45).toFloat(), paint)
        canvas.drawText("${pages.size + 1}", (width - margin - 30).toFloat(), (height - 45).toFloat(), paint)
        val compressed = ByteArrayOutputStream()
        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        page = null
        DeflaterOutputStream(compressed).use { zipped ->
            val row = ByteArray(width * 3)
            var idx = 0
            for (py in 0 until height) {
                var off = 0
                for (px in 0 until width) {
                    val rgb = pixels[idx++]
                    row[off++] = (rgb shr 16).toByte()
                    row[off++] = (rgb shr 8).toByte()
                    row[off++] = rgb.toByte()
                }
                zipped.write(row)
            }
        }
        pages += compressed.toByteArray()
    }

    fun finish(): List<ByteArray> {
        flush()
        endPage()
        return pages
    }

    fun dispose() {
        atoms.filterIsInstance<PageAtom.ImageAtom>().forEach { if (it.ownsCopy && !it.bitmap.isRecycled) it.bitmap.recycle() }
        atoms.clear()
        page?.let { if (!it.isRecycled) it.recycle() }
        page = null
    }
}

internal fun encodeAndroidImagePdf(pages: List<ByteArray>): ByteArray {
    require(pages.isNotEmpty())
    val output = ByteArrayOutputStream()
    fun write(s: String) {
        output.write(s.toByteArray(Charsets.ISO_8859_1))
    }
    val offsets = mutableListOf(0)
    fun obj(id: Int, content: () -> Unit) {
        offsets += output.size()
        write("$id 0 obj\n")
        content()
        write("\nendobj\n")
    }
    write("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n")
    obj(1) { write("<< /Type /Catalog /Pages 2 0 R >>") }
    obj(2) { write("<< /Type /Pages /Count ${pages.size} /Kids [${pages.indices.joinToString(" ") { "${3 + it * 3} 0 R" }}] >>") }
    pages.forEachIndexed { index, pixels ->
        val id = 3 + index * 3
        obj(id) {
            write("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << /PageImage ${id + 1} 0 R >> >> /Contents ${id + 2} 0 R >>")
        }
        obj(id + 1) {
            write("<< /Type /XObject /Subtype /Image /Width 1190 /Height 1684 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length ${pixels.size} >>\nstream\n")
            output.write(pixels)
            write("\nendstream")
        }
        val commands = "q\n595 0 0 842 0 0 cm\n/PageImage Do\nQ\n"
        obj(id + 2) { write("<< /Length ${commands.length} >>\nstream\n${commands}endstream") }
    }
    val start = output.size()
    write("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
    offsets.drop(1).forEach { write("%010d 00000 n \n".format(Locale.ROOT, it)) }
    write("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$start\n%%EOF\n")
    return output.toByteArray()
}
