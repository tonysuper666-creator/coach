package com.coach.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.coach.client.CourseHttp
import com.coach.core.LedgerActor
import com.coach.server.ReaderLibrary
import com.coach.server.readerRoutes
import com.coach.store.LedgerStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import kotlin.test.*

private const val token = "invented-isolated-token-01234567890123456789"
private inline fun <T> CourseHttp.use(block: (CourseHttp) -> T): T = try { block(this) } finally { close() }
private fun pdf(label: String): ByteArray {
    val stream = "BT /F1 18 Tf 30 300 Td ($label) Tj ET"
    val objects = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [4 0 R] /Count 1 >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 400] /Resources << /Font << /F1 3 0 R >> >> /Contents 5 0 R >>",
        "<< /Length ${stream.length} >>\nstream\n$stream\nendstream")
    val out = StringBuilder("%PDF-1.4\n"); val offsets = mutableListOf(0)
    objects.forEachIndexed { index, obj -> offsets += out.length; out.append("${index + 1} 0 obj\n$obj\nendobj\n") }
    val xref = out.length
    out.append("xref\n0 6\n0000000000 65535 f \n")
    offsets.drop(1).forEach { out.append("%010d 00000 n \n".format(it)) }
    out.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return out.toString().toByteArray(Charsets.US_ASCII)
}
private fun writeCatalog(dir: Path, label: String) {
    val docs = listOf("C1" to label, "C2" to "Synthetic second course").mapIndexed { i, (course, title) ->
        val bytes = pdf(title); val filename = "book$i.pdf"; Files.write(dir.resolve(filename), bytes)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        buildJsonObject { put("id", "book"); put("course", course); put("title", title); put("file", filename); put("sha256", sha); put("pages", 1); put("kind", "textbook"); put("printedPageOffset", 0) }
    }
    Files.writeString(dir.resolve("catalog.json"), buildJsonObject { put("schema", 1); put("documents", JsonArray(docs)) }.toString())
}
private fun popplerReady(dir: Path) = listOf("pdftoppm.exe", "pdftotext.exe", "pdfinfo.exe").all { name ->
    runCatching { Files.isRegularFile(dir.resolve(name)) }.getOrDefault(false)
}

/** Non-Windows keeps PATH lookup. Windows passes a directory that contains the three Poppler executables. */
private fun readerToolsDirectory(): String {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return ""
    System.getenv("COACH_POPPLER_BIN")?.takeIf { popplerReady(Path.of(it)) }?.let { return it }
    val onPath = (System.getenv("PATH") ?: "").split(java.io.File.pathSeparator).firstNotNullOfOrNull { entry ->
        runCatching { Path.of(entry) }.getOrNull()?.takeIf(::popplerReady)
    }
    if (onPath != null) return onPath.toString()
    val root = Path.of("D:/agent/tools/poppler")
    if (Files.isDirectory(root)) {
        val found = Files.walk(root, 8).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().equals("pdftoppm.exe", ignoreCase = true) }
                .map { it.parent }.filter(::popplerReady).map { it.toString() }.max(Comparator.naturalOrder()).orElse(null)
        }
        if (found != null) return found
    }
    error("Windows Poppler directory is missing; this test does not skip a missing renderer")
}

private class Service(private val root: Path) : AutoCloseable {
    val store = LedgerStore(Files.createDirectories(root.resolve("db")))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val actor = LedgerActor(scope, store)
    private val library = ReaderLibrary::class.java.getDeclaredConstructor(Path::class.java, String::class.java).let { it.isAccessible = true; it.newInstance(root.resolve("library"), readerToolsDirectory()) }
    private val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { readerRoutes(actor, MutableSharedFlow(extraBufferCapacity = 64), library) }
    val base: String
    init {
        store.enroll("phone", token); store.enroll("desktop", token)
        server.start(false)
        base = "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().single().port }}"
    }
    fun client(course: String, device: String = "phone") = CourseHttp(base, device, token, course)
    override fun close() { runBlocking { server.stopSuspend(0, 500); actor.close() }; scope.cancel() }
}

@OptIn(ExperimentalTestApi::class)
class PrivateReaderIntegrationTest {
    @Test fun realServicePersistsConflictsRestartAndVersionAcrossTwoCourses() {
        val root = Files.createTempDirectory("coach-reader-ui-integration")
        try {
            Files.createDirectories(root.resolve("library")); writeCatalog(root.resolve("library"), "Synthetic first course")
            Service(root).use { service ->
                service.client("C1").use { phone -> service.client("C1", "desktop").use { desktop ->
                    runComposeUiTest {
                        setContent { CoachTheme(false) { Surface { Box(Modifier.size(600.dp, 900.dp)) { PrivateReaderScreen(phone.reader) {} } } } }
                        waitUntil(timeoutMillis = 15000) { onAllNodesWithTag("private-book-book").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("private-book-book").performClick()
                        waitUntil(timeoutMillis = 15000) { onAllNodesWithTag("private-page-1").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("private-open-notes").performClick()
                        waitUntil(timeoutMillis = 15000) { onAllNodesWithTag("private-note-text").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("private-note-text").performTextInputSelection(TextRange(0, 9))
                        onNodeWithTag("private-note-add").performClick()
                        onNodeWithTag("private-note-editor").performTextInput("phone note")
                        waitUntil(timeoutMillis = 15000) { runBlocking { phone.reader.state("book").body?.get("notes")?.jsonArray?.singleOrNull()?.jsonObject?.get("note")?.jsonPrimitive?.content == "phone note" } }
                        waitUntil(timeoutMillis = 15000) { onAllNodesWithTag("private-note-editor-close").fetchSemanticsNodes().firstOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false }
                        onNodeWithTag("private-note-editor-close").performClick()
                        val saved = runBlocking { desktop.reader.state("book").body!!.getValue("notes").jsonArray.single().jsonObject }
                        val remoteBody = JsonObject(saved.filterKeys { it in setOf("id", "version", "pdfPage", "textSha256", "start", "end", "quote") } + mapOf(
                            "note" to JsonPrimitive("desktop edit"), "deleted" to JsonPrimitive(false), "baseRevision" to saved.getValue("revision"), "mutationId" to JsonPrimitive("desktop-edit")))
                        assertTrue(runBlocking { desktop.reader.saveNote("book", remoteBody).ok })
                        onNodeWithTag("private-note-edit-${saved.getValue("id").jsonPrimitive.content}").performClick()
                        onNodeWithTag("private-note-editor").performTextReplacement("phone edit")
                        waitUntil(timeoutMillis = 15000) { onAllNodesWithTag("private-note-conflict-load").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("private-note-conflict-load").performClick()
                        waitUntil(timeoutMillis = 15000) { onAllNodesWithTag("private-note-keep-local").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithText("已保存的批注：desktop edit").assertExists()
                        onNodeWithTag("private-note-keep-local").performClick()
                        waitUntil(timeoutMillis = 15000) { runBlocking { desktop.reader.state("book").body!!.getValue("notes").jsonArray.single().jsonObject.getValue("note").jsonPrimitive.content == "phone edit" } }
                    }
                } }
                service.client("C2").use { other ->
                    assertTrue(runBlocking { other.reader.state("book").body!!.getValue("notes").jsonArray.isEmpty() })
                    runComposeUiTest {
                        setContent { CoachTheme(false) { Surface { Box(Modifier.size(600.dp, 900.dp)) { PrivateReaderScreen(other.reader) {} } } } }
                        waitUntil(timeoutMillis = 15000) { onAllNodesWithTag("private-book-book").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("private-book-book").performClick()
                        waitUntil(timeoutMillis = 15000) { onAllNodesWithTag("private-page-1").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("private-open-notes").performClick()
                        waitUntil(timeoutMillis = 15000) { onAllNodesWithTag("private-note-text").fetchSemanticsNodes().isNotEmpty() }
                        onNodeWithTag("private-note-text").performTextInputSelection(TextRange(0, 9))
                        onNodeWithTag("private-note-add").performClick()
                        onNodeWithTag("private-note-editor").performTextInput("other course note")
                        waitUntil(timeoutMillis = 15000) { runBlocking { other.reader.state("book").body!!.getValue("notes").jsonArray.singleOrNull()?.jsonObject?.get("note")?.jsonPrimitive?.content == "other course note" } }
                    }
                }
            }
            Service(root).use { restarted -> restarted.client("C1").use { phone ->
                runComposeUiTest {
                    setContent { CoachTheme(false) { Surface { Box(Modifier.size(600.dp, 900.dp)) { PrivateReaderScreen(phone.reader) {} } } } }
                    waitUntil(timeoutMillis = 15000) { onAllNodesWithTag("private-book-book").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("private-book-book").performClick()
                    waitUntil(timeoutMillis = 15000) { onAllNodesWithTag("private-page-1").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("private-open-notes").performClick()
                    waitUntil(timeoutMillis = 15000) { onAllNodesWithText("我的批注：phone edit").fetchSemanticsNodes().isNotEmpty() }
                }
            } }
            writeCatalog(root.resolve("library"), "Synthetic changed book")
            Service(root).use { changed -> changed.client("C1").use { phone ->
                runComposeUiTest {
                    setContent { CoachTheme(false) { Surface { Box(Modifier.size(600.dp, 900.dp)) { PrivateReaderScreen(phone.reader) {} } } } }
                    waitUntil(timeoutMillis = 15000) { onAllNodesWithTag("private-book-book").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("private-book-book").performClick()
                    waitUntil(timeoutMillis = 15000) { onAllNodesWithTag("private-page-1").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithTag("private-open-notes").performClick()
                    waitUntil(timeoutMillis = 15000) { onAllNodesWithTag("private-note-stale").fetchSemanticsNodes().isNotEmpty() }
                    onNodeWithText("我的批注：phone edit").assertExists()
                }
            } }
        } finally { root.toFile().deleteRecursively() }
    }
}
