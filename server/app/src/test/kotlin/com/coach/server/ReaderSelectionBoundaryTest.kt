package com.coach.server

import com.coach.domain.scope.VisibleScope
import com.coach.store.SourcePage
import kotlin.test.*

class ReaderSelectionBoundaryTest {
    @Test fun boundPdfComparisonPreservesMeaningfulCharacters() {
        assertTrue(containsBoundBookSelection("An _economic_ explanation of resources.", "An eco-\nnomic explanation of re-\nsources."))
        assertTrue(containsBoundBookSelection("An eco-\r\n nomic explanation.", "An economic explanation."))
        for ((source, selection) in listOf(
            "a_b" to "ab", "_unpaired" to "unpaired", "cost-effective" to "costeffective",
            "1-\n2" to "12", "not equal" to "equal not", "decreased" to "increased",
            "one\n\ntwo" to "onetwo", "_x_2" to "x2", "_10_" to "10")) {
            assertFalse(containsBoundBookSelection("Before $source after", "Before $selection after"),
                "$source must not authorize $selection")
        }
        assertFalse(containsBoundBookSelection("text", " "))
        assertFalse(containsReaderSelection("An _economic_ explanation.", "An eco-\nnomic explanation."),
            "Nonlocal reader matching keeps its original literal boundary")
    }
    @Test fun literalSelectionMustSurviveVisibilityAndBudgetFiltering() {
        val public = SourcePage("book", 1, "Synthetic   visible passage.")
        val later = SourcePage("book", 2, "Synthetic unread chapter.")
        val private = SourcePage("answer", 1, "Synthetic quiz answer.", learnerVisible = false)
        val scope = VisibleScope.learner().narrowToPages("book", 1..1)
        val pack = SourcePack.prepare("", listOf(public, later, private).filter { scope.permits(it.docId, it.pdfPage, it.learnerVisible) })
        assertTrue(pack.containsSelection("Synthetic visible\npassage"))
        assertFalse(pack.containsSelection(later.text))
        assertFalse(pack.containsSelection(private.text))
        assertFalse(pack.containsSelection(" "))
        assertFalse(SourcePack(listOf(SourcePage("a", 1, "one"), SourcePage("b", 1, "two"))).containsSelection("one two"))
        assertNotNull(checkedReaderAnswer("有依据的解释。【S1】", pack))
        assertNull(checkedReaderAnswer("“Synthetic unread chapter.”【S1】", pack))
        assertNull(checkedReaderAnswer("无效出处。【S2】", pack))
        val full = (1..6).map { SourcePage("public-$it", it, "Other synthetic material", score = 1.0) } + later
        assertFalse(SourcePack.prepare("", full).containsSelection(later.text))
    }
}
