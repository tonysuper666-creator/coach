package com.coach.desktop.books

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
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

/** One local subscription request. No Python bridge, pairing, API key, model switch, or raw diagnostic logging. */
internal class LocalCodexBookTransport(
    private val executable: Path,
    private val identity: OwnedBookConsentIdentity = OwnedBookConsentIdentity(),
    private val timeoutMillis: Long = 180_000,
    private val commandPrefix: List<String> = listOf(executable.toString()),
    private val receipt: (List<String>, Int) -> Unit = { _, _ -> },
) : OwnedBookCliTransport {
    override suspend fun complete(request: String): String = withContext(Dispatchers.IO) {
        val lease = currentCoroutineContext()[BookConsentLease] ?: throw LocalCodexFailure(LocalCodexProblem.FAILED)
        require(request.toByteArray(Charsets.UTF_8).size <= 1_048_576)
        val root = Files.createTempDirectory("coach-book-codex-")
        val cwd = Files.createDirectory(root.resolve("work"))
        val output = root.resolve("answer.txt")
        try {
            val login = execute(commandPrefix + listOf("login", "status"), cwd, null, lease, 15_000)
            // API-key sessions must not fall back to paid API usage.
            if (login.first != 0 || !login.second.contains("Logged in using ChatGPT", true))
                throw LocalCodexFailure(LocalCodexProblem.LOGIN)
            val argv = commandPrefix + listOf("exec", "-m", identity.model, "-c", "model_reasoning_effort=low",
                "--ephemeral", "--skip-git-repo-check", "-s", "read-only", "--ignore-user-config") +
                listOf("shell_tool", "unified_exec", "apps", "plugins", "browser_use", "computer_use",
                    "in_app_browser", "image_generation", "view_image", "multi_agent").flatMap { listOf("-c", "features.$it=false") } +
                // CLI 0.167 still exposes collaboration tools with multi_agent=false (even v2=false).
                // Disable the native tools explicitly; stderr/JSONL guards reject observable calls too.
                listOf("-c", "features.multi_agent_v2.disable_native_tools=true", "-c", "web_search=disabled", "--json", "-o", output.toString(), "-")
            val prompt = "Use only the supplied excerpts. Return only a JSON object with exactly two fields: " +
                "answer (a useful explanation or answer, not a quotation) and citations (an array of excerpt IDs used). " +
                "Do not use Markdown.\n\n" + request
            val result = execute(argv, cwd, prompt, lease, timeoutMillis)
            if (result.first != 0) throw LocalCodexFailure(classifyCodexFailure(result.second))
            if (!Files.isRegularFile(output) || Files.size(output) > 1_048_576) throw LocalCodexFailure(LocalCodexProblem.FAILED)
            lease.checked { Files.readString(output) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (known: LocalCodexFailure) { throw known }
        catch (_: java.io.IOException) { throw LocalCodexFailure(if (!Files.isRegularFile(executable)) LocalCodexProblem.MISSING else LocalCodexProblem.FAILED) }
        finally {
            // Codex may create scratch files even with an empty cwd. All request scratch is private and ephemeral.
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private suspend fun execute(argv: List<String>, cwd: Path, input: String?, lease: BookConsentLease, timeout: Long): Pair<Int, String> {
        val builder = ProcessBuilder(argv).directory(cwd.toFile())
        listOf("OPENAI_API_KEY", "CODEX_API_KEY", "AZURE_OPENAI_API_KEY").forEach { builder.environment().remove(it) }
        // Serialize the actual spawn with revoke, not just a check before serializing the prompt.
        val process = lease.checked { builder.start() }
        val tree = LocalCodexProcessTree(process)
        try {
            return withTimeoutOrNull(timeout) {
                coroutineScope {
                    // PR226 972c5a94: remember children before their parent exits and never reap
                    // synchronously on a cancelling UI/timer thread. Finally still awaits cleanup.
                    val tracker = launch {
                        try {
                            while (process.isAlive) { tree.track(); delay(100) }
                            awaitCancellation()
                        } finally { tree.stopAsync() }
                    }
                    var eventsComplete = false
                    val stdout = async { runInterruptible {
                        if (input == null) boundedDiagnostic(process.inputStream)
                        else { eventsComplete = readCodexEvents(process.inputStream); "" }
                    } }
                    val stderr = async { runInterruptible { boundedDiagnostic(process.errorStream, rejectTools = input != null) } }
                    val writer = async { runInterruptible { process.outputStream.use { if (input != null) it.write(input.toByteArray(Charsets.UTF_8)) } } }
                    val exit = suspendCancellableCoroutine<Int> { continuation ->
                        process.onExit().whenComplete { finished, error ->
                            if (error != null) continuation.resumeWithException(error) else continuation.resume(finished.exitValue())
                        }
                        continuation.invokeOnCancellation { tree.stopAsync() }
                    }
                    writer.await()
                    receipt(argv, exit)
                    val diagnostic = stdout.await() + "\n" + stderr.await()
                    tracker.cancel()
                    if (input != null && exit == 0 && !eventsComplete) throw LocalCodexFailure(LocalCodexProblem.FAILED)
                    exit to diagnostic
                }
            } ?: throw LocalCodexFailure(LocalCodexProblem.TIMEOUT)
        } finally { tree.stop() }
    }
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

// Some CLI tool calls (notably collaboration) have no JSONL item. Inspect every stderr byte,
// including beyond the retained diagnostic limit and across read boundaries; never log it.
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

/** PR226 972c5a94 snapshot/reap protocol, not launch-time OS containment. */
private class LocalCodexProcessTree(private val process: Process) {
    private val tracked = LinkedHashSet<ProcessHandle>()
    private val reaping = AtomicBoolean(false)
    @Synchronized fun track() { process.descendants().use { stream -> stream.forEach { tracked.add(it) } } }
    fun stopAsync() {
        if (reaping.compareAndSet(false, true))
            Thread(::stop, "local-book-codex-reaper").apply { isDaemon = true }.start()
    }
    @Synchronized fun stop() {
        try { reap() } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
    private fun reap() {
        track()
        val descendants = tracked.toList()
        if (!process.isAlive && descendants.none { it.isAlive }) return
        var interrupted = Thread.interrupted()
        try {
            process.destroy()
            try { process.waitFor(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { interrupted = true }
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
