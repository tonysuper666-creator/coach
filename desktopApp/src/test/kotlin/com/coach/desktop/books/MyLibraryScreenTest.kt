package com.coach.desktop.books

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runSkikoComposeUiTest
import com.coach.ui.CoachTheme
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    @Test fun offlineAskShowsTheSentenceAndJumpsWithoutCallingOut() = runSkikoComposeUiTest {
        val root = Files.createTempDirectory("library-ask-offline-")
        val library = TextbookLibrary(root.resolve("library"))
        val page = "Opportunity cost is the value of the next best alternative that is given up. Scarcity forces every choice to leave something else undone."
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { sql ->
                sql.executeUpdate("INSERT INTO books(id,title,format,touched,reading_page) VALUES('b1','Open Principles','md',1,1)")
                sql.executeUpdate("INSERT INTO pages(book,page,text) VALUES('b1',12,'${page.replace("'", "''")}')")
            }
        }
        val remote = AtomicInteger()
        var opened = ""
        setContent {
            CoachTheme(false) {
                CompositionLocalProvider(LocalCoachConnectivity provides CoachConnectivityStatus.OFFLINE) {
                    MyLibraryScreen(library, onBack = {}, onOpenBook = { opened = it }, askOnline = {
                        remote.incrementAndGet()
                        error("offline")
                    })
                }
            }
        }
        onNodeWithTag("library-ask").performTextInput("opportunity cost")
        onNodeWithTag("library-ask-submit").performClick()
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNodeWithTag("library-ask-cite-0").assertExists() }.isSuccess
        }
        onNodeWithTag("library-ask-claim-0").assertTextContains("Opportunity cost", substring = true)
        onNodeWithTag("library-ask-network").assertExists()
        assertEquals(0, remote.get())
        onNodeWithTag("library-ask-cite-0").performClick()
        assertEquals("b1", opened)
        assertEquals(12, library.savedReadSpot("b1")?.first)
        val jump = library.peekQuoteJump("b1")
        assertNotNull(jump)
        assertEquals(12, jump.page)
        assertTrue(jump.text.startsWith("Opportunity cost"))
        assertEquals(jump.text, page.substring(jump.start, jump.end))
    }

    @Test fun missingQuestionSaysSoAndAVagueOneAsksForTheConcept() = runSkikoComposeUiTest {
        val root = Files.createTempDirectory("library-ask-missing-")
        val library = TextbookLibrary(root.resolve("library"))
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { sql ->
                sql.executeUpdate("INSERT INTO books(id,title,format,touched,reading_page) VALUES('b1','Open Principles','md',1,1)")
                sql.executeUpdate("INSERT INTO pages(book,page,text) VALUES('b1',12,'Opportunity cost is the value of the next best alternative that is given up.')")
            }
        }
        setContent {
            CoachTheme(false) {
                CompositionLocalProvider(LocalCoachConnectivity provides CoachConnectivityStatus.OFFLINE) {
                    MyLibraryScreen(library, onBack = {}, onOpenBook = {})
                }
            }
        }
        onNodeWithTag("library-ask").performTextInput("这是什么")
        onNodeWithTag("library-ask-submit").performClick()
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNodeWithTag("library-ask-vague").assertExists() }.isSuccess
        }
        onNodeWithTag("library-ask").performTextReplacement("quantum entanglement")
        onNodeWithTag("library-ask-submit").performClick()
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onNodeWithTag("library-ask-missing").assertExists() }.isSuccess
        }
    }

    @Test fun onlineAskRewritesTheSentenceAndStillJumpsToTheLocalOffset() = runSkikoComposeUiTest {
        val root = Files.createTempDirectory("library-ask-online-")
        val library = TextbookLibrary(root.resolve("library"))
        val page = "Opportunity cost is the value of the next best alternative that is given up."
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { sql ->
                sql.executeUpdate("INSERT INTO books(id,title,format,touched,reading_page) VALUES('b1','Open Principles','md',1,1)")
                sql.executeUpdate("INSERT INTO pages(book,page,text) VALUES('b1',12,'${page.replace("'", "''")}')")
            }
        }
        val remote = AtomicInteger()
        var opened = ""
        setContent {
            CoachTheme(false) {
                MyLibraryScreen(library, onBack = {}, onOpenBook = { opened = it }, askOnline = {
                    remote.incrementAndGet()
                    CourseServerCallResult(
                        buildJsonObject {
                            put("sentences", buildJsonArray {
                                add(buildJsonObject {
                                    put("claim", "Opportunity cost means the next best alternative is given up.")
                                    put("blockId", "B1")
                                    put("quoteAnchor", "next best alternative")
                                })
                            })
                        },
                        null,
                    )
                })
            }
        }
        onNodeWithTag("library-ask").performTextInput("opportunity cost")
        onNodeWithTag("library-ask-submit").performClick()
        waitUntil(timeoutMillis = 10_000) {
            runCatching {
                onNodeWithTag("library-ask-claim-0").assertTextContains("means the next best alternative", substring = true)
            }.isSuccess
        }
        assertEquals(1, remote.get())
        onNodeWithTag("library-ask-cite-0").performClick()
        assertEquals("b1", opened)
        val jump = library.peekQuoteJump("b1")
        assertNotNull(jump)
        assertEquals(page, jump.text)
        assertEquals(0, jump.start)
        assertEquals(page.length, jump.end)
    }
}
