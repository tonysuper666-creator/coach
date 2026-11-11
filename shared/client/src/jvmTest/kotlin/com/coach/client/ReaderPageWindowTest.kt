package com.coach.client

import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first

class ReaderPageWindowTest {
    private val identity = ReaderRasterIdentity("invented-book", "v1", "png/default")
    private class Raster(val label: String)
    private class Call(val key: ReaderRasterKey, val prefetch: Boolean) {
        val result = CompletableDeferred<Raster?>()
        fun finish(): Raster = Raster("${key.identity}:${key.page}").also { result.complete(it) }
    }
    private class Driver(context: CoroutineContext, val ignoreCancellationOn: Set<Int> = emptySet(), overlap: Boolean = false,
                         retainNeighbours: Boolean = true) {
        val started = Channel<Call>(Channel.UNLIMITED)
        val all = mutableListOf<Call>()
        val discarded = mutableListOf<Raster>()
        val window: ReaderPageWindow<Raster> = ReaderPageWindow(context, prefetchBeforeDraw = overlap, retainNeighbours = retainNeighbours, load = { key, prefetch ->
            val call = Call(key, prefetch).also { all += it }
            started.send(call)
            val value = if (key.page in ignoreCancellationOn) withContext(NonCancellable) { call.result.await() } else call.result.await()
            if (prefetch && overlap && !window.awaitPrefetchDecode(key)) null else value
        }, discard = { _, raster -> discarded += raster })
        suspend fun next(page: Int, prefetch: Boolean? = null): Call = withTimeout(3000) {
            started.receive().also { assertEquals(page, it.key.page); if (prefetch != null) assertEquals(prefetch, it.prefetch) }
        }
        suspend fun ready(viewer: ReaderPageWindow<Raster>.Viewer, page: Int) {
            withTimeout(3000) { window.changes.first { viewer.image(page) != null } }
        }
        suspend fun failed(viewer: ReaderPageWindow<Raster>.Viewer, page: Int) {
            withTimeout(3000) { window.changes.first { viewer.failed(page) } }
        }
        suspend fun stop() {
            window.close()
            all.toList().forEach { it.result.complete(null) }
            withTimeout(3000) { window.join() }
        }
    }
    private suspend fun scenario(ignoreCancellationOn: Set<Int> = emptySet(), block: suspend Driver.() -> Unit) {
        val driver = Driver(currentCoroutineContext(), ignoreCancellationOn)
        try { driver.block() } finally { driver.stop() }
    }

    @Test fun currentDrawPrecedesPrefetchAndAHitNeverRequestsOrDecodesAgain() = runBlocking {
        scenario {
            val view = window.open(identity, 5, 10)
            val five = next(5, false).finish()
            ready(view, 5)
            assertTrue(started.tryReceive().isFailure, "decoded is not the same as drawn")
            view.presented(5)
            val six = next(6, true).finish()
            next(4, true).finish()
            withTimeout(3000) { window.changes.first { window.stats().inFlight == 0 } }
            view.show(6)
            assertSame(six, view.image(6))
            assertSame(five, view.image(5))
            assertEquals(1, all.count { it.key.page == 6 })
            assertTrue(started.tryReceive().isFailure)
            view.presented(6)
            next(7, true).finish()
            ready(view, 7)
            assertNull(view.image(4))
            assertEquals(3, window.stats().peakOwnedSlots)
        }
    }

    @Test fun cacheOffDoesNotPrefetchOrRetainPreviousPageAndReturningLoadsAgain() = runBlocking {
        val driver = Driver(coroutineContext, overlap = true, retainNeighbours = false)
        try { with(driver) {
            val view = window.open(identity, 1, 24)
            val first = next(1, false).finish(); ready(view, 1); view.presented(1); yield()
            assertTrue(started.tryReceive().isFailure)
            assertNull(view.image(2)); assertFalse(view.pending(2))
            view.show(2)
            assertNull(view.image(1)); assertTrue(first in discarded)
            next(2, false).finish(); ready(view, 2); view.presented(2); yield()
            assertTrue(started.tryReceive().isFailure)
            view.show(1)
            assertNull(view.image(1)); assertNull(view.image(2))
            val reloaded = next(1, false).finish(); ready(view, 1)
            assertNotSame(first, reloaded)
            assertEquals(listOf(1,2,1),all.map { it.key.page })
            assertTrue(all.none { it.prefetch })
            assertEquals(1,window.stats().peakOwnedSlots)
        } } finally { driver.stop() }
    }

    @Test fun rapidJumpCancelsSpeculationAndLateResponseCannotReplaceNewestTarget() = runBlocking {
        scenario(setOf(6)) {
            val view = window.open(identity, 5, 20)
            next(5).finish(); ready(view, 5); view.presented(5)
            val slow = next(6, true)
            view.show(9); view.show(2)
            yield()
            assertTrue(started.tryReceive().isFailure, "a cancelled non-interruptible load still owns the serial slot")
            val obsolete = slow.finish()
            val target = next(2, false).finish()
            ready(view, 2)
            assertSame(target, view.image(2))
            assertNull(view.image(6)); assertNull(view.image(9))
            assertTrue(obsolete in discarded)
            assertTrue(all.none { it.key.page == 9 })
            assertTrue(window.stats().peakOwnedSlots <= 3)
        }
    }

    @Test fun citationViewerSharesReadyRasterThenRestoresUnderlyingWindowAndLastCloseClears() = runBlocking {
        scenario {
            val parent = window.open(identity, 5, 10)
            val five = next(5).finish(); ready(parent, 5); parent.presented(5)
            val six = next(6).finish(); next(4).finish()
            withTimeout(3000) { window.changes.first { window.stats().inFlight == 0 } }
            val citation = window.open(identity, 6, 10)
            assertSame(six, citation.image(6))
            assertNull(parent.image(5), "an obscured viewer must not compete for the cache")
            assertEquals(3, all.size)
            citation.close()
            assertSame(five, parent.image(5))
            assertEquals(3, all.size)
            parent.close()
            assertEquals(0, window.stats().cached)
            assertEquals(0, window.stats().viewers)
        }
    }

    @Test fun bookVersionAndRenderingChangesNeverReuseOldKey() = runBlocking {
        scenario {
            val initial = window.open(identity, 5, 10)
            val old = next(5).finish(); ready(initial, 5)
            for (other in listOf(identity.copy(document = "other-book"), identity.copy(version = "v2"), identity.copy(rendering = "png/density-2"))) {
                val changed = window.open(other, 5, 10)
                val call = next(5, false)
                assertEquals(other, call.key.identity)
                assertNull(changed.image(5))
                val replacement = call.finish(); ready(changed, 5)
                assertSame(replacement, changed.image(5)); assertNotSame(old, changed.image(5))
            }
            assertTrue(old in discarded)
        }
    }

    @Test fun foregroundRetryAndFailedPrefetchPromotionAreRetryable() = runBlocking {
        scenario {
            val view = window.open(identity, 5, 10)
            next(5).result.complete(null); failed(view, 5)
            view.retry(5)
            next(5, false).finish(); ready(view, 5); view.presented(5)
            next(6, true).result.complete(null)
            next(4, true).finish()
            withTimeout(3000) { window.changes.first { window.stats().inFlight == 0 } }
            view.show(6)
            next(6, false).finish(); ready(view, 6)
            assertFalse(view.failed(6))
            assertEquals(2, all.count { it.key.page == 6 })
        }
    }

    @Test fun firstLastAndSpreadStayInsidePageRangeAndThreeOwnedSlots() = runBlocking {
        scenario {
            val view = window.open(identity, 9, 10, visible = 2)
            next(9, false).finish(); ready(view, 9)
            next(10, false).finish(); ready(view, 10)
            view.presented(9)
            assertTrue(started.tryReceive().isFailure)
            view.presented(10)
            next(8, true).finish(); ready(view, 8)
            view.show(10)
            assertTrue(started.tryReceive().isFailure)
            view.show(1, visible = 1)
            next(1, false).finish(); ready(view, 1); view.presented(1)
            next(2, true).finish(); ready(view, 2)
            assertTrue(all.all { it.key.page in 1..10 })
            assertTrue(window.stats().peakOwnedSlots <= 3)
        }
    }

    @Test fun closeDuringNonCooperativeLoadDiscardsItsResult() = runBlocking {
        scenario(setOf(5)) {
            val view = window.open(identity, 5, 10)
            val slow = next(5)
            view.close()
            val late = slow.finish()
            withTimeout(3000) { window.changes.first { window.stats().inFlight == 0 } }
            assertNull(view.image(5))
            assertEquals(0, window.stats().cached)
            assertTrue(late in discarded)
        }
    }

    @Test fun duplicateForegroundSelectionAndRetryCoalesceAnInFlightRequest() = runBlocking {
        scenario {
            val view = window.open(identity, 5, 10)
            val single = next(5)
            view.show(5); view.retry(5); yield()
            assertTrue(started.tryReceive().isFailure)
            val image = single.finish(); ready(view, 5)
            assertSame(image, view.image(5))
            assertEquals(1, all.size)
        }
    }

    @Test fun returningFromCitationWaitsForParentDrawBeforeReplacingAnEvictedNeighbour() = runBlocking {
        scenario {
            val parent = window.open(identity, 5, 10)
            next(5).finish(); ready(parent, 5); parent.presented(5)
            next(6).finish(); next(4).finish()
            withTimeout(3000) { window.changes.first { window.stats().inFlight == 0 } }
            val citation = window.open(identity, 6, 10)
            citation.close()
            yield()
            assertTrue(started.tryReceive().isFailure, "returning content gets its draw before speculative replacement")
            parent.presented(5)
            next(4, true).finish(); ready(parent, 4)
        }
    }

    @Test fun newCurrentPagePreemptsAnOldForegroundRequestThatBecameANeighbour() = runBlocking {
        scenario {
            val view = window.open(identity, 5, 10)
            val old = next(5, false)
            view.show(6)
            val latest = next(6, false).finish()
            ready(view, 6)
            old.finish()
            assertSame(latest, view.image(6))
            assertNull(view.image(5))
            assertEquals(2, all.size)
        }
    }

    @Test fun stalePositionCanHoldSpeculationUntilAnExplicitNavigation() = runBlocking {
        scenario {
            val view = window.open(identity, 5, 10, allowPrefetch = false)
            next(5).finish(); ready(view, 5); view.presented(5); yield()
            assertTrue(started.tryReceive().isFailure)
            view.show(6, allowPrefetch = true)
            next(6, false).finish(); ready(view, 6); view.presented(6)
            next(7, true).finish(); ready(view, 7)
            assertTrue(window.stats().peakOwnedSlots <= 3)
        }
    }

    @Test fun neighbourFetchOverlapsCurrentDrawButDecodeWaitsForVisibleContent() = runBlocking {
        val driver = Driver(coroutineContext, overlap = true)
        try { with(driver) {
            val view = window.open(identity, 5, 10)
            next(5, false).finish(); ready(view, 5)
            val six = next(6, true).finish()
            yield()
            assertNull(view.image(6), "received bytes cannot decode ahead of the visible draw")
            view.presented(5); ready(view, 6)
            assertSame(six, view.image(6))
            assertTrue(window.stats().peakOwnedSlots <= 3)
        } } finally { driver.stop() }
    }

    @Test fun promotingAnEarlyFetchDoesNotWaitForItsOwnFirstDraw() = runBlocking {
        val driver = Driver(coroutineContext, overlap = true)
        try { with(driver) {
            val view = window.open(identity, 5, 10)
            next(5).finish(); ready(view, 5)
            val six = next(6, true).finish()
            view.show(6)
            ready(view, 6)
            assertSame(six, view.image(6))
            assertEquals(1, all.count { it.key.page == 6 })
        } } finally { driver.stop() }
    }

    @Test fun closingWhileEarlyBytesWaitForDrawDoesNotPublishADecodedNeighbour() = runBlocking {
        val driver = Driver(coroutineContext, overlap = true)
        try { with(driver) {
            val view = window.open(identity, 5, 10)
            next(5).finish(); ready(view, 5)
            next(6, true).finish(); yield()
            window.close(); window.join()
            assertNull(view.image(6))
            assertEquals(0, window.stats().cached)
        } } finally { driver.stop() }
    }
}
