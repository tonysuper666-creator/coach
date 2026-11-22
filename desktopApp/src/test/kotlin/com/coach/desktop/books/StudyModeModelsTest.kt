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

    @Test
    fun migratesLegacyFiveColumnPackTableAndPreservesFiveColumnHarnessInsert() {
        val dir = Files.createTempDirectory("study-mode-migrate-")
        try {
            val library = TextbookLibrary(dir)
            library.catalog()
            val dbPath = dir.resolve("library.sqlite")
            // Seed legacy 5-column Stage 7 schema manually
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { db ->
                db.createStatement().use { st ->
                    st.execute("DROP TABLE IF EXISTS book_study_mode_packs")
                    st.execute(
                        """CREATE TABLE book_study_mode_packs(
                            book_id TEXT NOT NULL,
                            chapter_num INTEGER NOT NULL,
                            study_mode TEXT NOT NULL,
                            pack_json TEXT NOT NULL,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY(book_id, chapter_num, study_mode)
                        )""",
                    )
                    st.execute(
                        """INSERT INTO book_study_mode_packs VALUES(
                            'bk-mig', 8, 'BOOK_MAP',
                            '{"firstScreen":{"headlineZh":"Ch8","bigPictureThesisZh":"Migrated thesis"}}',
                            12345
                        )""",
                    )
                }
            }

            // Loading triggers automatic migration to 8-column table preserving legacy row
            val loaded = StudyModeStore.loadBookMapPack(library, "bk-mig", 8)
            assertEquals("Migrated thesis", loaded?.bigPictureThesisZh)

            // Verify legacy 5-column ReaderAcceptanceHarness INSERT still succeeds cleanly
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { db ->
                db.prepareStatement(
                    "INSERT OR REPLACE INTO book_study_mode_packs(book_id, chapter_num, study_mode, pack_json, updated_at) VALUES(?,?,?,?,?)",
                ).use { ps ->
                    ps.setString(1, "bk-mig")
                    ps.setInt(2, 8)
                    ps.setString(3, "EXAM_FOCUS")
                    ps.setString(4, """{"firstScreen":{"headlineZh":"Exam 8","mustStudySections":[{"sectionId":"8.1","weightTag":"CORE","examAngleZh":"Elasticity","pdfPage":142}]}}""")
                    ps.setLong(5, 99999L)
                    ps.executeUpdate()
                }
            }
            val exam = StudyModeStore.loadExamFocusPack(library, "bk-mig", 8)
            assertEquals("Exam 8", exam?.headlineZh)
            assertEquals(1, exam?.mustStudySections?.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun parsesAllLiveModesSavesBatchMetaAndInjectsCourseTopicsInWireBuilder() {
        val dir = Files.createTempDirectory("study-mode-v2-")
        try {
            val library = TextbookLibrary(dir)
            library.catalog()
            val dbPath = dir.resolve("library.sqlite")
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { db ->
                CourseStore.ensureReady(db)
                db.createStatement().use { st ->
                    st.execute("INSERT OR REPLACE INTO books(id, title, format, reading_page, touched) VALUES('bk-he', 'Health Economics', 'pdf', 141, 1000)")
                    st.execute("INSERT OR REPLACE INTO pages(book, page, text) VALUES('bk-he', 141, 'RAND Health Insurance Experiment coinsurance rate 3/2 elasticity.')")
                    st.execute("INSERT OR REPLACE INTO collections(id, kind, title, metadata_json, created_at, updated_at) VALUES('c-1', 'COURSE', 'ECNM10119', '{}', 1000, 1000)")
                    st.execute("INSERT OR REPLACE INTO collection_materials(collection_id, material_id, role, ordinal, added_at) VALUES('c-1', 'bk-he', 'PRIMARY', 1, 1000)")
                    st.execute("INSERT OR REPLACE INTO course_topics(topic_id, collection_id, week_label, ord, title, exam_status, updated_at) VALUES('t-1', 'c-1', '第 4 周', 1, 'Moral Hazard & RAND HIE', 'EXAMINABLE', 1000)")
                }
            }

            // Test diagnostic / problem / question / read_through live parsers + SQLite cache
            val diagJson = """{"skipPolicy":"Pass to skip","diagnosticGates":[{"gateId":"d1","sectionTitle":"8.1","targetConcept":"RAND","questionType":"CALCULATION","questionPrompt":"What happened?","options":["A","B"],"correctIndex":0,"passSkipNote":"Skip 8.1","missRemediation":"Review p.141","missJumpPdfPage":141,"quoteAnchor":"RAND Health Insurance Experiment coinsurance rate"}]}"""
            StudyModeStore.saveModePackJson(library, "bk-he", 8, BookStudyMode.DIAGNOSTIC_SKIP, ExplainDetailLevel.DETAILED, ExplainLanguage.BILINGUAL, "", diagJson)
            val probJson = """{"strategyNote":"Try exercise first","problems":[{"problemId":"p1","title":"HIE","exercisePdfPage":150,"exerciseQuoteAnchor":"Compute arc elasticity across coinsurance plans","attemptPrompt":"Try first","scaffoldingHints":["Step 1"],"keyDerivationCheck":"exact 3/2","bodyPdfPage":141,"quoteAnchor":"RAND Health Insurance Experiment coinsurance rate","answerOutline":["Outline"]}]}"""
            StudyModeStore.saveModePackJson(library, "bk-he", 8, BookStudyMode.PROBLEM_DRIVEN, ExplainDetailLevel.DETAILED, ExplainLanguage.BILINGUAL, "", probJson)
            val qJson = """{"questionFocus":"Moral hazard?","directConclusion":"Cost-sharing reduces utilization.","evidenceHits":[{"hitId":"e1","bodyJumpPage":141,"quoteAnchor":"RAND Health Insurance Experiment coinsurance rate","howItAnswersQuestion":"Direct evidence","limitOrCounterpoint":"Income effect"}],"argumentationScaffold":["Point 1"],"antiGhostwriteNotice":"Outline only","followUpQuestions":["Follow up?"]}"""
            StudyModeStore.saveModePackJson(library, "bk-he", 8, BookStudyMode.QUESTION_DRIVEN, ExplainDetailLevel.DETAILED, ExplainLanguage.BILINGUAL, "moral hazard?", qJson)

            val t0 = System.nanoTime()
            val d = StudyModeStore.loadDiagnosticSkipPack(library, "bk-he", 8)
            val p = StudyModeStore.loadProblemDrivenPack(library, "bk-he", 8)
            val q = StudyModeStore.loadQuestionDrivenPack(
                library,
                "bk-he",
                8,
                BookStudyPreference(bookId = "bk-he", mode = BookStudyMode.QUESTION_DRIVEN, inquiryQuestion = "moral hazard?"),
            )
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
            assertEquals(1, d?.gates?.size)
            assertEquals(1, p?.problems?.size)
            assertEquals("Cost-sharing reduces utilization.", q?.directConclusion)
            assertTrue(elapsedMs < 500.0, "Cached SQLite switch should be fast, took ${elapsedMs}ms")

            // Test batch meta save/load
            val batchObj = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"bookId":"bk-he","bookTitle":"Health Economics","chapterCount":24,"chapters":[{"chapterNum":8,"chapterTitle":"RAND","oneLiner":"Core HIE evidence","coreClaims":[{"claim":"Coinsurance matters","importance":"CORE","pdfPage":141,"quoteAnchor":"RAND Health Insurance Experiment coinsurance rate"}]}],"thematicClusters":[{"clusterId":"c1","title":"Demand","themeSummary":"Demand for care","chapterNums":[6,8]}],"dependencyEdges":[{"fromChapter":6,"toChapter":8,"relation":"PREREQUISITE","reason":"Builds on uncertainty"}],"fastSkimOrder40Min":[{"chapterNum":8,"minutes":8,"focus":"RAND table"}],"masterGlossary":[{"en":"Moral hazard","zh":"道德风险","chapterNum":8}],"costStats":{"estimatedCostUsd":0.024}}""",
            ) as kotlinx.serialization.json.JsonObject
            StudyModeStore.saveBookMapBatchResult(library, "bk-he", batchObj)
            val meta = StudyModeStore.loadBookMapMeta(library, "bk-he")
            assertEquals(24, meta?.chapterCount)
            assertEquals("Moral hazard", meta?.glossary?.singleOrNull()?.en)
            assertEquals(0.024, meta?.estimatedCostUsd)

            // Test WireBuilder injects SQLite course_topics & pages
            val book = Textbook(
                id = "bk-he",
                title = "Health Economics",
                format = "pdf",
                pages = listOf(BookPage(141, "RAND Health Insurance Experiment coinsurance rate 3/2 elasticity.")),
            )
            val group = ChapterSectionGroup(
                chapterId = "ch-8",
                chapterTitle = "Chapter 8 RAND",
                sections = listOf(BookLesson("8.1", "8.1 Intro", listOf(SourceBlock("b1", 141, 0, 10, false, "RAND")))),
                firstPage = 141,
                lastPage = 141,
            )
            val wire = StudyModeWireBuilder.buildModeRequestJson(library, book, group, 8, BookStudyPreference(bookId = "bk-he"))
            assertEquals("Moral Hazard & RAND HIE", wire["courseTopics"]?.let { (it as kotlinx.serialization.json.JsonArray)[0] }?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
            assertEquals(1, (wire["pages"] as kotlinx.serialization.json.JsonArray).size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun bookMapSectionPageResolutionAndStreamPreviewExtraction() {
        val legacyJson = """{"chapterNum":8,"oneLiner":"Ch8 summary","coreClaims":[{"claim":"Claim","importance":"CORE","pdfPage":193,"quoteAnchor":"quote"}],"sections":[{"sectionTitle":"8.1 Design","summary":"Design"},{"sectionTitle":"8.2 Results","summary":"Results","pdfPage":1}]}"""
        val parsed = StudyModeStore.parseBookMapPack(legacyJson)!!
        assertEquals(listOf(0, 1), parsed.sectionArchitecture.map { it.pdfPage })

        val chGroup = ChapterSectionGroup(
            chapterId = "ch-8",
            chapterTitle = "Chapter 8",
            sections = listOf(
                BookLesson("8.1", "8.1 Design", listOf(SourceBlock("b1", 190, 0, 10, false, "Design"))),
                BookLesson("8.2", "8.2 Results", listOf(SourceBlock("b2", 196, 0, 10, false, "Results"))),
            ),
            firstPage = 190,
            lastPage = 205,
        )
        assertEquals(190, resolveBookMapSectionPage(parsed.sectionArchitecture[0], 0, chGroup, parsed))
        assertEquals(196, resolveBookMapSectionPage(parsed.sectionArchitecture[1], 1, chGroup, parsed))

        val partialJson = """{"studyMode":"READ_THROUGH","learningArc":"本章主线：从逆向选择到市场失灵","sections":[{"sectionId":"s1","title":"柠檬市场","plainExplanation":"好车退出市场导致"""
        val preview = extractReadableStreamPreview(partialJson)
        assertTrue("本章主线：从逆向选择到市场失灵" in preview)
        assertTrue("柠檬市场" in preview)
        assertTrue("好车退出市场导致" in preview)
        assertFalse("READ_THROUGH" in preview)
        assertFalse("\"learningArc\"" in preview)
    }
}
