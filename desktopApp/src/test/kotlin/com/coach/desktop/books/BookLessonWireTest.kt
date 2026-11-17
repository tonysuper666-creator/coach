package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class BookLessonWireTest {
    @Test fun allBlocksAndUtf16CoordinatesSurviveWithoutSelectionTruncation() {
        val book = "a".repeat(64)
        val blocks = (1..8).map { n ->
            val text = "  自写第 $n 块🌱  "
            SourceBlock("$book:p$n:5", n, 5, 5 + text.length, false, text)
        }
        val input = BookLessonWire.request("ab12", blocks, 8)
        assertEquals("lesson", input["kind"]!!.jsonPrimitive.content)
        assertFalse("selection" in input)
        assertEquals(blocks.map { it.text }, input["blocks"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content })
        assertFailsWith<IllegalArgumentException> { BookLessonWire.request("ab12", blocks, 7) }
        assertFalse(BookLessonWire.fits(blocks + List(121) { blocks.first() }))
        assertFalse(BookLessonWire.fits(listOf(blocks.first().copy(text = "x".repeat(24_001)))))
    }

    @Test fun escapedPayloadIncludesReceiptInTheExistingHttpBudget() {
        val book = "a".repeat(64)
        val blocks = (1..3).map { n ->
            val text = "\u0001".repeat(7_999) + "x"
            SourceBlock("$book:p$n:0", n, 0, text.length, false, text)
        }
        assertTrue(BookLessonWire.fits(blocks))
        val input = BookLessonWire.request("ab12", blocks, 3)
        val failure = assertFailsWith<IllegalArgumentException> {
            BookLessonWire.withSource(input, buildJsonObject { put("schema", 1) })
        }
        assertEquals(BookLessonWire.TOO_LONG, failure.message)
    }
}

/** Private local probe. Prints numerical receipts only; never writes original text into artifacts. */
object BookLessonBudgetProbe {
    @JvmStatic fun main(args: Array<String>) {
        runBlocking {
            val root = Files.createTempDirectory("lesson-budget-")
            try {
                val library = TextbookLibrary(root.resolve("library"))
                val book = library.importBook(Path.of(args.single()))
                val bodies = book.lessons.map { it.blocks.filterNot(SourceBlock::heading) }
                val wireSizes = bodies.map { blocks ->
                    val wire = BookLessonWire.request("ab12", blocks, book.pages.size)
                    BookLessonWire.withSource(wire, ImportedBookWire(library).run {
                        ExplainConsent(library.directory).allow(book.id); snapshot(blocks)
                    }).toString().toByteArray(Charsets.UTF_8).size
                }
                println("LESSON_BUDGET books=1 pages=${book.pages.size} lessons=${bodies.size} maxBodyChars=${bodies.maxOf { blocks -> blocks.sumOf { it.text.length } }} maxBlocks=${bodies.maxOf { it.size }} maxLessonPages=${bodies.maxOf { blocks -> blocks.map { it.page }.distinct().size }} maxRequestBytes=${wireSizes.max()} overBudget=${bodies.count { !BookLessonWire.fits(it) }}")
            } finally { root.toFile().deleteRecursively() }
        }
        // PDF import initializes AWT. This standalone numerical probe has no window to keep alive.
        kotlin.system.exitProcess(0)
    }
}
