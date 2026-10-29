package com.coach.store

import java.nio.file.*
import java.sql.DriverManager
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.store.probe.FtsProbe

private data class Page(val name: String, val body: String)

fun main(args: Array<String>) {
    val realCorpus = args.isNotEmpty()
    val pages = if (realCorpus) {
        Files.list(Path.of(args.single())).use { files ->
            files.filter { it.toString().endsWith(".md") }.sorted().map { Page(it.fileName.toString(), Files.readString(it)) }.toList()
        }
    } else {
        listOf("01-en.md", "02-zh.md", "03-grossman.md").map { name ->
            val body = checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("fts/$name")).bufferedReader().use { it.readText() }
            Page(name, body)
        }
    }
    require(pages.size == 3) { "Expected three page extracts, got ${pages.size}" }
    println("Corpus: ${if (realCorpus) "real local textbook pages" else "synthetic default examples"}")
    DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
        db.createStatement().use { sql ->
            sql.executeQuery("SELECT sqlite_version(), sqlite_compileoption_used('ENABLE_FTS5')").use {
                check(it.next()); println("SQLite=${it.getString(1)} ENABLE_FTS5=${it.getInt(2)}"); check(it.getInt(2) == 1)
            }
            sql.execute("CREATE VIRTUAL TABLE fts_en USING fts5(source UNINDEXED, body, tokenize='porter unicode61')")
            sql.execute("CREATE VIRTUAL TABLE fts_zh USING fts5(source UNINDEXED, body, tokenize='trigram')")
        }
        for (page in pages) {
            val body = page.body
            for (table in listOf("fts_en", "fts_zh")) db.prepareStatement("INSERT INTO $table(source,body) VALUES(?,?)").use {
                it.setString(1, page.name); it.setString(2, body); it.executeUpdate()
            }
        }
        for ((table, term) in listOf("fts_zh" to "逆向选择", "fts_en" to "adverse selection", "fts_en" to "Grossman")) {
            db.prepareStatement("SELECT source, bm25($table) FROM $table WHERE $table MATCH ? ORDER BY rank").use { q ->
                q.setString(1, "\"$term\"")
                q.executeQuery().use { rows ->
                    var n = 0
                    while (rows.next()) { n++; println("MATCH '$term' source=${rows.getString(1)} bm25=${rows.getDouble(2)}") }
                    println("MATCH '$term' hits=$n")
                    if (term != "逆向选择" || !realCorpus) check(n > 0)
                    if (term == "逆向选择" && n == 0) println("Real source has no Chinese literal; no translation was injected")
                }
            }
        }
    }
    // Exercise SQLDelight's native escape hatch against the exact same bundled SQLite.
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    driver.execute(null, "CREATE VIRTUAL TABLE probe USING fts5(body, tokenize='trigram')", 0)
    FtsProbe.Schema.create(driver)
    for (page in pages) {
        driver.execute(null, "INSERT INTO fts_zh(body) VALUES (?)", 1) { bindString(0, page.body) }
    }
    val queries = FtsProbe(driver).searchQueries
    for (term in listOf("adverse selection", "Grossman")) {
        val rows = queries.search("\"$term\"").executeAsList()
        val scores = queries.score("\"$term\"").executeAsList()
        check(rows.isNotEmpty() && scores.size == rows.size)
        println("SQLDelight generated MATCH/rank/bm25 '$term' hits=${rows.size}")
    }
    driver.close()
    println("SQLDelight generated schema + driver.execute FTS5 trigram: OK")
}
