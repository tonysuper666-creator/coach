package com.coach.core
import com.coach.store.LedgerStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** All mutations, including backups, job claims and maintenance, share this mailbox. */
class LedgerActor(scope: CoroutineScope, private val store: LedgerStore) {
    private val mailbox = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val worker = scope.launch(Dispatchers.IO) { for (action in mailbox) action() }
    suspend fun <T> execute(block: LedgerStore.() -> T): T {
        val result = CompletableDeferred<T>()
        mailbox.send { try { result.complete(store.block()) } catch (e: Throwable) { result.completeExceptionally(e) } }
        return result.await()
    }
    suspend fun close() { mailbox.close(); worker.join(); store.close() }
}
