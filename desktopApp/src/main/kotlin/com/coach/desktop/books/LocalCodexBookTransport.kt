package com.coach.desktop.books

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class LocalCodexProblem(val message: String) {
    MISSING("这台电脑上没有可用的 Codex，讲解用不了；原文和做题不受影响。"),
    LOGIN("这台电脑上的 Codex 还没有登录订阅账号，请先登录后再试。"),
    AUTH_REFRESH("Codex 登录状态需要重新确认，讲解已停止。请重新登录 Codex 后再试。"),
    MODEL("当前 Codex 账号用不了这个模型，讲解没有生成；原文和做题不受影响。"),
    TIMEOUT("Codex 讲解超时了，这次请求已停止，可以稍后重试。"),
    FAILED("Codex 这次运行失败，讲解没有生成，可以稍后重试。"),
}
internal class LocalCodexFailure(val reason: LocalCodexProblem) : Exception(reason.name)

/** Locate an installed executable, including npm's native Windows binary; never run a shell wrapper. */
internal fun discoverLocalCodex(
    path: String = System.getenv("PATH").orEmpty(),
    windows: Boolean = System.getProperty("os.name").startsWith("Windows", true),
): Path? {
    val names = if (windows) listOf("codex.exe") else listOf("codex")
    for (entry in path.split(java.io.File.pathSeparator).filter { it.isNotBlank() }) {
        val directory = runCatching { Path.of(entry.trim('"')) }.getOrNull() ?: continue
        for (name in names) {
            val candidate = directory.resolve(name)
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toAbsolutePath()
        }
        if (windows) {
            val npm = directory.resolve("node_modules/@openai/codex/node_modules/@openai")
            for ((pkg, arch) in listOf("codex-win32-x64" to "x86_64", "codex-win32-arm64" to "aarch64")) {
                val candidate = npm.resolve("$pkg/vendor/$arch-pc-windows-msvc/bin/codex.exe")
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toAbsolutePath()
            }
        }
    }
    return null
}

/** Explicit, trusted installation paths. The default is unavailable until the owner wires them. */
internal data class WindowsBookIsolation(val helper: Path, val authCommitHelper: Path, val authSource: Path)

/** Only allowlisted helper metadata; never expose child output, auth or arbitrary diagnostics. */
internal data class WindowsBookIsolationReceipt(val phase: String, val exit: Int, val childPid: Long?,
    val containerSid: String?, val verified: Boolean, val cleaned: Boolean, val createError: Int?)

internal data class WindowsBookRun(val work: Path, val home: Path)

/** The helper is trusted code; a partial child output is never a success receipt. */
internal fun validIsolationReceipt(log: String): Boolean {
    val lines = log.lineSequence().map { it.trim() }.toList()
    val pid = lines.singleOrNull { Regex("COACH_ISO_VERIFIED pid=[0-9]+ appcontainer=1").matches(it) }
        ?.substringAfter("pid=")?.substringBefore(' ') ?: return false
    return lines.none { it.startsWith("COACH_ISO_REFUSED") } &&
        lines.any { Regex("COACH_ISO_SID S-1-15-2-[0-9-]+").matches(it) } &&
        lines.any { it == "COACH_ISO_RESUMED pid=$pid" } &&
        lines.any { it == "COACH_ISO_CHILD_EXIT 0" } &&
        lines.any { it == "COACH_ISO_ACL_REVOKED 3" } &&
        lines.any { it == "COACH_ISO_PROFILE_DELETED hr=0x0" }
}

/** One local subscription request. No Python bridge, pairing, API key, model switch, or raw diagnostic logging. */
internal class LocalCodexBookTransport(
    private val executable: Path,
    private val identity: OwnedBookConsentIdentity = OwnedBookConsentIdentity(),
    private val timeoutMillis: Long = 180_000,
    private val commandPrefix: List<String> = listOf(executable.toString()),
    private val receipt: (List<String>, Int) -> Unit = { _, _ -> },
    private val isolation: WindowsBookIsolation? = null,
    private val isolationReceipt: (WindowsBookIsolationReceipt) -> Unit = {},
    private val observeRun: (WindowsBookRun) -> AutoCloseable = { AutoCloseable {} },
) : OwnedBookCliTransport {
    override suspend fun complete(request: String): String = withContext(Dispatchers.IO) {
        val lease = currentCoroutineContext()[BookConsentLease] ?: throw LocalCodexFailure(LocalCodexProblem.FAILED)
        require(request.toByteArray(Charsets.UTF_8).size <= 1_048_576)
        val config = isolation ?: throw LocalCodexFailure(LocalCodexProblem.FAILED)
        if (!System.getProperty("os.name").startsWith("Windows", true)) throw LocalCodexFailure(LocalCodexProblem.FAILED)
        if (commandPrefix != listOf(executable.toString())) throw LocalCodexFailure(LocalCodexProblem.FAILED)
        val root = Files.createTempDirectory("coach-book-codex-")
        val expectedRoot = root.toRealPath()
        val cwd = Files.createDirectory(root.resolve("work"))
        val output = cwd.resolve("answer.txt")
        var snapshot: CodexAuthSnapshot? = null
        var observation: AutoCloseable? = null
        try {
            val runtime = Files.createDirectory(root.resolve("runtime"))
            if (!Files.isRegularFile(executable) || !Files.isRegularFile(config.helper) || !Files.isRegularFile(config.authCommitHelper))
                throw LocalCodexFailure(LocalCodexProblem.MISSING)
            val isolatedExecutable = Files.copy(executable, runtime.resolve("codex.exe"))
            snapshot = CodexAuthSnapshot.create(config.authSource, root, WindowsAuthAtomicCommit(config.authCommitHelper))
            val home = snapshot.home
            observation = observeRun(WindowsBookRun(cwd, home))
            val login = executeIsolated(config, isolatedExecutable, listOf("login", "status"), root, cwd, runtime, home, null, lease, 15_000)
            // API-key sessions must not fall back to paid API usage.
            if (login.first != 0 || !login.second.contains("Logged in using ChatGPT", true))
                throw LocalCodexFailure(LocalCodexProblem.LOGIN)
            val argv = listOf("exec", "-m", identity.model, "-c", "model_reasoning_effort=low",
                "--ephemeral", "--skip-git-repo-check", "-s", "read-only", "--ignore-user-config") +
                listOf("shell_tool", "unified_exec", "apps", "plugins", "browser_use", "computer_use",
                    "in_app_browser", "image_generation", "view_image", "multi_agent").flatMap { listOf("-c", "features.$it=false") } +
                // CLI 0.167 still exposes collaboration tools with multi_agent=false (even v2=false).
                // The native-tool switch is the primary control. Stderr only reveals failed calls
                // under the default log filter; it cannot detect every successful tool invocation.
                listOf("-c", "features.multi_agent_v2.disable_native_tools=true", "-c", "web_search=disabled", "--json", "-o", output.toString(), "-")
            val prompt = "Use only the supplied excerpts. Return only a JSON object with exactly two fields: " +
                "answer (a useful explanation or answer, not a quotation) and citations (an array of excerpt IDs used). " +
                "Do not use Markdown.\n\n" + request
            val result = executeIsolated(config, isolatedExecutable, argv, root, cwd, runtime, home, prompt, lease, timeoutMillis)
            if (result.first != 0) throw LocalCodexFailure(classifyCodexFailure(result.second))
            if (!Files.isRegularFile(output) || Files.size(output) > 1_048_576) throw LocalCodexFailure(LocalCodexProblem.FAILED)
            val answer = lease.checked { Files.readString(output) }
            currentCoroutineContext().ensureActive()
            lease.checked { Unit }
            // Cancellation interrupts the native commit wait and reaps that helper. Never hold
            // the consent store lock across blocking filesystem/transaction work.
            val authSnapshot = checkNotNull(snapshot)
            val committed = runInterruptible { authSnapshot.commit() }
            if (!committed.cleaned) throw LocalCodexFailure(LocalCodexProblem.FAILED)
            if (committed.status == AuthCommitStatus.SOURCE_CHANGED)
                throw LocalCodexFailure(LocalCodexProblem.FAILED)
            if (committed.status !in setOf(AuthCommitStatus.UNCHANGED, AuthCommitStatus.COMMITTED))
                throw LocalCodexFailure(LocalCodexProblem.AUTH_REFRESH)
            snapshot = null
            currentCoroutineContext().ensureActive()
            lease.checked { answer }
        } catch (cancel: CancellationException) { throw cancel }
        catch (known: LocalCodexFailure) { throw known }
        catch (_: java.io.IOException) { throw LocalCodexFailure(if (!Files.isRegularFile(executable)) LocalCodexProblem.MISSING else LocalCodexProblem.FAILED) }
        finally {
            try { observation?.close() } finally {
                // Discard our entire run before invoking the dependency's close. A failed tree
                // cleanup must not fall through to a less restrictive recursive cleanup.
                discardIsolatedBookRun(root, expectedRoot)
                snapshot?.close()
            }
        }
    }

    private suspend fun executeIsolated(
        config: WindowsBookIsolation, executable: Path, args: List<String>, root: Path, cwd: Path,
        runtime: Path, home: Path, input: String?, lease: BookConsentLease, timeout: Long,
    ): Pair<Int, String> {
        val phase = if (input == null) "login" else "exec"
        val stdin = root.resolve("$phase.in")
        val stdout = root.resolve("$phase.out")
        val stderr = root.resolve("$phase.err")
        if (input != null) Files.writeString(stdin, input)
        val argv = listOf(config.helper.toAbsolutePath().toString(), "--allow-read", runtime.toString(),
            "--allow-write", cwd.toString(), "--allow-write", home.toString(),
            "--capability", "S-1-15-3-1", "--stdout", stdout.toString(), "--stderr", stderr.toString(),
            "--cwd", cwd.toString(), "--timeout-ms", timeout.toString()) +
            (if (input == null) emptyList() else listOf("--stdin", stdin.toString())) +
            listOf("--", executable.toString()) + args
        val builder = ProcessBuilder(argv).directory(cwd.toFile())
        builder.environment().clear()
        System.getenv("SystemRoot")?.let { builder.environment()["SystemRoot"] = it }
        // Windows resolves the AppContainer profile from LOCALAPPDATA during CreateProcess.
        // Its presence grants no filesystem access; only the three per-run roots receive ACEs.
        System.getenv("LOCALAPPDATA")?.let { builder.environment()["LOCALAPPDATA"] = it }
        builder.environment()["CODEX_HOME"] = home.toString()
        val process = lease.checked { builder.start() }
        val tree = LocalCodexProcessTree(process)
        // Keep helper pipes open through cancellation so its finally can report and finish
        // profile/ACL cleanup. These readers are collected below before this method returns.
        val drainJob = SupervisorJob()
        val drains = CoroutineScope(Dispatchers.IO + drainJob)
        val helperOut = drains.async { boundedDiagnostic(process.inputStream) }
        val helperErr = drains.async { boundedDiagnostic(process.errorStream) }
        val reported = AtomicBoolean()
        fun report(exit: Int, log: String) {
            if (!reported.compareAndSet(false, true)) return
            val lines = log.lineSequence().map { it.trim() }.toSet()
            receipt(argv, exit)
            isolationReceipt(WindowsBookIsolationReceipt(phase, exit,
                Regex("(?m)^COACH_ISO_VERIFIED pid=([0-9]+) appcontainer=1\\s*$").find(log)?.groupValues?.get(1)?.toLongOrNull(),
                Regex("(?m)^COACH_ISO_SID (S-1-15-2-[0-9-]+)\\s*$").find(log)?.groupValues?.get(1),
                log.lineSequence().any { it.trim().matches(Regex("COACH_ISO_VERIFIED pid=[0-9]+ appcontainer=1")) },
                "COACH_ISO_ACL_REVOKED 3" in lines && "COACH_ISO_PROFILE_DELETED hr=0x0" in lines,
                Regex("create_process err=([0-9]+)").find(log)?.groupValues?.get(1)?.toIntOrNull()))
        }
        try {
            return withTimeoutOrNull(timeout + 5_000) {
                coroutineScope {
                    val tracker = launch {
                        try { while (process.isAlive) { tree.track(); delay(100) }; awaitCancellation() }
                        finally { tree.stopAsync() }
                    }
                    process.outputStream.close()
                    val exit = suspendCancellableCoroutine<Int> { continuation ->
                        process.onExit().whenComplete { finished, error ->
                            if (error != null) continuation.resumeWithException(error) else continuation.resume(finished.exitValue())
                        }
                        continuation.invokeOnCancellation { tree.stopAsync() }
                    }
                    val out = helperOut.await()
                    val log = helperErr.await()
                    tracker.cancel()
                    report(exit, log)
                    if (exit != 0 || out.isNotBlank() || !validIsolationReceipt(log)) {
                        if (Regex("(?m)^COACH_ISO_REFUSED code=126 reason=timeout\\s*$").containsMatchIn(log))
                            throw LocalCodexFailure(LocalCodexProblem.TIMEOUT)
                        // A verified child failure may supply a diagnostic, never an answer or
                        // stdout. Helper refusals/cleanup failures remain generic failures.
                        val childExit = Regex("(?m)^COACH_ISO_CHILD_EXIT ([0-9]+)\\s*$").find(log)?.groupValues?.get(1)?.toIntOrNull()
                        val lines = log.lineSequence().map { it.trim() }.toSet()
                        if (exit != 0 && childExit == exit && !log.contains("COACH_ISO_REFUSED") &&
                            log.contains(" appcontainer=1") && "COACH_ISO_PROFILE_DELETED hr=0x0" in lines &&
                            "COACH_ISO_ACL_REVOKED 3" in lines && Files.isRegularFile(stderr)) {
                            val diagnostic = Files.newInputStream(stderr).use { boundedDiagnostic(it, rejectTools = input != null) }
                            throw LocalCodexFailure(classifyCodexFailure(diagnostic))
                        }
                        throw LocalCodexFailure(LocalCodexProblem.FAILED)
                    }
                    if (!Files.isRegularFile(stdout) || !Files.isRegularFile(stderr))
                        throw LocalCodexFailure(LocalCodexProblem.FAILED)
                    val diagnostics = Files.newInputStream(stderr).use { boundedDiagnostic(it, rejectTools = input != null) }
                    val childOutput = if (input == null) Files.newInputStream(stdout).use { boundedDiagnostic(it) }
                        else { if (!Files.newInputStream(stdout).use { readCodexEvents(it) }) throw LocalCodexFailure(LocalCodexProblem.FAILED); "" }
                    0 to (childOutput + "\n" + diagnostics)
                }
            } ?: throw LocalCodexFailure(LocalCodexProblem.TIMEOUT)
        } finally {
            withContext(NonCancellable) {
                tree.stop()
                try {
                    runCatching { helperOut.await() }
                    val log = runCatching { helperErr.await() }.getOrDefault("")
                    if (!process.isAlive) report(process.exitValue(), log)
                } finally {
                    drainJob.cancelAndJoin()
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                }
            }
        }
    }

}

/** Pin the real root before launching a child; unlink junctions without traversing their targets. */
internal fun discardIsolatedBookRun(root: Path, expectedRoot: Path) {
    if (!Files.exists(root, NOFOLLOW_LINKS)) return
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            val expected = expectedRoot.resolve(root.relativize(dir)).normalize()
            if (attrs.isOther || dir.toRealPath() != expected) {
                Files.delete(dir)
                return FileVisitResult.SKIP_SUBTREE
            }
            return FileVisitResult.CONTINUE
        }
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
            if (error != null) throw error
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

/** Tools are disabled before launch. This is a second, fail-closed check of the entire JSONL stream.
 * Unknown future item/event kinds are rejected too; never return an answer after a tool event.
 * Raw events can contain excerpts and remain private in memory, never in logs or exceptions.
 */
private fun readCodexEvents(stream: InputStream): Boolean {
    var completed = false
    var answer = false
    var failed = false
    fun reject(): Nothing = throw LocalCodexFailure(LocalCodexProblem.FAILED)
    fun event(line: String) {
        if (line.isBlank()) return
        val json = try { Json.parseToJsonElement(line).jsonObject } catch (_: Exception) { reject() }
        fun kind(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: reject()
        when (kind(json["type"])) {
            "thread.started", "turn.started" -> Unit
            "turn.completed" -> completed = true
            "turn.failed", "error" -> failed = true
            "item.started", "item.updated", "item.completed" -> {
                val item = json["item"] as? JsonObject ?: reject()
                if (kind(item["type"]) !in setOf("agent_message", "reasoning")) reject()
                if (kind(json["type"]) == "item.completed" && kind(item["type"]) == "agent_message") answer = true
            }
            else -> reject()
        }
    }
    stream.bufferedReader(Charsets.UTF_8).use { reader ->
        val line = StringBuilder()
        var total = 0
        while (true) {
            val char = reader.read()
            if (char < 0) break
            if (++total > 8_388_608 || line.length >= 1_048_576) reject()
            if (char == '\n'.code) { event(line.toString()); line.setLength(0) }
            else line.append(char.toChar())
        }
        if (line.isNotEmpty()) event(line.toString())
    }
    return completed && answer && !failed
}

// Some failed CLI tool calls (notably collaboration) have no JSONL item. This supplemental
// guard sees failures logged under the default filter, not every successful call. Inspect every
// stderr byte, including beyond the retained diagnostic limit and across read boundaries; never log it.
private fun boundedDiagnostic(stream: InputStream, rejectTools: Boolean = false): String = stream.use {
    val kept = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var tail = ""
    while (true) {
        val count = it.read(buffer)
        if (count < 0) break
        if (rejectTools) {
            val scanned = tail + String(buffer, 0, count, Charsets.ISO_8859_1).lowercase()
            if ("codex_core::tools::router" in scanned || "collab" in scanned)
                throw LocalCodexFailure(LocalCodexProblem.FAILED)
            tail = scanned.takeLast(24)
        }
        if (kept.size() < 16_384) kept.write(buffer, 0, minOf(count, 16_384 - kept.size()))
    }
    kept.toString(Charsets.UTF_8)
}

internal fun classifyCodexFailure(diagnostic: String): LocalCodexProblem {
    val message = diagnostic.lowercase()
    return when {
        listOf("not logged in", "authentication", "unauthorized", "401", "token expired", "login required").any { it in message } -> LocalCodexProblem.LOGIN
        "model" in message && listOf("not found", "not supported", "does not exist", "not available", "unsupported", "access").any { it in message } -> LocalCodexProblem.MODEL
        else -> LocalCodexProblem.FAILED
    }
}

/** Reap only this helper's descendants; AppContainer supplies the launch-time boundary. */
private class LocalCodexProcessTree(private val process: Process) {
    private val tracked = LinkedHashSet<ProcessHandle>()
    private val reaping = AtomicBoolean(false)
    @Synchronized fun track() { process.descendants().use { stream -> stream.forEach { tracked.add(it) } } }
    fun stopAsync() {
        if (reaping.compareAndSet(false, true))
            Thread(::stop, "local-book-codex-reaper").apply { isDaemon = true }.start()
    }
    @Synchronized fun stop() { reap() }
    private fun reap() {
        track()
        val descendants = tracked.toList()
        if (!process.isAlive && descendants.none { it.isAlive }) return
        var interrupted = Thread.interrupted()
        try {
            // Kill the isolated children first and let the helper observe their exit, revoke its
            // temporary ACEs and delete the profile. Terminating the helper first skips finally.
            val gracefulDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (process.isAlive && System.nanoTime() < gracefulDeadline) {
                track()
                tracked.toList().asReversed().forEach { if (it.isAlive) it.destroyForcibly() }
                try { process.waitFor(10, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { interrupted = true }
            }
            if (process.isAlive) process.destroy()
            val remaining = (descendants + process.descendants().use { it.toList() }).distinct()
            remaining.asReversed().forEach { if (it.isAlive) it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            for (handle in remaining + process.toHandle()) while (handle.isAlive) {
                val nanos = deadline - System.nanoTime()
                if (nanos <= 0) break
                try { handle.onExit().get(nanos, TimeUnit.NANOSECONDS) }
                catch (_: InterruptedException) { interrupted = true }
                catch (_: TimeoutException) { break }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
