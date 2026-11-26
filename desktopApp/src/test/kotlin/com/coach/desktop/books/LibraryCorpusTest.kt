package com.coach.desktop.books

import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryCorpusTest {
    @Test fun searchKeepsUserTextAndDropsPageBodySentBlocksAndSecrets() {
        val fixture = fixture()
        val db = openLibraryCorpus(fixture.library.directory)
        db.use { connection ->
            val sync = syncLibraryCorpus(connection)
            assertEquals(5, sync.count)
            assertTrue(sync.rebuilt)
            assertEquals(5, syncLibraryCorpus(connection).count)
            assertFalse(syncLibraryCorpus(connection).rebuilt)

            val marginal = searchLibrary(connection, LibraryQuery(text = "边际成本"))
            assertEquals(listOf("legacy-v1:annotation:n1"), marginal.map { it.key })
            assertTrue(marginal.single().key.startsWith("legacy-v1:"))
            assertEquals(8, marginal.single().page)
            assertEquals(4, marginal.single().startUtf16)

            val highlight = searchLibrary(connection, LibraryQuery(text = "HIGHLIGHT_ONLY"))
            assertEquals(LibraryKind.HIGHLIGHT, highlight.single().kind)

            val english = searchLibrary(connection, LibraryQuery(text = "opportunity"))
            assertEquals("乙书", english.single().bookTitle)

            assertTrue(searchLibrary(connection, LibraryQuery(text = "SECRET_PAGE_BODY")).isEmpty())
            assertTrue(searchLibrary(connection, LibraryQuery(text = "SECRET_SENT_BLOCK")).isEmpty())
            assertTrue(searchLibrary(connection, LibraryQuery(text = "SECRET_REFERENCE")).isEmpty())
            assertTrue(searchLibrary(connection, LibraryQuery(text = "DEVICE-SECRET")).isEmpty())
            assertTrue(searchLibrary(connection, LibraryQuery(text = "不该出现")).isEmpty())

            val onlyHighlight = searchLibrary(connection, LibraryQuery(kind = LibraryKind.HIGHLIGHT))
            assertEquals(1, onlyHighlight.size)
            val onlySecond = searchLibrary(connection, LibraryQuery(bookTitleContains = "乙书"))
            assertEquals(listOf("乙书"), onlySecond.map { it.bookTitle }.distinct())
            val recent = searchLibrary(connection, LibraryQuery(sinceMs = 1_700_000_000_000L))
            assertTrue(recent.any { it.kind == LibraryKind.QUESTION })
            assertTrue(recent.none { it.kind == LibraryKind.ANNOTATION })
        }
        openLibraryCorpus(fixture.library.directory).use { connection ->
            val hit = searchLibrary(connection, LibraryQuery(text = "边际成本")).single()
            assertTrue(stageLibraryJump(fixture.library, hit))
            connection.createStatement().use { sql ->
                sql.executeQuery("SELECT reading_page FROM books WHERE id='b1'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(8, rows.getInt(1))
                }
            }
        }
        val spot = fixture.library.savedReadSpot("b1")
        assertEquals(8 to 4, spot)
    }

    @Test fun obsidianExportKeepsUserLinesAndOmitsSecrets() {
        val fixture = fixture()
        val vault = Files.createTempDirectory("obsidian-vault-")
        val first = export(fixture, vault, Instant.parse("2026-11-26T08:00:00Z"))
        assertEquals(2, first.written)
        assertEquals(0, first.skipped)
        val jia = vault.resolve("甲书.md")
        val yi = vault.resolve("乙书.md")
        val jiaText = Files.readString(jia)
        assertTrue(jiaText.contains("title: \"甲书\""))
        assertTrue(jiaText.contains("author: \"\""))
        assertTrue(jiaText.contains("exported_at: \"2026-11-26T08:00:00Z\""))
        assertTrue(jiaText.contains("coach_record_id: \"legacy-v1:book:b1\""))
        assertTrue(jiaText.contains("coach_id_scheme: \"legacy-v1\""))
        assertTrue(jiaText.contains("[[甲书]]"))
        assertTrue(jiaText.contains("[[乙书]]"))
        assertTrue(jiaText.contains("边际成本是多生产一件"))
        assertTrue(jiaText.contains("原句"))
        assertTrue(jiaText.contains(OBSIDIAN_MANAGED_BEGIN))
        assertTrue(jiaText.contains(OBSIDIAN_MANAGED_END))
        assertFalse(jiaText.contains("HIGHLIGHT_ONLY"), jiaText)
        assertFalse(jiaText.contains("SECRET_PAGE_BODY"), jiaText)
        assertFalse(jiaText.contains("SECRET_SENT_BLOCK"), jiaText)
        assertFalse(jiaText.contains("SECRET_REFERENCE"), jiaText)
        assertFalse(jiaText.contains("DEVICE-SECRET"), jiaText)
        assertFalse(jiaText.contains("http://"), jiaText)
        assertFalse(jiaText.contains("不该出现"), jiaText)
        assertTrue(Files.readString(yi).contains("opportunity cost"))

        val edited = jiaText + "我自己加的一行\n"
        Files.writeString(jia, edited)
        val before = Files.readAllBytes(jia)
        val second = export(fixture, vault, Instant.parse("2026-11-26T09:00:00Z"))
        assertTrue(second.skipped >= 1)
        assertTrue(before.contentEquals(Files.readAllBytes(jia)))

        openLibraryCorpus(fixture.library.directory).use { connection ->
            connection.createStatement().use {
                it.executeUpdate("UPDATE book_notes SET note='改过的批注' WHERE id='n1'")
            }
        }
        val third = export(fixture, vault, Instant.parse("2026-11-26T10:00:00Z"))
        assertTrue(third.written >= 1)
        val rewritten = Files.readString(jia)
        assertTrue(rewritten.contains("我自己加的一行"), rewritten)
        assertTrue(rewritten.contains("改过的批注"), rewritten)
        assertFalse(rewritten.contains("边际成本是多生产一件"), rewritten)
        assertFalse(rewritten.contains("DEVICE-SECRET"), rewritten)
    }

    @Test fun refusesAFolderInsideGit() {
        val root = Files.createTempDirectory("obsidian-git-")
        Files.createDirectories(root.resolve(".git"))
        val outcome = exportObsidianVault(root, emptyList(), emptyList())
        assertTrue(outcome is ObsidianExportOutcome.Refused)
    }

    private fun export(fixture: Fixture, vault: java.nio.file.Path, now: Instant): ObsidianExportOutcome.Done {
        openLibraryCorpus(fixture.library.directory).use { connection ->
            syncLibraryCorpus(connection)
            val outcome = exportObsidianVault(vault, listLibraryRecords(connection), listLibraryBooks(connection), now)
            return outcome as ObsidianExportOutcome.Done
        }
    }

    private class Fixture(val library: TextbookLibrary)

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("library-corpus-")
        val library = TextbookLibrary(root.resolve("library"), todayCardEnabled = false)
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { sql ->
                sql.executeUpdate("INSERT INTO books(id,title,format,touched) VALUES('b1','甲书','md',1)")
                sql.executeUpdate("INSERT INTO books(id,title,format,touched) VALUES('b2','乙书','md',1)")
                sql.executeUpdate("INSERT INTO pages(book,page,text) VALUES('b1',8,'SECRET_PAGE_BODY that must stay in the library')")
                sql.executeUpdate(
                    """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
                       VALUES('b1','n1','v1',8,4,8,'原句','边际成本是多生产一件','sha',1,'m',0)""",
                )
                sql.executeUpdate(
                    """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
                       VALUES('b1','gone','v1',1,0,1,'删掉的','不该出现','sha',1,'m2',1)""",
                )
                sql.executeUpdate("INSERT INTO book_highlights(book,page,start,end,quote) VALUES('b1',8,0,3,'HIGHLIGHT_ONLY')")
                sql.executeUpdate(
                    """INSERT INTO book_ask_turns(
                        book,thread_id,selection_id,selection_page,selection_start,selection_end,selection_text,
                        label,question_text,answer_text,citations_json,sent_blocks_json,created_at
                    ) VALUES('b1','t1','s1',8,0,2,'选中句','问','这句什么意思，对照乙书','一句摘要','[]','SECRET_SENT_BLOCK',1700000000000)""",
                )
                sql.executeUpdate(
                    """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
                       VALUES('b2','n2','v1',3,0,4,'乙句','opportunity cost 和乙书','sha',1,'m3',0)""",
                )
                sql.executeUpdate(
                    """INSERT INTO book_reviews(
                        book,lesson,mode,book_title,lesson_title,prompt,reference,solution,options,wrong_text,
                        answer_text,revealed,understood,completed,guessed
                    ) VALUES(
                        'b1','lesson-8','CHOICE','甲书','第八节','题干原文','SECRET_REFERENCE','正确项','[]','错误项',
                        '错误项',1,NULL,0,0
                    )""",
                )
                sql.executeUpdate("INSERT INTO book_toc_nodes(book,id,ordinal,level,kind,title,start_page,end_page,source) VALUES('b1','lesson-8',1,1,'chapter','第八节',8,9,'TEST')")
                sql.executeUpdate(
                    """INSERT INTO book_upload_queue(account_key,local_book_id,file_sha256,format,title,state,error_message,updated_at)
                       VALUES('DEVICE-SECRET-9f3a','b1','abc','md','甲书','FAILED','http://secret.example/pair',1)""",
                )
            }
        }
        return Fixture(library)
    }
}
