package com.coach.desktop

import com.coach.client.RenderedMath
import com.coach.domain.math.MathDelimiters
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO

/** A4 at 144 dpi. Raster pages preserve the exact Compose formula painter and CJK fonts.
 * The accompanying Markdown remains searchable/editable. No browser, printer, or remote PDF service.
 */
data class NotesPdfResult(val bytes: ByteArray, val unresolved: Int)

suspend fun notesPdf(markdown: String, fetch: suspend (String, Boolean) -> RenderedMath?): NotesPdfResult {
    val layout = NotesPageLayout()
    val cache = mutableMapOf<Pair<String, Boolean>, BufferedImage>()
    var unresolved = 0
    for (segment in MathDelimiters.tokenize(markdown).segments) {
        val tex = segment.math
        if (tex == null) layout.text(segment.text.orEmpty())
        else {
            val key = tex to segment.display
            val image = cache[key] ?: fetch(tex, segment.display)?.let { asset ->
                ImageIO.read(ByteArrayInputStream(rasterizeFormula(asset.sha, asset.svg, 64f))).also { cache[key] = it }
            }
            if (image == null || !layout.formula(image, segment.display)) {
                unresolved++
                layout.text("\n【公式未能清晰绘制，保留原始 TeX】\n$tex\n")
            }
        }
    }
    return NotesPdfResult(imagePdf(layout.finish()), unresolved)
}

internal class NotesPageLayout {
    private val width = 1190
    private val height = 1684
    private val margin = 85
    private val body = width - 2 * margin
    private var page = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    private var g: Graphics2D = page.createGraphics()
    private var y = 100
    private var x = 0
    private var lineHeight = 0
    private val atoms = mutableListOf<Triple<Any, Int, Int>>()
    private val pages = mutableListOf<ByteArray>()
    private var font = Font("SansSerif", Font.PLAIN, 22)
    init { startPage() }

    private fun startPage() {
        g.color = Color.WHITE; g.fillRect(0, 0, width, height)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.color = Color(62, 109, 114); g.font = Font("SansSerif", Font.PLAIN, 18)
        g.drawString("COACH  /  我的复习提纲", margin, 57)
        g.color = Color(217, 228, 230); g.drawLine(margin, 70, width - margin, 70)
    }

    fun text(value: String) {
        value.split('\n').forEachIndexed { index, raw ->
            if (index > 0) flush(blank = true)
            val heading = if (x == 0) Regex("^(#{1,6})\\s+").find(raw) else null
            font = Font("SansSerif", if (heading != null) Font.BOLD else Font.PLAIN,
                when (heading?.groupValues?.get(1)?.length) { 1 -> 34; 2 -> 29; 3 -> 25; else -> 22 })
            val clean = raw.removePrefix(heading?.value.orEmpty()).replace("**", "").replace("`", "")
            g.font = font
            val metrics = g.fontMetrics
            // Keep a section heading with the following content on the printed page.
            if (heading != null && y + metrics.height + 125 > height - 90) nextPage()
            val iter = clean.codePoints().iterator()
            while (iter.hasNext()) {
                val letter = String(Character.toChars(iter.nextInt()))
                val w = metrics.stringWidth(letter)
                if (x + w > body) flush()
                atoms += Triple(letter to font, w, metrics.height)
                x += w; lineHeight = maxOf(lineHeight, metrics.height)
            }
        }
    }

    fun formula(image: BufferedImage, display: Boolean): Boolean {
        val natural = if (display) 0.62 else 0.43 // source em 64; print em 40/28
        val scale = minOf(natural, body.toDouble() / image.width, (height - 240.0) / image.height)
        if (scale < 0.18) return false
        if (display) flush()
        val w = (image.width * scale).toInt().coerceAtLeast(1)
        val h = (image.height * scale).toInt().coerceAtLeast(1)
        if (x + w > body) flush()
        if (display) x = (body - w) / 2
        atoms += Triple(image, w, h); x += w + 6; lineHeight = maxOf(lineHeight, h + 6)
        if (display) { flush(); y += 10 }
        return true
    }

    private fun flush(blank: Boolean = false) {
        if (atoms.isEmpty()) { if (blank) y += 13; return }
        if (y + lineHeight > height - 90) nextPage()
        var left = if (atoms.size == 1 && atoms[0].first is BufferedImage) maxOf(0, (body - atoms[0].second) / 2) else 0
        g.color = Color(24, 43, 51)
        for ((item, w, h) in atoms) {
            if (item is BufferedImage) g.drawImage(item, margin + left, y + (lineHeight - h) / 2, w, h, null)
            else {
                @Suppress("UNCHECKED_CAST") val (letter, face) = item as Pair<String, Font>
                g.font = face
                g.drawString(letter, margin + left, y + (lineHeight - g.fontMetrics.height) / 2 + g.fontMetrics.ascent)
            }
            left += w + if (item is BufferedImage) 6 else 0
        }
        y += lineHeight + 7; atoms.clear(); x = 0; lineHeight = 0
    }

    private fun nextPage() {
        endPage(); page = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        g = page.createGraphics(); y = 100; startPage()
    }

    private fun endPage() {
        check(pages.size < 100) { "导出超过 100 页，请按知识点分别导出" }
        g.color = Color(85, 100, 106); g.font = Font("SansSerif", Font.PLAIN, 18)
        g.drawString("私人学习资料 · 仅保存在本机", margin, height - 45)
        g.drawString("${pages.size + 1}", width - margin - 30, height - 45); g.dispose()
        val bytes = ByteArrayOutputStream()
        DeflaterOutputStream(bytes).use { zipped ->
            val row = ByteArray(width * 3)
            for (py in 0 until height) {
                for (px in 0 until width) { val rgb = page.getRGB(px, py); row[px * 3] = (rgb shr 16).toByte(); row[px * 3 + 1] = (rgb shr 8).toByte(); row[px * 3 + 2] = rgb.toByte() }
                zipped.write(row)
            }
        }
        pages += bytes.toByteArray()
    }

    fun finish(): List<ByteArray> { flush(); endPage(); return pages }
}

/** Minimal PDF 1.4: lossless RGB page images, explicit xref offsets and A4 media boxes. */
internal fun imagePdf(pages: List<ByteArray>): ByteArray {
    require(pages.isNotEmpty())
    val output = ByteArrayOutputStream()
    fun write(s: String) { output.write(s.toByteArray(Charsets.ISO_8859_1)) }
    val offsets = mutableListOf(0)
    fun obj(id: Int, content: () -> Unit) { offsets += output.size(); write("$id 0 obj\n"); content(); write("\nendobj\n") }
    write("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n")
    obj(1) { write("<< /Type /Catalog /Pages 2 0 R >>") }
    obj(2) { write("<< /Type /Pages /Count ${pages.size} /Kids [${pages.indices.joinToString(" ") { "${3 + it * 3} 0 R" }}] >>") }
    pages.forEachIndexed { index, pixels ->
        val id = 3 + index * 3
        obj(id) { write("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << /PageImage ${id + 1} 0 R >> >> /Contents ${id + 2} 0 R >>") }
        obj(id + 1) { write("<< /Type /XObject /Subtype /Image /Width 1190 /Height 1684 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length ${pixels.size} >>\nstream\n"); output.write(pixels); write("\nendstream") }
        val commands = "q\n595 0 0 842 0 0 cm\n/PageImage Do\nQ\n"
        obj(id + 2) { write("<< /Length ${commands.length} >>\nstream\n${commands}endstream") }
    }
    val start = output.size()
    write("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
    offsets.drop(1).forEach { write("%010d 00000 n \n".format(java.util.Locale.ROOT, it)) }
    write("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$start\n%%EOF\n")
    return output.toByteArray()
}
