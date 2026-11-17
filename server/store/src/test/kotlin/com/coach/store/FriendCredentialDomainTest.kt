package com.coach.store

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.*

class FriendCredentialDomainTest {
    private fun <T> database(root: Path, body: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${root.resolve("coach.sqlite")}").use(body)

    private fun execute(db: Connection, sql: String, vararg values: Any?) {
        db.prepareStatement(sql).use { statement ->
            values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun scalar(db: Connection, sql: String, vararg values: Any?): String? =
        db.prepareStatement(sql).use { statement ->
            values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    // The pre-friend binary authorizes by raw SHA-256 and redeems the singleton
    // pairing hash without consulting friend_name. These are its acceptance SQLs,
    // not a claim that an actual old JVM was run by this unit test.
    private fun oldAuthorizes(db: Connection, credential: DeviceCredential): Boolean =
        scalar(db, "SELECT device_id FROM devices WHERE device_id=? AND token_sha256=? AND revoked_at IS NULL",
            credential.deviceId, sha256(credential.token.toByteArray())) != null

    private fun oldCanRedeem(db: Connection, code: String): Boolean =
        scalar(db, "SELECT singleton FROM pairing_code WHERE code_sha256=? AND expires_at>? AND attempts<5",
            sha256(code.toByteArray()), 1000L) != null

    private fun fixture(body: (Path) -> Unit) {
        val root = Files.createTempDirectory("coach-friend-domain-")
        try { body(root) } finally { root.toFile().deleteRecursively() }
    }

    @Test fun friendCodeAndTokenRejectOldAcceptanceButSurviveCandidateRestart() = fixture { root ->
        lateinit var friend: DeviceCredential
        LedgerStore(root) { 1000L }.use { store ->
            val code = store.createPairingCode("test-friend").code
            database(root) { assertFalse(oldCanRedeem(it, code)) }
            friend = assertNotNull(store.pair(code, "test device", friendsOnly = true))
            assertEquals("friend", friend.role)
            assertTrue(store.authorize(friend.deviceId, friend.token))
            database(root) { assertFalse(oldAuthorizes(it, friend)) }
            assertNull(store.pair(code, "replay", friendsOnly = true))
        }
        LedgerStore(root) { 2000L }.use { store ->
            assertTrue(store.authorize(friend.deviceId, friend.token))
            assertEquals("test-friend", store.friendName(friend.deviceId))
            store.revokeFriend("test-friend")
            assertFalse(store.authorize(friend.deviceId, friend.token))
        }
    }

    @Test fun ownerPairingAndEnrollmentRemainCompatibleWithOldHashes() = fixture { root ->
        LedgerStore(root) { 1000L }.use { store ->
            val code = store.createPairingCode().code
            database(root) { assertTrue(oldCanRedeem(it, code)) }
            val owner = assertNotNull(store.pair(code, "owner"))
            assertEquals("owner", owner.role)
            store.enroll("existing-owner", "o".repeat(43))
            assertTrue(store.authorize(owner.deviceId, owner.token))
            assertTrue(store.authorize("existing-owner", "o".repeat(43)))
            database(root) {
                assertTrue(oldAuthorizes(it, owner))
                assertTrue(oldAuthorizes(it, DeviceCredential("existing-owner", "o".repeat(43))))
            }
        }
    }

    @Test fun oldOwnerCodeUpsertCannotTurnStaleFriendNameIntoFriendAccess() = fixture { root ->
        LedgerStore(root) { 1000L }.use { store ->
            store.createPairingCode("test-friend")
            database(root) { db ->
                // Exact old upsert: neither friend_name nor the additive version is cleared.
                execute(db, "INSERT INTO pairing_code(singleton,code_sha256,expires_at,attempts) VALUES(1,?,?,0) ON CONFLICT(singleton) DO UPDATE SET code_sha256=excluded.code_sha256,expires_at=excluded.expires_at,attempts=0",
                    sha256("123456".toByteArray()), 301000L)
                assertEquals("test-friend", scalar(db, "SELECT friend_name FROM pairing_code"))
            }
            assertNull(store.pair("123456", "must fail", friendsOnly = true))
            assertNull(store.pair("123456", "must also fail on normal listener"))
            val replacement = store.createPairingCode()
            assertNull(store.pair(replacement.code, "public owner", friendsOnly = true))
            assertEquals("owner", assertNotNull(store.pair(replacement.code, "owner")).role)
        }
    }

    @Test fun neitherDomainFallsBackToTheOtherHash() = fixture { root ->
        LedgerStore(root) { 1000L }.use { store ->
            val friend = assertNotNull(store.pair(store.createPairingCode("test-friend").code, "friend"))
            val owner = assertNotNull(store.pair(store.createPairingCode().code, "owner"))
            database(root) { db ->
                execute(db, "UPDATE devices SET token_sha256=? WHERE device_id=?", sha256(friend.token.toByteArray()), friend.deviceId)
                execute(db, "UPDATE devices SET token_sha256=? WHERE device_id=?", sha256(("coach-friend-v1:" + owner.token).toByteArray()), owner.deviceId)
            }
            assertFalse(store.authorize(friend.deviceId, friend.token))
            assertFalse(store.authorize(owner.deviceId, owner.token))
        }
    }

    @Test fun upgradeRevokesLegacyFriendsAndPendingFriendCodeWithoutChangingOwners() = fixture { root ->
        lateinit var owner: DeviceCredential
        lateinit var legacy: DeviceCredential
        LedgerStore(root) { 1000L }.use { store ->
            owner = assertNotNull(store.pair(store.createPairingCode().code, "owner"))
            legacy = assertNotNull(store.pair(store.createPairingCode("legacy").code, "friend"))
            store.createPairingCode("legacy")
        }
        database(root) { db ->
            // Recreate the actual earlier schema and raw friend hashes.
            execute(db, "ALTER TABLE devices DROP COLUMN friend_hash_version")
            execute(db, "ALTER TABLE pairing_code DROP COLUMN friend_hash_version")
            execute(db, "UPDATE devices SET token_sha256=? WHERE device_id=?", sha256(legacy.token.toByteArray()), legacy.deviceId)
            execute(db, "UPDATE pairing_code SET code_sha256=?", sha256("123456".toByteArray()))
        }
        LedgerStore(root) { 2000L }.use { store ->
            assertTrue(store.authorize(owner.deviceId, owner.token))
            assertFalse(store.authorize(legacy.deviceId, legacy.token))
            assertEquals(2000L, store.pairedDevices().single { it.id == legacy.deviceId }.revokedAt)
            assertNull(store.pair("123456", "legacy code"))
            database(root) { assertFalse(oldAuthorizes(it, legacy)); assertFalse(oldCanRedeem(it, "123456")) }
        }
        LedgerStore(root) { 3000L }.use { store ->
            assertEquals(2000L, store.pairedDevices().single { it.id == legacy.deviceId }.revokedAt)
            assertTrue(store.authorize(owner.deviceId, owner.token))
        }
    }

    @Test fun migrationPreservesOwnerPendingCodeAndAlreadyRevokedFriendTime() = fixture { root ->
        lateinit var friend: DeviceCredential
        var ownerCode = ""
        LedgerStore(root) { 1000L }.use { store ->
            friend = assertNotNull(store.pair(store.createPairingCode("legacy").code, "friend"))
            store.revokeFriend("legacy")
            ownerCode = store.createPairingCode().code
        }
        database(root) { db -> execute(db, "UPDATE devices SET friend_hash_version=0 WHERE device_id=?", friend.deviceId) }
        LedgerStore(root) { 2000L }.use { store ->
            assertEquals(1000L, store.pairedDevices().single { it.id == friend.deviceId }.revokedAt)
            assertEquals("owner", assertNotNull(store.pair(ownerCode, "owner")).role)
        }
    }

    @Test fun newFriendCodeSurvivesRestartAndStillHasFiveGuessLimit() = fixture { root ->
        var code = ""
        LedgerStore(root) { 1000L }.use { code = it.createPairingCode("test-friend").code }
        LedgerStore(root) { 2000L }.use { store ->
            repeat(5) { assertNull(store.pair("invalid", "friend", friendsOnly = true)) }
            assertNull(store.pair(code, "friend", friendsOnly = true))
            code = store.createPairingCode("test-friend").code
        }
        LedgerStore(root) { 3000L }.use { store ->
            assertNotNull(store.pair(code, "friend", friendsOnly = true))
            val expiring = store.createPairingCode("test-friend")
            // Exact boundary checked by a separate store clock after reopening.
            code = expiring.code
        }
        LedgerStore(root) { 303000L }.use { assertNull(it.pair(code, "late", friendsOnly = true)) }
    }
}
