package com.coach.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import com.coach.client.ReaderHttp
import com.coach.client.ReaderPageWindow
import com.coach.client.ReaderRasterIdentity
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// A closing window's non-interruptible native decode must finish before another window decodes.
private val readerDecodeGate = Mutex()
private val emptyReaderRevision = MutableStateFlow(0L)

private class ReaderImageResource(reader: ReaderHttp, context: CoroutineContext, session: Int) {
    var users = 0
    var action = 0L
    val window = ReaderPageWindow<ReaderLoadedImage>(context, load = { key, prefetch ->
        val action = if (prefetch) 0L else this.action
        ReaderPageTrace.mark("request_started", session, key.page, action)
        val bytes = reader.image(key.identity.document, key.page, key.identity.version)
        ReaderPageTrace.mark(if (bytes != null) "request_ready" else "request_failed", session, key.page, action)
        bytes?.let {
            readerDecodeGate.withLock {
                withContext(Dispatchers.Default) {
                    val observation = ReaderPageTrace.decode(session, key.page, action, prefetch)
                    var bitmap: androidx.compose.ui.graphics.ImageBitmap? = null
                    try {
                        bitmap = runCatching { decodeReaderImage(bytes, observation) }.getOrNull()
                        bitmap?.let { ReaderLoadedImage(it, key.page, session, observation, key.identity, Long.MAX_VALUE) }
                    } finally { ReaderPageTrace.decoded(observation, bitmap != null) }
                }
            }
        }
    }, discard = { key, image ->
        // Drop ownership, leaving renderer/native references to their own lifetimes. Never recycle a displayed bitmap.
        ReaderPageTrace.mark("cache_evicted", session, key.page, 0, image.observation?.image ?: 0)
    }, publish = { _, image -> image.copy(readyNs = ReaderPageTrace.nowNs()) })
}

private object ReaderImageResources {
    private val resources = mutableMapOf<ReaderHttp, ReaderImageResource>()
    fun acquire(reader: ReaderHttp, context: CoroutineContext, session: Int): ReaderImageResource = synchronized(resources) {
        resources.getOrPut(reader) { ReaderImageResource(reader, context, session) }.also { it.users++ }
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
                                 private val page: Int, private val pages: Int, private val context: CoroutineContext,
                                 private val session: Int, private var action: Long, private var allowPrefetch: Boolean) : RememberObserver {
    private var resource by mutableStateOf<ReaderImageResource?>(null)
    private var viewer: ReaderPageWindow<ReaderLoadedImage>.Viewer? = null
    val changes get() = resource?.window?.changes ?: emptyReaderRevision
    val foreground get() = resource != null && viewer?.foreground == true

    override fun onRemembered() {
        val acquired = ReaderImageResources.acquire(reader, context, session)
        acquired.action = action
        viewer = acquired.window.open(identity, page, pages, allowPrefetch = allowPrefetch)
        resource = acquired
    }
    override fun onForgotten() = release()
    override fun onAbandoned() = release()
    private fun release() {
        val acquired = resource ?: return
        viewer?.close(); viewer = null
        ReaderImageResources.release(reader, acquired)
        resource = null
    }

    fun show(page: Int, visible: Int? = null, action: Long = this.action, allowPrefetch: Boolean = this.allowPrefetch) {
        this.action = action
        this.allowPrefetch = allowPrefetch
        if (viewer?.foreground == true) resource?.action = action
        if (visible == null) viewer?.show(page, allowPrefetch = allowPrefetch) else viewer?.show(page, visible, allowPrefetch)
    }
    fun image(page: Int): ReaderLoadedImage? = viewer?.image(page)?.takeIf { it.identity == identity && it.sourcePage == page }
    fun failed(page: Int) = viewer?.failed(page) == true
    fun pending(page: Int) = viewer?.pending(page) == true
    fun retry(page: Int) { viewer?.retry(page) }
    fun presented(page: Int) { viewer?.presented(page) }
}

@Composable
internal fun rememberReaderImages(reader: ReaderHttp, doc: PrivateDocument, page: Int, session: Int, action: Long, allowPrefetch: Boolean): ReaderImageViewer {
    val density = LocalDensity.current
    // Current transport returns the server PNG at a fixed raster configuration. Include native presentation configuration
    // as a conservative invalidation boundary; no bitmap from another version or decoder configuration is reused.
    val identity = ReaderRasterIdentity(doc.id, doc.version, "server-png/native-default/fill-width/${density.density}/${density.fontScale}")
    val scope = rememberCoroutineScope()
    return remember(reader, identity) { ReaderImageViewer(reader, identity, page, doc.pages, scope.coroutineContext, session, action, allowPrefetch) }
}
