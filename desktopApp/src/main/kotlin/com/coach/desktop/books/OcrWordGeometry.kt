package com.coach.desktop.books

import com.coach.domain.reader.ReaderRect
import org.jsoup.Jsoup

internal object OcrWordGeometry {
    private val bbox = Regex("""\bbbox (\d+) (\d+) (\d+) (\d+)""")

    /** Align physical words against the stored UTF-16 transcript. Never guess across an OCR difference. */
    fun boxes(text: String, hocr: String, width: Int, height: Int, rotation: Int): List<PageTextBox> {
        val offsets = text.indices.filter { !text[it].isWhitespace() }
        val canonical = buildString { offsets.forEach { append(text[it]) } }
        val lines = Jsoup.parse(hocr).select(".ocr_line, .ocrx_line, .ocr_header, .ocr_caption, .ocr_textfloat")
            .map { line -> line.select(".ocrx_word").map { word ->
                word.text().filterNot(Char::isWhitespace) to bbox.find(word.attr("title"))
            } }
        val lineTexts = lines.map { line -> line.joinToString("") { it.first } }
        val exact = lineTexts.joinToString("") == canonical
        val counts = if (exact) emptyMap() else lineTexts.groupingBy { it }.eachCount()
        var position = 0
        return buildList {
            for ((index, line) in lines.withIndex()) {
                val content = lineTexts[index]
                if (content.isEmpty()) continue
                if (!exact) {
                    if (counts[content] != 1) continue
                    position = canonical.indexOf(content)
                    if (position < 0 || canonical.indexOf(content, position + 1) >= 0) continue
                }
                for ((token, match) in line) {
                    val start = position
                    position += token.length
                    if (match == null || token.isEmpty()) continue
                    val (left, top, right, bottom) = match.groupValues.drop(1).map { it.toInt() }
                    if (left !in 0 until right || right > width || top !in 0 until bottom || bottom > height) continue
                    val x = left.toDouble() / width
                    val y = top.toDouble() / height
                    val w = (right - left).toDouble() / width
                    val h = (bottom - top).toDouble() / height
                    // Tesseract sees the rotated raster. Stored boxes use the unrotated CropBox.
                    val rect = when (rotation) {
                        90 -> ReaderRect(y, (width - right).toDouble() / width, h, w)
                        180 -> ReaderRect((width - right).toDouble() / width, (height - bottom).toDouble() / height, w, h)
                        270 -> ReaderRect((height - bottom).toDouble() / height, x, h, w)
                        else -> ReaderRect(x, y, w, h)
                    }
                    add(PageTextBox(offsets[start], offsets[position - 1] + 1, rect))
                }
            }
        }.sortedBy { it.startOffset }
    }
}
