package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import com.coach.desktop.books.WindowsBookRuntimeResourceTool as Tool
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import kotlin.test.*

/**
 * The ordinary Windows host finds its signed runtime through `WindowsBookRuntime.installed()` alone: the test passes
 * no `support` and no `runtime`. Trust key and signing key are generated here for the test and never leave temp dirs.
 */
@OptIn(ExperimentalTestApi::class)
class WindowsInstalledRuntimeDiscoveryTest {
    private class Fixture(publishTrust: Boolean = true, publishRuntime: Boolean = true) : AutoCloseable {
        val f = OwnedBookConsentTest.Fixture()
        val sentinel = Files.writeString(f.root.resolve("SYNTHETIC-neighbor.txt"), "SYNTHETIC_ADJACENT_MUST_NOT_BE_READ")
        val adapter = SyntheticIsolatedCodex(f, "success", sentinel)
        val resources = Files.createDirectory(f.root.resolve("SYNTHETIC-app-resources"))
        val keys = Tool.newTestKey()
        val classpath = Tool.testClasspathResourceDirectory()
        val trustFile = classpath.resolve(Tool.TRUST_RESOURCE)
        private val previousProperty = System.getProperty("compose.application.resources.dir")
        val runtimeRoot: Path = resources.resolve(Tool.RUNTIME_DIRECTORY)
        val authReads = AtomicInteger()
        val argv = Collections.synchronizedList(mutableListOf<Pair<List<String>, Int>>())
        val receipts = Collections.synchronizedList(mutableListOf<WindowsBookIsolationReceipt>())
        val runs = Collections.synchronizedList(mutableListOf<WindowsBookRun>())
        val helperHashes = Collections.synchronizedList(mutableListOf<String>())
        val evidence = WindowsBookRuntimeEvidence({ args, exit ->
            helperHashes.add(WindowsBookRuntime.sha256(Path.of(args.first()))); argv.add(args to exit)
        }, { receipts.add(it) }, { runs.add(it); AutoCloseable {} })
        val authSource: () -> Path = { authReads.incrementAndGet(); adapter.auth }
        val book: Textbook
        val controller: TextbookController
        init {
            check(!Files.exists(trustFile)) { "stale trust resource from another run: $trustFile" }
            if (publishRuntime) Tool.writeRuntime(resources, mapOf("codex.exe" to adapter.executable,
                "coach-iso.exe" to SyntheticIsolatedCodex.helper, "coach-auth.exe" to SyntheticIsolatedCodex.committer), keys)
            if (publishTrust) Tool.writeTrust(classpath, keys.public)
            System.setProperty("compose.application.resources.dir", resources.toString())
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
            book = f.library.importBook(pdf).copy(readingPage = 2, learning = true, promptSeen = true, questionMode = QuestionMode.SHORT_ANSWER)
            f.library.save(book)
            controller = TextbookController(f.library)
            runBlocking { controller.load(); controller.select(book.id) }
        }
        override fun close() {
            Files.deleteIfExists(trustFile)
            if (previousProperty == null) System.clearProperty("compose.application.resources.dir")
            else System.setProperty("compose.application.resources.dir", previousProperty)
        }
        fun assertNothingLaunched() {
            assertEquals(0, authReads.get(), "No auth path may be resolved or read"); assertTrue(argv.isEmpty()); assertTrue(receipts.isEmpty())
            assertContentEquals(adapter.original, Files.readAllBytes(adapter.auth))
            assertEquals("SYNTHETIC_ADJACENT_MUST_NOT_BE_READ", Files.readString(sentinel))
        }
        fun assertClean() {
            assertTrue(runs.all { !Files.exists(it.work.parent) }, "Every real transport run must be removed")
            assertTrue(argv.all { !Files.exists(Path.of(it.first.first()).parent) }, "Every verified assembly runtime copy must be removed")
            assertTrue(receipts.all { it.cleaned && it.verified && it.exit == 0 })
            assertTrue(receipts.mapNotNull { it.childPid }.none { ProcessHandle.of(it).map { process -> process.isAlive }.orElse(false) })
            assertContentEquals(adapter.original, Files.readAllBytes(adapter.auth))
            assertEquals("SYNTHETIC_ADJACENT_MUST_NOT_BE_READ", Files.readString(sentinel))
            assertTrue(Files.exists(runtimeRoot), "Installed resource files are never consumed")
        }
    }

    private fun SkikoComposeUiTest.waitTag(tag: String) = waitUntil(timeoutMillis = 60_000) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun SkikoComposeUiTest.clickTag(tag: String) {
        waitUntil(timeoutMillis = 60_000) { onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun SkikoComposeUiTest.capture(tag: String, name: String) {
        val output = Path.of("build/screens/installed-runtime").also { Files.createDirectories(it) }.resolve("$name.png")
        assertTrue(ImageIO.write(onNodeWithTag(tag).captureToImage().toAwtImage(), "png", output.toFile()))
    }
    private fun SkikoComposeUiTest.sendQuestion(f: Fixture) {
        waitTag("home-read")
        onNodeWithTag("home-read").performScrollTo().performClick()
        waitTag("ask-book-passage")
        assertEquals(2, f.controller.state.value.page)
        onNodeWithTag("ask-book-passage").performClick()
        onNodeWithTag("ask-paragraph-0").performScrollTo().performClick()
        onNodeWithTag("book-passage-question").performScrollTo().performTextInput("Why is light needed?")
        onNodeWithTag("book-passage-send").performScrollTo().performClick()
    }
    private fun SkikoComposeUiTest.openQuestion(f: Fixture) {
        sendQuestion(f)
        waitTag("book-consent-notice")
        assertEquals(0, f.authReads.get(), "An offer cannot read auth or start a process")
        assertTrue(f.argv.isEmpty())
    }
    // Deliberately no `support` and no `runtime`: the host must find the signed assets itself.
    private fun SkikoComposeUiTest.hostWithDefaultDiscovery(f: Fixture) =
        setContent { WindowsTextbookHost(f.controller, runtimeAuthSource = f.authSource, runtimeEvidence = f.evidence) { null } }

    @Test fun defaultDiscoveryRunsTheOrdinaryButtonChainThroughRealHelper() {
        Fixture().use { f ->
            val discovered = assertNotNull(WindowsBookRuntime.installed(), "installed() must find the generated assets")
            assertEquals(OwnedBookConsentIdentity(), discovered.identity)
            runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
                hostWithDefaultDiscovery(f)
                openQuestion(f)
                clickTag("book-consent-decline"); waitTag("book-consent-reopen")
                assertTrue(f.argv.isEmpty()); assertEquals(0, f.authReads.get())
                clickTag("book-consent-reopen"); waitTag("book-consent-grant"); clickTag("book-consent-grant")
                waitTag("book-passage-answer")
                onNodeWithTag("book-passage-answer").assertTextEquals("Light supplies energy for making stored food. SYNTHETIC_NEIGHBOR_DENIED")
                capture("book-passage-ask", "answer-before-source")
                assertEquals(f.book.id, assertNotNull(f.f.store.read(f.book.id)).book)
                waitTag("book-source-0"); onNodeWithTag("book-source-0").performScrollTo().performClick()
                waitTag("return-to-passage-question"); waitTag("reader-page"); waitTag("remote-image")
                onNodeWithTag("remote-image").assertContentDescriptionEquals("${f.book.title} 第 2 页")
                capture("remote-image", "physical-pdf-page-2")
                assertEquals(2, f.controller.state.value.page)
                onNodeWithTag("return-to-passage-question").performClick()
                waitTag("book-passage-answer")
                onNodeWithTag("book-passage-question").assertTextContains("Why is light needed?")
                capture("book-passage-ask", "same-answer-after-source")
                waitForIdle()
                assertEquals(1, f.authReads.get()); assertEquals(2, f.argv.size, "One ask is one login and one exec; source return adds none")
                val exec = f.argv.single { "exec" in it.first }
                assertEquals(0, exec.second)
                assertTrue(exec.first.contains("--ignore-user-config"))
                assertTrue(exec.first.contains("features.multi_agent_v2.disable_native_tools=true"))
                assertEquals(listOf(WindowsBookRuntime.sha256(SyntheticIsolatedCodex.helper)), f.helperHashes.distinct())
                assertEquals(setOf("login", "exec"), f.receipts.map { it.phase }.toSet())
                assertTrue(f.receipts.all { it.childPid != null && it.containerSid?.startsWith("S-1-15-2-") == true })
                clickTag("book-consent-revoke"); waitTag("book-consent-reopen")
                onNodeWithTag("book-passage-answer").assertDoesNotExist()
                onNodeWithTag("book-passage-send").assertIsNotEnabled()
                waitForIdle(); assertEquals(2, f.argv.size); assertNull(f.f.store.read(f.book.id))
            }
            f.assertClean()
            println("INSTALLED_DISCOVERY default=installed() passed_support=none passed_runtime=none trust=generated_test_key consent=explicit physical_pdf_page=2 ask_execs=1 source_return_execs=0 revoked_spawns=0 cleanup=complete")
            f.receipts.forEach { println("INSTALLED_DISCOVERY_HELPER phase=${it.phase} exit=${it.exit} pid=${it.childPid} sid=${it.containerSid} verified=${it.verified} cleaned=${it.cleaned}") }
            println("INSTALLED_DISCOVERY_IDENTITY helper_sha256=${f.helperHashes.first()} manifest_sha256=${WindowsBookRuntime.sha256(f.runtimeRoot.resolve("manifest.json"))}")
        }
    }

    @Test fun missingTrustOrRuntimeResourcesStayUnavailableThroughTheHost() {
        for ((trust, runtime) in listOf(false to true, true to false, false to false)) {
            Fixture(publishTrust = trust, publishRuntime = runtime).use { f ->
                assertNull(WindowsBookRuntime.installed())
                runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
                    hostWithDefaultDiscovery(f)
                    sendQuestion(f)
                    waitTag("book-passage-unavailable")
                    onNodeWithTag("book-consent-notice").assertDoesNotExist()
                    onNodeWithTag("book-passage-answer").assertDoesNotExist()
                }
                f.assertNothingLaunched()
            }
        }
        println("INSTALLED_DISCOVERY missing_trust=denied missing_runtime=denied missing_both=denied auth_reads=0 spawns=0")
    }

    @Test fun badSignatureWrongTrustAndChangedComponentsAreNeverDiscovered() {
        Fixture().use { f ->
            assertNotNull(WindowsBookRuntime.installed())
            val manifest = f.runtimeRoot.resolve("manifest.json"); val original = Files.readAllBytes(manifest)
            Files.write(manifest, original + byteArrayOf(32)); assertNull(WindowsBookRuntime.installed(), "manifest changed after signing")
            Files.write(manifest, original)
            val signature = f.runtimeRoot.resolve("manifest.sig"); val goodSignature = Files.readAllBytes(signature)
            Files.write(signature, Tool.sign(original, Tool.newTestKey())); assertNull(WindowsBookRuntime.installed(), "signed by an untrusted key")
            Files.write(signature, goodSignature.copyOf(63)); assertNull(WindowsBookRuntime.installed(), "short signature")
            Files.write(signature, goodSignature)
            assertNotNull(WindowsBookRuntime.installed())
            Files.write(f.trustFile, Tool.newTestKey().public.encoded); assertNull(WindowsBookRuntime.installed(), "wrong application trust key")
            Tool.writeTrust(f.classpath, f.keys.public); assertNotNull(WindowsBookRuntime.installed())
            Files.write(f.runtimeRoot.resolve("coach-auth.exe"), byteArrayOf(1), APPEND); assertNull(WindowsBookRuntime.installed(), "component bytes changed")
        }
        // Through the host: a signed set replaced after discovery is rejected before auth is even resolved.
        Fixture().use { f ->
            runSkikoComposeUiTest(size = Size(1100f, 1000f), density = Density(1f)) {
                hostWithDefaultDiscovery(f)
                openQuestion(f)
                Files.write(f.runtimeRoot.resolve("coach-iso.exe"), byteArrayOf(1), APPEND)
                clickTag("book-consent-grant")
                waitTag("book-passage-unavailable"); onNodeWithTag("book-passage-answer").assertDoesNotExist()
            }
            f.assertNothingLaunched()
        }
        println("INSTALLED_DISCOVERY bad_manifest=denied bad_signature=denied wrong_trust=denied changed_component=denied replaced_after_discovery=denied auth_reads=0 spawns=0")
    }
}
