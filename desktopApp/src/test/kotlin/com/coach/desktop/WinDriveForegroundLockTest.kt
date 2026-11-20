package com.coach.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.*

/**
 * Uses a private `Local\` lock with the driver's DACL, never the shared `Global\Coach.WinDrive.Foreground`,
 * so it can run next to real smoke drivers.
 */
class WinDriveForegroundLockTest {
    private fun privateName() = "Local\\Coach.WinDrive.Foreground.kt-test-${UUID.randomUUID()}"

    /** Stands in for Win-Drive.ps1 creating the lock first: driver DACL, holder keeps the handle open. */
    private fun <T> driverCreated(name: String, block: () -> T): T {
        val driverHandle = WinDriveForegroundLock.open(name)
        try { return block() } finally { WinDriveForegroundLock.Kernel.CloseHandle(driverHandle) }
    }

    @Test fun legacyFullAccessOpenIsDeniedOnDriverCreatedLock() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val name = privateName()
        driverCreated(name) {
            val legacy = Legacy.CreateMutexW(null, false, WString(name))
            val error = Native.getLastError()
            legacy?.let { Legacy.CloseHandle(it) }
            assertNull(legacy, "The old fixture call must reproduce the heavy failure on a driver-created lock")
            assertEquals(5, error, "ERROR_ACCESS_DENIED")
        }
    }

    @Test fun limitedAccessAcquiresAndReleasesDriverCreatedLock() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val name = privateName()
        driverCreated(name) {
            assertEquals(1, WinDriveForegroundLock.hold(name, timeoutMs = 5_000) { 1 })
            // Released after the first hold: a second hold gets it again without waiting.
            assertEquals(2, WinDriveForegroundLock.hold(name, timeoutMs = 1_000) { 2 })
        }
    }

    @Test fun lockCreatedByFixtureStaysOpenableWithDriverRights() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val name = privateName()
        val first = WinDriveForegroundLock.open(name)
        try {
            val second = WinDriveForegroundLock.open(name)
            WinDriveForegroundLock.Kernel.CloseHandle(second)
        } finally { WinDriveForegroundLock.Kernel.CloseHandle(first) }
    }

    @Test fun waitTimeoutNamesTheLockAndResult() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val name = privateName()
        val holder = Thread {
            WinDriveForegroundLock.hold(name, timeoutMs = 5_000) { Thread.sleep(1_500) }
        }.apply { start() }
        Thread.sleep(300)
        val failure = assertFailsWith<IllegalStateException> { WinDriveForegroundLock.hold(name, timeoutMs = 100) { } }
        holder.join()
        assertTrue(name in failure.message.orEmpty() && "wait result 258" in failure.message.orEmpty(), failure.message)
    }

    private interface LegacyKernel : StdCallLibrary {
        fun CreateMutexW(attributes: Pointer?, owner: Boolean, name: WString): Pointer?
        fun CloseHandle(handle: Pointer): Boolean
    }

    private val Legacy by lazy { Native.load("kernel32", LegacyKernel::class.java) }
}
