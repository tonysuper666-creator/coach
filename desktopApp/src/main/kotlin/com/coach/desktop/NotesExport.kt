package com.coach.desktop

import com.coach.client.CourseView
import com.coach.domain.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class NotesSelection(val week: Int? = null, val pointId: String? = null) {
    val label get() = week?.let { "第 $it 周" } ?: "知识点"
}
data class NotesDraft(val markdown: String, val attemptIds: List<String>, val lastSeq: Long)

/** Freeze the same view and mistake selector used by the visible review screen. No server DB access. */
fun notesRequest(view: CourseView, course: String, selection: NotesSelection): JsonObject {
    require(view.events.isNotEmpty()) { "课程尚无账本，请先同步" }
    val points = weekPoints(view.events).filter { p -> selection.week?.let { it in p.weeks } ?: (p.id == selection.pointId) }
    require(points.isNotEmpty() || selection.week != null) { "找不到该知识点" }
    val wrong = pendingChoiceMistakes(view.events, points.map { it.id }.toSet())
    return buildJsonObject {
        put("selection", buildJsonObject { selection.week?.let { put("week", it) }; selection.pointId?.let { put("point_id", it) } })
        put("snapshot", buildJsonObject {
            put("course", course)
            put("events", JsonArray(view.events.map { e -> buildJsonObject { put("seq", e.seq); put("type", e.type); put("payload", e.payload) } }))
            put("points", JsonArray(emptyList()))
            for (key in listOf("questions", "keys", "pool", "constructed")) put(key, JsonObject(emptyMap()))
            put("caches", JsonArray(emptyList()))
            put("mistake_policy", "pending")
            put("selected_attempt_ids", JsonArray(wrong.map { JsonPrimitive(it.payload.str("attemptId")) }))
            put("mastery_labels", buildJsonObject { view.kps.forEach { kp -> put(kp.id, kp.status.label) } })
        })
    }
}

/** Bundled Python is code only; private data travels through pipes and is never written to temp files. */
object NotesCore {
    suspend fun build(request: JsonObject): NotesDraft = withContext(Dispatchers.IO) {
        val configured = System.getenv("COACH_PYTHON")
        val executable = configured ?: listOf("/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3").firstOrNull { File(it).canExecute() } ?: "python3"
        val bundle = Files.createTempFile("coach-notes-core-", ".zip")
        try {
            checkNotNull(NotesCore::class.java.getResourceAsStream("/notes-core.zip")) { "安装包缺少笔记提取器" }.use { input -> Files.newOutputStream(bundle).use { input.copyTo(it) } }
            val bootstrap = "import sys,runpy; sys.path.insert(0,sys.argv[1]); runpy.run_module('notes.desktop',run_name='__main__')"
            val process = try { ProcessBuilder(executable, "-I", "-c", bootstrap, bundle.toString()).start() }
                catch (_: Exception) { error("无法启动 Python 3.10+；请安装或设置 COACH_PYTHON") }
            val readers = Executors.newFixedThreadPool(3)
            try {
                val stdout = readers.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
                val stderr = readers.submit<String> { process.errorStream.bufferedReader().use { it.readText() } }
                val writer = readers.submit { process.outputStream.bufferedWriter().use { it.write(request.toString()) } }
                if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); error("笔记提取超时，请缩小范围重试") }
                val raw = stdout.get(5, TimeUnit.SECONDS)
                stderr.get(5, TimeUnit.SECONDS) // Drain; never log potentially private diagnostics.
                writer.get(5, TimeUnit.SECONDS)
                val result = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    ?: error("笔记提取器无法运行，请确认 Python 版本为 3.10 或更新")
                result["error"]?.let { error(it.jsonPrimitive.content) }
                check(process.exitValue() == 0) { "笔记提取失败" }
                NotesDraft(result.getValue("markdown").jsonPrimitive.content,
                    result.getValue("attempt_ids").jsonArray.map { it.jsonPrimitive.content }, result.getValue("last_seq").jsonPrimitive.long)
            } finally {
                if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
                readers.shutdownNow()
            }
        } finally { Files.deleteIfExists(bundle) }
    }
}

/** Private files appear as one new folder only after all requested formats have succeeded. */
fun saveNotes(directory: Path, draft: NotesDraft, pdf: ByteArray?): Path {
    val chosen = directory.toRealPath()
    require(Files.isDirectory(chosen)) { "请选择已有文件夹" }
    require(generateSequence(chosen) { it.parent }.none { Files.exists(it.resolve(".git")) }) { "请选择 Git 仓库之外的私人目录" }
    val staging = Files.createTempDirectory(chosen, ".coach-notes-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    try {
        fun save(name: String, bytes: ByteArray) {
            Files.write(staging.resolve(name), bytes)
            Files.setPosixFilePermissions(staging.resolve(name), PosixFilePermissions.fromString("rw-------"))
        }
        save("复习提纲.md", draft.markdown.toByteArray(Charsets.UTF_8))
        if (pdf != null) save("复习提纲.pdf", pdf)
        val target = chosen.resolve("Coach-复习-${java.time.LocalDate.now()}-${UUID.randomUUID().toString().take(8)}")
        return Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        if (Files.exists(staging)) { Files.list(staging).use { paths -> paths.forEach { Files.deleteIfExists(it) } }; Files.deleteIfExists(staging) }
    }
}
