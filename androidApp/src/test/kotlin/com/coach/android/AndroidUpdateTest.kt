package com.coach.android

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest

/** The update path reads the same manifest as Windows; these tests pin the Android half of that contract. */
class AndroidUpdateTest {
    private val payload = "payload".toByteArray()
    private val apk = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + payload
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val channel = URI("https://updates.example.com/coach/latest.json")

    private fun update(installedVersionCode: Long) = AndroidUpdate(channel, installedVersionCode)

    private fun manifest(
        version: String = "1.1.0",
        versionCode: Long = 3,
        file: String = "LearningCoach-1.1.0-android.apk",
        size: Long = apk.size.toLong(),
        hash: String = sha256,
        arch: String = "android",
    ) = """
        {"packages":[
          {"version":"1.0.1","arch":"x64","file":"LearningCoach-1.0.1-x64.msi","size":10,"sha256":"${"0".repeat(64)}"},
          {"version":"$version","arch":"$arch","versionCode":$versionCode,"file":"$file","size":$size,"sha256":"$hash"}
        ]}
    """.trimIndent()

    private fun release(json: String = manifest()) = requireNotNull(update(2).candidate(json))

    private fun <T> withTempDirectory(body: (File) -> T): T {
        val directory = Files.createTempDirectory("coach-update-test").toFile()
        return try {
            body(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun offersOnlyNewerAndroidPackages() {
        assertEquals("1.1.0", update(2).candidate(manifest())?.version)
        assertNull("the installed version is not an update", update(3).candidate(manifest()))
        assertNull("an older package is not an update", update(4).candidate(manifest()))
    }

    @Test fun ignoresWindowsPackagesInTheSharedManifest() {
        assertNull(update(2).candidate(manifest(arch = "x64")))
        assertNull(update(2).candidate("""{"packages":[{"version":"1.1.0","arch":"x64","file":"a.msi","size":9,"sha256":"${"0".repeat(64)}"}]}"""))
    }

    @Test fun rejectsVersionsThatCannotBeCompared() {
        for (version in listOf("256.0.0", "1.09.1", "1.9.65536", "1.1", "v1.1.0", "0.1.0")) {
            assertNull(version, update(2).candidate(manifest(version = version)))
        }
    }

    @Test fun rejectsAPackageThatCannotBeInstalled() {
        // An installer compares versionCode; a package that does not advance it would be refused.
        assertNull("equal versionCode", update(2).candidate(manifest(versionCode = 2)))
        assertThrows(IllegalArgumentException::class.java) { update(2).candidate(manifest(versionCode = 0)) }
        assertThrows(IllegalArgumentException::class.java) {
            update(2).candidate(manifest(versionCode = 2_100_000_001L))
        }
        // A relative path could escape the channel directory; anything but an .apk name is refused.
        for (file in listOf("../update.apk", "sub/dir.apk", "update.exe", "update.apk?x=1", ".apk")) {
            assertThrows(file, IllegalArgumentException::class.java) { update(2).candidate(manifest(file = file)) }
        }
        assertThrows(IllegalArgumentException::class.java) { update(2).candidate(manifest(size = 0)) }
        assertThrows(IllegalArgumentException::class.java) { update(2).candidate(manifest(size = (1L shl 30) + 1)) }
        assertThrows(IllegalArgumentException::class.java) { update(2).candidate(manifest(hash = "not-a-hash")) }
    }

    @Test fun confinesChecksToThePackagedChannel() {
        assertThrows(IllegalArgumentException::class.java) { AndroidUpdate(URI("http://updates.example.com/latest.json"), 2) }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidUpdate(URI("https://user:secret@updates.example.com/latest.json"), 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidUpdate(URI("https://updates.example.com/latest.json?x=1"), 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidUpdate(URI("https://updates.example.com/latest.json#fragment"), 2)
        }
        assertThrows(IllegalArgumentException::class.java) { AndroidUpdate(URI("https://updates.example.com/manifest.json"), 2) }
        // Acceptance builds may read a local server, but only the emulator alias and loopback.
        AndroidUpdate(URI("http://10.0.2.2:8080/latest.json"), 2, allowLocalHttp = true)
        assertThrows(IllegalArgumentException::class.java) {
            AndroidUpdate(URI("http://updates.example.com/latest.json"), 2, allowLocalHttp = true)
        }
    }

    @Test fun stagesOnlyCompleteVerifiedApkAndRemovesPartialTransfers() = withTempDirectory { directory ->
        val release = release()
        // Truncated, extended, corrupted and empty transfers all leave nothing behind.
        val variants = listOf(
            apk.copyOf(apk.size - 1),
            apk + 0x00,
            apk.copyOf().also { it[it.size - 1] = 0x00 },
            ByteArray(0),
            "not-a-zip".toByteArray(),
        )
        for (bytes in variants) {
            assertThrows(IllegalArgumentException::class.java) {
                update(2).stage(release, directory, ByteArrayInputStream(bytes)) {}
            }
            assertEquals("partial transfers must not survive", 0, directory.listFiles()!!.size)
        }

        val staged = update(2).stage(release, directory, ByteArrayInputStream(apk)) {}
        assertEquals(sha256 + ".apk", staged.name)
        assertTrue(staged.readBytes().contentEquals(apk))
        assertEquals(1, directory.listFiles()!!.size)

        // Re-staging the same bytes replaces the file rather than trusting what is already there.
        val tampered = File(directory, staged.name).apply { writeBytes(apk.copyOf().also { it[it.size - 1] = 0x00 }) }
        assertEquals(apk.size.toLong(), tampered.length())
        update(2).stage(release, directory, ByteArrayInputStream(apk)) {}
        assertTrue(staged.readBytes().contentEquals(apk))
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun stopsAStagingTransferThatWasCancelled() = withTempDirectory { directory ->
        val release = release()
        assertThrows(IllegalStateException::class.java) {
            update(2).stage(release, directory, ByteArrayInputStream(apk)) { error("cancelled") }
        }
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun downloadsThePackageTheManifestOffers() = withTempDirectory { directory ->
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifest()))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(apk)))
            val update = AndroidUpdate(server.url("/latest.json").toUri(), 2, allowLocalHttp = true)
            val release = requireNotNull(update.check())
            val staged = update.download(release, directory)
            assertEquals(apk.size.toLong(), staged.length())
            assertTrue(staged.readBytes().contentEquals(apk))
            assertEquals("/latest.json", server.takeRequest().path)
            assertEquals("/LearningCoach-1.1.0-android.apk", server.takeRequest().path)
        }
    }

    @Test fun refusesAPackageSignedByAnotherKey() {
        val installed = ApkIdentity("com.coach.android", setOf("a".repeat(64)))
        assertTrue(sameInstallIdentity(installed, installed))
        // A different key would fail the upgrade or replace the learner's data.
        assertFalse(sameInstallIdentity(installed, ApkIdentity("com.coach.android", setOf("b".repeat(64)))))
        assertFalse(sameInstallIdentity(installed, ApkIdentity("com.coach.android", setOf("a".repeat(64), "b".repeat(64)))))
        assertFalse(sameInstallIdentity(installed, ApkIdentity("com.coach.android", emptySet())))
        assertFalse(sameInstallIdentity(installed, ApkIdentity("com.other.app", setOf("a".repeat(64)))))
        assertFalse(sameInstallIdentity(null, installed))
        assertFalse(sameInstallIdentity(installed, null))
    }
}
