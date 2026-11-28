package com.coach.desktop.books

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CourseStoreAndWorkflowTest {
    @Test
    fun textbookCourseSkipsFrontMatterAndKeepsTheBodyTopicWhenRealigned() {
        val dir = Files.createTempDirectory("coach-course-body-start-")
        try {
            val library = TextbookLibrary(dir.resolve("library"), lazyPdfExtras = false)
            val book = library.stageBookImport(textbookWithFrontMatter(dir)).book
            assertEquals(32, book.bodyStart.importPage)
            assertEquals(32, book.readingPage)
            val authorIntro = book.tocNodes.first { it.startPage == 18 }
            val chapterOne = book.tocNodes.first { it.startPage == 32 }
            assertEquals(TocNodeKind.FRONT_MATTER, authorIntro.kind)

            val courseId = library.withCourseDatabase { db ->
                val (cid, _) = CourseStore.createCourse(db, "植物学", CourseExamProfile(), listOf(book.id))
                val legacyTopics = listOf(authorIntro, chapterOne).mapIndexed { index, node ->
                    CourseTopicPlan(
                        weekLabel = "第 ${index + 1} 章",
                        weekNum = null,
                        ord = (index + 1) * 10,
                        title = node.title,
                        examStatus = CourseTopicStatus.EXAMINABLE,
                        examWeight = 1.0,
                        sources = listOf(CourseTopicLinkPlan(
                            book.id, node.id, node.startPage, node.endPage, CourseMaterialRole.CORE_TEXTBOOK, node.title,
                        )),
                    )
                }
                CourseStore.applyAlignmentRespectingLocks(db, cid, CourseAlignmentPlan(topics = legacyTopics))
                cid
            }
            val reopened = TextbookLibrary(library.directory).courseDetail(courseId)
            val first = reopened.examinableTopics.single()
            assertEquals(chapterOne.id, first.sources.single().tocNodeId)
            assertEquals(book.bodyStart.importPage, first.sources.single().startPage)

            val realigned = library.withCourseDatabase { db ->
                CourseStore.savePracticeDraft(db, courseId, CourseTopicPracticeDraft(
                    first.topicId, text = "保留的答题草稿", activeSourceBookId = book.id, activePage = 33,
                ))
                CourseAutoAligner.alignLocally(db, courseId)
            }
            assertEquals(listOf(32, 39), realigned.examinableTopics.map { it.sources.single().startPage })
            assertEquals("第 1 章", realigned.examinableTopics.first().weekLabel)
            assertEquals(first.topicId, realigned.examinableTopics.first().topicId)
            assertEquals("保留的答题草稿", realigned.examinableTopics.first().practiceDraft?.text)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }


    @Test
    fun locksManualWeekAndTombstonesAcrossRealignmentAndPreservesBookOnRemove() {
        val dir = Files.createTempDirectory("coach-course-store-")
        try {
            val library = TextbookLibrary(dir, lazyPdfExtras = false)

            fun stageText(filename: String, content: String): Textbook {
                val p = dir.resolve(filename)
                Files.writeString(p, content)
                return library.stageBookImport(p).book
            }

            val syllabus = stageText(
                "Course-Handbook.md",
                "# Course Handbook\nWeek 4: Choice Under Uncertainty\nWeek 6: Adverse Selection & Insurance\n",
            )
            // Misnamed as Week 3 initially so auto-aligner puts it in Week 3 until corrected in-place
            val slidesWrongWeek = stageText(
                "Week3-Slides-Mislabelled.md",
                "# Adverse Selection Slides\nUnraveling occurs when average cost exceeds low-risk willingness to pay.\n",
            )
            val week4Key = stageText(
                "Tutorial-Week4-KEY.md",
                "# Week 4 Official Solutions\nCertainty equivalent equals $24.01 when risk premium is positive.\n",
            )
            val week6Transcript = stageText(
                "Week6-Recording.md",
                "[00:04:10] Lecture benchmark emphasizes pooling equilibrium breakdown.\n" +
                    "[00:11:20] Empirical health plan study: Cutler & Reber (1998).\n" +
                    "[00:18:05] Subsidy restores participation in pooled contracts.\n",
            )
            val textbook = stageText(
                "Core-Textbook.md",
                "# Chapter 1 Choice Under Uncertainty\nExpected utility weighs contingent outcomes by probabilities.\n\n" +
                    "# Chapter 2 Adverse Selection\nLow-risk buyers exit as pooled premiums climb above reservation value.\n\n" +
                    "# Chapter 9 International Trade Extensions\nTariff schedules lie outside core insurance curriculum.\n",
            )
            val reading = stageText(
                "Journal-Cutler-Reber.md",
                "# Quarterly Paper\nDeath spiral dynamics observed after fixed-dollar employer contribution switch.\n",
            )
            val notes = stageText(
                "My-Week6-Notes.md",
                "# Personal Revision Notes\nRemember 2022 past paper exact question asked about deductible schedules.\nFocus on risk pooling intuition.\n",
            )

            val detail = library.withCourseDatabase { db ->
                val (cid, rejected) = CourseStore.createCourse(
                    db = db,
                    title = "ECNM Applied Theory",
                    examProfile = CourseExamProfile(examDate = "2026-12-12", examDateTentative = true, examFormat = "MCQ + 300词论述", examFormatTentative = true),
                    materialBookIds = listOf(syllabus.id, slidesWrongWeek.id, week4Key.id, week6Transcript.id, textbook.id, reading.id, notes.id),
                )
                assertTrue(rejected.isEmpty())
                CourseAutoAligner.alignLocally(db, cid)
            }

            // Uncovered Chapter 9 folded into EXCLUDED (`本课不考内容`)
            val excluded = detail.excludedTopics.firstOrNull { it.title.contains("International Trade") }
            assertNotNull(excluded, "Uncovered textbook chapter must fold into 本课不考内容")

            // Authority roles inferred properly (L1 -> L2 -> L3 -> L4)
            val matById = detail.materials.associateBy { it.bookId }
            assertEquals(CourseMaterialRole.SYLLABUS, matById[syllabus.id]?.role)
            assertEquals(CourseMaterialRole.SOLUTION_KEY, matById[week4Key.id]?.role)
            assertEquals(CourseMaterialRole.TRANSCRIPT, matById[week6Transcript.id]?.role)
            assertEquals(CourseMaterialRole.CORE_TEXTBOOK, matById[textbook.id]?.role)
            assertEquals(CourseMaterialRole.USER_NOTES, matById[notes.id]?.role)

            // User-note spoiler regex sanitization triggered (Gate 1)
            assertTrue(detail.spoilerSanitizedCount >= 1)

            // Reassign mislabelled slide from Week 3 -> Week 6
            val week6BeforeHash = detail.examinableTopics.first { it.weekNum == 6 }.packSourcesHash
            library.withCourseDatabase { db ->
                CourseStore.updateMaterialAssignment(db, detail.courseId, slidesWrongWeek.id, 6, CourseMaterialRole.LECTURE_SLIDES)
            }
            val afterMove = library.courseDetail(detail.courseId)
            val movedMat = afterMove.materials.first { it.bookId == slidesWrongWeek.id }
            assertEquals(6, movedMat.weekNum)
            assertTrue(movedMat.userLocked, "Manual reassignment must set user_locked=1")
            val week6After = afterMove.examinableTopics.first { it.weekNum == 6 }
            assertTrue(week6After.sources.any { it.bookId == slidesWrongWeek.id })
            assertNotEquals(week6BeforeHash, week6After.packSourcesHash, "pack_sources_hash must refresh when topic sources change")

            // Restore excluded Chapter 9 to EXAMINABLE (`恢复为必考`) and re-align
            library.withCourseDatabase { db ->
                CourseStore.setTopicExamStatus(db, excluded.topicId, CourseTopicStatus.EXAMINABLE)
            }
            // Also unlink reading from week 6 via tombstone (-1)
            library.withCourseDatabase { db ->
                CourseStore.unlinkTopicSource(db, detail.courseId, week6After.topicId, slidesWrongWeek.id)
                CourseStore.unlinkTopicSource(db, detail.courseId, week6After.topicId, textbook.id)
            }

            // Re-run local auto-aligner: locked Week 6 assignment + restored chapter + -1 tombstone must survive
            val afterRealign = library.withCourseDatabase { db -> CourseAutoAligner.alignLocally(db, detail.courseId) }
            val slidePostRealign = afterRealign.materials.first { it.bookId == slidesWrongWeek.id }
            assertEquals(6, slidePostRealign.weekNum)
            assertTrue(slidePostRealign.userLocked)
            assertTrue(afterRealign.examinableTopics.any { it.topicId == excluded.topicId }, "Restored chapter must remain EXAMINABLE")
            val week6PostRealign = afterRealign.examinableTopics.first { it.weekNum == 6 }
            assertFalse(week6PostRealign.sources.any { it.bookId == textbook.id }, "Tombstoned -1 source must not be re-linked")

            // Remove material from course non-destructively (must still exist in library books table!)
            library.withCourseDatabase { db ->
                CourseStore.removeMaterialFromCourse(db, detail.courseId, reading.id)
            }
            val afterRemove = library.courseDetail(detail.courseId)
            assertFalse(afterRemove.materials.any { it.bookId == reading.id })
            assertNotNull(library.book(reading.id), "Removing from course must NOT delete the book from library")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun enforcesFourQualityGatesAndVision25PageCacheGuard() {
        val dir = Files.createTempDirectory("coach-course-gates-")
        try {
            // Gate 1: past exam rejection + spoiler note scrubbing (including 2026+ and "期末考过")
            assertTrue(CourseClientGates.isPastExamMaterial("ECNM10119-Past-Exam-Paper-2023.pdf"))
            assertTrue(CourseClientGates.isPastExamMaterial("2026期末试卷.pdf"))
            assertFalse(CourseClientGates.isPastExamMaterial("ECNM10119-Course-Syllabus-Handbook.pdf"))
            assertFalse(CourseClientGates.isPastExamMaterial("2026-Course-Handbook.pdf"))
            val scrubbed = CourseClientGates.sanitizeUserNote(
                "2023 真题 原题问了 insurance deductible.\n" +
                    "2026年期末考过逆向选择大题。\n" +
                    "期末考过类似赔偿计算题。\n" +
                    "Dec 2026 final exam asked deductible schedule.\n" +
                    "Exam Date: 2026-12-12 (tentative).\n" +
                    "期末复习重点：理解 risk premium。\n" +
                    "Normal intuition note stays intact.",
            )
            assertTrue(scrubbed.hasLeakage)
            assertEquals(4, scrubbed.redactedCount)
            assertFalse(scrubbed.cleanText.contains("2023 真题"))
            assertFalse(scrubbed.cleanText.contains("2026年期末考过"))
            assertFalse(scrubbed.cleanText.contains("期末考过类似"))
            assertFalse(scrubbed.cleanText.contains("Dec 2026 final exam"))
            assertTrue(scrubbed.cleanText.contains("Exam Date: 2026-12-12 (tentative)."))
            assertTrue(scrubbed.cleanText.contains("期末复习重点：理解 risk premium。"))
            assertTrue(scrubbed.cleanText.contains("Normal intuition note stays intact"))

            // Gate 2: 10/10 exact UTF-16 anchor verification across materials + [mm:ss] transcript
            val pagesByBook = linkedMapOf<String, List<BookPage>>()
            val candidates = mutableListOf<CourseCitationRef>()
            for (i in 1..10) {
                val bid = "book-$i"
                val line = if (i % 3 == 0) "[05:1$i] Transcript claim number $i for market unraveling analysis." else "Slide concept statement $i about certainty equivalent."
                val text = "Header $i\n$line\nTrailer"
                pagesByBook[bid] = listOf(BookPage(i, text))
                val start = text.indexOf(line)
                candidates += CourseCitationRef(
                    bookId = bid,
                    bookTitle = "Source $i",
                    role = if (i % 3 == 0) CourseMaterialRole.TRANSCRIPT else CourseMaterialRole.LECTURE_SLIDES,
                    page = i,
                    startUtf16 = start,
                    endUtf16 = start + line.length,
                    timestampLabel = if (i % 3 == 0) "05:1$i" else null,
                    quote = line,
                )
            }
            val verified = CourseClientGates.verifyCitations(candidates, pagesByBook)
            assertEquals(10, verified.size, "10/10 cross-material citations must verify")
            val invalid = CourseClientGates.verifySingleCitation(
                CourseCitationRef("book-1", "Source 1", CourseMaterialRole.CORE_TEXTBOOK, 1, 0, 12, null, "Hallucinated sentence never in book"),
                pagesByBook["book-1"].orEmpty(),
            )
            assertNull(invalid)

            // Gate 2b: client-side line-break hyphen & soft-hyphen verification (positive + negative)
            val hyphenPage = BookPage(
                12,
                "The synthetic trial showed a pos- itive change in out-\npatient visits and sig\u00ADnificant savings.\n" +
                    "A cost-effective plan ran in 2020- 2021 with health - income gaps and demand— and supply shifts.",
            )
            val clientPos = CourseClientGates.verifySingleCitation(
                CourseCitationRef(
                    "tb-hyp", "Synthetic Textbook", CourseMaterialRole.CORE_TEXTBOOK, 12, 0, 0, null,
                    "The synthetic trial showed a positive change in outpatient visits and significant savings.",
                ),
                listOf(hyphenPage),
            )
            assertNotNull(clientPos, "Client verifier must join 'pos- itive', 'out-\\npatient', and soft hyphen")
            assertTrue(hyphenPage.text.substring(clientPos.startUtf16, clientPos.endUtf16).contains("pos- itive"))
            assertNull(
                CourseClientGates.verifySingleCitation(
                    CourseCitationRef(
                        "tb-hyp", "Synthetic Textbook", CourseMaterialRole.CORE_TEXTBOOK, 12, 0, 0, null,
                        "A costeffective plan ran in 2020- 2021",
                    ),
                    listOf(hyphenPage),
                ),
                "Client verifier must NOT join unspaced compound hyphen 'cost-effective'",
            )
            assertNull(
                CourseClientGates.verifySingleCitation(
                    CourseCitationRef(
                        "tb-hyp", "Synthetic Textbook", CourseMaterialRole.CORE_TEXTBOOK, 12, 0, 0, null,
                        "ran in 20202021 with healthincome gaps and demandand supply shifts.",
                    ),
                    listOf(hyphenPage),
                ),
                "Client verifier must NOT join numeric ranges, spaced dashes, or em-dashes",
            )

            // Gate 3: English 8-gram and Chinese 12-gram overlap check
            val src = listOf(
                "Adverse selection causes average insurer claims to rise monotonically across risk classes.",
                "当高低风险投保人面对相同保费时低风险人群会率先退出保障池。",
            )
            assertTrue(
                CourseClientGates.auditOverlap(
                    "Explain why pooling equilibrium breaks down under asymmetric risk.",
                    src,
                ).passed,
            )
            assertFalse(
                CourseClientGates.auditOverlap(
                    "Why does adverse selection causes average insurer claims to rise monotonically?",
                    src,
                ).passed,
            )
            assertFalse(
                CourseClientGates.auditOverlap(
                    "请解释为什么低风险人群会率先退出保障池导致的后果。",
                    src,
                ).passed,
            )

            // Gate 4: BigDecimal 2-decimal precision verifier (including $24.01, $46.91, $237.50 checks)
            assertTrue(CourseClientGates.verifyEqualityClaim("0.5 * 16 + 0.5 * 36 - 1.99", BigDecimal("24.01")).passed)
            assertTrue(CourseClientGates.verifyEqualityClaim("(0.3 * 100) + (0.7 * 24.16)", BigDecimal("46.91")).passed)
            assertTrue(CourseClientGates.verifyEqualityClaim("250 - (5% * 250)", BigDecimal("237.50")).passed)
            assertFalse(CourseClientGates.verifyEqualityClaim("250 - (5% * 250)", BigDecimal("230.00")).passed)

            // Vision SQLite SHA-256 cache deduplication
            val library = TextbookLibrary(dir, lazyPdfExtras = false)
            val txtFile = dir.resolve("dummy-note.md").also { Files.writeString(it, "Page 1") }
            val imported = library.stageBookImport(txtFile).book
            val pngHash = CourseVisionCacheStore.sha256Hex("synthetic-png-bytes".toByteArray())
            library.withCourseDatabase { db ->
                CourseVisionCacheStore.saveRecognizedPages(
                    db,
                    imported.id,
                    listOf(VisionPageOutput(1, "Handwritten derivation step: EU = 24.01", pngHash)),
                )
                assertEquals("Handwritten derivation step: EU = 24.01", CourseVisionCacheStore.lookupBySha(db, pngHash))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

internal fun textbookWithFrontMatter(dir: Path): Path {
    val file = dir.resolve("Botany Textbook.pdf")
    val headings = mapOf(1 to "Copyright", 18 to "About the Authors", 19 to "Preface",
        32 to "Chapter 1 Light and Leaves", 39 to "Chapter 2 Plant Cells")
    PDDocument().use { pdf ->
        pdf.documentInformation.title = "Botany Textbook"
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val outline = PDDocumentOutline().also { pdf.documentCatalog.documentOutline = it }
        for (number in 1..40) {
            val page = PDPage()
            pdf.addPage(page)
            headings[number]?.let { heading ->
                outline.addLast(PDOutlineItem().apply {
                    title = heading
                    destination = PDPageFitDestination().apply { this.page = page }
                })
            }
            PDPageContentStream(pdf, page).use { out ->
                out.beginText()
                out.setFont(font, 18f)
                out.newLineAtOffset(50f, 710f)
                out.showText(headings[number] ?: "Botany textbook page $number")
                out.setFont(font, 11f)
                for (line in listOf(
                    "Photosynthesis uses light to transform water and carbon dioxide into stored sugars.",
                    "Chlorophyll in leaf cells absorbs light and transfers the energy to other molecules.",
                    "Plants can use the stored sugars to grow leaves, roots, and other tissues.",
                )) {
                    out.newLineAtOffset(0f, -25f)
                    out.showText(line)
                }
                out.endText()
            }
        }
        pdf.save(file.toFile())
    }
    return file
}
