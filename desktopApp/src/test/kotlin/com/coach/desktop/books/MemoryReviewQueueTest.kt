package com.coach.desktop.books

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class MemoryReviewQueueTest {
    @Test fun questionOnlyQueueKeepsCountsAndSummary() {
        val root = Files.createTempDirectory("coach-memory-questions-")
        try {
            val clock = ReviewTestClock()
            val store = TextbookLibrary(root.resolve("library"), clock)
            val book = store.importBook(syntheticTextbook(root.resolve("synthetic")))
            val questions = book.lessons.filter { it.text.isNotBlank() }
            assertTrue(questions.isNotEmpty())
            for (lesson in questions) {
                store.saveAnswer(book.id, lesson.id, QuestionMode.SHORT_ANSWER, PracticeAnswer("Not yet", true, false))
            }
            val queue = store.reviewQueue()
            assertEquals(questions.size, queue.cards.size)
            assertEquals(0, queue.deferred)
            assertTrue(queue.memory.isEmpty())
            assertEquals(queue.cards.map { ReviewLine.Question(it) }, queue.lineup)
            assertEquals("今日 ${questions.size} 题", queue.summary)
            connect(store).use { db ->
                val modes = db.createStatement().use { sql ->
                    sql.executeQuery("SELECT DISTINCT mode FROM book_fsrs_cards").use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1)) }
                    }
                }
                assertTrue(MemoryReview.MODE_CONCEPT !in modes && MemoryReview.MODE_NOTE !in modes)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun conceptsAndNotesShareTheDailyCap() {
        val root = Files.createTempDirectory("coach-memory-cap-")
        try {
            val clock = ReviewTestClock()
            val store = TextbookLibrary(root.resolve("library"), clock)
            val book = store.importBook(syntheticTextbook(root.resolve("synthetic")))
            connect(store).use { db ->
                db.autoCommit = false
                repeat(19) { index ->
                    val lesson = "q%02d".format(index + 1)
                    db.prepareStatement(
                        """INSERT INTO book_reviews(book,lesson,mode,book_title,lesson_title,prompt,reference,solution,options,wrong_text,due_day,streak)
                           VALUES(?,?,'SHORT_ANSWER','Book','Lesson','Prompt','Ref','Answer','[]','x',?,0)""",
                    ).use { sql ->
                        sql.setString(1, book.id)
                        sql.setString(2, lesson)
                        sql.setLong(3, clock.day)
                        sql.executeUpdate()
                    }
                }
                insertConcept(db, "cap-concept", book.id, 1, 0, "Mass", "a short quote about mass")
                insertNote(db, book.id, "cap-note", 1, 0, "plants use light", "I think this is about energy")
                db.commit()
            }
            val queue = store.reviewQueue()
            assertEquals(20, queue.lineup.size)
            assertEquals(19, queue.cards.size)
            assertEquals(1, queue.memory.size)
            assertEquals(MemoryReviewKind.CONCEPT, queue.memory.single().kind)
            assertEquals(1, queue.deferred)
            assertTrue(queue.lineup.none { it is ReviewLine.Memory && it.item.kind == MemoryReviewKind.NOTE })
            assertTrue(queue.summary.startsWith("今日优先 20 条"))
            connect(store).use { db ->
                assertEquals(1, count(db, "SELECT COUNT(*) FROM book_fsrs_cards WHERE mode='${MemoryReview.MODE_NOTE}' AND due_day<=${clock.day}"))
                assertEquals(0L, longValue(db, "SELECT updated_at FROM book_fsrs_cards WHERE mode='${MemoryReview.MODE_CONCEPT}'"))
                assertEquals(1, count(db, "SELECT COUNT(*) FROM book_fsrs_cards WHERE mode='${MemoryReview.MODE_CONCEPT}'"))
            }
            store.reviewQueue()
            connect(store).use { db ->
                assertEquals(1, count(db, "SELECT COUNT(*) FROM book_fsrs_cards WHERE mode='${MemoryReview.MODE_CONCEPT}'"))
                assertEquals(1, count(db, "SELECT COUNT(*) FROM book_fsrs_cards WHERE mode='${MemoryReview.MODE_NOTE}'"))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun gradingAConceptUpdatesMasteryAndANoteCanBeEdited() {
        val root = Files.createTempDirectory("coach-memory-grade-")
        try {
            val clock = ReviewTestClock()
            val store = TextbookLibrary(root.resolve("library"), clock)
            val book = store.importBook(syntheticTextbook(root.resolve("synthetic")))
            val page = book.pages.first { it.text.length >= 8 }
            val quote = page.text.trim().take(40).trim()
            val offset = page.text.indexOf(quote)
            assertTrue(offset >= 0 && quote.length >= 2)
            connect(store).use { db ->
                insertConcept(db, "live-concept", book.id, page.number, offset, "Osmosis", quote)
                insertNote(db, book.id, "live-note", page.number, offset, quote, "my first note")
            }
            val queue = store.reviewQueue()
            val concept = queue.memory.single { it.objectId == "live-concept" }
            val note = queue.memory.single { it.objectId == "live-note" }
            assertEquals(book.id, concept.bookId)
            assertEquals(page.number, concept.page)
            assertEquals(offset, concept.offsetUtf16)
            assertEquals(quote, concept.quote)
            store.rateMemoryReview(concept, remembered = true, editedNote = null)
            connect(store).use { db ->
                val mastery = db.prepareStatement(
                    "SELECT p_mastery FROM concept_mastery_ledger WHERE concept_key=?",
                ).use { sql ->
                    sql.setString(1, "live-concept")
                    sql.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        rows.getDouble(1)
                    }
                }
                assertTrue(mastery > 0.15, "mastery was $mastery")
                val due = longValue(db, "SELECT due_day FROM book_fsrs_cards WHERE lesson='concept:live-concept'")
                assertTrue(due > clock.day)
            }
            store.rateMemoryReview(note, remembered = true, editedNote = "改过的批注")
            connect(store).use { db ->
                assertEquals("改过的批注", text(db, "SELECT note FROM book_notes WHERE id='live-note'"))
            }
            store.rateMemoryReview(note, remembered = false, editedNote = "   ")
            connect(store).use { db ->
                assertEquals("改过的批注", text(db, "SELECT note FROM book_notes WHERE id='live-note'"))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun deletedNoteAndMutedConceptLeaveTheQueue() {
        val root = Files.createTempDirectory("coach-memory-drop-")
        try {
            val clock = ReviewTestClock()
            val store = TextbookLibrary(root.resolve("library"), clock)
            val book = store.importBook(syntheticTextbook(root.resolve("synthetic")))
            connect(store).use { db ->
                insertConcept(db, "drop-concept", book.id, 1, 0, "Mass", "mass quote")
                insertNote(db, book.id, "drop-note", 1, 0, "note quote", "a note")
            }
            assertEquals(2, store.reviewQueue().memory.size)
            connect(store).use { db ->
                db.createStatement().use { sql ->
                    sql.executeUpdate("UPDATE book_notes SET deleted=1 WHERE id='drop-note'")
                    sql.executeUpdate("UPDATE concept_entities SET muted=1 WHERE concept_id='drop-concept'")
                }
            }
            val again = store.reviewQueue()
            assertTrue(again.memory.isEmpty())
            connect(store).use { db ->
                assertEquals(0, count(db, "SELECT COUNT(*) FROM book_fsrs_cards WHERE mode IN ('${MemoryReview.MODE_CONCEPT}','${MemoryReview.MODE_NOTE}')"))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun jumpBackOpensTheSentenceOnlyWhenItStillMatches() = runBlocking {
        val root = Files.createTempDirectory("coach-memory-jump-")
        try {
            val clock = ReviewTestClock()
            val store = TextbookLibrary(root.resolve("library"), clock)
            val book = store.importBook(syntheticTextbook(root.resolve("synthetic")))
            val page = book.pages.first { it.text.length >= 8 }
            val quote = page.text.trim().take(24).trim()
            val offset = page.text.indexOf(quote)
            connect(store).use { db ->
                insertConcept(db, "jump-concept", book.id, page.number, offset, "Osmosis", quote)
                insertConcept(db, "miss-concept", book.id, page.number, 0, "Missing", "this sentence is not on the page")
            }
            val queue = store.reviewQueue()
            val hit = queue.memory.single { it.objectId == "jump-concept" }
            val miss = queue.memory.single { it.objectId == "miss-concept" }
            val controller = TextbookController(store)
            controller.openMemoryReview(hit)
            assertEquals(BookScreen.REVIEWING, controller.state.value.screen)
            controller.showMemorySentence()
            assertEquals(BookScreen.READER, controller.state.value.screen)
            assertEquals(page.number, controller.state.value.page)
            assertEquals(quote, controller.state.value.pendingCourseJump?.text)
            assertEquals(queue.lineup, controller.state.value.todayQueue.lineup)
            controller.back()
            assertEquals(BookScreen.REVIEWING, controller.state.value.screen)
            assertEquals(hit.objectId, controller.state.value.memoryReview?.objectId)
            assertFalse(controller.state.value.memorySentenceOpen)
            controller.openMemoryReview(miss)
            controller.showMemorySentence()
            assertEquals(BookScreen.READER, controller.state.value.screen)
            assertEquals(page.number, controller.state.value.page)
            assertNull(controller.state.value.pendingCourseJump)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun clozeNeedsTheNameAndADistractor() {
        val cloze = MemoryReview.clozeOrRetell(
            "c1",
            "Chlorophyll",
            "Chlorophyll absorbs light",
            listOf("Chlorophyll", "Respiration"),
        )
        assertTrue(cloze is MemoryPrompt.Cloze)
        val made = cloze as MemoryPrompt.Cloze
        assertEquals("____ absorbs light", made.stem)
        assertEquals("Chlorophyll", made.answer)
        assertTrue("Respiration" in made.options)
        assertTrue(MemoryReview.clozeOrRetell("c1", "ATP", "plants use light", listOf("ATP", "Sugar")) is MemoryPrompt.Retell)
        assertTrue(MemoryReview.clozeOrRetell("c1", "Chlorophyll", "Chlorophyll absorbs light", listOf("Chlorophyll")) is MemoryPrompt.Retell)
    }

    private fun connect(store: TextbookLibrary) =
        DriverManager.getConnection("jdbc:sqlite:${store.directory.resolve("library.sqlite")}")

    private fun insertConcept(db: java.sql.Connection, id: String, bookId: String, page: Int, offset: Int, name: String, quote: String) {
        db.prepareStatement(
            "INSERT INTO concept_entities(concept_id, collection_id, canonical_name, muted) VALUES(?,?,?,0)",
        ).use { sql ->
            sql.setString(1, id)
            sql.setString(2, "col-$id")
            sql.setString(3, name)
            sql.executeUpdate()
        }
        db.prepareStatement(
            """INSERT INTO concept_mentions(mention_id, concept_id, book_id, page, offset_utf16, quote_anchor, mention_role, claim_summary)
               VALUES(?,?,?,?,?,?,'PRIMARY_DEF','')""",
        ).use { sql ->
            sql.setString(1, "m-$id")
            sql.setString(2, id)
            sql.setString(3, bookId)
            sql.setInt(4, page)
            sql.setInt(5, offset)
            sql.setString(6, quote)
            sql.executeUpdate()
        }
    }

    private fun insertNote(db: java.sql.Connection, bookId: String, id: String, page: Int, start: Int, quote: String, note: String) {
        db.prepareStatement(
            """INSERT INTO book_notes(book,id,version,page,start,end,quote,note,text_sha256,revision,last_mutation_id,deleted)
               VALUES(?,?,'v',?,?,?,?,?,'sha',1,'mut',0)""",
        ).use { sql ->
            sql.setString(1, bookId)
            sql.setString(2, id)
            sql.setInt(3, page)
            sql.setInt(4, start)
            sql.setInt(5, start + quote.length)
            sql.setString(6, quote)
            sql.setString(7, note)
            sql.executeUpdate()
        }
    }

    private fun count(db: java.sql.Connection, sqlText: String): Int =
        db.createStatement().use { sql -> sql.executeQuery(sqlText).use { rows -> rows.next(); rows.getInt(1) } }

    private fun longValue(db: java.sql.Connection, sqlText: String): Long =
        db.createStatement().use { sql -> sql.executeQuery(sqlText).use { rows -> rows.next(); rows.getLong(1) } }

    private fun text(db: java.sql.Connection, sqlText: String): String =
        db.createStatement().use { sql -> sql.executeQuery(sqlText).use { rows -> rows.next(); rows.getString(1) } }
}
