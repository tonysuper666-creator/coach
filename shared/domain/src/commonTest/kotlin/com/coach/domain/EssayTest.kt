package com.coach.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EssayTest {
    @Test fun wordCountMatchesWhatAWordProcessorShows() {
        assertEquals(0, Essay.wordCount("   \n "))
        // Dashes and bullets on their own are not words; numbers and hyphenated words are.
        assertEquals(9, Essay.wordCount("Cost-sharing cut visits by 25% — see\nthe RAND data."))
    }

    @Test fun copyingEightWordsIsCaughtThroughCaseAndPunctuation() {
        val source = "Careful drivers find the pooled premium too high and leave the market early."
        assertNotNull(Essay.sharedRun("As noted, CAREFUL drivers find the pooled premium, too high and so on.", source))
        // Seven shared words are a paraphrase, not a copy.
        assertNull(Essay.sharedRun("Careful drivers find the pooled premium too costly.", source))
        assertNull(Essay.sharedRun("", source))
    }
}
