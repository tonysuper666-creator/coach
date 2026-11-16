package com.coach.desktop.books

import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.sl.usermodel.ShapeType
import org.apache.poi.sl.usermodel.PictureData.PictureType
import java.awt.Color
import java.awt.Dimension
import java.awt.geom.Rectangle2D
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class FormatBookIllustrationsTest {
    private fun fixture(root: Path, name: String): Path = root.resolve(name).also {
        javaClass.getResourceAsStream("/books/illustrations/$name")!!.use { input -> Files.copy(input, it) }
    }
    private inline fun withRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("coach-format-images-")
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }

    @Test fun fourReflowFormatsSaveImagesAtExactUtf16OffsetsAndReopenWithoutSources() = withRoot { root ->
        for (name in listOf("illustrated.docx", "article.html", "illustrated.mobi", "illustrated.azw3")) {
            val sourceDir = Files.createDirectory(root.resolve(name + "-sources"))
            val source = fixture(sourceDir, name)
            val png = fixture(sourceDir, "diagram.png")
            val library = TextbookLibrary(root.resolve(name + "-library"))
            val book = library.importBook(source)
            assertEquals(2, book.pages.size, name)
            assertEquals(2, book.pages.sumOf { it.illustrations.size }, name)
            assertEquals(if (name.endsWith("docx")) listOf("甲😀乙 Biology.", "甲😀乙 Biology. | Cell") else
                listOf("# Chapter 1\n\n\n\n甲😀乙 ${if (name.endsWith("html")) "Biological diagram." else "Biology."}",
                    "# Chapter 2\n\n\n\n甲😀乙 ${if (name.endsWith("html")) "Second." else "Again."}"), book.pages.map { it.text }, name)
            val expectedBytes = Files.readAllBytes(png)
            for (page in book.pages) {
                val image = page.illustrations.single()
                assertEquals(page.text.indexOf("乙"), image.anchorOffset, name)
                assertEquals(64, image.width); assertEquals(32, image.height)
                assertContentEquals(expectedBytes, Files.readAllBytes(BookIllustrationAssets.imageFile(library.directory, book.id, image.imageSha256, image.extension)))
                for (block in StudyCoachCourse.blocks(book.id, listOf(page)).filterNot { it.heading }) {
                    val resolution = assertIs<BookSourceResolution.Resolved>(BookSourceResolver(library).resolve(
                        OwnedBookCitation(book.id, page.number, block.start, block.end), block.text))
                    assertEquals(block.text, page.text.substring(resolution.target.start, resolution.target.end))
                }
            }
            assertEquals(1, book.pages.flatMap { it.illustrations }.map { it.imageSha256 }.distinct().size, name)
            sourceDir.toFile().deleteRecursively()
            assertEquals(book.pages, TextbookLibrary(library.directory).book(book.id).pages, name)
        }
    }

    private fun powerpoint(path: Path, slides: Int = 2) {
        XMLSlideShow().use { show ->
            show.pageSize = Dimension(640, 360)
            repeat(slides) { index ->
                val slide = show.createSlide()
                slide.createAutoShape().apply {
                    shapeType = ShapeType.RECT
                    anchor = Rectangle2D.Double(100.0, 100.0, 200.0, 100.0)
                    fillColor = if (index == 0) Color.RED else Color.BLUE
                    lineColor = fillColor
                }
                slide.createPicture(show.addPicture(javaClass.getResourceAsStream("/books/illustrations/diagram.png")!!.readBytes(), PictureType.PNG)).apply {
                    anchor = Rectangle2D.Double(400.0, 180.0, 128.0, 64.0)
                }
                slide.createTextBox().apply {
                    anchor = Rectangle2D.Double(20.0, 20.0, 600.0, 50.0)
                    text = "Slide ${index + 1} 生物 Biology 😀"
                }
            }
            Files.newOutputStream(path).use(show::write)
        }
    }

    @Test fun powerpointStoresWholeSlideBeforeUnchangedTextInSlideOrder() = withRoot { root ->
        val source = root.resolve("slides.pptx")
        powerpoint(source)
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(source)
        assertEquals(listOf("Slide 1 生物 Biology 😀", "Slide 2 生物 Biology 😀"), book.pages.map { it.text })
        for ((index, page) in book.pages.withIndex()) {
            val image = page.illustrations.single()
            assertEquals(0, image.anchorOffset)
            assertEquals(page.number, image.page)
            assertEquals(1280, image.width); assertEquals(720, image.height)
            val file = BookIllustrationAssets.imageFile(library.directory, book.id, image.imageSha256, image.extension)
            val raster = ImageIO.read(file.toFile())
            assertEquals((if (index == 0) Color.RED else Color.BLUE).rgb, raster.getRGB(400, 300))
            // Text starts at the top left; a renderer that flips every shape must fail.
            val leftInk = (40 until 400).sumOf { x -> (40 until 140).count { y -> raster.getRGB(x, y) != Color.WHITE.rgb } }
            val rightInk = (900 until 1240).sumOf { x -> (40 until 140).count { y -> raster.getRGB(x, y) != Color.WHITE.rgb } }
            assertTrue(leftInk > 100, "Expected upright text at the left: $leftInk")
            assertEquals(0, rightInk, "Text was flipped toward the right")
            val original = ImageIO.read(javaClass.getResourceAsStream("/books/illustrations/diagram.png"))
            assertEquals(original.getRGB(8, 8), raster.getRGB(832, 392), "Picture top-left orientation")
            assertEquals(original.getRGB(55, 24), raster.getRGB(1020, 456), "Picture bottom-right orientation")
            if (index == 0) {
                val output = Path.of("build/screens/ton1663-rendered-slide.png")
                Files.createDirectories(output.parent); Files.copy(file, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        Files.delete(source)
        assertEquals(book.pages, TextbookLibrary(library.directory).book(book.id).pages)
    }

    @Test fun htmlNeverFetchesRemoteImagesOrReadsOutsideSelectedDirectory() = withRoot { root ->
        val directory = Files.createDirectory(root.resolve("article"))
        val source = directory.resolve("article.html")
        val library = TextbookLibrary(root.resolve("library"))
        Files.writeString(source, "<html><body><p>正文<img src='https://127.0.0.1:1/never-request'>结束</p></body></html>")
        assertTrue(library.importBook(source).pages.single().illustrations.isEmpty())
        fixture(root, "diagram.png")
        for (path in listOf("../diagram.png", "%2e%2e/diagram.png")) {
            Files.writeString(source, "<html><body><p>正文<img src='$path'>结束</p></body></html>")
            assertContains(assertFailsWith<IllegalArgumentException> { library.importBook(source) }.message.orEmpty(), "目录")
        }
        // Percent-encoded spaces are ordinary local asset names.
        Files.copy(root.resolve("diagram.png"), directory.resolve("my diagram.png"))
        Files.writeString(source, "<html><body><p>甲😀<img src='my%20diagram.png'>乙</p></body></html>")
        assertEquals(3, library.importBook(source).pages.single().illustrations.single().anchorOffset)
    }

    @Test fun imageLimitsAndCancellationLeaveNoPartiallyImportedBook() = withRoot { root ->
        val source = root.resolve("slides.pptx")
        powerpoint(source)
        val library = TextbookLibrary(root.resolve("library"))
        val control = BookImportControl(progress = { throw BookImportCancelled() })
        assertFailsWith<BookImportCancelled> { library.importBook(source, control) }
        assertTrue(library.catalog().isEmpty())
        val png = Files.readAllBytes(fixture(root, "diagram.png"))
        // PNG header dimensions are checked without decoding its raster.
        java.nio.ByteBuffer.wrap(png).putInt(16, 9000)
        assertContains(assertFailsWith<IllegalArgumentException> { FormatBookImages.image(png, 1, 0) }.message.orEmpty(), "8000")
    }
}
