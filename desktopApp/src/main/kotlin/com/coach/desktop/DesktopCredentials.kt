package com.coach.desktop

import com.coach.client.PairedCredentials
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.*
import java.util.Properties
import java.util.UUID

/** A separate, address-bound client identity. Never reads another device's credential. */
internal class DesktopCredentials(clientDirectory: Path) {
    private val directory = clientDirectory.resolve("pairing").toAbsolutePath().normalize()
    internal val file: Path = directory.resolve("credentials.properties")
    private val addressFile = directory.resolve("server-url")

    private fun <T> readPrivate(path: Path, read: (InputStream) -> T): T? {
        checkParents(directory)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        protect(directory, true)
        protect(path, false)
        return Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            Channels.newInputStream(channel).use(read)
        }
    }

    @Synchronized fun savedAddress(): String? = readPrivate(addressFile) {
        desktopServerUrl(it.bufferedReader(Charsets.UTF_8).readText())
    }

    @Synchronized fun saveAddress(serverUrl: String) {
        val address = desktopServerUrl(serverUrl).toByteArray(Charsets.UTF_8)
        writePrivate(addressFile) { it.write(address) }
    }

    @Synchronized fun load(serverUrl: String): PairedCredentials? {
        val values = readPrivate(file) { input -> Properties().apply { load(input) } } ?: return null
        if (values.getProperty("server") != desktopServerUrl(serverUrl)) return null
        val device = values.getProperty("deviceId").orEmpty()
        val token = values.getProperty("token").orEmpty()
        check(device.matches(Regex("[A-Za-z0-9_-]{1,128}")) && token.matches(Regex("[A-Za-z0-9_-]{43}"))) {
            "Invalid saved Coach pairing"
        }
        return PairedCredentials(device, token)
    }

    @Synchronized fun save(serverUrl: String, credentials: PairedCredentials) {
        check(credentials.deviceId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && credentials.token.matches(Regex("[A-Za-z0-9_-]{43}"))) {
            "Invalid Coach pairing"
        }
        val url = desktopServerUrl(serverUrl)
        writePrivate(file) { output ->
            Properties().apply {
                setProperty("server", url)
                setProperty("deviceId", credentials.deviceId)
                setProperty("token", credentials.token)
            }.store(output, "Coach pairing; private to this user")
        }
    }

    private fun writePrivate(path: Path, write: (OutputStream) -> Unit) {
        checkParents(directory)
        Files.createDirectories(directory)
        protect(directory, true)
        if (Files.exists(path, NOFOLLOW_LINKS)) protect(path, false)
        val temporary = directory.resolve(".credentials-${UUID.randomUUID()}")
        try {
            Files.createFile(temporary)
            // Empty until the native privacy boundary has been set AND read back.
            protect(temporary, false)
            Files.newByteChannel(temporary, setOf(WRITE, NOFOLLOW_LINKS)).use { channel ->
                Channels.newOutputStream(channel).use(write)
            }
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    @Synchronized fun clear() {
        checkParents(directory)
        if (Files.exists(file, NOFOLLOW_LINKS)) {
            protect(directory, true)
            protect(file, false)
            Files.delete(file)
        }
    }

    @Synchronized fun clear(serverUrl: String, expected: PairedCredentials) {
        val current = load(serverUrl) ?: return
        if (current.deviceId == expected.deviceId && current.token == expected.token) clear()
    }

    private fun checkParents(path: Path) {
        for (parent in generateSequence(path) { it.parent }) {
            check(!Files.isSymbolicLink(parent)) { "Coach pairing paths must not be symbolic links" }
            if (Files.exists(parent, NOFOLLOW_LINKS)) check(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "Invalid Coach pairing directory" }
        }
    }

    private fun protect(path: Path, directory: Boolean) {
        check(!Files.isSymbolicLink(path) && if (directory) Files.isDirectory(path, NOFOLLOW_LINKS) else Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            "Invalid Coach pairing path"
        }
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
        if (posix != null) {
            val expected = PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------")
            posix.setPermissions(expected)
            check(posix.readAttributes().permissions() == expected) { "Cannot protect Coach pairing" }
        } else {
            val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
                ?: error("Coach pairing requires private file permissions")
            val user = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
            val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
                .setPermissions(*AclEntryPermission.values()).apply {
                    if (directory) setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                }.build()
            acl.acl = listOf(entry)
            check(acl.acl == listOf(entry)) { "Cannot protect Coach pairing" }
        }
    }
}
