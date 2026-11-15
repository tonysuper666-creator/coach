package com.coach.desktop.books

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

/** The queue must consume a real persisted deadline, independently of the writer. */
class TextbookDueLegacyRedTest {
    @Test fun completedOriginalWithPersistedDeadlineReturnsAsFreshDueQuestion() {
        val root = Files.createTempDirectory("coach-due-read-")
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(syntheticTextbook(root.resolve("synthetic")))
        val lesson = book.lessons.first()
        val practice = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)!!
        library.saveAnswer(book.id, lesson.id, QuestionMode.CHOICE,
            PracticeAnswer(practice.options.first { it != practice.answer }, true))
        library.saveReviewAnswer(library.reviews().single(), PracticeAnswer(practice.answer, true))
        DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { db ->
            val columns = db.createStatement().use { s -> s.executeQuery("PRAGMA table_info(book_reviews)").use { r ->
                buildSet { while (r.next()) add(r.getString("name")) }
            } }
            db.createStatement().use { s ->
                for ((name, type) in mapOf("due_day" to "INTEGER", "interval" to "INTEGER", "ease" to "REAL", "anchor" to "INTEGER"))
                    if (name !in columns) s.execute("ALTER TABLE book_reviews ADD COLUMN $name $type")
                s.executeUpdate("UPDATE book_reviews SET due_day=0,interval=1,ease=2.3,anchor=NULL")
            }
        }
        val due = TextbookLibrary(library.directory).reviews()
        assertEquals(1, due.size, "PR207 ignores persisted due dates on completed rows")
        assertEquals(practice, due.single().practice)
        assertEquals(PracticeAnswer(), due.single().answer)
        assertFalse(due.single().completed)
    }
}
