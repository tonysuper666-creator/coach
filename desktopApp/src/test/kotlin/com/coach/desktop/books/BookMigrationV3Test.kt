package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.*

/** A real 2.0 (v2) library opened by this build: collections widen in place and FSRS cards appear once. */
class BookMigrationV3Test {
    private val v3Tables = listOf("book_chapter_progress", "course_topics", "course_topic_sources", "concept_entities",
        "concept_mentions", "concept_links", "book_fsrs_cards")

    private fun connect(file: Path) = DriverManager.getConnection("jdbc:sqlite:$file")

    private fun rows(db: Connection, sql: String): List<List<String?>> = db.createStatement().use { s ->
        s.executeQuery(sql).use { r ->
            val n = r.metaData.columnCount
            buildList { while (r.next()) add((1..n).map { r.getString(it) }) }
        }
    }

    /** Rewinds a fresh library to exactly what main's v2 schema created, keeping every stored row. */
    private fun rewindToV2(file: Path) = connect(file).use { db -> db.createStatement().use { s ->
        for (t in v3Tables) s.execute("DROP TABLE IF EXISTS $t")
        s.execute("ALTER TABLE collection_materials RENAME TO cm_v3")
        s.execute("ALTER TABLE collections RENAME TO c_v3")
        s.execute("""CREATE TABLE collections(id TEXT PRIMARY KEY,
            kind TEXT NOT NULL CHECK(kind IN ('COURSE','BOOKLIST','CUSTOM')), title TEXT NOT NULL,
            metadata_json TEXT NOT NULL DEFAULT '{}', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""")
        s.execute("""CREATE TABLE collection_materials(collection_id TEXT NOT NULL, material_id TEXT NOT NULL,
            role TEXT NOT NULL DEFAULT 'PRIMARY', ordinal INTEGER NOT NULL DEFAULT 0, added_at INTEGER NOT NULL,
            metadata_json TEXT NOT NULL DEFAULT '{}', PRIMARY KEY(collection_id, material_id),
            FOREIGN KEY(collection_id) REFERENCES collections(id) ON DELETE CASCADE,
            FOREIGN KEY(material_id) REFERENCES books(id) ON DELETE CASCADE)""")
        s.execute("INSERT INTO collections SELECT id,kind,title,metadata_json,created_at,updated_at FROM c_v3")
        s.execute("INSERT INTO collection_materials SELECT collection_id,material_id,role,ordinal,added_at,metadata_json FROM cm_v3")
        s.execute("DROP TABLE cm_v3")
        s.execute("DROP TABLE c_v3")
        s.execute("ALTER TABLE books DROP COLUMN show_standalone")
        s.execute("PRAGMA user_version = 2")
    } }

    @Test fun v2LibraryUpgradesToV3WithBackupSameRowsAndLegacyReviewsMapped() {
        val root = Files.createTempDirectory("coach-v2-upgrade-")
        try {
            val clock = ReviewTestClock()
            val dir = root.resolve("library")
            val first = TextbookLibrary(dir, clock)
            val book = first.importBook(syntheticTextbook(root.resolve("synthetic")))
            val lesson = book.lessons.first()
            // A self-check marked "not yet" is the local answer path that lands in book_reviews.
            first.saveAnswer(book.id, lesson.id, QuestionMode.SHORT_ANSWER, PracticeAnswer("Not yet", true, false))
            first.upsertCollection(MaterialCollectionRecord("course-a", CollectionKind.COURSE, "Course A", createdAt = 11))
            first.upsertCollection(MaterialCollectionRecord("list-a", CollectionKind.BOOKLIST, "List A", createdAt = 12))
            first.attachMaterial(CollectionMaterialRecord("course-a", book.id, role = "TEXTBOOK", ordinal = 3, addedAt = 13))
            first.attachMaterial(CollectionMaterialRecord("list-a", book.id, role = "REFERENCE", ordinal = 0, addedAt = 14))
            val file = dir.resolve("library.sqlite")
            rewindToV2(file)
            val before = connect(file).use { db ->
                Triple(rows(db, "SELECT * FROM collections ORDER BY id"),
                    rows(db, "SELECT * FROM collection_materials ORDER BY collection_id"),
                    rows(db, "SELECT * FROM book_reviews ORDER BY book,lesson,mode"))
            }
            assertEquals(1, before.third.size)

            val upgraded = TextbookLibrary(dir, clock)

            val backup = dir.resolve(BookSchemaMigration.V2_BACKUP_FILENAME)
            assertTrue(Files.isRegularFile(backup), "v2 libraries keep their own backup before the upgrade")
            assertTrue(BookSchemaMigration.isHealthySqlite(backup))
            assertEquals(2, connect(backup).use(BookSchemaMigration::readUserVersion))
            connect(file).use { db ->
                assertEquals(BookSchemaMigration.CURRENT_SCHEMA_VERSION, BookSchemaMigration.readUserVersion(db))
                for (t in v3Tables) assertTrue(BookSchemaMigration.tableExists(db, t), "missing $t")
                assertEquals(before.first, rows(db, "SELECT id,kind,title,metadata_json,created_at,updated_at FROM collections ORDER BY id"))
                assertEquals(before.second, rows(db, "SELECT collection_id,material_id,role,ordinal,added_at,metadata_json FROM collection_materials ORDER BY collection_id"))
                assertEquals(before.third, rows(db, "SELECT * FROM book_reviews ORDER BY book,lesson,mode"), "legacy review rows stay byte-identical")
                assertEquals(listOf(listOf("1")), rows(db, "SELECT show_standalone FROM books"))
                assertEquals(1, rows(db, "SELECT origin FROM book_fsrs_cards").size)
            }
            val legacyState = assertNotNull(upgraded.fsrsState(book.id, lesson.id, QuestionMode.SHORT_ANSWER))
            assertTrue(legacyState.dueDay <= upgraded.review(book.id, lesson.id, QuestionMode.SHORT_ANSWER).dueDay,
                "a mapped card never becomes due later than the old schedule")

            // The widened kinds and new link columns round-trip on the upgraded file.
            upgraded.upsertCollection(MaterialCollectionRecord("single-a", CollectionKind.SINGLE_BOOK, "Book A",
                sortOrder = 2, examDate = "2027-01-10", sourceHash = "h1", userLocked = true))
            upgraded.attachMaterial(CollectionMaterialRecord("single-a", book.id, weekNum = 4, userLocked = true,
                authorityRank = 1, sourceHash = "h2"))
            val single = upgraded.collections().single { it.id == "single-a" }
            assertEquals(CollectionKind.SINGLE_BOOK, single.kind)
            assertEquals(2, single.sortOrder); assertEquals("2027-01-10", single.examDate); assertTrue(single.userLocked)
            val link = upgraded.collectionMaterials("single-a").single()
            assertEquals(4, link.weekNum); assertEquals(1, link.authorityRank); assertEquals("h2", link.sourceHash)
            assertNull(upgraded.collectionMaterials("list-a").single().weekNum)
            assertEquals(3, upgraded.collections().size)

            // A second open is a no-op: same version, still one FSRS card, review queue unchanged.
            val again = TextbookLibrary(dir, clock)
            connect(file).use { db ->
                assertEquals(BookSchemaMigration.CURRENT_SCHEMA_VERSION, BookSchemaMigration.readUserVersion(db))
                assertEquals(1, rows(db, "SELECT 1 FROM book_fsrs_cards").size)
            }
            assertEquals(upgraded.reviews(), again.reviews())
            assertEquals(1, again.reviewQueue().cards.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun reviewQueueShowsTwentyByUrgencyAndRollsTheRestOver() {
        val root = Files.createTempDirectory("coach-review-queue-")
        try {
            val clock = ReviewTestClock()
            val store = TextbookLibrary(root.resolve("library"), clock)
            val book = store.importBook(syntheticTextbook(root.resolve("synthetic")))
            val questions = book.lessons.filter { it.text.isNotBlank() }
            assertTrue(questions.isNotEmpty())
            for (l in questions) store.saveAnswer(book.id, l.id, QuestionMode.SHORT_ANSWER, PracticeAnswer("Not yet", true, false))
            val queue = store.reviewQueue()
            assertEquals(questions.size, store.reviews().size)
            assertEquals(minOf(20, questions.size), queue.cards.size)
            assertEquals(questions.size - queue.cards.size, queue.deferred)
            assertTrue(queue.cards.all { it in store.reviews() })
            assertTrue(queue.summary.startsWith("今日"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
