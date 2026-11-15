package com.coach.desktop.books

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Deletion applies to the opened name. No WRITE_ATTRIBUTES access or DOS attribute mutation. */
internal object WindowsReadonlyBookEntry {
    private val kernel by lazy { Native.load("kernel32", WindowsBookDeleteKernel::class.java) }

    fun delete(path: Path, expected: Path, directory: Boolean) {
        if (!System.getProperty("os.name").startsWith("Windows", true)) throw IOException("Native cleanup requires Windows")
        val api = kernel
        // DELETE | FILE_READ_ATTRIBUTES; share read/write but pin against concurrent deletion.
        // OPEN_REPARSE_POINT prevents a final-component link being followed. BACKUP_SEMANTICS
        // allows a directory handle without granting backup/restore privileges.
        val handle = api.CreateFileW(WString(path.toAbsolutePath().toString()), 0x10080, 3, null, 3,
            0x00200000 or 0x02000000, null)
        if (handle == null || Pointer.nativeValue(handle) == -1L) throw failure("open")
        try {
            Memory(52).use { info ->
                if (api.GetFileInformationByHandle(handle, info) == 0) throw failure("attributes")
                val attributes = info.getInt(0)
                if ((attributes and 0x400) != 0 || ((attributes and 0x10) != 0) != directory || (attributes and 1) == 0)
                    throw IOException("Native cleanup entry identity refused")
            }
            val name = CharArray(32768)
            val length = api.GetFinalPathNameByHandleW(handle, name, name.size, 0)
            if (length <= 0 || length >= name.size) throw failure("path")
            val raw = String(name, 0, length)
            val resolved = when {
                raw.startsWith("\\\\?\\UNC\\", true) -> "\\\\" + raw.substring(8)
                raw.startsWith("\\\\?\\") -> raw.substring(4)
                else -> raw
            }
            if (Path.of(resolved).toAbsolutePath().normalize() != expected.toAbsolutePath().normalize())
                throw IOException("Native cleanup handle escaped its pinned location")
            Memory(4).use { disposition ->
                // FileDispositionInfoEx: DELETE | POSIX_SEMANTICS | IGNORE_READONLY_ATTRIBUTE.
                // In particular, this removes our hardlink without clearing the shared readonly bit.
                disposition.setInt(0, 0x1 or 0x2 or 0x10)
                if (api.SetFileInformationByHandle(handle, 21, disposition, 4) == 0) throw failure("disposition")
            }
        } finally {
            if (api.CloseHandle(handle) == 0) throw failure("close")
        }
        if (Files.exists(path, NOFOLLOW_LINKS)) throw IOException("Native cleanup entry remains")
    }

    private fun failure(operation: String) = IOException("Native cleanup $operation failed: ${Native.getLastError()}")
}

/** Minimal kernel32 ABI; all calls use the same verified handle. */
internal interface WindowsBookDeleteKernel : StdCallLibrary {
    fun CreateFileW(name: WString, access: Int, share: Int, security: Pointer?, creation: Int, flags: Int, template: Pointer?): Pointer?
    fun GetFileInformationByHandle(handle: Pointer, info: Pointer): Int
    fun GetFinalPathNameByHandleW(handle: Pointer, name: CharArray, size: Int, flags: Int): Int
    fun SetFileInformationByHandle(handle: Pointer, type: Int, info: Pointer, size: Int): Int
    fun CloseHandle(handle: Pointer): Int
}
