package com.coach.desktop.books

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.poi.xslf.usermodel.XMLSlideShow
import java.awt.geom.Rectangle2D
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CourseExamPointsTest {
    private fun writePdf(path: Path, lines: List<String>) {
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (line in lines) {
                val page = PDPage()
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { content ->
                    content.beginText()
                    content.setFont(font, 12f)
                    content.newLineAtOffset(40f, 650f)
                    content.showText(line)
                    content.endText()
                }
            }
            pdf.save(path.toFile())
        }
    }

    private fun writeSlides(path: Path, lines: List<String>) {
        XMLSlideShow().use { show ->
            lines.forEach { line ->
                show.createSlide().createTextBox().apply {
                    anchor = Rectangle2D.Double(40.0, 40.0, 520.0, 150.0)
                    text = line
                }
            }
            Files.newOutputStream(path).use(show::write)
        }
    }

    @Test
    fun standaloneTextbookBuildsExamPointsWithCitablePages() {
        val directory = Files.createTempDirectory("single-textbook")
        try {
            val library = TextbookLibrary(directory.resolve("library"), lazyPdfExtras = false)
            val file = directory.resolve("Astronomy-Textbook.pdf")
            writePdf(file, listOf(
                "Tidal locking: An orbit and a spin take equal time.",
                "Orbital resonance: Orbits repeat in a regular ratio.",
            ))
            val bookId = library.stageBookImport(file).book.id
            library.withCourseDatabase { db ->
                val (course, _) = CourseStore.createCourse(db, "Astronomy", CourseExamProfile(examEnabled = true), listOf(bookId))
                val detail = CourseAutoAligner.alignLocally(db, course)
                val points = detail.examinableTopics.filter { it.weekLabel == CourseExamPoints.LABEL }
                assertEquals(mapOf("Tidal locking" to 1, "Orbital resonance" to 2),
                    points.associate { it.title to it.sources.single().startPage })
                assertTrue(points.all { it.sources.single().bookId == bookId })
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun textbookDefinitionStaysWhenSlidesNeverNameIt() {
        val pages = listOf(
            ExamTeachingPage("tb", 1, CourseMaterialRole.CORE_TEXTBOOK, "Demand elasticity: Buyers respond to price."),
            ExamTeachingPage("tb", 2, CourseMaterialRole.CORE_TEXTBOOK, "Quality weight: A year of life scaled by health."),
            ExamTeachingPage("slides", 1, CourseMaterialRole.LECTURE_SLIDES, "Week 2 discussion applies DEMAND ELASTICITY in a new example."),
            ExamTeachingPage("slides", 2, CourseMaterialRole.LECTURE_SLIDES, "Opening remark for this week."),
        )
        val identified = CourseExamPoints.identifyFromPages("course", pages)
        assertEquals(setOf("Demand elasticity", "Quality weight"), identified.map { it.title }.toSet())
        val legacy = identified.filter { it.sources.distinctBy { source -> source.bookId }.size >= 2 }.map { it.title }
        assertEquals(listOf("Demand elasticity"), legacy)
        assertEquals(1, identified.single { it.title == "Quality weight" }.sources.size)
    }

    @Test
    fun twoCoursesKeepDistinctPageLinksAndOnlyDerivedExamTechniques() {
        val directory = Files.createTempDirectory("course-exam-points")
        try {
            val library = TextbookLibrary(directory, lazyPdfExtras = false)
            val fixtureDirectory = System.getenv("COACH_TON1890_FIXTURES")?.takeIf { it.isNotBlank() }?.let {
                Files.createTempDirectory(Files.createDirectories(Path.of(it)), "synthetic-courses-")
            } ?: directory
            val courses = listOf(
                listOf("Tidal locking", "Lunar phase", "Axial tilt", "Solar wind", "Stellar parallax",
                    "Event horizon", "Orbital period", "Escape velocity", "Light curve", "Transit depth"),
                listOf("Opportunity cost", "Price ceiling", "Consumer surplus", "Market clearing", "Tax incidence",
                    "Public goods", "Demand elasticity", "Marginal utility", "Adverse selection", "Capital stock"),
            )
            for ((index, terms) in courses.withIndex()) {
                val ids = listOf("Textbook", "Slides", "Handout").mapIndexed { roleIndex, role ->
                    val extension = if (role == "Slides") "pptx" else "pdf"
                    val file = fixtureDirectory.resolve("Course-$index-$role.$extension")
                    val orderedTerms = terms.drop(roleIndex) + terms.take(roleIndex)
                    val lines = orderedTerms.map { name ->
                        when (role) {
                            "Textbook" -> "$name: A synthetic textbook definition."
                            "Slides" -> "Week 3 discussion applies ${name.uppercase()} in a new example."
                            else -> "An independent worked example illustrates $name."
                        }
                    }
                    if (role == "Slides") writeSlides(file, lines) else writePdf(file, lines)
                    library.stageBookImport(file).book.id
                }
                val marker = "Reserved synthetic question wording $index"
                val paper = fixtureDirectory.resolve("Past-Paper-$index.pdf")
                writePdf(paper, terms.mapIndexed { i, name -> "${i + 1}. Compare $name. $marker." })
                library.withCourseDatabase { db ->
                    val (courseId, _) = CourseStore.createCourse(db, "Synthetic course $index", CourseExamProfile(examEnabled = true), ids)
                    val detail = CourseAutoAligner.alignLocally(db, courseId)
                    val points = detail.examinableTopics.filter { it.weekLabel == CourseExamPoints.LABEL }
                    assertEquals(terms.toSet(), points.map { it.title }.toSet())
                    val roles = listOf(CourseMaterialRole.CORE_TEXTBOOK, CourseMaterialRole.LECTURE_SLIDES, CourseMaterialRole.HANDOUT)
                    for (point in points) {
                        assertEquals(roles, point.sources.map { it.role })
                        for (source in point.sources) {
                            assertEquals(source.startPage, source.endPage)
                            assertTrue(CourseStore.loadPagesForBook(db, source.bookId).single { it.number == source.startPage }.text.contains(point.title, ignoreCase = true))
                        }
                    }
                    repeat(2) { assertEquals(10, CoursePastPaper.import(db, courseId, paper)) }
                    val refreshed = CourseAutoAligner.alignLocally(db, courseId)
                    assertEquals(points.map { it.topicId }.toSet(), refreshed.pastPaperTechniques.keys)
                    assertTrue(refreshed.pastPaperTechniques.values.all { it == listOf(ExamTechnique.COMPARISON) })
                    for (point in refreshed.examinableTopics.filter { it.weekLabel == CourseExamPoints.LABEL }) {
                        val pages = ids.associateWith { CourseStore.loadPagesForBook(db, it) }
                        assertFalse(CourseAutoAligner.buildTopicPackRequest(refreshed, point, pages).toString().contains(marker))
                        assertFalse(CourseAutoAligner.buildOfflineTopicPack(refreshed, point, pages).toString().contains(marker))
                    }
                    assertEquals(3, refreshed.materials.size)
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun syntheticTextbookFiltersTocPagesNoisyNamesAndEndOfChapterExercises() {
        val pages = listOf(
            // TOC page with section lines ending in page numbers
            ExamTeachingPage("tb", 1, CourseMaterialRole.CORE_TEXTBOOK, """
                Contents
                1.1 Orbital resonance 12
                2.3 Solar wind dynamics 45
                10.3 Tidal dissipation in moons 184
                12.1 Lifecycle of a catalyst 251
            """.trimIndent()),
            // Body page with valid concept + noisy fragments (short, lowercase, symbol, trailing page number)
            ExamTeachingPage("tb", 12, CourseMaterialRole.CORE_TEXTBOOK, """
                to live
                Model: A short label fragment.
                Movies: Another six char word.
                j=1: Formula noise.
                C2: Tiny label.
                n+1]-P: Bracket math noise.
                in the past: Lowercase fragment.
                orbital drift is getting faster: Lowercase clause.
                -> Example: Arrow noise.
                Adverse selection in real markets 184: Trailing page number noise.
                Orbital resonance: Periodic gravitational tugs lock two orbiting bodies into integer period ratios.
            """.trimIndent()),
            // Chapter end with exercises starting mid-page
            ExamTeachingPage("tb", 13, CourseMaterialRole.CORE_TEXTBOOK, """
                Solar wind dynamics: Charged plasma flowing outward from a star shapes planetary magnetospheres around every world in the system.

                Exercises
                Determine whether each of the following statements is true, false, or unclear.
                1. Exercise-only claim: Orbital resonance never occurs in icy moons.
            """.trimIndent()),
            // Multi-page continuation of exercises (PDF 230 / 231 pattern)
            ExamTeachingPage("tb", 14, CourseMaterialRole.CORE_TEXTBOOK, """
                2. Falsehood statement: Solar wind dynamics stop completely at two astronomical units.
                3. Analytical setup: Suppose a fictional satellite orbits a ringed planet. (a) Derive its period. (b) Explain your answer.
                4. Compare two imaginary asteroids and explain which one cools faster?
            """.trimIndent()),
            ExamTeachingPage("slides", 1, CourseMaterialRole.LECTURE_SLIDES,
                "Week 1 covers Orbital resonance, Solar wind dynamics, Model, Movies, to live, and Exercise-only claim."),
        )
        val identified = CourseExamPoints.identifyFromPages("synthetic-clean", pages)
        val titles = identified.map { it.title }.toSet()
        assertEquals(setOf("Orbital resonance", "Solar wind dynamics"), titles)
        // Verify no source link points to TOC page 1 or exercise continuation page 14
        for (point in identified) {
            assertTrue(point.sources.none { it.bookId == "tb" && it.startPage == 1 })
            assertTrue(point.sources.none { it.bookId == "tb" && it.startPage == 14 })
        }
        // Verify cleanTextbookPagesByNumber strips exercise text from page 13 and drops pages 1 and 14
        val bookPages = pages.filter { it.bookId == "tb" }.map { BookPage(it.page, it.text) }
        val cleaned = CourseExamPoints.cleanTextbookPagesByNumber(bookPages)
        assertFalse(1 in cleaned)
        assertTrue(12 in cleaned)
        assertTrue(13 in cleaned)
        assertFalse(cleaned[13]!!.contains("Exercise-only claim"))
        assertFalse(14 in cleaned)
    }

    @Test
    fun alignLocallyMatchesSlideSectionToChapterAndExcludesUntaughtFutureWeeks() {
        val dir = Files.createTempDirectory("coach-align-scope-")
        try {
            val library = TextbookLibrary(dir.resolve("lib"), lazyPdfExtras = false)
            // Build synthetic multi-chapter PDF textbook with TOC page + Chapters 1, 12 (with section 12.1), 15, 16
            val tbPath = dir.resolve("Synthetic-Textbook.pdf")
            val pageSpecs = listOf(
                1 to listOf("Contents", "1 Basics of Catalysis 2", "12 Catalysts and Innovation 3", "15 Future Reactions 5", "16 Advanced Polymers 6"),
                2 to listOf("Chapter 1 Basics of Catalysis", "Activation barrier: Minimum energy needed to initiate a synthetic chemical reaction across a membrane."),
                3 to listOf("Chapter 12 Catalysts and Innovation", "12.1 Lifecycle of a Catalyst", "Catalyst turnover: Number of reaction cycles completed per active site before deactivation occurs."),
                4 to listOf("12.2 Patent Protection for Catalysts", "Patent exclusivity: Legal protection window encouraging expensive laboratory research and synthesis.",
                    "Exercises", "Determine whether each of the following statements is true, false, or unclear.", "1. Catalyst turnover is always infinite."),
                5 to listOf("Chapter 15 Future Reactions", "Untaught reaction rate: A future topic scheduled for week nine that has not been taught yet."),
                6 to listOf("Chapter 16 Advanced Polymers", "Untaught polymer chain: A future topic scheduled for week ten that has not been taught yet."),
            )
            PDDocument().use { pdf ->
                pdf.documentInformation.title = "Synthetic Catalysis Textbook"
                val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
                val outline = PDDocumentOutline().also { pdf.documentCatalog.documentOutline = it }
                val pdfPages = mutableMapOf<Int, PDPage>()
                for ((num, lines) in pageSpecs) {
                    val page = PDPage()
                    pdf.addPage(page)
                    pdfPages[num] = page
                    PDPageContentStream(pdf, page).use { out ->
                        out.beginText()
                        out.setFont(font, 11f)
                        out.newLineAtOffset(40f, 700f)
                        for (line in lines) {
                            out.showText(line)
                            out.newLineAtOffset(0f, -18f)
                        }
                        out.endText()
                    }
                }
                fun addOutline(parent: PDOutlineItem?, title: String, pageNum: Int): PDOutlineItem {
                    val item = PDOutlineItem().apply {
                        this.title = title
                        destination = PDPageFitDestination().apply { page = pdfPages[pageNum] }
                    }
                    if (parent == null) outline.addLast(item) else parent.addLast(item)
                    return item
                }
                addOutline(null, "Contents", 1)
                addOutline(null, "Chapter 1 Basics of Catalysis", 2)
                val ch12 = addOutline(null, "Chapter 12 Catalysts and Innovation", 3)
                addOutline(ch12, "12.1 Lifecycle of a Catalyst", 3)
                addOutline(ch12, "12.2 Patent Protection for Catalysts", 4)
                addOutline(null, "Chapter 15 Future Reactions", 5)
                addOutline(null, "Chapter 16 Advanced Polymers", 6)
                pdf.save(tbPath.toFile())
            }

            // Handbook lists W1, W6 (taught) and W9-W10 (untaught future weeks!)
            val handbookPath = dir.resolve("Module-Handbook.md")
            Files.writeString(handbookPath, """
                # Module Handbook
                Week 1: Basics of Catalysis (Chapter 1)
                Week 6: Industrial Innovation and Drug Synthesis
                Week 9: Future Reactions
                Reading: Chapters 15-16
                Week 10: Advanced Polymers (Ch 16)
            """.trimIndent())

            // W1 slides and W6 slides (W6 slide 8 mentions "Lifecycle of a Catalyst", far past first 500 chars!)
            val w1Slides = dir.resolve("Week1-Slides.pptx")
            writeSlides(w1Slides, listOf(
                "Week 1 Basics of Catalysis",
                "Activation barrier determines reaction speed.",
            ))
            val w6Slides = dir.resolve("Week6-Slides.pptx")
            writeSlides(w6Slides, listOf(
                "Week 6 Lecture Overview and Administrative Announcements " + "padding ".repeat(60),
                "Agenda and Course Logistics " + "padding ".repeat(60),
                "Recap of Prior Weeks " + "padding ".repeat(60),
                "Lifecycle of a Catalyst",
                "Catalyst turnover and Patent exclusivity drive research incentives.",
            ))

            val tbId = library.stageBookImport(tbPath).book.id
            val hbId = library.stageBookImport(handbookPath).book.id
            val w1Id = library.stageBookImport(w1Slides).book.id
            val w6Id = library.stageBookImport(w6Slides).book.id

            val detail = library.withCourseDatabase { db ->
                val (cid, _) = CourseStore.createCourse(
                    db,
                    "Synthetic Catalysis",
                    CourseExamProfile(examEnabled = true),
                    listOf(tbId, hbId, w1Id, w6Id),
                )
                CourseAutoAligner.alignLocally(db, cid)
            }

            // Chapter 12 MUST be aligned to Week 6 (EXAMINABLE), NOT EXCLUDED!
            val w6Topic = detail.examinableTopics.first { it.weekNum == 6 }
            assertTrue(
                w6Topic.sources.any { it.bookId == tbId && it.startPage == 3 },
                "Chapter 12 must align to Week 6 via its section 'Lifecycle of a Catalyst'",
            )
            assertTrue(
                detail.excludedTopics.none { it.title.contains("Chapter 12") },
                "Chapter 12 must NOT be marked excluded",
            )

            // Untaught W9-W10 (Chapters 15-16) MUST be in pendingTopics, NOT generating exam points!
            assertTrue(detail.pendingTopics.any { it.weekNum == 9 })
            assertTrue(detail.pendingTopics.any { it.weekNum == 10 })
            val pointTitles = detail.examinableTopics
                .filter { it.weekLabel == CourseExamPoints.LABEL }
                .map { it.title }
                .toSet()
            assertTrue("Activation barrier" in pointTitles, "Taught Chapter 1 point must exist: $pointTitles")
            assertTrue("Catalyst turnover" in pointTitles, "Taught Chapter 12 point must exist: $pointTitles")
            assertFalse("Untaught reaction rate" in pointTitles, "Untaught Chapter 15 must NOT produce exam points")
            assertFalse("Untaught polymer chain" in pointTitles, "Untaught Chapter 16 must NOT produce exam points")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun numericChapterHeadingStopsExerciseStrippingAndKeepsSubsequentChapters() {
        // Synthetic textbook whose chapter headers are "<number> <title>" (e.g. "3 Demand for energy: ...")
        // with end-of-chapter "2.6 Exercises" followed immediately by Chapter 3 and Chapter 4.
        val pages = listOf(
            BookPage(10, "2 Price sensitivity in energy markets\n2.1 Measuring demand\nPrice elasticity: Measures how quantity demanded responds to price changes across households."),
            BookPage(11, "2.6 Exercises\nDetermine whether each of the following statements is true, false, or unclear.\n1. Price elasticity is always zero for every good.\n2. Suppose price doubles. (a) Compute revenue. (b) Explain your answer."),
            BookPage(12, "3. Evaluate whether subsidies flatten demand curves?\n4. Compare two imaginary grids and explain which one wastes less power?\n5. Suppose a fictional utility sets flat rates. (a) Derive surplus. (b) Discuss equity."),
            BookPage(13, "3 Demand for energy: the thermal model\n3.1 Thermal storage basics\nThermal inertia: Ability of building mass to slow indoor temperature swings during peak tariffs."),
            BookPage(14, "3.2 Peak load shifting\nLoad shifting: Moving flexible appliance demand away from high-cost evening hours into off-peak windows."),
            BookPage(15, "3.5 Exercises\nTrue, False, or Unclear\n1. Thermal inertia disappears when roofs are insulated."),
            BookPage(16, "4 Supply constraints and grid balancing\n4.1 Reserve margins\nReserve margin: Spare generation capacity held ready above forecast peak load to prevent outages."),
        )
        // Even WITHOUT explicit chapterStartPages, numeric headings "3 Demand..." and "4 Supply..." must terminate exercise tails!
        val cleaned = CourseExamPoints.cleanTextbookPagesByNumber(pages)
        assertTrue(10 in cleaned, "Chapter 2 body page 10 must stay")
        assertFalse(11 in cleaned, "Chapter 2 exercise page 11 must be stripped")
        assertFalse(12 in cleaned, "Chapter 2 exercise continuation page 12 must be stripped")
        assertTrue(13 in cleaned, "Chapter 3 opening page 13 ('3 Demand for energy...') must NOT be stripped")
        assertTrue(14 in cleaned, "Chapter 3 body page 14 must NOT be stripped")
        assertFalse(15 in cleaned, "Chapter 3 exercise page 15 must be stripped")
        assertTrue(16 in cleaned, "Chapter 4 opening page 16 ('4 Supply constraints...') must NOT be stripped")

        // Also verify CourseAutoAligner.buildTopicPackRequest keeps Chapter 3 & 4 blocks for exam points!
        val teachingPages = pages.map { ExamTeachingPage("tb", it.number, CourseMaterialRole.CORE_TEXTBOOK, it.text) } +
            listOf(ExamTeachingPage("slides", 1, CourseMaterialRole.LECTURE_SLIDES, "Week 2 covers Thermal inertia and Reserve margin."))
        val identified = CourseExamPoints.identifyFromPages("numeric-chapters", teachingPages)
        val names = identified.map { it.title }.toSet()
        assertTrue("Thermal inertia" in names, "Chapter 3 exam point must be identified: $names")
        assertTrue("Load shifting" in names, "Chapter 3 section point must be identified: $names")
        assertTrue("Reserve margin" in names, "Chapter 4 exam point must be identified: $names")

        val thermalTopic = CourseTopicEntry(
            topicId = "pt-thermal",
            collectionId = "c",
            weekLabel = CourseExamPoints.LABEL,
            weekNum = null,
            ord = 1,
            title = "Thermal inertia",
            examStatus = CourseTopicStatus.EXAMINABLE,
            userLocked = false,
            sources = listOf(CourseTopicSourceLink("pt-thermal", "tb", "Synthetic Textbook", "pdf", CourseMaterialRole.CORE_TEXTBOOK, 1, startPage = 13, endPage = 13)),
        )
        val detail = CourseDetailSnapshot("c", "Energy", CourseExamProfile(examEnabled = true), emptyList(), listOf(thermalTopic), emptyList(), emptyList())
        val req = CourseAutoAligner.buildTopicPackRequest(detail, thermalTopic, mapOf("tb" to pages))
        val blocks = req["blocks"] as kotlinx.serialization.json.JsonArray
        assertEquals(1, blocks.size, "buildTopicPackRequest must include Chapter 3 textbook page 13")
        assertTrue(blocks.toString().contains("Thermal inertia"))
    }

    @Test
    fun alignLocallyIgnoresOverviewSlidesAndCrossRefsAndExcludesUntaughtAndUncoveredChapters() {
        val dir = Files.createTempDirectory("coach-align-precision-")
        try {
            val library = TextbookLibrary(dir.resolve("lib"), lazyPdfExtras = false)
            val tbPath = dir.resolve("Precision-Textbook.pdf")
            val pageSpecs = listOf(
                1 to listOf("1 Foundations of Grid Economics", "Grid dispatch: Matching electricity generation to real-time demand."),
                2 to listOf("12 Storage and Innovation", "12.1 Lifecycle of a Battery", "Battery cycling: Repeated charge and discharge degradation in stationary storage."),
                3 to listOf("15 Regional Interconnectors", "Untaught interconnector flow: Cross-border transmission scheduled for week nine."),
                4 to listOf("16 Offshore Wind Pricing", "Untaught offshore auction: Future capacity auction scheduled for week ten."),
                5 to listOf("21 Geothermal Extensions", "Uncovered geothermal basin: Optional topic outside the exam scope."),
            )
            PDDocument().use { pdf ->
                pdf.documentInformation.title = "Grid Economics Textbook"
                val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
                val outline = PDDocumentOutline().also { pdf.documentCatalog.documentOutline = it }
                val pdfPages = mutableMapOf<Int, PDPage>()
                for ((num, lines) in pageSpecs) {
                    val page = PDPage()
                    pdf.addPage(page)
                    pdfPages[num] = page
                    PDPageContentStream(pdf, page).use { out ->
                        out.beginText()
                        out.setFont(font, 11f)
                        out.newLineAtOffset(40f, 700f)
                        for (line in lines) {
                            out.showText(line)
                            out.newLineAtOffset(0f, -18f)
                        }
                        out.endText()
                    }
                }
                fun addOutline(parent: PDOutlineItem?, title: String, pageNum: Int): PDOutlineItem {
                    val item = PDOutlineItem().apply {
                        this.title = title
                        destination = PDPageFitDestination().apply { page = pdfPages[pageNum] }
                    }
                    if (parent == null) outline.addLast(item) else parent.addLast(item)
                    return item
                }
                addOutline(null, "1 Foundations of Grid Economics", 1)
                val ch12 = addOutline(null, "12 Storage and Innovation", 2)
                addOutline(ch12, "12.1 Lifecycle of a Battery", 2)
                addOutline(null, "15 Regional Interconnectors", 3)
                addOutline(null, "16 Offshore Wind Pricing", 4)
                addOutline(null, "21 Geothermal Extensions", 5)
                pdf.save(tbPath.toFile())
            }

            val handbookPath = dir.resolve("Course-Handbook.md")
            Files.writeString(handbookPath, """
                # Course Handbook
                Week 1: Grid Dispatch Basics (Chapter 1)
                Week 6: Stationary Energy Storage
                Week 9: Regional Interconnectors (Chapter 15)
                Week 10: Offshore Wind Pricing (Chapter 16)
            """.trimIndent())

            // W1 slides include a "Course Schedule" slide listing Weeks 1-10 and Chapters 1, 12, 15, 16!
            val w1Slides = dir.resolve("Week1-Slides.pptx")
            writeSlides(w1Slides, listOf(
                "Week 1 Grid Dispatch Basics",
                "Course Schedule\nWeek 1: Ch 1\nWeek 6: Ch 12\nWeek 9: Ch 15\nWeek 10: Ch 16",
                "Grid dispatch balances load every minute.",
            ))
            // W6 slides include a cross-reference ("See Chapter 21") plus slide title "Lifecycle of a Battery"
            val w6Slides = dir.resolve("Week6-Slides.pptx")
            writeSlides(w6Slides, listOf(
                "Week 6 Energy Storage",
                "See Chapter 21 for optional geothermal extensions and see Chapter 15 for interconnectors.",
                "Lifecycle of a Battery\nBattery cycling determines replacement cost.",
            ))

            val tbId = library.stageBookImport(tbPath).book.id
            val hbId = library.stageBookImport(handbookPath).book.id
            val w1Id = library.stageBookImport(w1Slides).book.id
            val w6Id = library.stageBookImport(w6Slides).book.id

            val detail = library.withCourseDatabase { db ->
                val (cid, _) = CourseStore.createCourse(
                    db,
                    "Grid Economics",
                    CourseExamProfile(examEnabled = true),
                    listOf(tbId, hbId, w1Id, w6Id),
                )
                CourseAutoAligner.alignLocally(db, cid)
            }

            val w1Topic = detail.examinableTopics.first { it.weekNum == 1 }
            val w6Topic = detail.examinableTopics.first { it.weekNum == 6 }
            assertEquals(listOf(1), w1Topic.sources.filter { it.bookId == tbId }.map { it.startPage },
                "W1 must ONLY attach Chapter 1, never steal Ch 15/16 from Course Schedule slide")
            assertEquals(listOf(2), w6Topic.sources.filter { it.bookId == tbId }.map { it.startPage },
                "W6 must attach Chapter 12 via section title and never steal Ch 15 or Ch 21 via cross-ref")
            assertTrue(detail.excludedTopics.any { it.title.contains("Geothermal") },
                "Uncovered Chapter 21 must be excluded")
            assertTrue(detail.pendingTopics.any { it.weekNum == 9 && it.sources.any { s -> s.startPage == 3 } })
            assertTrue(detail.pendingTopics.any { it.weekNum == 10 && it.sources.any { s -> s.startPage == 4 } })

            val pointTitles = detail.examinableTopics
                .filter { it.weekLabel == CourseExamPoints.LABEL }
                .map { it.title }
                .toSet()
            assertTrue("Grid dispatch" in pointTitles)
            assertTrue("Battery cycling" in pointTitles)
            assertFalse("Untaught interconnector flow" in pointTitles)
            assertFalse("Untaught offshore auction" in pointTitles)
            assertFalse("Uncovered geothermal basin" in pointTitles)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun untaughtSyllabusChapterCannotBeStolenByEarlierWeekSlidesAndNoisyNamesAndExerciseContinuationsAreFiltered() {
        // 1. Noisy slide footers, boilerplate labels ("Sources", "Examples", "Introduction", "Side note"),
        //    cohort ranges ("Ages 55-64"), and long sentence headings must be excluded from exam points.
        val footer = "Dr. Pat Smith - Energy Systems"
        val noisePages = listOf(
            ExamTeachingPage("tb", 10, CourseMaterialRole.CORE_TEXTBOOK, """
                Sources: Synthetic data table notes.
                Examples: Synthetic worked illustration.
                Introduction: Opening remarks for this chapter.
                Side note: Brief historical digression.
                Ages 55-64: Demographic cohort slice.
                Grid stability depends on rapid frequency response across every region: Long sentence noise.
                Marginal abatement cost: Incremental cost of reducing one additional unit of emissions.
            """.trimIndent()),
            ExamTeachingPage("slides", 1, CourseMaterialRole.LECTURE_SLIDES, "$footer\nSources\nMarginal abatement cost overview"),
            ExamTeachingPage("slides", 2, CourseMaterialRole.LECTURE_SLIDES, "$footer\nExamples\nMarginal abatement cost case"),
            ExamTeachingPage("slides", 3, CourseMaterialRole.LECTURE_SLIDES, "$footer\nIntroduction\nSide note\nAges 55-64"),
            ExamTeachingPage("slides", 4, CourseMaterialRole.LECTURE_SLIDES, "$footer\nDr. Pat Smith - ECON10100"),
        )
        val cleanedPoints = CourseExamPoints.identifyFromPages("noise-filter", noisePages).map { it.title }.toSet()
        assertEquals(setOf("Marginal abatement cost"), cleanedPoints)

        // 2. Multi-page exercise continuation pages (including wrapped "7 An insured..." and 2-problem (a)/(b)/(c) pages)
        //    plus a "Part IV" chapter-list divider page must ALL be stripped when TOC chapter starts are known!
        val bookPages = listOf(
            BookPage(20, "2 Price sensitivity\nPrice elasticity: Response of quantity demanded to price changes across markets."),
            BookPage(21, "2.6 Exercises\nTrue, False, or Unclear\n1. Price elasticity is always infinite."),
            BookPage(22, "7 An imaginary household faces two tariffs over a single season without storage\n8 Another statement whose period wraps onto line two."),
            BookPage(23, "1. Suppose a utility offers two plans. (a) Compute revenue. (b) Derive surplus. (c) Compare outcomes?\n2. Explain how subsidies alter peak load."),
            BookPage(24, "Part IV\nInternational Grid Systems\n15 Regional Interconnectors\n16 The Nordic Grid Model\n17 The Continental Model"),
            BookPage(25, "3 Demand for energy: the thermal model\nThermal inertia: Building mass slows indoor temperature swings."),
        )
        val cleaned = CourseExamPoints.cleanTextbookPagesByNumber(bookPages, chapterStartPages = setOf(20, 25))
        assertEquals(setOf(20, 25), cleaned.keys)

        // 3. Untaught Chapter 16 owned by W10 in the Handbook (even by title only, without "Chapter 16"!)
        //    must NOT be stolen into W8 EXAMINABLE when W8 slides mention Ch 16 or a Ch 16 section!
        val dir = Files.createTempDirectory("coach-w10-lock-")
        try {
            val library = TextbookLibrary(dir.resolve("lib"), lazyPdfExtras = false)
            val tbPath = dir.resolve("Systems-Textbook.pdf")
            val pageSpecs = listOf(
                1 to listOf("14 Cost-Effectiveness Evaluation", "Incremental ratio: Extra cost per unit of gained output."),
                2 to listOf("16 The Nordic Grid Model", "16.1 Cost-Effectiveness in Practice", "Nordic dispatch pool: Future topic taught only in week ten."),
            )
            PDDocument().use { pdf ->
                pdf.documentInformation.title = "Energy Systems Textbook"
                val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
                val outline = PDDocumentOutline().also { pdf.documentCatalog.documentOutline = it }
                val pdfPages = mutableMapOf<Int, PDPage>()
                for ((num, lines) in pageSpecs) {
                    val page = PDPage()
                    pdf.addPage(page)
                    pdfPages[num] = page
                    PDPageContentStream(pdf, page).use { out ->
                        out.beginText()
                        out.setFont(font, 11f)
                        out.newLineAtOffset(40f, 700f)
                        for (line in lines) {
                            out.showText(line)
                            out.newLineAtOffset(0f, -18f)
                        }
                        out.endText()
                    }
                }
                fun addOutline(parent: PDOutlineItem?, title: String, pageNum: Int): PDOutlineItem {
                    val item = PDOutlineItem().apply {
                        this.title = title
                        destination = PDPageFitDestination().apply { page = pdfPages[pageNum] }
                    }
                    if (parent == null) outline.addLast(item) else parent.addLast(item)
                    return item
                }
                addOutline(null, "14 Cost-Effectiveness Evaluation", 1)
                val ch16 = addOutline(null, "16 The Nordic Grid Model", 2)
                addOutline(ch16, "16.1 Cost-Effectiveness in Practice", 2)
                pdf.save(tbPath.toFile())
            }
            val handbookPath = dir.resolve("Module-Handbook.md")
            Files.writeString(handbookPath, """
                # Module Handbook
                Week 8: Cost-Effectiveness Evaluation
                Week 10: The Nordic Grid Model
            """.trimIndent())
            val w8Slides = dir.resolve("Week8-Slides.pptx")
            writeSlides(w8Slides, listOf(
                "Week 8 Cost-Effectiveness Evaluation",
                "Reading: Chapter 14 and Chapter 16",
                "Cost-Effectiveness in Practice\nIncremental ratio guides technology adoption.",
            ))
            val tbId = library.stageBookImport(tbPath).book.id
            val hbId = library.stageBookImport(handbookPath).book.id
            val w8Id = library.stageBookImport(w8Slides).book.id
            val detail = library.withCourseDatabase { db ->
                val (cid, _) = CourseStore.createCourse(
                    db, "Energy Systems", CourseExamProfile(examEnabled = true), listOf(tbId, hbId, w8Id),
                )
                CourseAutoAligner.alignLocally(db, cid)
            }
            val w8Topic = detail.examinableTopics.first { it.weekNum == 8 }
            assertEquals(listOf(1), w8Topic.sources.filter { it.bookId == tbId }.map { it.startPage },
                "W8 must NOT steal Chapter 16 owned by pending W10 in Handbook")
            assertTrue(detail.pendingTopics.any { it.weekNum == 10 && it.sources.any { s -> s.startPage == 2 } })
            val pointTitles = detail.examinableTopics.filter { it.weekLabel == CourseExamPoints.LABEL }.map { it.title }.toSet()
            assertTrue("Incremental ratio" in pointTitles)
            assertFalse("Nordic dispatch pool" in pointTitles, "Untaught W10 Chapter 16 must produce zero exam points")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun bodyPageWithFigureSubpanelsAndQuestionsNotFollowingExercisesIsKeptAndCounteredSlideFootersAreFiltered() {
        // 1. Mid-chapter body page (p.44) has (a)(b)(c) figure/table panel captions + rhetorical questions
        //    and does NOT follow an exercise section. Neither p.44 nor p.45..53 may be stripped!
        //    Real end-of-chapter exercises (p.54) and their immediate continuation (p.55) MUST still be stripped.
        val figureBodyPage = """
            2.4 Experimental Evidence on Usage
            Figure 2.5 Usage shifts across service categories
            (a) Daytime usage rose by 35 percent after enrollment. (b) Nighttime usage showed smaller changes. (c) Peak demand remained stable.
            Why did daytime usage respond more strongly than peak demand? Does pricing alone explain the gap?
            Pilot enrollment lottery: Randomized assignment used to measure causal utilization effects across households.
        """.trimIndent()
        val bookPages = listOf(
            BookPage(40, "2 Pricing and Usage\nUsage elasticity: Responsiveness of service volume to user fees."),
            BookPage(44, figureBodyPage),
            BookPage(45, "Follow-up evidence after two years confirms persistent utilization gains across outpatient clinics.\nOutpatient utilization: Visits to non-hospital facilities observed during the pilot window."),
            BookPage(46, "Inpatient utilization also increased modestly in the second year of the synthetic trial."),
            BookPage(54, "2.6 Exercises\nDetermine whether each of the following statements is true, false, or unclear.\n1. Pilot enrollment lottery always lowers usage."),
            BookPage(55, "2. Suppose a subsidy halves user fees. (a) Compute new volume. (b) Derive welfare change. (c) Explain deadweight loss?"),
            BookPage(56, "3 Dynamic Capacity Planning\nCapacity buffer: Extra supply maintained to absorb demand shocks."),
        )
        assertFalse(CourseExamPoints.looksLikeExerciseContinuationPage(figureBodyPage))
        val cleaned = CourseExamPoints.cleanTextbookPagesByNumber(bookPages, chapterStartPages = setOf(40, 56))
        assertEquals(setOf(40, 44, 45, 46, 56), cleaned.keys)
        assertTrue(cleaned[44]!!.contains("Pilot enrollment lottery"))
        assertTrue(cleaned[45]!!.contains("Outpatient utilization"))

        // 2. Slide footers with varying slide counters ("1/30", "12 Lecturer - Course"), cross-deck bottom footers,
        //    conditional/conversational/truncated/cohort/boilerplate headings must all be filtered out.
        val pages = bookPages.map { ExamTeachingPage("tb", it.number, CourseMaterialRole.CORE_TEXTBOOK, it.text) } + listOf(
            ExamTeachingPage("tb", 47, CourseMaterialRole.CORE_TEXTBOOK, """
                If healthy: Conditional table header fragment.
                Hypothesis: Generic structural heading.
                Possible explanation: Generic discussion heading.
                Nonsmokers - oldest (55 yrs): Demographic table row label.
                Let us consider a thought experiment: Conversational opener.
                Consider the alternative: Imperative opener.
                A special note for non-local readers: Editorial aside.
                The labor market for: Truncated line-wrapped heading.
                Grid components and the: Truncated conjunction heading.
            """.trimIndent()),
            ExamTeachingPage("slides-w1", 1, CourseMaterialRole.LECTURE_SLIDES, "1 Alex Morgan - Energy Systems\nPilot enrollment lottery overview\nAlex Morgan - Energy Systems 1/30"),
            ExamTeachingPage("slides-w1", 2, CourseMaterialRole.LECTURE_SLIDES, "2 Alex Morgan - Energy Systems\nOutpatient utilization summary\nAlex Morgan - Energy Systems 2/30"),
            ExamTeachingPage("slides-w1", 3, CourseMaterialRole.LECTURE_SLIDES, "3 Alex Morgan - Energy Systems\nUsage elasticity notes\nAlex Morgan - Energy Systems 3/30"),
            ExamTeachingPage("slides-w2", 1, CourseMaterialRole.LECTURE_SLIDES, "Week 2 Lecture\nCapacity buffer discussion\nJordan Lee - Grid Policy"),
            ExamTeachingPage("slides-w3", 1, CourseMaterialRole.LECTURE_SLIDES, "Week 3 Lecture\nJordan Lee - Grid Policy: Review\nJordan Lee - Grid Policy"),
        )
        val titles = CourseExamPoints.identifyFromPages("r4-regression", pages, mapOf("tb" to setOf(40, 56))).map { it.title }.toSet()
        assertEquals(
            setOf(
                "Pricing and Usage",
                "Usage elasticity",
                "Experimental Evidence on Usage",
                "Pilot enrollment lottery",
                "Outpatient utilization",
                "Dynamic Capacity Planning",
                "Capacity buffer",
            ),
            titles,
        )
    }

    @Test
    fun tableCellLabelsAreRejectedWhileSectionSpanPagesRunningChapterHeadersAndMultiSlideTopicsStay() {
        // 1. Synthetic table rows ("All care: -0.22", "Dental care Source: ...", "100.0 All medical" near chart ticks)
        //    must NEVER become exam points.
        // 2. Real textbook chapter/section titles repeating as running headers ("192 ADVERSE SELECTION")
        //    and real multi-slide topic titles (3 slides out of 12 in one lecture deck) MUST NOT be suppressed.
        // 3. Multi-page TOC section spans (e.g. 12.4 spanning p.271..274 where p.272 omits the literal heading)
        //    and orphan Conclusion pages (p.280 inside Ch 12) must remain attached to exam points,
        //    while stripped exercise pages (p.281) must NEVER be pulled back in.
        val textbookPages = listOf(
            ExamTeachingPage("tb", 50, CourseMaterialRole.CORE_TEXTBOOK, """
                Table 2.1 Synthetic Elasticity Estimates
                Dental care Source: Synthetic Panel Survey (1999)
                All care: -0.22
                Inpatient: -0.17
                Outpatient: -0.31
                Restaurant meals: -2.27 (or -1.6)
                Fresh tomatoes: -4.6
                Experiment: Summary table of synthetic trials.
                Price elasticity of demand: Percentage change in quantity demanded divided by percentage change in price.
            """.trimIndent()),
            ExamTeachingPage("tb", 190, CourseMaterialRole.CORE_TEXTBOOK, """
                8 Adverse selection
                Adverse selection: Buyers with higher hidden risk selectively choose fuller coverage contracts.
            """.trimIndent()),
            ExamTeachingPage("tb", 191, CourseMaterialRole.CORE_TEXTBOOK, "191 ADVERSE SELECTION\nFurther analysis of hidden information in insurance pools."),
            ExamTeachingPage("tb", 192, CourseMaterialRole.CORE_TEXTBOOK, "192 ADVERSE SELECTION\nEmpirical tests of risk selection across tiers."),
            ExamTeachingPage("tb", 193, CourseMaterialRole.CORE_TEXTBOOK, "193 ADVERSE SELECTION\nPolicy tools for mitigating risk spirals."),
            ExamTeachingPage("tb", 271, CourseMaterialRole.CORE_TEXTBOOK, """
                12.4 Induced innovation
                Induced innovation: Larger expected market size shifts research investment toward corresponding technologies.
            """.trimIndent()),
            ExamTeachingPage("tb", 272, CourseMaterialRole.CORE_TEXTBOOK, """
                Demographic shifts expand expected sales volume and stimulate private laboratory funding across decades.
                50.0
                100.0
                150.0 All medical
                200.0
                250.0
            """.trimIndent()),
            ExamTeachingPage("tb", 275, CourseMaterialRole.CORE_TEXTBOOK, """
                12.5 Regulation of synthetic markets
                Safety approval trials verify efficacy before commercial release.
            """.trimIndent()),
            ExamTeachingPage("tb", 280, CourseMaterialRole.CORE_TEXTBOOK, """
                12.6 Conclusion
                Patent length and market size jointly shape research pipelines and retail pricing.
            """.trimIndent()),
            ExamTeachingPage("tb", 281, CourseMaterialRole.CORE_TEXTBOOK, """
                12.7 Exercises
                Determine whether each of the following statements is true, false, or unclear.
                1. Patent length has zero effect on research effort.
            """.trimIndent()),
        )
        // A 12-slide deck where "Lemon market equilibrium" is the top title on 3 consecutive slides (25% < 35%) -> MUST stay!
        val slidePages = listOf(
            ExamTeachingPage("slides-w4", 1, CourseMaterialRole.LECTURE_SLIDES, "Lemon market equilibrium\nAdverse selection unravels pooled contracts."),
            ExamTeachingPage("slides-w4", 2, CourseMaterialRole.LECTURE_SLIDES, "Lemon market equilibrium\nLow-risk buyers exit when premiums rise."),
            ExamTeachingPage("slides-w4", 3, CourseMaterialRole.LECTURE_SLIDES, "Lemon market equilibrium\nEquilibrium quality falls toward worst risk."),
            ExamTeachingPage("slides-w4", 4, CourseMaterialRole.LECTURE_SLIDES, "1. Aging population:\nDemographic shifts alter spending."),
        ) + (5..12).map { n ->
            ExamTeachingPage("slides-w4", n, CourseMaterialRole.LECTURE_SLIDES, "Slide topic $n\nAdditional lecture detail $n.")
        } + listOf(
            ExamTeachingPage("slides-w5", 1, CourseMaterialRole.LECTURE_SLIDES, "Review of Lemon market equilibrium and Aging population."),
        )

        val identified = CourseExamPoints.identifyFromPages(
            courseId = "r5-regression",
            pages = textbookPages + slidePages,
            chapterStartPagesByBook = mapOf("tb" to setOf(50, 190, 271)),
            sectionSpansByBook = mapOf(
                "tb" to listOf(
                    ExamSectionSpan("induced innovation", 271..274),
                    ExamSectionSpan("regulation of synthetic markets", 275..279),
                ),
            ),
            chapterRangesByBook = mapOf("tb" to listOf(50..55, 190..195, 271..285)),
        )
        val byTitle = identified.associateBy { it.title }
        val titles = byTitle.keys

        // Table cells / chart ticks / source labels must be completely rejected
        for (bad in listOf("Dental care Source", "All care", "Inpatient", "Outpatient", "Restaurant meals", "Fresh tomatoes", "All medical", "Experiment", "Aging population:")) {
            assertFalse(bad in titles, "Table/noise title '$bad' must be filtered out: $titles")
        }

        // Running-header chapter topic & multi-slide topic & cleaned trailing-colon topic must be preserved
        assertTrue("Price elasticity of demand" in titles)
        assertTrue("Adverse selection" in titles, "Chapter topic repeating in textbook running headers must stay: $titles")
        assertTrue("Lemon market equilibrium" in titles, "3-slide topic in a 12-slide deck must NOT be killed as a header: $titles")
        assertTrue("Aging population" in titles, "Trailing colon should be stripped cleanly: $titles")

        // Section span page 272 (doesn't repeat literal "induced innovation") must be linked to "Induced innovation"
        val inducedPages = byTitle.getValue("Induced innovation").sources.filter { it.bookId == "tb" }.map { it.startPage }.toSet()
        assertTrue(271 in inducedPages && 272 in inducedPages, "Section 12.4 span must include p.272: $inducedPages")

        // Orphan Conclusion page 280 must be attached to preceding Ch 12 section, while exercise page 281 stays excluded
        val allLinkedTbPages = identified.flatMap { it.sources }.filter { it.bookId == "tb" }.map { it.startPage }.toSet()
        assertTrue(280 in allLinkedTbPages, "Orphan chapter conclusion page 280 must be covered: $allLinkedTbPages")
        assertFalse(281 in allLinkedTbPages, "Stripped exercise page 281 must never be re-attached: $allLinkedTbPages")
    }

    @Test
    fun phraseInBothTableRowAndTextbookSectionIsRetainedAlongWithBodyBackedTermsAndTocSections() {
        // Synthetic textbook where:
        // 1. "Outpatient demand elasticity" appears BOTH as a numeric table row ("Outpatient demand elasticity: -0.31")
        //    AND as a dedicated textbook section heading ("2.3 Outpatient demand elasticity") -> MUST be retained!
        //    "Fresh tomatoes" appears ONLY as a numeric table row ("Fresh tomatoes: -4.6") -> MUST be filtered out!
        // 2. "Regional Trial Experiment" appears once per deck across 3 weekly slide decks AND in textbook body prose -> MUST be retained!
        // 3. Math-formula slide definitions ("Risk-averse: U(E[X]) > E[U(X)]"), short slide subtitles ("Expected value: Definition",
        //    "Transition to outpatient care: 1980-2000"), textbook bulleted term definitions ("• Visual analogue scale (VAS). ...",
        //    "• Time trade-off (TTO). ..."), and official TOC sections containing uppercase "IS" ("9.2 Indifference curves in IH–IS space")
        //    or 5-word section titles ("1.2 Health is uncertain and contagious") MUST all be retained!
        // 4. Non-topic prose sentences ("But this story relies on two critical assumptions", "A market equilibrium satisfies three conditions")
        //    must still be filtered out!
        val textbookPages = listOf(
            ExamTeachingPage("tb", 33, CourseMaterialRole.CORE_TEXTBOOK, """
                1.2 Health is uncertain and contagious
                Uncertainty in health outcomes motivates formal risk-sharing contracts across households.
            """.trimIndent()),
            ExamTeachingPage("tb", 50, CourseMaterialRole.CORE_TEXTBOOK, """
                Table 2.1 Synthetic Elasticities
                Outpatient demand elasticity: -0.31
                Fresh tomatoes: -4.6
                2.3 Outpatient demand elasticity
                Outpatient demand elasticity measures how clinic visit counts change when user fees vary.
                The Regional Trial Experiment randomized households across coinsurance tiers and tracked utilization over five years.
                A sustained Transition to outpatient care reshaped hospital budgets across decades.
                But this story relies on two critical assumptions: Prose sentence fragment.
                A market equilibrium satisfies three conditions: Another prose sentence fragment.
            """.trimIndent()),
            ExamTeachingPage("tb", 160, CourseMaterialRole.CORE_TEXTBOOK, """
                Expected value equals the probability-weighted average across uncertain financial states.
                A Risk-averse decision maker strictly prefers a certain payoff equal to the gamble's expected value.
            """.trimIndent()),
            ExamTeachingPage("tb", 194, CourseMaterialRole.CORE_TEXTBOOK, """
                9.2 Indifference curves in IH–IS space
                Indifference curves in IH–IS space map constant expected utility across healthy and sick income states.
            """.trimIndent()),
            ExamTeachingPage("tb", 320, CourseMaterialRole.CORE_TEXTBOOK, """
                Quality weights can be elicited using three standard preference methods:
                • Visual analogue scale (VAS). Respondents place a health state on a zero-to-one hundred line.
                • Time trade-off (TTO). Respondents choose between fewer years in full health and longer life with illness.
            """.trimIndent()),
        )
        val slidePages = listOf(
            ExamTeachingPage("slides-w1", 1, CourseMaterialRole.LECTURE_SLIDES, "Regional Trial Experiment\nRandomized design and utilization findings."),
            ExamTeachingPage("slides-w4", 1, CourseMaterialRole.LECTURE_SLIDES, "Expected value: Definition\nRisk-averse: U(E[X]) > E[U(X)]\nRegional Trial Experiment recap."),
            ExamTeachingPage("slides-w5", 1, CourseMaterialRole.LECTURE_SLIDES, "Regional Trial Experiment\nMoral hazard evidence from coinsurance arms."),
            ExamTeachingPage("slides-w6", 1, CourseMaterialRole.LECTURE_SLIDES, "Regional Trial Experiment\nFollow-up comparison across cohorts."),
            ExamTeachingPage("slides-w8", 1, CourseMaterialRole.LECTURE_SLIDES, "Transition to outpatient care: 1980-2000\nHospital volume shifts over time."),
        )

        val identified = CourseExamPoints.identifyFromPages(
            courseId = "r6-body-backed",
            pages = textbookPages + slidePages,
            chapterStartPagesByBook = mapOf("tb" to setOf(33, 50, 160, 194, 320)),
            sectionSpansByBook = mapOf(
                "tb" to listOf(
                    ExamSectionSpan("Health is uncertain and contagious", "health is uncertain and contagious", 33..33),
                    ExamSectionSpan("Outpatient demand elasticity", "outpatient demand elasticity", 50..50),
                    ExamSectionSpan("Indifference curves in IH–IS space", "indifference curves in ih–is space", 194..194),
                ),
            ),
            chapterRangesByBook = mapOf("tb" to listOf(33..37, 50..55, 160..170, 194..200, 320..325)),
        )
        val titles = identified.map { it.title }.toSet()

        assertTrue("Outpatient demand elasticity" in titles, "Phrase in BOTH table row and textbook section MUST stay: $titles")
        assertFalse("Fresh tomatoes" in titles, "Phrase ONLY in numeric table row MUST be filtered: $titles")
        assertFalse("But this story relies on two critical assumptions" in titles)
        assertFalse("A market equilibrium satisfies three conditions" in titles)

        assertTrue("Regional Trial Experiment" in titles, "Cross-cutting topic across 3 slide decks + textbook prose MUST stay: $titles")
        assertTrue("Expected value" in titles, "Slide title with 1-word subtitle + textbook prose MUST stay: $titles")
        assertTrue("Risk-averse" in titles, "Math-formula slide definition + textbook prose MUST stay: $titles")
        assertTrue("Transition to outpatient care" in titles, "Body-backed slide title with year subtitle MUST stay: $titles")
        assertTrue("Visual analogue scale" in titles, "Textbook bulleted term definition MUST stay: $titles")
        assertTrue("Time trade-off" in titles, "Textbook bulleted term definition MUST stay: $titles")
        assertTrue("Indifference curves in IH–IS space" in titles, "TOC section with uppercase IS MUST stay: $titles")
        assertTrue("Health is uncertain and contagious" in titles, "Official 5-word TOC section MUST stay: $titles")
    }
}
