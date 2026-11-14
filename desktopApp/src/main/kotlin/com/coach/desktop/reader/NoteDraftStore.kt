package com.coach.desktop.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

data class StoredDraft(val identity: DraftIdentity, val noteId: String, val quote: String, val note: String)

interface NoteDraftStore {
    /** 宿主给的拥有者/课程/书库身份；空白表示没有可靠身份，存储停用。 */
    val scope: String
    fun put(draft: StoredDraft)
    fun get(identity: DraftIdentity): StoredDraft?
    /** 这本书这个版本这一页上的草稿（身份必须全部匹配）。 */
    fun listFor(doc: String, version: String, pdfPage: Int): List<StoredDraft>
    fun remove(identity: DraftIdentity)
}

/** 目录里一份草稿一个 JSON（schema=1）；文件名是身份的哈希，不含文档 id/正文；写入先临时文件再原子替换。 */
class FileNoteDraftStore(private val directory: Path, override val scope: String) : NoteDraftStore {
    private val enabled get() = scope.isNotBlank()

    private fun key(i: DraftIdentity): String {
        val canonical = listOf(i.scope, i.doc, i.version, i.pdfPage, i.start, i.end, i.textSha256).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun file(i: DraftIdentity) = directory.resolve(key(i) + ".json")

    override fun put(draft: StoredDraft) {
        if (!enabled || draft.identity.scope != scope || draft.note.isEmpty()) return
        Files.createDirectories(directory)
        val i = draft.identity
        val body = buildJsonObject {
            put("schema", 1); put("scope", i.scope); put("doc", i.doc); put("version", i.version); put("pdfPage", i.pdfPage)
            put("start", i.start); put("end", i.end); put("textSha256", i.textSha256)
            put("noteId", draft.noteId); put("quote", draft.quote); put("note", draft.note)
        }
        val tmp = Files.createTempFile(directory, "draft-", ".tmp")
        Files.writeString(tmp, body.toString())
        Files.move(tmp, file(i), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun parse(path: Path): StoredDraft? {
        val o = runCatching { Json.parseToJsonElement(Files.readString(path)).jsonObject }.getOrNull() ?: return null
        if ((o["schema"] as? JsonPrimitive)?.intOrNull != 1) return null
        fun s(k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun n(k: String): Int? = (o[k] as? JsonPrimitive)?.intOrNull
        val identity = DraftIdentity(s("scope") ?: return null, s("doc") ?: return null, s("version") ?: return null, n("pdfPage") ?: return null,
            n("start") ?: return null, n("end") ?: return null, s("textSha256") ?: return null)
        return StoredDraft(identity, s("noteId") ?: return null, s("quote") ?: return null, s("note") ?: return null)
    }

    override fun get(identity: DraftIdentity): StoredDraft? {
        if (!enabled || identity.scope != scope) return null
        val path = file(identity).takeIf { Files.isRegularFile(it) } ?: return null
        return parse(path)?.takeIf { it.identity == identity } // 文件内容的身份必须和请求逐项一致
    }

    override fun listFor(doc: String, version: String, pdfPage: Int): List<StoredDraft> {
        if (!enabled || !Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { s ->
            s.filter { it.fileName.toString().endsWith(".json") }.toList().mapNotNull { parse(it) }
        }.filter { it.identity.scope == scope && it.identity.doc == doc && it.identity.version == version && it.identity.pdfPage == pdfPage }
            .sortedBy { it.identity.start }
    }

    override fun remove(identity: DraftIdentity) {
        if (!enabled || identity.scope != scope) return
        Files.deleteIfExists(file(identity)) // 只删这一个身份对应的文件
    }
}
