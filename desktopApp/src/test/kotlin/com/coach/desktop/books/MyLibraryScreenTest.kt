package com.coach.desktop.books

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import com.coach.ui.CoachTheme
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MyLibraryScreenTest {
    @Test fun shelfOpensLibraryAndHitReturnsToTheSentence() = runSkikoComposeUiTest {
        val root = Files.createTempDirectory("my-library-screen-")
        val library = TextbookLibrary(root.resolve("library"), todayCardEnabled = false)
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { sql ->
                sql.executeUpdate("INSERT INTO books(id,title,format,touched,reading_page) VALUES('b1','甲书','md',1,1)")
                sql.executeUpdate("INSERT INTO pages(book,page,text) VALUES('b1',8,'page')")
                sql.executeUpdate(
                    """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
                       VALUES('b1','n1','v1',8,4,8,'原句','边际成本是多生产一件','sha',1,'m',0)""",
                )
            }
        }
        var opened = ""
        setContent {
            CoachTheme(false) {
                MyLibraryScreen(library, onBack = {}, onOpenBook = { opened = it })
            }
        }
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNodeWithTag("library-hit-legacy-v1-annotation-n1").assertExists() }.isSuccess
        }
        onNodeWithTag("library-search").performTextInput("边际")
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNodeWithTag("library-hit-legacy-v1-annotation-n1").assertExists() }.isSuccess
        }
        onNodeWithTag("library-hit-legacy-v1-annotation-n1").performClick()
        assertEquals("b1", opened)
        assertEquals(8 to 4, library.savedReadSpot("b1"))
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().executeQuery("SELECT reading_page FROM books WHERE id='b1'").use { rows ->
                check(rows.next())
                assertEquals(8, rows.getInt(1))
            }
        }
    }

    @Test fun homeShowsMyLibraryWhenBooksExist() = runSkikoComposeUiTest {
        val root = Files.createTempDirectory("my-library-home-")
        val library = TextbookLibrary(root.resolve("library"), todayCardEnabled = false)
        val summary = BookSummary("b1", "甲书", 1, 1, 1, false)
        setContent {
            CoachTheme(false) {
                LibraryHome(
                    ui = LibraryUi(catalog = listOf(summary), initialized = true),
                    onImport = {},
                    onSelect = {},
                    onRead = {},
                    library = library,
                )
            }
        }
        onNodeWithTag("open-my-library").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNodeWithTag("library-empty").assertExists() }.isSuccess
        }
    }
}
