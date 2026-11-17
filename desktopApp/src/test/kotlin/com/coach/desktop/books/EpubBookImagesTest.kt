package com.coach.desktop.books

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

class EpubBookImagesTest {
    private fun png() = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(12, 10, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    private fun epub(path: Path, chapters: List<String>, assets: Map<String, ByteArray>) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            entry("META-INF/container.xml", "<container><rootfiles><rootfile full-path='OPS/package.opf'/></rootfiles></container>".toByteArray())
            val manifest = chapters.indices.joinToString("") { "<item id='c$it' href='text/c$it.xhtml'/>" }
            val spine = chapters.indices.joinToString("") { "<itemref idref='c$it'/>" }
            entry("OPS/package.opf", "<package><metadata><title>Self authored</title></metadata><manifest>$manifest</manifest><spine>$spine</spine></package>".toByteArray())
            chapters.forEachIndexed { i, body -> entry("OPS/text/c$i.xhtml",
                "<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Ignored</title></head><body>$body</body></html>".toByteArray()) }
            assets.forEach { (name, bytes) -> entry("OPS/$name", bytes) }
        }
    }

    private fun <T> temporary(block: (Path) -> T): T {
        val root = Files.createTempDirectory("coach-epub-images-")
        return try { block(root) } finally { root.toFile().deleteRecursively() }
    }

    @Test fun mapsTreePositionsThroughTrimPaginationAndRepeatedChaptersWithoutChangingCitations() = temporary { root ->
        val image = png()
        val chapter = "\r\n<p>Before 😀</p><figure><img src='../images/plot%20one.png'/><figcaption>Figure 1</figcaption></figure><p>After 😀</p>\r\n"
        val file = root.resolve("book.epub")
        epub(file, listOf(chapter, chapter), mapOf("images/plot one.png" to image))
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(file)
        val expected = "Before 😀\n\nFigure 1\n\nAfter 😀"
        assertEquals(listOf(expected, expected), book.pages.map { it.text })
        assertEquals(listOf(1, 2), book.pages.map { it.illustrations.single().page })
        assertEquals(listOf(expected.indexOf("Figure 1"), expected.indexOf("Figure 1")), book.pages.map { it.illustrations.single().anchorOffset })
        assertEquals(listOf("Figure 1", "Figure 1"), book.pages.map { it.illustrations.single().caption })
        for (page in book.pages) {
            val asset = page.illustrations.single()
            assertContentEquals(image, Files.readAllBytes(BookIllustrationAssets.imageFile(library.directory, book.id, asset)))
        }
        val sources = TextbookExtraction.epubSourceMapping(file, book.pages)
        assertEquals(EpubSourceMapping.Status.MAPPED, sources.status)
        assertEquals(listOf(1, 2), sources.elements.filter { it.localName == "figcaption" }.map { it.ranges.single().page })
        val blocks = StudyCoachCourse.blocks(book.id, book.pages)
        val citations = blocks.filter { !it.heading }.map { OwnedBookCitation(book.id, it.page, it.start, it.end) to it.text }
        val resolved = BookSourceResolver(library).resolveAll(citations)
        assertTrue(resolved.isNotEmpty() && resolved.all { it is BookSourceResolution.Resolved })
        Files.delete(file)
        val reopenedLibrary = TextbookLibrary(library.directory)
        val reopened = reopenedLibrary.book(book.id)
        assertEquals(book.pages, reopened.pages)
        assertEquals(blocks, StudyCoachCourse.blocks(reopened.id, reopened.pages))
        assertEquals(resolved, BookSourceResolver(reopenedLibrary).resolveAll(citations))
    }

    @Test fun boundaryAndSurrogateAnchorsBelongToTheFollowingPage() = temporary { root ->
        val file = root.resolve("boundary.epub")
        // The heading-less traversal has no leading separators. Exactly 4000 UTF-16
        // units precede the image; its page must be 2, not the end of page 1.
        val before = "x".repeat(3998) + "😀"
        epub(file, listOf(before + "<img src='../plot.png' alt='Boundary'/>After"), mapOf("plot.png" to png()))
        val book = TextbookLibrary(root.resolve("library")).importBook(file)
        assertEquals(listOf(before, "After"), book.pages.map { it.text })
        assertTrue(book.pages[0].illustrations.isEmpty())
        assertEquals(0, book.pages[1].illustrations.single().anchorOffset)
        assertEquals(2, book.pages[1].illustrations.single().page)
    }

    @Test fun svgKeepsTextInkAndNestedImageDoesNotBecomeADuplicate() = temporary { root ->
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="180" height="70" viewBox="0 0 180 70"><path d="M10 55 L170 55" stroke="black"/><text x="12" y="30" font-size="20">Figure label</text></svg>"""
        val file = root.resolve("svg.epub")
        epub(file, listOf("<p>Before 😀</p><figure>$svg<figcaption>Figure 2</figcaption></figure><p>After</p>" +
            "<img src='../vector.svg' alt='External SVG'/><img src='https://127.0.0.1:1/never.png'/><img src='../missing.png'/>"),
            mapOf("vector.svg" to svg.toByteArray()))
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(file)
        val page = book.pages.single()
        assertEquals("Before 😀\n\nFigure labelFigure 2\n\nAfter", page.text)
        assertEquals(2, page.illustrations.size)
        assertEquals(listOf("Figure 2", "External SVG"), page.illustrations.map { it.caption })
        for (asset in page.illustrations) {
            val image = ImageIO.read(BookIllustrationAssets.imageFile(library.directory, book.id, asset).toFile())
            try {
                assertEquals(360, image.width)
                assertEquals(140, image.height)
                var labelInk = 0
                for (y in 10 until 65) for (x in 15 until 300) if (image.getRGB(x, y).ushr(24) > 128) labelInk++
                assertTrue(labelInk > 100, "SVG labels must survive rasterization, not just its bottom path")
            } finally { image.flush() }
        }
    }

    @Test fun unsupportedSvgTextAndExternalResourcesAreNotSavedAsIncompleteImages() {
        for (content in listOf("<text x='10' y='30'><tspan>Do not lose me</tspan></text>",
            "<image href='file:///private/image.png'/>", "<style>@import 'https://127.0.0.1:1/x';</style>")) {
            val svg = EpubXml.parse("<svg xmlns='http://www.w3.org/2000/svg' width='100' height='50'>$content</svg>".toByteArray())
            assertNull(BookSvgImages.raster(svg))
        }
    }
}
