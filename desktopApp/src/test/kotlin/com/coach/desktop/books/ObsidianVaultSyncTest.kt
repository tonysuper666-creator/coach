package com.coach.desktop.books

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObsidianVaultSyncTest {
    @Test fun exportedNoteRoundTripKeepsBothSidesOnConflictAndDoesNotDropADeletedFile() {
        val library = library()
        val vault = Files.createTempDirectory("obsidian-sync-")
        val dbPath = library.directory.resolve("library.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { db ->
            insertNote(db, "b1", "n1", 8, 4, 8, "原句", "库里的批注")
        }
        openLibraryCorpus(library.directory).use { db ->
            syncLibraryCorpus(db)
            val exported = exportObsidianVault(vault, listLibraryRecords(db), listLibraryBooks(db))
            assertTrue(exported is ObsidianExportOutcome.Done)
            rememberObsidianExportBaseline(vault, db)
        }
        val file = vault.resolve("甲书.md")
        val original = Files.readString(file)
        assertTrue(original.contains("库里的批注"), original)

        Files.writeString(file, original.replace("库里的批注", "笔记里改过"))
        val pulled = sync(library, vault)
        assertEquals(1, pulled.pulled)
        assertEquals(0, pulled.conflicts.size)
        assertEquals("笔记里改过", noteText(library, "n1"))

        openLibraryCorpus(library.directory).use { db ->
            db.createStatement().use { it.executeUpdate("UPDATE book_notes SET note='库里改过' WHERE id='n1'") }
        }
        val written = sync(library, vault)
        assertEquals(1, written.writtenBack)
        assertTrue(Files.readString(file).contains("库里改过"))
        assertEquals("库里改过", noteText(library, "n1"))

        val agreed = Files.readString(file)
        Files.writeString(file, agreed.replace("库里改过", "只在笔记里"))
        openLibraryCorpus(library.directory).use { db ->
            db.createStatement().use { it.executeUpdate("UPDATE book_notes SET note='只在库里' WHERE id='n1'") }
        }
        val conflict = sync(library, vault)
        assertEquals(1, conflict.conflicts.size)
        assertTrue(obsidianSyncStatus(conflict).contains("两边都改了，两份都留着"))
        assertEquals("只在库里", noteText(library, "n1"))
        assertTrue(Files.readString(file).contains("只在笔记里"))
        assertFalse(Files.readString(file).contains("只在库里"))
        openLibraryCorpus(library.directory).use { db ->
            val view = loadObsidianSyncView(db)
            assertEquals("只在库里", view.conflicts.single().coachText)
            assertEquals("只在笔记里", view.conflicts.single().obsidianText)
        }

        Files.delete(file)
        val removed = sync(library, vault)
        assertEquals(1, removed.removed)
        assertEquals("只在库里", noteText(library, "n1"))
        openLibraryCorpus(library.directory).use { db ->
            val view = loadObsidianSyncView(db)
            assertTrue("legacy-v1:annotation:n1" in view.deletedKeys)
            assertTrue(view.deletedTitles.contains("甲书"))
        }
    }

    @Test fun plainNotesAttachByConceptOrStayListedAndDoNotDuplicate() {
        val library = library()
        val vault = Files.createTempDirectory("obsidian-plain-")
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { sql ->
                sql.executeUpdate("INSERT INTO collections(id, kind, title, created_at, updated_at) VALUES('c1','BOOKLIST','库',1,1)")
                sql.executeUpdate(
                    "INSERT INTO concept_entities(concept_id, collection_id, canonical_name, aliases_json, muted, shared_note) VALUES('c-opp','c1','机会成本','[]',0,'')",
                )
                sql.executeUpdate(
                    "INSERT INTO concept_entities(concept_id, collection_id, canonical_name, aliases_json, muted, shared_note) VALUES('c-a','c1','甲概念','[]',0,'')",
                )
                sql.executeUpdate(
                    "INSERT INTO concept_entities(concept_id, collection_id, canonical_name, aliases_json, muted, shared_note) VALUES('c-b','c1','乙概念','[]',0,'')",
                )
                sql.executeUpdate(
                    """INSERT INTO concept_mentions(
                        mention_id, concept_id, book_id, page, offset_utf16, quote_anchor, mention_role, claim_summary
                    ) VALUES('m1','c-opp','b1',8,4,'机会成本是放弃的下一件事','PRIMARY_DEF','定义')""",
                )
            }
        }
        Files.createDirectories(vault.resolve(".obsidian"))
        Files.writeString(vault.resolve(".obsidian").resolve("skip.md"), "机会成本不该进来")
        Files.writeString(vault.resolve("机会成本.md"), "# 机会成本\n\n我在笔记里写的解释\n")
        Files.writeString(vault.resolve("杂记.md"), "今天没有对上任何概念")
        Files.writeString(vault.resolve("含糊.md"), "甲概念和乙概念都写在这里")

        val first = sync(library, vault)
        assertEquals(1, first.concepts)
        assertEquals(listOf("杂记", "含糊").sorted(), first.unmatched.sorted())
        openLibraryCorpus(library.directory).use { db ->
            syncLibraryCorpus(db)
            val hit = searchLibrary(db, LibraryQuery(text = "笔记里写的解释")).single()
            assertEquals("b1", hit.bookId)
            assertEquals(8, hit.page)
            assertEquals("机会成本是放弃的下一件事", hit.quote)
            assertEquals(4, hit.startUtf16)
        }
        val again = sync(library, vault)
        assertEquals(0, again.concepts)
        assertEquals(1, countNotes(library, "笔记里写的解释"))

        val noteFile = vault.resolve("机会成本.md")
        Files.writeString(noteFile, "# 机会成本\n\n解释改在笔记里\n")
        val pulled = sync(library, vault)
        assertEquals(1, pulled.pulled)
        assertEquals(1, countNotes(library, "解释改在笔记里"))
        assertEquals(0, countNotes(library, "笔记里写的解释"))

        openLibraryCorpus(library.directory).use { db ->
            db.createStatement().use { it.executeUpdate("UPDATE book_notes SET note='解释改在库里' WHERE note LIKE '%解释改在笔记里%'") }
        }
        val written = sync(library, vault)
        assertEquals(1, written.writtenBack)
        assertTrue(Files.readString(noteFile).contains("解释改在库里"))
    }

    @Test fun twoHundredNotesImportOnceAndStayUnderAMinute() {
        val library = library()
        val vault = Files.createTempDirectory("obsidian-bulk-")
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { sql ->
                sql.executeUpdate("INSERT INTO collections(id, kind, title, created_at, updated_at) VALUES('c1','BOOKLIST','库',1,1)")
                sql.executeUpdate(
                    "INSERT INTO concept_entities(concept_id, collection_id, canonical_name, aliases_json, muted, shared_note) VALUES('c-cost','c1','边际成本','[]',0,'')",
                )
                sql.executeUpdate(
                    """INSERT INTO concept_mentions(
                        mention_id, concept_id, book_id, page, offset_utf16, quote_anchor, mention_role, claim_summary
                    ) VALUES('m1','c-cost','b1',3,0,'边际成本是多生产一件','PRIMARY_DEF','定义')""",
                )
            }
        }
        Files.createDirectories(vault.resolve("nested"))
        Files.createDirectories(vault.resolve(".obsidian"))
        Files.writeString(vault.resolve(".obsidian").resolve("hidden.md"), "边际成本")
        repeat(200) { index ->
            val dir = if (index == 7) vault.resolve("nested") else vault
            val body = if (index < 150) "第 $index 篇提到边际成本。" else "第 $index 篇没有概念。"
            Files.writeString(dir.resolve("note-$index.md"), body)
        }
        val started = System.nanoTime()
        val first = sync(library, vault)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 60_000, "import took ${elapsedMs}ms")
        assertEquals(150, first.concepts)
        assertEquals(50, first.unmatched.size)
        assertEquals(150, countNotes(library, "边际成本"))
        val second = sync(library, vault)
        assertEquals(0, second.concepts)
        assertEquals(150, countNotes(library, "边际成本"))
    }

    @Test fun refusesAFolderInsideGit() {
        val library = library()
        val root = Files.createTempDirectory("obsidian-sync-git-")
        Files.createDirectories(root.resolve(".git"))
        openLibraryCorpus(library.directory).use { db ->
            val outcome = syncObsidianVault(root, db)
            assertTrue(outcome is ObsidianSyncOutcome.Refused)
        }
    }

    private fun sync(library: TextbookLibrary, vault: java.nio.file.Path): ObsidianSyncReport {
        openLibraryCorpus(library.directory).use { db ->
            val outcome = syncObsidianVault(vault, db)
            check(outcome is ObsidianSyncOutcome.Done) { outcome.toString() }
            return outcome.report
        }
    }

    private fun noteText(library: TextbookLibrary, id: String): String {
        openLibraryCorpus(library.directory).use { db ->
            db.prepareStatement("SELECT note FROM book_notes WHERE id=?").use { sql ->
                sql.setString(1, id)
                sql.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    return rows.getString(1)
                }
            }
        }
    }

    private fun countNotes(library: TextbookLibrary, needle: String): Int {
        openLibraryCorpus(library.directory).use { db ->
            db.prepareStatement("SELECT COUNT(*) FROM book_notes WHERE note LIKE ? AND deleted=0").use { sql ->
                sql.setString(1, "%$needle%")
                sql.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    return rows.getInt(1)
                }
            }
        }
    }

    private fun insertNote(db: java.sql.Connection, book: String, id: String, page: Int, start: Int, end: Int, quote: String, note: String) {
        db.prepareStatement(
            """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
               VALUES(?,?, 'v1',?,?,?,?,?,'sha',1,'m',0)""",
        ).use { sql ->
            sql.setString(1, book)
            sql.setString(2, id)
            sql.setInt(3, page)
            sql.setInt(4, start)
            sql.setInt(5, end)
            sql.setString(6, quote)
            sql.setString(7, note)
            sql.executeUpdate()
        }
    }

    private fun library(): TextbookLibrary {
        val root = Files.createTempDirectory("obsidian-sync-lib-")
        val library = TextbookLibrary(root.resolve("library"), todayCardEnabled = false)
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { it.executeUpdate("INSERT INTO books(id,title,format,touched) VALUES('b1','甲书','md',1)") }
        }
        return library
    }
}
