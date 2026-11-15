package com.coach.desktop.books

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/** UI must observe changes and clear previous results on revoke, including results already displayed. */
internal interface ConsentingBookLearningSupport : BookLearningSupport {
    val changes: StateFlow<Long>
    fun consentOffer(book: String): NeedsConsent
    suspend fun grant(offer: NeedsConsent, actions: Set<OwnedBookAction>)
    suspend fun decline(book: String)
    suspend fun revoke(book: String)
    /** Call with the book deletion operation; also removes the remembered decline. */
    suspend fun forgetBook(book: String)
}

internal class ConsentedBookLearningSupport(
    private val library: TextbookLibrary,
    private val store: OwnedBookConsentStore,
    private val transport: OwnedBookCliTransport,
    private val identity: OwnedBookConsentIdentity = OwnedBookConsentIdentity(),
) : ConsentingBookLearningSupport {
    override val changes = store.changes
    override fun consentOffer(book: String) = store.offer(book, identity)
    override suspend fun grant(offer: NeedsConsent, actions: Set<OwnedBookAction>) {
        require(offer.identity == identity)
        revoke(offer.book)
        withContext(Dispatchers.IO) { store.grant(offer, actions) }
    }
    override suspend fun decline(book: String) { withContext(Dispatchers.IO) { store.revoke(book, identity) }.joinAll() }
    override suspend fun revoke(book: String) { withContext(Dispatchers.IO) { store.revoke(book) }.joinAll() }
    override suspend fun forgetBook(book: String) = revoke(book)

    private fun needsConsent(book: String) = BookLearningReply.Unavailable(
        "这本书没开讲解，可以直接读原文或先做题。", consentOffer(book),
    )

    override suspend fun explain(request: BookExplanationRequest) = run(request.book, OwnedBookAction.EXPLAIN) {
        it.explain(request)
    }
    override suspend fun ask(request: BookPassageQuestion) = run(request.passage.book, OwnedBookAction.ASK) {
        it.ask(request)
    }

    private suspend fun run(
        book: String, action: OwnedBookAction, call: suspend (BookLearningSupport) -> BookLearningReply,
    ): BookLearningReply = supervisorScope {
        var failure: LocalCodexFailure? = null
        lateinit var lease: BookConsentLease
        val request = async(start = CoroutineStart.LAZY) {
            withContext(lease) {
                val guardedTransport = OwnedBookCliTransport { input ->
                    currentCoroutineContext().ensureActive()
                    lease.checked { Unit }
                    try { transport.complete(input) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (error: LocalCodexFailure) { failure = error; throw error }
                }
                call(windowsBookLearningSupport(library, guardedTransport, ConsentedOwnedBookOutbound(store, identity)))
            }
        }
        val registered = store.register(book, action, identity, request)
        if (registered == null) {
            request.cancel()
            return@supervisorScope needsConsent(book)
        }
        lease = registered
        try {
            val reply = request.await()
            // Provider rechecks the original; this separately rechecks consent after the answer returns.
            lease.checked { failure?.let { BookLearningReply.Unavailable(it.reason.message) } ?: reply }
        } catch (cancel: CancellationException) {
            currentCoroutineContext().ensureActive() // Propagate navigation/application cancellation.
            needsConsent(book) // A revoked child cannot publish its result.
        } catch (_: IllegalStateException) {
            needsConsent(book)
        } finally { store.release(lease) }
    }
}

internal class MissingLocalCodexSupport : BookLearningSupport {
    private val reply = BookLearningReply.Unavailable(LocalCodexProblem.MISSING.message)
    override suspend fun explain(request: BookExplanationRequest) = reply
    override suspend fun ask(request: BookPassageQuestion) = reply
}

/** Used by the real entry point as well as its tests. Discovery cannot grant consent. */
internal fun localWindowsBookLearningSupport(
    library: TextbookLibrary,
    executable: java.nio.file.Path? = discoverLocalCodex(),
): BookLearningSupport = if (executable == null) MissingLocalCodexSupport() else {
    val identity = OwnedBookConsentIdentity()
    ConsentedBookLearningSupport(library, OwnedBookConsentStore(library), LocalCodexBookTransport(executable, identity), identity)
}
