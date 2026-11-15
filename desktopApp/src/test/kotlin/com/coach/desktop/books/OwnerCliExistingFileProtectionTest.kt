package com.coach.desktop.books

import com.coach.migrate.privateDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.*
import org.junit.Test

class OwnerCliExistingFileProtectionTest {
    @Test fun existingWideFileIsProtectedBeforeOverwrite() {
        val root = Files.createTempDirectory("coach-existing-private-")
        try {
            val output = Files.createDirectory(root.resolve("output"))
            val file = output.resolve("content.json")
            file.writeText("synthetic old content")
            val posix = Files.getFileAttributeView(file, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            if (posix != null) {
                posix.setPermissions(PosixFilePermissions.fromString("rw-rw-rw-"))
                assertTrue(PosixFilePermissions.toString(posix.readAttributes().permissions()).endsWith("rw-"))
            } else {
                val acl = assertNotNull(Files.getFileAttributeView(file, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS))
                val lookup = file.fileSystem.userPrincipalLookupService
                val owner = lookup.lookupPrincipalByName(System.getProperty("user.name"))
                val everyone = listOf("Everyone", "S-1-1-0")
                    .firstNotNullOfOrNull { runCatching { lookup.lookupPrincipalByName(it) }.getOrNull() }
                assertNotNull(everyone, "Cannot resolve Everyone for the positive control")
                acl.acl = listOf(
                    AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                        .setPermissions(*AclEntryPermission.values()).build(),
                    AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone)
                        .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.READ_ACL,
                            AclEntryPermission.READ_ATTRIBUTES).build())
                assertTrue(acl.acl.any { it.principal() == everyone && AclEntryPermission.READ_DATA in it.permissions() })
            }

            privateDir(output)
            assertPrivate(file)
            file.writeText("synthetic replacement")
            assertPrivate(file)
            println("EXISTING FILE: wide before, private before and after overwrite")
        } finally {
            assertTrue(root.toFile().deleteRecursively())
        }
    }

    private fun assertPrivate(path: Path) {
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        if (posix != null) {
            assertEquals("rw-------", PosixFilePermissions.toString(posix.readAttributes().permissions()))
        } else {
            val acl = assertNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS))
            val owner = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
            assertEquals(1, acl.acl.size, "existing file kept another ACL grant")
            assertEquals(owner, acl.acl.single().principal())
            assertEquals(AclEntryType.ALLOW, acl.acl.single().type())
            assertEquals(AclEntryPermission.values().toSet(), acl.acl.single().permissions())
        }
    }
}
