package com.coach.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.security.MessageDigest
import java.util.UUID

/** Run through check-android.py with COACH_VERIFY_LEDGER_WAKE=1; it owns the server STOP/CONT. */
@RunWith(AndroidJUnit4::class)
class LedgerReconnectSmoke {
    @Test fun reconnectAfterServerSuspension(): Unit = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="synthetic-reconnect.sqlite"
        context.deleteDatabase(name)
        val driver=AndroidSqliteDriver(ClientDatabase.Schema,context,name)
        val token=InstrumentationRegistry.getArguments().getString("coachToken","")
        assertTrue("Isolated server token is required",token.length>=32)
        val transport=HttpLedgerTransport("http://${BuildConfig.COACH_TAIL_HOST}:18791","desktop",token)
        val engine=SyncEngine(ClientDatabase(driver),"desktop",transport,listOf("synthetic"),{UUID.randomUUID().toString()}, { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } })
        val loop=engine.start(this)
        try {
            withTimeout(120000) {
                while(engine.connection.value!="online") delay(50)
                val epoch=engine.epoch
                val revision=engine.revision.value
                android.util.Log.i("CoachReconnect","READY")
                while(engine.connection.value!="offline") delay(50)
                android.util.Log.i("CoachReconnect","OFFLINE")
                while(engine.connection.value!="online" || engine.revision.value<=revision) delay(50)
                assertEquals(epoch,engine.epoch)
                android.util.Log.i("CoachReconnect","RECONNECTED epochUnchanged=true")
            }
        } finally {
            loop.cancelAndJoin();transport.close();driver.close();context.deleteDatabase(name)
        }
    }
}
