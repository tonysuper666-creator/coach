package com.coach.desktop.books

import com.ibm.icu.text.CharsetDetector
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipFile

/** Canonical names are also the extensions of immutable, content-addressed originals. */
internal object BookFormats {
    val images = setOf("png", "jpg")
    val supported = images + setOf("pdf", "epub", "md", "txt", "docx", "pptx", "html", "mobi", "azw3", "rtf", "odt", "srt", "vtt")
    const val description = "PDF、电子书、Word、幻灯片、网页、文本和扫描图片"

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
        if (prefix.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10)) return "png"
        if (prefix.take(3).map { it.toInt() and 255 } == listOf(255, 216, 255)) return "jpg"
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
        val binarySuffix = suffix in setOf("pdf", "epub", "docx", "pptx", "odt", "mobi", "azw3", "rtf", "png", "jpg", "jpeg")
        val damaged = "这份 ${suffix.uppercase(Locale.ROOT)} 文件不完整或已损坏，请重新保存或下载后再导入。"
        require(Files.size(path) <= 32L * 1024 * 1024) { "文本教材请控制在 32 MB 以内。" }
        val text = try { BookText.decode(Files.readAllBytes(path)) } catch (e: IllegalArgumentException) {
            if (binarySuffix) throw IllegalArgumentException(damaged, e) else throw e
        }
        if (suffix == "vtt" || text.trimStart('\uFEFF').startsWith("WEBVTT")) return "vtt"
        if (suffix == "srt") return "srt"
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

    /** Optional section provenance uses offsets in clean(text), before its outer trim. */
    fun chapters(text: String, section: ((Int, List<BookPage>) -> Unit)? = null): List<BookPage> {
        val normalized = TextbookExtraction.clean(text)
        val leading = normalized.length - normalized.trimStart().length
        val cleaned = normalized.trim()
        require(cleaned.isNotBlank()) { "教材中没有可读取的文字。" }
        val starts = (listOf(0) + heading.findAll(cleaned).map { it.range.first }).distinct().sorted()
        return starts.flatMapIndexed { i, start ->
            val raw = cleaned.substring(start, starts.getOrNull(i + 1) ?: cleaned.length)
            val local = TextbookExtraction.paginate(raw.trim())
            section?.invoke(leading + start + raw.length - raw.trimStart().length, local)
            local
        }.mapIndexed { i, page -> page.copy(number = i + 1) }
    }

    fun chapterTitle(text: String): String? = heading.find(text)?.value?.trim()?.trimStart('#')?.trim()?.take(150)
}

/** Bounded reads only; no archive entry is extracted to disk or fetched from a URL. */
internal class SafeBookArchive(path: Path) : AutoCloseable {
    companion object {
        const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val MAX_DOCX_DOCUMENT_BYTES = 128L * 1024 * 1024
        const val MAX_DOCX_TOTAL_BYTES = 512L * 1024 * 1024
    }

    private val zip = ZipFile(path.toFile())
    private var total = 0L
    val declaredTotalBytes: Long
    init {
        try {
            val isDocx = zip.getEntry("word/document.xml") != null
            val totalLimit = if (isDocx) MAX_DOCX_TOTAL_BYTES else MAX_TOTAL_BYTES
            var declared = 0L
            var count = 0
            val names = HashSet<String>()
            for (entry in zip.entries()) {
                checkName(entry.name)
                require(names.add(entry.name)) { "教材压缩包含有重名文件，请重新保存后再导入。" }
                require(++count <= 10000) { "这份教材的压缩包文件数量过多。" }
                val entryLimit = if (isDocx && entry.name == "word/document.xml") MAX_DOCX_DOCUMENT_BYTES else MAX_ENTRY_BYTES
                require(entry.size in 0..entryLimit) {
                    if (entryLimit == MAX_ENTRY_BYTES) "这份教材中有文件超过 16 MB，请拆分后导入。"
                    else "这份 Word 正文超过 128 MB，请拆分后导入。"
                }
                declared += entry.size
                require(declared <= totalLimit) {
                    if (totalLimit == MAX_TOTAL_BYTES) "这份教材解压后超过 64 MB，请拆分后导入。"
                    else "这份 Word 解压后超过 512 MB，请拆分后导入。"
                }
            }
            declaredTotalBytes = declared
        } catch (e: Exception) { zip.close(); throw e }
    }
    fun names(): List<String> = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
    fun contains(name: String) = zip.getEntry(name) != null
    fun size(name: String): Long? {
        checkName(name)
        return zip.getEntry(name)?.size?.takeIf { it >= 0 }
    }
    fun remainingReadBudget(): Long = (MAX_TOTAL_BYTES - total).coerceAtLeast(0L)
    fun read(name: String): ByteArray {
        checkName(name)
        val entry = requireNotNull(zip.getEntry(name)) { "教材缺少正文文件，请重新保存后再导入。" }
        return zip.getInputStream(entry).use { input ->
            val bytes = input.readNBytes(MAX_ENTRY_BYTES.toInt() + 1)
            require(bytes.size <= MAX_ENTRY_BYTES) { "这份教材的单个文件解压后超过 16 MB。" }
            total += bytes.size
            require(total <= MAX_TOTAL_BYTES) { "这份教材解压后的正文超过 64 MB。" }
            bytes
        }
    }
    /** Stream a single entry with a hard bound on actual decompressed bytes, avoiding full-entry buffering. */
    fun <T> stream(name: String, maxBytes: Long = MAX_ENTRY_BYTES, block: (InputStream) -> T): T {
        checkName(name)
        val entry = requireNotNull(zip.getEntry(name)) { "教材缺少正文文件，请重新保存后再导入。" }
        return zip.getInputStream(entry).use { raw ->
            val bounded = BoundedArchiveInputStream(raw, maxBytes)
            block(bounded)
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

    private class BoundedArchiveInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var readBytes = 0L
        private fun count(n: Int): Int {
            if (n > 0) {
                readBytes += n
                require(readBytes <= limit) { "这份教材的单个文件解压后超过 ${limit / (1024 * 1024)} MB。" }
            }
            return n
        }
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = count(super.read(b, off, len))
    }
}
