package com.coach.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * The one way test fixtures open Win-Drive's cross-job foreground lock.
 *
 * `tools/smoke/Win-Drive.ps1` (`New-ForegroundMutex`) creates the named mutex with a DACL that gives
 * Authenticated Users only `Synchronize, Modify`. When that mutex already exists, plain
 * `CreateMutexW(null, …)` asks for MUTEX_ALL_ACCESS, so it returns a null handle with error 5. This helper
 * asks only for SYNCHRONIZE | MUTEX_MODIFY_STATE (all a wait and a release need). When it is first to create
 * the mutex, it gives it the same DACL as the driver, so the driver can still open it afterwards.
 * Failures report the Win32 error code.
 */
internal object WinDriveForegroundLock {
    const val NAME = "Global\\Coach.WinDrive.Foreground"
    const val SYNCHRONIZE = 0x00100000
    const val MUTEX_MODIFY_STATE = 0x0001
    const val ACCESS = SYNCHRONIZE or MUTEX_MODIFY_STATE
    /** Same rule as Win-Drive.ps1: Authenticated Users, Allow, Synchronize | Modify (0x100001). */
    const val DRIVER_SDDL = "D:(A;;0x100001;;;AU)"
    private const val WAIT_OBJECT_0 = 0
    private const val WAIT_ABANDONED = 0x80
    private const val WAIT_FAILED = -1

    /** Opens the named mutex, or creates it with the driver's DACL, requesting only [ACCESS]. */
    fun open(name: String = NAME): Pointer {
        val descriptor = PointerByReference()
        if (!Advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW(WString(DRIVER_SDDL), 1, descriptor, null)) {
            error("ConvertStringSecurityDescriptorToSecurityDescriptorW failed: Win32 error ${Native.getLastError()}")
        }
        try {
            val attributes = SecurityAttributes().apply {
                nLength = size()
                lpSecurityDescriptor = descriptor.value
                bInheritHandle = false
                write()
            }
            return Kernel.CreateMutexExW(attributes, WString(name), 0, ACCESS)
                ?: error("CreateMutexExW($name, access=0x${ACCESS.toString(16)}) failed: Win32 error ${Native.getLastError()}")
        } finally {
            Kernel.LocalFree(descriptor.value)
        }
    }

    /** Holds the lock for exactly one input or capture. An abandoned mutex also grants ownership. */
    fun <T> hold(name: String = NAME, timeoutMs: Int = 30_000, block: () -> T): T {
        val mutex = open(name)
        var held = false
        try {
            val result = Kernel.WaitForSingleObject(mutex, timeoutMs)
            val error = if (result == WAIT_FAILED) Native.getLastError() else 0
            held = result == WAIT_OBJECT_0 || result == WAIT_ABANDONED
            check(held) {
                if (result == WAIT_FAILED) "Foreground lock $name wait failed: Win32 error $error"
                else "Foreground lock $name not acquired within ${timeoutMs}ms: wait result $result"
            }
            return block()
        } finally {
            if (held && !Kernel.ReleaseMutex(mutex)) {
                System.err.println("Foreground lock $name release failed: Win32 error ${Native.getLastError()}")
            }
            Kernel.CloseHandle(mutex)
        }
    }

    @Structure.FieldOrder("nLength", "lpSecurityDescriptor", "bInheritHandle")
    class SecurityAttributes : Structure() {
        @JvmField var nLength: Int = 0
        @JvmField var lpSecurityDescriptor: Pointer? = null
        @JvmField var bInheritHandle: Boolean = false
    }

    internal interface LockKernel : StdCallLibrary {
        fun CreateMutexExW(attributes: SecurityAttributes?, name: WString, flags: Int, desiredAccess: Int): Pointer?
        fun WaitForSingleObject(handle: Pointer, timeout: Int): Int
        fun ReleaseMutex(handle: Pointer): Boolean
        fun CloseHandle(handle: Pointer): Boolean
        fun LocalFree(memory: Pointer?): Pointer?
    }

    internal interface LockAdvapi : StdCallLibrary {
        fun ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl: WString, revision: Int, descriptor: PointerByReference, size: Pointer?,
        ): Boolean
    }

    internal val Kernel: LockKernel by lazy { Native.load("kernel32", LockKernel::class.java) }
    private val Advapi: LockAdvapi by lazy { Native.load("advapi32", LockAdvapi::class.java) }
}
