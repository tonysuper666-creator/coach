package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class BookLearningPathTest {
    @Test fun pathsAreIndependentAndSurviveANewLibraryWithoutChangingExistingState() {
        val root = Files.createTempDirectory("coach-book-paths-")
        val directory = root.resolve("library")
        val library = TextbookLibrary(directory)
        val first = library.importBook(syntheticTextbook(root.resolve("source")))
        val second = library.importBook(Files.writeString(root.resolve("other.md"),
            "# Another book\n\nWater is a compound containing hydrogen and oxygen."))
        val progressed = first.copy(readingPage = 2, lessonIndex = 1, learning = true,
            promptSeen = true, questionMode = QuestionMode.CHOICE)
        library.save(progressed)
        val lesson = progressed.lessons[1]
        val practice = assertNotNull(com.coach.desktop.books.testPracticeFixture(progressed, lesson, QuestionMode.CHOICE))
        library.saveAnswer(first.id, lesson.id, QuestionMode.CHOICE,
            PracticeAnswer(practice.options.first { it != practice.answer }, true))
        assertEquals(1, library.reviews().size)
        val before = existingRows(directory)
        // An old library has no path table or recorded preference; migration must not infer one.
        DriverManager.getConnection("jdbc:sqlite:${directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { it.execute("DROP TABLE book_learning_paths") }
        }
        val migrated = TextbookLibrary(directory)
        assertNull(migrated.learningPath(first.id))
        assertNull(migrated.learningPath(second.id))
        migrated.saveLearningPath(first.id, "QUESTION_FIRST")
        migrated.saveLearningPath(second.id, "READ")
        assertEquals("QUESTION_FIRST", migrated.learningPath(first.id))
        assertEquals("READ", migrated.learningPath(second.id))
        migrated.saveLearningPath(first.id, "READ_AND_LEARN")
        val reopened = TextbookLibrary(directory)
        assertEquals("READ_AND_LEARN", reopened.learningPath(first.id))
        assertEquals("READ", reopened.learningPath(second.id))
        assertEquals(before, existingRows(directory), "Preferences must not overwrite bookmarks, answers or reviews")
        assertEquals(progressed, reopened.book(first.id))
    }

    @Test fun unknownBooksAndInvalidPathsCannotCreateOrReplaceAPreference() {
        val root = Files.createTempDirectory("coach-book-path-reject-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("source")))
        library.saveLearningPath(book.id, "READ")
        for (invalid in listOf("", "read", "LEARN", "READ_AND_LEARN ")) {
            assertFailsWith<IllegalArgumentException> { library.saveLearningPath(book.id, invalid) }
        }
        val unknown = "0".repeat(64)
        assertFailsWith<IllegalArgumentException> { library.saveLearningPath(unknown, "QUESTION_FIRST") }
        assertFailsWith<IllegalArgumentException> { library.saveLearningPath("not-a-material-sha", "READ") }
        val reopened = TextbookLibrary(library.directory)
        assertNull(reopened.learningPath(unknown))
        assertEquals("READ", reopened.learningPath(book.id))
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            db.createStatement().use { sql ->
                sql.executeQuery("SELECT count(*) FROM book_learning_paths").use { row ->
                    assertTrue(row.next()); assertEquals(1, row.getInt(1))
                }
                sql.executeQuery("PRAGMA integrity_check").use { row ->
                    assertTrue(row.next()); assertEquals("ok", row.getString(1))
                }
            }
        }
    }

    private fun existingRows(directory: Path): Map<String, List<List<String?>>> =
        DriverManager.getConnection("jdbc:sqlite:${directory.resolve("library.sqlite")}").use { db ->
            listOf("books", "pages", "answers", "book_reviews", "book_review_position").associateWith { table ->
                db.createStatement().use { sql ->
                    sql.executeQuery("SELECT * FROM $table ORDER BY rowid").use { rows ->
                        buildList { while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) }) }
                    }
                }
            }
        }
}
