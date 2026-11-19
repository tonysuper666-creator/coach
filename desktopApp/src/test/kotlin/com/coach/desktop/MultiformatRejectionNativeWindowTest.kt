package com.coach.desktop

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Assume.assumeTrue
import java.awt.GraphicsEnvironment
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.test.*

/** 2026-11-19 rejection round: what a reader sees when the file cannot be opened.
 *
 *  The import checklist asked for a human reason and a next step on unopenable material.
 *  The offline suite already checks the exception text, but not the window. Here a truncated
 *  DOCX (`fresh/` in `books/formats`), a three-page PDF cut at 60%, and a password PDF built
 *  in-process with PDFBox are imported into a brand-new empty library rendered in each theme.
 *  The message must name the problem and the fix, no catalog entry or stored original may
 *  remain, and a good file imported afterwards must still land. No model, network, or real books.
 */
@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class, ComposeToolingApi::class)
class MultiformatRejectionNativeWindowTest {
    private data class Bad(val file: String, val hint: String, val cause: String)

    private val checkedIn = listOf(
        Bad("fresh-broken.docx", "损坏", "DOCX 只留下 ZIP 头"),
    )

    private val bad = checkedIn + listOf(
        Bad("fresh-truncated.pdf", "损坏", "三页 PDF 截到 60%，尾部交叉表丢失"),
        Bad("fresh-password.pdf", "密码保护", "PDF 设了阅读密码"),
    )

    /** Runs off the console too: fixes the exact wording before a window run is spent. */
    @Test fun damagedAndPasswordProtectedFilesGiveActionableErrorsWithoutPartialImports() {
        val root = Files.createTempDirectory("coach-reject-parse-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val samples = listOf(
                copySample(root, "fresh-broken.docx"),
                truncatedPdf(root.resolve("fresh-truncated.pdf")),
                passwordPdf(root.resolve("fresh-password.pdf")),
            ).zip(bad)
            for ((path, sample) in samples) {
                val error = assertFailsWith<IllegalArgumentException>(sample.file) { library.importBook(path) }
                assertContains(error.message.orEmpty(), sample.hint, message = sample.file)
                assertContains(error.message.orEmpty(), "请", message = "${sample.file}: 提示里要有下一步")
                println("REJECT_PARSE file=${sample.file} bytes=${Files.size(path)} message=${error.message}")
            }
            assertTrue(library.catalog().isEmpty(), "rejected files must not join the catalog")
            assertTrue(Files.list(library.directory).use { paths ->
                paths.noneMatch { it.fileName.toString().startsWith(".import-") || it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.(pdf|docx)")) }
            }, "rejected files must not leave a staging directory or a stored original")
        } finally { root.toFile().deleteRecursively() }
    }

    /** A real window and a brand-new empty library per theme. The window reaches the import
     *  through `controller.importDroppedBooks`, the same call `WindowsTextbookHost` makes from the
     *  drop callback after `drafts.flush() && exams.flush()` (both no-ops with nothing queued).
     *
     *  The OS drag gesture is deliberately not asserted here. The merged #489 window test
     *  (`MultiformatFreshMaterialNativeWindowTest`) already drags 7 files per theme through the
     *  real window; re-run on this box on 2026-11-19 it fails at its first drop with the
     *  controller untouched and the shelf still empty, so a gesture failure would measure the
     *  box, not the rejection path.
     */
    @Test fun encryptedPdfAndCorruptDocsShowReasonAndLeaveShelfEmptyInBothThemes() {
        assumeTrue(System.getProperty("coach.nativeFormats") == "true")
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("coach-native-reject-")
        val previousTheme = System.getProperty("coach.windows.theme")
        try {
            val files = checkedIn.associate { it.file to copySample(root, it.file) } + mapOf(
                "fresh-truncated.pdf" to truncatedPdf(root.resolve("fresh-truncated.pdf")),
                "fresh-password.pdf" to passwordPdf(root.resolve("fresh-password.pdf")),
            )
            val good = copySample(root, "fresh-notes.html")
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                System.setProperty("coach.windows.theme", theme)
                // Each theme gets its own brand-new empty library; the bad files never share one
                // with the good import below, so an accidental shelf entry is unambiguous.
                val library = TextbookLibrary(root.resolve("library-$theme"))
                val controller = TextbookController(library)
                runBlocking { controller.load() }
                assertTrue(controller.state.value.catalog.isEmpty(), "$theme: library must start empty")
                val window = edt { ComposeWindow().apply {
                    title = "Coach · 坏文件提示验收 · 本地合成讲解"
                    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    setBounds(screen.x + 20, screen.y + 20, minOf(1600, screen.width - 40), minOf(950, screen.height - 150))
                    setContent { WindowsTextbookHost(controller, learningSupport = UnavailableLearning) { null } }
                    isVisible = true
                } }
                try {
                    waitFor("initial empty shelf") { node(window, "upload-textbook") != null }
                    shot(window, dark, "shelf-empty")
                    // The window drives the same entry point the drop callback uses
                    // (`WindowsTextbookHost` runs drafts.flush() && exams.flush() before calling
                    // importDroppedBooks; with nothing queued both return true untouched).
                    for (sample in bad) {
                        // Re-read the shelf from disk so the previous message cannot satisfy the wait,
                        // and so the catalog shown here is what actually survived the rejection.
                        runBlocking { controller.load() }
                        assertEquals(0, controller.state.value.catalog.size, "$theme: ${sample.file} 之前书架应为空")
                        runBlocking { controller.importDroppedBooks(listOf(files.getValue(sample.file))) }
                        waitFor("rejection ${sample.file}") { controller.state.value.let { !it.busy && it.error != null } }
                        val message = controller.state.value.error.orEmpty()
                        assertContains(message, sample.hint, message = "${sample.file}: ${sample.cause}")
                        assertContains(message, "请", message = "${sample.file}: 提示里要有下一步")
                        assertEquals(0, controller.state.value.catalog.size, "$theme: ${sample.file} 不能留在书架")
                        assertTrue(controller.state.value.prompt == null, "$theme: ${sample.file} 不该进入已导入提示")
                        shot(window, dark, "${sample.file}-rejected")
                        // The state already carries the message; wait for the frame that paints it.
                        val rendered = run {
                            val deadline = System.nanoTime() + 10_000_000_000L
                            var texts = emptyList<String>()
                            while (System.nanoTime() < deadline) {
                                texts = edt { textNodes(window) }
                                texts.firstOrNull { it.contains(message) }?.let { return@run it }
                                Thread.sleep(150)
                            }
                            println("WINDOW_TEXTS theme=$theme file=${sample.file} texts=${texts.filter { it.isNotBlank() }}")
                            null
                        }
                        assertNotNull(rendered, "$theme: ${sample.file} 的提示没有显示在窗口里：$message")
                        println("REJECT_WINDOW theme=$theme file=${sample.file} catalog=0 unchanged=true path=importDroppedBooks message=$message rendered=$rendered")
                    }
                    // The same window and library must still accept a good book: rejection leaves
                    // nothing behind that blocks a later import.
                    runBlocking { controller.load() }
                    runBlocking { controller.importDroppedBooks(listOf(good)) }
                    waitFor("good ${good.fileName}") {
                        controller.state.value.let { !it.busy && it.prompt == BookPrompt.IMPORTED && it.catalog.size == 1 }
                    }
                    assertNull(controller.state.value.error, "$theme: 拒绝之后好文件仍应能导入")
                    shot(window, dark, "shelf-after-rejections")
                    val stored = runBlocking { controller.library.catalog() }
                    assertEquals(1, stored.size, "$theme: 书架上只能剩那本好教材")
                    println("REJECT_WINDOW_RECOVER theme=$theme file=fresh-notes.html catalog=1 ok=true")
                } finally { edt { window.dispose() } }
            }
        } finally {
            if (previousTheme == null) System.clearProperty("coach.windows.theme") else System.setProperty("coach.windows.theme", previousTheme)
            root.toFile().deleteRecursively()
        }
    }

    private object UnavailableLearning : BookLearningSupport {
        override suspend fun explain(request: BookExplanationRequest) = BookLearningReply.Unavailable("坏文件验收不用整节讲解。")
        override suspend fun ask(request: BookPassageQuestion) = BookLearningReply.Unavailable("坏文件验收不用提问。")
    }

    /** Runtime samples, like the offline suite: no damaged binary and no password in Git.
     *  A three-page PDF cut at 60% keeps the header but loses the tail xref: the kind of file
     *  left by an interrupted download. Cutting only the last 5–20% is recoverable by PDFBox
     *  and correctly imports all three pages, so the sample must be cut past that point. */
    private fun truncatedPdf(target: Path): Path {
        val bytes = PDDocument().use { pdf ->
            repeat(3) { i ->
                val page = PDPage().also { pdf.addPage(it) }
                PDPageContentStream(pdf, page).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 14f)
                    cs.newLineAtOffset(72f, 700f); cs.showText("Coastal notes page ${i + 1}"); cs.endText()
                }
            }
            ByteArrayOutputStream().also { pdf.save(it) }.toByteArray()
        }
        Files.write(target, bytes.copyOf(bytes.size * 60 / 100))
        return target
    }

    private fun passwordPdf(target: Path): Path {
        PDDocument().use { pdf ->
            pdf.addPage(PDPage())
            pdf.protect(StandardProtectionPolicy("owner-password", "reader-password", AccessPermission()).apply { encryptionKeyLength = 128 })
            pdf.save(target.toFile())
        }
        return target
    }

    private fun copySample(root: Path, name: String): Path = root.resolve(name).also { file ->
        checkNotNull(javaClass.getResourceAsStream("/books/formats/fresh/$name")).use {
            Files.copy(it, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun textNodes(window: ComposeWindow): List<String> = nodes(window).flatMap { n ->
        n.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
    }
    private fun nodes(window: ComposeWindow): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        val owners = window.semanticsOwners + ownedDialogs(window).flatMap { it.semanticsOwners }
        return owners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun ownedDialogs(window: java.awt.Window): List<ComposeDialog> = window.ownedWindows
        .filter { it.isVisible }.flatMap { listOfNotNull(it as? ComposeDialog) + ownedDialogs(it) }
    private fun node(window: ComposeWindow, tag: String) = edt { nodes(window).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag } }
    private fun shot(window: ComposeWindow, dark: Boolean, name: String) {
        Thread.sleep(350)
        edt {
            window.renderImmediately()
            val path = Path.of("build/screens/formats-native-reject", if (dark) "dark" else "light")
            Files.createDirectories(path)
            VerifyHooks.capture(window, path.resolve("$name.png").toFile())
            ownedDialogs(window).forEachIndexed { index, dialog ->
                dialog.renderImmediately()
                VerifyHooks.capture(dialog, path.resolve("$name-dialog-$index.png").toFile())
            }
        }
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 25_000_000_000L
        while (!condition()) { check(System.nanoTime() < deadline) { "Timed out: $label" }; Thread.sleep(100) }
    }
    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
