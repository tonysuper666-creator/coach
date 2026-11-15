package com.coach.desktop.books

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        continuation.invokeOnCancellation { ownedTree.stop() }
    }

    private class OwnedProcessTree(private val process: Process) {
        // Cancellation and finally can race. Neither may destroy the parent before the other's snapshot.
        @Synchronized fun stop() {
            val descendants = process.descendants().use { it.toList() }
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
