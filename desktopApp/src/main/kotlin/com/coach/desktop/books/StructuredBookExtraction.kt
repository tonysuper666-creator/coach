package com.coach.desktop.books

import org.jsoup.Jsoup
import org.jsoup.nodes.Element as HtmlElement
import org.jsoup.nodes.Node as HtmlNode
import org.jsoup.nodes.TextNode
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.text.rtf.RTFEditorKit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal object StructuredBookExtraction {
    fun extract(path: Path, format: String, title: String): ExtractedBook = when (format) {
        "html" -> html(BookText.decode(Files.readAllBytes(path)), title)
        "rtf" -> {
            require(Files.size(path) <= 32L * 1024 * 1024) { "文本教材请控制在 32 MB 以内。" }
            val kit = RTFEditorKit()
            val document = kit.createDefaultDocument()
            Files.newInputStream(path).use { kit.read(it, document, 0) }
            ExtractedBook(title, BookText.chapters(document.getText(0, document.length)))
        }
        else -> SafeBookArchive(path).use { zip ->
            val bookTitle = if (zip.contains("docProps/core.xml")) xml(zip.read("docProps/core.xml")).all("title").firstOrNull()?.textContent?.trim()?.takeIf(String::isNotEmpty) ?: title else title
            when (format) {
                "docx" -> word(zip, bookTitle)
                "pptx" -> slides(zip, bookTitle)
                "odt" -> odt(zip, title)
                else -> throw IllegalArgumentException("请使用 PDF、电子书、Word、幻灯片、网页或文本教材。")
            }
        }
    }

    fun html(text: String, title: String): ExtractedBook {
        val doc = Jsoup.parse(text)
        doc.select("script,style,nav,header,footer,aside,form,iframe,noscript,[hidden],[aria-hidden=true]").remove()
        val main = doc.selectFirst("article") ?: doc.selectFirst("main,[role=main]") ?: doc.body()
        val output = StringBuilder()
        fun visit(node: HtmlNode) {
            if (node is TextNode) { output.append(node.wholeText); return }
            val name = (node as? HtmlElement)?.normalName().orEmpty()
            val block = name in setOf("p", "div", "li", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "br", "tr", "pre", "blockquote")
            if (block) output.append("\n\n")
            if (name.matches(Regex("h[1-6]"))) output.append("# ")
            node.childNodes().forEach(::visit)
            if (name in setOf("td", "th")) output.append(" | ")
            if (block) output.append("\n\n")
        }
        visit(main)
        return ExtractedBook(doc.title().trim().ifEmpty { title }, BookText.chapters(output.toString()))
    }

    private fun word(zip: SafeBookArchive, title: String): ExtractedBook {
        val footnotes = if (zip.contains("word/footnotes.xml")) xml(zip.read("word/footnotes.xml")).all("footnote")
            .filter { it.attr("id").toIntOrNull()?.let { id -> id > 0 } == true }
            .associate { it.attr("id") to inline(it) } else emptyMap()
        val headingStyles = if (zip.contains("word/styles.xml")) xml(zip.read("word/styles.xml")).all("style")
            .filter { style -> style.all("outlineLvl").isNotEmpty() || style.all("name").any { it.attr("val").matches(Regex("(?i)heading\\s*\\d+")) } }
            .map { it.attr("styleId") }.toSet() else emptySet()
        val output = StringBuilder()
        fun visit(node: Node) {
            if (node !is Element) return
            when (node.localName) {
                "p" -> {
                    if (node.all("pageBreakBefore").any { it.attr("val") !in setOf("0", "false", "off") }) output.append('\u000c')
                    val style = node.all("pStyle").firstOrNull()?.attr("val").orEmpty()
                    output.append("\n\n")
                    if (style in headingStyles || style.matches(Regex("(?i)heading[1-9]"))) output.append("# ")
                    output.append(inline(node))
                    output.append("\n\n")
                }
                "tbl" -> node.children().filter { it.localName == "tr" }.forEach { row ->
                    output.append("\n\n")
                    output.append(row.children().filter { it.localName == "tc" }.joinToString(" | ") { cell -> cell.children().filter { it.localName == "p" }.joinToString(" ") { inline(it) } })
                    output.append("\n\n")
                }
                "sectPr" -> if (node.all("type").any { it.attr("val") in setOf("nextPage", "evenPage", "oddPage") }) output.append('\u000c')
                else -> node.children().forEach(::visit)
            }
        }
        visit(xml(zip.read("word/document.xml")))
        val text = output.toString().trim()
        require(text.isNotBlank()) { "这份 Word 文档没有可读取的文字。" }
        val divided = if ('\u000c' in text) text.split('\u000c').flatMap { TextbookExtraction.paginate(it.trim()) }
            .mapIndexed { i, page -> page.copy(number = i + 1) } else BookText.chapters(text)
        val pages = divided.map { page ->
            val ids = Regex("【脚注 ([0-9]+)】").findAll(page.text).map { it.groupValues[1] }.distinct().toList()
            page.copy(text = page.text + ids.joinToString("") { id -> "\n\n脚注 $id：${footnotes[id] ?: "（原文件缺少这条脚注）"}" })
        }
        return ExtractedBook(title, pages)
    }

    private fun inline(node: Node): String {
        if (node !is Element) return ""
        return when (node.localName) {
            "t" -> node.textContent
            "tab" -> "\t"
            "br" -> if (node.attr("type") == "page") "\u000c" else "\n"
            "cr" -> "\n"
            "footnoteReference" -> "【脚注 ${node.attr("id")}】"
            "del", "instrText" -> ""
            else -> node.children().joinToString("") { inline(it) }
        }
    }

    private fun slides(zip: SafeBookArchive, title: String): ExtractedBook {
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
        return ExtractedBook(title, pages)
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

    private fun xml(bytes: ByteArray): Element {
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
    private fun Element.attr(local: String): String = (0 until attributes.length).map { attributes.item(it) }
        .firstOrNull { it.localName == local || it.nodeName == local }?.nodeValue.orEmpty()
    private fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.all(local: String): List<Element> = getElementsByTagNameNS("*", local).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
}
