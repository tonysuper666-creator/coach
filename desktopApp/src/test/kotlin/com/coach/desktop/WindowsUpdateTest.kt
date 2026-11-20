package com.coach.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.ShortByReference
import org.junit.Assume.assumeTrue
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

    @Test fun x64ProcessUnderPrismUpdatesToNativeArm64() {
        val kernel = object : UpdateKernel32 {
            override fun GetCurrentProcess(): Pointer = Pointer.createConstant(-1L)
            override fun IsWow64Process2(process: Pointer, processMachine: ShortByReference, nativeMachine: ShortByReference): Boolean {
                assertEquals(GetCurrentProcess(), process)
                processMachine.value = 0x8664.toShort() // emulated x64
                nativeMachine.value = 0xaa64.toShort() // native ARM64
                return true
            }
        }
        val host = assertNotNull(windowsUpdateArch(kernel))
        val prism = WindowsUpdate(URI("https://updates.invalid/channel/latest.json"), "1.0.1", host)
        val x64 = json(version = "1.1.0", file = "LearningCoach-1.1.0-x64.msi")
        val arm64 = """{"version":"1.1.0","arch":"arm64","file":"LearningCoach-1.1.0-arm64.msi","size":42,"sha256":"${"a".repeat(64)}"}"""
        val manifest = """{"packages":[$x64,$arm64]}"""
        assertEquals(WindowsRelease("1.1.0", "LearningCoach-1.1.0-arm64.msi", 42, "a".repeat(64)), prism.candidate(manifest))
        assertNull(prism.candidate(x64))
        val nativeX64 = WindowsUpdate(URI("https://updates.invalid/channel/latest.json"), "1.0.1", "x64")
        assertEquals(WindowsRelease("1.1.0", "LearningCoach-1.1.0-x64.msi", bytes.size.toLong(), hash), nativeX64.candidate(manifest))
    }

    @Test fun windowsUpdateArchMapsNativeX64AndRejectsUnknownOrFailedCalls() {
        fun kernel(ok: Boolean, nativeMachine: Int) = object : UpdateKernel32 {
            override fun GetCurrentProcess(): Pointer = Pointer.createConstant(-1L)
            override fun IsWow64Process2(process: Pointer, processMachine: ShortByReference, outNative: ShortByReference): Boolean {
                outNative.value = nativeMachine.toShort()
                return ok
            }
        }
        assertEquals("x64", windowsUpdateArch(kernel(ok = true, nativeMachine = 0x8664)))
        assertEquals("arm64", windowsUpdateArch(kernel(ok = true, nativeMachine = 0xaa64)))
        assertNull(windowsUpdateArch(kernel(ok = true, nativeMachine = 0x014c)))
        assertNull(windowsUpdateArch(kernel(ok = false, nativeMachine = 0xaa64)))
    }

    @Test fun liveKernel32ReportsNativeArchitectureOnWindows() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val jvmArch = System.getProperty("os.arch").lowercase()
        val detected = assertNotNull(windowsUpdateArch(Native.load("kernel32", UpdateKernel32::class.java)))
        println("LIVE_WINDOWS_UPDATE_ARCH=$detected jvm_arch=$jvmArch")
        when (jvmArch) {
            "aarch64", "arm64" -> assertEquals("arm64", detected)
            "amd64", "x86_64" -> assertTrue(detected in setOf("x64", "arm64"), "unexpected host arch: $detected")
            else -> fail("Unexpected Windows JVM os.arch: $jvmArch")
        }
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
