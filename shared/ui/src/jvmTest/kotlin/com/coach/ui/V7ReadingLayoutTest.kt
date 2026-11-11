package com.coach.ui

import com.coach.ui.v7.*
import kotlin.test.*

class V7ReadingLayoutTest {
    @Test fun annotationsStayAtTheirLineUnlessAnEarlierCardNeedsTheSpace() {
        val anchors = listOf(45, 50, 320)
        val heights = listOf(80, 110, 60)
        val tops = marginOffsets(anchors, heights, gap = 12, header = 24)
        for (i in tops.indices) {
            assertTrue(tops[i] >= anchors[i], "A note must not precede its marked line")
            if (i > 0) assertTrue(tops[i] >= tops[i - 1] + heights[i - 1] + 12, "Cards must not overlap")
        }
        assertEquals(anchors.last(), tops.last(), "Once there is room, alignment must return to the source line")
    }

    @Test fun missingOrScrolledPastAnchorsStillProduceReadableOrderedNotes() {
        val heights = listOf(140, 400, 80)
        val tops = marginOffsets(listOf(null, -60, null), heights, 12, 24)
        assertTrue(tops.first() >= 24)
        tops.zipWithNext().forEachIndexed { i, (first, second) -> assertTrue(second >= first + heights[i] + 12) }
    }

    @Test fun availableWidthDeterminesColumnsAcrossRotationAndSplitScreen() {
        assertEquals(DeskWindow.OuterLandscape, deskWindow(699f, 411f))
        assertEquals(DeskWindow.Medium, deskWindow(700f, 933f))
        assertEquals(DeskWindow.Medium, deskWindow(704f, 933f))
        assertEquals(DeskWindow.Medium, deskWindow(986f, 624f))
        assertEquals(DeskWindow.Medium, deskWindow(1099f, 700f))
        assertEquals(DeskWindow.Inner, deskWindow(1100f, 700f))
        assertEquals(DeskWindow.Inner, deskWindow(1224f, 924f))
        assertEquals(DeskWindow.Medium, deskWindow(933f, 704f))
        assertEquals(DeskWindow.Outer, deskWindow(624f, 986f))
    }

    @Test fun footnoteDefinitionsAreRemovedWithoutEatingAdjacentParagraphsOrOtherMarkdown() {
        val content = "先读这一行[^a]。\n\n[^a]: 合成教材 p.3\n接着读这一行。\n\n**强调**"
        val notes = footnotes(content)
        assertEquals("合成教材 p.3", notes.single().text)
        val body = withoutFootnoteDefinitions(content)
        assertTrue(body.contains("先读这一行[^a]。"))
        assertTrue(body.contains("接着读这一行。"))
        assertTrue(body.contains("**强调**"))
        assertFalse(body.contains("[^a]:"))
    }
}
