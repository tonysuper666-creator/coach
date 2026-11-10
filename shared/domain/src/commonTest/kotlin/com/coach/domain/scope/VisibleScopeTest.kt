package com.coach.domain.scope

import kotlin.test.*

class VisibleScopeTest {
    private val learner = VisibleScope.learner()
    @Test fun learnerRefusesHiddenMaterial() {
        assertFalse(learner.permits("answers", 1, learnerVisible = false))
        assertTrue(learner.permits("book", 1, learnerVisible = true))
    }
    @Test fun noneAndNarrowedWindowRefuse() {
        assertFalse(VisibleScope.NONE.permits("book", 1, true))
        val chapter = learner.narrowToPages("book", 10..20)
        assertTrue(chapter.permits("book", 10, true)); assertTrue(chapter.permits("book", 20, true))
        assertFalse(chapter.permits("book", 21, true))
        assertFalse(chapter.permits("other", 12, true))
    }
    @Test fun narrowingNeverWidens() {
        val learned = learner.narrowToPages("book", 1..30)
        val forged = VisibleScope.tutorInternal().narrowToPages("book", 1..500)
        val both = learned.narrow(forged)
        assertFalse(both.permits("book", 31, true))
        assertFalse(both.permits("book", 5, false))
        assertTrue(both.permits("book", 30, true))
        assertEquals(both, forged.narrow(learned))
        assertEquals(both, both.narrow(both))
        val twice = both.narrowToPages("book", 5..10).narrowToPages("book", 1..500)
        assertFalse(twice.permits("book", 11, true)); assertTrue(twice.permits("book", 7, true))
    }
    @Test fun disjointNarrowingIsEmpty() {
        val s = learner.narrowToPages("book", 1..5).narrowToPages("book", 8..9)
        assertTrue((1..10).none { s.permits("book", it, true) })
        val d = learner.narrowToDocs("a").narrowToDocs("b")
        assertFalse(d.permits("a", 1, true)); assertFalse(d.permits("b", 1, true))
    }
    @Test fun internalCeilingStillHonoursNarrowing() {
        val t = VisibleScope.tutorInternal()
        assertTrue(t.permits("ref", 1, false))
        assertFalse(t.narrow(learner).permits("ref", 1, false))
    }
}
