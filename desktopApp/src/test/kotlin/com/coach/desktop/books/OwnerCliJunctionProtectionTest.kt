package com.coach.desktop.books

import com.coach.migrate.privateDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class OwnerCliJunctionProtectionTest {
    @Test fun junctionInsideOutputCannotChangeExternalAcl() = checkJunction(rootIsJunction = false)
    @Test fun junctionAsOutputCannotChangeExternalAcl() = checkJunction(rootIsJunction = true)

    private fun checkJunction(rootIsJunction: Boolean) {
        assumeTrue("Windows NTFS junction test", System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("coach-junction-")
        val external = Files.createDirectory(root.resolve("external"))
        val victim = Files.writeString(external.resolve("synthetic.txt"), "synthetic")
        val output = if (rootIsJunction) root.resolve("output") else Files.createDirectory(root.resolve("output"))
        val junction = if (rootIsJunction) output else output.resolve("outside")
        try {
            val command = "mklink /J \"$junction\" \"$external\""
            val process = ProcessBuilder("cmd.exe", "/c", command).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), "junction creation failed: $log")
            val before = acl(victim).acl.toList()
            val result = runCatching { privateDir(output) }
            assertEquals(before, acl(victim).acl, "privateDir changed ACL outside the output tree")
            assertTrue(result.isFailure, "privateDir must reject a junction")
            assertEquals("synthetic", Files.readString(victim))
            println("JUNCTION root=$rootIsJunction rejected=true outsideAclUnchanged=true")
        } finally {
            Files.deleteIfExists(junction)
            assertTrue(root.toFile().deleteRecursively())
        }
    }

    private fun acl(path: Path) = assertNotNull(
        Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
    )
}
