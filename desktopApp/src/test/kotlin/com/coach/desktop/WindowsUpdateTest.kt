package com.coach.desktop

import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.test.*

class WindowsUpdateTest {
    private val update = WindowsUpdate(URI("https://updates.invalid/channel/latest.json"), "1.9.0", "x64")
    private val bytes = byteArrayOf(-48, -49, 17, -32, -95, -79, 26, -31, 0)
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun json(version: String = "1.10.0", arch: String = "x64", file: String = "LearningCoach-1.10.0.msi") =
        """{"version":"$version","arch":"$arch","file":"$file","size":${bytes.size},"sha256":"$hash"}"""

    @Test fun offersOnlyNewerCompatibleReleases() {
        assertEquals("1.10.0", update.candidate(json())?.version)
        for (version in listOf("1.9.0", "1.8.99", "256.0.0", "1.09.1", "1.9.65536")) assertNull(update.candidate(json(version)))
        assertNull(update.candidate(json(arch = "arm64")))
    }

    @Test fun confinesDownloadsToPackagedHttpsDirectory() {
        for (url in listOf("http://host/latest.json", "https://a:b@host/latest.json", "https://host/latest.json?token=x"))
            assertFails { WindowsUpdate(URI(url), "1.0.1", "x64") }
        for (file in listOf("https://other/file.msi", "../file.msi", "x%2ffile.msi", "file.exe", "file.msi?q"))
            assertFails { update.candidate(json(file = file)) }
    }

    @Test fun stagesOnlyCompleteVerifiedMsiAndRemovesPartialTransfers() {
        val dir = Files.createTempDirectory("coach-update-test")
        val release = update.candidate(json())!!
        try {
            for (bad in listOf(bytes.copyOf(8), bytes + 0, bytes.copyOf().also { it[8] = 1 }, ByteArray(9))) {
                assertFails { update.stage(release, dir, bad.inputStream()) {} }
            }
            assertFailsWith<CancellationException> {
                update.stage(release, dir, bytes.inputStream()) { throw CancellationException() }
            }
            assertEquals(0L, Files.list(dir).use { it.count() })
            assertContentEquals(bytes, Files.readAllBytes(update.stage(release, dir, bytes.inputStream()) {}))
        } finally {
            Files.deleteIfExists(dir.resolve("$hash.msi"))
            Files.delete(dir)
        }
    }
}
