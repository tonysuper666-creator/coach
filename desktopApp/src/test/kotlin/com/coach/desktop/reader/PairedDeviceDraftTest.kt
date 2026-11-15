package com.coach.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
import com.coach.desktop.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class PairedDeviceDraftTest {
    private class Rig(val host: ReaderHostController) : AutoCloseable {
        val session = host.session
        private val scene = ImageComposeScene(1200, 950, Density(1f)) { ReaderHostContent(host, ReaderOpenRequest("same-doc", pdfPage = 1)) }
        private var time = 0L
        init { until("real PDF page") { host.remote.page is PageState.Ready }; session.focusPane(ReaderPane.Body); frames() }
        fun frames() { repeat(4) { time += 16_000_000; scene.render(time).close(); Thread.sleep(3) } }
        fun until(label: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!condition()) { assertTrue(System.nanoTime() < deadline, "timeout: $label, ${host.openingFailure}"); frames() }
        }
        fun node(tag: String): SemanticsNode? {
            fun find(n: SemanticsNode): SemanticsNode? = if (n.config.getOrNull(SemanticsProperties.TestTag) == tag) n else n.children.firstNotNullOfOrNull(::find)
            return scene.semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode) }
        }
        fun click(tag: String) {
            val action = assertNotNull(assertNotNull(node(tag), tag).config.getOrNull(SemanticsActions.OnClick)?.action)
            assertTrue(action()); frames()
        }
        fun key(key: Key) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)); scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp)); frames()
        }
        fun type(text: String) {
            val action = assertNotNull(assertNotNull(node("note-input")).config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action)
            assertTrue(action(AnnotatedString(text))); frames()
        }
        fun start(text: String) { key(Key.N); key(Key.Enter); type(text) }
        fun save() { click("reader-note-save") }
        override fun close() { scene.close(); host.close() }
    }
    private fun persisted(host: ReaderHostController): NoteDraftStore = host.session.drafts ?: run {
        host.close(); fail("registered pairing must receive a paired-device draft store")
    }
    private fun fresh(settings: DesktopConnectionSettings, course: String = "C1") = pairedReaderHost(settings, assertNotNull(DesktopPairing(settings).load()), course)

    @Test fun real409ReopensTheSameDraftInANewControllerWithoutAutomaticPost() {
        PairedDeviceDraftFixture("conflict-reopen").use { f ->
            val settings = f.settings(); val a = f.pair(settings, "A")
            val b = f.pair(f.settings(f.root.resolve("rival-home")), "Rival")
            val host = fresh(settings); persisted(host)
            var saved: StoredDraft? = null
            Rig(host).use { r ->
                r.start("Before conflict"); r.save()
                r.until("200") { host.remote.noteSave is NoteSaveState.Saved }
                val current = r.session.noteDraft!!
                f.updateFromOtherDevice(b, current, current.baseRevision)
                r.type(" continued after rival"); r.save()
                r.until("409") { host.remote.noteSave is NoteSaveState.Conflict }
                saved = r.session.localDrafts().single()
                assertEquals(current.id, saved!!.noteId); assertEquals(current.baseRevision, saved!!.baseRevision)
                assertEquals("Before conflict continued after rival", saved!!.note)
                assertTrue(r.session.draftsKept())
            }
            val posts = f.notePosts(a.credentials.deviceId).size
            assertEquals(listOf(200, 409), f.notePosts(a.credentials.deviceId).map { it["status"]!!.jsonPrimitive.int })
            Rig(fresh(settings)).use { r ->
                assertEquals(saved, r.session.localDrafts().single())
                r.click("local-draft-0")
                assertEquals(saved!!.noteId, r.session.noteDraft!!.id)
                assertEquals(saved!!.baseRevision, r.session.noteDraft!!.baseRevision)
                assertEquals(saved!!.note, r.session.noteText)
                r.frames(); assertEquals(posts, f.notePosts(a.credentials.deviceId).size, "no automatic resend")
            }
            assertEquals(2, f.sqliteNotes().size)
            f.assertNoSecrets(settings.clientDirectory.resolve("reader-drafts"))
        }
    }

    @Test fun originCourseAndNewPairingNeverEnumerateOrResumeTheOtherScope() {
        PairedDeviceDraftFixture("scope-isolation").use { f ->
            val settings = f.settings(); val a = f.pair(settings, "A")
            val host = fresh(settings); val store = persisted(host)
            val key = PairedDeviceDraftFixture.digest("${desktopServerUrl(f.url)}\n${a.credentials.deviceId}\nC1".toByteArray()).take(32)
            assertEquals("paired-device:$key", store.scope)
            var draft: StoredDraft? = null
            Rig(host).use { r -> r.start("Only paired A and C1"); draft = r.session.localDrafts().single() }
            assertTrue(Files.isDirectory(settings.clientDirectory.resolve("reader-drafts/paired/$key")))
            // Equivalent server spellings use exactly the same partition.
            pairedReaderHost(settings, DesktopIdentity(f.url + "/", a.credentials), "C1").use { alias -> assertEquals(store.scope, persisted(alias).scope) }
            Rig(fresh(settings, "C2")).use { r ->
                assertEquals("same-doc", r.host.remote.current!!.id)
                assertNotEquals(store.scope, persisted(r.host).scope)
                assertTrue(r.session.localDrafts().isEmpty()); assertFalse(r.session.resumeLocalDraft(draft!!))
            }
            val b = f.pair(settings, "B replaces A on same client directory")
            assertNotEquals(a.credentials.deviceId, b.credentials.deviceId)
            Rig(fresh(settings)).use { r ->
                assertNotEquals(store.scope, persisted(r.host).scope)
                assertTrue(r.session.localDrafts().isEmpty()); assertFalse(r.session.resumeLocalDraft(draft!!))
            }
            PairedDeviceDraftFixture("other-origin").use { other ->
                // Mint a real pairing at the new origin and assert the exact address/device/course hash.
                val otherSettings = other.settings(settings.home)
                val identity = other.pair(otherSettings, "Other service")
                pairedReaderHost(otherSettings, identity, "C1").use { otherHost ->
                    val actual = persisted(otherHost).scope
                    val expected = PairedDeviceDraftFixture.digest("${desktopServerUrl(other.url)}\n${identity.credentials.deviceId}\nC1".toByteArray()).take(32)
                    assertEquals("paired-device:$expected", actual); assertNotEquals(store.scope, actual)
                    Rig(otherHost).use { r -> assertTrue(r.session.localDrafts().isEmpty()); assertFalse(r.session.resumeLocalDraft(draft!!)) }
                }
            }
            assertEquals(draft, store.listFor("same-doc", f.version, 1).single(), "old file retained, never migrated")
            f.assertNoSecrets(settings.clientDirectory.resolve("reader-drafts"))
        }
    }

    @Test fun aChangedDocumentVersionCannotRestoreTheOldDraftAtTheSameOrigin() {
        PairedDeviceDraftFixture("version-isolation").use { f ->
            val settings = f.settings(); f.pair(settings, "A")
            val host = fresh(settings); val store = persisted(host)
            var original: StoredDraft? = null
            Rig(host).use { r -> r.start("Old version only"); original = r.session.localDrafts().single() }
            val oldVersion = f.version; val before = f.snapshot(settings.clientDirectory.resolve("reader-drafts"))
            f.replaceVersion(); assertNotEquals(oldVersion, f.version)
            Rig(fresh(settings)).use { r ->
                assertEquals(f.version, r.host.remote.current!!.version)
                assertTrue(r.session.localDrafts().isEmpty()); assertFalse(r.session.resumeLocalDraft(original!!))
            }
            assertEquals(before, f.snapshot(settings.clientDirectory.resolve("reader-drafts")))
            assertEquals(original, store.listFor("same-doc", oldVersion, 1).single())
        }
    }

    @Test fun actual401DoesNotAckOrChangeTheDraftAndRepairingUsesANewPartition() {
        PairedDeviceDraftFixture("revocation").use { f ->
            val settings = f.settings(); val a = f.pair(settings, "A")
            val host = fresh(settings); val store = persisted(host)
            val directory = settings.clientDirectory.resolve("reader-drafts")
            var before: Map<String, String> = emptyMap()
            Rig(host).use { r ->
                r.start("Draft before actual revocation")
                before = f.snapshot(directory); assertEquals(1, before.size)
                f.revoke(a); r.save()
                r.until("actual401") { host.remote.noteSave == NoteSaveState.Failed(ReaderFailure.Unauthorized) }
                assertEquals("Draft before actual revocation", r.session.noteText)
                assertEquals(before, f.snapshot(directory)); assertFalse(host.authorized)
                r.save(); r.frames()
                assertEquals(listOf(401), f.notePosts(a.credentials.deviceId).map { it["status"]!!.jsonPrimitive.int })
            }
            assertEquals(before, f.snapshot(directory)); assertTrue(f.sqliteNotes().isEmpty())
            val replacement = f.pair(settings, "Repaired")
            assertNotEquals(a.credentials.deviceId, replacement.credentials.deviceId)
            Rig(fresh(settings)).use { r -> assertNotEquals(store.scope, persisted(r.host).scope); assertTrue(r.session.localDrafts().isEmpty()) }
            assertEquals(before, f.snapshot(directory)); f.assertNoSecrets(directory)
        }
    }

    @Test fun aReal200OnlyClearsItsMatchingDraftAndNeverTheOtherPage() {
        PairedDeviceDraftFixture("matching-ack").use { f ->
            val settings = f.settings(); f.pair(settings, "A")
            val host = fresh(settings); val store = persisted(host)
            Rig(host).use { r ->
                r.start("Save this page")
                val current = r.session.localDrafts().single()
                val other = current.copy(identity = current.identity.copy(pdfPage = 2), noteId = "other-page-note", note = "Keep other page")
                assertTrue(store.put(other))
                r.save(); r.until("200") { host.remote.noteSave is NoteSaveState.Saved }
                assertNull(store.get(current.identity)); assertEquals(other, store.get(other.identity))
                assertEquals(1, f.sqliteNotes().size)
                assertNull(r.node("reader-persistence-notice"), "window-only notice cannot contradict an enabled durable store")
            }
            assertEquals("Keep other page", store.listFor("same-doc", f.version, 2).single().note)
        }
    }

    @Test fun unregisteredCredentialsAndGenericSourcesStayNonPersistent() {
        val home = Files.createTempDirectory("unregistered-draft-")
        val settings = DesktopConnectionSettings(home, { if (it == "COACH_URL") "http://127.0.0.1:49195" else null }, mac = false, property = { null })
        pairedReaderHost(settings, DesktopIdentity("http://127.0.0.1:49195", PairedCredentials("invented", "not-registered")), "C1").use { assertNull(it.session.drafts) }
        assertNull(ReaderDraftScope.forSource(FakeReaderService(Triple("a", "v1", 2)), home))
        assertNull(ReaderDraftScope.forSource(SyntheticReaderSource(), home))
        assertFalse(Files.exists(settings.clientDirectory.resolve("reader-drafts")))
    }
}
