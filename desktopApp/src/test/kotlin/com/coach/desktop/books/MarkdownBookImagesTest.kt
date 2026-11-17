package com.coach.desktop.books

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownBookImagesTest {
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
            sourceDir.toFile().deleteRecursively()
            assertEquals(book.pages, TextbookLibrary(library.directory).book(book.id).pages)
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
}
