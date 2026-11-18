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

/** One request per process. Installation and authorization remain the caller's responsibility. */
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
            if (process.isAlive) {
                // Give the adapter a chance to cancel and reap the existing CLI process.
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                }
            }
        }
    }

    private suspend fun awaitExit(process: Process): Int = suspendCancellableCoroutine { continuation ->
        process.onExit().whenComplete { finished, error ->
            if (error != null) continuation.resumeWithException(error)
            else continuation.resume(finished.exitValue())
        }
        continuation.invokeOnCancellation { process.destroy() }
    }
}
