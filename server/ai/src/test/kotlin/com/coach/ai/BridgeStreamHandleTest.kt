package com.coach.ai

import java.io.ByteArrayInputStream
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertTrue

class BridgeStreamHandleTest {
    @Test
    fun cancellationBeforeTheFutureIsPublishedStillCancelsIt() {
        val handle = BridgeStreamHandle()
        handle.cancel()
        val future = CompletableFuture<Unit>()
        handle.future = future
        assertTrue(future.isCancelled)
    }

    @Test
    fun cancellationBeforeTheResponseBodyIsPublishedStillClosesIt() {
        val handle = BridgeStreamHandle()
        var closed = false
        val body = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { closed = true; super.close() }
        }
        handle.cancel()
        handle.body = body
        assertTrue(closed)
    }
}
