package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.ui.theme.CoachTheme
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class ExplainClassifierTest {
    private val bookId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun block(text: String, page: Int = 1, start: Int = 0, heading: Boolean = false) =
        SourceBlock("$bookId:p$page:$start", page, start, start + text.length, heading, text)

    @Test fun classifiesTocEntriesHeadingsPageNumbersHeadersShortAndBodyBlocks() {
        // 目录条目：引导点加页码、短行末尾页码、多行目录块
        val tocSamples = listOf(
            "一、课程性质与任务 ……1",
            "一、课程性质与任务 …………………………………… 1",
            "（一）核心素养内涵 ………………………………… 5",
            "1.1 课程背景 ........ 12",
            "Chapter 1 Introduction . . . . . . 15",
            "二、课程理念 ……………… iv",
            "附录 1 核心素养学段要求 ········ 88",
            "一、课程性质与任务    1",
            "1.2 教学建议 24",
            "Chapter 2 Methods   18",
            "一、课程性质与任务 …… 1\n二、课程理念 …… 3\n三、课程目标 …… 5",
            "三、义务教育阶段核心素养发展目标与\n学段质量标准说明 …………………… 15",
        )
        for (sample in tocSamples) {
            assertEquals(ExplainBlockKind.TOC_ENTRY, ExplainClassifier.classifyBlock(block(sample)), "应识别为目录行：$sample")
        }

        // 标题：含已有 heading 标记及常见中英文短标题
        assertEquals(ExplainBlockKind.HEADING, ExplainClassifier.classifyBlock(block("# 第一章 课程性质", heading = true)))
        for (heading in listOf("目 录", "Contents", "一、课程性质与任务", "（一）核心定位", "前 言", "附录", "Chapter 3")) {
            assertEquals(ExplainBlockKind.HEADING, ExplainClassifier.classifyBlock(block(heading)), "应识别为标题：$heading")
        }

        // 单独的页码
        for (num in listOf("1", "42", "- 12 -", "— 3 —", "· 5 ·", "[12]", "第 5 页", "Page 12", "P. 8", "12 / 120", "iv", "XII", "- iii -", "<!-- PDF 第 5 页 · 书第 1 页 -->")) {
            assertEquals(ExplainBlockKind.PAGE_NUMBER, ExplainClassifier.classifyBlock(block(num)), "应识别为页码：$num")
        }

        // 页眉页脚与版权信息
        for (hf in listOf(
            "ISBN 978-7-107-36000-0",
            "图书在版编目（CIP）数据",
            "© 2022 人民教育出版社有限公司版权所有，翻印必究。",
            "Copyright © 2024 Example Press. All rights reserved. No part of this book may be reproduced.",
            "12   义务教育数学课程标准（2022年版）",
            "义务教育数学课程标准 · 12",
        )) {
            assertEquals(ExplainBlockKind.HEADER_FOOTER, ExplainClassifier.classifyBlock(block(hf)), "应识别为页眉页脚/版权：$hf")
        }

        // 去掉空白后不到 40 字的短行（含 39 字边界）
        assertEquals(ExplainBlockKind.SHORT, ExplainClassifier.classifyBlock(block("图 1-1 课程目标结构示意图")))
        assertEquals(ExplainBlockKind.SHORT, ExplainClassifier.classifyBlock(block("Alpha light.")))
        assertEquals(ExplainBlockKind.SHORT, ExplainClassifier.classifyBlock(block("测".repeat(39) + "   \n  ")))

        // 40 字边界与正常中文段落
        assertEquals(ExplainBlockKind.BODY, ExplainClassifier.classifyBlock(block("测".repeat(40))))
        val chineseBody = "本课程面向全体学生，立足真实生活与跨学科实践情境，引导学生在观察、思考、表达和探究中逐步形成适应终身发展和社会发展需要的核心素养与关键能力。"
        assertEquals(ExplainBlockKind.BODY, ExplainClassifier.classifyBlock(block(chineseBody)))

        // 以数字或中文序号开头、但本身是完整正文段落（>=40字且有句号），不能误判为标题
        val numberedBody = "1. 教师应当立足学生生活经验，创设真实而富有意义的学习情境，引导学生通过观察、思考、表达和合作探究形成持久的学科理解。"
        assertEquals(ExplainBlockKind.BODY, ExplainClassifier.classifyBlock(block(numberedBody)))

        // 中英混排段落
        val mixedBody = "在具体教学实施中，教师应结合 Formative Assessment（形成性评价）与项目化任务，及时发现学生在概念理解、方法迁移和论证表达中的难点，避免单一机械刷题。"
        assertEquals(ExplainBlockKind.BODY, ExplainClassifier.classifyBlock(block(mixedBody)))

        // 英文正常段落
        val englishBody = "Plants use light to make sugars through photosynthesis inside chloroplasts while releasing oxygen."
        assertEquals(ExplainBlockKind.BODY, ExplainClassifier.classifyBlock(block(englishBody)))

        // 正文里提及 ISBN 概念但不带真实书号，不应误判为版权页
        val isbnConcept = "国际标准书号（ISBN）是一种用于唯一标识图书出版物的编码体系，它由前缀号、组区号、出版者号、书名号和校验码五个部分组成。"
        assertEquals(ExplainBlockKind.BODY, ExplainClassifier.classifyBlock(block(isbnConcept)))
    }

    @Test fun analyzesTocCopyrightBlankHeadingsOnlyAndRepeatingHeaderPages() {
        val body1 = "本课程面向全体学生，立足真实生活与跨学科实践情境，引导学生在观察、思考、表达和探究中逐步形成适应终身发展和社会发展需要的核心素养与关键能力。"
        val body2 = "在具体教学实施中，教师应结合 Formative Assessment（形成性评价）与项目化任务，及时发现学生在概念理解、方法迁移和论证表达中的难点，避免单一机械刷题。"
        val all = listOf(
            // 第 1 页：目录页
            block("目 录", page = 1, start = 0),
            block("一、课程性质与任务 …………………… 1", page = 1, start = 10),
            block("二、课程理念 …………………………… 3", page = 1, start = 40),
            block("1", page = 1, start = 80),
            // 第 2 页：版权页
            block("图书在版编目（CIP）数据", page = 2, start = 0),
            block("ISBN 978-7-107-36000-0", page = 2, start = 30),
            block("© 2022 人民教育出版社有限公司版权所有，翻印必究。", page = 2, start = 60),
            // 第 3 页：只有页码的空白页
            block("- 3 -", page = 3, start = 0),
            // 第 4 页：只有标题的章首页
            block("第一章 绪论", page = 4, start = 0, heading = true),
            block("（一）核心定位", page = 4, start = 20),
            // 第 5 页 & 第 6 页：带跨页重复页眉的正文页
            block("义务教育课程标准示例", page = 5, start = 0),
            block(body1, page = 5, start = 20),
            block("义务教育课程标准示例", page = 6, start = 0),
            block(body2, page = 6, start = 20),
        )

        val toc = ExplainClassifier.analyzePage(all, 1)
        assertEquals(ExplainPageKind.TOC, toc.kind)
        assertTrue(toc.bodyBlocks.isEmpty())
        assertEquals("这一页是目录，翻到正文再讲。", toc.kind.notice)

        val copyright = ExplainClassifier.analyzePage(all, 2)
        assertEquals(ExplainPageKind.COPYRIGHT, copyright.kind)
        assertTrue(copyright.bodyBlocks.isEmpty())

        val blank = ExplainClassifier.analyzePage(all, 3)
        assertEquals(ExplainPageKind.BLANK, blank.kind)
        assertTrue(blank.bodyBlocks.isEmpty())

        val headingsOnly = ExplainClassifier.analyzePage(all, 4)
        assertEquals(ExplainPageKind.HEADINGS_ONLY, headingsOnly.kind)
        assertTrue(headingsOnly.bodyBlocks.isEmpty())

        val empty = ExplainClassifier.analyzePage(all, 99)
        assertEquals(ExplainPageKind.BLANK, empty.kind)

        val page5 = ExplainClassifier.analyzePage(all, 5)
        assertEquals(ExplainPageKind.BODY, page5.kind)
        assertEquals(ExplainBlockKind.HEADER_FOOTER, page5.blockKinds[all[10].id], "跨页重复页首短行应识别为页眉")
        assertEquals(listOf(all[11]), page5.bodyBlocks)
    }

    @Test fun pdfExplainPanelHidesTocButtonsShowsPageExplainAndNeverAutoRequests() {
        val root = Files.createTempDirectory("explain-smart-pdf-")
        val outDir = Path.of("build/screens/ton1766")
        Files.createDirectories(outDir)
        try {
            val pdf = writeSampleTocPdf(root.resolve("curriculum-sample.pdf"))
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(pdf)
            val consent = ExplainConsent(library.directory).apply { allow(book.id) }
            val explainer = RecordingExplainer(root.resolve("home"))

            // 1) 目录页：不显示「已连上 Mac」，不显示「讲解这一页」，每一行都没有「讲解这段」，不自动发请求。
            runSkikoComposeUiTest(size = Size(920f, 760f), density = Density(1f)) {
                setContent {
                    CoachTheme(darkTheme = false) {
                        Box(Modifier.fillMaxSize().background(CoachTheme.colors.surface1)) {
                            BookExplainPanel(book, 1, consent, explainer)
                        }
                    }
                }
                waitUntil(timeoutMillis = 20_000) { explainer.loaded }
                waitForIdle()
                assertNoMacWord()
                onNodeWithTag("explain-page-notice").assertTextEquals("这一页是目录，翻到正文再讲。")
                onNodeWithTag("explain-page-button").assertDoesNotExist()
                assertEquals(0, onAllNodesWithTag("explain-button").fetchSemanticsNodes().size, "目录页不能挂任何「讲解这段」")
                assertEquals(0, explainer.explainCalls.size)
                assertEquals(0, explainer.guideCalls.size)
                val tocImage = (this as SkikoComposeUiTest).captureToImage().toAwtImage()
                assertTrue(ImageIO.write(tocImage, "png", outDir.resolve("after-toc.png").toFile()))
            }

            // 2) 正文页：不显示「讲解这一页」，也不每行挂「讲解这段」；顶部只保留淡淡的「讲讲这一节」，点了才展开整节讲解。
            runSkikoComposeUiTest(size = Size(920f, 760f), density = Density(1f)) {
                setContent {
                    CoachTheme(darkTheme = false) {
                        Box(Modifier.fillMaxSize().background(CoachTheme.colors.surface1)) {
                            BookExplainPanel(book, 2, consent, explainer, library = library)
                        }
                    }
                }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("explain-section-trigger").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                assertNoMacWord()
                onNodeWithTag("explain-page-notice").assertDoesNotExist()
                onNodeWithTag("explain-page-button").assertDoesNotExist()
                assertEquals(0, onAllNodesWithTag("explain-button").fetchSemanticsNodes().size, "不再在每一行旁边挂「讲解这段」")
                onNodeWithTag("explain-section-trigger").assertTextEquals("讲讲这一节")
                assertEquals(0, explainer.explainCalls.size)
                assertEquals(0, explainer.guideCalls.size)
                val bodyImage = (this as SkikoComposeUiTest).captureToImage().toAwtImage()
                assertTrue(ImageIO.write(bodyImage, "png", outDir.resolve("after-body.png").toFile()))

                // 点击「讲讲这一节」走 explainSectionGuide，返回带段号出处的完整讲解。
                onNodeWithTag("explain-section-trigger").performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("section-guide-full").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(1, explainer.guideCalls.size)
                val citations = onAllNodesWithTag("explain-citation").fetchSemanticsNodes()
                assertEquals(2, citations.size)
                onAllNodesWithTag("explain-citation")[0].assertTextContains("第 2 页", substring = true)
            }

            // 3) 选中任意文字后「选段提问」照常可用（包括目录页的短行）。
            val support = PairedBookLearningSupport(library, consent) { sent, _ ->
                ExplainResult.Done(Explanation("这是一句目录条目说明。【S1】", listOf(Citation("S1", sent.id, sent.page)), 5))
            }
            runSkikoComposeUiTest(size = Size(1000f, 800f), density = Density(1f)) {
                setContent { CoachTheme(darkTheme = false) { BookPassageAsk(book, book.pages.first(), support) {} } }
                onNodeWithTag("ask-paragraph-0").performClick()
                onNodeWithTag("book-passage-question").performTextInput("这一行是什么？")
                onNodeWithTag("book-passage-send").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("book-passage-reply").fetchSemanticsNodes().isNotEmpty() }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun ComposeUiTest.assertNoMacWord() {
        val texts = onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }
        val leaks = texts.filter { "Mac" in it }
        assertTrue(leaks.isEmpty(), "连接正常或配对界面不应再出现 Mac 字样：$leaks")
    }

    private class RecordingExplainer(home: Path) :
        BookExplainer(DesktopConnectionSettings(home = home, environment = { null })) {
        val explainCalls = mutableListOf<SourceBlock>()
        val guideCalls = mutableListOf<ExplainSectionContext>()
        override suspend fun load() {
            identity = DesktopIdentity("http://service.test:18791", PairedCredentials("test-device", "test-token"))
            loaded = true
        }
        override suspend fun explain(chosen: SourceBlock, blocks: List<SourceBlock>, requestId: String): ExplainResult {
            explainCalls += chosen
            return ExplainResult.Done(Explanation("这一段讲解核心定位。【S1】", listOf(Citation("S1", chosen.id, chosen.page)), 8))
        }
        override suspend fun explainSectionGuide(context: ExplainSectionContext, bookPages: Int, requestId: String): SectionGuideResult {
            guideCalls += context
            val body = context.sectionBlocks.filterNot { it.heading }.filter { ExplainClassifier.nonWhitespaceLength(it.text) >= 40 }
                .ifEmpty { context.sectionBlocks.filterNot { it.heading } }
            val citations = body.take(2).mapIndexed { idx, b -> Citation("S${idx + 1}", b.id, b.page) }
            val text = "主旨是明确课程育人定位并落实形成性评价。【S1】难点在于将评价融入日常教学实践。【S${citations.size}】"
            return SectionGuideResult.Done(SectionGuide(listOf("明确课程育人定位", "落实形成性评价"), Explanation(text, citations, 12), "本节明确课程定位。"))
        }
    }

    private fun writeSampleTocPdf(target: Path): Path {
        PDDocument().use { pdf ->
            val font = PDType0Font.load(pdf, Path.of("../shared/ui/src/androidMain/res/font/noto_serif_sc.ttf").toFile())
            fun addPage(lines: List<Pair<Float, String>>) {
                val page = PDPage()
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    var y = 740f
                    for ((gap, text) in lines) {
                        y -= gap
                        out.beginText()
                        out.setFont(font, 11f)
                        out.newLineAtOffset(56f, y)
                        out.showText(text)
                        out.endText()
                    }
                }
            }
            addPage(listOf(
                0f to "目 录",
                36f to "一、课程性质与任务 …………………………………… 1",
                28f to "二、课程理念 …………………………………………… 3",
                28f to "三、课程目标 …………………………………………… 5",
                28f to "（一）核心素养内涵 ………………………………… 5",
                28f to "（二）学段要求 ……………………………………… 8",
                28f to "四、课程内容 …………………………………………… 12",
                28f to "五、教学实施与评价建议 ……………………………… 24",
                60f to "1",
            ))
            addPage(listOf(
                0f to "一、课程性质与任务",
                34f to "（一）核心定位",
                34f to "本课程面向全体学生，立足真实生活与跨学科实践情境，引导学生在观察、思考、",
                16f to "表达和探究中逐步形成适应终身发展和社会发展需要的核心素养与关键能力。",
                34f to "在具体教学实施中，教师应结合 Formative Assessment（形成性评价）与项目化任务，",
                16f to "及时发现学生在概念理解、方法迁移和论证表达中的难点，避免单一机械刷题。",
                34f to "图 1-1 课程目标结构示意图",
                60f to "- 2 -",
            ))
            pdf.save(target.toFile())
        }
        return target
    }
}
