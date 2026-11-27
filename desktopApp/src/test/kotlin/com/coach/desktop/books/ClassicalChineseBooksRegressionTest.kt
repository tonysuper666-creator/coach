package com.coach.desktop.books

import com.coach.domain.grade.ChapterOpenGate
import com.coach.domain.grade.ChapterOpenRawPoint
import com.coach.domain.grade.ChapterOpenSourceBlock
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClassicalChineseBooksRegressionTest {
    private val bookId = "a".repeat(64)

    @Test
    fun lunyuStyleMarkdownExcludesBookTitleAndCollationFromChaptersAndKeepsNumberBound() {
        val cnNums = listOf(
            "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        )
        val chapterTitles = listOf(
            "學而", "為政", "八佾", "里仁", "公冶", "雍也", "述而", "泰伯", "子罕", "鄉黨",
            "先進", "顏淵", "子路", "憲問", "衛靈", "季氏", "陽貨", "微子", "子張", "堯曰",
        ).mapIndexed { i, name -> "${name}第${cnNums[i]}" }

        val sb = StringBuilder()
        sb.append("# 古典對話錄\n\n")
        chapterTitles.forEachIndexed { idx, title ->
            sb.append("### ").append(title).append("\n\n")
            sb.append("${cnNums[idx]}之一\n\n")
            sb.append("師曰：「問學之道，日省其身而踐行不輟，亦足悅乎。今試演第${idx + 1}段義理，務求言簡意賅。」\n\n")
            if (idx % 2 == 0) {
                sb.append("#### 校勘記\n\n")
                sb.append("[一] 「踐行」別本或作「篤行」。\n\n")
            }
        }
        val pages = BookText.chapters(sb.toString())
        val structure = BookStructurePlanner.plan(bookId, "古典對話錄", "md", pages)
        val chapters = structure.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }
        val subsections = structure.tocNodes.filter { it.kind == TocNodeKind.SUBSECTION }
        assertEquals(20, chapters.size)
        assertTrue(chapters.none { it.title == "古典對話錄" })
        assertTrue(chapters.none { it.title.contains("校勘記") })
        assertEquals(10, subsections.size)
        assertTrue(subsections.all { it.title.contains("校勘記") })

        val blocks = StudyCoachCourse.blocks(bookId, pages)
        val p2FirstNum = blocks.first { "一之一" in it.text }
        val p2Text = pages.first { it.number == p2FirstNum.page }.text
        assertTrue("師曰：「問學之道" in p2FirstNum.text, "一之一应与下文语录绑定同一阅读块: ${p2FirstNum.text}")
        assertEquals(p2FirstNum.text, p2Text.substring(p2FirstNum.start, p2FirstNum.end))

        val book = Textbook(bookId, "古典對話錄", "md", pages, structure = structure)
        val signals = ReadingMethodSignalsReader.fromBook(book)
        val choice = ReadingMethodSelector.choose(signals)
        assertFalse("则短篇" in choice.reason, choice.reason)
    }

    @Test
    fun wangBiCommentaryStyleMarkdownKeeps81ChaptersPartsBackMatterAndLayers() {
        val ones = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        fun cn(n: Int): String = when {
            n < 10 -> ones[n]
            n == 10 -> "十"
            n < 20 -> "十${ones[n - 10]}"
            n % 10 == 0 -> "${ones[n / 10]}十"
            else -> "${ones[n / 10]}十${ones[n % 10]}"
        }

        val p1 = buildString {
            append("# 玄言注本\n\n晉 王弼注.\n\n## 上篇\n\n")
            for (i in 1..37) {
                append("### ${cn(i)}章\n\n")
                append("大道之行，順自然之勢而無為（第${i}章義）。\n\n")
                append("〈夫自然之理，以無為本，故不強作而自成也。〉\n\n")
            }
        }
        val p2 = buildString {
            append("## 下篇\n\n")
            for (i in 38..81) {
                append("### ${cn(i)}章\n\n")
                append("大德若虛，守謙靜之度而萬化歸焉（第${i}章義）。\n\n")
            }
            append("## 跋\n\n宋人題跋一則。\n\n")
            append("## 經典釋文\n\n唐人釋文體例。\n\n")
            append("## 老子道德經音義\n\n字音釋義若干。\n\n")
            append("## 注釋\n\n校注說明。\n")
        }
        val pages = listOf(BookPage(1, p1), BookPage(2, p2))
        val structure = BookStructurePlanner.plan(bookId, "玄言注本", "md", pages)

        val parts = structure.tocNodes.filter { it.kind == TocNodeKind.PART }
        val chapters = structure.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }
        val backMatter = structure.tocNodes.filter { it.kind == TocNodeKind.BACK_MATTER }
        assertEquals(listOf("上篇", "下篇"), parts.map { it.title })
        assertEquals(81, chapters.size)
        assertTrue(backMatter.size >= 4, "跋/經典釋文/音義/注釋应归为 BACK_MATTER: ${backMatter.map { it.title }}")

        val book = Textbook(bookId, "玄言注本", "md", pages, structure = structure)
        val noteBlock = StudyCoachCourse.blocks(bookId, pages).first { "〈夫自然之理" in it.text }
        assertEquals(ClassicalTextLayer.COMMENTARY, ClassicalAnnotationLayer.classifyBlock(noteBlock.text))
        val ctx = ClassicalAnnotationLayer.buildExplainContext(book, noteBlock, null)
        assertNotNull(ctx)
        assertTrue("王弼注" in ctx, ctx)
        assertTrue("注文" in ctx, ctx)
    }

    @Test
    fun zhuangziStyleVariantAndReadingMethodAvoidsShortStoriesLabel() {
        val chaptersMd = (1..9).joinToString("\n\n") { idx ->
            "## 寓言篇第$idx\n\n莊生嘗言於河伯與山木之間，大鵬一作「天鵝」而徙於南冥，齊萬物為一。"
        }
        val pages = listOf(BookPage(1, "# 南華寓言選\n\n$chaptersMd"))
        val structure = BookStructurePlanner.plan(bookId, "南華寓言選", "md", pages)
        val chapters = structure.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }
        assertEquals(9, chapters.size)
        val block = StudyCoachCourse.blocks(bookId, pages).first { "一作「天鵝」" in it.text }
        assertEquals(ClassicalTextLayer.TEXTUAL_VARIANT, ClassicalAnnotationLayer.classifyBlock(block.text))

        val book = Textbook(bookId, "南華寓言選", "md", pages, structure = structure)
        val choice = ReadingMethodSelector.choose(ReadingMethodSignalsReader.fromBook(book))
        assertFalse("则短篇" in choice.reason, choice.reason)
        assertTrue("9 篇" in choice.reason || "9 章" in choice.reason, choice.reason)
    }

    @Test
    fun tianyanlunStylePreservesZixuAndFuanCommentaryLayer() {
        val cnNums = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八")
        val body = buildString {
            append("# 演化譯論\n\n")
            append("## 吳序\n\n吳氏序言若干句，論翻譯信達雅之道。\n\n")
            append("## 譯例言\n\n譯者述翻譯凡例數則。\n\n")
            append("## 自序\n\n自序略言譯是書之旨趣與體例。\n\n")
            for (i in 1..18) append("## 導言${cnNums[i - 1]}\n\n今之演化者，物競而天擇，適者乃存第${i}。\n\n")
            for (i in 1..17) {
                append("## 論${cnNums[i - 1]}\n\n群己權界與人治天行之辨第${i}。\n\n")
                if (i == 1) append("復案：此條推論天行與人治之界說，當細玩上下文。\n\n")
            }
        }
        val pages = listOf(BookPage(1, body))
        val structure = BookStructurePlanner.plan(bookId, "演化譯論", "md", pages)
        val chapters = structure.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }
        assertEquals(38, chapters.size)
        assertTrue(chapters.any { it.title == "自序" }, "自序不应被漏掉: ${chapters.map { it.title }}")
        assertTrue(chapters.none { it.title == "演化譯論" })

        val book = Textbook(bookId, "演化譯論", "md", pages, structure = structure)
        val fuanBlock = StudyCoachCourse.blocks(bookId, pages).first { "復案：此條" in it.text }
        assertEquals(ClassicalTextLayer.COMMENTARY, ClassicalAnnotationLayer.classifyBlock(fuanBlock.text))
        val ctx = ClassicalAnnotationLayer.buildExplainContext(book, fuanBlock, null)
        assertNotNull(ctx)
        assertTrue("嚴復按語（復案）" in ctx, ctx)
    }

    @Test
    fun shijiStyleTxtAndEpubRecoverVolumesWithoutLicenseOrCalendarPollution() {
        val txt = buildString {
            append("史記 目錄\n\n本紀\n五帝本紀\n夏本紀\n殷本紀\n項羽本紀\n列傳\n伯夷列傳\n\n")
            append("本紀\n\n")
            append("史記 五帝本紀\n\n昔太史公述上古五帝之事，自黃帝始，修德振兵以撫萬民。\n【索隱述贊】帝德修明，萬邦乃寧。\n\n")
            append("史記 夏本紀\n\n夏禹疏九河以通九州之水，十三年居外而不入家門。\n\n")
            append("史記 殷本紀\n\n湯承夏統，修德行義，諸侯咸歸而四海安寧。\n\n")
            append("史記 項羽本紀\n\n力拔山兮氣蓋世，興亡成敗之際可以為鑑。\n\n")
            append("列傳\n\n")
            append("史記 伯夷列傳\n\n夫學者載籍極博，猶考信於六藝，賢人清節垂於後世。\n")
        }
        val txtChapters = BookText.chapters(txt)
        assertTrue(txtChapters.size >= 4, "TXT 应拆分史记卷目，实际: ${txtChapters.map { it.text.lineSequence().firstOrNull().orEmpty() }}")
        val txtPages = listOf(BookPage(1, txt))
        val txtPlan = BookStructurePlanner.plan(bookId, "史記", "txt", txtPages)
        val txtChaptersToc = txtPlan.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }
        val txtParts = txtPlan.tocNodes.filter { it.kind == TocNodeKind.PART }
        assertEquals(listOf("本紀", "列傳"), txtParts.map { it.title })
        assertTrue(txtChaptersToc.any { it.title.contains("五帝本紀") })
        assertTrue(txtChaptersToc.any { it.title.contains("夏本紀") })
        assertTrue(txtChaptersToc.any { it.title.contains("伯夷列傳") })
        assertEquals(ClassicalTextLayer.COMMENTARY.label, run {
            val block = StudyCoachCourse.blocks(bookId, txtPages).first { "【索隱述贊】" in it.text }
            ClassicalAnnotationLayer.classifyBlock(block.text).label
        })
        val txtBook = Textbook(bookId, "史記", "txt", txtPages, structure = txtPlan)
        val txtMethod = ReadingMethodSelector.choose(ReadingMethodSignalsReader.fromBook(txtBook))
        assertTrue("历史书" in txtMethod.reason, txtMethod.reason)

        // 构造带 Gutenberg License 伪目录与历书格子噪声的合成 EPUB
        val tempDir = Files.createTempDirectory("coach-shiji-epub-")
        try {
            val epubFile = tempDir.resolve("shiji-synthetic.epub")
            val entries = linkedMapOf(
                "mimetype" to "application/epub+zip".toByteArray(StandardCharsets.UTF_8),
                "META-INF/container.xml" to """<?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>""".trimIndent().toByteArray(StandardCharsets.UTF_8),
                "OEBPS/content.opf" to """<?xml version="1.0" encoding="utf-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>史記</dc:title>
                      </metadata>
                      <manifest>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine toc="ncx">
                        <itemref idref="c1"/>
                        <itemref idref="c2"/>
                      </spine>
                    </package>""".trimIndent().toByteArray(StandardCharsets.UTF_8),
                "OEBPS/toc.ncx" to """<?xml version="1.0" encoding="utf-8"?>
                    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                      <navMap>
                        <navPoint id="n1" playOrder="1">
                          <navLabel><text>THE FULL PROJECT GUTENBERG™ LICENSE</text></navLabel>
                          <content src="ch2.xhtml#pg_license"/>
                        </navPoint>
                      </navMap>
                    </ncx>""".trimIndent().toByteArray(StandardCharsets.UTF_8),
                "OEBPS/ch1.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body>
                    <p id="id00001">◎曆術甲子篇</p>
                    <p id="id00002">正北</p>
                    <p id="id00003">十二</p>
                    <p id="id00004">閏十三</p>
                    <p id="id00005">太初元年歲名焉逢攝提格</p>
                    <p id="id00010">史記 五帝本紀</p>
                    <p>昔太史公述上古五帝之事，自黃帝始，修德振兵以撫萬民，天下有不順者從而征之。</p>
                    <p>【索隱述贊】帝德修明，萬邦乃寧。</p>
                    </body></html>""".trimIndent().toByteArray(StandardCharsets.UTF_8),
                "OEBPS/ch2.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body>
                    <p id="id00020">史記 伯夷列傳</p>
                    <p>夫學者載籍極博，猶考信於六藝。詩書雖缺，然虞夏之文可知也。</p>
                    <div id="pg_license">THE FULL PROJECT GUTENBERG™ LICENSE PLEASE READ THIS</div>
                    </body></html>""".trimIndent().toByteArray(StandardCharsets.UTF_8),
            )
            writeZip(epubFile, entries)
            val lib = TextbookLibrary(tempDir.resolve("lib"))
            val imported = lib.importBook(epubFile)
            val epubChapters = imported.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }.map { it.title }
            assertTrue(epubChapters.any { "五帝本紀" in it }, "应恢复 EPUB 正文卷标: $epubChapters")
            assertTrue(epubChapters.any { "伯夷列傳" in it }, "应恢复 EPUB 伯夷列傳: $epubChapters")
            assertFalse(epubChapters.any { "正北" in it || it == "十二" || "閏十三" in it }, "历书格子噪声不得入选章节: $epubChapters")
            assertFalse(epubChapters.any { "GUTENBERG" in it }, "License 不得成为章节: $epubChapters")

            val soyinBlock = imported.sourceBlocks().first { "【索隱述贊】" in it.text }
            val explainCtx = ClassicalAnnotationLayer.buildExplainContext(imported, soyinBlock, null)
            assertNotNull(explainCtx)
            assertTrue("五帝本紀" in explainCtx, explainCtx)
            assertTrue("司馬貞【索隱述贊】" in explainCtx, explainCtx)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun cjkCitationGateAndOpenQuestionGateProduceExactPageOffsets() {
        val wrappedPage =
            "一之一\n" +
                "大道\n" +
                "之行也，天下\n" +
                "為公，選賢與能，講信修睦。\n" +
                "故人不獨親其親，不獨子其子。"
        val verified = SentenceCitationGate.verifyOrSnapAnchor("大道之行也，天下為公，選賢與能", wrappedPage)
        assertTrue(verified.verified, "换行中文原文应命中高亮校验")
        assertTrue("選賢與能" in verified.canonicalAnchor)
        assertTrue(wrappedPage.indexOf(verified.canonicalAnchor) >= 0)

        val pagePrefix = "本卷前言若干字，用以占位，使目标段落偏移量大于零。\n\n"
        val blockText = "問學之道，\n日省其身而踐行不輟。"
        val fullPage = pagePrefix + blockText
        val blockStart = fullPage.indexOf(blockText)
        val points = ChapterOpenGate.acceptPoints(
            raw = listOf(
                ChapterOpenRawPoint(
                    id = "p1",
                    claim = "强调每日反思并持续践行所学内容",
                    blockId = "book:p2:$blockStart",
                    quoteAnchor = "問學之道，日省其身而踐行不輟",
                ),
            ),
            blocks = listOf(
                ChapterOpenSourceBlock(
                    id = "book:p2:$blockStart",
                    page = 2,
                    text = blockText,
                    startUtf16 = blockStart,
                ),
            ),
        )
        assertEquals(1, points.size)
        val accepted = points.single()
        assertEquals(blockStart, accepted.startUtf16)
        assertEquals(
            accepted.quoteAnchor,
            fullPage.substring(accepted.startUtf16, accepted.endUtf16),
            "开放题补读锚点必须是相对整页文本的精确 UTF-16 切片",
        )
    }

    @Test
    fun localPublicCorpusChineseBooksMeetStructureAndLayerContractsWhenPresent() {
        val homeDir = System.getenv("HOME")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
        val corpusRoot = java.nio.file.Path.of(homeDir, "Coach/materials/public-corpus/chinese")
        org.junit.Assume.assumeTrue("local chinese public corpus not present", Files.isDirectory(corpusRoot))
        val libRoot = Files.createTempDirectory("coach-chinese-corpus-check-")
        try {
            val lib = TextbookLibrary(libRoot.resolve("lib"))

            val lunyu = lib.importBook(corpusRoot.resolve("lunyu/lunyu.txt"))
            val lunyuChapters = lunyu.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }
            assertEquals(20, lunyuChapters.size, "论语篇数应为20: ${lunyuChapters.map { it.title }}")
            assertTrue(lunyuChapters.none { "論語" in it.title || "校勘記" in it.title })
            assertTrue(lunyu.tocNodes.any { it.kind == TocNodeKind.SUBSECTION && "校勘記" in it.title })
            val lunyuP2 = lunyu.sourceBlocks().filter { it.page == 2 }
            val numBlock = lunyuP2.firstOrNull { "一之一" in it.text }
            assertNotNull(numBlock, "论语第2页应保留一之一")
            assertTrue(numBlock.text.trim().length > 10, "一之一应与下方语录合并成块")
            assertFalse("则短篇" in ReadingMethodSelector.choose(ReadingMethodSignalsReader.fromBook(lunyu)).reason)

            val ddj = lib.importBook(corpusRoot.resolve("daodejing-wangbi/daodejing-wangbi.txt"))
            val ddjChapters = ddj.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }
            assertEquals(81, ddjChapters.size, "道德经王弼注应保持81章，多余章节: ${ddjChapters.map { it.title }.filterNot { it.endsWith("章") }}")
            assertEquals(listOf("老子《道德經》上篇", "老子《道德經》下篇"), ddj.tocNodes.filter { it.kind == TocNodeKind.PART }.map { it.title })
            val ddjCommentCount = ddj.sourceBlocks().count { ClassicalAnnotationLayer.classifyBlock(it.text) == ClassicalTextLayer.COMMENTARY }
            assertTrue(ddjCommentCount >= 200, "王弼注分层数量过少: $ddjCommentCount")

            val zhuangzi = lib.importBook(corpusRoot.resolve("zhuangzi-selected/zhuangzi-selected.txt"))
            val zzChapters = zhuangzi.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }
            assertEquals(9, zzChapters.size, "庄子选应为9篇: ${zzChapters.map { it.title }}")
            assertFalse("则短篇" in ReadingMethodSelector.choose(ReadingMethodSignalsReader.fromBook(zhuangzi)).reason)

            val tianyan = lib.importBook(corpusRoot.resolve("tianyanlun/tianyanlun.txt"))
            val tyChapters = tianyan.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }
            assertEquals(38, tyChapters.size, "天演论应包含38节（含自序）")
            assertTrue(tyChapters.any { it.title == "自序" })

            val shijiTxt = lib.importBook(corpusRoot.resolve("shiji/shiji.txt"))
            val shijiTxtChapters = shijiTxt.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }
            assertEquals(130, shijiTxtChapters.size, "史记 TXT 应切分出130卷")
            assertTrue("历史书" in ReadingMethodSelector.choose(ReadingMethodSignalsReader.fromBook(shijiTxt)).reason)

            val shijiEpub = lib.importBook(corpusRoot.resolve("shiji/shiji.epub"))
            val shijiEpubChapters = shijiEpub.tocNodes.filter { it.kind == TocNodeKind.CHAPTER }
            assertTrue(shijiEpubChapters.size in 127..130, "史记 EPUB 应覆盖全部127独立卷/130篇，实际: ${shijiEpubChapters.size}")
            assertTrue(shijiEpubChapters.none { Regex("""（\d+/\d+）$""").containsMatchIn(it.title) })
            assertTrue(shijiEpubChapters.none { "GUTENBERG" in it.title.uppercase() })
            assertTrue("历史书" in ReadingMethodSelector.choose(ReadingMethodSignalsReader.fromBook(shijiEpub)).reason)
        } finally {
            libRoot.toFile().deleteRecursively()
        }
    }

    private fun writeZip(path: java.nio.file.Path, entries: Map<String, ByteArray>) {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        Files.write(path, out.toByteArray())
    }
}
