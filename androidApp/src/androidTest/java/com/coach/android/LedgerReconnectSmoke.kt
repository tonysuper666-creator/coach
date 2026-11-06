package com.coach.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.protocol.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.security.MessageDigest
import java.util.UUID

/** Host runner pauses only its isolated JVM. This is not evidence of real OS sleep. */
@RunWith(AndroidJUnit4::class)
class LedgerReconnectSmoke {
    @Test fun reconnectAfterServerSuspension(): Unit = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="synthetic-reconnect.sqlite"
        context.deleteDatabase(name)
        val driver=AndroidSqliteDriver(ClientDatabase.Schema,context,name)
        val args=InstrumentationRegistry.getArguments()
        val token=args.getString("coachToken","")
        assertTrue("Isolated server token is required",token.length>=32)
        assertTrue("Use a non-production build", BuildConfig.COACH_PORT !in setOf(8790,8791,18791))
        val runId=args.getString("coachRunId", "manual")
        fun mark(message: String) = android.util.Log.i("CoachReconnect","$runId $message port=${BuildConfig.COACH_PORT} elapsedMs=${android.os.SystemClock.elapsedRealtime()}")
        val wire=HttpLedgerTransport("http://${BuildConfig.COACH_TAIL_HOST}:${BuildConfig.COACH_PORT}","desktop",token)
        // Only operation names and exception classes: never URLs, tokens, payloads or messages.
        suspend fun <T> observed(operation: String, block: suspend () -> T): T {
            mark("REQUEST_BEGIN operation=$operation")
            try {
                return block().also { mark("REQUEST_END operation=$operation") }
            } catch (error: Throwable) {
                mark("REQUEST_FAILED operation=$operation type=${error.javaClass.simpleName} cause=${error.cause?.javaClass?.simpleName}")
                throw error
            }
        }
        val transport=object : LedgerTransport by wire {
            override suspend fun hello(epoch: String?): Hello = observed("hello") { wire.hello(epoch) }
            override suspend fun snapshot(course: String): Snapshot = observed("snapshot") { wire.snapshot(course) }
            override suspend fun events(course: String, after: Long): EventsPage = observed("events") { wire.events(course,after) }
            override suspend fun watch(onLedger: suspend () -> Unit) = observed("watch") { wire.watch(onLedger) }
        }
        val engine=SyncEngine(ClientDatabase(driver),"desktop",transport,listOf("synthetic"),{UUID.randomUUID().toString()}, { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } })
        val loop=engine.start(this)
        loop.invokeOnCompletion { mark("SYNC_LOOP_FINISHED type=${it?.javaClass?.simpleName ?: "completed"}") }
        val status=launch(Dispatchers.IO) {
            while(isActive) {
                mark("SYNC_STATUS connection=${engine.connection.value} revision=${engine.revision.value} active=${loop.isActive}")
                delay(2000)
            }
        }
        try {
            withTimeout(120000) {
                while(engine.connection.value!="online") delay(50)
                val epoch=engine.epoch
                val revision=engine.revision.value
                mark("READY")
                while(engine.connection.value!="offline") delay(50)
                mark("OFFLINE")
                // A new round-trip after OFFLINE cannot be a buffered pre-pause response.
                // It also prevents an in-flight sync from being mistaken for recovery.
                while (true) {
                    try {
                        assertEquals(epoch, transport.hello(epoch).epoch)
                        break
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { delay(250) }
                }
                while(engine.connection.value!="online" || engine.revision.value<=revision) delay(50)
                assertEquals(epoch,engine.epoch)
                mark("RECONNECTED epochUnchanged=true")
            }
        } finally {
            status.cancelAndJoin();loop.cancelAndJoin();wire.close();driver.close();context.deleteDatabase(name)
        }
    }
}
