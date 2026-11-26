package com.coach.server

import io.ktor.server.routing.RoutingCall
import io.ktor.server.netty.NettyApplicationCall
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import kotlinx.coroutines.*

private class ReaderPeerClosed : CancellationException("Reader client disconnected")

/** Netty does not cancel an HTTP handler merely because its peer closes the socket. */
internal suspend fun <T> RoutingCall.withReaderConnection(block: suspend () -> T): T = try {
    coroutineScope {
        val connection = (pipelineCall.engineCall as? NettyApplicationCall)?.context?.channel()?.closeFuture()
        val job = currentCoroutineContext().job
        val listener = GenericFutureListener<Future<in Void>> { job.cancel(ReaderPeerClosed()) }
        connection?.addListener(listener)
        try {
            val outcome = attributes.getOrNull(readerOutcome)
            if (outcome == null) block() else withContext(outcome) { block() }
        } finally { connection?.removeListener(listener) }
    }
} catch (closed: ReaderPeerClosed) {
    attributes.getOrNull(readerOutcome)?.finish(499)
    throw closed
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    // Keep the exception class and our code location. Messages can contain submitted book text.
    val frame = failure.stackTrace.firstOrNull { it.className.startsWith("com.coach.") }
    org.slf4j.LoggerFactory.getLogger("coach.reader").warn("Reader handler failed: {} at {}.{}:{}",
        failure.javaClass.simpleName, frame?.className, frame?.methodName, frame?.lineNumber)
    throw failure
}
