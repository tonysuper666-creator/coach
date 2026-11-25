package com.coach.desktop.regression

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runSkikoComposeUiTest
import com.coach.desktop.HostSearchDialog
import com.coach.desktop.books.BookSummary
import com.coach.desktop.books.CollectionQaRetriever
import com.coach.desktop.books.TextbookLibrary
import com.coach.ui.CoachTheme
import com.coach.ui.v7.LocalV7ReducedMotion
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TON-1847: a standalone imported book can be asked through the virtual read-library scope.
 */
@OptIn(ExperimentalTestApi::class)
class R1847ReadLibraryScopeTest {
    @Test
    fun standaloneBookExposesReadLibraryScope() {
        val root = Files.createTempDirectory("coach-r1847-scope-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(
                root.resolve("call.md").also {
                    Files.writeString(
                        it,
                        "# Chapter 1 Tasks\n\nSpecialisation of the pin factory is the running example.\n",
                    )
                },
            )
            val bundle = CollectionQaRetriever.retrieve(
                library,
                "__virtual_read_library__",
                "specialisation",
            )
            assertEquals("已读书目", bundle.collection.title)
            assertTrue(bundle.blocks.any { it.bookId == book.id }, "standalone book was not retrieved")
            val summary = library.catalog().single()
            runSkikoComposeUiTest(size = Size(900f, 700f)) {
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                            HostSearchDialog(
                                library = library,
                                catalog = listOf(
                                    BookSummary(
                                        id = summary.id,
                                        title = summary.title,
                                        pageCount = summary.pageCount,
                                        lastPage = summary.lastPage,
                                        readingPage = summary.readingPage,
                                        learning = summary.learning,
                                    ),
                                ),
                                collections = emptyList(),
                                onOpenBook = { _, _ -> },
                                onOpenCollection = {},
                                onClose = {},
                            )
                        }
                    }
                }
                waitForIdle()
                onNodeWithTag("host-search-col-__virtual_read_library__").assertIsDisplayed()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
