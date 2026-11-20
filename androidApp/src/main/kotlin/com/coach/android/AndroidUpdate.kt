package com.coach.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One Android package offered by the published channel, described the same way as a Windows MSI. */
internal data class AndroidRelease(val version: String, val versionCode: Long, val file: String, val size: Long, val sha256: String)

/**
 * Reads the same `latest.json` the Windows builds use and stages a verified APK.
 *
 * The channel is baked into the APK by the packager; a build without it simply never checks.
 * Only `arch: "android"` entries are candidates, so Windows packages in the same manifest are ignored.
 */
internal class AndroidUpdate(private val manifest: URI, private val installedVersionCode: Long, private val allowLocalHttp: Boolean = false) {
    init {
        // Acceptance builds read the manifest from a local server; release builds require https.
        val local = allowLocalHttp && manifest.scheme == "http" && manifest.host in LOCAL_TEST_HOSTS
        require((manifest.scheme == "https" || local) && manifest.host != null && manifest.rawUserInfo == null &&
            manifest.rawQuery == null && manifest.rawFragment == null && manifest.path.endsWith("/latest.json"))
    }

    companion object {
        /** Emulator host alias and loopback; never accepted by a release build. */
        internal val LOCAL_TEST_HOSTS = setOf("10.0.2.2", "127.0.0.1", "localhost")

        internal fun installed(): AndroidUpdate? {
            val channel = BuildConfig.COACH_UPDATE_CHANNEL
            if (channel.isBlank()) return null
            val uri = runCatching { URI(channel) }.getOrNull() ?: return null
            return runCatching { AndroidUpdate(uri, BuildConfig.VERSION_CODE.toLong(), BuildConfig.DEBUG) }.getOrNull()
        }
    }

    private fun versionNumber(version: String): Long? {
        if (!version.matches(Regex("""[1-9][0-9]{0,2}\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,4})"""))) return null
        val (major, minor, patch) = version.split('.').map(String::toLong)
        return if (major <= 255 && minor <= 255 && patch <= 65535) (major * 256 + minor) * 65536 + patch else null
    }

    internal fun candidate(json: String): AndroidRelease? {
        val manifestData = Json.parseToJsonElement(json).jsonObject
        val packages = manifestData["packages"]?.jsonArray?.map { it.jsonObject } ?: listOf(manifestData)
        val data = packages.singleOrNull { it["arch"]?.jsonPrimitive?.content == "android" } ?: return null
        fun field(name: String) = data.getValue(name).jsonPrimitive.content
        val version = field("version")
        if (versionNumber(version) == null) return null
        // The installer itself compares versionCode; a release that does not advance it would be refused.
        val versionCode = field("versionCode").toLongOrNull() ?: return null
        require(versionCode in 1..2_100_000_000L)
        if (versionCode <= installedVersionCode) return null
        val file = field("file")
        require(file.matches(Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,180}\.apk""")))
        val size = field("size").toLong()
        require(size in 1..(1L shl 30))
        val hash = field("sha256").lowercase()
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return AndroidRelease(version, versionCode, file, size, hash)
    }

    private fun <T> read(uri: URI, body: (InputStream) -> T): T {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            check(connection.responseCode == 200)
            return connection.inputStream.use(body)
        } finally {
            connection.disconnect()
        }
    }

    fun check(): AndroidRelease? = read(manifest) { stream ->
        val bytes = stream.readNBytes(16385)
        require(bytes.size <= 16384)
        candidate(bytes.toString(Charsets.UTF_8))
    }

    fun download(release: AndroidRelease, directory: File, checkActive: () -> Unit = {}): File =
        read(manifest.resolve(release.file)) { stream -> stage(release, directory, stream, checkActive) }

    /** Stages only a complete APK whose bytes match the published size and SHA-256; partial transfers never survive. */
    internal fun stage(release: AndroidRelease, directory: File, stream: InputStream, checkActive: () -> Unit): File {
        directory.mkdirs()
        check(directory.isDirectory) { "Cannot create ${directory.name}" }
        val partial = File.createTempFile("download-", ".part", directory)
        try {
            val input = BufferedInputStream(stream)
            input.mark(4)
            val magic = byteArrayOf(0x50, 0x4b, 0x03, 0x04) // ZIP local file header; every APK is a zip
            require(input.readNBytes(4).contentEquals(magic))
            input.reset()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var size = 0L
            partial.outputStream().use { output ->
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
            val installed = File(directory, release.sha256 + ".apk")
            // Replace, never trust, a package staged by an earlier attempt at the same version.
            Files.move(partial.toPath(), installed.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return installed
        } finally {
            partial.delete()
        }
    }
}
