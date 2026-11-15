package com.coach.desktop.reader

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ReaderNoteButtonsTest {
    private class Rig(val fake: FakeReaderService = source(), val store: NoteDraftStore? = null) : AutoCloseable {
        val remote = ReaderRemote(fake)
        val session = ReaderSession(ReaderFixture("Invented", emptyList(), emptyList()), 1100, remote, store)
        val scene = ImageComposeScene(1100, 850, Density(1f)) { ReaderWindow(session) }
        private var time = 0L
        init {
            frames()
            runBlocking { remote.loadCatalog(); session.openRemote("a") }
            until("page ready") { remote.page is PageState.Ready }
            session.focusPane(ReaderPane.Body); frames()
        }
        fun frames(n: Int = 4) { repeat(n) { time += 16_000_000; scene.render(time).close(); Thread.sleep(3) } }
        fun until(label: String, check: () -> Boolean) {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!check()) { assertTrue(System.nanoTime() < deadline, "timeout: $label"); frames() }
        }
        fun node(tag: String): SemanticsNode? {
            fun find(n: SemanticsNode): SemanticsNode? = if (n.config.getOrNull(SemanticsProperties.TestTag) == tag) n
                else n.children.firstNotNullOfOrNull(::find)
            return scene.semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode) }
        }
        fun key(key: Key, ctrl: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl)); frames()
        }
        fun enter() { key(Key.N); key(Key.Enter); assertNotNull(node("note-input")) }
        fun type(text: String) {
            for (char in text) {
                assertTrue(checkNotNull(node("note-input")!!.config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action)(AnnotatedString(char.toString())))
                frames(1)
            }
        }
        /** Pointer events exercise the enabled/disabled control, never its semantics action directly. */
        fun click(tag: String) {
            val target = assertNotNull(node(tag), "missing $tag")
            val p = target.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Move, p)
            scene.sendPointerEvent(PointerEventType.Press, p, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.sendPointerEvent(PointerEventType.Release, p, buttons = PointerButtons(), button = PointerButton.Primary)
            frames()
        }
        fun status() = node("note-status")?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        override fun close() = scene.close()
    }

    @Test fun pointerSaveAndCtrlEnterUseTheSameContractAndShowSaved() {
        val bodies = mutableListOf<JsonObject>()
        for (pointer in listOf(true, false)) Rig().use { r ->
            assertNull(r.node("reader-note-save")); assertNull(r.node("reader-note-close"))
            r.enter(); r.type("Same user note")
            assertNotNull(r.node("reader-note-save")); assertNotNull(r.node("reader-note-close"))
            val id = r.session.noteDraft!!.id
            if (pointer) r.click("reader-note-save") else r.key(Key.Enter, ctrl = true)
            r.until("saved") { r.remote.noteSave is NoteSaveState.Saved && r.status() == "已保存" }
            assertEquals(1, r.fake.noteCalls.size)
            assertEquals(id, assertIs<NoteSaveState.Saved>(r.remote.noteSave).id)
            assertEquals("Same user note", r.session.noteText)
            bodies += JsonObject(r.fake.noteCalls.single().filterKeys { it !in setOf("id", "mutationId") })
        }
        assertEquals(bodies[0], bodies[1], "button and shortcut send the same immutable note/base revision")
    }

    @Test fun savingDisablesTheButtonAndASecondPointerClickCannotQueueAnotherRequest() {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val fake = source().apply { noteHold = entered to release }
        try {
            Rig(fake).use { r ->
                r.enter(); r.type("One request only")
                r.click("reader-note-save")
                r.until("request held") { entered.isCompleted && r.session.noteSaving }
                assertNotNull(r.node("reader-note-save")!!.config.getOrNull(SemanticsProperties.Disabled))
                r.click("reader-note-save")
                assertEquals(1, fake.noteCalls.size)
                release.complete(Unit)
                r.until("ack rendered and button enabled") {
                    !r.session.noteSaving && r.remote.noteSave is NoteSaveState.Saved &&
                        r.node("reader-note-save")!!.config.getOrNull(SemanticsProperties.Disabled) == null
                }
                assertNull(r.node("reader-note-save")!!.config.getOrNull(SemanticsProperties.Disabled))
                assertEquals(1, fake.noteCalls.size)
            }
        } finally { release.complete(Unit) }
    }

    @Test fun pointerAndKeyboard501KeepTheSameDraftAndFailureText() {
        val displayed = mutableListOf<String>()
        for (pointer in listOf(true, false)) {
            val store = FileNoteDraftStore(Files.createTempDirectory("reader-button-refusal-"), "test-owner:$pointer")
            Rig(source().apply { noteStatus = 501 }, store).use { r ->
                r.enter(); r.type("Keep this refused draft")
                assertNotNull(r.node("reader-note-save"))
                val draft = r.session.noteDraft!!
                if (pointer) r.click("reader-note-save") else r.key(Key.Enter, ctrl = true)
                r.until("501") { r.remote.noteSave is NoteSaveState.Failed && r.node("note-failure-reason") != null }
                assertEquals(NoteSaveState.Failed(ReaderFailure.Server(501)), r.remote.noteSave)
                assertEquals(draft.id, r.session.noteDraft!!.id)
                assertEquals(draft.baseRevision, r.session.noteDraft!!.baseRevision)
                assertEquals("Keep this refused draft", r.session.noteText)
                val saved = store.listFor("a", "va1", 1).single()
                assertEquals(draft.id, saved.noteId); assertEquals(draft.baseRevision, saved.baseRevision)
                assertEquals(r.session.noteText, saved.note)
                displayed += r.node("note-failure-reason")!!.config.getOrNull(SemanticsProperties.Text)!!.joinToString("") { it.text }
            }
        }
        assertEquals(displayed[0], displayed[1])
    }

    @Test fun pointerCloseIsOrdinaryCloseAndCanResumeTheSameDraftLikeEsc() {
        for (pointer in listOf(true, false)) {
            val store = FileNoteDraftStore(Files.createTempDirectory("reader-button-close-"), "test-owner:$pointer")
            Rig(source(), store).use { r ->
                r.enter(); r.type("Close without discarding")
                assertNotNull(r.node("reader-note-close"))
                val original = r.session.noteDraft!!
                if (pointer) r.click("reader-note-close") else r.key(Key.Escape)
                assertNull(r.node("note-input")); assertNull(r.node("reader-note-save")); assertNull(r.node("reader-note-close"))
                assertTrue(r.fake.noteCalls.isEmpty(), "close is not a save or automatic resend")
                assertEquals("Close without discarding", store.listFor("a", "va1", 1).single().note)
                r.enter()
                assertEquals(original.id, r.session.noteDraft!!.id)
                assertEquals(original.baseRevision, r.session.noteDraft!!.baseRevision)
                assertEquals("Close without discarding", r.session.noteText)
            }
        }
    }

    companion object {
        private fun source() = FakeReaderService(Triple("a", "va1", 3)).apply { bodyText = { _, _ -> "Alpha beta gamma\nDelta epsilon" } }
    }
}
