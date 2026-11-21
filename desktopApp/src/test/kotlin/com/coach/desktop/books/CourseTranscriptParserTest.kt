package com.coach.desktop.books

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CourseTranscriptParserTest {

    @Test
    fun parsesSrtVttAndInlineTimestampMarkdownCleanly() {
        val srt = """
            1
            00:00:05,200 --> 00:00:12,800
            <i>Today we analyze</i> selection bias in insurance pools.

            2
            00:02:14,000 --> 00:02:20,500
            Equilibrium premiums depend on average expected cost.
        """.trimIndent()
        val srtCues = CourseTranscriptParser.parse(srt, "srt")
        assertEquals(2, srtCues.size)
        assertEquals("00:05", srtCues[0].timestampLabel)
        assertTrue(srtCues[0].text.contains("Today we analyze selection bias"))
        assertEquals("02:14", srtCues[1].timestampLabel)

        val vtt = """
            WEBVTT

            NOTE ignore this studio note

            00:01:02.100 --> 00:01:08.500
            <v Lecturer>Moral hazard changes post-contract effort.</v>
        """.trimIndent()
        val vttCues = CourseTranscriptParser.parse(vtt, "vtt")
        assertEquals(1, vttCues.size)
        assertEquals("01:02", vttCues[0].timestampLabel)
        assertTrue(vttCues[0].text.startsWith("Lecturer:"))

        val inlineMd = """
            # Lecture Recording Notes
            [00:04:15] First benchmark: actuarially fair contract equates premium to expected claim.
            [00:14:42] Second benchmark: separating contract induces self-selection via deductibles.
            [01:02:05] Summary check for week 6 empirical extension.
        """.trimIndent()
        assertTrue(CourseTranscriptParser.looksLikeTranscript(inlineMd, "md"))
        val mdPages = CourseTranscriptParser.extractToBookPages(inlineMd, "md")
        assertTrue(mdPages.isNotEmpty())
        val anchor = CourseTranscriptParser.findTimestampAnchor(mdPages, "14:42")
        assertNotNull(anchor)
        assertEquals("14:42", anchor.timestampLabel)
        assertTrue(mdPages[anchor.pageNumber - 1].text.substring(anchor.startUtf16, anchor.endUtf16).contains("[14:42]"))
    }

    @Test
    fun importsSrtVttAndTimestampedMarkdownThroughTextbookLibrary() {
        val dir = Files.createTempDirectory("coach-transcript-lib-")
        try {
            val library = TextbookLibrary(dir, lazyPdfExtras = false)
            val srtFile = dir.resolve("week4-recording.srt")
            Files.writeString(
                srtFile,
                "1\n00:03:10,000 --> 00:03:20,000\nRisk aversion lowers certainty equivalent.\n\n" +
                    "2\n00:07:25,000 --> 00:07:35,000\nCoinsurance shifts partial loss back to households.\n",
            )
            val vttFile = dir.resolve("week5-recording.vtt")
            Files.writeString(
                vttFile,
                "WEBVTT\n\n00:00:30.000 --> 00:00:40.000\nCutler and Reber study adverse selection in health plans.\n",
            )
            val stagedSrt = library.stageBookImport(srtFile)
            val stagedVtt = library.stageBookImport(vttFile)
            assertEquals("srt", stagedSrt.book.format)
            assertEquals("vtt", stagedVtt.book.format)
            assertTrue(stagedSrt.book.pageLabel(1).contains("第 1 段"))
            assertTrue(stagedVtt.book.pages.first().text.contains("[00:30]"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
