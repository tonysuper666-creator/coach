package com.coach.server

import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.*
import java.util.UUID

/** Unchecked so PrintStream cannot swallow a broken privacy boundary as a logging I/O error. */
internal class PrivateFilePermissionException(cause: Exception) : SecurityException("Cannot protect private Coach path", cause)

/** Create empty objects, protect them, and only then let callers write private bytes. */
internal class PrivateFiles(
    private val permissions: (Path, Boolean) -> Unit = ::setPrivatePermissions,
) {
    fun directory(path: Path): Path {
        val target = path.toAbsolutePath().normalize()
        val created = mutableListOf<Path>()
        try { ensureDirectory(target, created); return target }
        catch (error: Exception) { created.asReversed().forEach { removeEmpty(it, error) }; throw error }
    }

    private fun ensureDirectory(target: Path, created: MutableList<Path>) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            protect(target, true)
            return
        }
        val parent = target.parent ?: throw IOException("Private directory has no parent")
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) ensureDirectory(parent, created)
        else requireDirectory(parent)
        try {
            Files.createDirectory(target, *initialPermissions(parent, true))
            created.add(target)
        } catch (_: FileAlreadyExistsException) { /* validate and protect the concurrent creator's object */ }
        protect(target, true)
    }

    fun file(path: Path): Path = prepareFile(path) { _, _ -> }

    fun writeNew(path: Path, bytes: ByteArray): Path = prepareFile(path) { target, created ->
        if (created) Files.newByteChannel(target, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }

    private fun prepareFile(path: Path, write: (Path, Boolean) -> Unit): Path {
        val target = path.toAbsolutePath().normalize()
        val parents = mutableListOf<Path>()
        var created = false
        try {
            ensureDirectory(target.parent, parents)
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createFile(target, *initialPermissions(target.parent, false))
                    created = true
                } catch (error: FileSystemException) {
                    // Windows can report AccessDenied for a concurrently created directory.
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw error
                }
            }
            secureFile(target)
            write(target, created)
            return target
        } catch (error: Exception) {
            if (created) removeEmpty(target, error)
            parents.asReversed().forEach { removeEmpty(it, error) }
            throw error
        }
    }

    fun tempFile(directory: Path, prefix: String, suffix: String): Path =
        file(directory.resolve("$prefix${UUID.randomUUID()}$suffix"))

    fun secureFile(path: Path) = protect(path, false)

    // UNIX sockets are not regular files, but have the same private mode.
    fun secureSocket(path: Path) = protect(path, false, socket = true)

    private fun protect(path: Path, directory: Boolean, socket: Boolean = false) {
        try {
            require(!Files.isSymbolicLink(path)) { "Private paths must not be symbolic links" }
            require(if (directory) Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                else if (socket) Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                else Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Invalid private path type" }
            permissions(path, directory)
        } catch (error: UnsupportedOperationException) {
            throw error
        } catch (error: Exception) {
            throw PrivateFilePermissionException(error)
        }
    }

    private fun requireDirectory(path: Path) {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            throw PrivateFilePermissionException(IOException("Invalid private parent directory"))
    }

    private fun removeEmpty(path: Path, error: Exception) {
        try { Files.deleteIfExists(path) }
        catch (cleanup: Exception) { error.addSuppressed(cleanup) }
    }
}

internal val privateFiles = PrivateFiles()

private fun initialPermissions(parent: Path, directory: Boolean): Array<FileAttribute<*>> =
    if (Files.getFileStore(parent).supportsFileAttributeView(PosixFileAttributeView::class.java))
        arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------")))
    else emptyArray()

private fun setPrivatePermissions(path: Path, directory: Boolean) {
    val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
    if (posix != null) {
        // protect() rejects symlinks first. Use chmod: macOS lchmod cannot protect UNIX sockets.
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
        return
    }
    val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        ?: throw UnsupportedOperationException("Private Coach paths require POSIX permissions or ACLs")
    // getOwner may return BUILTIN\\Administrators on Windows. Grant the process user instead.
    val user = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
        .setPermissions(*AclEntryPermission.values()).apply {
            if (directory) setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
        }.build()
    acl.acl = listOf(entry)
    check(acl.acl == listOf(entry)) { "Private ACL did not retain the current user's exclusive grant" }
}
