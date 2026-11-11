package com.coach.server

import io.ktor.server.routing.RoutingCall
import io.ktor.server.netty.NettyApplicationCall
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import kotlinx.coroutines.*

/** Netty does not cancel an HTTP handler merely because its peer closes the socket. */
internal suspend fun <T> RoutingCall.withReaderConnection(block: suspend () -> T): T = coroutineScope {
    val connection = (pipelineCall.engineCall as? NettyApplicationCall)?.context?.channel()?.closeFuture()
    val job = currentCoroutineContext().job
    val listener = GenericFutureListener<Future<in Void>> { job.cancel(CancellationException("Reader client disconnected")) }
    connection?.addListener(listener)
    try { block() } finally { connection?.removeListener(listener) }
}
