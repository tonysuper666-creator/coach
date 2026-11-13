package com.coach.desktop.books

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** All state belongs to this Windows library. No server, course ID, token or user corpus is opened. */
internal class TextbookLibrary(val directory: Path) {
    init {
        Files.createDirectories(directory)
        database().use { db -> db.createStatement().use { sql ->
            sql.execute("CREATE TABLE IF NOT EXISTS books(id TEXT PRIMARY KEY,title TEXT NOT NULL,format TEXT NOT NULL,reading_page INTEGER NOT NULL DEFAULT 1,lesson_index INTEGER NOT NULL DEFAULT 0,learning INTEGER NOT NULL DEFAULT 0,prompt_seen INTEGER NOT NULL DEFAULT 0,mode TEXT NOT NULL DEFAULT 'NONE',touched INTEGER NOT NULL)")
            sql.execute("CREATE TABLE IF NOT EXISTS pages(book TEXT NOT NULL,page INTEGER NOT NULL,text TEXT NOT NULL,PRIMARY KEY(book,page))")
            sql.execute("CREATE TABLE IF NOT EXISTS answers(book TEXT NOT NULL,lesson TEXT NOT NULL,mode TEXT NOT NULL,text TEXT NOT NULL,revealed INTEGER NOT NULL,understood INTEGER,PRIMARY KEY(book,lesson,mode))")
        } }
    }
    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite:${directory.resolve("library.sqlite")}").apply {
        createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
    }

    @Synchronized fun catalog(): List<BookSummary> = database().use { db ->
        db.createStatement().use { sql -> sql.executeQuery("SELECT b.*, (SELECT count(*) FROM pages p WHERE p.book=b.id) AS page_count FROM books b ORDER BY touched DESC,id").use { rows -> buildList {
            while (rows.next()) add(BookSummary(rows.getString("id"), rows.getString("title"), rows.getInt("page_count"), rows.getInt("reading_page"), rows.getInt("learning") == 1))
        } } }
    }

    @Synchronized fun book(id: String): Textbook = database().use { db ->
        db.prepareStatement("SELECT * FROM books WHERE id=?").use { sql ->
            sql.setString(1, id)
            sql.executeQuery().use { row ->
                check(row.next()) { "这本教材已不在书架中。" }
                val pages = db.prepareStatement("SELECT page,text FROM pages WHERE book=? ORDER BY page").use { query ->
                    query.setString(1, id)
                    query.executeQuery().use { p -> buildList { while (p.next()) add(BookPage(p.getInt(1), p.getString(2))) } }
                }
                Textbook(id, row.getString("title"), row.getString("format"), pages, row.getInt("reading_page"),
                    row.getInt("lesson_index"), row.getInt("learning") == 1, row.getInt("prompt_seen") == 1,
                    QuestionMode.valueOf(row.getString("mode")))
            }
        }
    }

    @Synchronized fun importBook(source: Path): Textbook {
        val format = source.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(format in setOf("pdf", "epub", "md", "txt")) { "请选择 PDF、EPUB、Markdown 或 UTF-8 文本教材。" }
        require(Files.size(source) in 1..(256L * 1024 * 1024)) { "请选择不超过 256 MB 的教材。" }
        val stage = directory.resolve(".import-${UUID.randomUUID()}")
        try {
            Files.copy(source, stage)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(stage).use { input ->
                val buffer = ByteArray(65536)
                while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            if (catalog().any { it.id == id }) return book(id)
            val fallback = source.fileName.toString().substringBeforeLast('.')
            val extracted = TextbookExtraction.extract(stage, format, fallback)
            require(extracted.pages.isNotEmpty()) { "教材中没有可打开的页面。" }
            val target = original(id, format)
            // The immutable source is in place before the single transaction makes it visible.
            Files.move(stage, target, StandardCopyOption.REPLACE_EXISTING)
            database().use { db ->
                db.autoCommit = false
                try {
                    db.prepareStatement("INSERT INTO books(id,title,format,touched) VALUES(?,?,?,?)").use { sql ->
                        sql.setString(1, id); sql.setString(2, extracted.title); sql.setString(3, format)
                        sql.setLong(4, System.currentTimeMillis()); sql.executeUpdate()
                    }
                    db.prepareStatement("INSERT INTO pages(book,page,text) VALUES(?,?,?)").use { sql ->
                        for (page in extracted.pages) {
                            sql.setString(1, id); sql.setInt(2, page.number); sql.setString(3, page.text); sql.addBatch()
                        }
                        sql.executeBatch()
                    }
                    db.commit()
                } catch (error: Exception) { db.rollback(); throw error }
            }
            return book(id)
        } finally { Files.deleteIfExists(stage) }
    }

    private fun original(id: String, format: String): Path {
        require(id.matches(Regex("[a-f0-9]{64}")) && format in setOf("pdf", "epub", "md", "txt"))
        return directory.resolve("$id.$format")
    }

    @Synchronized fun save(book: Textbook) {
        require(book.readingPage in 1..book.pages.size && book.lessonIndex in 0..book.lessons.size)
        database().use { db -> db.prepareStatement("UPDATE books SET reading_page=?,lesson_index=?,learning=?,prompt_seen=?,mode=?,touched=? WHERE id=?").use { sql ->
            sql.setInt(1, book.readingPage); sql.setInt(2, book.lessonIndex); sql.setInt(3, if (book.learning) 1 else 0)
            sql.setInt(4, if (book.promptSeen) 1 else 0); sql.setString(5, book.questionMode.name)
            sql.setLong(6, System.currentTimeMillis()); sql.setString(7, book.id)
            check(sql.executeUpdate() == 1) { "无法保存这本书的进度。" }
        } }
    }

    @Synchronized fun answer(book: String, lesson: String, mode: QuestionMode): PracticeAnswer = database().use { db ->
        db.prepareStatement("SELECT text,revealed,understood FROM answers WHERE book=? AND lesson=? AND mode=?").use { sql ->
            sql.setString(1, book); sql.setString(2, lesson); sql.setString(3, mode.name)
            sql.executeQuery().use { row ->
                if (!row.next()) PracticeAnswer() else {
                    val understood = row.getInt(3).let { if (row.wasNull()) null else it == 1 }
                    PracticeAnswer(row.getString(1), row.getInt(2) == 1, understood)
                }
            }
        }
    }

    @Synchronized fun saveAnswer(book: String, lesson: String, mode: QuestionMode, answer: PracticeAnswer) {
        database().use { db -> db.prepareStatement("INSERT OR REPLACE INTO answers(book,lesson,mode,text,revealed,understood) VALUES(?,?,?,?,?,?)").use { sql ->
            sql.setString(1, book); sql.setString(2, lesson); sql.setString(3, mode.name); sql.setString(4, answer.text)
            sql.setInt(5, if (answer.revealed) 1 else 0)
            if (answer.understood == null) sql.setNull(6, java.sql.Types.INTEGER) else sql.setInt(6, if (answer.understood) 1 else 0)
            sql.executeUpdate()
        } }
    }

    fun renderPdf(book: Textbook, page: Int): BufferedImage {
        require(book.format == "pdf" && page in 1..book.pages.size)
        return Loader.loadPDF(original(book.id, book.format).toFile()).use { pdf ->
            val bounds = pdf.getPage(page - 1).cropBox
            val scale = minOf(1.5f, 1800f / maxOf(bounds.width, bounds.height)).coerceAtLeast(.05f)
            PDFRenderer(pdf).renderImage(page - 1, scale)
        }
    }
}

internal data class ExtractedBook(val title: String, val pages: List<BookPage>)

internal object TextbookExtraction {
    fun extract(path: Path, format: String, title: String): ExtractedBook = when (format) {
        "pdf" -> Loader.loadPDF(path.toFile()).use { pdf ->
            require(pdf.numberOfPages in 1..5000) { "这本 PDF 的页数暂不支持。" }
            require(pdf.currentAccessPermission.canExtractContent()) { "这本 PDF 限制了文字提取，请使用有阅读和提取权限的教材。" }
            val stripper = PDFTextStripper().apply { sortByPosition = true; paragraphEnd = "\n\n" }
            val pages = (1..pdf.numberOfPages).map { n ->
                stripper.startPage = n; stripper.endPage = n
                BookPage(n, clean(stripper.getText(pdf)))
            }
            ExtractedBook(pdf.documentInformation.title?.trim()?.takeIf(String::isNotEmpty) ?: title, pages)
        }
        "epub" -> epub(path, title)
        else -> {
            require(Files.size(path) <= 32L * 1024 * 1024) { "文本教材请控制在 32 MB 以内。" }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            val text = clean(decoder.decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(path))).toString().removePrefix("\uFEFF"))
            require(text.isNotBlank()) { "教材中没有文字。" }
            ExtractedBook(title, paginate(text))
        }
    }

    private fun clean(text: String) = text.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "")
    private fun paginate(text: String): List<BookPage> {
        val chunks = mutableListOf<String>()
        for (section in text.split('\u000c')) {
            var rest = section
            if (rest.isEmpty()) { chunks += ""; continue }
            while (rest.isNotEmpty()) {
                var end = minOf(rest.length, 4000)
                if (end < rest.length) {
                    val boundary = rest.lastIndexOf('\n', end)
                    if (boundary >= 2000) end = boundary + 1
                    if (Character.isHighSurrogate(rest[end - 1])) end--
                }
                chunks += rest.take(end); rest = rest.drop(end)
            }
        }
        return chunks.mapIndexed { index, content -> BookPage(index + 1, content) }
    }

    private fun epub(path: Path, fallback: String): ExtractedBook = ZipFile(path.toFile()).use { zip ->
        fun read(name: String): ByteArray {
            require(!name.startsWith('/') && name.split('/').none { it == ".." } && '\\' !in name) { "EPUB 内含无效的文件位置。" }
            val entry = requireNotNull(zip.getEntry(name)) { "EPUB 缺少阅读顺序中的正文。" }
            require(entry.size in 0..(16L * 1024 * 1024)) { "EPUB 的单个章节过大。" }
            return zip.getInputStream(entry).use { input ->
                val bytes = input.readNBytes(16 * 1024 * 1024 + 1)
                require(bytes.size <= 16 * 1024 * 1024) { "EPUB 的单个章节过大。" }; bytes
            }
        }
        fun xml(bytes: ByteArray): Element {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false; isExpandEntityReferences = false
            }
            return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
        }
        fun Element.all(name: String): List<Element> = getElementsByTagNameNS("*", name).let { list -> (0 until list.length).map { list.item(it) as Element } }
        val container = xml(read("META-INF/container.xml"))
        val packagePath = container.all("rootfile").firstOrNull()?.getAttribute("full-path") ?: error("EPUB 缺少内容目录。")
        val opf = xml(read(packagePath))
        val manifest = opf.all("item").associate { it.getAttribute("id") to it.getAttribute("href") }
        val spine = opf.all("itemref").filter { it.getAttribute("linear") != "no" }
        require(spine.size in 1..5000) { "EPUB 没有可读正文，或章节太多。" }
        val root = java.net.URI("/$packagePath")
        var totalBytes = 0L
        val pages = spine.flatMap { item ->
            val href = requireNotNull(manifest[item.getAttribute("idref")]) { "EPUB 阅读顺序不完整。" }
            val resolved = root.resolve(href).normalize()
            require(resolved.scheme == null && resolved.host == null && resolved.fragment == null) { "EPUB 的正文必须包含在书内。" }
            val bytes = read(resolved.path.removePrefix("/"))
            totalBytes += bytes.size
            require(totalBytes <= 64L * 1024 * 1024) { "EPUB 的正文过大。" }
            val doc = xml(bytes)
            val output = StringBuilder()
            fun visit(node: Node) {
                if (node.nodeType == Node.TEXT_NODE) { output.append(node.nodeValue); return }
                val name = node.localName?.lowercase().orEmpty()
                if (name in setOf("script", "style", "head")) return
                val block = name in setOf("p", "div", "li", "h1", "h2", "h3", "h4", "section", "br", "tr")
                if (block) output.append("\n\n")
                if (name.matches(Regex("h[1-6]"))) output.append("# ")
                for (i in 0 until node.childNodes.length) visit(node.childNodes.item(i))
                if (block) output.append("\n\n")
            }
            visit(doc)
            paginate(clean(output.toString()).trim())
        }.mapIndexed { index, page -> page.copy(number = index + 1) }
        require(pages.any { it.text.isNotBlank() }) { "EPUB 中没有可读取的文字正文。" }
        ExtractedBook(opf.all("title").firstOrNull()?.textContent?.trim()?.takeIf(String::isNotEmpty) ?: fallback, pages)
    }
}
