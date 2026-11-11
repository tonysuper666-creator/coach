package com.coach.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Canvas
import com.coach.client.ReaderRasterIdentity
import com.coach.client.ReaderRasterLease
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*

/** No material identifiers, text, URLs, credentials or strong bitmap references enter this trace. */
object ReaderPageTrace {
    enum class Input { NEXT, PREVIOUS, CITATION, MAP, RESTORE }
    private const val LIMIT = 8192
    private const val ALLOCATION_LIMIT = 2048
    private val lock = Any()
    private val clock = TimeSource.Monotonic.markNow()
    @kotlin.concurrent.Volatile var enabled = false
        private set
    /** Explicit synthetic measurement control; ordinary readers always retain their neighbours. */
    @kotlin.concurrent.Volatile internal var neighbourCacheEnabled = true
        private set
    private var epoch = 0
    private var sequence = 0L
    private var eventOrdinal = 0L
    private var sessionSequence = 0
    private var dropped = 0
    private var allocationOverflow = false
    private var decoding = 0
    private var peakDecoding = 0
    private var peakDecoded = 0
    private var peakReachableBytes = 0L
    private var peakDecodedSnapshot: JsonObject? = null
    private var peakBytesSnapshot: JsonObject? = null
    private val events = ArrayDeque<JsonObject>()
    private val allocations = mutableListOf<Allocation>()
    private val owners = mutableMapOf<Long, Int>()
    private val leaseOwnership = mutableMapOf<Long, ReaderRasterLease.Ownership>()
    private val releases = mutableMapOf<Long, String>()
    private val published = mutableSetOf<Long>()
    private val retired = mutableSetOf<Long>()
    private val observedImages = mutableSetOf<Long>()
    private val draws = mutableSetOf<Pair<Long, Long>>()
    private val lookups = mutableSetOf<Pair<Long, Int>>()
    private val inputTimes = mutableMapOf<Long, Long>()
    private var decodePause: ReaderDecodePause? = null

    private class Allocation(val observation: ReaderDecodeObservation, val storage: String, val width: Int, val height: Int,
                             val bytes: Long, val weak: ReaderBitmapWeakReference)
    { val image get() = observation.image }

    /** Start before composing a measurement surface. Default production execution does no observation. */
    fun start(neighbourCacheEnabled: Boolean = true) = synchronized(lock) {
        this.neighbourCacheEnabled = neighbourCacheEnabled
        decodePause?.resume(); decodePause = null
        epoch++; sequence = 0; eventOrdinal = 0; sessionSequence = 0; dropped = 0; allocationOverflow = false
        decoding = 0; peakDecoding = 0; peakDecoded = 0; peakReachableBytes = 0
        peakDecodedSnapshot = null; peakBytesSnapshot = null
        leaseOwnership.clear(); releases.clear(); published.clear(); retired.clear(); observedImages.clear()
        events.clear(); allocations.clear(); owners.clear(); draws.clear(); lookups.clear(); inputTimes.clear(); enabled = true
    }

    fun stop() = synchronized(lock) { enabled = false; neighbourCacheEnabled = true; decodePause?.resume(); decodePause = null }
    fun nowNs(): Long = clock.elapsedNow().inWholeNanoseconds

    internal fun session(): Int = if (!enabled) 0 else synchronized(lock) { ++sessionSequence }

    internal fun request(session: Int, page: Int, action: Long): Long = if (!enabled) 0 else synchronized(lock) {
        (++sequence).also { record("request_started", session, page, action, it) }
    }

    /** Measurement only: pause in the non-interruptible decode section, before entering JNI. */
    fun pauseNextDecode(): ReaderDecodePause = synchronized(lock) {
        check(enabled && decodePause == null)
        ReaderDecodePause().also { decodePause = it }
    }

    internal suspend fun awaitDecodePermit(observation: ReaderDecodeObservation?) {
        if (!enabled || observation == null) return
        val pause = synchronized(lock) { decodePause?.takeIf { !it.taken }?.also { it.taken = true } } ?: return
        mark("decode_paused_before_native", observation.session, observation.page, observation.action, observation.image)
        try { pause.entered.complete(observation.image); pause.permit.await() }
        finally { synchronized(lock) { if (decodePause === pause) decodePause = null } }
    }

    internal fun input(kind: Input, page: Int, session: Int = 0): Long {
        if (!enabled) return 0
        return synchronized(lock) {
            val action = ++sequence
            val time = nowNs()
            inputTimes[action] = time
            record("input_${kind.name.lowercase()}", session, page, action, timestamp = time)
            action
        }
    }

    internal fun lookup(session: Int, page: Int, action: Long, image: ReaderLoadedImage?, pending: Boolean) {
        if (enabled) synchronized(lock) {
            if (!lookups.add(action to page)) return
            if (lookups.size >= LIMIT) { lookups.clear(); inputTimes.clear(); dropped++ }
            val input = inputTimes[action]
            val hit = image != null && input != null && image.readyNs <= input
            record(if (hit) "lookup_hit" else if (pending) "lookup_wait" else "lookup_miss", session, page, action, image?.observation?.image ?: 0)
        }
    }

    internal fun mark(kind: String, session: Int, page: Int, action: Long, image: Long = 0) {
        if (enabled) synchronized(lock) {
            if (image in observedImages) when {
                kind == "cache_published" -> published.add(image)
                kind == "cache_evicted" -> { published.remove(image); retired.add(image) }
                kind.startsWith("release_") -> releases[image] = kind
            }
            record(kind, session, page, action, image)
        }
    }

    internal fun ownership(observation: ReaderDecodeObservation, state: ReaderRasterLease.Ownership) {
        if (enabled) synchronized(lock) {
            if (observation.epoch != epoch || observation.image !in observedImages) return
            leaseOwnership[observation.image] = state
            record("raster_ownership", observation.session, observation.page, observation.action, observation.image)
        }
    }

    internal fun decode(session: Int, page: Int, action: Long, prefetch: Boolean = false): ReaderDecodeObservation? {
        if (!enabled) return null
        return synchronized(lock) {
            decoding++; peakDecoding = maxOf(peakDecoding, decoding)
            ReaderDecodeObservation(epoch, session, page, action, ++sequence, prefetch).also {
                record(if (prefetch) "prefetch_decode_started" else "decode_started", session, page, action, it.image)
            }
        }
    }

    internal fun allocation(observation: ReaderDecodeObservation, storage: String, width: Int, height: Int,
                            bytes: Long, weak: ReaderBitmapWeakReference) {
        if (!enabled) return
        synchronized(lock) {
            if (observation.epoch != epoch) return
            // Retain bounded allocation metadata after collection so peak sizes remain auditable.
            if (allocations.size >= ALLOCATION_LIMIT) allocationOverflow = true
            else { allocations += Allocation(observation, storage, width, height, bytes, weak); observedImages.add(observation.image) }
            record("allocation_$storage", observation.session, observation.page, observation.action, observation.image)
        }
    }

    internal fun decoded(observation: ReaderDecodeObservation?, ok: Boolean) {
        if (!enabled || observation == null) return
        synchronized(lock) {
            if (observation.epoch != epoch) return
            decoding--
            record(if (!ok) "decode_failed" else if (observation.prefetch) "prefetch_decode_ready" else "decode_ready",
                observation.session, observation.page, observation.action, observation.image)
        }
    }

    internal fun owner(observation: ReaderDecodeObservation?, attach: Boolean) {
        if (!enabled || observation == null) return
        synchronized(lock) {
            if (observation.epoch != epoch) return
            val id = observation.image
            val count = (owners[id] ?: 0) + if (attach) 1 else -1
            if (count > 0) owners[id] = count else owners.remove(id)
            record(if (attach) "composition_attach" else "composition_detach", observation.session, observation.page, observation.action, id)
        }
    }

    /** Called after target bitmap draw commands; false for repeat draws. This is not a presentation timestamp. */
    internal fun drawn(observation: ReaderDecodeObservation?, action: Long): Boolean {
        if (!enabled || observation == null) return false
        return synchronized(lock) {
            if (observation.epoch != epoch || !draws.add(action to observation.image)) return@synchronized false
            if (draws.size >= LIMIT) { draws.clear(); dropped++ }
            record("target_draw_commands", observation.session, observation.page, action, observation.image)
            true
        }
    }

    internal fun submitted(observation: ReaderDecodeObservation, action: Long) {
        if (enabled) synchronized(lock) {
            if (observation.epoch == epoch) record("frame_submitted", observation.session, observation.page, action, observation.image)
        }
    }

    /** The harness calls this only after verifying the target page's pixels in a native capture. */
    fun pixelsObserved(page: Int) {
        if (enabled) synchronized(lock) { record("pixels_observed", 0, page, 0) }
    }

    fun checkpoint(label: String) {
        require(label.matches(Regex("[a-z_]{1,32}")))
        if (enabled) synchronized(lock) { record("checkpoint_$label", 0, 0, 0) }
    }

    fun snapshot(): JsonObject = synchronized(lock) {
        val alive = allocations.filter { it.weak.alive() }
        buildJsonObject {
            put("schema", 1)
            put("clock", "process-local monotonic nanoseconds")
            put("mode", if (neighbourCacheEnabled) "three_page_window" else "visible_pages_only")
            put("managedWindowLimitIncludingLoad", if (neighbourCacheEnabled) 3 else 2) // visible spread can contain two pages
            put("inputPoint", "production click handler; excludes dispatch before the handler")
            put("drawPoint", "target bitmap draw commands recorded; not physical presentation")
            put("firstPresentLatencyVerified", false)
            put("gpuRetentionObserved", false)
            put("weakLivenessIsNativeDeallocationProof", false)
            put("complete", dropped == 0 && !allocationOverflow)
            put("droppedEvents", dropped)
            put("allocationOverflow", allocationOverflow)
            put("decodeInFlight", decoding)
            put("peakDecodeInFlight", peakDecoding)
            put("peakWeakLiveDecodedBackings", peakDecoded)
            put("peakWeakReachableBackingBytes", peakReachableBytes)
            put("peakWeakLiveDecodedSnapshot", peakDecodedSnapshot ?: JsonNull)
            put("peakWeakReachableBytesSnapshot", peakBytesSnapshot ?: JsonNull)
            put("ownershipOrReleaseIsPhysicalProof", false)
            put("compositionOwners", owners.values.sum())
            put("weakLiveBackings", JsonArray(alive.map(::backingReceipt)))
            put("allocations", JsonArray(allocations.map { allocation -> buildJsonObject {
                for ((key, value) in backingReceipt(allocation)) put(key, value)
                put("weakAlive", allocation.weak.alive())
            } }))
            put("events", JsonArray(events.toList()))
            put("process", readerProcessMemory())
        }
    }

    private fun record(kind: String, session: Int, page: Int, action: Long, image: Long = 0, timestamp: Long = nowNs()) {
        val elapsed = if (kind.startsWith("input_") || kind == "target_window_draw_commands") readerElapsedRealtimeNanos() else null
        val ordinal = ++eventOrdinal
        val alive = allocations.filter { it.weak.alive() }
        val decoded = alive.count { it.storage == "decoded" }
        val bytes = alive.sumOf { it.bytes }
        // Capture identities with the same weak-reference sample as each high water. Do not
        // substitute final-GC liveness or current Map size for the earlier peak.
        if (decoded > peakDecoded) { peakDecoded = decoded; peakDecodedSnapshot = peakReceipt(kind, timestamp, alive) }
        if (bytes > peakReachableBytes) { peakReachableBytes = bytes; peakBytesSnapshot = peakReceipt(kind, timestamp, alive) }
        if (events.size >= LIMIT) { events.removeFirst(); dropped++ }
        val eventClock = readerTraceMarker(kind, action, image, ordinal, elapsed)
        events.addLast(buildJsonObject {
            put("kind", kind); put("ns", timestamp); put("session", session); put("page", page)
            put("ordinal", ordinal)
            if (eventClock != null) {
                put("elapsedRealtimeNanos", eventClock.elapsed)
                put("markerBeforeElapsedRealtimeNanos", eventClock.before)
                put("markerAfterElapsedRealtimeNanos", eventClock.after)
                put("traceMarkerWritten", eventClock.written)
            }
            put("action", action); put("image", image)
            put("decodeInFlight", decoding); put("compositionOwners", owners.values.sum())
            put("weakLiveDecodedBackings", decoded); put("weakLiveScratchBackings", alive.count { it.storage == "scratch" })
            put("weakReachableBackingBytes", bytes)
        })
    }

    private fun backingReceipt(allocation: Allocation): JsonObject = buildJsonObject {
        put("image", allocation.image); put("storage", allocation.storage)
        put("width", allocation.width); put("height", allocation.height); put("bytes", allocation.bytes)
        put("sourceSession", allocation.observation.session); put("sourcePage", allocation.observation.page)
        put("prefetch", allocation.observation.prefetch)
        put("published", allocation.image in published); put("retired", allocation.image in retired)
        put("releaseDisposition", releases[allocation.image]?.let(::JsonPrimitive) ?: JsonNull)
        put("compositionOwners", owners[allocation.image] ?: 0)
        put("lease", leaseOwnership[allocation.image]?.let { state -> buildJsonObject {
            put("cacheOwned", state.cacheOwned); put("composedUses", state.composedUses)
            put("valueCleared", state.valueCleared); put("everDrawn", state.everDrawn)
        } } ?: JsonNull)
    }

    private fun peakReceipt(kind: String, timestamp: Long, alive: List<Allocation>) = buildJsonObject {
        put("event", kind); put("ns", timestamp)
        put("decoded", alive.count { it.storage == "decoded" }); put("scratch", alive.count { it.storage == "scratch" })
        put("bytes", alive.sumOf { it.bytes }); put("decodeInFlight", decoding)
        put("compositionOwners", owners.values.sum()); put("backings", JsonArray(alive.map(::backingReceipt)))
    }
}

internal class ReaderDecodeObservation(val epoch: Int, val session: Int, val page: Int, val action: Long,
                                     val image: Long, val prefetch: Boolean)

class ReaderDecodePause internal constructor() {
    internal var taken = false
    internal val entered = CompletableDeferred<Long>()
    internal val permit = CompletableDeferred<Unit>()
    suspend fun awaitEntered(): Long = entered.await()
    fun resume() { permit.complete(Unit) }
}
internal fun interface ReaderBitmapWeakReference { fun alive(): Boolean }
internal expect fun readerWeakBitmap(value: Any): ReaderBitmapWeakReference
internal expect fun readerProcessMemory(): JsonObject
internal data class ReaderEventClock(val elapsed: Long, val before: Long, val after: Long, val written: Boolean)
internal expect fun readerElapsedRealtimeNanos(): Long?
internal expect fun readerTraceMarker(kind: String, action: Long, image: Long, ordinal: Long, elapsed: Long?): ReaderEventClock?
internal expect fun readerWindowDraw(canvas: Canvas): Boolean

/** Android additionally observes swap-chain submission. JVM deliberately reports no presentation callback. */
@Composable
internal expect fun readerFrameSubmitted(): (ReaderDecodeObservation, Long) -> Unit

internal data class ReaderLoadedImage(val raster: ReaderRasterLease<ImageBitmap>, val sourcePage: Int, val sourceSession: Int,
                                     val observation: ReaderDecodeObservation?, val identity: ReaderRasterIdentity, val readyNs: Long)

internal expect fun releaseReaderImage(bitmap: ImageBitmap, drawn: Boolean): String
