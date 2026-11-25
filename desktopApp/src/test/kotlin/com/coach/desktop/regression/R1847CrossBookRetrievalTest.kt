package com.coach.desktop.regression

import com.coach.desktop.books.BookChapterResolver
import com.coach.desktop.books.CollectionKind
import com.coach.desktop.books.CollectionMaterialRecord
import com.coach.desktop.books.CollectionQaRetriever
import com.coach.desktop.books.MaterialCollectionRecord
import com.coach.desktop.books.TextbookLibrary
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TON-1847: a comparison question over two read books must cite both books,
 * even when the question shares no lexical tokens with either page.
 */
class R1847CrossBookRetrievalTest {
    @Test
    fun comparisonQuestionCitesEveryReadBook() {
        val root = Files.createTempDirectory("coach-r1847-compare-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val smith = library.importBook(
                root.resolve("smith.md").also {
                    Files.writeString(
                        it,
                        "# Chapter 1 Division\n\nThe pin factory shows how tasks split across workers.\n",
                    )
                },
            )
            val malthus = library.importBook(
                root.resolve("malthus.md").also {
                    Files.writeString(
                        it,
                        "# Chapter 1 Population\n\nSubsistence limits how many people a place can feed.\n",
                    )
                },
            )
            val booklistId = "booklist-compare"
            library.upsertCollection(
                MaterialCollectionRecord(id = booklistId, kind = CollectionKind.BOOKLIST, title = "Read list"),
            )
            library.attachMaterial(CollectionMaterialRecord(booklistId, smith.id, ordinal = 0))
            library.attachMaterial(CollectionMaterialRecord(booklistId, malthus.id, ordinal = 1))
            for (book in listOf(smith, malthus)) {
                val chapter = BookChapterResolver.chapters(book).first()
                library.setChapterManualReadState(book.id, chapter.tocNodeId, chapter.pagesTotal, read = true)
            }
            val bundle = CollectionQaRetriever.retrieve(
                library,
                booklistId,
                "请对照两位作者对分工与人口的看法",
            )
            val cited = bundle.blocks.map { it.bookId }.toSet()
            assertTrue(smith.id in cited, "Smith was missing from $cited")
            assertTrue(malthus.id in cited, "Malthus was missing from $cited")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
