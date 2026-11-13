package com.coach.probes

import kotlinx.coroutines.*

/** Run the real Main unchanged. Nested runBlocking shares this thread's event loop,
 * so this heartbeat also observes blocking of Main's actor/supervisor dispatcher.
 * No listener, route, authentication or retry logic is duplicated here.
 */
fun main(): Unit = runBlocking {
    launch(start = CoroutineStart.UNDISPATCHED) {
        while (isActive) {
            println("TAILNET_HEARTBEAT ${System.nanoTime()}")
            delay(100)
        }
    }
    com.coach.server.main(emptyArray())
}
