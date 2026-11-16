package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Self-authored imports for the separate real-subscription binding check. No user library is read. */
class SavedBookBindingTest {
    @Test fun exportActualSavedImportsWithoutRegeneratingTheirPages() = runBlocking {
        val output = Path.of("build/screens/saved-book-binding")
        output.toFile().deleteRecursively()
        Files.createDirectories(output)
        val library = TextbookLibrary(output.resolve("library"))
        val fixtures = listOf("formats/gbk.txt", "formats/lesson.docx", "formats/lesson.pptx") +
            if (System.getProperty("os.name").startsWith("Windows")) listOf("ocr/01-clean-serif.png") else emptyList()
        val samples = fixtures.map { fixture ->
            val source = output.resolve(fixture.substringAfterLast('/'))
            checkNotNull(javaClass.getResourceAsStream("/books/$fixture")).use { Files.copy(it, source) }
            val book = library.importBook(source)
            Files.delete(source)
            assertEquals(book.pages, TextbookLibrary(library.directory).book(book.id).pages)
            val block = StudyCoachCourse.blocks(book.id, book.pages).first { !it.heading && it.text.length in 30..4000 }
            assertEquals(block.text, book.page(block.page)!!.text.substring(block.start, block.end))
            if (book.format == "png") assertTrue(book.page(block.page)!!.ocr)
            buildJsonObject {
                put("book", book.id); put("format", book.format); put("page", block.page)
                put("id", block.id); put("start", block.start); put("end", block.end); put("text", block.text)
                put("ocr", book.page(block.page)!!.ocr)
            }
        }
        Files.writeString(output.resolve("samples.json"), JsonArray(samples).toString())
        assertEquals(fixtures.size, samples.size)
    }
}
