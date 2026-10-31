package com.coach.store

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class PairingStoreTest {
    @Test fun expiresAtFiveMinutesAndIsSingleUseAcrossRestarts() {
        val root = Files.createTempDirectory("coach-pair-expiry-")
        var now = 1000L
        try {
            var code = ""
            LedgerStore(root) { now }.use { code = it.createPairingCode().code }
            LedgerStore(root) { now }.use { store ->
                now += 299999
                val phone = assertNotNull(store.pair(code, "Fold test"))
                assertEquals(32, java.util.Base64.getUrlDecoder().decode(phone.token).size)
                assertTrue(store.authorize(phone.deviceId, phone.token))
                assertNull(store.pair(code, "replay"))
                val expired = store.createPairingCode()
                now = expired.expiresAt
                assertNull(store.pair(expired.code, "late"))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun guessesAreBoundedAndReplacementInvalidatesOldCode() {
        val root = Files.createTempDirectory("coach-pair-guesses-")
        try { LedgerStore(root).use { store ->
            val first = store.createPairingCode()
            repeat(5) { assertNull(store.pair("invalid", "test phone")) }
            assertNull(store.pair(first.code, "test phone"))
            val fresh = store.createPairingCode()
            assertNotNull(store.pair(fresh.code, "test phone"))
        } } finally { root.toFile().deleteRecursively() }
    }

    @Test fun onlyHashesPersistAndRevocationSurvivesRestartWithoutAffectingOtherDevice() {
        val root = Files.createTempDirectory("coach-pair-hashes-")
        try {
            lateinit var phone: DeviceCredential
            lateinit var pad: DeviceCredential
            LedgerStore(root).use { store ->
                phone = assertNotNull(store.pair(store.createPairingCode().code, "test phone"))
                pad = assertNotNull(store.pair(store.createPairingCode().code, "test pad"))
                store.revokeDevice(phone.deviceId)
                assertFalse(store.authorize(phone.deviceId, phone.token))
                assertTrue(store.authorize(pad.deviceId, pad.token))
                assertNotNull(store.pairedDevices().single { it.id == phone.deviceId }.revokedAt)
            }
            LedgerStore(root).use { store ->
                assertFalse(store.authorize(phone.deviceId, phone.token))
                assertTrue(store.authorize(pad.deviceId, pad.token))
            }
            DriverManager.getConnection("jdbc:sqlite:$root/coach.sqlite").use { db ->
                db.createStatement().use { statement -> statement.executeQuery("SELECT token_sha256 FROM devices").use { rows ->
                    val hashes = mutableListOf<String>()
                    while (rows.next()) hashes.add(rows.getString(1))
                    assertEquals(setOf(sha256(phone.token.toByteArray()), sha256(pad.token.toByteArray())), hashes.toSet())
                } }
                db.createStatement().use { statement -> statement.executeQuery("SELECT count(*) FROM ai_calls").use { rows ->
                    rows.next(); assertEquals(0, rows.getInt(1))
                } }
            }
            for (file in Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList() }) {
                val content = Files.readAllBytes(file).toString(Charsets.ISO_8859_1)
                assertFalse(content.contains(phone.token), "Plain token persisted")
                assertFalse(content.contains(pad.token), "Plain token persisted")
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
