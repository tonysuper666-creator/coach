package com.coach.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coach.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.coach.client.db.ClientDatabase
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class PairingKeystoreSmoke {
    @Test fun pairPersistAndReconnect(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val base = "http://${BuildConfig.COACH_TAIL_HOST}:${args.getString("coachPort",BuildConfig.COACH_PORT.toString())}"
        val store = KeystoreCredentials(context,base,"pairing-smoke")
        if (args.getString("phase","pair") == "pair") {
            val client = PairingClient(base)
            try { store.save(client.pair(args.getString("pairingCode", ""),"synthetic emulator")) } finally { client.close() }
        }
        val credentials = assertNotNullValue(KeystoreCredentials(context,base,"pairing-smoke").load())
        val transport = HttpLedgerTransport(base,credentials.deviceId,credentials.token)
        try {
            if (args.getString("phase") == "revoked") {
                try { transport.hello(null); fail("Revoked device was authorized") }
                catch (_: DeviceAuthorizationException) { /* expected */ }
                android.util.Log.i("CoachPairing","revokedHTTP=401")
                store.clear()
                return@runBlocking
            }
            assertTrue(transport.hello(null).epoch.isNotEmpty())
            // The production app starts both sockets together. On a two-core device,
            // WebSocket nonce initialization must not exhaust the CPU dispatcher.
            val driver=AndroidSqliteDriver(ClientDatabase.Schema,context,"dual-connection-smoke.db")
            val twinTransport=HttpLedgerTransport(base,credentials.deviceId,credentials.token)
            val model=TutorModel(System::currentTimeMillis)
            val tutor=TutorClient(base,credentials.deviceId,credentials.token,model)
            val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val sync=SyncEngine(ClientDatabase(driver),credentials.deviceId,twinTransport,listOf(BuildConfig.COACH_COURSE),
                { UUID.randomUUID().toString() }, { bytes -> java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } })
            val syncing=sync.start(owner);val tutoring=tutor.start(owner)
            try {
                withTimeout(10000) { sync.connection.first { it=="online" };model.state.first { it.connected } }
                android.util.Log.i("CoachPairing","dualConnections=online cores=${Runtime.getRuntime().availableProcessors()}")
            } finally {
                syncing.cancelAndJoin();tutoring.cancelAndJoin();tutor.close();twinTransport.close();owner.cancel();driver.close()
            }
            val seen=AtomicBoolean(false)
            val socket=launch { transport.watch { seen.set(true) } }
            try { withTimeout(10000) { while(!seen.get()) delay(50) } } finally { socket.cancelAndJoin() }
        } finally { transport.close() }
        val persisted=context.getSharedPreferences("pairing-smoke",0).all.toString()
        assertFalse(persisted.contains(credentials.token))
        assertFalse(persisted.contains(credentials.deviceId))
        assertFalse(persisted.contains("synthetic emulator"))
        android.util.Log.i("CoachPairing","phase=${args.getString("phase")} HTTP=200 WS=authenticated Keystore=decrypted plaintextInPrefs=false")
    }

    @Test fun sharedClientsRespectAndroidCleartextAllowlist() {
        assertThrows(SecurityException::class.java) { TutorClient("http://10.0.2.2:18791","test","x".repeat(43),TutorModel(System::currentTimeMillis)) }
        assertThrows(SecurityException::class.java) { PairingClient("http://10.0.2.2:18791") }
        assertThrows(SecurityException::class.java) { HttpLedgerTransport("http://10.0.2.2:18791","test","x".repeat(43)) }
    }

    private fun <T: Any> assertNotNullValue(value: T?): T { assertNotNull(value); return value!! }
}
