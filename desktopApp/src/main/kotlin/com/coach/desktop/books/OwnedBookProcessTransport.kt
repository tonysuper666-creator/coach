package com.coach.desktop.books

import java.util.concurrent.TimeUnit
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
                    val exit = awaitExit(process)
                    input.await()
                    val bytes = output.await()
                    require(exit == 0 && bytes.size <= 1_048_576) { "Book CLI transport failed" }
                    bytes.toString(Charsets.UTF_8)
                }
            }
            result ?: error("Book CLI transport timed out")
        } finally {
            terminateTree(process)
        }
    }

    private fun terminateTree(process: Process) = synchronized(process) {
        // Snapshot while the parent is still alive: killing it first can orphan its CLI.
        // This cannot recover a descendant already orphaned by an earlier parent exit.
        val descendants = process.descendants().use { it.toList() }
        val byPid = descendants.associateBy { it.pid() }
        fun depth(handle: ProcessHandle): Int {
            var parent = handle.parent().orElse(null)
            var depth = 0
            while (parent != null && byPid.containsKey(parent.pid())) {
                depth++
                parent = parent.parent().orElse(null)
            }
            return depth
        }
        descendants.sortedByDescending(::depth).forEach { it.destroyForcibly() }
        if (process.isAlive) process.destroyForcibly()
        descendants.forEach { runCatching { it.onExit().get(2, TimeUnit.SECONDS) } }
        if (process.isAlive) process.waitFor(2, TimeUnit.SECONDS)
        Unit
    }

    private suspend fun awaitExit(process: Process): Int = suspendCancellableCoroutine { continuation ->
        process.onExit().whenComplete { finished, error ->
            if (error != null) continuation.resumeWithException(error)
            else continuation.resume(finished.exitValue())
        }
        // Unblock pipe readers on cancellation, but snapshot descendants before killing the parent.
        continuation.invokeOnCancellation { terminateTree(process) }
    }
}
