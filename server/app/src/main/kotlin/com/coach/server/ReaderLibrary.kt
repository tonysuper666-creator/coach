package com.coach.server

import com.coach.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Explicit, private allowlist. Never discovers PDFs by walking a material directory. */
internal data class ReaderDocument(
    val id: String, val course: String, val title: String, val file: Path,
    val version: String, val pages: Int, val offset: Int?, val kind: String, val week: Int?,
    val outline: List<OutlineNode> = emptyList(),
) {
    fun view() = json("id" to id, "title" to title, "version" to version, "pages" to pages,
        "printedPageOffset" to offset, "kind" to kind, "week" to week)
    fun printedPage(page: Int): Int? = offset?.let { page - it }?.takeIf { it > 0 }
}

/** Chapter (depth 0) or section (depth 1) registered by the catalog at a PDF page; ranges run to the next sibling. Never inferred from text. */
internal data class OutlineNode(val title: String, val page: Int, val sections: List<OutlineNode> = emptyList())

internal fun parseOutline(raw: JsonElement?, pages: Int): List<OutlineNode> {
    val array = raw as? JsonArray ?: return emptyList()
    require(array.size <= 200) { "Reader outline too large" }
    fun node(o: JsonObject, depth: Int): OutlineNode {
        val title = o.str("title"); val page = (o["pdfPage"] as? JsonPrimitive)?.intOrNull
        require(title.length in 1..200 && page != null && page in 1..pages) { "Invalid reader outline" }
        val subs = if (depth == 0) (o["sections"] as? JsonArray).orEmpty().map { node(it.jsonObject, 1) } else emptyList()
        require(subs.all { it.page >= page } && subs.zipWithNext().all { (a, b) -> a.page < b.page }) { "Invalid reader outline order" }
        return OutlineNode(title, page, subs)
    }
    val chapters = array.map { node(it.jsonObject, 0) }
    require(chapters.zipWithNext().all { (a, b) -> a.page < b.page && (a.sections.lastOrNull()?.page ?: 0) < b.page }) { "Invalid reader outline order" }
    return chapters
}

internal class ReaderFailure(val status: io.ktor.http.HttpStatusCode, message: String) : RuntimeException(message)

/** All PDF work is local Poppler. No bridge, network, AI client or source-pack ingestion. */
class ReaderLibrary internal constructor(private val root: Path?, private val bin: String = "") {
    private val lock = Mutex()
    private val verified = mutableMapOf<Path, Pair<String, BasicFileAttributes>>()
    private val documents: List<ReaderDocument> = load()

    private fun load(): List<ReaderDocument> {
        if (root == null) return emptyList()
        val realRoot = root.toRealPath()
        val manifest = realRoot.resolve("catalog.json")
        require(Files.size(manifest) <= 1_048_576) { "Reader catalog too large" }
        val raw = LedgerJson.parseToJsonElement(Files.readString(manifest)).jsonObject
        require(raw.num("schema") == 1L) { "Unsupported reader catalog" }
        val docs = raw.getValue("documents").jsonArray.map { it.jsonObject }.map { d ->
            val id = d.str("id")
            val relative = Path.of(d.str("file"))
            require(Regex("[a-zA-Z0-9_-]{1,80}").matches(id) && !relative.isAbsolute) { "Invalid reader document" }
            val file = realRoot.resolve(relative).normalize().toRealPath()
            require(file.startsWith(realRoot) && Files.isRegularFile(file)) { "Reader file outside library" }
            val version = d.str("sha256")
            val pages = d.num("pages").toInt()
            val offset = (d["printedPageOffset"] as? JsonPrimitive)?.intOrNull
            val kind = d.str("kind")
            val week = (d["week"] as? JsonPrimitive)?.intOrNull
            require(Regex("[0-9a-f]{64}").matches(version) && pages in 1..100_000 &&
                (offset == null || offset in 0 until pages) && kind in setOf("textbook", "slides", "reading") &&
                (kind != "slides" || week != null && week in 1..100) &&
                d.str("course").length in 1..128 && d.str("title").length in 1..300) { "Invalid reader metadata" }
            ReaderDocument(id, d.str("course"), d.str("title"), file, version, pages, offset, kind, week, parseOutline(d["outline"], pages))
        }
        require(docs.size <= 500 && docs.map { it.course to it.id }.distinct().size == docs.size) { "Duplicate reader document" }
        return docs
    }

    internal fun list(course: String) = documents.filter { it.course == course }
    internal fun document(course: String, id: String): ReaderDocument = list(course).singleOrNull { it.id == id }
        ?: throw ReaderFailure(io.ktor.http.HttpStatusCode.NotFound, "资料未加入这门课的私有书库")

    /** Only exact supported citations. Ambiguous and unregistered sources never guess a page. */
    internal fun resolve(course: String, reference: String): JsonObject {
        val textbook = Regex("^教材\\s+p\\.(\\d+)$").matchEntire(reference.trim())
        val slides = Regex("^W(\\d+)\\s+课件\\s+p\\.(\\d+)$", RegexOption.IGNORE_CASE).matchEntire(reference.trim())
        val candidates = when {
            textbook != null -> list(course).filter { it.kind == "textbook" && it.offset != null }
            slides != null -> list(course).filter { it.kind == "slides" && it.week == slides.groupValues[1].toIntOrNull() }
            else -> emptyList()
        }
        val d = candidates.singleOrNull() ?: throw ReaderFailure(io.ktor.http.HttpStatusCode.UnprocessableEntity, "出处未登记或不唯一，不能猜测页码")
        val number = (textbook?.groupValues?.get(1) ?: slides!!.groupValues[2]).toIntOrNull()
        val page = number?.toLong()?.plus(if (textbook != null) d.offset!!.toLong() else 0L)
        if (number == null || number < 1 || page == null || page !in 1L..d.pages.toLong())
            throw ReaderFailure(io.ktor.http.HttpStatusCode.UnprocessableEntity, "出处页码超出文档")
        return json("documentId" to d.id, "version" to d.version, "pdfPage" to page,
            "printedPage" to d.printedPage(page.toInt()), "anchorStatus" to "page_only")
    }

    internal suspend fun page(d: ReaderDocument, page: Int): JsonObject {
        val bytes = cached(d, page, false)
        val text = bytes.toString(Charsets.UTF_8)
        return json("documentId" to d.id, "version" to d.version, "pdfPage" to page,
            "printedPage" to d.printedPage(page), "text" to text,
            "textSha256" to digest(bytes), "privateAi" to "disabled_no_local_inference")
    }

    internal suspend fun image(d: ReaderDocument, page: Int): ByteArray = cached(d, page, true)

    // One renderer at a time, off the ledger actor and event loops. Disk cache is version-addressed.
    private suspend fun cached(d: ReaderDocument, page: Int, image: Boolean): ByteArray = withContext(Dispatchers.IO) {
        if (page !in 1..d.pages) throw ReaderFailure(io.ktor.http.HttpStatusCode.NotFound, "PDF 页码超出文档")
        lock.withLock {
            val realRoot = root!!.toRealPath()
            if (!d.file.toRealPath().startsWith(realRoot)) throw ReaderFailure(io.ktor.http.HttpStatusCode.Conflict, "资料位置已改变，请重新导入")
            val attrs = Files.readAttributes(d.file, BasicFileAttributes::class.java)
            val prior = verified[d.file]
            if (prior == null || prior.first != d.version || prior.second.size() != attrs.size() ||
                prior.second.lastModifiedTime() != attrs.lastModifiedTime() || prior.second.fileKey() != attrs.fileKey()) {
                val hash = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(d.file).use { input -> val chunk = ByteArray(65536); while (true) {
                    val n = input.read(chunk); if (n < 0) break; hash.update(chunk, 0, n)
                } }
                if (hash.digest().joinToString("") { "%02x".format(it) } != d.version)
                    throw ReaderFailure(io.ktor.http.HttpStatusCode.Conflict, "资料版本已改变，请重新导入")
                verified[d.file] = d.version to attrs
            }
            val cache = Files.createDirectories(realRoot.resolve("cache").resolve(d.version))
            require(cache.toRealPath().startsWith(realRoot)) { "Reader cache outside library" }
            val target = cache.resolve("$page" + if (image) "-1600.png" else ".txt")
            if (!Files.exists(target)) {
                val temp = Files.createTempDirectory(cache, "render-")
                try {
                    val out = temp.resolve(if (image) "page" else "page.txt")
                    val command = if (image) listOf(executable("pdftoppm"), "-f", "$page", "-l", "$page", "-singlefile", "-scale-to", "1600", "-png", d.file.toString(), out.toString())
                        else listOf(executable("pdftotext"), "-f", "$page", "-l", "$page", "-layout", "-enc", "UTF-8", d.file.toString(), out.toString())
                    val process = try { ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start() }
                        catch (_: Exception) { throw ReaderFailure(io.ktor.http.HttpStatusCode.ServiceUnavailable, "Mac 缺少本地 PDF 渲染工具") }
                    try {
                        if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0)
                            throw ReaderFailure(io.ktor.http.HttpStatusCode.ServiceUnavailable, "本地 PDF 渲染失败，请重试")
                    } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor() } }
                    val output = if (image) temp.resolve("page.png") else out
                    if (!Files.exists(output) || Files.size(output) > if (image) 12_000_000 else 2_000_000)
                        throw ReaderFailure(io.ktor.http.HttpStatusCode.ServiceUnavailable, "这一页超出渲染限制")
                    Files.move(output, target, StandardCopyOption.ATOMIC_MOVE)
                } finally { temp.toFile().deleteRecursively() }
            }
            Files.readAllBytes(target)
        }
    }

    private fun executable(name: String) = if (bin.isBlank()) name else Path.of(bin).resolve(name).toString()
    companion object {
        fun configured(): ReaderLibrary = ReaderLibrary(System.getenv("COACH_READER_LIBRARY")?.takeIf(String::isNotBlank)?.let(Path::of))
        internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
