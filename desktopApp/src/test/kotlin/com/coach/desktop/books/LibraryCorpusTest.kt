package com.coach.desktop.books

import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    @Test fun everyTokenFiltersBeforeTheLimit() {
        val root = Files.createTempDirectory("library-tokens-")
        val library = TextbookLibrary(root.resolve("library"), todayCardEnabled = false)
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.autoCommit = false
            db.createStatement().use { it.executeUpdate("INSERT INTO books(id,title,format,touched) VALUES('b1','合成书','md',1)") }
            db.prepareStatement(
                """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
                   VALUES('b1',?,'v1',1,0,1,'句',?,'sha',1,'m',0)""",
            ).use { sql ->
                repeat(800) { index ->
                    sql.setString(1, "n$index")
                    sql.setString(2, if (index == 0) "opportunity 稀 唯一" else "opportunity 笔记 $index")
                    sql.addBatch()
                }
                sql.executeBatch()
            }
            db.createStatement().use { it.executeUpdate("UPDATE book_notes SET created_at=1 WHERE id='n0'") }
            db.commit()
        }
        openLibraryCorpus(library.directory).use { connection ->
            syncLibraryCorpus(connection)
            val hits = searchLibrary(connection, LibraryQuery(text = "opportunity 稀", limit = 80))
            assertEquals(listOf("legacy-v1:annotation:n0"), hits.map { it.key })
        }
    }

    @Test fun sameLessonInTwoBooksBothStayInTheIndex() {
        val root = Files.createTempDirectory("library-mistake-key-")
        val library = TextbookLibrary(root.resolve("library"), todayCardEnabled = false)
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { sql ->
                sql.executeUpdate("INSERT INTO books(id,title,format,touched) VALUES('a','甲书','md',1)")
                sql.executeUpdate("INSERT INTO books(id,title,format,touched) VALUES('b','乙书','md',1)")
                sql.executeUpdate(
                    """INSERT INTO book_reviews(
                        book,lesson,mode,book_title,lesson_title,prompt,reference,solution,options,wrong_text,answer_text,revealed,completed,guessed
                    ) VALUES('a','lesson-1','CHOICE','甲书','节','题干','','正确','[]','错误','错误',1,0,0)""",
                )
                sql.executeUpdate(
                    """INSERT INTO book_reviews(
                        book,lesson,mode,book_title,lesson_title,prompt,reference,solution,options,wrong_text,answer_text,revealed,completed,guessed
                    ) VALUES('b','lesson-1','CHOICE','乙书','节','题干','','正确','[]','错误','错误',1,0,0)""",
                )
            }
        }
        openLibraryCorpus(library.directory).use { connection ->
            val sync = syncLibraryCorpus(connection)
            assertEquals(2, sync.count)
            val keys = searchLibrary(connection, LibraryQuery(kind = LibraryKind.MISTAKE, limit = 10)).map { it.key }.toSet()
            assertEquals(setOf("legacy-v1:mistake:a:lesson-1:CHOICE", "legacy-v1:mistake:b:lesson-1:CHOICE"), keys)
        }
    }

    @Test fun keepsUserYamlAndSeparateFilesWhenNamesDifferOnlyByCase() {
        val root = Files.createTempDirectory("library-yaml-")
        val library = TextbookLibrary(root.resolve("library"), todayCardEnabled = false)
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { sql ->
                sql.executeUpdate("INSERT INTO books(id,title,format,touched) VALUES('s1','Sample','md',1)")
                sql.executeUpdate("INSERT INTO books(id,title,format,touched) VALUES('s2','sample','md',1)")
                sql.executeUpdate(
                    """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
                       VALUES('s1','n1','v1',1,0,1,'句','Sample 批注','sha',1,'m',0)""",
                )
                sql.executeUpdate(
                    """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
                       VALUES('s2','n2','v1',1,0,1,'句','sample 批注','sha',1,'m2',0)""",
                )
            }
        }
        val vault = Files.createTempDirectory("obsidian-case-")
        val first = export(Fixture(library), vault, Instant.parse("2026-11-26T08:00:00Z"))
        assertEquals(2, first.written)
        val files = Files.newDirectoryStream(vault, "*.md").use { stream -> stream.toList() }
        assertEquals(2, files.size)
        val sample = files.first { Files.readString(it).contains("coach_record_id: \"legacy-v1:book:s1\"") }
        val other = files.first { it != sample }
        val otherBytes = Files.readAllBytes(other)
        Files.writeString(sample, Files.readString(sample).replaceFirst("---\n", "---\ntags:\n  - 我的标签\n") + "我自己加的一行\n")
        openLibraryCorpus(library.directory).use { connection ->
            connection.createStatement().use { it.executeUpdate("UPDATE book_notes SET note='改过的批注' WHERE id='n1'") }
        }
        export(Fixture(library), vault, Instant.parse("2026-11-26T10:00:00Z"))
        val rewritten = Files.readString(sample)
        assertTrue(rewritten.contains("tags:"), rewritten)
        assertTrue(rewritten.contains("我的标签"), rewritten)
        assertTrue(rewritten.contains("我自己加的一行"), rewritten)
        assertTrue(rewritten.contains("改过的批注"), rewritten)
        assertTrue(otherBytes.contentEquals(Files.readAllBytes(other)))
    }

    @Test fun newNotesAndHighlightsKeepTimeWhileOldRowsStayUnknown() {
        val root = Files.createTempDirectory("library-times-")
        val library = TextbookLibrary(root.resolve("library"), todayCardEnabled = false)
        val started = System.currentTimeMillis() - 5_000
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { sql ->
                sql.executeUpdate("INSERT INTO books(id,title,format,touched) VALUES('b1','甲书','md',1)")
                sql.executeUpdate("INSERT INTO pages(book,page,text) VALUES('b1',2,'page')")
                sql.executeUpdate(
                    """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
                       VALUES('b1','old','v1',2,0,1,'句','旧批注','sha',1,'m',0)""",
                )
                sql.executeUpdate("UPDATE book_notes SET created_at=NULL WHERE id='old'")
                sql.executeUpdate("INSERT INTO book_highlights(book,page,start,end,quote) VALUES('b1',2,4,6,'旧高亮')")
                sql.executeUpdate("UPDATE book_highlights SET created_at=NULL WHERE quote='旧高亮'")
            }
        }
        val saved = library.saveNote(
            "b1",
            BookNoteRecord("new", "v1", 2, 0, 1, "句", "新批注", "sha", 0L),
            0L,
            "mut-new",
        )
        assertTrue(saved is BookNoteWriteResult.Saved)
        library.setHighlight(BookPassage("b1", 2, 0, 2, "新高亮"), true)
        openLibraryCorpus(library.directory).use { connection ->
            syncLibraryCorpus(connection)
            val rows = searchLibrary(connection, LibraryQuery(limit = 20))
            assertEquals(null, rows.single { it.primary == "旧批注" }.atMs)
            assertEquals(null, rows.single { it.primary == "旧高亮" }.atMs)
            val note = rows.single { it.primary == "新批注" }
            val highlight = rows.single { it.primary == "新高亮" }
            assertTrue(note.atMs != null && note.atMs!! >= started)
            assertTrue(highlight.atMs != null && highlight.atMs!! >= started)
            val recent = searchLibrary(connection, LibraryQuery(sinceMs = started, limit = 20))
            assertTrue(recent.any { it.primary == "新批注" })
            assertTrue(recent.any { it.primary == "新高亮" })
            assertTrue(recent.none { it.primary == "旧批注" })
            assertTrue(recent.none { it.primary == "旧高亮" })
        }
    }

    @Test fun comparisonOutlineAndSayingsStayGroundedAndExportOnce() {
        val root = Files.createTempDirectory("library-study-")
        var vault: java.nio.file.Path? = null
        try {
            val library = TextbookLibrary(root.resolve("library"), lazyPdfExtras = false, todayCardEnabled = false)
            val imported = (1..3).map { library.importBook(LiteratureGuideFixture.pdf(root, it)) }
            val books = imported.map { library.literatureBook(it.id) }
            val course = library.withCourseDatabase { db ->
                CourseStore.createCourse(db, "对比", CourseExamProfile(), books.map { it.id }).first
            }
            val input = LiteratureComparisonInput(books)
            val verified = input.verify(
                comparisonReply(input.request(), failMethodOf = books.last().id, outlineCount = 4),
                LiteratureOcrVerifier { book, pdfPage -> book.page(pdfPage)?.text.orEmpty() },
            )
            val table = requireNotNull(verified.table)
            assertEquals(1, table.audit().pendingCites)
            library.withCourseDatabase { LiteratureComparisonStore.save(it, course, books.map { book -> book.id }, table) }

            val flower = "flowering plants"
            val faster = "faster growth"
            val left = phraseAt(books[0], flower)
            val spanLeft = phraseAt(books[0], faster)
            val spanRight = phraseAt(books[1], faster)
            library.withCourseDatabase { db ->
                db.createStatement().use { sql ->
                    sql.executeUpdate(
                        """CREATE TABLE IF NOT EXISTS concept_sayings (
                            concept_id TEXT NOT NULL,
                            input_hash TEXT NOT NULL,
                            book_id TEXT NOT NULL,
                            mention_id TEXT NOT NULL,
                            book_title TEXT NOT NULL,
                            statement TEXT NOT NULL,
                            stance TEXT NOT NULL,
                            page INTEGER NOT NULL,
                            offset_utf16 INTEGER NOT NULL,
                            quote_anchor TEXT NOT NULL,
                            kept INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY (concept_id, input_hash, book_id)
                        )""",
                    )
                }
                db.prepareStatement(
                    """INSERT INTO concept_entities(concept_id, collection_id, canonical_name, aliases_json, muted, shared_note)
                       VALUES(?,?,?,?,?,?)""",
                ).use { sql ->
                    fun entity(id: String, name: String, muted: Int) {
                        sql.setString(1, id)
                        sql.setString(2, course)
                        sql.setString(3, name)
                        sql.setString(4, "[]")
                        sql.setInt(5, muted)
                        sql.setString(6, "")
                        sql.addBatch()
                    }
                    entity("shade-growth", "遮阴生长", 0)
                    entity("mention-span", "样本生长", 0)
                    entity("muted-one", "静音概念", 1)
                    sql.executeBatch()
                }
                db.prepareStatement(
                    """INSERT INTO concept_sayings(
                        concept_id, input_hash, book_id, mention_id, book_title, statement, stance,
                        page, offset_utf16, quote_anchor, kept, updated_at
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                ).use { sql ->
                    sql.setString(1, "shade-growth")
                    sql.setString(2, "hash-shade")
                    sql.setString(3, books[0].id)
                    sql.setString(4, "say-shade")
                    sql.setString(5, books[0].title)
                    sql.setString(6, "独有说法遮阴对照句")
                    sql.setString(7, "SAME")
                    sql.setInt(8, left.first)
                    sql.setInt(9, left.second)
                    sql.setString(10, flower)
                    sql.setInt(11, 1)
                    sql.setLong(12, 1_700_000_000_000L)
                    sql.executeUpdate()
                }
                db.prepareStatement(
                    """INSERT INTO concept_mentions(
                        mention_id, concept_id, book_id, page, offset_utf16, quote_anchor, mention_role, claim_summary
                    ) VALUES(?,?,?,?,?,?,?,?)""",
                ).use { sql ->
                    fun mention(id: String, concept: String, book: String, page: Int, start: Int, quote: String, claim: String) {
                        sql.setString(1, id)
                        sql.setString(2, concept)
                        sql.setString(3, book)
                        sql.setInt(4, page)
                        sql.setInt(5, start)
                        sql.setString(6, quote)
                        sql.setString(7, "PRIMARY_DEF")
                        sql.setString(8, claim)
                        sql.addBatch()
                    }
                    mention("span-left", "mention-span", books[0].id, spanLeft.first, spanLeft.second, faster, "独有提及跨书样本")
                    mention("span-right", "mention-span", books[1].id, spanRight.first, spanRight.second, faster, "独有提及跨书样本")
                    mention("muted-mention", "muted-one", books[0].id, left.first, 0, "MUTEDTOKENZ9", "MUTEDTOKENZ9")
                    sql.executeBatch()
                }
            }

            lateinit var jumpHit: LibraryRecord
            openLibraryCorpus(library.directory).use { connection ->
                syncLibraryCorpus(connection)
                val pages = HashMap<Pair<String, Int>, String>()
                connection.createStatement().use { sql ->
                    sql.executeQuery("SELECT book, page, text FROM pages").use { rows ->
                        while (rows.next()) pages[rows.getString(1) to rows.getInt(2)] = rows.getString(3).orEmpty()
                    }
                }
                fun assertOnPage(record: LibraryRecord) {
                    val page = record.page
                    val start = record.startUtf16
                    val end = record.endUtf16
                    if (page == null || start == null || end == null) return
                    val text = checkNotNull(pages[record.bookId to page]) { record.key }
                    assertEquals(text.substring(start, end), record.quote, record.key)
                }
                val comparisons = searchLibrary(connection, LibraryQuery(kind = LibraryKind.COMPARISON, limit = 200))
                val grounded = comparisons.filter { it.startUtf16 != null && it.quote.isNotBlank() }
                val pending = comparisons.filter { COMPARISON_PENDING in (libraryDetailValues(it.detail)[LibraryStudyFields.CITE].orEmpty()) }
                assertTrue(grounded.size >= 10, "grounded=${grounded.size}")
                assertEquals(1, pending.size)
                pending.forEach { hit ->
                    assertEquals("", hit.quote)
                    assertEquals(null, hit.page)
                    assertEquals(null, hit.startUtf16)
                    assertEquals(null, hit.endUtf16)
                    assertTrue(hit.primary.isNotBlank())
                }
                comparisons.forEach(::assertOnPage)
                assertEquals(comparisons.size, comparisons.map { it.key }.distinct().size)

                val claims = searchLibrary(connection, LibraryQuery(text = "这篇用自己的话概括了主张", limit = 20))
                assertEquals(3, claims.size)
                assertTrue(claims.all { it.kind == LibraryKind.COMPARISON })
                claims.forEach(::assertOnPage)

                val outlines = searchLibrary(connection, LibraryQuery(text = "生长速度", limit = 20))
                assertTrue(outlines.isNotEmpty())
                assertTrue(outlines.all { it.kind == LibraryKind.OUTLINE })
                assertTrue(outlines.all { it.quote.isNotBlank() && it.startUtf16 != null })
                outlines.forEach(::assertOnPage)

                val saying = searchLibrary(connection, LibraryQuery(text = "独有说法遮阴对照句")).single()
                assertEquals(LibraryKind.SAYING, saying.kind)
                assertEquals(flower, saying.quote)
                assertOnPage(saying)
                assertTrue(saying.detail.contains("说法一致"))

                val mentions = searchLibrary(connection, LibraryQuery(text = "独有提及跨书样本", limit = 10))
                assertEquals(2, mentions.size)
                assertTrue(mentions.all { it.kind == LibraryKind.SAYING && it.detail.contains("尚未对照") })
                mentions.forEach(::assertOnPage)
                assertTrue(searchLibrary(connection, LibraryQuery(text = "MUTEDTOKENZ9")).isEmpty())

                jumpHit = grounded.first()
                assertTrue(stageLibraryJump(library, jumpHit))
                assertFalse(stageLibraryJump(library, pending.single()))
                assertTrue(stageLibraryJump(library, jumpHit))
            }

            val controller = TextbookController(library)
            runBlocking {
                controller.openBook(jumpHit.bookId)
                assertEquals(null, controller.state.value.error)
                val passage = assertNotNull(controller.state.value.pendingCourseJump)
                assertEquals(jumpHit.bookId, passage.book)
                assertEquals(jumpHit.page, passage.page)
                assertEquals(jumpHit.startUtf16, passage.start)
                assertEquals(jumpHit.endUtf16, passage.end)
                assertEquals(jumpHit.quote, passage.text)
                controller.jumpToMockSourcePage(
                    course,
                    jumpHit.bookId,
                    jumpHit.page ?: error("page"),
                    jumpHit.quote,
                    BookCompanionAnchor(
                        pdfPage = jumpHit.page ?: error("page"),
                        blockId = "cell",
                        startUtf16 = jumpHit.startUtf16 ?: error("start"),
                        endUtf16 = jumpHit.endUtf16 ?: error("end"),
                        quoteAnchor = jumpHit.quote,
                    ),
                )
                assertEquals(null, controller.state.value.error)
                assertEquals(jumpHit.quote, controller.state.value.pendingCourseJump?.text)
                assertTrue(stageLibraryJump(library, jumpHit.copy(quote = "这句不在这一页上")))
                controller.openBook(jumpHit.bookId)
                assertEquals(null, controller.state.value.pendingCourseJump)
            }

            vault = Files.createTempDirectory("obsidian-study-")
            val vaultDir = vault ?: error("vault")
            val first = export(Fixture(library), vaultDir, Instant.parse("2026-11-26T08:00:00Z"))
            assertEquals(3, first.written)
            assertEquals(0, first.skipped)
            val files = Files.newDirectoryStream(vaultDir, "*.md").use { stream -> stream.toList() }
            assertEquals(3, files.size)
            val comparison = files.first { Files.readString(it).contains(":comparison:") }
            val comparisonText = Files.readString(comparison)
            assertTrue(comparisonText.contains("| 文献 | 主张 | 方法 / 证据 | 关键概念 | 与其他几篇的关系 |"))
            assertTrue(comparisonText.contains("# 综述提纲"))
            assertTrue(comparisonText.contains("生长速度"))
            assertTrue(comparisonText.contains(COMPARISON_PENDING))
            assertTrue(comparisonText.contains("这篇用自己的话概括了主张"))
            val sayingFile = files.first { Files.readString(it).contains("独有说法遮阴对照句") }
            val sayingText = Files.readString(sayingFile)
            assertTrue(sayingText.contains(":concept:shade-growth"))
            assertTrue(sayingText.contains(flower))
            assertTrue(sayingText.contains("PDF 第"))
            val mentionFile = files.first { it != comparison && it != sayingFile }
            assertTrue(Files.readString(mentionFile).contains(":concept:mention-span"))
            assertTrue(Files.readString(mentionFile).contains("尚未对照"))

            val names = files.map { it.fileName.toString() }.sorted()
            Files.writeString(comparison, comparisonText + "我自己加的一行\n")
            val second = export(Fixture(library), vaultDir, Instant.parse("2026-11-26T09:00:00Z"))
            assertEquals(0, second.written)
            assertTrue(second.skipped >= 1)
            val again = Files.newDirectoryStream(vaultDir, "*.md").use { stream -> stream.map { it.fileName.toString() }.sorted() }
            assertEquals(names, again)
            val kept = Files.readString(comparison)
            assertTrue(kept.contains("我自己加的一行"))
            assertTrue(kept.contains(OBSIDIAN_MANAGED_BEGIN))
            assertTrue(kept.startsWith("---"))
        } finally {
            root.toFile().deleteRecursively()
            vault?.toFile()?.deleteRecursively()
        }
    }

    private fun phraseAt(book: Textbook, phrase: String): Pair<Int, Int> {
        val page = book.pages.firstOrNull { phrase in it.text } ?: error("页上没有「$phrase」")
        val start = page.text.indexOf(phrase)
        check(page.text.indexOf(phrase, start + phrase.length) < 0) { "「$phrase」在同一页出现了多次" }
        return page.number to start
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
                sql.executeUpdate("UPDATE book_notes SET created_at=NULL")
                sql.executeUpdate("UPDATE book_highlights SET created_at=NULL")
            }
        }
        return Fixture(library)
    }
}
