package com.coach.store

import java.nio.file.Files
import kotlin.test.*

class FriendInvitationTest {
    @Test fun pendingInvitesLeaveRoomForLegacyPairing() {
        val root = Files.createTempDirectory("friend-invite-capacity-")
        try {
            LedgerStore(root).use { store ->
                val legacy = store.createPairingCode("旧流程")
                val invites = (1..9).map { assertNotNull(store.createFriendInvite("friend-$it")) }
                assertEquals(10, (invites + legacy).map { it.code.first() }.distinct().size)
                assertNull(store.createFriendInvite("extra"))
                assertNotNull(store.pair(legacy.code, "legacy", friendsOnly = true))
                val owner = store.createPairingCode()
                assertNotNull(store.pair(owner.code, "owner"))
                store.revokeFriend("friend-1")
                assertNotNull(store.createFriendInvite("replacement"))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun independentInvitationsSurviveRestartAndAreConsumedOnlyOnce() {
        val root = Files.createTempDirectory("friend-invite-")
        var now = 1000L
        try {
            val names = listOf("朋友A", "朋友B", "朋友C", "朋友D", "朋友E")
            val invites = LedgerStore(root) { now }.use { store ->
                names.map { assertNotNull(store.createFriendInvite(it)) }
            }
            now += 71 * 3_600_000L
            LedgerStore(root) { now }.use { store ->
                val legacy = store.createPairingCode("旧流程")
                val target = invites[0].code
                val wrong = target.dropLast(1) + if (target.last() == '9') '0' else '9'
                repeat(5) { assertNull(store.pair(wrong, "PC", friendsOnly = true)) }
                assertNull(store.pair(target, "PC", friendsOnly = true))
                val b = assertNotNull(store.pair(invites[1].code, "PC", friendsOnly = true))
                assertEquals(names[1], store.friendName(b.deviceId))
                assertEquals("friend", b.role)
                assertNull(store.pair(invites[1].code, "another PC"))
                store.revokeFriend(names[2])
                assertNull(store.pair(invites[2].code, "PC"))
                val d = invites[3].code
                val wrongD = d.dropLast(1) + if (d.last() == '0') '1' else '0'
                repeat(4) { assertNull(store.pair(wrongD, "PC", friendsOnly = true)) }
                assertNotNull(store.pair(d, "PC", friendsOnly = true))
                val oldDevice = assertNotNull(store.pair(legacy.code, "PC", friendsOnly = true))
                assertEquals("旧流程", store.friendName(oldDevice.deviceId))
                now = invites[4].expiresAt
                assertEquals(1000L + 72 * 3_600_000L, now)
                assertNull(store.pair(invites[4].code, "PC"))
                store.revokeFriend(names[1])
                assertFalse(store.authorize(b.deviceId, b.token))
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
