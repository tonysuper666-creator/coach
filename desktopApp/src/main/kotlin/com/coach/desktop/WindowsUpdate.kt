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

internal enum class UpdateFailure { INTERRUPTED, CHECKSUM, SPACE }

internal class UpdateFailed(val failure: UpdateFailure, message: String) : IllegalStateException(message)

internal const val UPDATE_INTERRUPTED = "下载中断了，没有更换版本。可以继续使用现在这个版本。"
internal const val UPDATE_CHECKSUM = "安装包校验没有通过，没有更换版本。可以继续使用现在这个版本。"
internal const val UPDATE_SPACE = "磁盘空间不够，没有更换版本。可以继续使用现在这个版本。"
internal const val UPDATE_FAILED = "更新没有完成，没有更换版本。可以继续使用现在这个版本。"
internal const val UPDATE_NOT_SAVED = "学习进度还没有保存。可以继续学习，或重试更新。"
internal const val UPDATE_INSTALLER = "更新没有开始，没有更换版本。可以继续使用现在这个版本。"

internal fun updateFailureMessage(error: Throwable): String = when (error) {
    is UpdateFailed -> error.message ?: UPDATE_FAILED
    is java.util.concurrent.CancellationException -> UPDATE_INTERRUPTED
    else -> UPDATE_FAILED
}

internal fun runningAppVersion(): String =
    System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("coach.app.version")?.takeIf { it.isNotBlank() }
        ?: "dev"

/**
 * Verified installers live beside the product directory, not inside it.
 * Uninstalling LearningCoach deletes %LOCALAPPDATA%\LearningCoach. An MSI stored there
 * disappears while msiexec is still reading it (error 1316) and the app is left uninstalled.
 * A temp directory is also wrong: cleanup can remove the only copy of the previous package.
 */
internal fun windowsUpdatesDirectory(
    localAppData: String? = System.getenv("LOCALAPPDATA"),
    temp: String = System.getProperty("java.io.tmpdir"),
): Path {
    val rooted = localAppData?.takeIf { it.isNotBlank() }?.let { Path.of(it, "LearningCoach-updates") }
    return rooted ?: Path.of(temp, "LearningCoach-updates")
}

internal fun usableSpace(directory: Path): Long =
    runCatching { Files.getFileStore(directory).usableSpace }.getOrDefault(Long.MAX_VALUE)

/** Same msiexec invocation the updater already uses. No new signature check. */
internal fun startWindowsInstaller(msi: Path): Boolean {
    val root = System.getenv("SystemRoot") ?: return false
    val installer = Path.of(root, "System32", "msiexec.exe")
    if (!Files.isRegularFile(installer)) return false
    return try {
        ProcessBuilder(installer.toString(), "/i", msi.toString(), "/passive", "/norestart").start()
        true
    } catch (_: Exception) {
        false
    }
}

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

    internal fun candidate(json: String, requireCompatible: Boolean = false): WindowsRelease? {
        val manifestData = Json.parseToJsonElement(json).jsonObject
        val packages = manifestData["packages"]?.jsonArray?.map { it.jsonObject } ?: listOf(manifestData)
        val data = packages.singleOrNull { it.getValue("arch").jsonPrimitive.content == arch }
            ?: return if (requireCompatible) error("No compatible package") else null
        fun field(name: String) = data.getValue(name).jsonPrimitive.content
        val version = field("version")
        val available = versionNumber(version)
        val current = versionNumber(installed)
        if (available == null || current == null) {
            if (requireCompatible) error("Invalid version")
            return null
        }
        if (available <= current) return null
        val file = field("file")
        require(file.matches(Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,180}\.msi""")))
        val size = field("size").toLong()
        require(size in 1..(2L shl 30))
        val hash = field("sha256").lowercase()
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return WindowsRelease(version, file, size, hash)
    }

    /** One older package listed beside the latest, verified the same way. Absent means no rollback download. */
    internal fun previousRelease(json: String): WindowsRelease? {
        val listed = Json.parseToJsonElement(json).jsonObject["previous"]?.jsonArray?.map { it.jsonObject } ?: return null
        val data = listed.singleOrNull { it.getValue("arch").jsonPrimitive.content == arch } ?: return null
        fun field(name: String) = data.getValue(name).jsonPrimitive.content
        val version = field("version")
        val previousNumber = versionNumber(version) ?: return null
        val installedNumber = versionNumber(installed) ?: return null
        if (previousNumber >= installedNumber) return null
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

    fun check(): WindowsRelease? = candidate(readManifest(), requireCompatible = true)

    internal fun readManifest(): String = read(manifest) { stream ->
        val bytes = stream.readNBytes(16385)
        require(bytes.size <= 16384)
        bytes.toString(Charsets.UTF_8)
    }

    suspend fun download(release: WindowsRelease, directory: Path): Path {
        val context = currentCoroutineContext()
        return read(manifest.resolve(release.file)) { stream ->
            stage(release, directory, stream) { context.ensureActive() }
        }
    }

    /** Same SHA-256 and MSI header check as [download], for the rollback click path. */
    internal fun fetchVerified(release: WindowsRelease, directory: Path): Path =
        read(manifest.resolve(release.file)) { stream ->
            stage(release, directory, stream) {}
        }

    internal fun stage(
        release: WindowsRelease,
        directory: Path,
        stream: InputStream,
        freeBytes: () -> Long = { usableSpace(directory) },
        checkActive: () -> Unit,
    ): Path {
        Files.createDirectories(directory)
        if (freeBytes() < release.size) throw UpdateFailed(UpdateFailure.SPACE, UPDATE_SPACE)
        val partial = Files.createTempFile(directory, "download-", ".part")
        try {
            val input = BufferedInputStream(stream)
            input.mark(8)
            val magic = byteArrayOf(-48, -49, 17, -32, -95, -79, 26, -31) // MSI compound file header
            if (!input.readNBytes(8).contentEquals(magic)) throw UpdateFailed(UpdateFailure.CHECKSUM, UPDATE_CHECKSUM)
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
                    if (size > release.size) throw UpdateFailed(UpdateFailure.CHECKSUM, UPDATE_CHECKSUM)
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            }
            if (size != release.size) throw UpdateFailed(UpdateFailure.INTERRUPTED, UPDATE_INTERRUPTED)
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != release.sha256) throw UpdateFailed(UpdateFailure.CHECKSUM, UPDATE_CHECKSUM)
            checkActive()
            // Retain a verified source for Windows Installer repair. Partial transfers are never reused.
            return Files.move(partial, directory.resolve(release.sha256 + ".msi"), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(partial)
        }
    }
}
