package com.coach.store

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class SourcePageStoreTest {
    @Test fun openingTheDeployedAlphaV5RepairsAnEmptyIndexAndMigratesChatHistory() {
        for (empty in listOf(true,false)) {
            val dir=Files.createTempDirectory("source-alpha-v5-")
            try {
                LedgerStore(dir).use { it.registerSourcePage("c","book","Synthetic book",1,"Adverse selection") }
                DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db -> db.createStatement().use { s ->
                    s.execute("CREATE TABLE IF NOT EXISTS pool_details(question_id TEXT PRIMARY KEY REFERENCES questions(question_id), pack_hash TEXT NOT NULL, explanation TEXT NOT NULL, source_quote TEXT NOT NULL, generator TEXT NOT NULL, reviewer TEXT NOT NULL)")
                    s.executeQuery("PRAGMA table_info(pool_details)").use { columns ->
                        val names = buildSet { while (columns.next()) add(columns.getString(2)) }
                        assertEquals(setOf("question_id","pack_hash","explanation","source_quote","generator","reviewer"),names)
                    }
                    for(table in listOf("fts_en","fts_zh")) {
                        for(kind in listOf("insert","delete","update")) s.execute("DROP TRIGGER ${table}_$kind")
                        if (empty) s.execute("DELETE FROM $table")
                    }
                    // Reproduce a same-sized stale index as well as the empty production index.
                    s.execute("UPDATE source_pages SET text='Moral hazard and 逆向选择' WHERE doc_id='book'")
                    s.execute("PRAGMA user_version=5")
                } }
                repeat(2) { // Repair and reopening must both retain working imports and version ownership.
                    LedgerStore(dir).use { store ->
                        assertEquals(listOf("book"),store.searchSourcePages("c","moral hazard").map { it.docId })
                        assertEquals(listOf("book"),store.searchSourcePages("c","逆向选择").map { it.docId })
                        assertEquals(emptyList(),store.searchSourcePages("c","adverse selection"))
                        DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db -> db.createStatement().use { s ->
                            s.executeQuery("PRAGMA user_version").use { r -> assertTrue(r.next()); assertEquals(6,r.getInt(1)) }
                            s.executeQuery("SELECT count(*) FROM pool_details").use { r -> assertTrue(r.next()); assertEquals(0,r.getInt(1)) }
                        } }
                    }
                }
                LedgerStore(dir).use { store ->
                    DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db -> db.createStatement().use { s ->
                        s.execute("INSERT INTO source_pages(doc_id,pdf_page,text) VALUES('book',2,'Grossman health capital')")
                        assertEquals(listOf(2),store.searchSourcePages("c","Grossman").map { it.pdfPage })
                        s.execute("UPDATE source_pages SET text='Insurance risk pooling' WHERE doc_id='book' AND pdf_page=2")
                        assertEquals(emptyList(),store.searchSourcePages("c","Grossman"))
                        assertEquals(listOf(2),store.searchSourcePages("c","risk pooling").map { it.pdfPage })
                        s.execute("DELETE FROM source_pages WHERE doc_id='book' AND pdf_page=2")
                        assertEquals(emptyList(),store.searchSourcePages("c","risk pooling"))
                    } }
                }
            } finally { dir.toFile().deleteRecursively() }
        }
    }
}
