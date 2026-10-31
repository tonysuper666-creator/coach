package com.coach.store

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class SourcePageStoreTest {
    @Test fun upgradingAnExistingLibraryRebuildsSearchAndSubsequentSqlImportsStaySearchable() {
        val dir=Files.createTempDirectory("source-upgrade-")
        try {
            LedgerStore(dir).use { it.registerSourcePage("c","book","Synthetic book",1,"Adverse selection") }
            // A pre-feature library has pages but no maintained FTS indexes.
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db -> db.createStatement().use { s ->
                for(table in listOf("fts_en","fts_zh")) {
                    for(kind in listOf("insert","delete","update")) s.execute("DROP TRIGGER ${table}_$kind")
                    s.execute("DELETE FROM $table")
                }
                s.execute("PRAGMA user_version=4")
            } }
            LedgerStore(dir).use { store ->
                assertEquals(listOf("book"),store.searchSourcePages("c","adverse selection").map { it.docId })
                DriverManager.getConnection("jdbc:sqlite:${dir.resolve("coach.sqlite")}").use { db -> db.createStatement().use { s ->
                    s.execute("UPDATE source_pages SET text='Moral hazard' WHERE doc_id='book'")
                    assertEquals(emptyList(),store.searchSourcePages("c","adverse selection"))
                    assertEquals(listOf("book"),store.searchSourcePages("c","moral hazard").map { it.docId })
                    s.execute("DELETE FROM source_pages WHERE doc_id='book'")
                    assertEquals(emptyList(),store.searchSourcePages("c","moral hazard"))
                } }
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
