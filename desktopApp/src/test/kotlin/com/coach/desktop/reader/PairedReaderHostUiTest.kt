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
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import java.nio.file.Files
import kotlin.test.*

/** New paired host with real CIO/loopback 401 and Compose input; no native/learner identity claim. */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class PairedReaderHostUiTest {
    @Test fun http401LeavesTheMountedEditorAndItsActualTypedTextAvailable() {
        val fake = FakeReaderService(Triple("a", "va1", 3)).apply { bodyText = { _, _ -> "Alpha beta gamma\nDelta epsilon" } }
        val lb = ReaderHttpSourceTest.Loopback(fake)
        lb.override = { r ->
            if (r.method == "POST" && r.rawPath.endsWith("/notes")) Triple(401, "{}".toByteArray(), "application/json") else null
        }
        val home = Files.createTempDirectory("paired-host-ui-")
        val settings = DesktopConnectionSettings(home, environment = { if (it == "COACH_URL") lb.base else null }, mac = false, property = { null })
        val host = pairedReaderHost(settings, DesktopIdentity(lb.base, PairedCredentials("dev1", "synthetic-token")), "c1")
        val scene = ImageComposeScene(1200, 800, Density(1f)) { ReaderHostContent(host, ReaderOpenRequest("a", "va1", 1)) }
        var time = 0L
        fun frames() { repeat(4) { time += 16_000_000; scene.render(time).close(); Thread.sleep(5) } }
        fun node(tag: String): SemanticsNode? {
            fun find(n: SemanticsNode): SemanticsNode? = if (n.config.getOrNull(SemanticsProperties.TestTag) == tag) n
                else n.children.firstNotNullOfOrNull(::find)
            return scene.semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode) }
        }
        fun until(what: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!condition()) { assertTrue(System.nanoTime() < deadline, "timeout: $what"); frames() }
        }
        fun key(key: Key, ctrl: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl)); frames()
        }
        try {
            until("actual HTTP page rendered") { host.remote.page is PageState.Ready }
            host.session.focusPane(ReaderPane.Body); frames()
            key(Key.N); key(Key.Enter)
            until("editor") { node("note-input") != null }
            val text = "Typed before actual HTTP 401"
            for (char in text) {
                assertTrue(checkNotNull(node("note-input")!!.config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action)(AnnotatedString(char.toString())))
                frames()
            }
            assertEquals(text, host.session.noteText)
            key(Key.Enter, ctrl = true)
            until("revocation is visible") { node("reader-pairing-revoked") != null }
            assertEquals(text, node("note-input")!!.config.getOrNull(SemanticsProperties.EditableText)!!.text)
            assertEquals(NoteSaveState.Failed(ReaderFailure.Unauthorized), host.remote.noteSave)
            assertNotNull(node("reader-persistence-notice"))
            host.revoke(); frames()
            assertEquals(text, node("note-input")!!.config.getOrNull(SemanticsProperties.EditableText)!!.text)
            key(Key.Enter, ctrl = true)
            assertEquals(1, lb.seen.count { it.method == "POST" && it.rawPath.endsWith("/notes") }, "retired host must not resend")
            assertNull(host.session.drafts, "no guessed HTTP owner")
        } finally { scene.close(); host.close(); lb.stop() }
    }
}
