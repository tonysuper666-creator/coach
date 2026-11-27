package com.coach.desktop.books

import org.jsoup.Jsoup
import kotlin.math.abs

/** Tesseract can include a narrow side note as the last few words of several justified lines. */
internal object OcrMarginalNotes {
    private data class Word(val text: String, val left: Int, val right: Int)
    private val box = Regex("""\bbbox (\d+) \d+ (\d+) \d+""")

    fun separate(text: String, hocr: String, width: Int): String {
        val lines = Jsoup.parse(hocr).select(".ocr_line, .ocrx_line").map { line ->
            line.select(".ocrx_word").mapNotNull { element ->
                val bounds = box.find(element.attr("title")) ?: return@mapNotNull null
                Word(element.text(), bounds.groupValues[1].toInt(), bounds.groupValues[2].toInt())
            }
        }
        val prose = lines.filter { it.size >= 5 && it.last().right - it.first().left >= width * 0.45 }
        if (prose.size < 8) return text
        val left = prose.map { it.first().left }.sorted()[prose.size / 2]
        val right = prose.map { it.last().right }.sorted()[prose.size / 2]
        val tolerance = (right - left) / 65
        if (prose.count { abs(it.first().left - left) <= tolerance && abs(it.last().right - right) <= tolerance } <
            prose.size / 2) return text

        val original = text.lines().toMutableList()
        val occupied = original.indices.filter { original[it].isNotBlank() }
        val recognized = lines.filter { it.isNotEmpty() }
        if (recognized.size != occupied.size) return text
        data class Split(val index: Int, val body: String, val note: String, val onLeft: Boolean)
        val splits = mutableListOf<Split>()
        for ((index, words) in recognized.withIndex()) {
            if (words.size < 4) continue
            val leading = words.takeWhile { it.right <= left + tolerance / 3 }.size
            val trailing = words.takeLastWhile { it.left >= right - tolerance / 3 }.size
            val onLeft = leading > 0 && trailing == 0 && abs(words[leading.coerceAtMost(words.lastIndex)].left - left) <= tolerance
            val onRight = trailing > 0 && leading == 0 && abs(words[(words.lastIndex - trailing).coerceAtLeast(0)].right - right) <= tolerance
            if (!onLeft && !onRight) continue
            val cut = if (onLeft) leading else words.size - trailing
            if (cut !in 1 until words.size) continue
            val line = original[occupied[index]]
            val first = words.take(cut).joinToString("") { it.text }.filterNot(Char::isWhitespace)
            val full = words.joinToString("") { it.text }.filterNot(Char::isWhitespace)
            if (line.filterNot(Char::isWhitespace) != full || first.isEmpty()) continue
            val offset = line.indices.filter { !line[it].isWhitespace() }[first.length - 1] + 1
            val before = line.take(offset).trimEnd()
            val after = line.drop(offset).trimStart()
            splits += if (onLeft) Split(index, after, before, true) else Split(index, before, after, false)
        }
        val notes = mutableListOf<String>()
        var start = 0
        while (start < splits.size) {
            var end = start + 1
            while (end < splits.size && splits[end].index == splits[end - 1].index + 1 &&
                splits[end].onLeft == splits[start].onLeft) end++
            if (end - start >= 2) {
                val group = splits.subList(start, end)
                group.forEach { original[occupied[it.index]] = it.body }
                notes += group.joinToString(" ") { it.note }
            }
            start = end
        }
        return if (notes.isEmpty()) text else original.joinToString("\n").trimEnd() + "\n\n" + notes.joinToString("\n\n")
    }
}
