package com.coach.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.coach.client.CourseHttp
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

/** Actual rendered copy, through production sessions and HTTP or real draft files; no native input claims. */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ReaderDraftBoundaryCopyTest {
    private class Rig(source: ReaderSource, store: NoteDraftStore? = null) : AutoCloseable {
        val remote = ReaderRemote(source)
        val session = ReaderSession(ReaderFixture("Synthetic", emptyList(), emptyList()), 1100, remote, store)
        val scene = ImageComposeScene(1100, 850, Density(1f)) { ReaderWindow(session) }
        private var time = 0L
        init {
            frames()
            runBlocking { remote.loadCatalog(); session.openRemote("a") }
            until("page ready") { remote.page is PageState.Ready }
            session.focusPane(ReaderPane.Body); frames()
        }
        fun frames() { repeat(4) { time += 16_000_000; scene.render(time).close(); Thread.sleep(3) } }
        fun until(label: String, check: () -> Boolean) {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!check()) { assertTrue(System.nanoTime() < deadline, "timeout: $label"); frames() }
        }
        fun node(tag: String): SemanticsNode? {
            fun find(n: SemanticsNode): SemanticsNode? = if (n.config.getOrNull(SemanticsProperties.TestTag) == tag) n
                else n.children.firstNotNullOfOrNull(::find)
            return scene.semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode) }
        }
        fun shown(tag: String) = node(tag)?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        fun key(key: Key, ctrl: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl)); frames()
        }
        fun type(text: String) {
            val insert = assertNotNull(assertNotNull(node("note-input")).config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action)
            assertTrue(insert(AnnotatedString(text))); frames()
        }
        fun start() { key(Key.N); key(Key.Enter); type("My draft") }
        fun conflict(fake: FakeReaderService) {
            start(); key(Key.Enter, ctrl = true)
            until("first save rendered") { remote.noteSave is NoteSaveState.Saved && shown("note-status") == "已保存" }
            fake.otherDeviceEditsNote("a", session.noteDraft!!.id, "Other device")
            type(" continued"); key(Key.Enter, ctrl = true)
            until("conflict rendered") { remote.noteSave is NoteSaveState.Conflict && node("note-conflict-panel") != null }
        }
        override fun close() = scene.close()
    }

    @Test fun httpWithoutDraftStorageWarnsThatTheConflictSurvivesOnlyInThisWindow() {
        val fake = source()
        val loopback = ReaderHttpSourceTest.Loopback(fake)
        try {
            Rig(ReaderHttpSource(CourseHttp(loopback.base, "synthetic-device", "synthetic-token", "c1").reader)).use { r ->
                r.conflict(fake)
                assertFalse(r.session.draftsKept()); assertNull(r.session.drafts)
                assertEquals(WINDOW_CONFLICT, r.shown("note-status"))
                assertEquals(WINDOW_CONFLICT, r.session.uiNoteStatus)
                assertFalse(r.shown("note-status")!!.contains("本机"))
                assertEquals("My draft continued", r.session.noteText)
                assertEquals(2, loopback.seen.count { it.method == "POST" && it.rawPath.endsWith("/notes") })
                println("HTTP_CONFLICT_COPY=${r.shown("note-status")}")
            }
        } finally { loopback.stop() }
    }

    @Test fun aRealDiskWriteFailureCannotPromiseThatTheConflictDraftIsOnDisk() {
        val root = Files.createTempDirectory("copy-disk-failure-")
        val blocker = Files.writeString(root.resolve("not-a-directory"), "synthetic blocker")
        val store = FileNoteDraftStore(blocker.resolve("drafts"), "synthetic-local-scope")
        val fake = source()
        Rig(fake, store).use { r ->
            r.conflict(fake)
            assertTrue(r.session.persistFailed); assertFalse(r.session.draftsKept())
            assertEquals(WINDOW_CONFLICT, r.shown("note-status"))
            assertEquals(WINDOW_CONFLICT, r.session.uiNoteStatus)
            assertEquals("My draft continued", r.session.noteText)
            assertTrue(store.listFor("a", "va1", 1).isEmpty())
            assertEquals("synthetic blocker", Files.readString(blocker))
            assertNotNull(r.node("note-draft-io-failed"))
        }
    }

    @Test fun aDraftActuallyWrittenToDiskKeepsTheExistingLocalConflictCopy() {
        // Synthetic scoped store, not a claim that HTTP has a trusted learner owner.
        val directory = Files.createTempDirectory("copy-persisted-")
        val store = FileNoteDraftStore(directory, "synthetic-local-scope")
        val fake = source()
        Rig(fake, store).use { r ->
            r.conflict(fake)
            assertTrue(r.session.draftsKept()); assertFalse(r.session.persistFailed)
            assertEquals(LOCAL_CONFLICT, r.shown("note-status"))
            assertEquals(LOCAL_CONFLICT, r.session.uiNoteStatus)
            val reread = FileNoteDraftStore(directory, store.scope).listFor("a", "va1", 1).single()
            assertEquals(r.session.noteDraft!!.id, reread.noteId)
            assertEquals(r.session.noteDraft!!.baseRevision, reread.baseRevision)
            assertEquals("My draft continued", reread.note)
            println("PERSISTED_CONFLICT_COPY=${r.shown("note-status")}")
        }
    }

    @Test fun unsupportedSaveStillDistinguishesPersistedAndWindowOnlyDrafts() {
        for (persisted in listOf(false, true)) {
            val store = if (persisted) FileNoteDraftStore(Files.createTempDirectory("copy-501-"), "synthetic-local-scope") else null
            val fake = source().apply { noteStatus = 501 }
            Rig(fake, store).use { r ->
                r.start(); r.key(Key.Enter, ctrl = true)
                r.until("refusal rendered") { r.remote.noteSave is NoteSaveState.Failed && r.node("note-failure-reason") != null }
                val tail = if (persisted) "；这条草稿已留在本机，关闭后可以从“本页有未保存的草稿”继续" else "；关闭编辑区后这条草稿不会保留"
                assertEquals(ReaderFailure.Server(501).text + tail, r.shown("note-failure-reason"))
                assertEquals(persisted, r.session.draftsKept())
                assertEquals("没有保存", r.shown("note-status"))
                assertEquals("My draft", r.session.noteText)
                if (persisted) assertEquals("My draft", store!!.listFor("a", "va1", 1).single().note)
            }
        }
    }

    companion object {
        private const val WINDOW_CONFLICT = "这条批注有冲突，没有覆盖对方；仅此窗口保留，关闭前请复制"
        private const val LOCAL_CONFLICT = "这条批注有冲突，草稿保留在本机，没有覆盖对方"
        private fun source() = FakeReaderService(Triple("a", "va1", 3)).apply { bodyText = { _, _ -> "Alpha beta gamma\nDelta epsilon" } }
    }
}
