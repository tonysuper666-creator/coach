package com.coach.desktop.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 本机批注草稿的存储合同（只存当前用户自己还没保存成功的文字，仅在本机）。
 *
 * 草稿按“真实持有的身份”绑定：scope（宿主给的拥有者/课程/书库身份，缺失=整个存储停用）、文档 id、资料版本、PDF 页、
 * 锚点（UTF-16 偏移 + 页文字哈希）。任何一项不同都读不到；不编造远端映射；不上传；远端 ack 之前不清除，也不自动补发。
 */
data class DraftIdentity(val scope: String, val doc: String, val version: String, val pdfPage: Int, val start: Int, val end: Int, val textSha256: String)

/**
 * @param baseRevision 用户编辑时依据的远端修订号；旧文件没有这个字段时为 null——此时不能猜成"最新修订"去覆盖，只有确认服务端没有这条批注才可当新建。
 * @param cleared 用户把既有非空批注改成了空（不是新的空白草稿），文字为空但这是一次真实的未提交修改。
 */
data class StoredDraft(val identity: DraftIdentity, val noteId: String, val quote: String, val note: String, val baseRevision: Long? = null, val cleared: Boolean = false)

interface NoteDraftStore {
    /** 宿主给的拥有者/课程/书库身份；空白表示没有可靠身份，存储停用。 */
    val scope: String
    /** 返回是否真的写入了存储；I/O 失败只返回 false，不抛异常。 */
    fun put(draft: StoredDraft): Boolean
    fun get(identity: DraftIdentity): StoredDraft?
    /** 这本书这个版本这一页上的草稿（身份必须全部匹配）。 */
    fun listFor(doc: String, version: String, pdfPage: Int): List<StoredDraft>
    fun remove(identity: DraftIdentity)
}

/** 目录里一份草稿一个 JSON（schema=1）；文件名是身份的哈希，不含文档 id/正文；写入先临时文件再原子替换。 */
class FileNoteDraftStore internal constructor(
    private val directory: Path,
    override val scope: String,
    private val moveFile: (Path, Path) -> Unit,
    private val pause: (Long) -> Unit,
    private val retryDelaysMs: LongArray = DEFAULT_RETRY_DELAYS_MS,
) : NoteDraftStore {
    constructor(directory: Path, scope: String) : this(directory, scope, ::atomicReplace, Thread::sleep)

    private val enabled get() = scope.isNotBlank()
    private val dirLock: Any = lockFor(directory)

    private fun key(i: DraftIdentity): String {
        val canonical = listOf(i.scope, i.doc, i.version, i.pdfPage, i.start, i.end, i.textSha256).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun file(i: DraftIdentity) = directory.resolve(key(i) + ".json")

    private fun replaceWithRetry(tmp: Path, target: Path) {
        var attempt = 0
        while (true) {
            try {
                moveFile(tmp, target)
                return
            } catch (e: IOException) {
                if (!isTransientReplaceLock(e) || attempt >= retryDelaysMs.size) throw e
                val delayMs = retryDelaysMs[attempt++]
                if (delayMs > 0L) {
                    try {
                        pause(delayMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
        }
    }

    override fun put(draft: StoredDraft): Boolean {
        if (!enabled || draft.identity.scope != scope || (draft.note.isEmpty() && !draft.cleared)) return false
        return synchronized(dirLock) {
            var tmp: Path? = null
            var moved = false
            try {
                Files.createDirectories(directory)
                val i = draft.identity
                val body = buildJsonObject {
                    put("schema", 1); put("scope", i.scope); put("doc", i.doc); put("version", i.version); put("pdfPage", i.pdfPage)
                    put("start", i.start); put("end", i.end); put("textSha256", i.textSha256)
                    put("noteId", draft.noteId); put("quote", draft.quote); put("note", draft.note)
                    draft.baseRevision?.let { put("baseRevision", it) }
                    if (draft.cleared) put("cleared", true)
                }
                val tempFile = Files.createTempFile(directory, "draft-", ".tmp")
                tmp = tempFile
                Files.write(tempFile, body.toString().toByteArray(Charsets.UTF_8))
                replaceWithRetry(tempFile, file(i))
                moved = true
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            } catch (_: UnsupportedOperationException) {
                false
            } finally {
                if (!moved) tmp?.let { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun parse(path: Path): StoredDraft? {
        val o = runCatching { Json.parseToJsonElement(String(Files.readAllBytes(path), Charsets.UTF_8)).jsonObject }.getOrNull() ?: return null
        if ((o["schema"] as? JsonPrimitive)?.intOrNull != 1) return null
        fun s(k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun n(k: String): Int? = (o[k] as? JsonPrimitive)?.intOrNull
        val identity = DraftIdentity(s("scope") ?: return null, s("doc") ?: return null, s("version") ?: return null, n("pdfPage") ?: return null,
            n("start") ?: return null, n("end") ?: return null, s("textSha256") ?: return null)
        val base = (o["baseRevision"] as? JsonPrimitive)?.longOrNull
        val cleared = (o["cleared"] as? JsonPrimitive)?.booleanOrNull == true
        return StoredDraft(identity, s("noteId") ?: return null, s("quote") ?: return null, s("note") ?: return null, base, cleared)
    }

    override fun get(identity: DraftIdentity): StoredDraft? {
        if (!enabled || identity.scope != scope) return null
        return synchronized(dirLock) {
            val path = file(identity).takeIf { Files.isRegularFile(it) } ?: return null
            parse(path)?.takeIf { it.identity == identity } // 文件内容的身份必须和请求逐项一致
        }
    }

    override fun listFor(doc: String, version: String, pdfPage: Int): List<StoredDraft> {
        if (!enabled) return emptyList()
        return synchronized(dirLock) {
            if (!Files.isDirectory(directory)) return emptyList()
            Files.list(directory).use { s ->
                s.filter { it.fileName.toString().endsWith(".json") }.collect(java.util.stream.Collectors.toList()).mapNotNull { parse(it) }
            }.filter { it.identity.scope == scope && it.identity.doc == doc && it.identity.version == version && it.identity.pdfPage == pdfPage }
                .sortedBy { it.identity.start }
        }
    }

    override fun remove(identity: DraftIdentity) {
        if (!enabled || identity.scope != scope) return
        synchronized(dirLock) {
            runCatching { Files.deleteIfExists(file(identity)) } // 只删这一个身份对应的文件；失败不抛
        }
    }

    internal companion object {
        val DEFAULT_RETRY_DELAYS_MS = longArrayOf(10L, 20L, 40L, 80L, 120L)
        private val stripedLocks = Array(64) { Any() }

        private fun lockFor(directory: Path): Any {
            val idx = (directory.toAbsolutePath().normalize().hashCode() and 0x7fffffff) % stripedLocks.size
            return stripedLocks[idx]
        }

        internal fun isTransientReplaceLock(error: IOException): Boolean =
            error is AccessDeniedException || error.javaClass == FileSystemException::class.java

        internal fun atomicReplace(tmp: Path, target: Path) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

/** 本机草稿按真实书库或已登记配对分别隔离；通用 HTTP/合成来源不能自行取得存储。 */
object ReaderDraftScope {
    fun forSource(source: ReaderSource, libraryDir: Path?): FileNoteDraftStore? {
        if (source !is LocalBooksReaderSource || libraryDir == null) return null
        val abs = libraryDir.toAbsolutePath().normalize().toString()
        val id = MessageDigest.getInstance("SHA-256").digest(abs.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        return FileNoteDraftStore(libraryDir.resolve("reader-drafts"), "local-library:$id")
    }

}
