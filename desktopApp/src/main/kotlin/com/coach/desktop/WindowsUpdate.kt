package com.coach.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.ShortByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal data class WindowsRelease(val version: String, val file: String, val size: Long, val sha256: String)

internal interface UpdateKernel32 : StdCallLibrary {
    fun GetCurrentProcess(): Pointer
    fun IsWow64Process2(process: Pointer, processMachine: ShortByReference, nativeMachine: ShortByReference): Boolean
}

internal fun windowsUpdateArch(kernel: UpdateKernel32): String? {
    val nativeMachine = ShortByReference()
    // The second output describes the host even when the current JVM runs under Prism.
    if (!kernel.IsWow64Process2(kernel.GetCurrentProcess(), ShortByReference(), nativeMachine)) return null
    return when (nativeMachine.value.toUShort().toInt()) {
        0x8664 -> "x64"   // IMAGE_FILE_MACHINE_AMD64
        0xaa64 -> "arm64" // IMAGE_FILE_MACHINE_ARM64
        else -> null
    }
}

internal class WindowsUpdate(private val manifest: URI, private val installed: String, private val arch: String) {
    init {
        require(manifest.scheme == "https" && manifest.host != null && manifest.rawUserInfo == null &&
            manifest.rawQuery == null && manifest.rawFragment == null && manifest.path.endsWith("/latest.json"))
    }

    companion object {
        fun installed(): WindowsUpdate? {
            if (!System.getProperty("os.name").startsWith("Windows") || System.getProperty("jpackage.app-path") == null) return null
            val version = System.getProperty("jpackage.app-version") ?: return null
            val url = WindowsUpdate::class.java.getResourceAsStream("/windows-update-channel.txt")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText().trim() } ?: return null
            val arch = try {
                windowsUpdateArch(Native.load("kernel32", UpdateKernel32::class.java))
            } catch (_: UnsatisfiedLinkError) {
                null
            } ?: return null
            return WindowsUpdate(URI(url), version, arch)
        }
    }

    private fun versionNumber(version: String): Long? {
        if (!version.matches(Regex("""[1-9][0-9]{0,2}\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,4})"""))) return null
        val (major, minor, patch) = version.split('.').map(String::toLong)
        return if (major <= 255 && minor <= 255 && patch <= 65535) (major * 256 + minor) * 65536 + patch else null
    }

    internal fun candidate(json: String): WindowsRelease? {
        val manifestData = Json.parseToJsonElement(json).jsonObject
        val packages = manifestData["packages"]?.jsonArray?.map { it.jsonObject } ?: listOf(manifestData)
        val data = packages.singleOrNull { it.getValue("arch").jsonPrimitive.content == arch } ?: return null
        fun field(name: String) = data.getValue(name).jsonPrimitive.content
        val version = field("version")
        if ((versionNumber(version) ?: return null) <= (versionNumber(installed) ?: return null)) return null
        val file = field("file")
        require(file.matches(Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,180}\.msi""")))
        val size = field("size").toLong()
        require(size in 1..(2L shl 30))
        val hash = field("sha256").lowercase()
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return WindowsRelease(version, file, size, hash)
    }

    private fun <T> read(uri: URI, body: (InputStream) -> T): T {
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            check(connection.responseCode == 200)
            return connection.inputStream.use(body)
        } finally {
            connection.disconnect()
        }
    }

    fun check(): WindowsRelease? = read(manifest) { stream ->
        val bytes = stream.readNBytes(16385)
        require(bytes.size <= 16384)
        candidate(bytes.toString(Charsets.UTF_8))
    }

    suspend fun download(release: WindowsRelease, directory: Path): Path {
        val context = currentCoroutineContext()
        return read(manifest.resolve(release.file)) { stream ->
            stage(release, directory, stream) { context.ensureActive() }
        }
    }

    internal fun stage(release: WindowsRelease, directory: Path, stream: InputStream, checkActive: () -> Unit): Path {
        Files.createDirectories(directory)
        val partial = Files.createTempFile(directory, "download-", ".part")
        try {
            val input = BufferedInputStream(stream)
            input.mark(8)
            val magic = byteArrayOf(-48, -49, 17, -32, -95, -79, 26, -31) // MSI compound file header
            require(input.readNBytes(8).contentEquals(magic))
            input.reset()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var size = 0L
            Files.newOutputStream(partial).use { output ->
                while (true) {
                    checkActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    require(size <= release.size)
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            }
            require(size == release.size && digest.digest().joinToString("") { "%02x".format(it) } == release.sha256)
            checkActive()
            // Retain a verified source for Windows Installer repair. Partial transfers are never reused.
            return Files.move(partial, directory.resolve(release.sha256 + ".msi"), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(partial)
        }
    }
}
