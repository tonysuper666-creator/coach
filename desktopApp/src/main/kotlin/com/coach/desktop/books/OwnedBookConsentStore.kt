package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

internal data class OwnedBookConsentIdentity(
    val provider: String = "local-codex-subscription-cli",
    val model: String = "gpt-6.1-sol",
    val notice: String = "owned-book-excerpts-v1",
)

/** A UI offer, never an authorization by itself. Only the uploader's affirmative button calls grant. */
internal data class NeedsConsent(
    val book: String,
    val identity: OwnedBookConsentIdentity,
    val offerAutomatically: Boolean,
)

internal data class OwnedBookConsent(
    val book: String, val actions: Set<OwnedBookAction>, val identity: OwnedBookConsentIdentity,
    val grantedAt: String,
)

/** Separate local files; no changes to the textbook database or content sent to a CLI. */
internal class OwnedBookConsentStore(private val library: TextbookLibrary) {
    private val directory = library.directory.resolve("book-consents")
    private val active = mutableMapOf<String, MutableSet<BookConsentLease>>()
    private val generation = mutableMapOf<String, Long>()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    private fun path(book: String, suffix: String = ".json"): Path {
        require(book.matches(Regex("[a-f0-9]{64}")))
        return directory.resolve(book + suffix)
    }

    @Synchronized fun offer(book: String, identity: OwnedBookConsentIdentity): NeedsConsent =
        NeedsConsent(book, identity, runCatching { Files.readString(path(book, ".declined")) != identity.notice }.getOrDefault(true))

    @Synchronized fun read(book: String): OwnedBookConsent? = runCatching {
        // Removing the source book invalidates and removes its consent, including on app restart.
        val source = runCatching { library.book(book) }.getOrNull()
        if (source == null || !Files.isRegularFile(library.directory.resolve("$book.${source.format}"))) {
            Files.deleteIfExists(path(book)); return null
        }
        val file = path(book)
        if (!Files.isRegularFile(file) || Files.size(file) > 8192) return null
        val json = Json.parseToJsonElement(Files.readString(file)).jsonObject
        require(json.keys == setOf("book", "actions", "provider", "model", "notice", "grantedAt"))
        fun string(key: String) = json.getValue(key).jsonPrimitive.also { require(it.isString) }.content
        val id = string("book"); require(id == book)
        val actions = json.getValue("actions").jsonArray.map {
            when (it.jsonPrimitive.content) { "explain" -> OwnedBookAction.EXPLAIN; "ask" -> OwnedBookAction.ASK; else -> error("action") }
        }.toSet()
        require(actions.isNotEmpty())
        val at = string("grantedAt"); Instant.parse(at)
        OwnedBookConsent(id, actions, OwnedBookConsentIdentity(string("provider"), string("model"), string("notice")), at)
    }.getOrNull()

    /**
     * Replaces any previous consent (and its outstanding leases) with [actions] under one lock and
     * one revision bump, so a single affirmative click is one change for every observer.
     * Caller awaits the returned jobs before reporting the grant complete.
     */
    @Synchronized fun replace(offer: NeedsConsent, actions: Set<OwnedBookAction>): List<Job> {
        require(actions.isNotEmpty())
        library.book(offer.book)
        generation[offer.book] = (generation[offer.book] ?: 0) + 1
        val jobs = active.remove(offer.book).orEmpty().map { it.job }
        jobs.forEach { it.cancel() }
        val record = buildJsonObject {
            put("book", offer.book)
            putJsonArray("actions") { actions.sortedBy { it.name }.forEach { add(it.name.lowercase()) } }
            put("provider", offer.identity.provider); put("model", offer.identity.model)
            put("notice", offer.identity.notice); put("grantedAt", Instant.now().toString())
        }
        Files.createDirectories(directory)
        Files.writeString(directory.resolve(".gitignore"), "*\n")
        val temp = Files.createTempFile(directory, "consent-", ".tmp")
        try {
            Files.writeString(temp, record.toString())
            Files.move(temp, path(offer.book), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Files.deleteIfExists(path(offer.book, ".declined"))
        } finally { Files.deleteIfExists(temp) }
        revision.value++
        return jobs
    }

    /** Caller awaits the returned jobs before reporting revoke complete; cancellation starts under the lock. */
    @Synchronized fun revoke(book: String, decline: OwnedBookConsentIdentity? = null): List<Job> {
        generation[book] = (generation[book] ?: 0) + 1
        val jobs = active.remove(book).orEmpty().map { it.job }
        jobs.forEach { it.cancel() }
        Files.deleteIfExists(path(book))
        if (decline != null) {
            Files.createDirectories(directory)
            Files.writeString(directory.resolve(".gitignore"), "*\n")
            Files.writeString(path(book, ".declined"), decline.notice)
        } else Files.deleteIfExists(path(book, ".declined"))
        revision.value++
        return jobs
    }

    @Synchronized fun permits(prompt: OwnedBookPrompt, identity: OwnedBookConsentIdentity): Boolean {
        val book = prompt.excerpts.firstOrNull()?.citation?.book ?: return false
        if (prompt.excerpts.any { it.citation.book != book }) return false
        val consent = read(book) ?: return false
        return consent.identity == identity && prompt.action in consent.actions
    }

    @Synchronized fun register(book: String, action: OwnedBookAction, identity: OwnedBookConsentIdentity, job: Job): BookConsentLease? {
        val record = read(book) ?: return null
        if (record.identity != identity || action !in record.actions) return null
        return BookConsentLease(this, book, action, identity, generation[book] ?: 0, job).also {
            active.getOrPut(book) { mutableSetOf() }.add(it)
        }
    }

    @Synchronized fun release(lease: BookConsentLease) { active[lease.book]?.remove(lease) }

    @Synchronized fun <T> checked(lease: BookConsentLease, operation: () -> T): T {
        check(!lease.job.isCancelled && lease.generation == (generation[lease.book] ?: 0)) { "Consent withdrawn" }
        val record = read(lease.book)
        check(record != null && record.identity == lease.identity && lease.action in record.actions) { "Consent withdrawn" }
        return operation()
    }
}

internal class BookConsentLease(
    val store: OwnedBookConsentStore, val book: String, val action: OwnedBookAction,
    val identity: OwnedBookConsentIdentity, val generation: Long, val job: Job,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BookConsentLease>
    fun <T> checked(operation: () -> T): T = store.checked(this, operation)
}

internal class ConsentedOwnedBookOutbound(
    private val store: OwnedBookConsentStore,
    private val identity: OwnedBookConsentIdentity = OwnedBookConsentIdentity(),
) : OwnedBookOutboundPolicy {
    override fun allows(prompt: OwnedBookPrompt) = store.permits(prompt, identity)
}
