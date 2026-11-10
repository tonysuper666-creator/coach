package com.coach.client

import kotlin.test.*
import kotlinx.coroutines.*

class ReaderRasterLeaseTest {
    @Test fun evictionWaitsForAllComposedUsesAndRecordsWhetherItWasDrawn() {
        val released = mutableListOf<Boolean>()
        val raster = Any()
        val lease = ReaderRasterLease(raster) { value, drawn -> assertSame(raster, value); released += drawn }
        val book = assertNotNull(lease.acquire())
        val citation = assertNotNull(lease.acquire())
        book.drawn()
        lease.retire()
        assertNull(lease.acquire())
        book.close(); book.close()
        assertSame(raster, citation.value)
        assertTrue(released.isEmpty(), "a source viewer still needs this raster")
        citation.close(); lease.retire()
        assertEquals(listOf(true), released)
    }

    @Test fun unusedPrefetchIsReleasedImmediatelyAndAbandonedCompositionReleasesItsUse() {
        var releases = 0
        val lease = ReaderRasterLease(Any()) { _, drawn -> assertFalse(drawn); releases++ }
        val abandoned = assertNotNull(lease.acquire())
        abandoned.close()
        assertEquals(0, releases, "the cache still owns this raster")
        lease.retire(); lease.retire(); abandoned.close()
        assertEquals(1, releases)
    }

    @Test fun decodeCompletingAfterCloseIsRetiredWithoutBeingPublished() = runBlocking {
        var releases = 0
        val started = CompletableDeferred<Unit>()
        var releasedWhenDrained = -1
        val finish = CompletableDeferred<Unit>()
        val window = ReaderPageWindow<ReaderRasterLease<Any>>(coroutineContext, load = { _, _ ->
            withContext(NonCancellable) {
                started.complete(Unit); finish.await()
                ReaderRasterLease(Any()) { _, drawn -> assertFalse(drawn); releases++ }
            }
        }, discard = { _, value -> value.retire() }, onDrained = { releasedWhenDrained = releases })
        val view = window.open(ReaderRasterIdentity("fake", "v1", "png"), 1, 1)
        withTimeout(3000) { started.await() }
        window.close(); finish.complete(Unit)
        withTimeout(3000) { window.join() }
        assertNull(view.image(1))
        assertEquals(1, releases)
        assertEquals(1, releasedWhenDrained, "drain follows retirement of the late native result")
    }
}
