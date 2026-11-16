package com.coach.desktop.books

import com.ibm.icu.text.CharsetDetector
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipFile

/** Canonical names are also the extensions of immutable, content-addressed originals. */
internal object BookFormats {
    val supported = setOf("pdf", "epub", "md", "txt", "docx", "pptx", "html", "mobi", "azw3", "rtf", "odt")
    const val description = "PDF、电子书、Word、幻灯片、网页和文本"

    fun detect(path: Path, filename: String): String = try {
        detectContent(path, filename)
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("这份教材不完整或已损坏，请重新保存或下载后再导入。", e)
    }

    private fun detectContent(path: Path, filename: String): String {
        val suffix = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val prefix = Files.newInputStream(path).use { it.readNBytes(8192) }
        val ascii = prefix.toString(Charsets.ISO_8859_1)
        if (ascii.startsWith("%PDF-")) return "pdf"
        if (ascii.startsWith("PK")) return SafeBookArchive(path).use { zip ->
            when {
                zip.contains("word/document.xml") -> "docx"
                zip.contains("ppt/presentation.xml") -> "pptx"
                zip.contains("META-INF/container.xml") -> "epub"
                zip.contains("mimetype") && zip.read("mimetype").toString(Charsets.UTF_8).trim() == "application/vnd.oasis.opendocument.text" -> "odt"
                else -> throw IllegalArgumentException("这个压缩包里没有可读的教材，请选择解压后的正文文件。")
            }
        }
        if (ascii.startsWith("{\\rtf")) return "rtf"
        if (ascii.length >= 68 && ascii.substring(60, 68) == "BOOKMOBI") return if (suffix == "azw3") "azw3" else "mobi"
        if (prefix.take(8).map { it.toInt() and 255 } == listOf(208, 207, 17, 224, 161, 177, 26, 225)) {
            throw IllegalArgumentException("这份 Office 文件可能已加密或使用旧版格式，请取消密码并另存为 Word（DOCX）或幻灯片（PPTX）。")
        }
        // Do not mistake a corrupt binary document for a short plain-text book.
        val binarySuffix = suffix in setOf("pdf", "epub", "docx", "pptx", "odt", "mobi", "azw3", "rtf")
        val damaged = "这份 ${suffix.uppercase(Locale.ROOT)} 文件不完整或已损坏，请重新保存或下载后再导入。"
        require(Files.size(path) <= 32L * 1024 * 1024) { "文本教材请控制在 32 MB 以内。" }
        val text = try { BookText.decode(Files.readAllBytes(path)) } catch (e: IllegalArgumentException) {
            if (binarySuffix) throw IllegalArgumentException(damaged, e) else throw e
        }
        if (suffix in setOf("html", "htm") || Regex("(?is)^\\s*(?:<!--.*?-->\\s*)*(?:<!doctype\\s+html|<html\\b|<head\\b|<body\\b|<article\\b)").containsMatchIn(text)) return "html"
        require(!binarySuffix) { damaged }
        return if (suffix in setOf("md", "markdown")) "md" else "txt"
    }
}

internal object BookText {
    fun decode(bytes: ByteArray): String {
        fun starts(vararg values: Int) = bytes.size >= values.size && values.indices.all { (bytes[it].toInt() and 255) == values[it] }
        fun strict(name: String, skip: Int = 0): String = Charset.forName(name).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()
        fun readable(text: String) = text.none { it == '\u0000' || (it.isISOControl() && it !in "\t\r\n\u000c") }
        val bom = when {
            starts(0xef, 0xbb, 0xbf) -> "UTF-8" to 3
            starts(0xff, 0xfe) -> "UTF-16LE" to 2
            starts(0xfe, 0xff) -> "UTF-16BE" to 2
            else -> null
        }
        try {
            if (bom != null) return strict(bom.first, bom.second).also { require(readable(it)) }
            runCatching { strict("UTF-8") }.getOrNull()?.takeIf(::readable)?.let { return it }
            val allowed = setOf("GB18030", "BIG5", "UTF-16LE", "UTF-16BE")
            val matches = CharsetDetector().setText(bytes).detectAll().filter { it.name.uppercase(Locale.ROOT) in allowed }
            for (match in matches) {
                val text = runCatching { strict(match.name) }.getOrNull() ?: continue
                if (match.confidence >= 10 && readable(text)) return text
            }
        } catch (_: Exception) { /* Use the same actionable message for truncated BOM encodings. */ }
        throw IllegalArgumentException("无法确认这份文本的编码，或文件含有损坏的字节。请另存为 UTF-8 文本后重试。")
    }

    private val heading = Regex("(?im)^(?:#{1,6}\\s+[^\\r\\n]+|第\\s*[零〇一二三四五六七八九十百千万两0-9]+\\s*[章节篇][^\\r\\n]*|(?:Chapter|Section)\\s+(?:[0-9]+|[IVXLC]+)\\b[^\\r\\n]*)$")

    fun chapters(text: String): List<BookPage> {
        val cleaned = TextbookExtraction.clean(text).trim()
        require(cleaned.isNotBlank()) { "教材中没有可读取的文字。" }
        val starts = (listOf(0) + heading.findAll(cleaned).map { it.range.first }).distinct().sorted()
        return starts.flatMapIndexed { i, start ->
            TextbookExtraction.paginate(cleaned.substring(start, starts.getOrNull(i + 1) ?: cleaned.length).trim())
        }.mapIndexed { i, page -> page.copy(number = i + 1) }
    }

    fun chapterTitle(text: String): String? = heading.find(text)?.value?.trim()?.trimStart('#')?.trim()?.take(150)
}

/** Bounded reads only; no archive entry is extracted to disk or fetched from a URL. */
internal class SafeBookArchive(path: Path) : AutoCloseable {
    private val zip = ZipFile(path.toFile())
    private var total = 0L
    init {
        try {
            var declared = 0L
            var count = 0
            val names = HashSet<String>()
            for (entry in zip.entries()) {
                checkName(entry.name)
                require(names.add(entry.name)) { "教材压缩包含有重名文件，请重新保存后再导入。" }
                require(++count <= 10000) { "这份教材的压缩包文件数量过多。" }
                require(entry.size in 0..16L * 1024 * 1024) { "这份教材中有文件超过 16 MB，请拆分后导入。" }
                declared += entry.size
                require(declared <= 64L * 1024 * 1024) { "这份教材解压后超过 64 MB，请拆分后导入。" }
            }
        } catch (e: Exception) { zip.close(); throw e }
    }
    fun contains(name: String) = zip.getEntry(name) != null
    fun read(name: String): ByteArray {
        checkName(name)
        val entry = requireNotNull(zip.getEntry(name)) { "教材缺少正文文件，请重新保存后再导入。" }
        return zip.getInputStream(entry).use { input ->
            val bytes = input.readNBytes(16 * 1024 * 1024 + 1)
            require(bytes.size <= 16 * 1024 * 1024) { "这份教材的单个文件解压后超过 16 MB。" }
            total += bytes.size
            require(total <= 64L * 1024 * 1024) { "这份教材解压后的正文超过 64 MB。" }
            bytes
        }
    }
    fun resolve(from: String, target: String): String {
        val uri = java.net.URI(target)
        require(!uri.isAbsolute && uri.host == null && uri.query == null && uri.fragment == null && !target.startsWith('/')) { "教材正文必须包含在文件内，不能读取外部链接。" }
        val resolved = java.net.URI("/$from").resolve(uri).normalize().path.removePrefix("/")
        checkName(resolved)
        return resolved
    }
    override fun close() = zip.close()
    private fun checkName(name: String) {
        require(!name.startsWith('/') && '\\' !in name && ':' !in name && name.split('/').none { it == ".." }) { "教材压缩包内含无效的文件位置，请重新保存后再导入。" }
    }
}
