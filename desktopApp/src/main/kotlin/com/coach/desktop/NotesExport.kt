package com.coach.desktop

import com.coach.client.CourseView
import com.coach.domain.*
import java.io.File
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.*
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
            // -I ignores PYTHONUTF8/PYTHONIOENCODING; the pipe protocol must travel with the app.
            val process = try { ProcessBuilder(executable, "-I", "-X", "utf8", "-c", bootstrap, bundle.toString()).start() }
                catch (_: Exception) { error("无法启动 Python 3.10+；请安装或设置 COACH_PYTHON") }
            val readers = Executors.newFixedThreadPool(3)
            try {
                val stdout = readers.submit<String> { process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
                val stderr = readers.submit<String> { process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
                val writer = readers.submit { process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(request.toString()) } }
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
    return saveNotes(directory, draft, pdf, NotesExportFiles())
}

internal fun saveNotes(directory: Path, draft: NotesDraft, pdf: ByteArray?, files: NotesExportFiles): Path {
    val chosen = directory.toRealPath()
    require(Files.isDirectory(chosen)) { "请选择已有文件夹" }
    require(generateSequence(chosen) { it.parent }.none { Files.exists(it.resolve(".git")) }) { "请选择 Git 仓库之外的私人目录" }
    val staging = Files.createTempDirectory(chosen, ".coach-notes-", files.permissions(chosen, true))
    var failure: Throwable? = null
    try {
        files.verifyPrivate(staging, true)
        files.write(staging.resolve("复习提纲.md"), draft.markdown.toByteArray(Charsets.UTF_8))
        if (pdf != null) files.write(staging.resolve("复习提纲.pdf"), pdf)
        val target = chosen.resolve("Coach-复习-${java.time.LocalDate.now()}-${UUID.randomUUID()}")
        return files.publish(staging, target)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            if (Files.exists(staging, NOFOLLOW_LINKS)) {
                Files.list(staging).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
                Files.deleteIfExists(staging)
            }
        } catch (cleanup: Exception) {
            if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
        }
    }
}

/** Native permissions are established on creation, then verified before any private bytes are written. */
internal open class NotesExportFiles {
    private fun acl(path: Path, directory: Boolean): List<AclEntry> {
        val user = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
        return listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
            .setPermissions(*AclEntryPermission.values()).apply {
                if (directory) setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
            }.build())
    }

    fun permissions(parent: Path, directory: Boolean): FileAttribute<*> {
        val store = Files.getFileStore(parent)
        if (store.supportsFileAttributeView(PosixFileAttributeView::class.java))
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
        check(store.supportsFileAttributeView(AclFileAttributeView::class.java)) { "笔记导出需要私有文件权限" }
        val entries = acl(parent, directory)
        return object : FileAttribute<List<AclEntry>> {
            override fun name() = "acl:acl"
            override fun value() = entries
        }
    }

    open fun verifyPrivate(path: Path, directory: Boolean) {
        check(!Files.isSymbolicLink(path) && if (directory) Files.isDirectory(path, NOFOLLOW_LINKS) else Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            "无法保护导出文件"
        }
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
        if (posix != null) {
            check(posix.readAttributes().permissions() == PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------")) { "无法保护导出文件" }
        } else {
            val view = checkNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)) { "笔记导出需要私有文件权限" }
            check(view.acl == acl(path, directory)) { "无法保护导出文件" }
        }
    }

    open fun write(path: Path, bytes: ByteArray) {
        Files.createFile(path, permissions(path.parent, false))
        verifyPrivate(path, false)
        Files.write(path, bytes, WRITE, NOFOLLOW_LINKS)
    }

    open fun publish(staging: Path, target: Path): Path {
        if (Files.exists(target, NOFOLLOW_LINKS)) throw FileAlreadyExistsException(target.toString())
        // Keep staging on the selected volume; never fall back to copy/delete on failure.
        return Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
    }
}
