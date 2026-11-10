package com.coach.client

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Reader/account scope belongs to the owning ReaderHttp instance, never to a process-wide document ID. */
data class ReaderRasterIdentity(val document: String, val version: String, val rendering: String)
data class ReaderRasterKey(val identity: ReaderRasterIdentity, val page: Int)

/**
 * One foreground viewer, one serial loader, current page and its immediate neighbours.
 * Nested source dialogs share decoded values and restore the underlying viewer on close.
 * Changes carry only a revision: old flow snapshots cannot retain entire bitmap maps.
 * The three owned slots include an in-flight load; native/GPU liveness needs separate observation.
 */
class ReaderPageWindow<T : Any>(
    context: CoroutineContext,
    private val load: suspend (ReaderRasterKey, Boolean) -> T?,
    private val discard: (ReaderRasterKey, T) -> Unit = { _, _ -> },
    private val publish: (ReaderRasterKey, T) -> T = { _, value -> value },
    private val prefetchBeforeDraw: Boolean = false,
    private val onDrained: () -> Unit = {},
) {
    private val lock = Any()
    private val lifetime = SupervisorJob()
    private val drained = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(context.minusKey(Job) + lifetime)
    private val revision = MutableStateFlow(0L)
    val changes: StateFlow<Long> get() = revision
    private val viewers = mutableListOf<Viewer>()
    private val ready = mutableMapOf<ReaderRasterKey, T>()
    private val failures = mutableMapOf<ReaderRasterKey, Boolean>()
    private var foreground: Viewer? = null
    private var work: Work? = null
    private var closed = false
    private var peakSlots = 0

    inner class Viewer internal constructor(val identity: ReaderRasterIdentity, internal var page: Int,
                                            internal val pages: Int, internal var visible: Int, internal var allowPrefetch: Boolean) {
        internal var direction = 1
        internal val drawn = mutableSetOf<Int>()
        internal var removed = false
        val foreground: Boolean get() = synchronized(lock) { !closed && !removed && viewers.lastOrNull() === this }

        fun show(page: Int, visible: Int = this.visible, allowPrefetch: Boolean = this.allowPrefetch) = synchronized(lock) {
            if (closed || removed) return@synchronized
            require(page in 1..pages && visible in 1..2)
            if (page == this.page && visible == this.visible && allowPrefetch == this.allowPrefetch) return@synchronized
            direction = if (page < this.page) -1 else if (page > this.page) 1 else direction
            if (page != this.page || visible != this.visible) drawn.clear()
            this.page = page; this.visible = visible; this.allowPrefetch = allowPrefetch
            if (viewers.lastOrNull() === this) reconcile()
        }

        fun image(page: Int): T? = synchronized(lock) {
            if (closed || removed || viewers.lastOrNull() !== this) null else ready[ReaderRasterKey(identity, page)]
        }

        fun failed(page: Int): Boolean = synchronized(lock) {
            !closed && !removed && viewers.lastOrNull() === this && ReaderRasterKey(identity, page) in failures
        }

        fun pending(page: Int): Boolean = synchronized(lock) {
            !closed && !removed && viewers.lastOrNull() === this && work?.key == ReaderRasterKey(identity, page)
        }

        fun retry(page: Int) = synchronized(lock) {
            if (closed || removed || viewers.lastOrNull() !== this) return@synchronized
            failures.remove(ReaderRasterKey(identity, page))
            reconcile()
        }

        /** Draw commands, not presentation. Early fetches may run; speculative decode waits for this signal. */
        fun presented(page: Int) = synchronized(lock) {
            if (!closed && !removed && viewers.lastOrNull() === this && drawn.add(page)) { changed(); pump() }
        }

        fun close() = synchronized(lock) {
            if (removed) return@synchronized
            removed = true
            viewers.remove(this)
            reconcile()
        }
    }

    private inner class Work(val key: ReaderRasterKey, val prefetch: Boolean) {
        var cancelled = false
        var value: T? = null
        lateinit var job: Job
    }

    data class Stats(val cached: Int, val inFlight: Int, val peakOwnedSlots: Int, val viewers: Int)
    fun stats(): Stats = synchronized(lock) { Stats(ready.size, if (work == null) 0 else 1, peakSlots, viewers.size) }

    /** A neighbour fetch may overlap the current draw; decoding still waits for that draw.
     * Promotion to visible content bypasses this wait, avoiding a wait for its own decode.
     */
    suspend fun awaitPrefetchDecode(key: ReaderRasterKey): Boolean {
        fun mayProceed(): Boolean = synchronized(lock) {
            val top = viewers.lastOrNull()
            closed || top == null || key !in desired(top) ||
                key.page in visible(top) || top.drawn.containsAll(visible(top))
        }
        changes.first { mayProceed() }
        return synchronized(lock) { !closed && viewers.lastOrNull()?.let { key in desired(it) } == true }
    }

    fun open(identity: ReaderRasterIdentity, page: Int, pages: Int, visible: Int = 1, allowPrefetch: Boolean = true): Viewer = synchronized(lock) {
        check(!closed)
        require(page in 1..pages && visible in 1..2)
        Viewer(identity, page, pages, visible, allowPrefetch).also { viewers += it; reconcile() }
    }

    private fun desired(viewer: Viewer): List<ReaderRasterKey> =
        listOf(viewer.page, viewer.page + viewer.direction, viewer.page - viewer.direction)
            .filter { it in 1..viewer.pages }.distinct().map { ReaderRasterKey(viewer.identity, it) }

    private fun visible(viewer: Viewer): List<Int> =
        (viewer.page until (viewer.page + viewer.visible).coerceAtMost(viewer.pages + 1)).toList()

    private fun reconcile() {
        val top = viewers.lastOrNull()
        if (top !== foreground) {
            foreground = top
            top?.drawn?.clear()
        }
        val wanted = top?.let(::desired).orEmpty().toSet()
        for (key in ready.keys.filter { it !in wanted }) ready.remove(key)?.let { discard(key, it) }
        failures.keys.retainAll(wanted)
        // A speculative failure is retried once when that page becomes foreground.
        if (top != null) visible(top).forEach { page ->
            val key = ReaderRasterKey(top.identity, page)
            if (failures[key] == true) failures.remove(key)
        }
        val loading = work
        if (loading != null && (loading.key !in wanted ||
                (top != null && loading.key.page != top.page && ReaderRasterKey(top.identity, top.page) !in ready))) {
            loading.cancelled = true
            loading.job.cancel()
        }
        changed()
        pump()
    }

    private fun pump() {
        if (closed || work != null) return
        val top = viewers.lastOrNull() ?: return
        val visible = visible(top)
        val priority = (visible.map { ReaderRasterKey(top.identity, it) } + desired(top)).distinct()
        val key = priority.firstOrNull { it !in ready && it !in failures } ?: return
        val prefetch = key.page !in visible
        if (prefetch && (!top.allowPrefetch || !prefetchBeforeDraw && !top.drawn.containsAll(visible))) return
        val loading = Work(key, prefetch)
        loading.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (prefetch && !prefetchBeforeDraw) yield()
                loading.value = load(key, prefetch)
            } catch (_: CancellationException) { loading.cancelled = true }
            catch (_: Exception) { /* foreground gets the existing explicit retry UI */ }
        }
        loading.job.invokeOnCompletion { cause ->
            synchronized(lock) {
                val value = loading.value
                loading.value = null
                if (work === loading) {
                    work = null
                    val current = viewers.lastOrNull()
                    val keep = !closed && cause == null && !loading.cancelled && current != null && key in desired(current)
                    if (value != null) {
                        if (keep) ready[key] = publish(key, value) else discard(key, value)
                    } else if (keep) failures[key] = prefetch
                    changed()
                    pump()
                } else value?.let { discard(key, it) }
                finishDrain()
            }
        }
        work = loading
        peakSlots = maxOf(peakSlots, ready.size + 1)
        changed()
        loading.job.start()
    }

    private fun changed() { revision.value++ }

    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        viewers.forEach { it.removed = true }; viewers.clear()
        foreground = null
        ready.forEach { (key, value) -> discard(key, value) }; ready.clear(); failures.clear()
        work?.let { it.cancelled = true; it.job.cancel() }
        lifetime.cancel()
        changed()
        finishDrain()
    }

    private fun finishDrain() {
        if (closed && work == null && !drained.isCompleted) { onDrained(); drained.complete(Unit) }
    }

    suspend fun join() { lifetime.join(); drained.await() }
}
