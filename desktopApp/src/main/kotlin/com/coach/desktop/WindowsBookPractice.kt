package com.coach.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coach.client.*
import com.coach.client.db.ClientDatabase
import com.coach.desktop.books.*
import com.coach.domain.BookPracticeSource
import com.coach.domain.LedgerJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties
import java.util.UUID

/** A user-selected course is a routing hint, never an approval or a book-to-course identity.
 * Only the authenticated, owner-mapped pose endpoint can establish that identity.
 * No PDF-to-text hash substitution, content guessing or client-created attempts is permitted here.
 */
internal class WindowsBookPracticeConnection(
    val settings: DesktopConnectionSettings,
    private val library: TextbookLibrary,
) : AutoCloseable {
    private val credentials = DesktopCredentials(settings.clientDirectory)
    private val pairing = DesktopPairing(settings, credentials)
    private val routes = library.directory.resolve("practice-course-routes.properties")
    private val sessions = mutableMapOf<String, LiveSession>()
    private val lifecycle = Mutex()
    private var closed = false
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10)).build()

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun scope(identity: DesktopIdentity) = hash(
        "${identity.serverUrl}\n${identity.credentials.deviceId}".toByteArray())
    private fun routeKey(identity: DesktopIdentity, book: String) = "${scope(identity)}.$book"
    private fun properties() = Properties().apply {
        if (Files.isRegularFile(routes)) Files.newInputStream(routes).use { load(it) }
    }

    /** Explicitly selected in this book's settings; neither this file nor a title can authorize posing. */
    @Synchronized fun selectCourse(book: String, course: String) {
        require(library.book(book).id == book)
        require(course.isNotBlank() && course.length <= 128 && course.none(Char::isISOControl))
        val identity = pairing.load() ?: error("请先连接你的 Mac。")
        require(settings.agentRunId == null) { "助手运行不能提交本人作答。" }
        val values = properties().apply { setProperty(routeKey(identity, book), course.trim()) }
        Files.createDirectories(library.directory)
        val temporary = Files.createTempFile(library.directory, "practice-route-", ".properties")
        try {
            Files.newOutputStream(temporary).use { values.store(it, "Routing hints only; server approval is required") }
            Files.move(temporary, routes, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    suspend fun prepare(book: Textbook, lesson: BookLesson, practice: Practice): BookPracticeSession? = withContext(Dispatchers.IO) { lifecycle.withLock {
        if (closed) return@withContext null
        if (settings.agentRunId != null) return@withContext null
        val identity = pairing.load() ?: return@withContext null
        requireDevelopmentServer(settings, identity.serverUrl)
        val course = synchronized(this@WindowsBookPracticeConnection) {
            properties().getProperty(routeKey(identity, book.id))
        } ?: return@withContext null
        // Import IDs are raw file hashes. An extracted server text blob is never a replacement for a PDF ID.
        val original = library.directory.resolve("${book.id}.${book.format}")
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(original).use { input ->
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != book.id) return@withContext null
        val block = lesson.blocks.singleOrNull { !it.heading && it.text.contains(practice.reference) }
            ?: return@withContext null
        val source = BookPracticeSource(book.id, lesson.id, block.page, block.start, block.end, practice.reference)
        val path = URLEncoder.encode(course, Charsets.UTF_8).replace("+", "%20")
        val request = HttpRequest.newBuilder(URI("${identity.serverUrl}/v1/courses/$path/book-practice-pose"))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer ${identity.credentials.token}")
            .header("X-Coach-Device", identity.credentials.deviceId).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(LedgerJson.encodeToString(source))).build()
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() == 401) {
            credentials.clear(identity.serverUrl, identity.credentials)
            return@withContext null
        }
        if (response.statusCode() == 409) throw BookPracticeUnavailableException()
        if (response.statusCode() != 200) return@withContext null
        // The response is not injected into SQLite. Normal authenticated ledger synchronization owns facts.
        val key = "${scope(identity)}:${hash(identity.credentials.token.toByteArray())}:$course"
        val live = sessions.getOrPut(key) { LiveSession(identity, course) }
        live.syncLocked()
        live.session
    } }

    private inner class LiveSession(private val identity: DesktopIdentity, course: String) : AutoCloseable {
        private val directory = settings.clientDirectory.resolve("book-practice").resolve(scope(identity))
            .resolve(hash(course.toByteArray())).also(Files::createDirectories)
        private val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("ledger.sqlite")}", Properties(), ClientDatabase.Schema)
        private val transport = HttpLedgerTransport(identity.serverUrl, identity.credentials.deviceId, identity.credentials.token)
        private val engine = SyncEngine(ClientDatabase(driver), identity.credentials.deviceId, transport, listOf(course),
            { UUID.randomUUID().toString() }, ::hash)
        val session = BookPracticeSession(engine, course, synchronize = {
            lifecycle.withLock { check(!closed); syncLocked() }
        })
        suspend fun syncLocked() {
            try { engine.syncOnce() }
            catch (failure: DeviceAuthorizationException) {
                credentials.clear(identity.serverUrl, identity.credentials)
                throw failure
            }
        }
        override fun close() { transport.close(); driver.close() }
    }

    // Join in-flight network synchronization before closing the session databases.
    override fun close() = runBlocking { lifecycle.withLock {
        closed = true
        sessions.values.forEach { it.close() }; sessions.clear()
    } }
}
