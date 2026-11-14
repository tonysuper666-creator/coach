package com.coach.desktop.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.coach.client.ReaderReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject

/** IDs and versions come from this connection's catalog, never a local PDF hash. */
internal data class ReaderOpenRequest(
    val documentId: String,
    val version: String? = null,
    val pdfPage: Int? = null,
    val anchor: String? = null,
)

/** One owner of the window, requests and transport. Close the editor before retiring requests. */
internal class ReaderHostController(
    source: ReaderSource,
    drafts: NoteDraftStore? = null,
    private val closeTransport: () -> Unit = {},
) : AutoCloseable {
    private val connection = WindowReaderSource(source)
    val remote = ReaderRemote(connection)
    val session = ReaderSession(ReaderFixture("资料", emptyList(), emptyList()), 1200, remote, drafts)
    val authorized get() = connection.authorized
    var openingFailure by mutableStateOf<ReaderFailure?>(null); private set
    private var closed = false

    suspend fun open(request: ReaderOpenRequest? = null) {
        remote.loadCatalog()
        openingFailure = remote.failure
        if (openingFailure != null) return
        val doc = if (request == null) remote.documents.firstOrNull()
            else remote.documents.firstOrNull { it.id == request.documentId }
        if (doc == null) {
            openingFailure = ReaderFailure.BadResponse("没有这本书")
            return
        }
        if (request?.version != null && request.version != doc.version) {
            openingFailure = ReaderFailure.VersionChanged
            return
        }
        if (request?.pdfPage != null && request.pdfPage !in 1..doc.pages) {
            openingFailure = ReaderFailure.MissingPage(request.pdfPage)
            return
        }
        session.openRemote(doc.id, request?.pdfPage, request?.anchor)
        openingFailure = remote.failure
        if (remote.current != null) remote.show(PdfPages.toPdf(session.state.position.page))
    }

    /** A 401 retires IO, but keeps the editor visible so its text can still be copied. */
    fun revoke() = connection.revoke()

    override fun close() {
        if (closed) return
        closed = true
        // HTTP has no proved durable owner yet. Never clear its only in-memory copy on revocation.
        if (session.drafts != null) session.cancelNote()
        connection.close()
        closeTransport()
    }
}

/** Cancels only this window's child requests; even a cancellation-ignoring source cannot publish late. */
internal class WindowReaderSource(private val source: ReaderSource) : ReaderSource, AutoCloseable {
    private val lock = Any()
    private val requests = mutableSetOf<Job>()
    private var closed = false
    var authorized by mutableStateOf(true); private set

    private suspend fun <T> request(unauthorized: T, call: suspend () -> T): T = coroutineScope {
        val job = currentCoroutineContext()[Job]!!
        synchronized(lock) {
            if (closed) throw CancellationException("Reader window closed")
            if (!authorized) return@coroutineScope unauthorized
            requests += job
        }
        try {
            val value = call()
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (closed) throw CancellationException("Reader window closed")
                if (!authorized) return@coroutineScope unauthorized
            }
            if (value is ReaderReply && value.status == 401) retire(job)
            value
        } finally {
            synchronized(lock) { requests -= job }
        }
    }

    private fun retire(except: Job? = null) {
        val pending = synchronized(lock) {
            authorized = false
            requests.filter { it !== except }
        }
        pending.forEach { it.cancel(CancellationException("Reader pairing revoked")) }
    }

    fun revoke() = retire()
    override fun close() {
        synchronized(lock) { closed = true }
        retire()
    }

    private suspend fun reply(call: suspend () -> ReaderReply) = request(ReaderReply(401, null), call)
    override suspend fun catalog() = reply { source.catalog() }
    override suspend fun page(document: String, pdfPage: Int) = reply { source.page(document, pdfPage) }
    override suspend fun image(document: String, pdfPage: Int, version: String?) = request<ByteArray?>(null) { source.image(document, pdfPage, version) }
    override suspend fun study(document: String, pdfPage: Int) = reply { source.study(document, pdfPage) }
    override suspend fun boxes(document: String, pdfPage: Int, version: String) = reply { source.boxes(document, pdfPage, version) }
    override suspend fun saveNote(document: String, body: JsonObject) = reply { source.saveNote(document, body) }
    override suspend fun state(document: String) = reply { source.state(document) }
    override suspend fun saveProgress(document: String, version: String, pdfPage: Int, baseRevision: Long, mutationId: String) =
        reply { source.saveProgress(document, version, pdfPage, baseRevision, mutationId) }
}
