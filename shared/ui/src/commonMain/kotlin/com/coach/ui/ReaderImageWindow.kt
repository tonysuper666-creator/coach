package com.coach.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import com.coach.client.ReaderHttp
import com.coach.client.ReaderPageWindow
import com.coach.client.ReaderRasterIdentity
import com.coach.client.ReaderRasterLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// A closing window's non-interruptible native decode must finish before another window decodes.
private val readerDecodeGate = Mutex()
private val emptyReaderRevision = MutableStateFlow(0L)

internal data class ReaderDocumentSnapshot(val document: PrivateDocument, val state: JsonObject)

private class ReaderImageResource(reader: ReaderHttp, session: Int) {
    val neighbourCacheEnabled = ReaderPageTrace.neighbourCacheEnabled
    var users = 0
    val viewers = mutableStateListOf<ReaderImageViewer>()
    val states = mutableStateMapOf<PrivateDocument, JsonObject>()
    @kotlin.concurrent.Volatile var action = 0L
    fun update(snapshot: ReaderDocumentSnapshot) {
        val old = states[snapshot.document]
        if (old == null || progressRevision(snapshot.state) >= progressRevision(old)) states[snapshot.document] = snapshot.state
    }
    // In particular, never inherit the pausable composition dispatcher or its test frame clock.
    val window: ReaderPageWindow<ReaderLoadedImage> = ReaderPageWindow(Dispatchers.Default, prefetchBeforeDraw = true,
        retainNeighbours = neighbourCacheEnabled,
        onDrained = { ReaderPageTrace.mark("window_drained", session, 0, 0) }, load = { key, prefetch ->
        val action = if (prefetch) 0L else this.action
        val request = ReaderPageTrace.request(session, key.page, action)
        val bytes = reader.image(key.identity.document, key.page, key.identity.version) { stage ->
            ReaderPageTrace.mark(stage, session, key.page, action, request)
        }
        ReaderPageTrace.mark(if (bytes != null) "request_ready" else "request_failed", session, key.page, action, request)
        bytes?.let {
            // Fetch overlaps recording the current image. Native decode retains visible priority.
            if (prefetch && !window.awaitPrefetchDecode(key)) return@let null
            readerDecodeGate.withLock {
                coroutineContext.ensureActive()
                // Native decoding cannot be interrupted. Keep its result reachable until the window's
                // completion handler can publish or retire it, including cancellation during decode.
                withContext(NonCancellable) {
                    val observation = ReaderPageTrace.decode(session, key.page, action, prefetch)
                    var bitmap: androidx.compose.ui.graphics.ImageBitmap? = null
                    try {
                        ReaderPageTrace.awaitDecodePermit(observation)
                        bitmap = runCatching { decodeReaderImage(bytes, observation) }.getOrNull()
                        bitmap?.let {
                            val raster = ReaderRasterLease(it) { value, drawn ->
                                val release = releaseReaderImage(value, drawn)
                                ReaderPageTrace.mark(release, session, key.page, 0, observation?.image ?: 0)
                            }
                            if (observation != null) raster.observeOwnership { ReaderPageTrace.ownership(observation, it) }
                            ReaderLoadedImage(raster, key.page, session, observation, key.identity, Long.MAX_VALUE)
                        }
                    } finally { ReaderPageTrace.decoded(observation, bitmap != null) }
                }
            }
        }
    }, discard = { key, image ->
        ReaderPageTrace.mark("cache_evicted", session, key.page, 0, image.observation?.image ?: 0)
        image.raster.retire()
    }, publish = { _, image ->
        ReaderPageTrace.mark("cache_published", image.sourceSession, image.sourcePage, 0, image.observation?.image ?: 0)
        image.copy(readyNs = ReaderPageTrace.nowNs())
    })
    init { ReaderPageTrace.mark("window_opened", session, 0, 0) }
}

private object ReaderImageResources {
    val resources = mutableStateMapOf<ReaderHttp, ReaderImageResource>()
    fun acquire(reader: ReaderHttp, session: Int): ReaderImageResource = synchronized(resources) {
        resources.getOrPut(reader) { ReaderImageResource(reader, session) }.also { it.users++ }
    }
    fun release(reader: ReaderHttp, resource: ReaderImageResource) = synchronized(resources) {
        if (--resource.users == 0) {
            resources.remove(reader)
            resource.window.close()
        }
    }
}

/** Registration follows composition ownership, including abandoned compositions. */
internal class ReaderImageViewer(private val reader: ReaderHttp, val identity: ReaderRasterIdentity,
                                 private val page: Int, private val pages: Int,
                                 snapshot: ReaderDocumentSnapshot, val citation: Boolean,
                                 private val session: Int, private var action: Long, private var allowPrefetch: Boolean) : RememberObserver {
    var snapshot by mutableStateOf(snapshot)
    private var resource by mutableStateOf<ReaderImageResource?>(null)
    private var viewer: ReaderPageWindow<ReaderLoadedImage>.Viewer? = null
    val changes get() = resource?.window?.changes ?: emptyReaderRevision
    val foreground get() = resource != null && viewer?.foreground == true

    override fun onRemembered() {
        val acquired = ReaderImageResources.acquire(reader, session)
        acquired.action = action
        viewer = acquired.window.open(identity, page, pages, allowPrefetch = allowPrefetch)
        acquired.viewers += this
        acquired.update(snapshot)
        resource = acquired
    }
    override fun onForgotten() = release()
    override fun onAbandoned() = release()
    private fun release() {
        val acquired = resource ?: return
        viewer?.close(); viewer = null
        acquired.viewers.remove(this)
        if (acquired.viewers.none { it.snapshot.document == snapshot.document }) acquired.states.remove(snapshot.document)
        ReaderImageResources.release(reader, acquired)
        resource = null
    }

    fun show(page: Int, visible: Int? = null, action: Long = this.action, allowPrefetch: Boolean = this.allowPrefetch) {
        this.action = action
        this.allowPrefetch = allowPrefetch
        if (viewer?.foreground == true) resource?.action = action
        if (viewer?.foreground == true && resource?.neighbourCacheEnabled == false) {
            // Observe the real lookup before show() starts the visible request.
            // A cold miss must not become a coalesced-wait merely because the
            // next composition runs after this handler has already started I/O.
            ReaderPageTrace.lookup(session, page, action, image(page), pending(page))
        }
        if (visible == null) viewer?.show(page, allowPrefetch = allowPrefetch) else viewer?.show(page, visible, allowPrefetch)
    }
    fun image(page: Int): ReaderLoadedImage? = viewer?.image(page)?.takeIf { it.identity == identity && it.sourcePage == page }
    fun failed(page: Int) = viewer?.failed(page) == true
    fun pending(page: Int) = viewer?.pending(page) == true
    fun retry(page: Int) { viewer?.retry(page) }
    fun presented(page: Int) { viewer?.presented(page) }
    fun update(snapshot: ReaderDocumentSnapshot) { this.snapshot = snapshot; resource?.update(snapshot) }
    fun confirmedRevision(): Long? = resource?.states?.get(snapshot.document)?.let(::progressRevision)
}

@Composable
internal fun rememberReaderImages(reader: ReaderHttp, doc: PrivateDocument, state: JsonObject, citation: Boolean,
                                  page: Int, session: Int, action: Long, allowPrefetch: Boolean): ReaderImageViewer {
    val density = LocalDensity.current
    // Current transport returns the server PNG at a fixed raster configuration. Include native presentation configuration
    // as a conservative invalidation boundary; no bitmap from another version or decoder configuration is reused.
    val identity = ReaderRasterIdentity(doc.id, doc.version, "server-png/native-default/fill-width/${density.density}/${density.fontScale}")
    val snapshot = ReaderDocumentSnapshot(doc, state)
    val images = remember(reader, identity) { ReaderImageViewer(reader, identity, page, doc.pages, snapshot, citation, session, action, allowPrefetch) }
    SideEffect { images.update(snapshot) }
    return images
}

/** Only an open book owns metadata. Closing the last viewer removes this reader/account scope. */
@Composable
internal fun activeReaderDocument(reader: ReaderHttp): ReaderDocumentSnapshot? {
    val resource = ReaderImageResources.resources[reader] ?: return null
    val snapshot = resource.viewers.lastOrNull { !it.citation }?.snapshot ?: return null
    return snapshot.copy(state = resource.states[snapshot.document] ?: snapshot.state)
}

private fun progressRevision(state: JsonObject): Long =
    ((state["progress"] as? JsonObject)?.get("revision") as? JsonPrimitive)?.longOrNull ?: 0L

/** Acquire during remember, so eviction cannot close a raster between composition and its effects. */
internal class ReaderImageUse(val use: ReaderRasterLease<androidx.compose.ui.graphics.ImageBitmap>.Use) : RememberObserver {
    override fun onRemembered() = Unit
    override fun onForgotten() = use.close()
    override fun onAbandoned() = use.close()
}
