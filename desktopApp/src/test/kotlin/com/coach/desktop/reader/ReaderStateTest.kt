package com.coach.desktop.reader

import kotlin.test.*

class ReaderStateTest {
    private fun fresh(w: Int = 1440) = ReaderState(DesktopReaderLayouts.forWidth(w), pageCount = 10)

    @Test fun layoutFollowsWidthNotDevice() {
        assertEquals(3, DesktopReaderLayouts.forWidth(1440).visible.size)
        assertEquals(listOf(ReaderPane.Body, ReaderPane.Related), DesktopReaderLayouts.forWidth(900).visible)
        assertEquals(listOf(ReaderPane.Shelf, ReaderPane.Related), DesktopReaderLayouts.forWidth(520).collapsed)
        for ((_, w) in DesktopReaderLayouts.fixtures) assertTrue(ReaderPane.Body in DesktopReaderLayouts.forWidth(w).visible)
    }

    @Test fun pagingClampsAtEnds() {
        var s = fresh()
        s = s.apply(ReaderAction.PrevPage); assertEquals(0, s.position.page)
        s = s.apply(ReaderAction.LastPage).apply(ReaderAction.NextPage); assertEquals(9, s.position.page)
    }

    @Test fun provenanceRoundTripKeepsPosition() {
        val s = fresh().goTo(2).openProvenance("a-17", 6)
        assertEquals(6, s.position.page); assertEquals("a-17", s.flashAnchor)
        val back = s.returnFromProvenance()
        assertEquals(2, back.position.page); assertNull(back.flashAnchor); assertTrue(back.provenanceStack.isEmpty())
    }

    @Test fun unknownPageDoesNotJump() {
        val s = fresh().goTo(3)
        assertSame(s, s.openProvenance("x", -1))
        assertSame(s, s.openProvenance("x", 99))
    }

    @Test fun resizeKeepsPositionAndFocusStaysVisible() {
        val s = fresh().goTo(4).copy(focus = ReaderPane.Shelf).resize(520)
        assertEquals(4, s.position.page); assertEquals(ReaderPane.Body, s.focus)
    }

    @Test fun tabCyclesThenReleasesFocus() {
        var s = fresh().copy(focus = ReaderPane.Shelf)
        val tab = KeyInput(ReaderKey.Tab)
        s = s.apply(ReaderKeys.map(tab, s)!!); assertEquals(ReaderPane.Body, s.focus)
        s = s.apply(ReaderKeys.map(tab, s)!!); assertEquals(ReaderPane.Related, s.focus)
        assertNull(ReaderKeys.map(tab, s))
        assertNull(ReaderKeys.map(KeyInput(ReaderKey.Tab, shift = true), s.copy(focus = ReaderPane.Shelf)))
    }

    @Test fun keysPageAndEscapeReturns() {
        val s = fresh()
        assertEquals(ReaderAction.NextPage, ReaderKeys.map(KeyInput(ReaderKey.Space), s))
        assertEquals(ReaderAction.PrevPage, ReaderKeys.map(KeyInput(ReaderKey.Space, shift = true), s))
        assertNull(ReaderKeys.map(KeyInput(ReaderKey.Escape), s))
        assertEquals(ReaderAction.ReturnFromProvenance, ReaderKeys.map(KeyInput(ReaderKey.Escape), s.openProvenance("a", 5)))
        assertNull(ReaderKeys.map(KeyInput(ReaderKey.Right), s.copy(focus = ReaderPane.Related)))
        assertEquals(ReaderAction.ZoomIn, ReaderKeys.map(KeyInput(ReaderKey.Up, ctrlOrCmd = true), s))
    }

    @Test fun saveRestoreRoundTrip() {
        val s = fresh().goTo(3).openProvenance("a|b%1", 7).copy(zoomPercent = 130)
        val r = ReaderState.restore(s.encode(), 1440, 10)!!
        assertEquals(s.position, r.position); assertEquals(s.provenanceStack, r.provenanceStack); assertEquals(130, r.zoomPercent)
    }

    @Test fun badSnapshotsAreRejected() {
        assertNull(ReaderState.restore(null, 1000, 10))
        assertNull(ReaderState.restore("junk", 1000, 10))
        assertNull(ReaderState.restore("v=1\npage=12\n", 1000, 10))
        assertNull(ReaderState.restore("v=2\npage=1\n", 1000, 10))
    }

    @Test fun contractThresholds() {
        assertEquals(listOf(ReaderPane.Body), DesktopReaderLayouts.forWidth(699).visible)
        assertEquals(listOf(ReaderPane.Body, ReaderPane.Related), DesktopReaderLayouts.forWidth(700).visible)
        assertEquals(listOf(ReaderPane.Body, ReaderPane.Related), DesktopReaderLayouts.forWidth(1099).visible)
        assertEquals(3, DesktopReaderLayouts.forWidth(1100).visible.size)
    }

    @Test fun outOfRangeBackStackRejected() {
        assertNull(ReaderState.restore("v=1\npage=3\nback=99|x\n", 1000, 10))
        assertNull(ReaderState.restore("v=1\npage=3\nback=-5|x\n", 1000, 10))
        assertNull(ReaderState.restore("v=1\npage=3\nback=5\n", 1000, 10))
    }

    @Test fun emptyVisibleDoesNotThrow() {
        val s = ReaderState(ReaderLayout(emptyList(), emptyList()), pageCount = 3)
        assertNull(ReaderKeys.map(KeyInput(ReaderKey.Tab), s))
        assertSame(s, s.apply(ReaderAction.NextPane))
    }

    @Test fun backKeysPassThroughWhenStackEmpty() {
        val s = fresh()
        assertNull(ReaderKeys.map(KeyInput(ReaderKey.Back), s))
        assertNull(ReaderKeys.map(KeyInput(ReaderKey.Left, alt = true), s))
        assertEquals(ReaderAction.ReturnFromProvenance, ReaderKeys.map(KeyInput(ReaderKey.Back), s.openProvenance("a", 5)))
    }
}
