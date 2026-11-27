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
}
