package com.coach.android

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.coach.client.PairedCredentials
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

/** Fixtures are locally built APKs, written with run-as into this isolated package's private files. */
class AppUpdateSmoke {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun installerIgnoresThirdPartyDefaultsAndFailsClosedWithoutTrustedHandler() {
        fun handler(name: String, system: Boolean = false, enabled: Boolean = true, exported: Boolean = true) = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = name; this.name = "$name.Installer"; this.enabled = enabled; this.exported = exported
                applicationInfo = ApplicationInfo().apply {
                    packageName = name; this.enabled = true
                    flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
                }
            }
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://synthetic/verified.apk"), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val untrusted = listOf(handler("third.party.default"), handler("system.viewer", system = true),
            handler("system.disabled", system = true, enabled = false), handler("system.private", system = true, exported = false))
        // Even a positive permission answer cannot turn a non-system APK handler into a trusted installer.
        val resolved = trustedInstaller(intent, untrusted + handler("system.installer", system = true)) { it != "system.viewer" }
        assertEquals("system.installer", resolved.component?.packageName)
        assertEquals("system.installer.Installer", resolved.component?.className)
        assertEquals(intent.data, resolved.data)
        assertEquals(intent.type, resolved.type)
        assertTrue(resolved.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNull("resolver input must not become an implicit fallback", intent.component)
        for (candidates in listOf(untrusted, emptyList())) {
            try { trustedInstaller(intent, candidates) { it != "system.viewer" }; fail("untrusted installer accepted") }
            catch (expected: UpdateRejected) { assertTrue(expected.message!!.contains("可信")) }
        }
    }
    @Test fun deviceInstallerIsExplicitAndPrivileged() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://synthetic/verified.apk"), "application/vnd.android.package-archive")
        val pm = context.packageManager
        val candidates = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(
            (PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY).toLong()))
        val explicit = trustedInstaller(intent, candidates) {
            pm.checkPermission(android.Manifest.permission.INSTALL_PACKAGES, it) == PackageManager.PERMISSION_GRANTED
        }
        val component = requireNotNull(explicit.component)
        val info = pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        assertTrue(info.exported && info.enabled && info.applicationInfo.enabled)
        assertTrue(info.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
        assertEquals(PackageManager.PERMISSION_GRANTED, pm.checkPermission(android.Manifest.permission.INSTALL_PACKAGES, component.packageName))
    }
    private fun release(name: String): Pair<File, AppRelease> {
        val directory = File(context.filesDir, "update-fixtures")
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
