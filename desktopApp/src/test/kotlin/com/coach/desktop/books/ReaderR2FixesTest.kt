package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.isSpecified
import com.coach.desktop.WindowsTextbookHost
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ReaderR2FixesTest {

    private fun ComposeUiTest.nodeText(tag: String): String =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().let { node ->
            val own = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
            if (own.isNotEmpty()) own
            else onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text }.orEmpty()
        }

    private fun ComposeUiTest.waitReaderReady(controller: TextbookController) {
        waitUntil(timeoutMillis = 15_000) {
            !controller.state.value.busy && controller.state.value.screen == BookScreen.READER
        }
        waitForIdle()
    }

    private fun writeGutenbergCopiedParagraphEpub(path: Path, withCover: Boolean = false) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            fun add(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add(
                "META-INF/container.xml",
                "<container><rootfiles><rootfile full-path='OEBPS/content.opf'/></rootfiles></container>",
            )
            add(
                "OEBPS/content.opf",
                """
                <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>合成短篇集</dc:title>
                    <dc:language>zh</dc:language>
                  </metadata>
                  <manifest>
                    <item id="wrap" href="wrap0000.xhtml" media-type="application/xhtml+xml"/>
                    <item id="body0" href="chunk0.xhtml" media-type="application/xhtml+xml"/>
                    <item id="footer" href="chunk_license.xhtml" media-type="application/xhtml+xml"/>
                    <item id="nav" href="toc.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  </manifest>
                  <spine>
                    <itemref idref="wrap"/>
                    <itemref idref="body0"/>
                    <itemref idref="footer"/>
                  </spine>
                </package>
                """.trimIndent(),
            )
            val cover = if (withCover) {
                "<svg xmlns='http://www.w3.org/2000/svg' width='400' height='600'><rect width='400' height='600' fill='navy'/></svg>"
            } else "<div/>"
            add("OEBPS/wrap0000.xhtml", "<html xmlns='http://www.w3.org/1999/xhtml'><body>$cover</body></html>")
            val copiedSentence = "S城南头有三间老屋，院里种着两棵高大的槐树，夏夜里常能听见蝉鸣，如今"
            val introParagraphs = (1..12).joinToString("\n") { idx ->
                "<p id='p$idx'>    序言第${idx}段讲述早年求学与写作的合成背景，每段都有充足的汉字长度以便自然分页排版。</p>"
            }
            val story1Paragraphs = (1..18).joinToString("\n") { idx ->
                if (idx in 5..7) {
                    "<h5 id='s1_$idx'>    寒冬清晨进城途中第${idx}段经过S门街巷人物对话与冬日景物，检验伪h5段落不被放大成标题。</h5>"
                } else {
                    "<p id='s1_$idx'>    寒冬清晨进城途中第${idx}段描写街巷人物对话与冬日景物，用于检验首开落点与同页翻屏进度反馈。</p>"
                }
            }
            val story2Paragraphs = (1..24).joinToString("\n") { idx ->
                "<p id='s2_$idx'>    夏夜摇船穿过石桥第${idx}段描写水乡夜航与旧友重逢的合成叙事段落。</p>"
            }
            add(
                "OEBPS/chunk0.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                <body>
                  <header class="pg-boilerplate pgheader" id="pg-header" lang="en">
                    <h2 id="pg-header-heading">The Project Gutenberg eBook of 合成短篇集</h2>
                    <div>This eBook is for the use of anyone anywhere in the United States and most other parts of the world at no cost under the Project Gutenberg License at www.gutenberg.org.</div>
                    <p>Title: 合成短篇集</p>
                    <p>Author: Synthetic Author</p>
                    <p>Release date: November 5, 2008 [eBook #99999]</p>
                    <p>Language: Chinese</p>
                    <p>Credits: Produced by Synthetic Transcriber</p>
                    <div id="pg-start-separator"><span>*** START OF THE PROJECT GUTENBERG EBOOK 合成短篇集 ***</span></div>
                  </header>
                  <p id="id00000">Produced by Synthetic Transcriber</p>
                  <p id="id00001" style="margin-top: 9em">《合成名家︰合成短篇集》</p>
                  <p id="id00002" style="margin-top: 2em">《合成短篇集》自序</p>
                  <p id="id00003">《合成短篇集》自序</p>
                  $introParagraphs
                  <h4 id="id00058">$copiedSentence</h4>
                  <p id="id00094" style="margin-top: 2em">一九二二年十二月记。</p>
                  <p id="id00095" style="margin-top: 2em">注釋︰</p>
                  <p id="id00096">本篇系作者为短篇小说集撰写的合成序言注释，供测试前置节识别与跳过使用。</p>
                  <p id="id00110" style="margin-top: 2em">小事纪闻</p>
                  $story1Paragraphs
                  <p id="id00210" style="margin-top: 2em">故乡夜航</p>
                  $story2Paragraphs
                </body>
                </html>
                """.trimIndent(),
            )
            add(
                "OEBPS/chunk_license.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                <body>
                  <footer class="pg-boilerplate pgheader" id="pg-footer" lang="en">
                    <div id="pg-end-separator"><span>*** END OF THE PROJECT GUTENBERG EBOOK 合成短篇集 ***</span></div>
                    <div>Updated editions will replace the previous one. Project Gutenberg is a registered trademark.</div>
                    <div id="project-gutenberg-license">START: FULL LICENSE</div>
                    <h2 id="pg-footer-heading">THE FULL PROJECT GUTENBERG™ LICENSE</h2>
                    <p>PLEASE READ THIS BEFORE YOU DISTRIBUTE OR USE THIS WORK. To protect the Project Gutenberg mission of promoting free distribution of electronic works, you agree to comply with all terms.</p>
                  </footer>
                </body>
                </html>
                """.trimIndent(),
            )
            add(
                "OEBPS/toc.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <body>
                  <nav epub:type="toc" role="doc-toc">
                    <ol>
                      <li><a href="chunk0.xhtml#id00058">$copiedSentence</a></li>
                      <li><a href="chunk_license.xhtml#pg-footer-heading">THE FULL PROJECT GUTENBERG™ LICENSE</a></li>
                    </ol>
                  </nav>
                </body>
                </html>
                """.trimIndent(),
            )
        }
    }

    private fun writeGutenbergPrefaceAndNewlineHeadingEpub(path: Path) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            fun add(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add(
                "META-INF/container.xml",
                "<container><rootfiles><rootfile full-path='OEBPS/content.opf'/></rootfiles></container>",
            )
            add(
                "OEBPS/content.opf",
                """
                <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Synthetic Principle Essay</dc:title>
                    <dc:language>en</dc:language>
                  </metadata>
                  <manifest>
                    <item id="wrap" href="wrap0000.xhtml" media-type="application/xhtml+xml"/>
                    <item id="body0" href="essay0.xhtml" media-type="application/xhtml+xml"/>
                    <item id="nav" href="toc.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  </manifest>
                  <spine>
                    <itemref idref="wrap"/>
                    <itemref idref="body0"/>
                  </spine>
                </package>
                """.trimIndent(),
            )
            add("OEBPS/wrap0000.xhtml", "<html xmlns='http://www.w3.org/1999/xhtml'><body><div/></body></html>")
            val prefaceBody = (1..24).joinToString("\n") { i ->
                "<p>Preface paragraph $i explains the origin of this synthetic treatise in calm analytical detail so the preface spans into the third imported page before concluding with a date.</p>"
            }
            val ch1Body = (1..22).joinToString("\n") { i ->
                "<p>Chapter one paragraph $i examines geometric and arithmetic ratios in synthetic population models with clear assumptions and empirical checks.</p>"
            }
            val ch2Body = (1..16).joinToString("\n") { i ->
                "<p>Chapter two paragraph $i analyzes preventive and positive checks across synthetic historical illustrations.</p>"
            }
            add(
                "OEBPS/essay0.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                <body>
                  <header class="pg-boilerplate pgheader" id="pg-header" lang="en">
                    <h2 id="pg-header-heading">The Project Gutenberg eBook of Synthetic Principle Essay</h2>
                    <div>This eBook is for the use of anyone anywhere at no cost under the Project Gutenberg License at www.gutenberg.org.</div>
                    <div id="pg-start-separator"><span>*** START OF THE PROJECT GUTENBERG EBOOK SYNTHETIC PRINCIPLE ESSAY ***</span></div>
                  </header>
                  <h1 id="pgepubid00000">
                  Synthetic Principle Essay
                  </h1>
                  <h2 id="pgepubid00001">
                  Synthetic Economist
                  </h2>
                  <h4 id="pgepubid00002">
                  1798
                  </h4>
                  <h3 id="pgepubid00003">
                  Preface
                  </h3>
                  $prefaceBody
                  <p class="noindent">7 June 1798<br/></p>
                  <div class="pg_body_wrapper"><a id="chap01"/></div>
                  <h3 id="pgepubid00004">
                  CHAPTER 1
                  </h3>
                  <p class="intro">Question stated—Outline of the principal argument of the synthetic essay</p>
                  $ch1Body
                  <div class="pg_body_wrapper"><a id="chap02"/></div>
                  <h3 id="pgepubid00005">
                  CHAPTER 2
                  </h3>
                  $ch2Body
                </body>
                </html>
                """.trimIndent(),
            )
            add(
                "OEBPS/toc.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <body>
                  <nav epub:type="toc" role="doc-toc">
                    <ol>
                      <li><a href="essay0.xhtml#pgepubid00000">Synthetic Principle Essay</a></li>
                      <li>
                        <a href="essay0.xhtml#pgepubid00001">Synthetic Economist</a>
                        <ol>
                          <li><a href="essay0.xhtml#pgepubid00002">1798</a></li>
                          <li><a href="essay0.xhtml#pgepubid00003">Preface</a></li>
                          <li><a href="essay0.xhtml#pgepubid00004">CHAPTER 1</a></li>
                          <li><a href="essay0.xhtml#pgepubid00005">CHAPTER 2</a></li>
                        </ol>
                      </li>
                    </ol>
                  </nav>
                </body>
                </html>
                """.trimIndent(),
            )
        }
    }

    private fun createOutlinedTextbookPdf(path: Path, totalPages: Int): Path {
        PDDocument().use { pdf ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val pages = (1..totalPages).map { p ->
                val page = PDPage(PDRectangle.LETTER)
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 13f)
                    cs.newLineAtOffset(64f, 710f)
                    when (p) {
                        1 -> {
                            cs.showText("Title Page - Synthetic Health Textbook")
                            cs.newLineAtOffset(0f, -22f)
                            cs.showText("Copyright 2026 Synthetic Academic Press. All rights reserved. ISBN 0-00-000000-0")
                        }
                        2 -> {
                            cs.showText("About the Authors")
                            cs.newLineAtOffset(0f, -22f)
                            cs.showText("Synthetic biography notes for the authors of this volume.")
                        }
                        3 -> {
                            cs.showText("Preface")
                            cs.newLineAtOffset(0f, -22f)
                            cs.showText("This preface introduces the pedagogical organization of the synthetic textbook.")
                        }
                        else -> {
                            cs.showText("Chapter ${p - 3} Synthetic Analysis on Page $p")
                            cs.newLineAtOffset(0f, -22f)
                            cs.showText("Detailed synthetic economic discussion on page $p covering equilibrium and welfare.")
                        }
                    }
                    cs.endText()
                }
                page
            }
            val outline = PDDocumentOutline()
            pdf.documentCatalog.documentOutline = outline
            fun addBookmark(title: String, pageIdx: Int) {
                val item = PDOutlineItem()
                item.title = title
                item.destination = PDPageFitWidthDestination().apply { page = pages[pageIdx] }
                outline.addLast(item)
            }
            addBookmark("About the Authors", 1) // physical page 2
            addBookmark("Preface", 2) // physical page 3
            addBookmark("Chapter 1 Foundations", 3) // physical page 4
            if (totalPages >= 20) {
                addBookmark("Chapter 2 Demand", 14) // physical page 15
            }
            pdf.save(path.toFile())
        }
        return path
    }

    @Test
    fun turningBackToOpeningNoticeThenJumpingToPrefaceUpdatesHeader() {
        val root = Files.createTempDirectory("coach-front-header-")
        try {
            val epub = root.resolve("synthetic.epub")
            writeGutenbergCopiedParagraphEpub(epub, withCover = true)
            val controller = TextbookController(TextbookLibrary(root.resolve("lib")))
            runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(1.5f)) {
                setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.initialized }
                runBlocking { controller.importBook(epub) }
                waitReaderReady(controller)
                waitUntil(timeoutMillis = 30_000) {
                    onAllNodesWithTag("book-sheet-0").fetchSemanticsNodes().isNotEmpty()
                }
                val previous = onNodeWithTag("previous-page")
                for (turn in 1..20) {
                    if (nodeText("reader-page-number").startsWith("第 1 /")) break
                    previous.performClick()
                    waitForIdle()
                }
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithText("Gutenberg", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(1, controller.state.value.page)
                assertTrue(nodeText("immersive-title").endsWith(" · 授权页"))
                onNodeWithTag("v7-directory-toggle").performClick()
                onNodeWithTag("toc-item-0").performClick()
                waitForIdle()
                assertTrue(nodeText("immersive-title").endsWith(" · 《合成短篇集》自序"))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun p2GutenbergEpubSkipsCopyrightOnFirstOpenUpdatesSubPageProgressAndMatchesLicenseHeader() {
        val root = Files.createTempDirectory("coach-ton1846-p2-")
        try {
            val epub = root.resolve("nahan-synthetic.epub")
            writeGutenbergCopiedParagraphEpub(epub)
            val library = TextbookLibrary(root.resolve("lib"))
            val controller = TextbookController(library)

            runSkikoComposeUiTest(size = Size(820f, 620f), density = Density(1f)) {
                setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.initialized }
                runBlocking { controller.importBook(epub) }
                waitReaderReady(controller)
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()

                // 1. #7 + #2: First open skips BOTH Gutenberg copyright AND 自序 (FRONT_MATTER), landing directly on Story 1 with real story title (never "正文")
                val importedBook = controller.state.value.book!!
                assertEquals(
                    listOf("《合成短篇集》自序", "小事纪闻", "故乡夜航", "THE FULL PROJECT GUTENBERG™ LICENSE"),
                    importedBook.tocNodes.map { it.title },
                )
                assertEquals(TocNodeKind.FRONT_MATTER, importedBook.tocNodes[0].kind)
                assertEquals(TocNodeKind.CHAPTER, importedBook.tocNodes[1].kind)

                val firstScreenText = nodeText("reader-text")
                assertFalse("Title: 合成短篇集" in firstScreenText, "First screen must not show Gutenberg Title metadata: $firstScreenText")
                assertFalse("START OF THE PROJECT GUTENBERG" in firstScreenText, "First screen must not show Gutenberg separator: $firstScreenText")
                assertFalse("Produced by Synthetic Transcriber" in firstScreenText, "First screen must not show Producer credit: $firstScreenText")
                assertFalse("自序" in firstScreenText || "序言第1段" in firstScreenText, "First open must skip 自序 (FRONT_MATTER) and land on Story 1: $firstScreenText")
                assertTrue("小事纪闻" in firstScreenText, "First screen must start directly at Story 1 heading: $firstScreenText")
                assertEquals("小事纪闻", nodeText("immersive-title"), "Top HUD on first open must show Story 1 title, never '正文'")
                onAllNodesWithText("正文").assertCountEquals(0)

                // Opening body page must belong to study lessons and Companion chapter group without preface/copyright leakage
                val openingPage = controller.state.value.page
                assertEquals(BookPageRole.BODY, importedBook.structure?.pageRole(openingPage))
                assertTrue(importedBook.lessons.any { openingPage in it.firstPage..it.lastPage }, "Story 1 page must belong to a lesson")
                val leakedBlocks = importedBook.lessons.flatMap { it.blocks }.filter {
                    "START OF THE PROJECT GUTENBERG" in it.text || "序言第1段" in it.text
                }
                assertTrue(leakedBlocks.isEmpty(), "Leaked blocks into lessons: $leakedBlocks")
                assertNotNull(ChapterCompanionGrouping.groupForSection(importedBook, 0), "Story 1 lesson must belong to a valid chapter companion group")

                // 2. P2 observation + second review checks:
                //    (a) Before reaching the last screen of Story 1, footer must NOT prematurely claim "本章完"
                //    (b) Turning to Story 1's continuation screen keeps "小事纪闻" in BOTH Top HUD and in-sheet running header,
                //        and renders Ebookmaker `<h5>    ...</h5>` prose in normal body font (never magnified as chapter/section heading)
                val openerLayouts = mutableListOf<TextLayoutResult>()
                onNodeWithTag("reader-text").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(openerLayouts)
                val openerLayout = openerLayouts.single()
                val bodyFontSize = openerLayout.layoutInput.style.fontSize
                assertTrue(
                    openerLayout.layoutInput.text.spanStyles.any { it.item.fontSize.isSpecified && it.item.fontSize > bodyFontSize },
                    "Opener screen must style Story 1 title above body font size",
                )

                val statusBefore = nodeText("reader-page-number")
                assertTrue("屏" in statusBefore, "Multi-screen EPUB page should display screen progress in footer: $statusBefore")
                assertFalse("本章完" in statusBefore, "First screen of multi-screen story must NOT claim '本章完': $statusBefore")
                assertTrue("本章还剩" in statusBefore, "First screen of multi-screen story should show remaining screens/pages: $statusBefore")

                onNodeWithTag("next-page").performClick()
                waitForIdle()
                val statusAfter = nodeText("reader-page-number")
                assertNotEquals(statusBefore, statusAfter, "Footer status must visibly change after turning one screen within the same imported page")
                assertEquals("小事纪闻", nodeText("immersive-title"))
                // In-sheet running header at top of book-sheet-0 must show current TOC node "小事纪闻"
                onNode(hasAnyAncestor(hasTestTag("book-sheet-0")) and hasText("小事纪闻"), useUnmergedTree = true).assertExists()
                val contLayouts = mutableListOf<TextLayoutResult>()
                onNodeWithTag("reader-text").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(contLayouts)
                val contLayout = contLayouts.single()
                val renderedText = contLayout.layoutInput.text.text
                assertFalse("#" in renderedText, "Bogus h5 markdown prefix '#' must never appear in rendered text: $renderedText")
                val magnifiedSpans = contLayout.layoutInput.text.spanStyles.filter {
                    it.item.fontSize.isSpecified && it.item.fontSize > bodyFontSize
                }
                assertTrue(
                    magnifiedSpans.isEmpty(),
                    "Continuation screen prose must never have heading font spans above body size ($bodyFontSize): $magnifiedSpans",
                )
                onAllNodesWithText("正文").assertCountEquals(0)

                // Advance to the actual last screen of Story 1 ("小事纪闻") and confirm "本章完" appears ONLY on its final screen
                var story1FinalFooter = statusAfter
                repeat(8) {
                    if ("本章完" in story1FinalFooter) return@repeat
                    onNodeWithTag("next-page").performClick()
                    waitForIdle()
                    if (nodeText("immersive-title") == "小事纪闻") {
                        story1FinalFooter = nodeText("reader-page-number")
                    }
                }
                assertTrue("本章完" in story1FinalFooter, "Actual last screen of Story 1 should display '本章完', got: $story1FinalFooter")

                // 3. #2: Clicking TOC row 0 (《合成短篇集》自序) shows 自序 in Top HUD, and clicking License row shows License without "本章完"
                onNodeWithTag("v7-directory-toggle").performClick()
                waitForIdle()
                onNodeWithTag("toc-item-0").performClick()
                waitForIdle()
                assertEquals("《合成短篇集》自序", nodeText("immersive-title"))
                assertTrue("自序" in nodeText("reader-text"))

                onNodeWithTag("v7-directory-toggle").performClick()
                waitForIdle()
                onNodeWithTag("toc-item-3").performClick()
                waitForIdle()

                val licenseHudTitle = nodeText("immersive-title")
                assertTrue("LICENSE" in licenseHudTitle.uppercase(), "Top HUD on License TOC target must show License title, got: $licenseHudTitle")
                assertFalse(licenseHudTitle == "正文", "Top HUD on License page must never stay '正文'")
                val licenseFooter = nodeText("reader-page-number")
                assertFalse("本章完" in licenseFooter, "Back-matter License page must not claim '本章完': $licenseFooter")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun p4MalthusEpubOpensDirectlyAtChapter1WithoutPrefaceDateOrOrphanHashAndSyncsRunningHeader() {
        val root = Files.createTempDirectory("coach-ton1846-p4-")
        try {
            val epub = root.resolve("malthus-synthetic.epub")
            writeGutenbergPrefaceAndNewlineHeadingEpub(epub)
            val library = TextbookLibrary(root.resolve("lib"))
            val controller = TextbookController(library)

            runSkikoComposeUiTest(size = Size(820f, 620f), density = Density(1f)) {
                setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                waitUntil(timeoutMillis = 10_000) { controller.state.value.initialized }
                runBlocking { controller.importBook(epub) }
                waitReaderReady(controller)
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()

                // 1. #7: First open lands directly on CHAPTER 1 opener, NOT on "7 June 1798" + "#"
                val openingText = nodeText("reader-text")
                assertFalse("7 June 1798" in openingText, "First open must skip Preface date tail: $openingText")
                assertTrue("CHAPTER 1" in openingText, "First open must land directly on CHAPTER 1 heading: $openingText")
                assertEquals("CHAPTER 1", nodeText("immersive-title"))

                // 2. #2: Flipping back one screen to the end of Preface ("7 June 1798") shows "Preface" in both Top HUD and sheet header
                onNodeWithTag("previous-page").performClick()
                waitForIdle()
                val prevText = nodeText("reader-text")
                assertTrue("7 June 1798" in prevText, "Previous screen should be the end of Preface: $prevText")
                assertEquals("Preface", nodeText("immersive-title"), "Top HUD on Preface tail must say 'Preface', not 'CHAPTER 1'")
                onAllNodesWithText("The Project Gutenberg eBook of", substring = true).assertCountEquals(0)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun p1FrontMatterTocItemsShowDistinctSectionTitlesInTopHud() {
        val root = Files.createTempDirectory("coach-ton1846-p1-")
        try {
            val pdfFile = createOutlinedTextbookPdf(root.resolve("Health-Textbook.pdf"), totalPages = 10)
            val library = TextbookLibrary(root.resolve("lib"))
            val book = library.importBook(pdfFile)
            assertEquals(4, book.readingPage, "Textbook should initially open at Chapter 1 (page 4)")

            val controller = TextbookController(library)
            runBlocking {
                controller.load()
                controller.openBook(book.id)
            }

            runSkikoComposeUiTest(size = Size(820f, 620f), density = Density(1f)) {
                setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                waitReaderReady(controller)

                assertTrue("Chapter 1" in nodeText("immersive-title"))

                // Click TOC row 0 ("About the Authors", FRONT_MATTER on page 2)
                onNodeWithTag("v7-directory-toggle").performClick()
                waitForIdle()
                onNodeWithTag("toc-item-0").performClick()
                waitForIdle()
                assertEquals("About the Authors", nodeText("immersive-title"))
                assertFalse("本章" in nodeText("reader-page-number"))

                // Click TOC row 1 ("Preface", FRONT_MATTER on page 3)
                onNodeWithTag("v7-directory-toggle").performClick()
                waitForIdle()
                onNodeWithTag("toc-item-1").performClick()
                waitForIdle()
                assertEquals("Preface", nodeText("immersive-title"))
                assertFalse("本章" in nodeText("reader-page-number"))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun p5LargePdfPressingEndMovesViewportWithoutMarkingBookReadToLastPageOnShelf() {
        val root = Files.createTempDirectory("coach-ton1846-p5-")
        try {
            val totalPages = 120
            val pdfFile = createOutlinedTextbookPdf(root.resolve("Large-Classics.pdf"), totalPages = totalPages)
            val library = TextbookLibrary(root.resolve("lib"), streamPdfExtras = true, lazyPdfExtras = true)
            val controller = TextbookController(library)
            runBlocking { controller.load() }

            runSkikoComposeUiTest(size = Size(820f, 620f), density = Density(1f)) {
                setContent { Box(Modifier.fillMaxSize()) { WindowsTextbookHost(controller, chooseBook = { null }) } }
                controller.launchImport(pdfFile)
                waitReaderReady(controller)
                val imported = checkNotNull(controller.state.value.book)
                assertEquals(4, imported.readingPage, "Initial readingPage should be Chapter 1 (page 4)")
                assertEquals(4, controller.state.value.page)

                // Focus reader-page and press End (Key.MoveEnd) in default PDF scroll mode
                onNodeWithTag("reader-page").performSemanticsAction(SemanticsActions.RequestFocus)
                waitForIdle()
                onNodeWithTag("reader-page").performKeyInput { pressKey(Key.MoveEnd) }
                waitUntil(timeoutMillis = 10_000) {
                    nodeText("reader-page-number").contains("第 $totalPages / $totalPages 页")
                }

                // Viewport moved to 120/120, but committed readingPage stays at 4!
                assertEquals(totalPages, controller.state.value.page)
                assertEquals(4, controller.state.value.book?.readingPage)

                // Return to Shelf and verify both Continue Card and Book Card show page 4 (NOT page 120)
                onNodeWithTag("immersive-back").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.HOME }
                waitForIdle()

                val continueMeta = nodeText("home-focus-meta")
                assertTrue("第 4 页" in continueMeta, "Continue card must stay at page 4 after End peek, got: $continueMeta")
                assertFalse("第 $totalPages 页" in continueMeta, "Continue card must not jump to $totalPages: $continueMeta")
                onNodeWithText("$totalPages 页 · 读到第 4 页").assertExists()
                onAllNodesWithText("读到第 $totalPages 页", substring = true).assertCountEquals(0)
                assertEquals(4, TextbookLibrary(library.directory).book(imported.id).readingPage)

                // Reopen via "继续" -> opens back at page 4, then turn one sequential page to page 5 -> persists page 5!
                onNodeWithTag("home-read").performClick()
                waitReaderReady(controller)
                assertEquals(4, controller.state.value.page)
                onNodeWithTag("next-page").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.book?.readingPage == 5 }

                // Also test single huge jump via page-jump box to 120 without dwelling -> returns to shelf still at page 5
                onNodeWithTag("reader-page-number").performClick()
                waitForIdle()
                onNodeWithTag("page-jump").performTextReplacement(totalPages.toString())
                onNodeWithTag("page-jump-go").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.page == totalPages }
                assertEquals(5, controller.state.value.book?.readingPage)

                onNodeWithTag("immersive-back").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.HOME }
                waitForIdle()
                assertTrue("第 5 页" in nodeText("home-focus-meta"))
                onNodeWithText("$totalPages 页 · 读到第 5 页").assertExists()
                assertEquals(5, TextbookLibrary(library.directory).book(imported.id).readingPage)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
