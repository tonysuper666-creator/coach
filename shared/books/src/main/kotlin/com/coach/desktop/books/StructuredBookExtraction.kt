package com.coach.desktop.books

import org.jsoup.Jsoup
import org.jsoup.nodes.Element as HtmlElement
import org.jsoup.nodes.Node as HtmlNode
import org.jsoup.nodes.TextNode
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal object StructuredBookExtraction {
    fun extract(path: Path, format: String, title: String, control: BookImportControl = BookImportControl()): ExtractedBook = when (format) {
        "html" -> html(BookText.decode(Files.readAllBytes(path)), title)
        "rtf" -> {
            require(Files.size(path) <= 32L * 1024 * 1024) { "文本教材请控制在 32 MB 以内。" }
            ExtractedBook(title, BookText.chapters(readBookRtf(path)))
        }
        else -> SafeBookArchive(path).use { zip ->
            val bookTitle = if (zip.contains("docProps/core.xml")) xml(zip.read("docProps/core.xml")).all("title").firstOrNull()?.textContent?.trim()?.takeIf(String::isNotEmpty) ?: title else title
            when (format) {
                "docx" -> wordStream(zip, bookTitle, control)
                "pptx" -> slides(zip, bookTitle, control)
                "odt" -> odt(zip, title)
                else -> throw IllegalArgumentException("请使用 PDF、电子书、Word、幻灯片、网页或文本教材。")
            }
        }
    }

    private data class HtmlContent(val title: String, val text: String, val images: List<Pair<Int, HtmlElement>>)

    private fun htmlContent(text: String): HtmlContent {
        val doc = Jsoup.parse(text)
        doc.select("script,style,nav,header,footer,aside,form,iframe,noscript,[hidden],[aria-hidden=true]").remove()
        val main = doc.selectFirst("article") ?: doc.selectFirst("main,[role=main]") ?: doc.body()
        val output = StringBuilder()
        val images = mutableListOf<Pair<Int, HtmlElement>>()
        fun visit(node: HtmlNode) {
            if (node is TextNode) { output.append(node.wholeText); return }
            val name = (node as? HtmlElement)?.normalName().orEmpty()
            val block = name in setOf("p", "div", "li", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "br", "tr", "pre", "blockquote")
            if (block) output.append("\n\n")
            if (name.matches(Regex("h[1-6]"))) output.append("# ")
            if (name == "img") images += output.length to (node as HtmlElement)
            node.childNodes().forEach(::visit)
            if (name in setOf("td", "th")) output.append(" | ")
            if (block) output.append("\n\n")
        }
        visit(main)
        return HtmlContent(doc.title().trim(), output.toString(), images)
    }

    fun html(text: String, title: String): ExtractedBook {
        val content = htmlContent(text)
        return htmlImages(text, ExtractedBook(content.title.ifEmpty { title }, BookText.chapters(content.text)), null)
    }

    fun htmlImages(text: String, book: ExtractedBook, directory: Path?): ExtractedBook {
        val content = htmlContent(text)
        val images = htmlPlacements(content) { FormatBookImages.htmlAsset(it.attr("src"), directory) }
        return book.copy(illustrations = FormatBookImages.attach(content.text, book.pages, images))
    }

    private fun htmlPlacements(content: HtmlContent, load: (HtmlElement) -> ByteArray?): List<FormatBookImages.Placement> {
        require(content.images.size <= FormatBookImages.MAX_IMAGES) { "网页插图过多，请分册导入。" }
        var total = 0L
        return content.images.mapNotNull { (offset, image) ->
            load(image)?.let { bytes ->
                total += bytes.size
                require(total <= FormatBookImages.MAX_TOTAL) { "这份材料插图超过 64 MB，请分册导入。" }
                FormatBookImages.Placement(offset, bytes, image.attr("alt").ifBlank { image.attr("title") })
            }
        }
    }

    fun kindle(book: com.coach.desktop.books.mobi.KindleText.Book, title: String): ExtractedBook {
        val pages = mutableListOf<BookPage>()
        val images = mutableListOf<ExtractedIllustration>()
        for (document in book.documents()) {
            val content = htmlContent(document)
            val local = BookText.chapters(content.text)
            val placements = htmlPlacements(content) { image ->
                val index = kindleImageIndex(image)
                index?.let { book.images()[it] }
            }
            val base = pages.size
            pages += local.map { it.copy(number = it.number + base) }
            images += FormatBookImages.attach(content.text, local, placements).map {
                ExtractedIllustration(it.page + base, it.anchorOffset, it.bytes, it.extension, it.width, it.height, it.caption)
            }
            require(images.size <= FormatBookImages.MAX_IMAGES && images.sumOf { it.bytes.size.toLong() } <= FormatBookImages.MAX_TOTAL) {
                "这本 Kindle 书插图过多或超过 64 MB，请分册导入。"
            }
        }
        return ExtractedBook(title, pages, images)
    }

    private fun kindleImageIndex(image: HtmlElement): Int? = image.attr("recindex").toIntOrNull()
        ?: Regex("(?i)^kindle:embed:([0-9a-v]+)(?:[?].*)?$").matchEntire(image.attr("src"))
            ?.groupValues?.get(1)?.toIntOrNull(32)

    /** On-demand provenance, not yet persisted or used for rendering/hiding. */
    fun wordSourceMapping(path: Path, canonicalPages: List<BookPage>): WordSourceMapping = SafeBookArchive(path).use { zip ->
        val docSize = zip.size("word/document.xml") ?: Long.MAX_VALUE
        if (docSize > SafeBookArchive.MAX_ENTRY_BYTES || zip.declaredTotalBytes > SafeBookArchive.MAX_TOTAL_BYTES) {
            return@use WordSourceMapping(WordSourceMapping.Status.STALE_TEXT, emptyList())
        }
        val elements = mutableListOf<WordElementSource>()
        val book = word(zip, "", elements)
        if (book.pages.map { it.number to it.text } == canonicalPages.map { it.number to it.text })
            WordSourceMapping(WordSourceMapping.Status.MAPPED, elements)
        else WordSourceMapping(WordSourceMapping.Status.STALE_TEXT, emptyList())
    }

    /** Bounded streaming DOCX extractor: handles >64 MiB archives and >16 MiB word/document.xml in O(depth) memory. */
    private fun wordStream(zip: SafeBookArchive, title: String, control: BookImportControl): ExtractedBook {
        val footnotes = if (zip.contains("word/footnotes.xml")) {
            xml(zip.read("word/footnotes.xml")).all("footnote")
                .filter { it.attr("id").toIntOrNull()?.let { id -> id > 0 } == true }
                .associate { note -> note.attr("id") to inline(note) }
        } else emptyMap()
        val headingStyles = if (zip.contains("word/styles.xml")) xml(zip.read("word/styles.xml")).all("style")
            .filter { style -> style.all("outlineLvl").isNotEmpty() || style.all("name").any { it.attr("val").matches(Regex("(?i)heading\\s*\\d+")) } }
            .map { it.attr("styleId") }.toSet() else emptySet()
        val rels = if (zip.contains("word/_rels/document.xml.rels")) relationships(zip, "word/_rels/document.xml.rels") else emptyMap()
        val output = StringBuilder()
        val placements = mutableListOf<FormatBookImages.Placement>()
        var imageBytes = 0L
        val supportedImages = setOf("png", "jpg", "jpeg", "gif")
        val headingRegex = Regex("(?i)heading[1-9]")

        class TableFrame(val depth: Int, var cellIndex: Int = 0, var cellParagraphIndex: Int = 0)
        val stack = ArrayDeque<String>()
        val captions = ArrayDeque<String?>()
        val tables = ArrayDeque<TableFrame>()
        var skipDepth = 0
        var topParagraphDepth = -1
        var topParagraphHeaderEmitted = false
        var topParagraphPageBreak = false
        var topParagraphStyle = ""
        var inTopPPrDepth = -1
        var cellParagraphDepth = -1
        var inSectPrDepth = -1
        var inTextElement = false

        fun flushTopParagraphHeader() {
            if (topParagraphDepth < 0 || topParagraphHeaderEmitted) return
            topParagraphHeaderEmitted = true
            if (topParagraphPageBreak) output.append('\u000c')
            output.append("\n\n")
            if (topParagraphStyle in headingStyles || headingRegex.matches(topParagraphStyle)) output.append("# ")
        }

        fun inInline(): Boolean = topParagraphDepth >= 0 || cellParagraphDepth >= 0

        fun nearestCaption(): String? {
            for (i in stack.size - 1 downTo 0) {
                val name = stack[i]
                if (name in setOf("p", "tc", "body")) break
                captions[i]?.let { return it }
            }
            return null
        }

        fun storeCaption(text: String) {
            for (i in stack.size - 1 downTo 0) {
                val name = stack[i]
                if (name in setOf("p", "tc", "body")) break
                if (captions[i] == null) captions[i] = text
            }
        }

        zip.stream("word/document.xml", SafeBookArchive.MAX_DOCX_DOCUMENT_BYTES) { input ->
            streamBookOfficeXml(input, control, object : BookXmlVisitor {
                override fun startElement(localName: String, attr: (String) -> String?) {
                    val parent = stack.lastOrNull()
                    val depth = stack.size
                    stack.addLast(localName)
                    captions.addLast(null)
                    if (skipDepth > 0) {
                        if (localName == "del" || localName == "instrText") skipDepth++
                        return
                    }
                    if (inInline() && (localName == "del" || localName == "instrText")) {
                        skipDepth = 1
                        return
                    }
                    if (topParagraphDepth >= 0 && !topParagraphHeaderEmitted) {
                        if (localName == "pPr" && depth == topParagraphDepth + 1) {
                            inTopPPrDepth = depth
                            return
                        }
                        if (inTopPPrDepth >= 0) {
                            when (localName) {
                                "pageBreakBefore" -> {
                                    val v = attr("val").orEmpty()
                                    if (v !in setOf("0", "false", "off")) topParagraphPageBreak = true
                                }
                                "pStyle" -> if (topParagraphStyle.isEmpty()) topParagraphStyle = attr("val").orEmpty()
                            }
                            return
                        }
                        flushTopParagraphHeader()
                    }
                    if (!inInline()) {
                        when (localName) {
                            "p" -> {
                                val topTable = tables.lastOrNull()
                                if (topTable != null && parent == "tc" && depth == topTable.depth + 3) {
                                    if (topTable.cellParagraphIndex++ > 0) output.append(" ")
                                    cellParagraphDepth = depth
                                } else if (tables.isEmpty()) {
                                    topParagraphDepth = depth
                                    topParagraphHeaderEmitted = false
                                    topParagraphPageBreak = false
                                    topParagraphStyle = ""
                                    inTopPPrDepth = -1
                                }
                            }
                            "tbl" -> if (tables.isEmpty()) tables.addLast(TableFrame(depth))
                            "tr" -> {
                                val topTable = tables.lastOrNull()
                                if (topTable != null && depth == topTable.depth + 1) {
                                    topTable.cellIndex = 0
                                    output.append("\n\n")
                                }
                            }
                            "tc" -> {
                                val topTable = tables.lastOrNull()
                                if (topTable != null && parent == "tr" && depth == topTable.depth + 2) {
                                    if (topTable.cellIndex++ > 0) output.append(" | ")
                                    topTable.cellParagraphIndex = 0
                                }
                            }
                            "sectPr" -> if (tables.isEmpty() && inSectPrDepth < 0) inSectPrDepth = depth
                            "type" -> if (inSectPrDepth >= 0 && attr("val") in setOf("nextPage", "evenPage", "oddPage")) {
                                output.append('\u000c')
                            }
                        }
                        return
                    }
                    // Inside inline paragraph content (either top-level <p> or table cell <p>).
                    when (localName) {
                        "t" -> inTextElement = true
                        "tab" -> output.append('\t')
                        "br" -> output.append(if (attr("type") == "page") '\u000c' else '\n')
                        "cr" -> output.append('\n')
                        "footnoteReference" -> output.append("【脚注 ${attr("id").orEmpty()}】")
                        "docPr", "cNvPr" -> {
                            val caption = attr("descr").orEmpty().ifBlank { attr("title").orEmpty() }.takeIf(String::isNotBlank)
                            if (caption != null) storeCaption(caption)
                        }
                        "blip", "imagedata" -> {
                            val id = if (localName == "blip") attr("embed").orEmpty() else attr("id").orEmpty()
                            val target = rels[id] ?: return
                            if (placements.size >= FormatBookImages.MAX_IMAGES) return
                            val resolved = zip.resolve("word/document.xml", target)
                            val ext = resolved.substringAfterLast('.', "").lowercase(Locale.ROOT)
                            if (ext !in supportedImages || !zip.contains(resolved)) return
                            val entrySize = zip.size(resolved) ?: return
                            if (entrySize <= 0 || entrySize > FormatBookImages.MAX_BYTES ||
                                imageBytes + entrySize > FormatBookImages.MAX_TOTAL ||
                                zip.remainingReadBudget() < entrySize) return
                            val bytes = zip.read(resolved)
                            imageBytes += bytes.size
                            placements += FormatBookImages.Placement(output.length, bytes, nearestCaption())
                        }
                    }
                }

                override fun text(value: String) {
                    if (skipDepth == 0 && inTextElement && inInline()) output.append(value)
                }

                override fun endElement(localName: String) {
                    val depth = stack.size - 1
                    if (skipDepth > 0) {
                        if (localName == "del" || localName == "instrText") skipDepth--
                        stack.removeLast()
                        captions.removeLast()
                        return
                    }
                    if (inTextElement && localName == "t") inTextElement = false
                    if (inTopPPrDepth == depth) inTopPPrDepth = -1
                    if (topParagraphDepth == depth) {
                        flushTopParagraphHeader()
                        output.append("\n\n")
                        topParagraphDepth = -1
                        topParagraphHeaderEmitted = false
                    } else if (cellParagraphDepth == depth) {
                        cellParagraphDepth = -1
                    } else if (tables.lastOrNull()?.let { it.depth + 1 == depth && localName == "tr" } == true) {
                        output.append("\n\n")
                    } else if (tables.lastOrNull()?.depth == depth) {
                        tables.removeLast()
                    } else if (inSectPrDepth == depth) {
                        inSectPrDepth = -1
                    }
                    stack.removeLast()
                    captions.removeLast()
                }
            })
        }

        val raw = output.toString()
        val text = raw.trim()
        require(text.isNotBlank()) { "这份 Word 文档没有可读取的文字。" }
        val explicitPages = '\u000c' in text
        val divided = if (explicitPages) {
            text.split('\u000c').flatMap { section -> TextbookExtraction.paginate(section.trim()) }
                .mapIndexed { i, page -> page.copy(number = i + 1) }
        } else BookText.chapters(text)
        val pages = divided.map { page ->
            val ids = Regex("【脚注 ([0-9]+)】").findAll(page.text).map { it.groupValues[1] }.distinct().toList()
            if (ids.isEmpty()) page else {
                val suffix = StringBuilder()
                for (id in ids) {
                    suffix.append("\n\n脚注 $id：")
                    suffix.append(footnotes[id] ?: "（原文件缺少这条脚注）")
                }
                page.copy(text = page.text + suffix)
            }
        }
        return ExtractedBook(title, pages, FormatBookImages.attach(raw, divided, placements))
    }

    private fun word(zip: SafeBookArchive, title: String, elements: MutableList<WordElementSource>? = null): ExtractedBook {
        val noteBytes = if (zip.contains("word/footnotes.xml")) zip.read("word/footnotes.xml") else null
        val noteRoot = noteBytes?.let(::xml)
        val noteTrace = if (elements != null && noteBytes != null && noteRoot != null) WordTextTrace("word/footnotes.xml", noteBytes, noteRoot) else null
        val noteText = if (noteTrace != null) StringBuilder() else null
        val footnotes = noteRoot?.all("footnote")
            ?.filter { it.attr("id").toIntOrNull()?.let { id -> id > 0 } == true }
            ?.associate { note ->
                val base = noteText?.length ?: 0
                val value = inline(note, span = noteTrace?.let { trace -> { node, start, end -> trace.record(node, base + start, base + end) } })
                noteText?.append(value)
                note.attr("id") to (base to value)
            }.orEmpty()
        if (noteTrace != null && noteRoot != null) noteTrace.record(noteRoot, 0, noteText!!.length)
        val headingStyles = if (zip.contains("word/styles.xml")) xml(zip.read("word/styles.xml")).all("style")
            .filter { style -> style.all("outlineLvl").isNotEmpty() || style.all("name").any { it.attr("val").matches(Regex("(?i)heading\\s*\\d+")) } }
            .map { it.attr("styleId") }.toSet() else emptySet()
        val output = StringBuilder()
        val documentBytes = zip.read("word/document.xml")
        val document = xml(documentBytes)
        val trace = if (elements != null) WordTextTrace("word/document.xml", documentBytes, document) else null
        val placements = mutableListOf<FormatBookImages.Placement>()
        val rels = if (zip.contains("word/_rels/document.xml.rels")) relationships(zip, "word/_rels/document.xml.rels") else emptyMap()
        var imageBytes = 0L
        fun appendInline(node: Node, includeRoot: Boolean = true) {
            val base = output.length
            val value = inline(node, span = trace?.let { source -> { current, start, end ->
                if (includeRoot || current !== node) source.record(current, base + start, base + end)
            } }) { image, offset ->
                val id = if (image.localName == "blip") image.attr("embed") else image.attr("id")
                val target = rels[id] ?: return@inline
                require(placements.size < FormatBookImages.MAX_IMAGES) { "这份 Word 插图过多，请分册导入。" }
                val bytes = zip.read(zip.resolve("word/document.xml", target))
                imageBytes += bytes.size
                require(imageBytes <= FormatBookImages.MAX_TOTAL) { "这份 Word 插图超过 64 MB，请分册导入。" }
                var ancestor: Node? = image.parentNode
                var caption: String? = null
                while (ancestor is Element && ancestor.localName !in setOf("p", "tc", "body")) {
                    caption = caption ?: (ancestor.all("docPr") + ancestor.all("cNvPr")).firstNotNullOfOrNull {
                        it.attr("descr").ifBlank { it.attr("title") }.takeIf(String::isNotBlank)
                    }
                    ancestor = ancestor.parentNode
                }
                placements += FormatBookImages.Placement(base + offset, bytes, caption)
            }
            output.append(value)
        }
        fun visit(node: Node) {
            if (node !is Element) return
            var start = output.length
            var end: Int? = null
            when (node.localName) {
                "p" -> {
                    if (node.all("pageBreakBefore").any { it.attr("val") !in setOf("0", "false", "off") }) output.append('\u000c')
                    val style = node.all("pStyle").firstOrNull()?.attr("val").orEmpty()
                    output.append("\n\n")
                    start = output.length
                    if (style in headingStyles || style.matches(Regex("(?i)heading[1-9]"))) output.append("# ")
                    appendInline(node, includeRoot = false)
                    end = output.length
                    output.append("\n\n")
                }
                "tbl" -> node.children().filter { it.localName == "tr" }.forEach { row ->
                    output.append("\n\n")
                    val rowStart = output.length
                    row.children().filter { it.localName == "tc" }.forEachIndexed { cellIndex, cell ->
                        if (cellIndex > 0) output.append(" | ")
                        val cellStart = output.length
                        cell.children().filter { it.localName == "p" }.forEachIndexed { paragraphIndex, paragraph ->
                            if (paragraphIndex > 0) output.append(" ")
                            appendInline(paragraph)
                        }
                        trace?.record(cell, cellStart, output.length)
                    }
                    trace?.record(row, rowStart, output.length)
                    output.append("\n\n")
                }
                "sectPr" -> if (node.all("type").any { it.attr("val") in setOf("nextPage", "evenPage", "oddPage") }) output.append('\u000c')
                else -> node.children().forEach(::visit)
            }
            trace?.record(node, start, end ?: output.length)
        }
        visit(document)
        val text = output.toString().trim()
        require(text.isNotBlank()) { "这份 Word 文档没有可读取的文字。" }
        val leading = output.length - output.toString().trimStart().length
        val explicitPages = '\u000c' in text
        val divided = if (explicitPages) {
            var cursor = leading
            text.split('\u000c').flatMap { section ->
                val local = TextbookExtraction.paginate(section.trim())
                trace?.pages(cursor + section.length - section.trimStart().length, local)
                cursor += section.length + 1
                local
            }.mapIndexed { i, page -> page.copy(number = i + 1) }
        } else BookText.chapters(text, trace?.let { source -> { start, pages ->
            source.pages(TextbookExtraction.clean(output.substring(0, leading)).length + start, pages)
        } })
        val pages = divided.map { page ->
            val ids = Regex("【脚注 ([0-9]+)】").findAll(page.text).map { it.groupValues[1] }.distinct().toList()
            val suffix = StringBuilder()
            for (id in ids) {
                suffix.append("\n\n脚注 $id：")
                val note = footnotes[id]
                if (note != null) noteTrace?.copy(page.number, note.first, note.second, page.text.length + suffix.length)
                suffix.append(note?.second ?: "（原文件缺少这条脚注）")
            }
            page.copy(text = page.text + suffix)
        }
        trace?.let { elements?.addAll(it.project(output.toString(), normalize = !explicitPages)) }
        noteTrace?.let { elements?.addAll(it.project(noteText.toString(), normalize = false)) }
        return ExtractedBook(title, pages, FormatBookImages.attach(output.toString(), divided, placements))
    }

    private fun inline(node: Node, span: ((Element, Int, Int) -> Unit)? = null, image: ((Element, Int) -> Unit)? = null): String {
        val output = StringBuilder()
        fun visit(current: Node) {
            if (current !is Element) return
            val start = output.length
            when (current.localName) {
                "t" -> output.append(current.textContent)
                "tab" -> output.append('\t')
                "br" -> output.append(if (current.attr("type") == "page") '\u000c' else '\n')
                "cr" -> output.append('\n')
                "footnoteReference" -> output.append("【脚注 ${current.attr("id")}】")
                "del", "instrText" -> return
                "blip", "imagedata" -> { image?.invoke(current, output.length); current.children().forEach(::visit) }
                else -> current.children().forEach(::visit)
            }
            span?.invoke(current, start, output.length)
        }
        visit(node)
        return output.toString()
    }

    private fun slides(zip: SafeBookArchive, title: String, control: BookImportControl): ExtractedBook {
        val presentation = xml(zip.read("ppt/presentation.xml"))
        val relationships = relationships(zip, "ppt/_rels/presentation.xml.rels")
        val ids = presentation.all("sldId")
        require(ids.size in 1..5000) { "这份幻灯片没有可打开的页面，或页数超过 5000。" }
        val pages = ids.mapIndexed { i, slide ->
            val ref = slide.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            val path = zip.resolve("ppt/presentation.xml", requireNotNull(relationships[ref]) { "幻灯片的阅读顺序不完整，请重新保存。" })
            val doc = xml(zip.read(path))
            val text = doc.all("p").joinToString("\n\n") { paragraph -> paragraph.children().joinToString("") { slideInline(it) } }
            val relPath = path.substringBeforeLast('/') + "/_rels/" + path.substringAfterLast('/') + ".rels"
            val notesTarget = if (zip.contains(relPath)) xml(zip.read(relPath)).all("Relationship")
                .firstOrNull { it.attr("Type").endsWith("/notesSlide") && it.attr("TargetMode") != "External" }?.attr("Target") else null
            val notes = notesTarget?.let { target ->
                xml(zip.read(zip.resolve(path, target))).all("sp").filter { shape ->
                    shape.all("ph").none { it.attr("type") in setOf("sldImg", "sldNum", "dt", "hdr", "ftr") }
                }.flatMap { it.all("p") }.joinToString("\n\n") { paragraph -> paragraph.children().joinToString("") { slideInline(it) } }.trim()
            }.orEmpty()
            BookPage(i + 1, text.trim() + if (notes.isNotBlank()) "\n\n备注\n\n$notes" else "")
        }
        require(pages.any { it.text.isNotBlank() }) { "这份幻灯片只有图片，没有可读取的文字；请使用文字版材料。" }
        return ExtractedBook(title, pages, PptBookImages.render(zip, pages, control))
    }

    private fun slideInline(node: Element): String = when (node.localName) {
        "t" -> node.textContent
        "br" -> "\n"
        "tab" -> "\t"
        else -> node.children().joinToString("") { slideInline(it) }
    }

    private fun odt(zip: SafeBookArchive, title: String): ExtractedBook {
        if (zip.contains("META-INF/manifest.xml")) require(xml(zip.read("META-INF/manifest.xml")).all("encryption-data").isEmpty()) {
            "这份 ODT 文档有密码保护，请取消密码后再导入。"
        }
        val doc = xml(zip.read("content.xml"))
        val output = StringBuilder()
        val notes = mutableListOf<String>()
        fun visit(node: Node) {
            if (node.nodeType == Node.TEXT_NODE) { output.append(node.nodeValue); return }
            if (node !is Element) return
            when (node.localName) {
                "scripts", "automatic-styles", "tracked-changes" -> return
                "note" -> {
                    val body = node.all("note-body").firstOrNull()?.textContent.orEmpty()
                    notes += body; output.append("【脚注 ${notes.size}】"); return
                }
                "s" -> { output.append(" ".repeat((node.attr("c").toIntOrNull() ?: 1).coerceIn(1, 1000))); return }
                "tab" -> { output.append('\t'); return }
                "line-break" -> { output.append('\n'); return }
            }
            val block = node.localName in setOf("h", "p", "table-row")
            if (block) output.append("\n\n")
            if (node.localName == "h") output.append("# ")
            for (i in 0 until node.childNodes.length) visit(node.childNodes.item(i))
            if (node.localName == "table-cell") output.append(" | ")
            if (block) output.append("\n\n")
        }
        val body = requireNotNull(doc.all("body").firstOrNull()) { "这份 ODT 文档缺少正文。" }
        visit(body)
        val pages = BookText.chapters(output.toString()).map { page ->
            val ids = Regex("【脚注 ([0-9]+)】").findAll(page.text).map { it.groupValues[1].toInt() }.distinct().toList()
            page.copy(text = page.text + ids.joinToString("") { id -> "\n\n脚注 $id：${notes[id - 1]}" })
        }
        return ExtractedBook(title, pages)
    }

    private fun relationships(zip: SafeBookArchive, path: String) = xml(zip.read(path)).all("Relationship")
        .filter { it.attr("TargetMode") != "External" }.associate { it.attr("Id") to it.attr("Target") }

    internal fun xml(bytes: ByteArray): Element = parseBookOfficeXml(bytes)
    private fun Element.attr(local: String): String = (0 until attributes.length).map { attributes.item(it) }
        .firstOrNull { it.localName == local || it.nodeName == local }?.nodeValue.orEmpty()
    private fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.all(local: String): List<Element> = getElementsByTagNameNS("*", local).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
}
