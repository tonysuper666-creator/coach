package com.coach.server

import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Same synthetic bad files as the desktop import test: truncated, not a PDF, password, one damaged page. */
class UnifiedBookBadPageTest {
    private val processor = DefaultUnifiedBookProcessor()

    @Test fun damagedLastPageIsSkippedAndTheRestKeepStructureAndIndex() {
        val root = Files.createTempDirectory("coach-server-damaged-")
        try {
            val artifact = process(damagedPagePdf(root.resolve("torn.pdf"), damagedIndex = 3))
            assertEquals(listOf(4), artifact.skippedPages)
            assertEquals(4, artifact.pageCount)
            assertEquals("DAMAGED", artifact.pageRoles[4])
            assertTrue(artifact.pageRoles[1] != "DAMAGED")
            assertEquals("PDF_OUTLINE", artifact.source)
            assertTrue(artifact.chapters.isNotEmpty())
            assertEquals(1, artifact.chapters.first().startPage)
            assertEquals(4, artifact.chapters.last().endPage)
            assertTrue(artifact.totalChars >= 33, "good pages dropped out of the index, chars=${artifact.totalChars}")
            assertEquals(64, artifact.contentFingerprint.length)
            assertTrue(artifact.toJson().toString().contains("\"skippedPages\":[4]"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun damagedMiddlePageDoesNotBlankThePagesAfterIt() {
        val root = Files.createTempDirectory("coach-server-middle-")
        try {
            val artifact = process(damagedPagePdf(root.resolve("middle.pdf"), damagedIndex = 1))
            assertEquals(listOf(2), artifact.skippedPages)
            assertEquals("DAMAGED", artifact.pageRoles[2])
            assertTrue(artifact.pageRoles[1] != "DAMAGED" && artifact.pageRoles[3] != "DAMAGED")
            assertEquals(4, artifact.chapters.last().endPage)
            assertTrue(artifact.totalChars >= 33, "pages after the bad one were lost, chars=${artifact.totalChars}")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun truncatedNotPdfAndPasswordFailTheFileWithoutAPartialArtifact() {
        val root = Files.createTempDirectory("coach-server-bad-file-")
        try {
            val truncated = assertFailsWith<IllegalArgumentException> { process(truncatedPdf(root.resolve("half.pdf"))) }
            assertTrue(truncated.message.orEmpty().contains("损坏"))
            assertTrue(truncated.message.orEmpty().contains("换一份"))

            val notPdf = assertFailsWith<IllegalArgumentException> {
                process(Files.writeString(root.resolve("notes.pdf"), "hello, this is not a pdf"))
            }
            assertTrue(notPdf.message.orEmpty().contains("不是 PDF"))

            val locked = assertFailsWith<IllegalArgumentException> {
                process(passwordPdf(root.resolve("locked.pdf")))
            }
            assertTrue(locked.message.orEmpty().contains("密码保护"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun process(file: Path): UnifiedBookArtifact = runBlocking {
        processor.process(file, "pdf", "synthetic", "ab".repeat(32), "v2-skeleton") { _, _ -> }
    }

    private fun damagedPagePdf(target: Path, damagedIndex: Int): Path {
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            repeat(4) { index ->
                val page = PDPage()
                pdf.addPage(page)
                if (index == 0) {
                    val outline = PDDocumentOutline()
                    pdf.documentCatalog.documentOutline = outline
                    val dest = PDPageFitDestination().apply { this.page = page }
                    outline.addLast(PDOutlineItem().apply { title = "1 Body"; destination = dest })
                }
                if (index == damagedIndex) return@repeat
                PDPageContentStream(pdf, page).use { stream ->
                    stream.beginText()
                    stream.setFont(font, 12f)
                    stream.newLineAtOffset(72f, 700f)
                    stream.showText("GOOD-PAGE-${index + 1}")
                    stream.endText()
                }
            }
            val broken = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.FONT)
                setItem(COSName.SUBTYPE, COSName.TYPE0)
                setItem(COSName.BASE_FONT, COSName.getPDFName("BrokenPage"))
            }
            val fonts = COSDictionary()
            fonts.setItem(COSName.getPDFName("F1"), broken)
            val resources = PDResources()
            resources.cosObject.setItem(COSName.FONT, fonts)
            val torn = pdf.getPage(damagedIndex)
            torn.resources = resources
            val contents = PDStream(pdf)
            contents.createOutputStream().use { out ->
                out.write("BT /F1 12 Tf 72 700 Td (GOOD-PAGE-${damagedIndex + 1}) Tj ET\n".toByteArray(Charsets.US_ASCII))
            }
            torn.setContents(contents)
            pdf.save(target.toFile())
        }
        return target
    }

    private fun truncatedPdf(target: Path): Path {
        val bytes = PDDocument().use { pdf ->
            repeat(3) { index ->
                val page = PDPage().also { pdf.addPage(it) }
                PDPageContentStream(pdf, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
                    stream.newLineAtOffset(72f, 700f)
                    stream.showText("Coastal notes page ${index + 1}")
                    stream.endText()
                }
            }
            java.io.ByteArrayOutputStream().also { pdf.save(it) }.toByteArray()
        }
        Files.write(target, bytes.copyOf(bytes.size * 60 / 100))
        return target
    }

    private fun passwordPdf(target: Path): Path {
        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
                stream.newLineAtOffset(72f, 700f)
                stream.showText("LOCKED-TOKEN")
                stream.endText()
            }
            pdf.protect(StandardProtectionPolicy("owner-password", "reader-password", AccessPermission()).apply {
                encryptionKeyLength = 128
            })
            pdf.save(target.toFile())
        }
        return target
    }
}
