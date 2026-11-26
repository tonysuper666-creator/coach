package com.coach.desktop.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.Path

/** Classifier checks use invented titles only. Real books stay out of this test. */
class StructureAuditTest {
    private fun item(title: String, page: Int, level: Int = 1, offset: Int = 0) =
        BookOutlineItem(title, level, page, HeadingSource.PDF_OUTLINE, offset)

    @Test
    fun explicitChapterKeepsALongTitleAndSkipsDottedSections() {
        val longTitle = "Chapter 1 " + "Opening ".repeat(30)
        val outline = listOf(
            item("Preface", 1),
            item(longTitle, 3),
            item("1.1 Detail of the same topic", 4),
            item("Chapter 2 Later", 8),
        )
        val native = StructureAuditMain.classifyOutline(outline)
        assertEquals("explicit", native.method)
        assertEquals(listOf(3, 8), native.pages)
        assertTrue(StructureAuditMain.looksLikeBodyDump(longTitle))
    }

    @Test
    fun numberedSequenceDropsDottedLinesAndShortRemainders() {
        val outline = listOf(
            item("Contents", 1),
            item("1 Why markets fail here", 5),
            item("1.1 Detail", 6),
            item("2 Next topic starts", 9),
            item("3 Too", 11),
            item("4 Costs and prices", 12),
        )
        assertFalse(StructureAuditMain.isNumberedChapter("3 Too"))
        assertFalse(StructureAuditMain.isNumberedChapter("1.1 Detail"))
        val native = StructureAuditMain.classifyOutline(outline)
        assertEquals("numbered", native.method)
        assertEquals(listOf(5, 9, 12), native.pages)
    }

    @Test
    fun proseIsNotCountedAsANumberedChapter() {
        val prose = "12 " + "word ".repeat(40) + "end. more."
        assertTrue(StructureAuditMain.looksLikeBodyDump(prose))
        assertFalse(StructureAuditMain.isNumberedChapter(prose))
    }

    @Test
    fun chineseChapterMarkerWinsBeforeAPartMarker() {
        val outline = listOf(
            item("第1篇 总述", 2),
            item("第1章 开篇", 4),
            item("第2章 后文", 9),
        )
        val native = StructureAuditMain.classifyOutline(outline)
        assertEquals("explicit", native.method)
        assertEquals(listOf(4, 9), native.pages)
    }

    @Test
    fun aTwoEntryTopYieldsToADeeperChapterBand() {
        val top = listOf(item("Volume label", 1), item("Later wrapper", 80))
        val band = (1..8).map { item("Piece $it", 10 + it, level = 2) }
        val native = StructureAuditMain.classifyOutline(top + band)
        assertEquals("outline-level", native.method)
        assertEquals((11..18).toList(), native.pages)
    }

    @Test
    fun aHugeTopLevelFallsThroughToASmallerBand() {
        val top = (1..80).map { item("Section label $it", it, level = 1) }
        val band = (1..12).map { item("Piece $it", 100 + it, level = 2) }
        val native = StructureAuditMain.classifyOutline(top + band)
        assertEquals("outline-level", native.method)
        assertEquals((101..112).toList(), native.pages)
    }

    @Test
    fun samplePagesSpreadsFromTheFirstChapterThroughTheLastPage() {
        assertEquals(listOf(32, 100, 168, 236, 305), StructureAuditMain.samplePages(32, 305, 5))
        assertEquals(listOf(1, 2, 3), StructureAuditMain.samplePages(1, 3, 10))
        assertEquals(emptyList<Int>(), StructureAuditMain.samplePages(5, 4, 3))
    }

    private fun epub(title: String, page: Int, level: Int = 1) =
        BookOutlineItem(title, level, page, HeadingSource.EPUB_TOC)

    @Test
    fun introductionIsBodyAndTheNumberedChapterStaysLater() {
        val structure = StructureAuditMain.readerStructure(listOf(
            epub("A Note Before The Work Starts", 2),
            epub("Introduction and Plan of the Work", 4),
            epub("Chapter 1 Opening", 7),
            epub("Chapter 2 Later", 12),
        ))
        assertEquals(4, structure.bodyStart)
        assertEquals(7, structure.firstChapter)
        assertEquals(listOf(7, 12), structure.chapterPages)
    }

    @Test
    fun aPrefaceIsFrontMatterAndADeeperNumberedHeadingStaysInsideThePiece() {
        val structure = StructureAuditMain.readerStructure(listOf(
            epub("序", 2),
            epub("Opening Piece", 3),
            epub("第一章 内文", 13, level = 2),
            epub("第二章 内文", 14, level = 2),
        ))
        assertEquals(3, structure.bodyStart)
        assertEquals(3, structure.firstChapter)
        assertEquals(listOf(3), structure.chapterPages)
    }

    @Test
    fun aPlainBodyLabelIsASectionAndARomanHeadingOpensTheChapterRun() {
        val adjusted = StructureAuditMain.adjustOutline(listOf(
            epub("正文", 2),
            epub("I OPENING MATTER HERE", 3),
            epub("Later Piece", 8),
        ))
        assertEquals(TocNodeKind.SECTION, adjusted[0].kind)
        assertEquals(TocNodeKind.CHAPTER, adjusted[1].kind)
        assertEquals(TocNodeKind.BACK_MATTER, adjusted[2].kind)
        val structure = StructureAuditMain.readerStructure(listOf(
            epub("正文", 2),
            epub("I OPENING MATTER HERE", 3),
            epub("Later Piece", 8),
        ))
        assertEquals(3, structure.bodyStart)
        assertEquals(3, structure.firstChapter)
        assertEquals(listOf(3), structure.chapterPages)
    }

    @Test
    fun headerExpectationUsesTheOutlineItemOfTheSameKind() {
        val adjusted = StructureAuditMain.adjustOutline(listOf(
            item("1 Why markets fail here", 32),
            item("1.1 Detail of the topic", 40, level = 2),
            item("Index", 90),
        ))
        assertEquals(
            "1.1 Detail of the topic",
            StructureAuditMain.expectedHeaderTitle(adjusted, 50, TocNodeKind.SECTION),
        )
        assertEquals(
            "1 Why markets fail here",
            StructureAuditMain.expectedHeaderTitle(adjusted, 50, TocNodeKind.CHAPTER),
        )
        assertEquals("Index", StructureAuditMain.expectedHeaderTitle(adjusted, 90, TocNodeKind.BACK_MATTER))
        val later = StructureAuditMain.adjustOutline(listOf(item("Index", 31, offset = 1360)))
        assertEquals(null, StructureAuditMain.expectedHeaderTitle(later, 31, TocNodeKind.BACK_MATTER, 0))
        assertEquals("Index", StructureAuditMain.expectedHeaderTitle(later, 31, TocNodeKind.BACK_MATTER, 1360))
    }

    @Test
    fun insideCheckoutRejectsADirectoryOutsideThisRepository() {
        var dir = Path("").toAbsolutePath()
        var root: java.nio.file.Path? = null
        while (true) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
                root = dir
                break
            }
            dir = dir.parent ?: break
        }
        val outside = Files.createTempDirectory("audit-outside-")
        try {
            if (root != null) assertTrue(StructureAuditMain.insideCheckout(root.resolve("desktopApp")))
            assertFalse(StructureAuditMain.insideCheckout(outside.resolve("report.json")))
        } finally {
            outside.toFile().deleteRecursively()
        }
    }
}
