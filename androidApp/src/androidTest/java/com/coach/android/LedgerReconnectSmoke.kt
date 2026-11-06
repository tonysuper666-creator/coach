package com.coach.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.protocol.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.security.MessageDigest
import java.util.UUID

/** Host runner pauses only its isolated JVM. This is not evidence of real OS sleep. */
@RunWith(AndroidJUnit4::class)
class LedgerReconnectSmoke {
    private var foreground: ReconnectSmokeActivity? = null

    @Before fun openIsolatedForegroundSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = android.content.Intent(instrumentation.targetContext,
            ReconnectSmokeActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        foreground = instrumentation.startActivitySync(intent) as ReconnectSmokeActivity
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertTrue("Isolated test activity must be resumed", foreground?.resumed == true)
        }
    }

    @After fun closeIsolatedForegroundSurface() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            foreground?.finishAndRemoveTask()
            foreground = null
        }
    }

    @Test fun reconnectAfterServerSuspension(): Unit = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="synthetic-reconnect.sqlite"
        context.deleteDatabase(name)
        val driver=AndroidSqliteDriver(ClientDatabase.Schema,context,name)
        val args=InstrumentationRegistry.getArguments()
        val token=args.getString("coachToken","")
        assertTrue("Isolated server token is required",token.length>=32)
        assertTrue("Use a non-production build", BuildConfig.COACH_PORT !in setOf(8790,8791,18791))
        val runId=args.getString("coachRunId", "")
        assertTrue("A random diagnostic run ID is required", runId.matches(Regex("[0-9a-f]{32}")))
        fun mark(message: String) = android.util.Log.i("CoachReconnect","$runId $message port=${BuildConfig.COACH_PORT} elapsedMs=${android.os.SystemClock.elapsedRealtime()}")
        mark("TEST_ACTIVITY_RESUMED")
        // Independent of logcat: save only this synthetic run's heartbeat counters.
        // Separate atomic files avoid a reader observing partial or mixed-channel data.
        val heartbeatFiles=listOf("main", "io").associateWith { channel ->
            android.util.AtomicFile(java.io.File(context.cacheDir,"ton1094-$runId-$channel.json"))
        }
        fun saveHeartbeat(channel: String, count: Long, connection: String?=null, active: Boolean?=null) {
            val file=heartbeatFiles.getValue(channel)
            var stream: java.io.FileOutputStream?=null
            try {
                val record=org.json.JSONObject().put("runId",runId).put("channel",channel)
                    .put("count",count).put("elapsedMs",android.os.SystemClock.elapsedRealtime())
                if (connection!=null) record.put("connection",connection).put("active",active)
                stream=file.startWrite()
                stream.write(record.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Exception) {
                if (stream!=null) file.failWrite(stream)
                mark("HEARTBEAT_FILE_FAILED channel=$channel type=${error.javaClass.simpleName}")
            }
        }
        // A main-looper heartbeat distinguishes coroutine-pool stalls from whole-process suspension.
        val handler=android.os.Handler(android.os.Looper.getMainLooper())
        val heartbeatActive=java.util.concurrent.atomic.AtomicBoolean(true)
        val heartbeat=object : Runnable {
            var count=0L
            override fun run() {
                if (!heartbeatActive.get()) return
                saveHeartbeat("main",++count)
                mark("MAIN_HEARTBEAT");handler.postDelayed(this,2000)
            }
        }
        handler.post(heartbeat)
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
            var count=0L
            while(isActive) {
                saveHeartbeat("io",++count,engine.connection.value,loop.isActive)
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
            heartbeatActive.set(false)
            handler.removeCallbacks(heartbeat)
            status.cancelAndJoin();loop.cancelAndJoin();wire.close();driver.close();context.deleteDatabase(name)
            heartbeatFiles.values.forEach { it.delete() }
        }
    }
}
