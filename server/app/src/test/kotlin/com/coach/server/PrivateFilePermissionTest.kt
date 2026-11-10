package com.coach.server

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.net.http.*
import java.nio.file.*
import java.nio.file.attribute.*
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

/** Assert the actual platform boundary: Windows must exercise ACLs, never skip permission checks. */
internal fun assertPrivatePath(path: Path, directory: Boolean) {
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
        assertEquals(if (directory) "rwx------" else "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
    } else {
        val view = assertNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS))
        val entries = view.acl
        assertEquals(1, entries.size, "Private path retained extra ACL entries")
        val entry = entries.single()
        val user = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
        assertEquals(user, entry.principal())
        assertEquals(AclEntryType.ALLOW, entry.type())
        assertEquals(AclEntryPermission.values().toSet(), entry.permissions())
        if (directory) assertTrue(entry.flags().containsAll(setOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)))
    }
}

class PrivateFilePermissionTest {
    @Test fun boundActivitySocketIsPrivateAndRemovedOnClose() {
        val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        // macOS limits UNIX socket paths to 104 bytes; resolve its /tmp symlink before protection.
        val root = if (posix) Files.createTempDirectory(Path.of("/tmp").toRealPath(), "cps-")
            else Files.createTempDirectory("coach-private-socket-")
        val parent = root.resolve("control")
        val socket = parent.resolve("control.sock")
        try {
            ActivityCoverage(root.resolve("logs")).use { coverage ->
                try {
                    // Real bind and secureSocket(), including macOS's socket-specific chmod behavior.
                    coverage.startControl(socket)
                } catch (error: Exception) {
                    // Windows may lack UNIX support or exceed its socket path limit in a long checkout.
                    if (posix || (error !is UnsupportedOperationException && error !is java.net.SocketException)) throw error
                    assertPrivatePath(parent, true)
                    assertFalse(Files.exists(socket), "Unavailable control must not leave an endpoint")
                    println("PRIVATE SOCKET: Windows bind unavailable; parent ACL verified")
                    return@use
                }
                assertTrue(Files.exists(socket))
                assertPrivatePath(parent, true)
                assertPrivatePath(socket, false)
                println("PRIVATE SOCKET: real bind; private permissions verified")
            }
            assertFalse(Files.exists(socket), "Closing activity control must remove its socket")
        } finally { assertTrue(root.toFile().deleteRecursively(), "Private socket fixture cleanup failed") }
    }

    @Test fun installedServerProtectsFreshAndExistingSecretsBeforeServing() = withTemp { root ->
        val binary = Path.of(System.getProperty("coach.serverBinary"))
        val windows = System.getProperty("os.name").startsWith("Windows")
        val home = Files.createDirectory(root.resolve("home"))
        val reader = Files.createDirectory(root.resolve("reader"))
        Files.writeString(reader.resolve("catalog.json"), """{"schema":1,"documents":[]}""")
        val data = root.resolve("data")
        val token = data.resolve("device-token")
        var previous: String? = null
        repeat(2) {
            val port = ServerSocket(0).use { it.localPort }.also { assertTrue(it > 1024 && it !in setOf(8790, 8791, 18791)) }
            // app.bat exceeds cmd.exe's argv limit in long worktrees. Use its installed lib distribution on Windows.
            val command = if (windows) listOf(Path.of(System.getenv("JAVA_HOME") ?: System.getProperty("java.home"), "bin/java.exe").toString(),
                "-Xmx512m", "-Duser.home=$home", "-cp", binary.parent.parent.resolve("lib").toString() + "/*", "com.coach.server.MainKt")
            else listOf(binary.toString())
            val builder = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
            builder.environment().apply {
                keys.removeIf { it.startsWith("COACH_") || it.startsWith("BRIDGE_") ||
                    it in setOf("JAVA_OPTS", "APP_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS") }
                putAll(mapOf("COACH_DATA_DIR" to data.toString(), "COACH_TOKEN_FILE" to token.toString(),
                    "COACH_DEVICE_ID" to "permission-fixture", "COACH_PORT" to port.toString(),
                    "COACH_TRAFFIC" to "test", "COACH_BRIDGE_URL" to "http://127.0.0.1:1", "COACH_TAIL_HOST" to "",
                    "COACH_GENERATED_DIR" to root.resolve("generated").toString(), "COACH_MOCK_DIR" to root.resolve("mock").toString(),
                    "COACH_PACKS_DIR" to root.resolve("packs").toString(), "COACH_READER_LIBRARY" to root.resolve("reader").toString(),
                    "COACH_LOG_DIR" to root.resolve("logs").toString(), "COACH_STATE_DIR" to root.resolve("state").toString(),
                    "COACH_ACTIVITY_SOCKET" to root.resolve("state/activity.sock").toString(), "COACH_SOURCE_LOOKUP" to "false"))
                if (!windows) put("JAVA_OPTS", "-Xmx512m -Duser.home=$home")
            }
            val process = builder.start()
            val ready = CountDownLatch(1)
            val startup = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val pump = thread(isDaemon = true, name = "private-file-server-output") {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.contains("COACH loopback ready")) ready.countDown()
                        // Keep only exception classes and source locations; never retain values or response bodies.
                        Regex("(?:Exception in thread \"[^\"]+\" |Caused by: )([\\w.$]+)").find(line)
                            ?.groupValues?.get(1)?.let(startup::add)
                        Regex("\\s*at (com\\.coach\\.server\\.[\\w.$]+\\([\\w.:]+\\))").matchEntire(line)
                            ?.groupValues?.get(1)?.let(startup::add)
                    }
                }
            }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                while (!ready.await(100, TimeUnit.MILLISECONDS)) {
                    assertTrue(process.isAlive, "Isolated server exited before COACH loopback ready: ${startup.joinToString("; ")}")
                    assertTrue(System.nanoTime() < deadline, "Isolated server readiness timed out")
                }
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/hello"))
                    .timeout(Duration.ofSeconds(5)).header("X-Coach-Device", "permission-fixture")
                    .header("Authorization", "Bearer invalid-synthetic-credential-32-characters").build()
                val client = HttpClient.newHttpClient()
                assertEquals(401, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                val valid = HttpRequest.newBuilder(request.uri()).timeout(Duration.ofSeconds(5))
                    .header("X-Coach-Device", "permission-fixture")
                    .header("Authorization", "Bearer ${Files.readString(token).trim()}").build()
                assertEquals(200, client.send(valid, HttpResponse.BodyHandlers.discarding()).statusCode())
                assertPrivatePath(data, true)
                assertPrivatePath(token, false)
                assertPrivatePath(root.resolve("logs"), true)
                assertPrivatePath(root.resolve("state"), true)
                assertPrivatePath(root.resolve("state/diagnostics"), true)
                Files.list(root.resolve("logs")).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { assertPrivatePath(it, false) } }
                if (previous != null) assertTrue(previous == Files.readString(token), "Restart replaced the existing credential")
                previous = Files.readString(token)
                println("PRIVATE FILES: COACH loopback ready; invalid HTTP=401; valid HTTP=200; restart=$it; permissions verified")
            } finally {
                val children = process.descendants().toList()
                process.destroy()
                if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)) }
                children.forEach { if (it.isAlive) it.destroyForcibly() }
                pump.join(2000)
                assertFalse(process.isAlive, "Isolated server survived cleanup")
            }
        }
    }

    @Test fun permissionFailureRemovesNewEmptyObjectsAndPreservesExistingSecrets() = withTemp { root ->
        for (unsupported in listOf(false, true)) {
            val files = PrivateFiles { path, directory ->
                if (directory) privateFiles.directory(path) else {
                    if (path.fileName.toString() == "new-secret") assertEquals(0, Files.size(path), "Secret bytes preceded protection")
                    if (unsupported) throw UnsupportedOperationException("injected unsupported permissions")
                    throw IOException("injected permission failure")
                }
            }
            val fresh = root.resolve("new-parent/nested/new-secret")
            val error = assertFails { files.writeNew(fresh, "synthetic private bytes".toByteArray()) }
            assertTrue(if (unsupported) error is UnsupportedOperationException else error is PrivateFilePermissionException)
            assertFalse(Files.exists(fresh))
            assertFalse(Files.exists(root.resolve("new-parent")), "New empty parent directories survived failure")
            val existing = root.resolve("existing-secret")
            Files.writeString(existing, "synthetic preserved content")
            assertFails { files.writeNew(existing, "must not replace".toByteArray()) }
            assertTrue(Files.exists(existing))
            assertTrue(Files.readString(existing) == "synthetic preserved content")
            val retained = root.resolve("old-directory")
            Files.createDirectories(retained)
            Files.writeString(retained.resolve("ledger"), "synthetic ledger")
            val denied = PrivateFiles { _, _ -> throw IOException("injected directory permission failure") }
            assertFailsWith<PrivateFilePermissionException> { denied.directory(root.resolve("empty-parent/nested")) }
            assertFalse(Files.exists(root.resolve("empty-parent")))
            assertFailsWith<PrivateFilePermissionException> { denied.directory(retained) }
            assertTrue(Files.exists(retained.resolve("ledger")))
        }
    }

    @Test fun filesystemWithoutEitherViewFailsClosed() = withTemp { root ->
        val uri = URI.create("jar:${root.resolve("no-permissions.zip").toUri()}")
        FileSystems.newFileSystem(uri, mapOf("create" to "true")).use { fs ->
            assertFailsWith<UnsupportedOperationException> { privateFiles.directory(fs.getPath("/private")) }
            assertFalse(Files.exists(fs.getPath("/private")))
            java.io.PrintStream(ServiceLog(fs.getPath("/"), "server")).use { output ->
                assertFailsWith<UnsupportedOperationException> { output.println("synthetic log") }
            }
        }
    }

    @Test fun coverageKeepsDiskFailuresStickyAndPermissionFailuresFatal() = withTemp { root ->
        ActivityCoverage(root.resolve("disk-failure"), persist = { _, _ -> throw IOException("synthetic full disk") }).use { coverage ->
            assertTrue(coverage.snapshot().getValue("failures").jsonPrimitive.long > 0)
            assertFails { coverage.seal(coverage.snapshot()) }
        }
        val invalid = root.resolve("not-a-directory")
        Files.writeString(invalid, "synthetic preserved content")
        assertFailsWith<IOException> { privateFiles.directory(invalid) }
        ActivityCoverage(invalid).use { coverage ->
            assertTrue(coverage.snapshot().getValue("failures").jsonPrimitive.long > 0)
            assertFails { coverage.seal(coverage.snapshot()) }
        }
        assertTrue(Files.exists(invalid))
        val invalidFile = Files.createDirectory(root.resolve("not-a-file"))
        assertFailsWith<IOException> { privateFiles.secureFile(invalidFile) }
        val denied = PrivateFiles { _, _ -> throw IOException("injected permission failure") }
        assertFailsWith<PrivateFilePermissionException> { denied.secureFile(invalid) }
    }

    @Test fun accessLogPathFailureIsStickyAndCannotAuthorizeASeal() = withTemp { root ->
        val logs = privateFiles.directory(root.resolve("logs"))
        val day = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        val accessPath = Files.createDirectory(logs.resolve("access-$day.log"))
        UsageObservation(logs, root.resolve("state")).use { observation ->
            assertEquals(0, observation.coverage.snapshot().getValue("failures").jsonPrimitive.long)
            observation.access(null, "/v1/hello", "GET", 200, 1, null)
            assertTrue(observation.coverage.snapshot().getValue("failures").jsonPrimitive.long > 0)
            assertFails { observation.coverage.seal(observation.coverage.snapshot()) }
            assertTrue(Files.isDirectory(accessPath), "An existing invalid object must not be deleted")
            Files.delete(accessPath)
            observation.access(null, "/v1/hello", "GET", 200, 1, null)
            assertPrivatePath(accessPath, false)
            assertTrue(observation.coverage.snapshot().getValue("failures").jsonPrimitive.long > 0)
            assertFails { observation.coverage.seal(observation.coverage.snapshot()) }
            println("PRIVATE LOGS: path fault recorded; release refused before and after writer recovery")
        }
    }

    private fun withTemp(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("coach-private-files-")
        try { block(root) }
        finally { assertTrue(root.toFile().deleteRecursively(), "Private permission fixture cleanup failed") }
    }
}
