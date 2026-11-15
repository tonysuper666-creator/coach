package com.coach.desktop.reader

import com.coach.client.PairedCredentials
import com.coach.core.LedgerActor
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.desktop.DesktopPairing
import com.coach.server.ReaderLibrary
import com.coach.server.ledgerRoutes
import com.coach.server.readerRoutes
import com.coach.store.LedgerStore
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.Collections
import kotlin.test.*

/** Real Ktor routes, LedgerStore/SQLite, minted pairings and invented PDFs; never a response override. */
internal class PairedDeviceDraftFixture(label: String) : AutoCloseable {
    // Default java.io.tmpdir on macOS is reached through the /var symlink; resolve it so the fixture's
    // protected directories are real paths and the pairing guard tests what it means to test.
    val root = Files.createTempDirectory("paired-draft-$label-").toRealPath()
    val evidence = Path.of("build", "paired-device-drafts", label).also { Files.createDirectories(it) }
    private val library = root.resolve("library").also { Files.createDirectories(it) }
    private val database = root.resolve("database").also { Files.createDirectories(it) }
    private val store = LedgerStore(database)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val actor = LedgerActor(scope, store)
    private val admin = "synthetic-paired-draft-admin-0123456789"
    private val secrets = mutableListOf(admin)
    val wire = Collections.synchronizedList(mutableListOf<JsonObject>())
    private val client = HttpClient.newHttpClient()
    var version = ""; private set
    init { store.enroll("desktop", admin); writePdf("Alpha beta gamma") }
    private var engine = server(0)
    val url: String
    init {
        // ReaderLibrary's constructor is internal to the server module. Only fixture construction uses reflection.
        engine.start(false)
        url = "http://127.0.0.1:${runBlocking { engine.engine.resolvedConnectors().single().port }}"
        check(URI(url).port !in setOf(18791, 8790, 8791, 3790, 19540))
    }
    private fun server(port: Int) = embeddedServer(Netty, host = "127.0.0.1", port = port) {
        val readerLibrary = ReaderLibrary::class.java.getDeclaredConstructor(Path::class.java, String::class.java)
            .newInstance(library, poppler())
        intercept(ApplicationCallPipeline.Monitoring) {
            try { proceed() } finally {
                wire += buildJsonObject {
                    put("method", call.request.httpMethod.value); put("path", call.request.path())
                    put("status", call.response.status()?.value ?: 200)
                    put("device", call.request.headers["X-Coach-Device"])
                }
            }
        }
        val pushes = MutableSharedFlow<String>(extraBufferCapacity = 32)
        ledgerRoutes(actor, pushes); readerRoutes(actor, pushes, readerLibrary)
    }
    private fun poppler(): String {
        if (!System.getProperty("os.name").startsWith("Windows")) return ""
        val base = Path.of("D:/agent/tools/poppler")
        if (!Files.isDirectory(base)) return ""
        return Files.walk(base).use { paths -> paths.filter { it.fileName.toString() == "pdftoppm.exe" }.findFirst().map { it.parent.toString() }.orElse("") }
    }
    private fun writePdf(text: String) {
        val pdf = library.resolve("synthetic.pdf")
        PDDocument().use { document ->
            repeat(2) {
                val page = PDPage(); document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText(); content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 18f)
                    content.newLineAtOffset(40f, 700f); content.showText(text); content.newLineAtOffset(0f, -30f)
                    content.showText("Delta epsilon"); content.endText()
                }
            }
            document.save(pdf.toFile())
        }
        version = digest(Files.readAllBytes(pdf))
        val entries = listOf("C1", "C2").map { course -> buildJsonObject {
            put("id", "same-doc"); put("course", course); put("title", "Synthetic textbook")
            put("file", "synthetic.pdf"); put("sha256", version); put("pages", 2); put("kind", "textbook")
        } }
        Files.writeString(library.resolve("catalog.json"), buildJsonObject { put("schema", 1); put("documents", JsonArray(entries)) }.toString())
    }
    fun settings(home: Path = root.resolve("home")) = DesktopConnectionSettings(home, { if (it == "COACH_URL") url else null }, mac = false, property = { null })
    fun request(method: String, path: String, credentials: PairedCredentials? = null, body: JsonObject? = null): HttpResponse<String> {
        val auth = credentials ?: PairedCredentials("desktop", admin)
        val builder = HttpRequest.newBuilder(URI(url + path)).header("X-Coach-Device", auth.deviceId)
            .header("Authorization", "Bearer ${auth.token}").header("Content-Type", "application/json")
        return client.send(builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }
    fun pair(settings: DesktopConnectionSettings, name: String): DesktopIdentity {
        val code = request("POST", "/v1/pairing-code").also { assertEquals(200, it.statusCode()) }
        return runBlocking { DesktopPairing(settings).pair(url, Json.parseToJsonElement(code.body()).jsonObject["code"]!!.jsonPrimitive.content, name) }
            .also { secrets += it.credentials.token }
    }
    fun revoke(identity: DesktopIdentity) { assertEquals(204, request("DELETE", "/v1/devices/${identity.credentials.deviceId}").statusCode()) }
    fun updateFromOtherDevice(identity: DesktopIdentity, draft: NoteDraft, revision: Long): Long {
        val body = buildJsonObject {
            put("id", draft.id); put("version", draft.version); put("pdfPage", draft.pdfPage)
            put("start", draft.start); put("end", draft.end); put("quote", draft.quote); put("textSha256", draft.textSha256)
            put("note", "Other legitimate paired device"); put("deleted", false)
            put("baseRevision", revision); put("mutationId", "rival-${System.nanoTime()}")
        }
        val response = request("POST", "/v1/courses/C1/reader/same-doc/notes", identity.credentials, body)
        assertEquals(200, response.statusCode())
        return Json.parseToJsonElement(response.body()).jsonObject["revision"]!!.jsonPrimitive.long
    }
    fun replaceVersion() {
        val port = URI(url).port
        runBlocking { engine.stopSuspend(0, 500) }
        writePdf("Changed synthetic content")
        engine = server(port).apply { start(false) }
    }
    fun notePosts(device: String) = synchronized(wire) { wire.filter { it["method"]?.jsonPrimitive?.content == "POST" && it["path"]?.jsonPrimitive?.content?.endsWith("/notes") == true && it["device"]?.jsonPrimitive?.content == device } }
    fun snapshot(directory: Path): Map<String, String> = if (!Files.isDirectory(directory)) emptyMap() else Files.walk(directory).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().associate { directory.relativize(it).toString() to digest(Files.readAllBytes(it)) }
    }
    fun assertNoSecrets(directory: Path) {
        var files = 0
        if (Files.isDirectory(directory)) Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { file ->
            files++
            val content = Files.readString(file)
            for (secret in secrets) {
                assertFalse(content.contains(secret), "token leaked into draft file")
                assertFalse(content.contains(digest(secret.toByteArray())), "token hash leaked into draft file")
            }
            assertFalse(content.contains("Authorization", ignoreCase = true))
        } }
        Files.writeString(evidence.resolve("token-scan.json"), """{"files":$files,"tokenHits":0,"tokenHashHits":0,"authorizationHits":0}""")
    }
    fun sqliteNotes(): List<String> = DriverManager.getConnection("jdbc:sqlite:${database.resolve("coach.sqlite")}").use { db -> db.createStatement().use { st ->
        st.executeQuery("SELECT payload FROM events WHERE type='reader_note_saved' ORDER BY seq").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    } }
    override fun close() {
        try {
            assertNoSecrets(root.resolve("home/Coach/client/reader-drafts"))
            Files.writeString(evidence.resolve("wire.json"), JsonArray(synchronized(wire) { wire.toList() }).toString())
            Files.writeString(evidence.resolve("sqlite-reader-events.json"), JsonArray(sqliteNotes().map { Json.parseToJsonElement(it) }).toString())
        } finally {
            runBlocking { engine.stopSuspend(0, 500); actor.close() }; scope.cancel()
        }
    }
    companion object {
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
