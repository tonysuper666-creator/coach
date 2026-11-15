package com.coach.desktop.books

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Controlled native substitute, with parent-side observation outside the AppContainer. */
internal class SyntheticIsolatedCodex(val fixture: OwnedBookConsentTest.Fixture, val mode: String, neighbor: Path? = null) {
    val root = fixture.root
    val auth = Files.writeString(Files.createTempDirectory(root, "SYNTHETIC-auth-").resolve("auth.json"), """{"SYNTHETIC":"ORIGINAL_NOT_A_CREDENTIAL"}""")
    val original = Files.readAllBytes(auth)
    val executable: Path
    val runs = java.util.Collections.synchronizedList(mutableListOf<WindowsBookRun>())
    val receipts = java.util.Collections.synchronizedList(mutableListOf<WindowsBookIsolationReceipt>())
    val argv = java.util.Collections.synchronizedList(mutableListOf<Pair<List<String>, Int>>())
    var beforePhaseReceipt: (WindowsBookIsolationReceipt) -> Unit = {}
    var prepare: (WindowsBookRun) -> Unit = {}
    init {
        val source = root.resolve("SyntheticBookCodex.cs")
        val text = Files.readString(repo.resolve("desktopApp/src/test/resources/synthetic-transport/SyntheticBookCodex.cs"))
            .replace("__SYNTHETIC_MODE__", mode)
            .replace("__SYNTHETIC_ROOT__", root.toString().replace("\"", "\"\""))
            .replace("__SYNTHETIC_NEIGHBOR__", neighbor?.toString()?.replace("\"", "\"\"").orEmpty())
        Files.writeString(source, text)
        executable = compile(root.resolve("synthetic-codex.exe"), listOf(source))
    }
    fun transport(timeout: Long = 5000, commitHelper: Path = committer, isolationHelper: Path = helper): LocalCodexBookTransport {
        var observation: Observation? = null
        return LocalCodexBookTransport(executable, timeoutMillis = timeout,
            isolation = WindowsBookIsolation(isolationHelper, commitHelper, auth),
            receipt = { args, exit -> argv.add(args to exit) },
            isolationReceipt = { receipt -> observation?.pump(); receipts.add(receipt); beforePhaseReceipt(receipt) },
            observeRun = { run ->
                runs.add(run)
                prepare(run)
                Observation(run.home, root).also { observation = it }
            })
    }
    fun clean() {
        assertTrue(runs.all { !Files.exists(it.work) && !Files.exists(it.home) }, "Transport run directories must be gone")
        assertTrue(receipts.all { it.cleaned }, "Every completed helper must report cleanup")
    }
    private class Observation(private val home: Path, private val destination: Path) : AutoCloseable {
        private val stopped = AtomicBoolean()
        private val captured = mutableSetOf<String>()
        @Synchronized fun pump() {
            for (name in listOf("argv.json", "cwd", "pids", "adapter-started", "login-environment.json", "exec-environment.json"))
                runCatching {
                    if (name in captured) return@runCatching
                    val source = home.resolve(name)
                    if (!Files.isRegularFile(source)) return@runCatching
                    val bytes = Files.readAllBytes(source)
                    val value = String(bytes, Charsets.UTF_8)
                    if (value.isEmpty() || (name == "pids" && !Regex("[0-9]+,[0-9]+").matches(value))) return@runCatching
                    if (name.endsWith(".json")) Json.parseToJsonElement(value)
                    val temporary = Files.createTempFile(destination, "SYNTHETIC-observation-", ".tmp")
                    try { Files.write(temporary, bytes); Files.move(temporary, destination.resolve(name), ATOMIC_MOVE, REPLACE_EXISTING); captured.add(name) }
                    finally { Files.deleteIfExists(temporary) }
                }
        }
        private val thread = Thread({ while (!stopped.get()) { pump(); try { Thread.sleep(5) } catch (_: InterruptedException) { break } } }, "synthetic-book-observer").apply { start() }
        override fun close() { stopped.set(true); thread.interrupt(); thread.join(2000); assertTrue(!thread.isAlive); pump() }
    }
    companion object {
        val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("desktopApp/windows-iso/CoachIso.cs")) }
        private val helperRoot by lazy { Files.createTempDirectory("SYNTHETIC-isolation-tools-").also { it.toFile().deleteOnExit() } }
        val helper by lazy { compile(helperRoot.resolve("coach-iso.exe"), listOf(repo.resolve("desktopApp/windows-iso/CoachIso.cs"))) }
        val committer by lazy { compile(helperRoot.resolve("coach-auth.exe"), listOf(repo.resolve("desktopApp/windows-auth/CoachAuthCommit.cs"))) }
        val commitHarness by lazy { compile(helperRoot.resolve("coach-auth-harness.exe"), listOf(repo.resolve("desktopApp/windows-auth/CoachAuthCommit.cs"),
            repo.resolve("desktopApp/src/test/resources/synthetic-auth/AuthTransactionHarness.cs")), "AuthTransactionHarness") }
        fun compile(output: Path, sources: List<Path>, main: String? = null): Path {
            val compiler = Path.of(System.getenv("SystemRoot"), "Microsoft.NET/Framework64/v4.0.30319/csc.exe")
            val command = listOf(compiler.toString(), "/nologo", "/out:$output") + (main?.let { listOf("/main:$it") } ?: emptyList()) + sources.map(Path::toString)
            val log = output.resolveSibling(output.fileName.toString() + ".compile.log")
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try { assertTrue(process.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), "Synthetic C# compile") }
            finally { if (process.isAlive) process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            log.toFile().deleteOnExit()
            output.toFile().deleteOnExit()
            return output
        }
    }
}
