package com.coach.desktop.books

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One request per process. Installation and authorization remain the caller's responsibility.
 * Descendant cleanup is best effort; it is not process containment. A child can escape if its
 * parent exits or forks during cleanup. Do not use for production CLI execution until a
 * launch-time operating-system containment mechanism covers the entire process lifetime.
 */
internal class OwnedBookProcessTransport(
    private val command: List<String>,
    private val timeoutMillis: Long = 180_000,
) : OwnedBookCliTransport {
    override suspend fun complete(request: String): String = withContext(Dispatchers.IO) {
        require(command.isNotEmpty() && timeoutMillis > 0)
        require(request.toByteArray(Charsets.UTF_8).size <= 1_048_576)
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val ownedTree = OwnedProcessTree(process)
        try {
            val result = withTimeoutOrNull(timeoutMillis) {
                coroutineScope {
                    // Record descendants while the adapter lives: one that exits first must not hide its tree.
                    // Cancellation of this scope (timeout, caller cancel, failure) starts the off-thread reaper,
                    // which also releases a pipe reader pinned by a descendant that inherited stdout.
                    val tracker = launch {
                        try {
                            while (process.isAlive) {
                                ownedTree.track()
                                delay(100)
                            }
                            awaitCancellation()
                        } finally {
                            ownedTree.stopAsync()
                        }
                    }
                    val output = async {
                        runInterruptible {
                            process.inputStream.use { it.readNBytes(1_048_577) }
                        }
                    }
                    val input = async {
                        runInterruptible {
                            process.outputStream.use { it.write(request.toByteArray(Charsets.UTF_8)) }
                        }
                    }
                    val exit = awaitExit(process, ownedTree)
                    input.await()
                    val bytes = output.await()
                    tracker.cancel()
                    require(exit == 0 && bytes.size <= 1_048_576) { "Book CLI transport failed" }
                    bytes.toString(Charsets.UTF_8)
                }
            }
            result ?: error("Book CLI transport timed out")
        } finally {
            ownedTree.stop()
        }
    }

    private suspend fun awaitExit(process: Process, ownedTree: OwnedProcessTree): Int = suspendCancellableCoroutine { continuation ->
        process.onExit().whenComplete { finished, error ->
            if (error != null) continuation.resumeWithException(error)
            else continuation.resume(finished.exitValue())
        }
        // Cancellation handlers run on the cancelling thread (UI, timeout timer): never block there.
        continuation.invokeOnCancellation { ownedTree.stopAsync() }
    }

    private class OwnedProcessTree(private val process: Process) {
        private val tracked = LinkedHashSet<ProcessHandle>() // handles carry start time: PID reuse is not matched
        private val reaping = AtomicBoolean(false)

        @Synchronized fun track() {
            process.descendants().use { stream -> stream.forEach { tracked.add(it) } }
        }

        fun stopAsync() {
            if (reaping.compareAndSet(false, true)) {
                Thread(::stop, "owned-book-reaper").apply { isDaemon = true }.start()
            }
        }

        // Cancellation and finally can race. Neither may destroy the parent before the other's snapshot.
        @Synchronized fun stop() {
            try {
                reap()
            } finally {
                // A descendant that escaped tracking may still hold our stdout; closing our end
                // releases the blocked reader so timeout/cancel return on time.
                runCatching { process.inputStream.close() }
            }
        }

        private fun reap() {
            track()
            val descendants = tracked.toList()
            if (!process.isAlive && descendants.none { it.isAlive }) return
            var interrupted = Thread.interrupted()
            try {
                // Capture before signalling: children may be reparented as soon as the adapter exits.
                // First allow the adapter's own cancellation/reaping path to run.
                process.destroy()
                try {
                    process.waitFor(2, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
                val remaining = (descendants + process.descendants().use { it.toList() }).distinct()
                remaining.asReversed().forEach { if (it.isAlive) it.destroyForcibly() }
                if (process.isAlive) process.destroyForcibly()

                // Collect exits in the foreground, with one shared deadline for the entire tree.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                for (handle in remaining + process.toHandle()) {
                    while (handle.isAlive) {
                        val nanos = deadline - System.nanoTime()
                        if (nanos <= 0) break
                        try {
                            handle.onExit().get(nanos, TimeUnit.NANOSECONDS)
                        } catch (_: InterruptedException) {
                            interrupted = true
                        } catch (_: TimeoutException) {
                            break
                        }
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
