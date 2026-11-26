package com.coach.desktop

import com.coach.client.PairedCredentials
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Packaged friend URL versus the saved friend address.
 * Placeholder hosts only; local book bytes must survive a credential reset.
 */
class FriendUrlMovedTest {
    private val oldUrl = "https://old.example:444"
    private val newUrl = "https://new.example:444"
    private val friend = PairedCredentials("friend-pc", "a".repeat(43), "friend")
    private val owner = PairedCredentials("owner-pc", "b".repeat(43), "owner")

    @Test fun sameAddressKeepsTheFriendSession() {
        val home = home()
        val marker = plant(home)
        val settings = settings(home, newUrl)
        val store = DesktopCredentials(settings.clientDirectory)
        store.save(newUrl, friend)
        store.saveAddress(newUrl)

        val opened = DesktopPairing(settings).loadForStartup()

        assertNull(opened.notice)
        assertEquals(friend.deviceId, opened.identity!!.credentials.deviceId)
        assertEquals("friend", opened.identity!!.credentials.role)
        assertEquals(newUrl, store.savedAddress())
        assertEquals(friend.deviceId, store.load(newUrl)!!.deviceId)
        assertUntouched(marker)
    }

    @Test fun differentAddressDropsOnlyTheFriendCredentialAndKeepsLocalBooks() {
        val home = home()
        val marker = plant(home)
        val settings = settings(home, newUrl)
        val store = DesktopCredentials(settings.clientDirectory)
        store.save(oldUrl, friend)
        store.saveAddress(oldUrl)

        val opened = DesktopPairing(settings).loadForStartup()

        assertEquals("服务地址换了，请重新登录一次", opened.notice)
        assertNull(opened.identity)
        assertNull(store.load(oldUrl))
        assertNull(store.load(newUrl))
        assertFalse(Files.exists(store.file))
        assertEquals(newUrl, store.savedAddress())
        assertFalse(store.wasRevoked())
        assertUntouched(marker)

        val again = DesktopPairing(settings).loadForStartup()
        assertNull(again.notice)
        assertNull(again.identity)
        assertUntouched(marker)

        val rebound = PairedCredentials("friend-pc", "c".repeat(43), "friend")
        store.save(newUrl, rebound)
        val afterLogin = DesktopPairing(settings).loadForStartup()
        assertNull(afterLogin.notice)
        assertEquals(rebound.deviceId, afterLogin.identity!!.credentials.deviceId)
        assertEquals(newUrl, afterLogin.identity!!.serverUrl)
        assertUntouched(marker)
    }

    @Test fun missingSavedAddressChangesNothing() {
        val home = home()
        val marker = plant(home)
        val settings = settings(home, newUrl)
        val store = DesktopCredentials(settings.clientDirectory)
        store.save(newUrl, friend)

        val opened = DesktopPairing(settings).loadForStartup()

        assertNull(opened.notice)
        assertEquals(friend.deviceId, opened.identity!!.credentials.deviceId)
        assertNull(store.savedAddress())
        assertEquals(friend.deviceId, store.load(newUrl)!!.deviceId)
        assertUntouched(marker)

        val empty = home()
        val emptySettings = settings(empty, newUrl)
        val emptyStore = DesktopCredentials(emptySettings.clientDirectory)
        val fresh = DesktopPairing(emptySettings).loadForStartup()
        assertNull(fresh.notice)
        assertNull(fresh.identity)
        assertNull(emptyStore.savedAddress())
        assertFalse(Files.exists(emptyStore.file))
        assertFalse(Files.exists(emptySettings.clientDirectory.resolve("pairing")))
    }

    @Test fun ownerConnectionStaysWhenTheFriendUrlMoves() {
        val home = home()
        val marker = plant(home)
        val settings = settings(home, newUrl)
        val store = DesktopCredentials(settings.clientDirectory)
        store.save(oldUrl, owner)
        store.saveAddress(oldUrl)

        val opened = DesktopPairing(settings).loadForStartup()

        assertNull(opened.notice)
        assertEquals(owner.deviceId, opened.identity!!.credentials.deviceId)
        assertEquals("owner", opened.identity!!.credentials.role)
        assertEquals(oldUrl, opened.identity!!.serverUrl)
        assertEquals(oldUrl, store.savedAddress())
        assertEquals(owner.deviceId, store.load(oldUrl)!!.deviceId)
        assertUntouched(marker)
    }

    private fun home() = Files.createTempDirectory("coach-friend-url-").toRealPath()

    private fun settings(home: Path, packaged: String?) = DesktopConnectionSettings(
        home,
        { null },
        mac = false,
        property = { key ->
            when (key) {
                "jpackage.app-path" -> home.resolve("LearningCoach.exe").toString()
                "coach.friendUrl" -> packaged
                else -> null
            }
        },
    )

    private fun plant(home: Path): Pair<Path, ByteArray> {
        val book = home.resolve("Coach/books/note.txt")
        val beside = home.resolve("Coach/client/local-progress.txt")
        val bytes = "page=12\nnote=keep\n".toByteArray(Charsets.UTF_8)
        Files.createDirectories(book.parent)
        Files.createDirectories(beside.parent)
        Files.write(book, bytes)
        Files.write(beside, bytes)
        return book to bytes
    }

    private fun assertUntouched(marker: Pair<Path, ByteArray>) {
        assertContentEquals(marker.second, Files.readAllBytes(marker.first))
        assertContentEquals(marker.second, Files.readAllBytes(marker.first.parent.parent.resolve("client/local-progress.txt")))
        assertTrue(Files.exists(marker.first))
    }
}
