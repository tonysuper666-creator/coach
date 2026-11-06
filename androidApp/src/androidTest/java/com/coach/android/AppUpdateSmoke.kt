package com.coach.android

import androidx.test.platform.app.InstrumentationRegistry
import com.coach.client.PairedCredentials
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

/** Fixtures are locally built APKs, pushed into this isolated package's external files directory. */
class AppUpdateSmoke {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun release(name: String): Pair<File, AppRelease> {
        val directory = File(context.getExternalFilesDir(null), "update-fixtures")
        val metadata = JSONObject(File(directory, "$name.json").readText())
        return File(directory, "$name.apk") to AppRelease(metadata.getLong("versionCode"), metadata.getString("versionName"), metadata.getString("sha256"), metadata.getLong("size"))
    }
    @Test fun acceptsValidUpdateAndRejectsDigestAndSignerMismatch() {
        val (valid, metadata) = release("valid")
        verifyUpdate(context, valid, metadata)
        try { verifyUpdate(context, valid, metadata.copy(sha256 = "0".repeat(64))); fail("digest mismatch accepted") }
        catch (expected: UpdateRejected) { assertTrue(expected.message!!.contains("校验失败")) }
        val (wrongSigner, badMetadata) = release("wrong-signer")
        try { verifyUpdate(context, wrongSigner, badMetadata); fail("wrong signer accepted") }
        catch (expected: UpdateRejected) { assertTrue(expected.message!!.contains("签名")) }
        try { verifyUpdate(context, valid, metadata.copy(code = metadata.code + 1)); fail("version mismatch accepted") }
        catch (expected: UpdateRejected) { assertTrue(expected.message!!.contains("版本")) }
    }
    @Test fun seedUpgradeSentinel() {
        val args = InstrumentationRegistry.getArguments()
        args.getString("coachUrl")?.let { url ->
            KeystoreCredentials(context, url).save(PairedCredentials(args.getString("deviceId")!!, args.getString("token")!!))
            context.getSharedPreferences("upgrade-check", 0).edit()
                .putString("url", url).putString("deviceId", args.getString("deviceId"))
                .putString("tokenHash", sha(args.getString("token")!!)).commit()
        }
        KeystoreCredentials(context, "http://synthetic.invalid", "update-sentinel").save(PairedCredentials("synthetic-upgrade-device", "synthetic-upgrade-token"))
        context.openOrCreateDatabase("upgrade-sentinel.db", 0, null).use {
            it.execSQL("CREATE TABLE IF NOT EXISTS learning (answer TEXT NOT NULL)")
            it.execSQL("DELETE FROM learning")
            it.execSQL("INSERT INTO learning VALUES ('synthetic retained answer')")
        }
    }
    @Test fun upgradeRetainsKeystorePairingAndLearningDatabase() {
        val saved = context.getSharedPreferences("upgrade-check", 0)
        saved.getString("url", null)?.let { url ->
            val restored = KeystoreCredentials(context, url).load() ?: error("Pairing lost after upgrade")
            assertEquals(saved.getString("deviceId", null), restored.deviceId)
            assertEquals(saved.getString("tokenHash", null), sha(restored.token))
        }
        val snapshot = JSONObject(saved.getString("replica", null) ?: error("Snapshot actual learning replica before upgrading"))
        assertTrue(snapshot.length() > 0)
        SQLiteDatabase.openDatabase(context.getDatabasePath("coach-client.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            snapshot.keys().forEach { seq ->
                db.rawQuery("SELECT event_json FROM replica WHERE seq=?", arrayOf(seq)).use { rows ->
                    assertTrue("Learning event missing after update", rows.moveToFirst())
                    assertEquals(snapshot.getString(seq), sha(rows.getString(0)))
                }
            }
        }
        assertEquals("synthetic-upgrade-device", KeystoreCredentials(context, "http://synthetic.invalid", "update-sentinel").load()?.deviceId)
        context.openOrCreateDatabase("upgrade-sentinel.db", 0, null).use { db ->
            db.rawQuery("SELECT answer FROM learning", null).use { rows ->
                assertTrue(rows.moveToFirst()); assertEquals("synthetic retained answer", rows.getString(0))
            }
        }
    }
    @Test fun snapshotLearningReplica() {
        val snapshot = JSONObject()
        SQLiteDatabase.openDatabase(context.getDatabasePath("coach-client.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT seq,event_json FROM replica ORDER BY seq", null).use { rows ->
                while (rows.moveToNext()) snapshot.put(rows.getLong(0).toString(), sha(rows.getString(1)))
            }
        }
        assertTrue("Sync real learning events before upgrading", snapshot.length() > 0)
        context.getSharedPreferences("upgrade-check", 0).edit().putString("replica", snapshot.toString()).commit()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "Learning replica snapshot: ${snapshot.length()} events; contents withheld\n")
        })
    }
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
