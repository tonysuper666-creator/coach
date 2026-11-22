package com.coach.desktop.books

import java.nio.file.Files
import java.sql.DriverManager
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudyModeModelsTest {

    @Test
    fun smartDefaultResolvesSixDistinctBookScenarios() {
        val today = LocalDate.of(2026, 11, 22)

        val imminent = SmartDefaultModeResolver.resolve(
            bookId = "b1",
            title = "Economics Textbook",
            format = "pdf",
            pageCount = 620,
            sampleText = "Chapter 1 Introduction to Modern Microeconomics Markets and Welfare",
            materialKind = "TEXTBOOK",
            inCourse = true,
            courseExamDate = "2026-12-06",
            inBooklist = false,
            testedSectionRatio = 0.35,
            readChapterRatio = 0.25,
            today = today,
        )
        assertEquals(BookStudyMode.DIAGNOSTIC_SKIP, imminent.mode)
        assertEquals(ExplainLanguage.BILINGUAL, imminent.explainLang)
        assertTrue(imminent.defaultReason.contains("14"))

        val course = SmartDefaultModeResolver.resolve(
            bookId = "b2",
            title = "Economics Textbook",
            format = "pdf",
            pageCount = 620,
            sampleText = "Chapter 1 Introduction to Modern Microeconomics Markets and Welfare",
            materialKind = "TEXTBOOK",
            inCourse = true,
            courseExamDate = "2027-05-10",
            inBooklist = false,
            testedSectionRatio = 0.0,
            readChapterRatio = 0.0,
            today = today,
        )
        assertEquals(BookStudyMode.EXAM_FOCUS, course.mode)

        val secondPass = SmartDefaultModeResolver.resolve(
            bookId = "b3",
            title = "Economics Textbook",
            format = "pdf",
            pageCount = 620,
            sampleText = "Chapter 1 Introduction to Modern Microeconomics Markets and Welfare",
            materialKind = "TEXTBOOK",
            inCourse = false,
            courseExamDate = null,
            inBooklist = false,
            testedSectionRatio = 0.40,
            readChapterRatio = 0.35,
            today = today,
        )
        assertEquals(BookStudyMode.DIAGNOSTIC_SKIP, secondPass.mode)
        assertEquals(ExplainDetailLevel.BRIEF, secondPass.detailLevel)

        val monograph = SmartDefaultModeResolver.resolve(
            bookId = "b4",
            title = "Academic Monograph on Institutions",
            format = "pdf",
            pageCount = 340,
            sampleText = "Institutions History Political Economy Comparative Analysis",
            materialKind = "MONOGRAPH",
            inCourse = false,
            courseExamDate = null,
            inBooklist = true,
            testedSectionRatio = 0.0,
            readChapterRatio = 0.0,
            today = today,
        )
        assertEquals(BookStudyMode.BOOK_MAP, monograph.mode)

        val generalEpub = SmartDefaultModeResolver.resolve(
            bookId = "b5",
            title = "随笔小集",
            format = "epub",
            pageCount = 68,
            sampleText = "这是一本轻松的中文散文随笔。",
            materialKind = null,
            inCourse = false,
            courseExamDate = null,
            inBooklist = false,
            testedSectionRatio = 0.0,
            readChapterRatio = 0.0,
            today = today,
        )
        assertEquals(BookStudyMode.READ_THROUGH, generalEpub.mode)
        assertEquals(ExplainDetailLevel.ORIGINAL_ONLY, generalEpub.detailLevel)
        assertEquals(ExplainLanguage.ZH, generalEpub.explainLang)

        val freshTextbook = SmartDefaultModeResolver.resolve(
            bookId = "b6",
            title = "Quantitative Methods Textbook",
            format = "pdf",
            pageCount = 620,
            sampleText = "Chapter 1 Probability Distributions Expected Utility Optimization",
            materialKind = "TEXTBOOK",
            inCourse = false,
            courseExamDate = null,
            inBooklist = false,
            testedSectionRatio = 0.0,
            readChapterRatio = 0.0,
            today = today,
        )
        assertEquals(BookStudyMode.READ_THROUGH, freshTextbook.mode)
        assertEquals(ExplainDetailLevel.DETAILED, freshTextbook.detailLevel)
        assertEquals(ExplainLanguage.BILINGUAL, freshTextbook.explainLang)
        assertFalse(freshTextbook.userLocked)
    }

    @Test
    fun switchingAllSixModesPreservesPageHighlightsAndNotes() {
        val dir = Files.createTempDirectory("study-mode-lossless-")
        try {
            val library = TextbookLibrary(dir)
            library.catalog()
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("library.sqlite")}").use { db ->
                db.createStatement().use { st ->
                    st.execute("INSERT OR REPLACE INTO books(id, title, format, reading_page, touched) VALUES('bk1', 'Sample Textbook', 'pdf', 179, 1000)")
                    st.execute("INSERT OR REPLACE INTO book_highlights(book, page, start, end, quote) VALUES('bk1', 179, 10, 38, 'synthetic-highlight')")
                    st.execute("INSERT OR REPLACE INTO book_notes(book, id, version, page, start, end, quote, note, text_sha256, revision, last_mutation_id, deleted) VALUES('bk1', 'n1', '1', 179, 10, 38, 'synthetic-highlight', 'synthetic-note', 'sha', 1, 'm1', 0)")
                }
            }

            var pref = StudyModeStore.loadOrResolve(library, "bk1", "Sample Textbook", 620)
            assertEquals(BookStudyMode.READ_THROUGH, pref.mode)

            for (mode in BookStudyMode.entries) {
                pref = StudyModeStore.save(
                    library,
                    pref.copy(
                        mode = mode,
                        userLocked = true,
                        detailLevel = ExplainDetailLevel.BRIEF,
                        explainLang = ExplainLanguage.ZH,
                        dailyMinutes = 30,
                        inquiryQuestion = "why does equilibrium fail?",
                    ),
                )
                val reloaded = StudyModeStore.loadOrResolve(library, "bk1")
                assertEquals(mode, reloaded.mode)
                assertTrue(reloaded.userLocked)
                assertEquals(ExplainDetailLevel.BRIEF, reloaded.detailLevel)
                assertEquals(ExplainLanguage.ZH, reloaded.explainLang)
                assertEquals(30, reloaded.dailyMinutes)
            }

            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("library.sqlite")}").use { db ->
                db.createStatement().use { st ->
                    st.executeQuery("SELECT reading_page FROM books WHERE id='bk1'").use { rs ->
                        assertTrue(rs.next())
                        assertEquals(179, rs.getInt(1))
                    }
                    st.executeQuery("SELECT COUNT(*) FROM book_highlights WHERE book='bk1' AND quote='synthetic-highlight'").use { rs ->
                        assertTrue(rs.next())
                        assertEquals(1, rs.getInt(1))
                    }
                    st.executeQuery("SELECT note FROM book_notes WHERE id='n1' AND deleted=0").use { rs ->
                        assertTrue(rs.next())
                        assertEquals("synthetic-note", rs.getString(1))
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
