package com.coach.desktop.books

import com.coach.migrate.copyFile
import com.coach.migrate.privateDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class OwnerCliAliasProtectionTest {
    @Test fun hardLinkIsRejectedBeforeAnyPermissionsChange() {
        val root = Files.createTempDirectory("coach-hardlink-")
        try {
            val external = Files.createDirectory(root.resolve("external"))
            val victim = Files.writeString(external.resolve("synthetic.txt"), "synthetic")
            val output = Files.createDirectory(root.resolve("output"))
            val nested = Files.createDirectory(output.resolve("nested"))
            Files.createLink(nested.resolve("alias.txt"), victim)
            val ordinary = Files.writeString(output.resolve("ordinary.txt"), "ordinary")
            val paths = listOf(external, victim, output, nested, ordinary)
            val before = paths.map(::permissions)
            val result = runCatching { privateDir(output) }
            val after = paths.map(::permissions)
            println("HARDLINK rejected=${result.isFailure} outsideAclUnchanged=${before.take(2) == after.take(2)} " +
                "allPermissionsUnchanged=${before == after} outsideBefore=${digest(before[1])} outsideAfter=${digest(after[1])}")
            assertEquals(before, after, "rejected output must not change external or in-tree permissions")
            assertTrue(result.isFailure, "privateDir must reject multiply linked files")
            assertEquals("synthetic", Files.readString(victim))
        } finally {
            assertTrue(root.toFile().deleteRecursively())
        }
    }

    @Test fun ancestorJunctionCannotChangeExternalAcl() = checkAncestorJunction(existingOutput = true)
    @Test fun ancestorJunctionCannotCreateExternalOutput() = checkAncestorJunction(existingOutput = false)

    @Test fun repeatedProtectionAndCopiesKeepNonemptyOutputPrivate() {
        val root = Files.createTempDirectory("coach-repeat-private-").toRealPath()
        try {
            val source = Files.writeString(root.resolve("source.txt"), "synthetic")
            val output = privateDir(root.resolve("output"))
            val existing = Files.writeString(output.resolve("existing.txt"), "old")
            if (!windows) {
                Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rw-rw-rw-"))
            }
            copyFile(source, output.resolve("first.txt"))
            val protectedPermissions = permissions(existing)
            Files.writeString(existing, "new")
            copyFile(source, output.resolve("second.txt"))
            privateDir(output)
            assertEquals(protectedPermissions, permissions(existing))
            for (file in listOf(existing, output.resolve("first.txt"), output.resolve("second.txt"))) {
                if (windows) {
                    val entries = assertNotNull(Files.getFileAttributeView(file, AclFileAttributeView::class.java)).acl
                    assertEquals(1, entries.size)
                    assertEquals(System.getProperty("user.name").lowercase(), entries.single().principal().name.substringAfterLast('\\').lowercase())
                } else {
                    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
                }
            }
            assertEquals("new", Files.readString(existing))
            assertEquals("synthetic", Files.readString(output.resolve("first.txt")))
            assertEquals("synthetic", Files.readString(output.resolve("second.txt")))
        } finally {
            assertTrue(root.toFile().deleteRecursively())
        }
    }

    private fun checkAncestorJunction(existingOutput: Boolean) {
        assumeTrue("Windows NTFS junction test", windows)
        val root = Files.createTempDirectory("coach-ancestor-junction-")
        val external = Files.createDirectory(root.resolve("external"))
        val junction = root.resolve("junction")
        try {
            val actual = external.resolve("output")
            val victim = if (existingOutput) {
                Files.createDirectory(actual)
                Files.writeString(actual.resolve("synthetic.txt"), "synthetic")
            } else Files.writeString(external.resolve("synthetic.txt"), "synthetic")
            val process = ProcessBuilder("cmd.exe", "/c", "mklink /J \"$junction\" \"$external\"")
                .redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), "junction creation failed: $log")
            val paths = listOfNotNull(external, victim, actual.takeIf { existingOutput })
            val before = paths.map(::permissions)
            val result = runCatching { privateDir(junction.resolve("output")) }
            val after = paths.map(::permissions)
            println("ANCESTOR existing=$existingOutput rejected=${result.isFailure} outsideAclUnchanged=${before == after} " +
                "outsideBefore=${digest(before.joinToString())} outsideAfter=${digest(after.joinToString())} " +
                "outputExists=${Files.exists(actual)}")
            assertEquals(before, after, "privateDir changed ACL through an ancestor junction")
            if (!existingOutput) assertFalse(Files.exists(actual), "privateDir created a directory outside its tree")
            assertTrue(result.isFailure, "privateDir must reject a junction in an ancestor")
            assertEquals("synthetic", Files.readString(victim))
        } finally {
            Files.deleteIfExists(junction)
            assertTrue(root.toFile().deleteRecursively())
        }
    }

    private fun permissions(path: Path): String {
        if (!windows) return PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))
        // SDDL includes the DACL protection/inheritance bits that the Java entry list omits.
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "(Get-Acl -LiteralPath \$env:COACH_ACL_FIXTURE -ErrorAction Stop).Sddl")
            .redirectErrorStream(true).apply { environment()["COACH_ACL_FIXTURE"] = path.toString() }.start()
        val result = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, process.waitFor(), "synthetic fixture ACL read failed")
        assertTrue(result.isNotBlank())
        return result
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private val windows get() = System.getProperty("os.name").startsWith("Windows")
}
