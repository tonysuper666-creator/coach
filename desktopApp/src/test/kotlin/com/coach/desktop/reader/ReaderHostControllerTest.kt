package com.coach.desktop.reader

import com.coach.client.PairedCredentials
import com.coach.client.ReaderReply
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.*

class ReaderHostControllerTest {
    private fun fake() = FakeReaderService(Triple("remote opaque/id", "v2", 3)).apply {
        bodyText = { _, p -> "Alpha beta on page $p\nSecond sentence" }
    }

    @Test fun opensTheCatalogIdentityAndActuallyLoadsTheRequestedPage() = runBlocking {
        val fake = fake()
        val host = ReaderHostController(fake)
        try {
            host.open(ReaderOpenRequest("remote opaque/id", "v2", 3, "Second sentence"))
            assertNull(host.openingFailure)
            val view = assertIs<PageState.Ready>(host.remote.page).view
            assertEquals("remote opaque/id", view.document)
            assertEquals(3, view.pdfPage)
            assertEquals("Alpha beta on page 3\nSecond sentence", view.text)
            assertEquals("Second sentence", host.session.state.flashAnchor)
        } finally { host.close() }
    }

    @Test fun unknownIdsWrongVersionsAndOutOfRangePagesNeverReachStateOrPage() = runBlocking {
        val fake = fake()
        var states = 0
        val source = object : ReaderSource by fake {
            override suspend fun state(document: String): ReaderReply { states++; return fake.state(document) }
        }
        val host = ReaderHostController(source)
        try {
            host.open(ReaderOpenRequest("local-sha-is-not-a-remote-id"))
            assertIs<ReaderFailure.BadResponse>(host.openingFailure)
            host.open(ReaderOpenRequest("remote opaque/id", "v1", 1))
            assertEquals(ReaderFailure.VersionChanged, host.openingFailure)
            for (page in listOf(0, 4)) {
                host.open(ReaderOpenRequest("remote opaque/id", "v2", page))
                assertEquals(ReaderFailure.MissingPage(page), host.openingFailure)
            }
            assertEquals(0, states)
            assertTrue(fake.pageCalls.isEmpty(), "must reject, never clamp or guess")
        } finally { host.close() }
    }

    @Test fun closeRejectsALateSuccessAndKeepsTheUnacknowledgedDraft() = runBlocking {
        val fake = fake()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = object : ReaderSource by fake {
            override suspend fun saveNote(document: String, body: JsonObject): ReaderReply = withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                fake.saveNote(document, body)
            }
        }
        val dir = Files.createTempDirectory("reader-host-close-")
        val store = FileNoteDraftStore(dir, "test-owner:one")
        var closes = 0
        val host = ReaderHostController(source, store) { closes++ }
        host.open()
        val session = host.session
        session.beginNote(); session.onKey(KeyInput(ReaderKey.Enter))
        session.editNote("unsent draft")
        val noteId = session.noteDraft!!.id
        val saving = async { session.commitNote() }
        entered.await()
        host.close(); host.close()
        release.complete(Unit)
        saving.join()
        assertTrue(saving.isCancelled, "old transport success cannot become a window ack")
        assertFalse(host.remote.noteSave is NoteSaveState.Saved)
        val kept = store.listFor("remote opaque/id", "v2", 1).single()
        assertEquals(noteId, kept.noteId)
        assertEquals("unsent draft", kept.note)
        assertEquals(0L, kept.baseRevision)
        assertEquals(1, closes)
        // A server may have committed before cancellation. The client never invents a rollback.
        assertEquals(1, fake.noteCalls.size)
    }

    @Test fun unauthorizedSaveKeepsTheEditorAndRetiresAllSubsequentNetworkWrites() = runBlocking {
        val fake = fake().apply { noteStatus = 401 }
        val host = ReaderHostController(fake)
        try {
            host.open()
            val s = host.session
            s.beginNote(); s.onKey(KeyInput(ReaderKey.Enter)); s.editNote("keep after 401")
            val id = s.noteDraft!!.id
            s.commitNote()
            assertFalse(host.authorized)
            assertEquals(NoteSaveState.Failed(ReaderFailure.Unauthorized), host.remote.noteSave)
            assertEquals("keep after 401", s.noteText)
            assertEquals(id, s.noteDraft!!.id)
            s.commitNote()
            assertEquals(1, fake.noteCalls.size, "no retry after pairing has been retired")
            host.revoke()
            assertEquals("keep after 401", s.noteText)
        } finally { host.close() }
    }

    @Test fun retiredWindowCannotPublishItsPageIntoANewOwnerWindow() = runBlocking {
        val fake = fake()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = ReaderHostController(object : ReaderSource by fake {
            override suspend fun page(document: String, pdfPage: Int): ReaderReply = withContext(NonCancellable) {
                entered.complete(Unit); release.await(); fake.page(document, pdfPage)
            }
        })
        val opening = async { old.open(ReaderOpenRequest("remote opaque/id", "v2", 1)) }
        entered.await()
        old.close()
        val fresh = ReaderHostController(fake)
        try {
            fresh.open(ReaderOpenRequest("remote opaque/id", "v2", 3))
            release.complete(Unit); opening.join()
            assertTrue(opening.isCancelled)
            assertFalse(old.remote.page is PageState.Ready)
            assertEquals(3, assertIs<PageState.Ready>(fresh.remote.page).view.pdfPage)
        } finally { fresh.close(); release.complete(Unit) }
    }

    @Test fun pairedHostUsesTheActualConnectionButNeverInventsADurableLearnerOwner() = runBlocking {
        val fake = FakeReaderService(Triple("a", "va1", 2))
        val lb = ReaderHttpSourceTest.Loopback(fake)
        val home = Files.createTempDirectory("paired-reader-home-")
        val settings = DesktopConnectionSettings(home, environment = { if (it == "COACH_URL") lb.base else null }, mac = false, property = { null })
        val identity = DesktopIdentity(lb.base, PairedCredentials("dev1", "synthetic-token"))
        val host = pairedReaderHost(settings, identity, "c1")
        try {
            host.open(ReaderOpenRequest("a", "va1", 2))
            assertIs<PageState.Ready>(host.remote.page)
            assertTrue(lb.seen.isNotEmpty())
            assertTrue(lb.seen.all { it.auth == "Bearer synthetic-token" && it.device == "dev1" })
            assertNull(host.session.drafts, "pairing/device identity is not a verified learner owner")
            assertFalse(Files.exists(home.resolve("Coach/client/reader-drafts")))
        } finally { host.close(); lb.stop() }
    }
}
