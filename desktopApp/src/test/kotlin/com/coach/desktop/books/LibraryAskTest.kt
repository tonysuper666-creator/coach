package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 在库里提问：对得上的句子能回到原句，对不上的直说没有。书页是合成短句，不是教材正文。 */
class LibraryAskTest {
    @Test fun twentyQuestionsStayOnTheSentenceOrSayTheyAreMissing() {
        val root = Files.createTempDirectory("library-ask-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            seed(library)
            val found = listOf(
                Expect("opportunity cost", "Open Principles", 12, "Opportunity cost is the value"),
                Expect("scarcity", "Open Principles", 12, "Scarcity forces"),
                Expect("comparative advantage", "Open Principles", 12, "Comparative advantage explains"),
                Expect("price ceiling", "Open Micro", 40, "price ceiling set below"),
                Expect("deadweight loss", "Open Micro", 40, "Deadweight loss is the reduction"),
                Expect("consumer surplus", "Open Micro", 40, "Consumer surplus is the gap"),
                Expect("marginal cost", "Open Principles", 13, "Marginal cost is the extra"),
                Expect("diminishing returns", "Open Principles", 13, "Diminishing returns set in"),
                Expect("equilibrium price", "Open Micro", 40, "equilibrium price creates"),
                Expect("shortage from a price ceiling", "Open Micro", 40, "creates a shortage"),
                Expect("willingness to pay", "Open Micro", 40, "willingness to pay"),
                Expect("total surplus", "Open Micro", 40, "total surplus from a tax"),
                Expect("next best alternative", "Open Principles", 12, "next best alternative"),
                Expect("specialize and then trade", "Open Principles", 12, "specialize and then trade"),
            )
            var jumps = 0
            for (item in found) {
                val answer = ask(library, item.question)
                assertEquals(LibraryAskKind.FOUND, answer.kind, item.question)
                assertFalse(answer.searchedNotes, item.question)
                val hit = answer.claims.firstOrNull {
                    it.bookTitle == item.title && it.page == item.page && item.snippet in it.quote
                }
                assertNotNull(hit, "${item.question} -> ${answer.claims.map { it.quote }}")
                val page = pageText(library, hit.bookId, hit.page)
                assertTrue(hit.endUtf16 <= page.length && hit.startUtf16 >= 0)
                assertEquals(hit.quote, page.substring(hit.startUtf16, hit.endUtf16), item.question)
                jumps++
            }
            assertTrue(jumps >= 14, "jumps=$jumps")
            val noted = ask(library, "机会成本是什么")
            assertEquals(LibraryAskKind.FOUND, noted.kind)
            val note = noted.claims.firstOrNull { it.source == LibraryAskSource.ANNOTATION }
            assertNotNull(note, noted.claims.toString())
            assertTrue("机会成本" in note.claim)
            assertTrue(note.quote.startsWith("Opportunity cost"))
            assertEquals(note.quote, pageText(library, note.bookId, note.page).substring(note.startUtf16, note.endUtf16))
            val missing = listOf(
                "quantum entanglement",
                "where are mitochondria",
                "world cup final",
                "capital of France",
                "who invented the telephone",
            )
            for (question in missing) {
                val answer = ask(library, question)
                assertEquals(LibraryAskKind.MISSING, answer.kind, question)
                assertTrue(answer.claims.isEmpty(), question)
                assertEquals(LibraryAsk.MISSING, LibraryAsk.missingCopy(answer))
            }
            assertEquals(LibraryAskKind.VAGUE, ask(library, "这是什么").kind)
            assertEquals(LibraryAskKind.MISSING, ask(library, "scarcity ceiling").kind)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun definitionBeatsContentsTitleAndFragment() {
        val root = Files.createTempDirectory("library-ask-definition-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val definition = "A velvet spindle is defined as the shaft that turns inside a toy mill and carries the thread."
            val page = listOf(
                "Velvet spindle  14",
                "Velvet Spindle",
                "• Explain how a velvet spindle works in the toy mill today.",
                "velvet spindle.",
                "A velvet spindle shows up all over the workshop and the yard.",
                definition,
            ).joinToString("\n")
            DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
                db.prepareStatement("INSERT INTO books(id,title,format,touched,reading_page) VALUES(?,?, 'md', 1, 1)").use { sql ->
                    sql.setString(1, "mill")
                    sql.setString(2, "Open Mill")
                    sql.executeUpdate()
                }
                db.prepareStatement("INSERT INTO pages(book,page,text) VALUES(?,?,?)").use { sql ->
                    sql.setString(1, "mill")
                    sql.setInt(2, 14)
                    sql.setString(3, page)
                    sql.executeUpdate()
                    sql.setInt(2, 3)
                    sql.setString(3, "Pewter cistern  3\nPewter Cistern\npewter cistern.")
                    sql.executeUpdate()
                }
            }
            val answer = ask(library, "velvet spindle")
            assertEquals(LibraryAskKind.FOUND, answer.kind)
            assertEquals(1, answer.claims.size)
            val hit = answer.claims.single()
            assertEquals(definition, hit.quote)
            assertEquals(definition, pageText(library, hit.bookId, hit.page).substring(hit.startUtf16, hit.endUtf16))
            assertEquals(LibraryAskKind.MISSING, ask(library, "pewter cistern").kind)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun notesJoinWhenTheTableExistsAndDeletedAnnotationsStayOut() {
        val root = Files.createTempDirectory("library-ask-notes-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            seed(library)
            val hidden = ask(library, "删注暗号")
            assertEquals(LibraryAskKind.MISSING, hidden.kind)
            openLibraryCorpus(library.directory).use { db ->
                db.createStatement().use { sql ->
                    sql.execute(
                        """CREATE TABLE library_notes(
                            book_id TEXT, page INTEGER, start_utf16 INTEGER, quote TEXT, body TEXT, deleted INTEGER
                        )""",
                    )
                }
                db.prepareStatement("INSERT INTO library_notes(book_id,page,start_utf16,quote,body,deleted) VALUES(?,?,?,?,?,0)").use { sql ->
                    sql.setString(1, "principles")
                    sql.setInt(2, 12)
                    sql.setInt(3, 0)
                    sql.setString(4, PRINCIPLES_12.substringBefore('.'))
                    sql.setString(5, "这则笔记把钠钾泵当成一个记号。")
                    sql.executeUpdate()
                }
            }
            val answer = ask(library, "钠钾泵")
            assertEquals(LibraryAskKind.FOUND, answer.kind)
            assertTrue(answer.searchedNotes)
            val note = answer.claims.single()
            assertEquals(LibraryAskSource.NOTE, note.source)
            assertTrue("钠钾泵" in note.shownText())
            assertEquals(note.quote, pageText(library, note.bookId, note.page).substring(note.startUtf16, note.endUtf16))
            assertEquals(LibraryAsk.MISSING_WITH_NOTES, LibraryAsk.missingCopy(ask(library, "quantum entanglement")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun proseKeepsTheLocalOffsetAndDropsAMadeUpQuote() {
        val quote = "Opportunity cost is the value of the next best alternative that is given up."
        val local = LibraryAskClaim(
            claim = quote,
            bookId = "principles",
            bookTitle = "Open Principles",
            page = 12,
            startUtf16 = 4,
            endUtf16 = 4 + quote.length,
            quote = quote,
            source = LibraryAskSource.PAGE,
        )
        val accepted = LibraryAsk.acceptProse(
            buildJsonObject {
                put("sentences", buildJsonArray {
                    add(buildJsonObject {
                        put("claim", "Opportunity cost means the next best alternative is given up.")
                        put("blockId", "B1")
                        put("quoteAnchor", "next best alternative")
                    })
                    add(buildJsonObject {
                        put("claim", "材料里没有这一句。")
                        put("blockId", "B1")
                        put("quoteAnchor", "next best alternative")
                    })
                    add(buildJsonObject {
                        put("claim", "A fabricated sentence about opportunity cost.")
                        put("blockId", "B1")
                        put("quoteAnchor", "this phrase is not on the page at all")
                    })
                })
            },
            listOf(local),
        )
        assertEquals(1, accepted.size)
        assertEquals("Opportunity cost means the next best alternative is given up.", accepted[0].claim)
        assertEquals(4, accepted[0].startUtf16)
        assertEquals(4 + quote.length, accepted[0].endUtf16)
        assertEquals(quote, accepted[0].quote)
    }

    @Test fun unreachablePolishDoesNotInventSentences() = runBlocking {
        val quote = "Scarcity forces every choice to leave something else undone."
        val local = LibraryAskClaim("x", "principles", "Open Principles", 12, 0, quote.length, quote, LibraryAskSource.PAGE)
        val thrown = LibraryAsk.polish("scarcity", listOf(local)) { error("down") }
        assertTrue(thrown.unreachable)
        assertTrue(thrown.claims.isEmpty())
        val offline = LibraryAsk.polish("scarcity", listOf(local)) {
            CourseServerCallResult(null, "down", ExplainTrouble.Unreachable)
        }
        assertTrue(offline.unreachable)
        assertTrue(offline.claims.isEmpty())
        val other = LibraryAsk.polish("scarcity", listOf(local)) {
            CourseServerCallResult(null, "later", ExplainTrouble.Other)
        }
        assertFalse(other.unreachable)
        assertTrue(other.claims.isEmpty())
    }

    @Test fun missingPageDoesNotStageAJump() {
        val root = Files.createTempDirectory("library-ask-jump-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
                db.createStatement().use { sql ->
                    sql.executeUpdate("INSERT INTO books(id,title,format,touched,reading_page) VALUES('b1','甲书','md',1,1)")
                    sql.executeUpdate("INSERT INTO pages(book,page,text) VALUES('b1',8,'page body')")
                }
            }
            assertFalse(stagePassageJump(library, "b1", 99, 0, 4, "page"))
            assertEquals(null, library.peekQuoteJump("b1"))
            assertTrue(stagePassageJump(library, "b1", 8, 0, 4, "page"))
            val jump = library.peekQuoteJump("b1")
            assertNotNull(jump)
            assertEquals(8, jump.page)
            assertEquals(0, jump.start)
            assertEquals(4, jump.end)
            assertEquals("page", jump.text)
            assertEquals(8 to 0, library.savedReadSpot("b1"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private data class Expect(val question: String, val title: String, val page: Int, val snippet: String)

    private fun ask(library: TextbookLibrary, question: String): LibraryAskAnswer =
        openLibraryCorpus(library.directory).use { LibraryAsk.answer(it, question) }

    private fun pageText(library: TextbookLibrary, bookId: String, page: Int): String =
        openLibraryCorpus(library.directory).use { db ->
            db.prepareStatement("SELECT text FROM pages WHERE book=? AND page=?").use { sql ->
                sql.setString(1, bookId)
                sql.setInt(2, page)
                sql.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getString(1)
                }
            }
        }

    private fun seed(library: TextbookLibrary) {
        val opportunity = PRINCIPLES_12.substringBefore('.')
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.prepareStatement("INSERT INTO books(id,title,format,touched,reading_page) VALUES(?,?, 'md', 1, 1)").use { sql ->
                sql.setString(1, "principles")
                sql.setString(2, "Open Principles")
                sql.executeUpdate()
                sql.setString(1, "micro")
                sql.setString(2, "Open Micro")
                sql.executeUpdate()
            }
            db.prepareStatement("INSERT INTO pages(book,page,text) VALUES(?,?,?)").use { sql ->
                sql.setString(1, "principles")
                sql.setInt(2, 12)
                sql.setString(3, PRINCIPLES_12)
                sql.executeUpdate()
                sql.setInt(2, 13)
                sql.setString(3, PRINCIPLES_13)
                sql.executeUpdate()
                sql.setString(1, "micro")
                sql.setInt(2, 40)
                sql.setString(3, MICRO_40)
                sql.executeUpdate()
            }
            db.prepareStatement(
                """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
            ).use { sql ->
                sql.setString(1, "principles")
                sql.setString(2, "n-live")
                sql.setString(3, "v1")
                sql.setInt(4, 12)
                sql.setInt(5, 0)
                sql.setInt(6, opportunity.length)
                sql.setString(7, opportunity)
                sql.setString(8, "我的批注：机会成本就是放弃的那个选项。")
                sql.setString(9, "sha")
                sql.setInt(10, 1)
                sql.setString(11, "m1")
                sql.setInt(12, 0)
                sql.executeUpdate()
                sql.setString(2, "n-dead")
                sql.setString(8, "删注暗号不该被找出来。")
                sql.setString(11, "m2")
                sql.setInt(12, 1)
                sql.executeUpdate()
            }
        }
    }

    private companion object {
        const val PRINCIPLES_12 =
            "Opportunity cost is the value of the next best alternative that is given up. " +
                "Scarcity forces every choice to leave something else undone. " +
                "Comparative advantage explains why people specialize and then trade."
        const val PRINCIPLES_13 =
            "Marginal cost is the extra cost of producing one more unit. " +
                "Diminishing returns set in when extra labor adds less output."
        const val MICRO_40 =
            "A price ceiling set below the equilibrium price creates a shortage. " +
                "Deadweight loss is the reduction in total surplus from a tax or a quota. " +
                "Consumer surplus is the gap between willingness to pay and the price actually paid."
    }
}
