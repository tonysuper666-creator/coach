package com.coach.desktop.books

import com.coach.domain.reader.ReaderTextRange
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class PdfPrintedTablesTest {
    private fun fixture(numbered: Boolean = true, brokenEdge: Boolean = false): PDDocument {
        val doc = PDDocument()
        val page = PDPage(PDRectangle.LETTER)
        page.cropBox = PDRectangle(12f, 17f, 530f, 700f)
        doc.addPage(page)
        PDPageContentStream(doc, page).use { out ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            fun text(y: Float, size: Float, value: String, x: Float = 60f) {
                out.beginText()
                out.setFont(font, size)
                out.newLineAtOffset(x, y)
                out.showText(value)
                out.endText()
            }
            fun rule(x: Float, y: Float, endX: Float, endY: Float) {
                out.moveTo(x, y)
                out.lineTo(endX, endY)
                out.stroke()
            }
            text(620f, 12f, "Text before the numbered table.")
            text(560f, 12f, if (numbered) "Table 1. Quantities" else "Quantities")
            for (y in listOf(540f, 510f, 420f)) rule(50f, y, 350f, y)
            for (row in 0..3) {
                val y = 540f - row * 30
                rule(50f, y, 50f, y - 30)
                if (!brokenEdge || row != 2) rule(350f, y, 350f, y - 30)
                text(y - 19, 9f, if (row == 0) "Name" else "Repeated")
                text(y - 19, 9f, if (row == 0) "Count" else "Item $row", 220f)
            }
            text(375f, 12f, "Text after the table.")
        }
        return doc
    }

    @Test fun extractsPrintedHeaderAndEveryRowAgainstCanonicalText() {
        fixture().use { pdf ->
            val text = PdfPageTextExtraction.extract(pdf, 1).unit.text!!
            val crop = PdfPrintedTables.extract(pdf, BookPage(1, text), BookImportControl()).single()
            assertEquals(PageVisualKind.TABLE, crop.kind)
            assertNull(crop.sourceStart)
            assertEquals("Table 1. Quantities", crop.caption)
            val table = assertNotNull(crop.table)
            val rows = table.rows
            assertEquals(listOf("Name Count", "Repeated Item 1", "Repeated Item 2", "Repeated Item 3"),
                rows.map { text.substring(it.source.startUtf16, it.source.endUtf16).trim() })
            assertEquals(36f, table.bodyFontPx)
            assertEquals(27f, table.tableFontPx)
            assertTrue(table.valid(crop.height, text))
            val layout = table.layout()
            assertEquals(rows.first().bottomPx, layout.headerBottomPx)
            assertEquals(rows.drop(1).map { it.source.endUtf16 }, layout.rows.map { it.sourceEndUtf16 })
            assertEquals(crop.table, ImportedTable.decode(table.encode(), crop.height))
            assertTrue(PdfPrintedTables.extract(pdf, BookPage(1, text + "changed"), BookImportControl()).isEmpty())
        }
    }

    @Test fun ordinaryImportPersistsVerifiedRowsAndReadsOlderLibraryWithoutThem() {
        val root = Files.createTempDirectory("coach-printed-table-")
        try {
            val input = root.resolve("sample.pdf")
            val canonical = fixture().use { pdf ->
                pdf.save(input.toFile())
                PdfPageTextExtraction.extract(pdf, 1).unit.text
            }
            val directory = root.resolve("library")
            val first = TextbookLibrary(directory)
            val id = first.importBook(input).id
            val page = first.book(id).pages.single()
            assertEquals(canonical, page.text)
            val visual = page.illustrations.single { it.kind == PageVisualKind.TABLE }
            val table = assertNotNull(visual.table)
            assertEquals(4, table.rows.size)
            val reopened = TextbookLibrary(directory)
            assertEquals(page, reopened.book(id).pages.single())
            val citations = table.rows.map { row ->
                val (start, end) = row.source
                OwnedBookCitation(id, 1, start, end) to page.text.substring(start, end)
            }
            assertTrue(BookSourceResolver(reopened).resolveAll(citations).all { it is BookSourceResolution.Resolved })
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("library.sqlite")).use { db ->
                db.createStatement().use { it.execute("ALTER TABLE book_illustrations DROP COLUMN table_rows") }
            }
            val legacy = TextbookLibrary(directory).book(id).pages.single()
            assertEquals(page.text, legacy.text)
            assertEquals(visual.copy(table = null), legacy.illustrations.single { it.kind == PageVisualKind.TABLE })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun incompleteEdgesAndUnnumberedFrameKeepOrdinaryText() {
        for (doc in listOf(fixture(brokenEdge = true), fixture(numbered = false))) {
            doc.use { pdf ->
                val text = PdfPageTextExtraction.extract(pdf, 1).unit.text!!
                assertTrue(text.contains("Item 3"))
                assertTrue(PdfPrintedTables.extract(pdf, BookPage(1, text), BookImportControl()).isEmpty())
            }
        }
    }

    @Test fun invalidGeometryAndSourceOffsetsCannotBecomeRows() {
        val text = "a😀bcde"
        val table = ImportedTable(listOf(
            ImportedTableRow(10, ReaderTextRange(0, 1)),
            ImportedTableRow(20, ReaderTextRange(1, 3)),
            ImportedTableRow(30, ReaderTextRange(4, 7)),
        ), 12f, 10f)
        assertTrue(table.valid(30, text))
        assertFalse(table.copy(rows = table.rows.toMutableList().apply {
            set(1, ImportedTableRow(20, ReaderTextRange(2, 3)))
        }).valid(30, text))
        assertNull(ImportedTable.decode(table.encode(), 31))
        assertNull(ImportedTable.decode(table.encode().dropLast(1).toByteArray(), 30))
        assertNull(ImportedTable.decode(table.encode().apply { this[0] = 1 }, 30))
    }
}
