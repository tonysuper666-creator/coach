package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class ImportedBookWireTest {
    @Test fun consentAndOriginalAndSavedUtf16RangeAreRequiredWithoutSendingRestOfPage() = runBlocking {
        val root = Files.createTempDirectory("synthetic-import-receipt-")
        try {
            val source = root.resolve("self-authored.txt")
            Files.writeString(source, "自写🌱前言。\n\nSynthetic selected text.\n\nUNSENT_PRIVATE_PAGE_SUFFIX.")
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(source)
            val page = book.pages.first()
            val text = "Synthetic selected text."
            val start = page.text.indexOf(text)
            val block = SourceBlock("${book.id}:p1:$start", 1, start, start + text.length, false, text)
            ReadingSettings(library.directory).chooseAiExplain(false)
            val wire = ImportedBookWire(library)
            assertFailsWith<IllegalArgumentException> { wire.snapshot(listOf(block)) }
            ExplainConsent(library.directory).allow(book.id)
            val snapshot = wire.snapshot(listOf(block))
            assertEquals(page.text.length, snapshot.getValue("pages").jsonArray.single().jsonObject.getValue("length").jsonPrimitive.int)
            assertFalse(snapshot.toString().contains("UNSENT_PRIVATE_PAGE_SUFFIX"))
            assertEquals(snapshot, ImportedBookWire(TextbookLibrary(library.directory)).snapshot(listOf(block)))
            assertFailsWith<IllegalArgumentException> { wire.snapshot(listOf(block.copy(start = start - 1))) }
            assertFailsWith<IllegalArgumentException> { wire.snapshot(listOf(block.copy(text = "Forged selected text."))) }
            assertFailsWith<IllegalArgumentException> { wire.snapshot(listOf(block.copy(page = 2))) }
            Files.writeString(library.directory.resolve("${book.id}.${book.format}"), "Changed synthetic original")
            assertFailsWith<IllegalArgumentException> { wire.snapshot(listOf(block)) }
            Unit
        } finally { root.toFile().deleteRecursively() }
    }
}
