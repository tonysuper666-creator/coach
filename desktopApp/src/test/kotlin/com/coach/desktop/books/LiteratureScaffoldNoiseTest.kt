package com.coach.desktop.books

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import com.coach.ui.CoachTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LiteratureScaffoldNoiseTest {
    @Test fun candidatePoolDropsContentsTablesPublicationErrataAndCaptions() {
        val body = "Researchers compare local conditions to explain the gap in benefit use."
        val mentionsFigure = "The graph in Figure 12.5 shows a tradeoff between output and protection for each society."
        val tableCaption = "Table 1.1 Benefit use by region in 2005 researchers still need a full sentence here."
        val figureCaption = "Figure 12.5 The tradeoff between output and environmental protection is drawn as a frontier."
        val physicsCaption = "Figure 11.29 With the valve closed, two balloons of different sizes share one tube."
        val contents = """
            Table of Contents
            1 An Introduction to the Field .  .  .  .  .  .  .  .  .  .  .
            Social Patterns and Daily Life .  .  .  .  .  .  .  .  .  .  .
            Why the Method Matters .  .  .  .  .  .  .  .  .  .  .  .  .  .
            How Groups Hold Together .  .  .  .  .  .  .  .  .  .  .  .  .
        """.trimIndent()
        val publication = """
            OpenStax is a nonprofit based at Rice University, and the mission is wider access for students.
            This reader is licensed under a Creative Commons Attribution license, which means the class can share the file.
            Updates can be made when a later printing fixes a small mistake in a worked example.
        """.trimIndent()
        val errata = """
            Errata
            All sample press books undergo a rigorous review before the next printing.
            Errors sometimes occur in a professional textbook after the first release.
            Readers review all errata suggestions before the next printing of this volume.
        """.trimIndent()
        val mixed = """
            $tableCaption
            $body
            $figureCaption
            $physicsCaption
            $mentionsFigure
        """.trimIndent()
        val book = Textbook(
            id = "open-sample",
            title = "Sample Textbook",
            format = "pdf",
            pages = listOf(
                page(7, contents),
                page(5, publication),
                page(9, errata),
                page(17, mixed),
            ),
        )
        val quotes = LiteraturePassages.extractCandidates(book).map { it.anchor.quoteAnchor }
        assertTrue(quotes.any { body in it }, "body proposition stays: $quotes")
        assertTrue(quotes.any { "Figure 12.5 shows" in it }, "a sentence that only mentions a figure stays: $quotes")
        assertTrue(quotes.none { "Social Patterns" in it || "Table of Contents" in it }, "contents page stays out: $quotes")
        assertTrue(quotes.none { it.startsWith("Table 1.1") }, "table title stays out: $quotes")
        assertTrue(quotes.none { it.startsWith("Figure ") }, "figure captions stay out: $quotes")
        assertTrue(quotes.none { "OpenStax" in it || "Creative Commons" in it || "Rice University" in it }, "publication page stays out: $quotes")
        assertTrue(quotes.none { "errata" in it.lowercase() || "rigorous review" in it || "sometimes occur" in it }, "errata page stays out: $quotes")
        assertFalse(LiteraturePassages.bodySentence(tableCaption))
        assertFalse(LiteraturePassages.bodySentence(figureCaption))
        assertFalse(LiteraturePassages.bodySentence(physicsCaption))
        assertTrue(LiteraturePassages.bodySentence(body))
        assertTrue(LiteraturePassages.bodySentence(mentionsFigure))

        val laterChapter = """
            OpenStax hosts a free companion for this chapter.
            A second line names Rice University as the place the project started.
            $body
            $mentionsFigure
            Neighborhood surveys still find the same gap after the program ends.
        """.trimIndent()
        val laterQuotes = LiteraturePassages.extractCandidates(
            Textbook("later-sample", "Later Chapter", "pdf", listOf(page(80, laterChapter))),
        ).map { it.anchor.quoteAnchor }
        assertTrue(laterQuotes.any { body in it }, "a later chapter keeps its body sentence: $laterQuotes")
        assertTrue(laterQuotes.none { "OpenStax" in it || "Rice University" in it }, "publisher sentences on that page stay out: $laterQuotes")
    }

    @Test fun stationsUseTheFilteredPoolAndStayClickable() {
        val body = "Researchers compare local conditions to explain the gap in benefit use."
        val caption = "Figure 11.29 With the valve closed, two balloons of different sizes share one tube."
        val text = "$caption\n$body"
        val book = Textbook(
            "physics-sample",
            "Physics Sample",
            "pdf",
            listOf(page(477, text)),
        )
        val stations = LiteratureStationFollow.stations(book)
        assertEquals(listOf(body), stations.map { it.quote })
        assertEquals(477, stations.single().page)
        var opened: LiteratureStation? = null
        runSkikoComposeUiTest(size = Size(900f, 720f)) {
            setContent {
                CoachTheme(false) {
                    Surface(modifier = Modifier.padding(8.dp)) {
                        LiteratureStationFollowPane(
                            bookIds = listOf(book.id),
                            load = { _ -> stations },
                            onBack = {},
                            onJump = { opened = it },
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag("station-follow-ready").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("逐站跟读").assertIsDisplayed()
            onNodeWithTag("station-stop-${book.id}-0").performClick()
            assertEquals(body, opened?.quote)
        }
    }

    @Test fun courseAndGuideScreensExposeTheStationFollowEntry() {
        val detail = CourseDetailSnapshot(
            "course",
            "公开教材",
            CourseExamProfile(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )
        var fromCourse = 0
        var fromGuide = 0
        val root = Files.createTempDirectory("coach-station-follow-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            runSkikoComposeUiTest(size = Size(980f, 860f)) {
                setContent {
                    CoachTheme(false) {
                        Surface {
                            CourseScreen(
                                detail = detail,
                                busy = false,
                                notice = null,
                                onBack = {},
                                onOpenTopic = {},
                                onAddMaterials = {},
                                onRealign = {},
                                onUpdateMaterialAssignment = { _, _, _ -> },
                                onRemoveMaterial = {},
                                onRunHandwrittenVision = {},
                                onRenameTopic = { _, _ -> },
                                onUnlinkTopicSource = { _, _ -> },
                                onSetTopicStatus = { _, _ -> },
                                onUpdateExamProfile = {},
                                onOpenLiteratureGuide = {},
                                onOpenLiteratureFollow = { fromCourse += 1 },
                            )
                        }
                    }
                }
                onNodeWithTag("course-literature-follow").assertIsDisplayed()
                onNodeWithText("逐站跟读").performClick()
                assertEquals(1, fromCourse)
            }
            runSkikoComposeUiTest(size = Size(980f, 860f)) {
                setContent {
                    CoachTheme(false) {
                        Surface {
                            LiteratureGuidePane(
                                detail = detail,
                                library = library,
                                explainer = BookExplainer(),
                                onBack = {},
                                onOpenLiteratureFollow = { fromGuide += 1 },
                                onSource = {},
                            )
                        }
                    }
                }
                onNodeWithTag("guide-station-follow").assertIsDisplayed()
                onNodeWithText("逐站跟读").performClick()
                assertEquals(1, fromGuide)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

private fun page(number: Int, text: String) = BookPage(number, text, guideRegions = listOf(GuideRegion(text)))
