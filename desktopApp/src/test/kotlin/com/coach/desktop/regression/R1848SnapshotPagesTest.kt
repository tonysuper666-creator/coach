package com.coach.desktop.regression

import com.coach.desktop.books.ExplainConsent
import com.coach.desktop.books.ImportedBookWire
import com.coach.desktop.books.TextbookLibrary
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * TON-1848: an explain snapshot reads the requested page instead of the whole book.
 */
class R1848SnapshotPagesTest {
    @Test
    fun snapshotMetaContainsOnlyTheRequestedPage() {
        val root = Files.createTempDirectory("coach-r1848-snap-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(
                root.resolve("two.md").also {
                    Files.writeString(
                        it,
                        "# Chapter 1 Opening\n\nThe requested page is the only page the snapshot needs.\n\n# Chapter 2 Later\n\nThis later page must stay out of the snapshot lookup.\n",
                    )
                },
            )
            val block = book.sourceBlocks().first { it.text.contains("requested page") }
            val metaMethod = library.javaClass.methods.firstOrNull { it.name == "bookSnapshotMeta" && it.parameterCount == 2 }
            assertNotNull(metaMethod, "explain still loads the whole book; bookSnapshotMeta is missing")
            val meta = metaMethod.invoke(library, book.id, setOf(block.page))
            assertNotNull(meta)
            @Suppress("UNCHECKED_CAST")
            val pages = meta.javaClass.getMethod("getPages").invoke(meta) as Map<Int, *>
            assertEquals(setOf(block.page), pages.keys)
            ExplainConsent(library.directory).allow(book.id)
            val snapshot = ImportedBookWire(library).snapshot(listOf(block))
            val numbers = snapshot.getValue("pages").jsonArray.map { it.jsonObject.getValue("number") }
            assertEquals(1, numbers.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
