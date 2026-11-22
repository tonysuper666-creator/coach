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

            // Cycle all 3 adjustment knobs (detailLevel, explainLang, pacePreset) as well
            for (detail in ExplainDetailLevel.entries) {
                pref = StudyModeStore.save(library, pref.copy(detailLevel = detail, userLocked = true))
                assertEquals(detail, StudyModeStore.loadOrResolve(library, "bk1").detailLevel)
            }
            for (lang in ExplainLanguage.entries) {
                pref = StudyModeStore.save(library, pref.copy(explainLang = lang, userLocked = true))
                assertEquals(lang, StudyModeStore.loadOrResolve(library, "bk1").explainLang)
            }
            pref = StudyModeStore.save(library, pref.copy(dailyMinutes = 60, targetDate = null, userLocked = true))
            assertEquals(StudyPacePreset.MIN_60, StudyModeStore.loadOrResolve(library, "bk1").pacePreset)
            pref = StudyModeStore.save(library, pref.copy(dailyMinutes = null, targetDate = "2026-12-15", userLocked = true))
            assertEquals(StudyPacePreset.TARGET_DATE, StudyModeStore.loadOrResolve(library, "bk1").pacePreset)

            // Verify user_locked = 1 is never overwritten even after linking to a course
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("library.sqlite")}").use { db ->
                db.createStatement().use { st ->
                    st.execute("INSERT OR REPLACE INTO collections(id, kind, title, created_at, updated_at, sort_order, exam_date, last_active_anchor_json, source_hash, user_locked, archived) VALUES('col-1', 'COURSE', 'Health Econ', 1, 1, 0, '2026-12-01', '{\"page\":179,\"offset\":10}', '', 0, 0)")
                    st.execute("INSERT OR REPLACE INTO collection_materials(collection_id, material_id, role, ordinal, added_at) VALUES('col-1', 'bk1', 'PRIMARY', 0, 1)")
                }
            }
            val stillLocked = StudyModeStore.loadOrResolve(library, "bk1", "Sample Textbook", 620)
            assertEquals(pref.mode, stillLocked.mode)
            assertTrue(stillLocked.userLocked)

            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("library.sqlite")}").use { db ->
                db.createStatement().use { st ->
                    st.executeQuery("SELECT reading_page FROM books WHERE id='bk1'").use { rs ->
                        assertTrue(rs.next())
                        assertEquals(179, rs.getInt(1))
                    }
                    st.executeQuery("SELECT start, end, quote FROM book_highlights WHERE book='bk1' AND quote='synthetic-highlight'").use { rs ->
                        assertTrue(rs.next())
                        assertEquals(10, rs.getInt(1))
                        assertEquals(38, rs.getInt(2))
                    }
                    st.executeQuery("SELECT start, end, text_sha256, note FROM book_notes WHERE id='n1' AND deleted=0").use { rs ->
                        assertTrue(rs.next())
                        assertEquals(10, rs.getInt(1))
                        assertEquals(38, rs.getInt(2))
                        assertEquals("sha", rs.getString(3))
                        assertEquals("synthetic-note", rs.getString(4))
                    }
                    st.executeQuery("SELECT last_active_anchor_json FROM collections WHERE id='col-1'").use { rs ->
                        assertTrue(rs.next())
                        assertEquals("{\"page\":179,\"offset\":10}", rs.getString(1))
                    }
                }
            }
            val draftBefore = ChapterPracticeDraft(
                bookId = "bk1",
                tocNodeId = "ch-8",
                page = 179,
                exerciseHintLevels = mapOf("ex-9" to 2),
                pretestSelections = mapOf("q1" to "A"),
            )
            library.saveChapterPracticeDraft("bk1", "ch-8", 21, draftBefore)
            StudyModeStore.save(library, pref.copy(mode = BookStudyMode.BOOK_MAP, userLocked = true))
            StudyModeStore.save(library, pref.copy(mode = BookStudyMode.EXAM_FOCUS, userLocked = true))
            val draftAfter = library.loadChapterPracticeDraft("bk1", "ch-8")
            assertEquals(draftBefore.exerciseHintLevels, draftAfter?.exerciseHintLevels)
            assertEquals(draftBefore.pretestSelections, draftAfter?.pretestSelections)
            assertEquals(179, draftAfter?.page)

            // Verify structured BookMapOnePagerPack & ExamFocusTriagePack round-trip
            StudyModeStore.saveModePackJson(
                library,
                "bk1",
                8,
                BookStudyMode.BOOK_MAP,
                """{"firstScreen":{"headlineZh":"第 8 章一页纸","bigPictureThesisZh":"核心论断测试","sectionArchitecture":[{"sectionId":"8.1","roleLabelZh":"起点","oneLineZh":"说明","pdfPage":173}],"chapterLinks":{"prerequisiteChapter":"承第7章","downstreamChapter":"启第9章"}}}""",
            )
            val bm = StudyModeStore.loadBookMapPack(library, "bk1", 8)
            assertEquals("核心论断测试", bm?.bigPictureThesisZh)
            assertEquals(1, bm?.sectionArchitecture?.size)
            assertEquals("承第7章", bm?.prerequisiteChapter)

            StudyModeStore.saveModePackJson(
                library,
                "bk1",
                8,
                BookStudyMode.EXAM_FOCUS,
                """{"firstScreen":{"headlineZh":"第 8 章考纲分流","courseWeekBadgeZh":"对应第 4 周","mustStudySections":[{"sectionId":"8.2","weightTag":"★ 必考核心","examAngleZh":"推导挂牌门槛","pdfPage":174}],"skimOrSkipSections":[{"sectionId":"8.1","weightTag":"○ 背景速览","whySkimZh":"引入故事可略读"}],"examPitfallAlertZh":"区分 3/2 与 5/2"}}""",
            )
            val ef = StudyModeStore.loadExamFocusPack(library, "bk1", 8)
            assertEquals("对应第 4 周", ef?.courseWeekBadgeZh)
            assertEquals(1, ef?.mustStudySections?.size)
            assertEquals("8.2", ef?.mustStudySections?.first()?.sectionId)
            assertEquals(1, ef?.skimOrSkipSections?.size)
            assertEquals("区分 3/2 与 5/2", ef?.examPitfallAlertZh)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun numberedChaptersIgnoresFrontMatterAndCounts24Chapters() {
        fun lessonAt(id: String, title: String, p1: Int, p2: Int) = BookLesson(
            id = id,
            title = title,
            blocks = listOf(
                SourceBlock("$id-a", p1, 0, 10, true, title),
                SourceBlock("$id-b", p2, 11, 40, false, "body"),
            ),
        )
        val lessons = buildList {
            add(lessonAt("p2", "Front Title", 2, 9))
            add(lessonAt("p10", "Brief Contents", 10, 17))
            add(lessonAt("p19", "Preface（1/2）", 19, 31))
            for (ch in 1..24) {
                val p = 30 + ch * 20
                add(lessonAt("ch-$ch", "$ch Chapter Title $ch", p, p + 5))
                add(lessonAt("ch-$ch-1", "$ch.1 Section One", p + 2, p + 9))
                if (ch == 14) {
                    add(lessonAt("stray", "21.063 years 3.92 days", p + 3, p + 3))
                }
            }
        }
        val numbered = StudyModeChapterNumbering.numberedChaptersFromLessons(lessons, maxBookPage = 620)
        assertEquals(24, numbered.size)
        val ch8 = numbered.first { it.number == 8 }
        assertEquals(190, ch8.firstPage)
        assertEquals(209, ch8.lastPage)

        val structure = BookStructure(pageRoles = emptyMap(), sections = emptyList(), source = "PDF_OUTLINE").apply {
            resolvedLessons = lessons
        }
        val book = Textbook(
            id = "bk-24",
            title = "Synthetic Health Economics",
            format = "pdf",
            pages = (1..620).map { BookPage(it, "p$it") },
            readingPage = 195,
            structure = structure,
        )
        val pos = StudyModeChapterNumbering.resolve(book, currentPage = 195, chapterGroupFirstPage = 190)
        assertEquals(8, pos.chapterNumber)
        assertEquals(24, pos.totalChapters)
        assertEquals(9, pos.next?.number)
    }

    @Test
    fun singleTestedConceptInMultiPageBookDoesNotFalselyTriggerDiagnosticSkip() {
        val dir = Files.createTempDirectory("study-mode-denom-")
        try {
            val library = TextbookLibrary(dir)
            library.catalog()
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("library.sqlite")}").use { db ->
                db.createStatement().use { st ->
                    st.execute("INSERT OR REPLACE INTO books(id, title, format, reading_page, touched) VALUES('bk-md', 'Econ Notes', 'md', 1, 1000)")
                    for (p in 1..18) {
                        st.execute("INSERT OR REPLACE INTO pages(book, page, text) VALUES('bk-md', $p, 'Chapter $p economic equilibrium dynamics and utility analysis')")
                    }
                    st.execute("INSERT OR REPLACE INTO concept_mastery_ledger(concept_key, book_id, toc_node_id, canonical_name, p_mastery, has_active_test, updated_at) VALUES('c1', 'bk-md', 'sec-1', 'Stock vs Flow', 0.9, 1, 1000)")
                }
            }
            val resolved = StudyModeStore.loadOrResolve(library, "bk-md", "Econ Notes", 18)
            assertEquals(BookStudyMode.READ_THROUGH, resolved.mode)
            assertEquals(ExplainDetailLevel.DETAILED, resolved.detailLevel)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
