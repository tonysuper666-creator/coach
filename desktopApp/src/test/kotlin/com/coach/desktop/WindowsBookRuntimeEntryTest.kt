package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import kotlin.test.*

/** Real Windows host -> consent UI -> production transport -> real AppContainer helper. No real auth. */
@OptIn(ExperimentalTestApi::class)
class WindowsBookRuntimeEntryTest {
    private class Fixture {
        val f = OwnedBookConsentTest.Fixture()
        val sentinel = Files.writeString(f.root.resolve("SYNTHETIC-neighbor.txt"), "SYNTHETIC_ADJACENT_MUST_NOT_BE_READ")
        val adapter = SyntheticIsolatedCodex(f, "success", sentinel)
        val root = Files.createDirectory(f.root.resolve("SYNTHETIC-signed-runtime"))
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val manifest: ByteArray
        val signature: ByteArray
        val runtime: WindowsBookRuntime
        val authReads = AtomicInteger()
        val argv = Collections.synchronizedList(mutableListOf<Pair<List<String>, Int>>())
        val receipts = Collections.synchronizedList(mutableListOf<WindowsBookIsolationReceipt>())
        val runs = Collections.synchronizedList(mutableListOf<WindowsBookRun>())
        val helperHashes = Collections.synchronizedList(mutableListOf<String>())
        val evidence = WindowsBookRuntimeEvidence({ args, exit ->
            helperHashes.add(WindowsBookRuntime.sha256(Path.of(args.first())))
            argv.add(args to exit)
        }, { receipts.add(it) }, {
            runs.add(it); AutoCloseable {}
        })
        val authSource: () -> Path = { authReads.incrementAndGet(); adapter.auth }
        val book: Textbook
        val controller: TextbookController
        init {
            Files.copy(adapter.executable, root.resolve("codex.exe"))
            Files.copy(SyntheticIsolatedCodex.helper, root.resolve("coach-iso.exe"))
            Files.copy(SyntheticIsolatedCodex.committer, root.resolve("coach-auth.exe"))
            manifest = buildJsonObject {
                put("contract", "windows-owned-book-runtime-v1")
                val identity = OwnedBookConsentIdentity()
                put("provider", identity.provider); put("model", identity.model); put("notice", identity.notice)
                putJsonObject("sha256") { listOf("codex.exe", "coach-iso.exe", "coach-auth.exe").forEach {
                    put(it, WindowsBookRuntime.sha256(root.resolve(it)))
                } }
            }.toString().toByteArray()
            signature = Signature.getInstance("Ed25519").run { initSign(keys.private); update(manifest); sign() }
            runtime = assertNotNull(WindowsBookRuntime.verify(root, manifest, signature, keys.public))
            val pdf = f.root.resolve("SYNTHETIC-plants.pdf")
            PDDocument().use { doc ->
                repeat(2) { i ->
                    val page = PDPage(); doc.addPage(page)
                    PDPageContentStream(doc, page).use { out ->
                        out.beginText(); out.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                        out.newLineAtOffset(50f, 710f)
                        out.showText(if (i == 0) "Plants grow roots." else "Photosynthesis uses light to make sugars.")
                        out.endText()
                    }
                }; doc.save(pdf.toFile())
            }
            val imported = f.library.importBook(pdf)
            book = imported.copy(readingPage = 2, learning = true, promptSeen = true, questionMode = QuestionMode.SHORT_ANSWER)
            f.library.save(book)
            controller = TextbookController(f.library)
            runBlocking { controller.load(); controller.select(book.id) }
        }
        fun assertClean() {
            assertTrue(runs.all { !Files.exists(it.work.parent) }, "Every real transport run must be removed")
            assertTrue(argv.all { !Files.exists(Path.of(it.first.first()).parent) }, "Every verified assembly runtime copy must be removed")
            assertTrue(receipts.all { it.cleaned && it.verified && it.exit == 0 })
            assertTrue(receipts.mapNotNull { it.childPid }.none { ProcessHandle.of(it).map { process -> process.isAlive }.orElse(false) })
            assertContentEquals(adapter.original, Files.readAllBytes(adapter.auth))
            assertEquals("SYNTHETIC_ADJACENT_MUST_NOT_BE_READ", Files.readString(sentinel))
        }
    }

    private fun SkikoComposeUiTest.waitTag(tag: String) = waitUntil(timeoutMillis = 60_000) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun SkikoComposeUiTest.clickTag(tag: String) {
        waitUntil(timeoutMillis = 60_000) { onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun SkikoComposeUiTest.capture(tag: String, name: String) {
        val output = Path.of("build/screens/runtime-entry").also { Files.createDirectories(it) }.resolve("$name.png")
        assertTrue(ImageIO.write(onNodeWithTag(tag).captureToImage().toAwtImage(), "png", output.toFile()))
    }
    private fun SkikoComposeUiTest.openQuestion(f: Fixture) {
        waitTag("home-read")
        onNodeWithTag("home-read").performScrollTo().performClick()
        waitTag("ask-book-passage")
        assertEquals(2, f.controller.state.value.page)
        onNodeWithTag("ask-book-passage").performClick()
        onNodeWithTag("ask-paragraph-0").performScrollTo().performClick()
        onNodeWithTag("book-passage-question").performScrollTo().performTextInput("Why is light needed?")
        onNodeWithTag("book-passage-send").performScrollTo().performClick()
        waitTag("book-consent-notice")
        assertEquals(0, f.authReads.get(), "An offer cannot read auth or start a process")
        assertTrue(f.argv.isEmpty())
    }

    @Test fun ordinaryShelfConsentAskRealHelperPhysicalSourceReturnAndRevoke() {
        val f = Fixture()
        runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
            setContent { WindowsTextbookHost(f.controller, runtime = f.runtime, runtimeAuthSource = f.authSource,
                runtimeEvidence = f.evidence) { null } }
            openQuestion(f)
            clickTag("book-consent-decline")
            waitTag("book-consent-reopen")
            assertTrue(f.argv.isEmpty()); assertEquals(0, f.authReads.get())
            clickTag("book-consent-reopen")
            waitTag("book-consent-grant")
            clickTag("book-consent-grant")
            waitTag("book-passage-answer")
            onNodeWithTag("book-passage-answer").assertTextEquals("Light supplies energy for making stored food. SYNTHETIC_NEIGHBOR_DENIED")
            capture("book-passage-ask", "answer-before-source")
            val grant = assertNotNull(f.f.store.read(f.book.id))
            assertEquals(f.book.id, grant.book)
            assertEquals(OwnedBookConsentIdentity(), grant.identity)
            assertEquals(setOf(OwnedBookAction.EXPLAIN, OwnedBookAction.ASK), grant.actions)
            waitTag("book-source-0")
            onNodeWithTag("book-source-0").performScrollTo().performClick()
            waitTag("return-to-passage-question")
            // The normal reader renders the actual imported PDF page, not a generated source page.
            waitTag("reader-page")
            waitTag("remote-image")
            onNodeWithTag("remote-image").assertContentDescriptionEquals("${f.book.title} 第 2 页")
            capture("remote-image", "physical-pdf-page-2")
            assertEquals(BookScreen.READER, f.controller.state.value.screen)
            assertEquals(2, f.controller.state.value.page)
            onNodeWithTag("return-to-passage-question").performClick()
            waitTag("book-passage-answer")
            onNodeWithTag("book-passage-question").assertTextContains("Why is light needed?")
            capture("book-passage-ask", "same-answer-after-source")
            waitForIdle()
            assertEquals(1, f.authReads.get())
            assertEquals(2, f.argv.size, "One ask has one login and one exec, including after source return")
            val exec = f.argv.single { "exec" in it.first }
            assertEquals(0, exec.second)
            assertTrue(exec.first.contains("--ignore-user-config"))
            assertTrue(exec.first.contains("features.multi_agent_v2.disable_native_tools=true"))
            assertEquals(listOf(WindowsBookRuntime.sha256(SyntheticIsolatedCodex.helper)), f.helperHashes.distinct())
            assertEquals(setOf("login", "exec"), f.receipts.map { it.phase }.toSet())
            assertTrue(f.receipts.all { it.childPid != null && it.containerSid?.startsWith("S-1-15-2-") == true })
            clickTag("book-consent-revoke")
            waitTag("book-consent-reopen")
            onNodeWithTag("book-passage-answer").assertDoesNotExist()
            onNodeWithTag("book-passage-send").assertIsNotEnabled()
            capture("book-passage-ask", "revoked-answer-cleared")
            waitForIdle(); assertEquals(2, f.argv.size); assertNull(f.f.store.read(f.book.id))
        }
        f.assertClean()
        println("SYNTHETIC_HOST consent=explicit book_sha=matched physical_pdf_page=2 ask_execs=1 source_return_execs=0 revoked_answers=0 subsequent_spawns=0 adjacent_sentinel=denied cleanup=complete")
        f.receipts.forEach { println("SYNTHETIC_HOST_HELPER phase=${it.phase} exit=${it.exit} pid=${it.childPid} sid=${it.containerSid} verified=${it.verified} cleaned=${it.cleaned}") }
        val native = f.argv.single { "exec" in it.first }.first.dropWhile { it != "--" }.drop(2)
        val safeArgv = native.mapIndexed { i, arg -> if (i > 0 && native[i - 1] == "-o") "<SYNTHETIC-output>" else arg }
        println("SYNTHETIC_HOST_ARGV " + JsonArray(safeArgv.map(::JsonPrimitive)))
        println("SYNTHETIC_HOST_IDENTITY helper_sha256=${f.helperHashes.first()} auth_helper_sha256=${WindowsBookRuntime.sha256(SyntheticIsolatedCodex.committer)} trust=ephemeral_test_key")
    }

    @Test fun badSyntheticAuthThroughActualHostCannotLaunchOrPublish() {
        val f = Fixture(); Files.writeString(f.adapter.auth, "{")
        runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
            setContent { WindowsTextbookHost(f.controller, runtime = f.runtime, runtimeAuthSource = f.authSource, runtimeEvidence = f.evidence) { null } }
            openQuestion(f)
            clickTag("book-consent-grant")
            waitUntil(timeoutMillis = 60_000) { f.authReads.get() == 1 && onAllNodesWithTag("book-passage-unavailable").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("book-passage-answer").assertDoesNotExist()
            assertTrue(f.argv.isEmpty()); assertTrue(f.receipts.isEmpty())
        }
        assertEquals("{", Files.readString(f.adapter.auth))
        println("SYNTHETIC_HOST bad_auth=preserved helper_spawns=0 answers=0")
    }

    @Test fun signaturesCapabilitiesAndConcurrentBinaryReplacementFailClosedBeforeAuth() = runBlocking {
        val f = Fixture()
        assertNull(WindowsBookRuntime.verify(f.root, f.manifest + byteArrayOf(32), f.signature, f.keys.public))
        assertNull(WindowsBookRuntime.verify(f.root, f.manifest, f.signature, KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public))
        for ((before, after) in listOf("windows-owned-book-runtime-v1" to "windows-owned-book-runtime-v0",
            OwnedBookConsentIdentity().notice to "SYNTHETIC-unreviewed-notice")) {
            val changed = f.manifest.toString(Charsets.UTF_8).replace(before, after).toByteArray()
            val signed = Signature.getInstance("Ed25519").run { initSign(f.keys.private); update(changed); sign() }
            assertNull(WindowsBookRuntime.verify(f.root, changed, signed, f.keys.public))
        }
        val unavailable = isolatedWindowsBookLearningSupport(f.f.library, null, f.authSource, f.evidence)
        assertIs<BookLearningReply.Unavailable>(unavailable.ask(f.f.ask()))
        val support = isolatedWindowsBookLearningSupport(f.f.library, f.runtime, f.authSource, f.evidence) as ConsentingBookLearningSupport
        support.grant(support.consentOffer(f.f.a.id), setOf(OwnedBookAction.ASK))
        Files.write(f.root.resolve("coach-iso.exe"), byteArrayOf(1), APPEND)
        assertIs<BookLearningReply.Unavailable>(support.ask(f.f.ask()))
        assertEquals(0, f.authReads.get()); assertTrue(f.argv.isEmpty())
        println("SYNTHETIC_HOST unsigned=denied wrong_trust=denied missing_capability=denied binary_replacement=denied auth_reads=0 spawns=0")
    }

    @Test fun crossBookAndChangedOriginalStopAtTheSameAssemblyGate() = runBlocking {
        val f = Fixture()
        val support = isolatedWindowsBookLearningSupport(f.f.library, f.runtime, f.authSource, f.evidence) as ConsentingBookLearningSupport
        support.grant(support.consentOffer(f.f.a.id), setOf(OwnedBookAction.ASK))
        assertIs<BookLearningReply.Unavailable>(support.ask(f.f.ask(f.f.b)))
        val original = f.f.ask()
        assertIs<BookLearningReply.Unavailable>(support.ask(original.copy(passage = original.passage.copy(text = "Forged selection"))))
        Files.write(f.f.library.directory.resolve("${f.f.a.id}.${f.f.a.format}"), byteArrayOf(10), APPEND)
        assertIs<BookLearningReply.Unavailable>(support.ask(f.f.ask()))
        assertEquals(0, f.authReads.get()); assertTrue(f.argv.isEmpty())
        println("SYNTHETIC_HOST cross_book=denied changed_original=denied auth_reads=0 spawns=0")
    }
}
