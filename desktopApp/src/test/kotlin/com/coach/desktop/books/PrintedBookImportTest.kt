package com.coach.desktop.books

import org.junit.Assert.assertEquals
import org.junit.Test

class PrintedBookImportTest {
    @Test fun numberedChildrenInPrefaceAndAnswerKeyStayOutsideTheChapterList() {
        val outline = listOf("Preface" to 1, "1. Authors" to 2, "Chapter 1. Weather" to 1,
            "1.1 Clouds" to 2, "Chapter 2. Water" to 1, "Solutions" to 1, "Chapter 1" to 2)
            .mapIndexed { i, (title, level) -> BookOutlineItem(title, level, i + 1, HeadingSource.PDF_OUTLINE) }
        val nodes = BookTocWaterfall.buildTier0Structure("id", "Earth", 7, outline).tocNodes
        assertEquals(listOf(3, 5), nodes.filter { it.kind == TocNodeKind.CHAPTER }.map { it.startPage })
        assertEquals(TocNodeKind.FRONT_MATTER, nodes[1].kind)
        assertEquals(TocNodeKind.BACK_MATTER, nodes.last().kind)
        assertEquals(nodes[2].id, nodes[3].parentId)
    }

    @Test fun printedChapterLabelsMustBeOnBodyPages() {
        val pages = listOf(
            BookPage(1, "iv     Contents.\nCHAPTER I.\nHarbour\nCHAPTER II.\nRivers", ocr = true),
            BookPage(2, "CHAPTER I.\nHarbour\nShips arrived in winter.\nTrade continued.", ocr = true),
            BookPage(3, "Third sort\nFishing fleets sailed.\nSeveral boats remained.\nLater that spring.", ocr = true),
            BookPage(4, "CHAPTER II.\nRivers\nMany towns had ferries.\nThe boats were small.", ocr = true),
        )
        val structure = BookStructurePlanner.plan("id", "Ports", "pdf", pages)
        assertEquals(listOf(2, 4), structure.tocNodes.map { it.startPage })
        assertEquals(BookPageRole.TOC, structure.pageRoles[1])
    }

    @Test fun contentsListingMinorHeadingsCannotOverridePhysicalChapterOpenings() {
        val titles = listOf("CHAPTER I.", "Northern ports", "Eastern ports", "Inland ports", "Harbour dues", "CHAPTER II.")
        val contents = titles.mapIndexed { i, title -> "$title .... " + (i + 1) }.joinToString("\n")
        val prose = "The main channel is deep enough for a laden vessel to enter safely. ".repeat(4)
        val pages = listOf(BookPage(1, "Contents\n$contents", ocr = true)) + titles.mapIndexed { i, title ->
            BookPage(i + 2, "$title\nShips and trade\n$prose\n$prose", ocr = true)
        }
        val nodes = BookStructurePlanner.plan("id", "Ports", "pdf", pages).tocNodes
        assertEquals(listOf(2, 7), nodes.filter { it.kind == TocNodeKind.CHAPTER }.map { it.startPage })
    }

    @Test fun recurringOpeningLayoutRecoversAnIllegibleLabelBetweenChapters() {
        val labels = listOf("Printer", "Chapter I.", "Illegible", "Chapter III.", "Index")
        val pages = labels.indices.map { BookPage(it + 1, "Some text on this page.") }
        val rows = labels.mapIndexed { i, label ->
            val lines = listOf("Page header", label, "TITLE " + ('A' + i), "A paragraph begins here.")
            val heights = listOf(0.1, 0.20, 0.24, 0.27)
            i + 1 to lines.mapIndexed { j, text -> PageLineTypography(i + 1, text, text, 10.0, false, heights[j]) }
        }.toMap()
        assertEquals(listOf(2, 3, 4), PrintedChapterOpenings.detect(pages, rows).map { it.page })
    }

    @Test fun marginalWordsMoveTogetherWithoutChangingBodyWords() {
        val raw = List(12) { i -> "One two three four five" + when (i) {
            3 -> " Margin"
            4 -> " note"
            else -> ""
        } }
        val hocr = raw.joinToString("") { line ->
            "<span class='ocr_line'>" + line.split(" ").mapIndexed { i, word ->
                val left = 100 + i * 140
                val right = left + 120
                "<span class='ocrx_word' title='bbox $left 0 $right 20'>$word</span>"
            }.joinToString("") + "</span>"
        }
        val expected = List(12) { "One two three four five" }.joinToString("\n") + "\n\nMargin note"
        assertEquals(expected, OcrMarginalNotes.separate(raw.joinToString("\n"), hocr, 1000))
    }
}
