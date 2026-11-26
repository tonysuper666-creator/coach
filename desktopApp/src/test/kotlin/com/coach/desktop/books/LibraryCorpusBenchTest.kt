package com.coach.desktop.books

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 1000 本书、5 万条合成记录上的全文搜索。测量的是索引建好之后的查询，不含建索引。
 * 命令：gradlew :desktopApp:test --tests com.coach.desktop.books.LibraryCorpusBenchTest
 */
class LibraryCorpusBenchTest {
    @Test fun mixedSearchP95StaysUnder200ms() {
        val root = Files.createTempDirectory("library-corpus-bench-")
        val library = TextbookLibrary(root.resolve("library"), todayCardEnabled = false)
        val phrases = listOf("边际成本", "opportunity", "elasticity", "沉没成本", "comparative")
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.autoCommit = false
            db.prepareStatement("INSERT INTO books(id,title,format,touched) VALUES(?,?, 'md', 1)").use { sql ->
                repeat(1000) { index ->
                    sql.setString(1, "b$index")
                    sql.setString(2, "合成书$index")
                    sql.addBatch()
                }
                sql.executeBatch()
            }
            db.prepareStatement(
                """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
            ).use { sql ->
                repeat(20_000) { index ->
                    val phrase = phrases[index % phrases.size]
                    sql.setString(1, "b${index % 1000}")
                    sql.setString(2, "n$index")
                    sql.setString(3, "v1")
                    sql.setInt(4, (index % 40) + 1)
                    sql.setInt(5, index)
                    sql.setInt(6, index + 1)
                    sql.setString(7, phrase)
                    sql.setString(8, "$phrase 笔记 $index")
                    sql.setString(9, "sha")
                    sql.setInt(10, 1)
                    sql.setString(11, "m")
                    sql.setInt(12, 0)
                    sql.addBatch()
                    if (index % 1000 == 999) sql.executeBatch()
                }
                sql.executeBatch()
            }
            db.prepareStatement("INSERT INTO book_highlights(book,page,start,end,quote) VALUES(?,?,?,?,?)").use { sql ->
                repeat(15_000) { index ->
                    val phrase = phrases[index % phrases.size]
                    sql.setString(1, "b${index % 1000}")
                    sql.setInt(2, (index % 30) + 1)
                    sql.setInt(3, 100_000 + index)
                    sql.setInt(4, 100_001 + index)
                    sql.setString(5, "$phrase 高亮 $index")
                    sql.addBatch()
                    if (index % 1000 == 999) sql.executeBatch()
                }
                sql.executeBatch()
            }
            db.prepareStatement(
                """INSERT INTO book_ask_turns(
                    book,thread_id,selection_id,selection_page,selection_start,selection_end,selection_text,
                    label,question_text,answer_text,created_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
            ).use { sql ->
                repeat(10_000) { index ->
                    val phrase = phrases[index % phrases.size]
                    sql.setString(1, "b${index % 1000}")
                    sql.setString(2, "t$index")
                    sql.setString(3, "s$index")
                    sql.setInt(4, 2)
                    sql.setInt(5, 0)
                    sql.setInt(6, 1)
                    sql.setString(7, phrase)
                    sql.setString(8, "问")
                    sql.setString(9, "$phrase 问题 $index")
                    sql.setString(10, "回答 $index")
                    sql.setLong(11, 1_700_000_000_000L + index)
                    sql.addBatch()
                    if (index % 1000 == 999) sql.executeBatch()
                }
                sql.executeBatch()
            }
            db.prepareStatement(
                """INSERT INTO book_reviews(
                    book,lesson,mode,book_title,lesson_title,prompt,reference,solution,options,wrong_text,answer_text,revealed,completed,guessed
                ) VALUES(?,?,'CHOICE',?,?,?,'', 'A','[]','B','B',1,0,0)""",
            ).use { sql ->
                repeat(5_000) { index ->
                    val phrase = phrases[index % phrases.size]
                    sql.setString(1, "b${index % 1000}")
                    sql.setString(2, "L$index")
                    sql.setString(3, "合成书${index % 1000}")
                    sql.setString(4, "节")
                    sql.setString(5, "$phrase 题干 $index")
                    sql.addBatch()
                    if (index % 1000 == 999) sql.executeBatch()
                }
                sql.executeBatch()
            }
            db.commit()
        }
        openLibraryCorpus(library.directory).use { db ->
            val sync = syncLibraryCorpus(db)
            assertEquals(50_000, sync.count)
            val queries = listOf("边际成本", "opportunity", "elasticity", "沉没成本", "comparative", "合成书500", "成本")
            repeat(8) { searchLibrary(db, LibraryQuery(text = queries[it % queries.size], limit = 20)) }
            val samples = LongArray(40)
            repeat(samples.size) { index ->
                val started = System.nanoTime()
                searchLibrary(db, LibraryQuery(text = queries[index % queries.size], limit = 20))
                samples[index] = System.nanoTime() - started
            }
            samples.sort()
            val p95 = samples[(samples.size * 95 / 100).coerceAtMost(samples.lastIndex)] / 1_000_000
            val line = "p95_ms=$p95 queries=${queries.joinToString("|")} records=50000 books=1000"
            val out = java.nio.file.Path.of("build", "library-search-p95.txt")
            Files.createDirectories(out.parent)
            Files.writeString(out, line + "\n")
            println(line)
            assertTrue(p95 < 200, line)
        }
    }
}
