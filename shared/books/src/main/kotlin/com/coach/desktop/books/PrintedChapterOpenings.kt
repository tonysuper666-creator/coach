package com.coach.desktop.books

import kotlin.math.abs

/** Printed chapter openings have an isolated chapter label, then its title and prose. */
internal object PrintedChapterOpenings {
    private val marker = Regex("""(?i)^chap(?:ter|\.)\s*([ivxlcdm]+|\d{1,3})[.,:]?$""")
    private val contents = Regex("""(?i)^[\W\divxlcm]*(?:table\s+of\s+)?contents[\W\d ivxlcm]*$""")
    private val pageReferences = Regex("""(?i)\bpp?\.\s*\d+|\.{3,}\s*\d+""")
    private data class Opening(val page: Int, val title: String, val strict: Boolean, val chapterLike: Boolean, val y: Double?)

    fun isContents(lines: List<String>): Boolean =
        lines.take(4).any { contents.matches(it.trim()) } ||
            lines.count { marker.matches(it.trim()) } >= 2 && lines.count { pageReferences.containsMatchIn(it) } >= 2 ||
            lines.take(10).any { it.trim().equals("Page.", true) } &&
            lines.count { it.trimEnd().lastOrNull()?.isDigit() == true } >= 4

    fun detect(pages: List<BookPage>, typography: Map<Int, List<PageLineTypography>>): List<BookOutlineItem> {
        val candidates = mutableListOf<Opening>()
        for (page in pages) {
            val rows = typography[page.number].orEmpty().filter { it.rowText.isNotBlank() }
            val lines = if (rows.isEmpty()) page.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
                else rows.map { it.rowText.trim() }
            if (lines.size < 4 || isContents(lines)) continue
            for ((i, label) in lines.withIndex()) {
                val heading = lines.getOrNull(i + 1) ?: continue
                val cleanLabel = label.trimStart { it.isWhitespace() || it in "\u2018\u2019\u201c\u201d'" }
                val ordinal = marker.matchEntire(cleanLabel)?.groupValues?.get(1)
                val strict = ordinal != null && cleanLabel.first().isUpperCase() &&
                    (page.ocr || ordinal.first().isLetter())
                // Some scan text layers misread the large type. Recover only in the same opening
                // layout as legible labels elsewhere, with a second, distinct display-title row.
                val gapBefore = if (i > 0 && rows.isNotEmpty()) rows[i].yNorm - rows[i - 1].yNorm else 0.0
                val gapAfter = if (rows.isNotEmpty()) rows[i + 1].yNorm - rows[i].yNorm else 0.0
                val display = i in 1..4 && gapBefore >= 0.065 && gapAfter >= 0.025 &&
                    label.length in 3..23 && heading.length in 4..85 &&
                    heading.count(Char::isUpperCase) > heading.count(Char::isLowerCase)
                if (!strict && !display) continue
                // A running header may also consist of just the chapter label.
                if (i == 0 && rows.size > 1 && rows[1].yNorm - rows[0].yNorm < 0.035) continue
                val title = if (display) heading else cleanLabel
                candidates += Opening(page.number, title, strict, label.startsWith("c", true), rows.getOrNull(i)?.yNorm)
                break
            }
        }
        val anchors = candidates.filter { it.strict }
        if (anchors.size < 2 || anchors.map { it.title.lowercase() }.distinct().size < 2) return emptyList()
        val layouts = anchors.mapNotNull { it.y }
        fun matchesLayout(opening: Opening): Boolean =
            opening.y != null && layouts.count { abs(it - opening.y) < 0.04 } >= 2
        val legible = candidates.filter { it.strict || it.chapterLike && matchesLayout(it) }
        val extent = legible.first().page..legible.last().page
        val accepted = candidates.filter { it.strict || it.page in extent && matchesLayout(it) }
        return accepted.map { BookOutlineItem(it.title, 1, it.page, HeadingSource.HEADING_RULE) }
    }
}
