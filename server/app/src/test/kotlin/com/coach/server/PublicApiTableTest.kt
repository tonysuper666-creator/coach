package com.coach.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TON-1943: every endpoint path in client source must be registered in [PublicApiTable], either open to
 * signed-in accounts or owner only. A new client call that nobody classified turns this test red.
 */
class PublicApiTableTest {
    private val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .firstOrNull { Files.exists(it.resolve("settings.gradle.kts")) && it.resolve("shared/client").isDirectory() }
        ?: fail("repository root with shared/client not found")

    private val clientRoots = listOf("shared", "desktopApp", "androidApp")
    private val testDirs = Regex("""/(build|test|jvmTest|commonTest|androidTest|androidUnitTest|desktopTest|androidInstrumentedTest)(/|$)""")
    private val literal = Regex(""""((?:[^"\\\n]|\\.)*)"""")
    private val endpointStart = Regex("""(?:^|(?<=[/}]))(v1/|api/books/|learning-sync/|offline-pack)""")

    /** `"$base/v1/books/${id.encode()}/status?x=1"` becomes `/v1/books/{x}/status`. */
    private fun clientPaths(): Map<String, Set<String>> {
        val found = sortedMapOf<String, MutableSet<String>>()
        for (root in clientRoots.map(repo::resolve).filter { it.isDirectory() }) {
            Files.walk(root).use { files ->
                files.filter { it.extension == "kt" && !testDirs.containsMatchIn(repo.relativize(it).invariantSeparatorsPathString) }
                    .forEach { file ->
                        for (line in file.readLines()) for (match in literal.findAll(line)) {
                            val text = match.groupValues[1]
                                .replace(Regex("""\$\{[^}]*}"""), "{x}")
                                .replace(Regex("""\$[A-Za-z_][A-Za-z0-9_]*"""), "{x}")
                            val start = endpointStart.find(text) ?: continue
                            val path = "/" + text.substring(start.range.first).substringBefore('?')
                            found.getOrPut(path) { sortedSetOf() } += repo.relativize(file).invariantSeparatorsPathString
                        }
                    }
            }
        }
        return found
    }

    private fun covers(template: String, clientPath: String): Boolean {
        val want = template.split('/')
        val have = clientPath.split('/')
        for ((index, segment) in want.withIndex()) {
            if (segment == "**") return have.size >= index
            val actual = have.getOrNull(index) ?: return false
            val placeholder = segment.startsWith('{') && segment.endsWith('}')
            if (actual == "{x}" && !placeholder) return false
            if (!placeholder && actual != segment) return false
        }
        return have.size == want.size
    }

    @Test fun everyClientEndpointIsEitherOpenToSignedInAccountsOrOwnerOnly() {
        val paths = clientPaths()
        // Guard against the scan silently finding nothing after a source move.
        assertTrue(paths.size >= 30, "only ${paths.size} client endpoints found: ${paths.keys}")
        for (known in listOf("/v1/library/practice/grade-and-diagnose", "/v1/library/course/mock/generate", "/v1/ws", "/v1/courses/{x}/events")) {
            assertTrue(known in paths, "scan missed $known")
        }
        val missing = paths.filterKeys { path -> PublicApiTable.routes.none { covers(it.template, path) } }
        assertTrue(missing.isEmpty(), "Register these client endpoints in PublicApiTable (SIGNED_IN or OWNER):\n" +
            missing.entries.joinToString("\n") { (path, files) -> "  $path  <- ${files.joinToString()}" })
        val owner = paths.keys.filter { path -> PublicApiTable.routes.first { covers(it.template, path) }.access == ApiAccess.OWNER }
        val open = paths.keys - owner.toSet()
        println("CLIENT_ENDPOINTS=${paths.size} SIGNED_IN=${open.size} OWNER=${owner.size}")
        println("OWNER_ONLY=" + owner.joinToString(" "))
    }

    @Test fun tableRowsDoNotOverlapBetweenOpenAndOwner() {
        val open = PublicApiTable.routes.filter { it.access == ApiAccess.SIGNED_IN }
        val owner = PublicApiTable.routes.filter { it.access == ApiAccess.OWNER }
        for (row in open) {
            val sample = row.template.replace(Regex("""\{[^}]+}"""), "sb_" + "0".repeat(24))
            val method = checkNotNull(row.method)
            assertTrue(row.matches(method, sample), "open row does not match its own template: ${row.template}")
            val clash = owner.filter { it.matches(method, sample) }
            assertEquals(emptyList(), clash.map { it.template }, "open path ${row.template} also matches owner rows")
        }
    }
}
