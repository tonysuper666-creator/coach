package com.coach.desktop.regression

import com.coach.desktop.books.BookExplainer
import com.coach.desktop.books.BookLesson
import com.coach.desktop.books.BookPage
import com.coach.desktop.books.BookPageRole
import com.coach.desktop.books.BookStructure
import com.coach.desktop.books.BookTocNode
import com.coach.desktop.books.ExplainConsent
import com.coach.desktop.books.HeadingSource
import com.coach.desktop.books.SectionGuideController
import com.coach.desktop.books.SourceBlock
import com.coach.desktop.books.Textbook
import com.coach.desktop.books.TocNodeKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * TON-1848: explaining the current screen starts at the prose, not the leading X-Ray caption.
 */
class R1848ScreenExplainTest {
    @Test
    fun anchorAtZeroSkipsLeadingImageCaption() {
        val dir = Files.createTempDirectory("coach-r1848-screen-")
        try {
            val caption = "X-Ray photograph of the hand showing bone structure."
            val intro = "INTRODUCTION The opening prose of this page explains the argument."
            val pageText = "$caption\n\n$intro"
            val introStart = pageText.indexOf(intro)
            val lessons = listOf(
                BookLesson("caption", "X-Ray", listOf(SourceBlock("cap", 1, 0, caption.length, false, caption))),
                BookLesson(
                    "intro",
                    "INTRODUCTION",
                    listOf(SourceBlock("intro", 1, introStart, introStart + intro.length, false, intro)),
                ),
            )
            val structure = BookStructure(
                pageRoles = mapOf(1 to BookPageRole.BODY),
                sections = emptyList(),
                source = HeadingSource.PDF_OUTLINE.name,
                tocNodes = listOf(
                    BookTocNode(
                        id = "ch1",
                        parentId = null,
                        ordinal = 0,
                        level = 1,
                        kind = TocNodeKind.CHAPTER,
                        numberLabel = "1",
                        title = "Chapter 1",
                        startPage = 1,
                        endPage = 1,
                        source = HeadingSource.PDF_OUTLINE,
                    ),
                ),
            ).also { it.deferResolvedLessons { lessons } }
            val book = Textbook(
                id = "synthetic-screen",
                title = "Synthetic",
                format = "pdf",
                pages = listOf(BookPage(1, pageText)),
                structure = structure,
            )
            val controller = SectionGuideController(
                book = book,
                library = null,
                consent = ExplainConsent(dir),
                explainer = BookExplainer(),
                scope = CoroutineScope(SupervisorJob()),
            )
            assertEquals(1, controller.sectionIndexForAnchor(1, 0))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
