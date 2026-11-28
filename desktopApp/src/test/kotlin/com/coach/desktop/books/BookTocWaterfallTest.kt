package com.coach.desktop.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookTocWaterfallTest {
    private val bookId = "c".repeat(64)

    @Test
    fun isolatedStoryTitlesOwnTheirNumberedSubdivisionsAndKeepExactOffsets() {
        val prose = "雨停后，人们走到河边，看着刚刚修好的桥。".repeat(28)
        val pages = listOf(
            BookPage(1, "出版资料。"),
            BookPage(2, "引言。\n\n 渡口\n\n$prose\n\n 灯塔\n\n第一章潮声\n\n$prose\n\n注释\n\n$prose"),
            BookPage(3, "沿岸的故事。\n\n 航线\n\n$prose\n*** END OF THE PROJECT GUTENBERG EBOOK ***\nSection 1. License"),
        )
        val structure = BookStructurePlanner.plan(bookId, "短篇集", "txt", pages)
        val chapters = structure.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }
        assertEquals(listOf("渡口", "灯塔", "航线"), chapters.map { it.title })
        assertEquals(chapters[1].id, structure.tocNodes.single { it.kind == TocNodeKind.SECTION }.parentId)
        assertEquals(TocNodeKind.BACK_MATTER, structure.tocNodes.last().kind)
        assertEquals(BookPageAnchor(2, pages[1].text.indexOf("渡口")), BookBoundaries.bodyStart(pages, structure))
        for (node in chapters) {
            assertTrue(pages[node.startPage - 1].text.startsWith(node.title, node.startOffset))
        }
    }

    @Test
    fun shortListsDoNotBecomeStoryCollections() {
        val pages = listOf(BookPage(1, "菜单\n\n渡口\n\n灯塔\n\n航线\n\n收尾。"))
        val structure = BookStructurePlanner.plan(bookId, "例子", "txt", pages)
        assertFalse(structure.tocNodes.any { it.source == HeadingSource.TEXT_LAYOUT })
    }

    @Test
    fun shortBookmarkedChapterOpeningRetainsItsPageUnlessExplicitlyFrontMatter() {
        assertFalse(BookBoundaries.looksLikeFrontMatter("Forest Trails\n1", "Forest Trails"))
        assertTrue(BookBoundaries.looksLikeFrontMatter("Forest Trails\n1", "Hill Walks"))
        assertTrue(BookBoundaries.looksLikeFrontMatter("Copyright\nForest Trails\n1", "Forest Trails"))
    }

    @Test
    fun stageARepairsPartHierarchyLigaturesAndDecouplesChunksFromCanonicalToc() {
        val pages = (1..30).map { p ->
            when (p) {
                1 -> BookPage(1, "Cover")
                2 -> BookPage(2, "Brief Contents\n1 Intro .... 5")
                5 -> BookPage(5, "Part I Foundations\n1.1 E\uFB03ciency and \uFB02exibility\n" + "Core concept paragraph. ".repeat(12))
                10 -> BookPage(10, "Part V Special Topics\n12 Policy Design\n12.1 Overview\n" + "Detailed policy discussion. ".repeat(14))
                20 -> BookPage(20, "References\n[1] Smith 2010")
                else -> BookPage(p, "Body text on page $p. " + "Economic agents weigh costs and benefits carefully. ".repeat(8))
            }
        }
        val flatOutline = listOf(
            BookOutlineItem("Cover", 1, 1, HeadingSource.PDF_OUTLINE),
            BookOutlineItem("Brief Contents", 1, 2, HeadingSource.PDF_OUTLINE),
            BookOutlineItem("Part I: Foundations", 1, 5, HeadingSource.PDF_OUTLINE),
            BookOutlineItem("1 Introduction to E\uFB03ciency", 2, 5, HeadingSource.PDF_OUTLINE),
            BookOutlineItem("1.1 Core Tradeo\uFB00s", 3, 5, HeadingSource.PDF_OUTLINE),
            BookOutlineItem("Part V: Special Topics", 3, 10, HeadingSource.PDF_OUTLINE),
            BookOutlineItem("12 Policy Design", 3, 10, HeadingSource.PDF_OUTLINE),
            BookOutlineItem("12.1 Overview", 4, 10, HeadingSource.PDF_OUTLINE),
            BookOutlineItem("References", 3, 20, HeadingSource.PDF_OUTLINE),
        )

        val structure = BookStructurePlanner.plan(bookId, "Synthetic Textbook", "pdf", pages, flatOutline)

        assertFalse(structure.tocNodes.any { it.title == "Cover" || it.title == "Brief Contents" })
        assertTrue(structure.tocNodes.none { Regex("""（\d+/\d+）$""").containsMatchIn(it.title) })
        assertFalse(structure.tocNodes.any { "\uFB03" in it.title || "\uFB00" in it.title })

        val part1 = structure.tocNodes.first { it.kind == TocNodeKind.PART && it.title.contains("Foundations") }
        val part5 = structure.tocNodes.first { it.kind == TocNodeKind.PART && it.title.contains("Special Topics") }
        val ch12 = structure.tocNodes.first { it.kind == TocNodeKind.CHAPTER && it.numberLabel == "12" }
        val sec121 = structure.tocNodes.first { it.kind == TocNodeKind.SECTION && it.numberLabel == "12.1" }
        val refs = structure.tocNodes.first { it.kind == TocNodeKind.BACK_MATTER }

        assertEquals(1, part1.level)
        assertEquals(null, part1.parentId)
        assertEquals(1, part5.level)
        assertEquals(null, part5.parentId)
        assertEquals(2, ch12.level)
        assertEquals(part5.id, ch12.parentId)
        assertEquals(3, sec121.level)
        assertEquals(ch12.id, sec121.parentId)
        assertEquals(1, refs.level)
        assertEquals(null, refs.parentId)
    }

    @Test
    fun stageBHandlesBriefDetailedDedupWrappedLinesAndMedianOffset() {
        val pages = (1..40).map { p ->
            when (p) {
                3 -> BookPage(3, "Brief Contents\n1 First Chapter   1\n2 Second Chapter   11")
                4 -> BookPage(
                    4,
                    "Contents\nPart I Core\n1 First Chapter 1\n1.1 Motivation 1\n" +
                        "2 Second Chapter 11\n2.1 Wrapped very long section\nheading on second line 14",
                )
                9 -> BookPage(9, "1 First Chapter\n1.1 Motivation\n" + "Opening chapter prose on fiscal incentives. ".repeat(18))
                19 -> BookPage(19, "2 Second Chapter\n" + "Chapter two examines supply responses in detail. ".repeat(18))
                22 -> BookPage(22, "2.1 Wrapped very long section heading on second line\n" + "Empirical findings confirm elasticity bounds. ".repeat(18))
                else -> BookPage(p, "Running Header\nGeneral body discussion for page $p. " + "Quantitative models require clear assumptions. ".repeat(10))
            }
        }
        val typo = mapOf(
            3 to listOf(
                PageLineTypography(3, "Brief Contents", "Brief Contents", 16.0, bold = true, yNorm = 0.10),
                PageLineTypography(3, "1 First Chapter   1", "1 First Chapter   1", 10.0, bold = false, yNorm = 0.20),
                PageLineTypography(3, "2 Second Chapter   11", "2 Second Chapter   11", 10.0, bold = false, yNorm = 0.25),
            ),
            4 to listOf(
                PageLineTypography(4, "Contents", "Contents", 16.0, bold = true, yNorm = 0.10),
                PageLineTypography(4, "Part I Core", "Part I Core", 11.0, bold = true, yNorm = 0.16),
                PageLineTypography(4, "1 First Chapter   1", "1 First Chapter   1", 10.0, bold = true, yNorm = 0.22),
                PageLineTypography(4, "1.1 Motivation   1", "1.1 Motivation   1", 10.0, bold = false, yNorm = 0.27),
                PageLineTypography(4, "2 Second Chapter   11", "2 Second Chapter   11", 10.0, bold = true, yNorm = 0.33),
                PageLineTypography(4, "2.1 Wrapped very long section", "2.1 Wrapped very long section", 10.0, bold = false, yNorm = 0.38),
                PageLineTypography(4, "heading on second line   14", "heading on second line   14", 10.0, bold = false, yNorm = 0.42),
            ),
        )

        val structure = BookStructurePlanner.plan(bookId, "No Outline Textbook", "pdf", pages, emptyList(), typo)
        assertEquals(HeadingSource.TOC_PAGE.name, structure.source)
        val chapters = structure.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }
        val sections = structure.tocNodes.filter { it.kind == TocNodeKind.SECTION }
        assertEquals(2, chapters.size)
        assertEquals(2, sections.size)
        assertTrue(sections.any { it.numberLabel == "2.1" && it.title.contains("second line") })
        // Printed page 1 -> PDF page 9 (offset +8), printed page 14 -> PDF page 22.
        assertEquals(9, chapters.first().startPage)
        assertEquals(22, sections.last().startPage)
    }

    @Test
    fun stageCRejectsFormulasCrossRefsAndZeroDecimalsWhileKeepingPaperHeadings() {
        val repeatingHeader = "Journal of Empirical Studies - Vol. 12"
        val pages = (1..8).map { p ->
            val heading = when (p) {
                1 -> "1. Introduction"
                3 -> "2. Theoretical Model"
                5 -> "2.1 Identification Assumptions"
                7 -> "3. Empirical Results"
                else -> "Continuation"
            }
            val body = buildString {
                append(repeatingHeader).append('\n')
                if (heading != "Continuation") append(heading).append('\n')
                append("See Section 2.1 for additional context.\n")
                append("0.453 Standard error in baseline specification\n")
                append("Y = a + b * X + 0.5 * Z\n")
                append("1998 Baseline survey sample\n")
                append("Substantive paragraph discussing identification strategy and equilibrium behavior on page $p. ".repeat(12))
            }
            BookPage(p, body)
        }
        val typo = (1..8).associateWith { p ->
            buildList {
                add(PageLineTypography(p, repeatingHeader, repeatingHeader, 9.0, bold = false, yNorm = 0.04))
                when (p) {
                    1 -> add(PageLineTypography(p, "1. Introduction", "1. Introduction", 14.0, bold = true, yNorm = 0.14))
                    3 -> add(PageLineTypography(p, "2. Theoretical Model", "2. Theoretical Model", 14.0, bold = true, yNorm = 0.14))
                    5 -> add(PageLineTypography(p, "2.1 Identification Assumptions", "2.1 Identification Assumptions", 12.0, bold = true, yNorm = 0.14))
                    7 -> add(PageLineTypography(p, "3. Empirical Results", "3. Empirical Results", 14.0, bold = true, yNorm = 0.14))
                }
                add(PageLineTypography(p, "See Section 2.1 for additional context.", "See Section 2.1 for additional context.", 11.0, bold = false, yNorm = 0.30))
                add(PageLineTypography(p, "0.453 Standard error in baseline specification", "0.453 Standard error in baseline specification", 11.0, bold = false, yNorm = 0.40))
                add(PageLineTypography(p, "Y = a + b * X + 0.5 * Z", "Y = a + b * X + 0.5 * Z", 11.0, bold = false, yNorm = 0.50))
                add(PageLineTypography(p, "1998 Baseline survey sample", "1998 Baseline survey sample", 11.0, bold = false, yNorm = 0.60))
            }
        }

        val structure = BookStructurePlanner.plan(bookId, "Working Paper", "pdf", pages, emptyList(), typo)
        val titles = structure.tocNodes.map { it.title }
        assertTrue("Expected numbered paper sections, got $titles", titles.contains("1. Introduction"))
        assertTrue(titles.contains("2. Theoretical Model"))
        assertTrue(titles.contains("2.1 Identification Assumptions"))
        assertTrue(titles.contains("3. Empirical Results"))
        assertFalse(titles.any { it.contains("0.453") || it.contains("Y = a") || it.contains("See Section") || it.contains("1998") })
    }

    @Test
    fun guardProtectsInteriorBodyPagesWithCopyrightNoticeAndCompletes620PagesQuickly() {
        val pages = (1..620).map { p ->
            when (p) {
                4 -> BookPage(4, "Copyright © 2014 Publisher Press.\nAll rights reserved.\nISBN 978-0-000-00000-0")
                277, 496 -> BookPage(
                    p,
                    "This section analyzes synthetic empirical distribution tables across cohort samples in detail.\n\n" +
                        "Source: Reprinted with permission from Synthetic Archive, © 1973 Research Institute, all rights reserved in original tables.\n\n" +
                        "Further regression estimates establish consistent coefficients across sample quintiles over five decades. ".repeat(8),
                )
                else -> BookPage(
                    p,
                    "Chapter ${(p - 1) / 25 + 1} Topic\nSection ${(p - 1) / 25 + 1}.${(p % 5) + 1} Analysis\n" +
                        "Standard synthetic textbook exposition explaining equilibrium outcomes and boundary conditions. ".repeat(14),
                )
            }
        }
        val outline = (1..24).map { ch ->
            BookOutlineItem("$ch Chapter Title $ch", 1, ((ch - 1) * 25 + 6).coerceAtMost(620), HeadingSource.PDF_OUTLINE)
        }

        val startNs = System.nanoTime()
        val structure = BookStructurePlanner.plan(bookId, "620p Benchmark", "pdf", pages, outline)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertEquals(BookPageRole.COPYRIGHT, structure.pageRole(4))
        assertEquals(BookPageRole.BODY, structure.pageRole(277))
        assertEquals(BookPageRole.BODY, structure.pageRole(496))
        assertTrue("Expected 620-page planning to finish well under 3000ms, took ${elapsedMs}ms", elapsedMs < 3000L)
    }

    @Test
    fun stageDLowConfidenceTriggersModelFallbackUntilApplied() {
        val longProsePages = (1..6).map { p ->
            BookPage(p, "Unmarked continuous narrative prose on page $p without any structural outline or numbered headings. ".repeat(18))
        }
        val raw = BookStructurePlanner.plan(bookId, "Unstructured Prose", "pdf", longProsePages)
        assertTrue("Expected unstructured long book to request model sectioning", raw.needsModelSectioning)

        val candidates = BookStructurePlanner.extractUnstructuredCandidates(bookId, longProsePages, raw.pageRoles)
        assertTrue(candidates.size >= 2)
        val applied = BookStructurePlanner.applyModelBoundaries(
            bookId = bookId,
            title = "Unstructured Prose",
            format = "pdf",
            pages = longProsePages,
            boundaries = listOf(
                ModelSectionBoundary(candidates.first().id, "Part One"),
                ModelSectionBoundary(candidates[candidates.size / 2].id, "Part Two"),
            ),
            fallback = raw,
        )
        assertFalse(applied.needsModelSectioning)
        assertEquals(2, applied.tocNodes.size)
        assertEquals(HeadingSource.MODEL_HAIKU.name, applied.source)
    }

    @Test
    fun midPageSourceOffsetChapterDoesNotActivateBeforeStartOffsetInWaterfall() {
        val page1 = BookPage(
            1,
            "第一章 管晏\n" +
                "管仲夷吾者潁上人也少時常與鮑叔牙遊。\n\n" +
                "太史公曰管仲世所謂賢臣然孔子小之。",
        )
        val ch2StartText = "第二章 老子韓非\n老子者楚苦縣厲鄉曲仁里人也。\n\n韓非者韓之諸公子也喜刑名法術之學。\n\n凡說之難非吾知之有以說之之難也。"
        val ch1ContText = "管仲既任政相齊以區區之齊在海濱通貨積財富國彊兵。\n\n鮑叔終善遇之不以為言天下不多管仲之賢而多鮑叔能知人也。\n\n"
        val page2Text = ch1ContText + ch2StartText
        val ch2Offset = page2Text.indexOf("第二章 老子韓非")
        val pages = listOf(page1, BookPage(2, page2Text))
        val outline = listOf(
            BookOutlineItem("第一章 管晏", level = 1, page = 1, source = HeadingSource.EPUB_TOC, startOffset = 0),
            BookOutlineItem("第二章 老子韓非", level = 1, page = 2, source = HeadingSource.EPUB_TOC, startOffset = ch2Offset),
        )
        val structure = BookStructurePlanner.plan(bookId, "合卷测试", "epub", pages, outline)
        val book = Textbook(
            id = bookId,
            title = "合卷测试",
            format = "epub",
            pages = pages,
            readingPage = 1,
            lessonIndex = 0,
            learning = false,
            promptSeen = false,
            questionMode = QuestionMode.CHOICE,
            structure = structure,
        )
        val targetOffset = page2Text.indexOf("凡說之難")
        assertEquals("第二章 老子韓非", BookChapterResolver.chapterForPage(book, 2, targetOffset)?.title)
        assertEquals("第一章 管晏", BookChapterResolver.chapterForPage(book, 2, 0)?.title)
        val secIdx = book.lessons.indexOfFirst { l -> l.blocks.any { it.page == 2 && targetOffset in it.start until it.end } }
        assertTrue(secIdx >= 0)
        assertEquals("第二章 老子韓非", BookChapterResolver.chapterForLesson(book, secIdx)?.title)
        assertEquals("第二章 老子韓非", ChapterCompanionGrouping.groupForSection(book, secIdx)?.chapterTitle)
        assertTrue(book.chapterSpans.first { it.title == "第二章 老子韓非" }.sectionIndices.isNotEmpty())
    }
}
