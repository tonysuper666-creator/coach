package com.coach.server

import com.coach.domain.scope.VisibleScope
import com.coach.store.SourcePage
import kotlin.test.*

class ReaderSelectionBoundaryTest {
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
