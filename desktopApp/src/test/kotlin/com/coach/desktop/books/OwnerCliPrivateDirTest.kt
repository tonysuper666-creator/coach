package com.coach.desktop.books

import com.coach.migrate.privateDir
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.*
import org.junit.Test

/**
 * The owner CLI writes content, reports and a ledger under [privateDir]. Windows used to return that
 * directory with whatever ACL it inherited, so a wide parent grant survived. These tests hold the real
 * function to the same boundary the server's PrivateFiles uses: exclusive owner access, read back, or refuse.
 */
class OwnerCliPrivateDirTest {
    @Test fun privateDirReplacesWideInheritedAccess() {
        val root = Files.createTempDirectory("coach-private-dir-")
        try {
            val parent = Files.createDirectory(root.resolve("wide"))
            val posix = Files.getFileAttributeView(parent, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            if (posix != null) {
                posix.setPermissions(PosixFilePermissions.fromString("rwxrwxrwx"))
            } else {
                val acl = assertNotNull(
                    Files.getFileAttributeView(parent, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS),
                    "Owner CLI needs an ACL view where POSIX is unavailable")
                val lookup = parent.fileSystem.userPrincipalLookupService
                val user = lookup.lookupPrincipalByName(System.getProperty("user.name"))
                val other = listOf("Everyone", "BUILTIN\\Users", "S-1-1-0")
                    .firstNotNullOfOrNull { runCatching { lookup.lookupPrincipalByName(it) }.getOrNull() }
                assertNotNull(other, "Cannot resolve a non-owner principal to widen the fixture")
                acl.acl = listOf(
                    AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
                        .setPermissions(*AclEntryPermission.values())
                        .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build(),
                    AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(other)
                        .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.READ_ACL,
                            AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.LIST_DIRECTORY)
                        .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build())
            }

            val child = privateDir(parent.resolve("private"))
            // seed-content writes content.json / report.md here; the same inheritance must cover the files.
            val report = child.resolve("report.txt")
            report.writeText("synthetic owner report\n")

            assertPrivateDirectory(child)
            if (posix == null) assertPrivateFile(report)
            println("PRIVATE DIR: ${if (posix == null) "acl" else "posix"} fixture replaced inherited wide access")
        } finally {
            assertTrue(root.toFile().deleteRecursively(), "Private dir fixture cleanup failed")
        }
    }

    @Test fun privateDirRefusesFileSystemThatCannotProtectThePath() {
        val root = Files.createTempDirectory("coach-private-fs-")
        val archive = root.resolve("synthetic.zip")
        try {
            FileSystems.newFileSystem(URI.create("jar:${archive.toUri()}"), mapOf("create" to "true")).use { fs ->
                val error = assertFailsWith<UnsupportedOperationException> {
                    privateDir(fs.getPath("/private"))
                }
                assertTrue(error.message.orEmpty().contains("POSIX"),
                    "Refusal must name the missing permission model: ${error.message}")
                assertFalse(Files.exists(fs.getPath("/private/report.txt")),
                    "A path that cannot be protected must not receive content")
            }
        } finally {
            assertTrue(root.toFile().deleteRecursively(), "Private fs fixture cleanup failed")
        }
    }

    private fun assertPrivateDirectory(path: Path) {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        if (posix != null) {
            assertEquals("rwx------", PosixFilePermissions.toString(posix.readAttributes().permissions()))
            return
        }
        val entry = assertSingleOwnerEntry(path)
        assertTrue(entry.flags().containsAll(setOf(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)),
            "Private directory must hand its protection on to new children")
    }

    private fun assertPrivateFile(path: Path) {
        assertSingleOwnerEntry(path)
    }

    private fun assertSingleOwnerEntry(path: Path): AclEntry {
        val acl = assertNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS))
        val user = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
        val entries = acl.acl
        assertEquals(1, entries.size, "Private path retained extra ACL grants: $entries")
        val entry = entries.single()
        assertEquals(user, entry.principal())
        assertEquals(AclEntryType.ALLOW, entry.type())
        assertEquals(AclEntryPermission.values().toSet(), entry.permissions())
        return entry
    }
}
