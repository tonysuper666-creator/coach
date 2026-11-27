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

    private fun normalize(text: String) = text
        .replace(Regex("""\$\{[^}]*}"""), "{x}")
        .replace(Regex("""\$[A-Za-z_][A-Za-z0-9_]*"""), "{x}")
        .substringBefore('?')

    /**
     * Endpoint paths in Kotlin source lines. Three shapes count as a server call:
     * - a literal that starts with a base-URL template: `"$base/…"`, `"${identity.serverUrl.trimEnd('/')}/…"`;
     * - a literal appended to a base URL: `baseUrl.trimEnd('/') + "/…"`;
     * - a literal with a known API prefix anywhere (`"v1/library/…"` passed to a helper).
     * `"$base/v1/books/${id.encode()}/status?x=1"` becomes `/v1/books/{x}/status`.
     */
    internal fun endpointsIn(lines: Sequence<String>): Set<String> {
        val found = sortedSetOf<String>()
        for (line in lines) for (match in literal.findAll(line)) {
            val raw = match.groupValues[1]
            val base = baseTemplate.find(raw)
            val baseExpr = base?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            val before = line.substring(0, match.range.first)
            val path = when {
                base != null && isBaseUrl(baseExpr!!) -> "/" + normalize(base.groupValues[3])
                raw.startsWith("/") && appended.containsMatchIn(before) -> normalize(raw)
                else -> endpointStart.find(normalize(raw))?.let { "/" + normalize(raw).substring(it.range.first) }
            } ?: continue
            // `"$base/$path"` is a helper forwarding a literal that is scanned where it is written.
            val fixed = path.split('/').any { it.isNotEmpty() && it != "{x}" }
            if (fixed && !path.contains(' ')) found += path
        }
        return found
    }
    private val baseTemplate = Regex("""^\$(?:\{([^}]*)}|([A-Za-z_][A-Za-z0-9_]*))/(.*)$""")
    /** `identity.serverUrl.trimEnd('/')` and `baseUrl` name a server; `document.encodeURLPathPart()` does not. */
    private fun isBaseUrl(expression: String) =
        expression.replace(Regex("""\.\w+\([^)]*\)"""), "").substringAfterLast('.').contains(Regex("(?i)^(base|.*url)$"))
    private val appended = Regex("""(?i)(base|url)[^"]*\+\s*$""")

    private fun clientPaths(): Map<String, Set<String>> {
        val found = sortedMapOf<String, MutableSet<String>>()
        for (root in clientRoots.map(repo::resolve).filter { it.isDirectory() }) {
            Files.walk(root).use { files ->
                files.filter { it.extension == "kt" && !testDirs.containsMatchIn(repo.relativize(it).invariantSeparatorsPathString) }
                    .forEach { file ->
                        for (path in endpointsIn(file.readLines().asSequence())) {
                            found.getOrPut(path) { sortedSetOf() } += repo.relativize(file).invariantSeparatorsPathString
                        }
                    }
            }
        }
        return found
    }

    private fun unregistered(paths: Collection<String>) = paths.filter { path -> PublicApiTable.routes.none { covers(it.template, path) } }

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
        val missing = paths.filterKeys { it in unregistered(paths.keys) }
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

    @Test fun aNewUnregisteredClientCallFailsEvenUnderANewPrefix() {
        val synthetic = sequenceOf(
            "val a = http.get(\"\$base/v2/brand-new/\${id.encodeURLPathPart()}\")",
            "val b = client.post(\"\${identity.serverUrl.trimEnd('/')}/fresh/thing?x=1\")",
            "private val url = baseUrl.trimEnd('/') + \"/other/new\"",
            "postCourseEndpoint(\"v1/library/not-yet-registered\", input)",
            "val known = http.get(\"\$base/v1/books\")",
            "val file = Path.of(\"\$dir/notes.md\")",
            "val label = \"layout=shuffled-v1\"",
        )
        val found = endpointsIn(synthetic)
        assertEquals(setOf("/v2/brand-new/{x}", "/fresh/thing", "/other/new", "/v1/library/not-yet-registered", "/v1/books"), found)
        assertEquals(listOf("/fresh/thing", "/other/new", "/v1/library/not-yet-registered", "/v2/brand-new/{x}"), unregistered(found).sorted())
    }
}
