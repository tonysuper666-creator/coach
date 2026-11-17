package com.coach.desktop.books

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MarkdownBookImagesTest {
    @Test fun exactUtf16PageBoundaryPlacesTheImageOnTheFollowingPage() {
        val root = Files.createTempDirectory("coach-md-boundary-")
        try {
            ImageIO.write(BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB), "png", root.resolve("plot.png").toFile())
            val before = "x".repeat(3998) + "😀"
            val after = "![Boundary](plot.png) After"
            val source = root.resolve("book.md")
            Files.writeString(source, before + after)
            val book = TextbookLibrary(root.resolve("library")).importBook(source)
            assertEquals(listOf(before, after), book.pages.map { it.text })
            assertTrue(book.pages[0].illustrations.isEmpty())
            assertEquals(2, book.pages[1].illustrations.single().page)
            assertEquals(0, book.pages[1].illustrations.single().anchorOffset)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun importsLocalImagesAtUnchangedUtf16PositionsAndReopensWithoutSource() {
        val root = Files.createTempDirectory("coach-md-images-")
        try {
            val sourceDir = Files.createDirectory(root.resolve("source"))
            val image = sourceDir.resolve("my diagram.png")
            ImageIO.write(BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB), "png", image.toFile())
            val original = Files.readAllBytes(image)
            val markdown = """
                # Chapter 1
                Before 😀
                ![Alt](<my diagram.png> "Figure 1")
                After.
                `![not an image](missing.png)`
                ```
                ![not an image](missing.png)
                ```
                # Chapter 2
                Other 😀 ![Second](my%20diagram.png) end.
            """.trimIndent()
            val source = sourceDir.resolve("book.md")
            Files.writeString(source, markdown)
            val expected = TextbookExtraction.extract(source, "md", "book").pages
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(source)
            assertEquals(expected.map { it.text }, book.pages.map { it.text })
            assertEquals(2, book.pages.size)
            val images = book.pages.map { it.illustrations.single() }
            assertEquals(listOf(1, 2), images.map { it.page })
            assertEquals(book.pages.map { it.text.indexOf("![") }, images.map { it.anchorOffset })
            assertEquals(listOf("Figure 1", "Second"), images.map { it.caption })
            assertEquals(images.first().imageSha256, images.last().imageSha256)
            assertContentEquals(original, Files.readAllBytes(BookIllustrationAssets.imageFile(library.directory, book.id, images.first())))
            val blocks = StudyCoachCourse.blocks(book.id, book.pages)
            assertEquals(StudyCoachCourse.blocks(book.id, expected), blocks)
            val citations = blocks.filter { !it.heading }.map { OwnedBookCitation(book.id, it.page, it.start, it.end) to it.text }
            val resolved = BookSourceResolver(library).resolveAll(citations)
            assertTrue(resolved.isNotEmpty() && resolved.all { it is BookSourceResolution.Resolved })
            sourceDir.toFile().deleteRecursively()
            val reopened = TextbookLibrary(library.directory)
            assertEquals(book.pages, reopened.book(book.id).pages)
            assertEquals(resolved, BookSourceResolver(reopened).resolveAll(citations))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun skipsRemoteAndMissingImagesWithoutChangingText() {
        val root = Files.createTempDirectory("coach-md-remote-")
        try {
            val source = root.resolve("book.md")
            Files.writeString(source, "Before ![remote](https://127.0.0.1:1/x.png) ![missing](missing.png) After")
            val book = TextbookLibrary(root.resolve("library")).importBook(source)
            assertTrue(book.pages.single().illustrations.isEmpty())
            assertEquals(Files.readString(source), book.pages.single().text)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun balancesDestinationsAndEscapesWithoutTreatingCodeAsImages() {
        val root = Files.createTempDirectory("coach-md-syntax-")
        try {
            for (name in listOf("plot(one).png", "a b.png")) {
                ImageIO.write(BufferedImage(9, 7, BufferedImage.TYPE_INT_RGB), "png", root.resolve(name).toFile())
            }
            val markdown = """
                # Images
                😀 ![A [nested] label](plot(one).png "Figure (1)")
                ![escaped \] label](plot\(one\).png 'Figure 2')
                ![space](<a b.png>)
                \![escaped](plot(one).png)
                `` ![code](plot(one).png) ` still code ``
                    ![indented](plot(one).png)
                ~~~~ language
                ![fenced](plot(one).png)
                ~~~
                ![still fenced](plot(one).png)
                ~~~~
                ![last](a%20b.png)
            """.trimIndent()
            val source = root.resolve("book.md")
            Files.writeString(source, markdown)
            val book = TextbookLibrary(root.resolve("library")).importBook(source)
            val page = book.pages.single()
            assertEquals(markdown, page.text)
            assertEquals(listOf("Figure (1)", "Figure 2", "space", "last"), page.illustrations.map { it.caption })
            assertEquals(listOf("![A", "![escaped ", "![space", "![last").map { markdown.indexOf(it) },
                page.illustrations.map { it.anchorOffset })
            assertTrue(page.illustrations.all { BookIllustrationAnchors.isValidAnchor(page.text, it.anchorOffset) })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun markedPagesKeepCrLfAndAstralCitationOffsetsAndRejectDirectoryEscape() {
        val root = Files.createTempDirectory("coach-md-marked-")
        try {
            val directory = Files.createDirectory(root.resolve("source"))
            ImageIO.write(BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB), "png", directory.resolve("plot.png").toFile())
            val raw = "<!-- PDF 第 12 页 · 书第 2 页 -->\r\n前😀\r\n![图](plot.png)\r\n尾\r\n" +
                "<!-- PDF 第 13 页 · 书第 3 页 -->\r\n后😀 ![图](plot.png)"
            val source = directory.resolve("book.md")
            Files.writeString(source, raw)
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(source)
            assertEquals(listOf(12, 13), book.pages.map { it.number })
            assertEquals(raw, book.pages.joinToString("") { it.text })
            for (page in book.pages) {
                assertEquals(page.text.indexOf("!["), page.illustrations.single().anchorOffset)
                assertTrue(page.text.contains("\r\n"))
            }
            Files.writeString(source, "Before ![outside](../outside.png) after")
            assertFailsWith<IllegalArgumentException> { library.importBook(source) }
            assertEquals(1, library.catalog().size)
            assertEquals(book.pages, TextbookLibrary(library.directory).book(book.id).pages)
        } finally { root.toFile().deleteRecursively() }
    }
}
