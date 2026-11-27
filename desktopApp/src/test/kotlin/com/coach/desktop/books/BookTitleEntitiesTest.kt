package com.coach.desktop.books

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** HTML escapes in a stored title stay in the database and show as the original characters. */
class BookTitleEntitiesTest {
    @Test fun shelfReaderLibraryAndExportFilenameDecodeHtmlEntities() {
        val root = Files.createTempDirectory("title-entities-")
        val vault = Files.createTempDirectory("title-entities-vault-")
        try {
            val library = TextbookLibrary(root.resolve("library"), todayCardEnabled = false)
            val encoded = "Botany Study &amp; Plot 1"
            val plain = "Law & Economics"
            DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
                db.createStatement().use { sql ->
                    sql.executeUpdate("INSERT INTO books(id,title,format,touched,reading_page) VALUES('b1','$encoded','md',2,1)")
                    sql.executeUpdate("INSERT INTO books(id,title,format,touched,reading_page) VALUES('b2','$plain','md',1,1)")
                    sql.executeUpdate("INSERT INTO pages(book,page,text) VALUES('b1',1,'page one')")
                    sql.executeUpdate("INSERT INTO pages(book,page,text) VALUES('b2',1,'page two')")
                    sql.executeUpdate(
                        """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
                           VALUES('b1','n1','v1',1,0,4,'原句','一条批注','sha',1,'m',0)""",
                    )
                }
            }
            assertEquals("Botany Study & Plot 1", library.catalog().first { it.id == "b1" }.title)
            assertEquals(plain, library.catalog().first { it.id == "b2" }.title)
            assertEquals("Botany Study & Plot 1", library.book("b1").title)
            assertFalse("&amp;" in library.book("b1").title)
            DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
                db.createStatement().executeQuery("SELECT title FROM books WHERE id='b1'").use { rows ->
                    check(rows.next())
                    assertEquals(encoded, rows.getString(1))
                }
            }
            openLibraryCorpus(library.directory).use { connection ->
                syncLibraryCorpus(connection)
                val hit = listLibraryRecords(connection).first { it.bookId == "b1" }
                assertEquals("Botany Study & Plot 1", hit.bookTitle)
                assertFalse("&amp;" in hit.bookTitle || "&#" in hit.bookTitle)
                assertEquals("Botany Study & Plot 1", listLibraryBooks(connection).first { it.id == "b1" }.title)
                val outcome = exportObsidianVault(vault, listLibraryRecords(connection), listLibraryBooks(connection))
                val done = outcome as ObsidianExportOutcome.Done
                assertTrue(done.written >= 1)
                val names = Files.newDirectoryStream(vault, "*.md").use { stream -> stream.map { it.fileName.toString() }.toList() }
                assertTrue(names.any { it == "Botany Study & Plot 1.md" }, names.joinToString())
                assertTrue(names.none { "&amp;" in it || "&#" in it })
            }
        } finally {
            root.toFile().deleteRecursively()
            vault.toFile().deleteRecursively()
        }
    }
}
