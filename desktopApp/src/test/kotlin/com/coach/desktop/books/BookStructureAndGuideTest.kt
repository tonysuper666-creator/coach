package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.ui.theme.CoachTheme
import java.sql.DriverManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class BookStructureAndGuideTest {
    private val bookId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test fun recognizesExpandedHeadingsTocOffsetsMergeAndSplitBounds() {
        // 1) 标题规则覆盖第X章/节、一、（一）、1.、1.1、§、Chapter/Section
        assertEquals(1, BookStructurePlanner.matchHeadingRule("第一章 绪论")?.level)
        assertEquals(2, BookStructurePlanner.matchHeadingRule("第一节 研究对象")?.level)
        assertEquals(1, BookStructurePlanner.matchHeadingRule("一、课程性质与任务")?.level)
        assertEquals(2, BookStructurePlanner.matchHeadingRule("（一）核心定位")?.level)
        assertEquals(3, BookStructurePlanner.matchHeadingRule("1. 教学原则")?.level)
        assertEquals(2, BookStructurePlanner.matchHeadingRule("1.1 课程背景")?.level)
        assertEquals(2, BookStructurePlanner.matchHeadingRule("§1.2 能量守恒")?.level)
        assertEquals(1, BookStructurePlanner.matchHeadingRule("Chapter 2 Cells")?.level)
        // 完整长句不是标题
        assertNull(BookStructurePlanner.matchHeadingRule("1. 教师应当立足学生真实生活经验，创设跨学科实践情境以促进核心素养的持续发展。"))

        // 2) 同章下 < 400 字的相邻小节合并；跨章不合并；超过 4000 字按段落切开且不切在句中
        val shortPara = "光合作用把太阳光转化为化学能并储存于有机物中，同时释放氧气供生物呼吸使用。".repeat(5) // ~180字
        val longPara = "在生态系统的能量流动中，生产者通过光合作用固定太阳能，随后沿食物链逐级传递，每一营养级都会因呼吸作用散失大量热能，因此能量流动具有单向流动和逐级递减的基本规律。".repeat(4) // ~316字
        val pages = listOf(
            BookPage(1, "图书在版编目（CIP）数据\n\nISBN 978-7-107-36000-0\n\n© 2022 人民教育出版社有限公司版权所有，翻印必究。"),
            BookPage(2, "目 录\n\n一、课程性质与任务 …………………… 1\n\n（一）性质定位 ……………………… 1\n\n（二）基本任务 ……………………… 1\n\n二、生态能量流动 ……………………… 2"),
            BookPage(3, "一、课程性质与任务\n\n（一）性质定位\n\n$shortPara\n\n（二）基本任务\n\n$shortPara"),
            BookPage(4, "二、生态能量流动\n\n" + (1..16).joinToString("\n\n") { "第 $it 段：$longPara" }),
        )
        val structure = BookStructurePlanner.plan(bookId, "测试教材", "pdf", pages)
        assertEquals(BookPageRole.COPYRIGHT, structure.pageRole(1))
        assertEquals(BookPageRole.TOC, structure.pageRole(2))
        assertEquals(BookPageRole.BODY, structure.pageRole(3))
        assertEquals(BookPageRole.BODY, structure.pageRole(4))

        val lessons = structure.resolvedLessons
        // 第 3 页两个 180 字小节同属「一、课程性质与任务」，合并为 1 节；第 4 页 ~5200 字按段落边界切为 2 节（总计 3 节）
        assertEquals(3, lessons.size, "应合并同章过短小节并按段落切分超长章：${lessons.map { it.title to it.text.length }}")
        assertTrue(lessons[0].title.contains("性质定位") && lessons[0].title.contains("基本任务"), "同章相邻短小节应合并：${lessons[0].title}")
        assertTrue(lessons[0].breadcrumb.contains("一、课程性质与任务"), "应携带上级章节路径：${lessons[0].breadcrumb}")
        assertEquals(3, lessons[0].firstPage)
        assertEquals(4, lessons[1].firstPage)
        assertTrue(lessons.all { ExplainClassifier.nonWhitespaceLength(it.text) <= BookStructurePlanner.MAX_SECTION_CHARS },
            "任何节正文都不应超过 4000 字：${lessons.map { ExplainClassifier.nonWhitespaceLength(it.text) }}")
        assertEquals(0, countMidSentenceCuts(lessons), "新分节不应产生句中切口")
    }

    @Test fun fiveBookSectioningBenchmarkReportsMetricsAndZeroMidSentenceCuts() {
        val root = Files.createTempDirectory("ton1766-benchmark-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            data class BenchCase(val name: String, val path: Path, val expectedStarts: List<String>, val expectedExcludedPages: Set<Int>)

            val p1 = writeCurriculumStandardPdf(root.resolve("01-curriculum-standard.pdf"))
            val p2 = writeScannedOcrLikePdf(root.resolve("02-scanned-textbook.pdf"))
            val p3 = writeSampleEpub(root.resolve("03-sample-book.epub"))
            val p4 = writeSampleDocx(root.resolve("04-sample-book.docx"))
            val p5 = writePlainTextNoHeadings(root.resolve("05-plain-no-headings.txt"))

            val cases = listOf(
                BenchCase("1-课程标准PDF", p1, listOf("（一）性质定位", "（二）基本任务", "（一）核心素养内涵", "（二）学段目标要求", "（一）教学实施建议"), setOf(1, 2)),
                BenchCase("2-扫描教材PDF(模拟TON-1767)", p2, listOf("第一节 细胞的基本结构", "第二节 细胞膜与物质运输", "第一节 光合作用原理", "第二节 细胞呼吸与能量"), setOf(1, 2)),
                BenchCase("3-EPUB电子书", p3, listOf("第一节 供给与需求", "第二节 价格弹性", "第一节 国民收入核算", "第二节 通货膨胀与失业"), emptySet()),
                BenchCase("4-Word教材", p4, listOf("1.1 线性数据结构", "1.2 树与图模型", "2.1 排序算法比较", "2.2 动态规划思想"), emptySet()),
                BenchCase("5-无标题纯文字", p5, emptyList(), emptySet()),
            )

            val summaryLines = mutableListOf<String>()
            var totalExpected = 0
            var totalHits = 0
            var totalUnits = 0
            var totalInRange = 0
            var totalExcludedChecked = 0
            var totalExcludedOk = 0
            var totalLegacyMidCuts = 0
            var totalNewMidCuts = 0

            for (c in cases) {
                val book = library.importBook(c.path)
                val structure = assertNotNull(book.structure, "${c.name} 导入时应缓存 BookStructure")
                val newLessons = book.lessons
                val legacyLessons = legacyPlanBeforeFix(book.id, book.title, book.pages)

                val hits = c.expectedStarts.count { target ->
                    newLessons.any { it.title.contains(target) || it.blocks.any { b -> b.text.contains(target) } && it.title.isNotBlank() }
                }
                val exactBoundaryHits = c.expectedStarts.count { target ->
                    newLessons.any { it.title.contains(target) || target in it.breadcrumb }
                }
                val inRange = newLessons.count { ExplainClassifier.nonWhitespaceLength(it.text) in 800..4000 }
                val excludedOk = c.expectedExcludedPages.count { p -> structure.pageRole(p) != BookPageRole.BODY && newLessons.none { p in it.firstPage..it.lastPage } }
                val legacyCuts = countMidSentenceCuts(legacyLessons)
                val newCuts = countMidSentenceCuts(newLessons)

                totalExpected += c.expectedStarts.size
                totalHits += exactBoundaryHits
                totalUnits += newLessons.size
                totalInRange += inRange
                totalExcludedChecked += c.expectedExcludedPages.size
                totalExcludedOk += excludedOk
                totalLegacyMidCuts += legacyCuts
                totalNewMidCuts += newCuts

                val line = "BENCH ${c.name}: source=${structure.source} sections=${newLessons.size} " +
                    "boundaryHits=$exactBoundaryHits/${c.expectedStarts.size} (loose=$hits) " +
                    "inRange800_4000=$inRange/${newLessons.size} " +
                    "excluded=$excludedOk/${c.expectedExcludedPages.size} " +
                    "midSentenceCuts legacy=$legacyCuts new=$newCuts"
                summaryLines += line
                println(line)

                assertEquals(c.expectedExcludedPages.size, excludedOk, "${c.name} 目录/版权页必须全部排除：$line")
                assertEquals(0, newCuts, "${c.name} 新分节不应有句中切口：$line")
            }
            val hitRate = if (totalExpected == 0) 100 else totalHits * 100 / totalExpected
            val rangeRate = if (totalUnits == 0) 100 else totalInRange * 100 / totalUnits
            println("BENCH_TOTAL boundaryHitRate=$totalHits/$totalExpected (${hitRate}%) " +
                "inRange800_4000=$totalInRange/$totalUnits (${rangeRate}%) " +
                "excluded=$totalExcludedOk/$totalExcludedChecked " +
                "midSentenceCuts legacy=$totalLegacyMidCuts -> new=$totalNewMidCuts")
            assertTrue(hitRate >= 90, "边界命中率应 >= 90%，实测 $hitRate%")
            assertTrue(rangeRate >= 90, "800-4000 字区间占比应 >= 90%，实测 $rangeRate%")
            assertTrue(totalLegacyMidCuts > 0, "旧版 3000 字硬切样例应能复现句中切口")
            assertEquals(0, totalNewMidCuts, "新版分节句中切口必须为 0")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun readerAutoGuideCardSelectionToolbarFollowUpAndScreenshots() {
        val root = Files.createTempDirectory("ton1766-ui-")
        val outDir = Path.of("build/screens/ton1766")
        Files.createDirectories(outDir)
        try {
            val pdf = writeCurriculumStandardPdf(root.resolve("curriculum-guide-sample.pdf"))
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(pdf)
            val consent = ExplainConsent(library.directory).apply { allow(book.id) }
            val explainer = GuidedFakeExplainer(root.resolve("home"))

            // 确认设置项「自动导读」默认开，且可持久化开关
            val settings = ReadingSettings(library.directory)
            assertTrue(settings.autoGuide, "自动导读默认应开启")
            settings.chooseAutoGuide(false)
            assertFalse(ReadingSettings(library.directory).autoGuide)
            settings.chooseAutoGuide(true)
            assertTrue(ReadingSettings(library.directory).autoGuide)

            // 导入本身不发起任何讲解请求
            assertEquals(0, explainer.guideCalls.size)
            assertEquals(0, explainer.selectionCalls.size)

            // 1) 目录页截图：无任何讲解入口（无导读卡、无「讲讲这一节」、无「讲解这段」、无「讲解这一页」、无 Mac）
            runSkikoComposeUiTest(size = Size(980f, 760f), density = Density(1f)) {
                setContent {
                    CoachTheme(darkTheme = false) {
                        Surface(Modifier.fillMaxSize(), color = CoachTheme.colors.surface1) {
                            BookExplainPanel(book, 2, consent, explainer, library = library)
                        }
                    }
                }
                waitUntil(timeoutMillis = 20_000) { explainer.loaded }
                waitForIdle()
                onNodeWithTag("explain-page-notice").assertTextEquals("这一页是目录，翻到正文再讲。")
                onNodeWithTag("explain-section-trigger").assertDoesNotExist()
                onNodeWithTag("section-guide-card").assertDoesNotExist()
                assertEquals(0, onAllNodesWithTag("explain-button").fetchSemanticsNodes().size)
                assertEquals(0, explainer.guideCalls.size)
                savePng(outDir.resolve("ton1766-01-toc.png"))
            }

            // 2) 一节开头导读卡 + 后台预生成下一节 + 展开完整讲解带段号出处
            val allBlocks = StudyCoachCourse.blocks(book.id, book.pages)
            val firstSection = book.lessons.first()
            val secondSection = book.lessons[1]
            runSkikoComposeUiTest(size = Size(980f, 760f), density = Density(1f)) {
                var expanded by mutableStateOf(false)
                setContent {
                    val scope = rememberCoroutineScope()
                    val controller = remember(book.id) { SectionGuideController(book, library, consent, explainer, scope) }
                    LaunchedEffect(Unit) {
                        explainer.load()
                        controller.prefetchForPage(firstSection.firstPage, autoGuide = true)
                    }
                    CoachTheme(darkTheme = false) {
                        Column(Modifier.fillMaxSize().background(CoachTheme.colors.surface1).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                androidx.compose.material3.Text(firstSection.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                                FaintSectionExplainButton(expanded = expanded, enabled = true, onClick = { expanded = !expanded })
                            }
                            SectionGuideCard(
                                book = book,
                                blocks = allBlocks,
                                section = firstSection,
                                state = controller.states[firstSection.id] ?: SectionGuideState.Idle,
                                paired = true,
                                consented = true,
                                expanded = expanded,
                                onToggleExpand = { expanded = !expanded },
                                onAllowConsent = {},
                                onRetry = {},
                                onOpenPair = {},
                                onOpenCitation = { _, _ -> },
                            )
                            val firstBodies = firstSection.blocks.filter { !it.heading && it.isBodyParagraph }.take(2)
                            assertTrue(firstBodies.isNotEmpty(), "本节应有可展示的正文段落")
                            for (body in firstBodies) {
                                androidx.compose.material3.Text(ReadableText.reflow(body.text),
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                                    color = CoachTheme.colors.fgPrimary)
                            }
                        }
                    }
                }
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithTag("section-guide-brief").fetchSemanticsNodes().isNotEmpty() &&
                        explainer.guideCalls.size >= 2
                }
                waitForIdle()
                // 确认：只预生成了当前节和下一节（2 节，不是整本书的 5 节），且第 2 节请求带上了第 1 节缓存的 150 字前情提要！
                assertEquals(2, explainer.guideCalls.size, "只应预生成当前节和下一节")
                assertEquals(firstSection.breadcrumb, explainer.guideCalls[0].location)
                assertEquals(secondSection.title, explainer.guideCalls[0].nextTitle)
                assertEquals(secondSection.breadcrumb, explainer.guideCalls[1].location)
                assertNotNull(explainer.guideCalls[1].previousRecap, "下一节导读必须带上上一节的前情提要")
                assertNotNull(library.sectionGuide(book.id, firstSection.id), "导读与前情提要应落盘缓存")
                savePng(outDir.resolve("ton1766-02-section-guide.png"))

                // 点开看完整讲解与段号出处
                onNodeWithTag("section-guide-toggle").performClick()
                waitForIdle()
                onNodeWithTag("section-guide-full").assertExists()
                onAllNodesWithTag("explain-citation")[0].assertTextContains("第 ${firstSection.firstPage} 页", substring = true)
            }

            // 3) 真实阅读器文字版拖选文字自动浮出工具条 + 4) 点击「讲讲」就地回答并连续追问（共用整节上下文）
            library.saveTextView(book.id, true)
            // Standard Windows 150% scaling uses a full-height side drawer.
            runSkikoComposeUiTest(size = Size(1920f, 1080f), density = Density(1.5f)) {
                var activePage by mutableStateOf(secondSection.firstPage)
                var readerWidth by mutableStateOf(1280.dp)
                setContent {
                    val focus = remember { FocusRequester() }
                    CoachTheme(darkTheme = false) {
                        CompositionLocalProvider(LocalReadingAutoGuide provides true) {
                            Box(Modifier.width(readerWidth).fillMaxHeight()) {
                                TextbookReader(
                                    ui = LibraryUi(library.catalog(), book, screen = BookScreen.READER, page = activePage),
                                    library = library,
                                    support = UnconnectedBookLearningSupport,
                                    practiceSource = null,
                                    pageFocus = focus,
                                    onNoteSession = {},
                                    onPage = { activePage = it },
                                    onBack = {},
                                    onLearn = {},
                                    explainProvider = explainer,
                                )
                            }
                        }
                    }
                }
                // TON-1784 opens explanations in the drawer. The preceding component test checks the cached guide.
                val prefix = secondSection.blocks.first { it.isBodyParagraph && it.text.trim().length >= 12 }.text.trim().take(12)
                val body = hasTestTag("reader-text") and hasText(prefix, substring = true)
                waitUntil(timeoutMillis = 20_000) { onAllNodes(body).fetchSemanticsNodes().size == 1 }
                val text = onNode(body)
                val layouts = mutableListOf<TextLayoutResult>()
                text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                val start = layout.layoutInput.text.text.indexOf(prefix)
                val end = start + prefix.length - 1
                text.performMouseInput {
                    moveTo(layout.getBoundingBox(start).center)
                    press()
                    moveTo(layout.getBoundingBox(end).center)
                    release()
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("selection-explain-toolbar").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                onNodeWithTag("selection-action-explain").assertTextEquals("讲讲")
                onNodeWithTag("selection-action-example").assertTextEquals("举个例子")
                onNodeWithTag("selection-action-split").assertTextEquals("拆句")
                onNodeWithTag("selection-action-ask").assertTextEquals("问…")
                savePng(outDir.resolve("ton1766-03-selection-toolbar.png"))

                // 点击「讲讲」→ 就地出现回答（带整节上下文和前情提要）
                onNodeWithTag("selection-action-explain").performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("inline-explain-turn-0").fetchSemanticsNodes().isNotEmpty() }
                assertEquals("explain", explainer.selectionCalls.last().first)
                assertEquals(secondSection.breadcrumb, explainer.selectionCalls.last().second?.location)
                assertNotNull(explainer.selectionCalls.last().second?.previousRecap)

                // 继续追问：共用同一节上下文与上一轮问答历史
                onNodeWithTag("inline-followup-input").performTextInput("这和上一节的任务有什么联系？")
                onNodeWithTag("inline-followup-send").performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("inline-explain-turn-1").fetchSemanticsNodes().isNotEmpty() }
                assertEquals("ask", explainer.selectionCalls.last().first)
                assertEquals(1, explainer.selectionCalls.last().third.size, "追问必须携带上一轮问答历史")
                assertTrue(onNodeWithTag("inline-explain-turn-0").fetchSemanticsNode().boundsInRoot.height > 20f, "第一轮回答必须可见")
                assertTrue(onNodeWithTag("inline-explain-turn-1").fetchSemanticsNode().boundsInRoot.height > 20f, "追问回答必须可见")
                // The side drawer scrolls history independently of its composer. The narrow
                // presentation moves the same input below the page with room for a full line.
                onNodeWithTag("inline-followup-input").assertIsDisplayed()
                runOnIdle { readerWidth = 900.dp }
                waitForIdle()
                onNodeWithTag("inline-explain-turn-1").assertExists()
                val inputBounds = onNodeWithTag("inline-followup-input").fetchSemanticsNode().boundsInRoot
                assertTrue(inputBounds.width > 400f && inputBounds.height < 120f, "追问输入框应为横向单行输入：$inputBounds")
                waitForIdle()
                savePng(outDir.resolve("ton1766-04-followup.png"))

                // 收起就地回答卡后，卡片和选字浮条均收起
                onNodeWithTag("companion-close").performClick()
                waitForIdle()
                onNodeWithTag("immersive-companion-drawer").assertDoesNotExist()
                onNodeWithTag("selection-explain-toolbar").assertDoesNotExist()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun realReaderEpubTextSelectionAndLegacyBookPreservation() {
        val root = Files.createTempDirectory("ton1766-e2e-legacy-")
        try {
            // 1) 真实阅读器打开 EPUB：拖选文字自动触发浮条 + 就地讲解 + 追问；同时验证安卓长按文本定位辅助函数
            val library = TextbookLibrary(root.resolve("library"))
            val epub = library.importBook(writeSampleEpub(root.resolve("econ.epub")))
            val consent = ExplainConsent(library.directory).apply { allow(epub.id) }
            val explainer = GuidedFakeExplainer(root.resolve("home"))
            assertEquals("epub", epub.format)
            runSkikoComposeUiTest(size = Size(1080f, 820f), density = Density(1f)) {
                var activePage by mutableStateOf(epub.lessons.first().firstPage)
                setContent {
                    val focus = remember { FocusRequester() }
                    CoachTheme(darkTheme = false) {
                        CompositionLocalProvider(LocalReadingAutoGuide provides false) {
                            TextbookReader(
                                ui = LibraryUi(library.catalog(), epub, screen = BookScreen.READER, page = activePage),
                                library = library,
                                support = UnconnectedBookLearningSupport,
                                practiceSource = null,
                                pageFocus = focus,
                                onNoteSession = {},
                                onPage = { activePage = it },
                                onBack = {},
                                onLearn = {},
                                explainProvider = explainer,
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                onNodeWithTag("book-sheet-0").performMouseInput {
                    val b = onNodeWithTag("reader-text").fetchSemanticsNode().boundsInRoot
                    val sheet = onNodeWithTag("book-sheet-0").fetchSemanticsNode().boundsInRoot
                    val startX = (b.left - sheet.left) + 20f
                    val y = (b.top - sheet.top) + 80f
                    moveTo(Offset(startX, y))
                    press()
                    moveTo(Offset(startX + 240f, y))
                    release()
                }
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("selection-explain-toolbar").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("selection-action-split").performClick()
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("inline-explain-turn-0").fetchSemanticsNodes().isNotEmpty() }
                assertEquals("split", explainer.selectionCalls.last().first)
                assertEquals(epub.lessons.first().breadcrumb, explainer.selectionCalls.last().second?.location)
            }

            // 2) 旧书未点「重新整理章节」必须保持完全相同的旧课次 ID（p1:2400）与已存作答/复习键
            val txt = writePlainTextNoHeadings(root.resolve("legacy-long.txt"))
            val imported = library.importBook(txt)
            // 模拟改版前已入库的旧书：删掉 book_structures 记录
            DriverManager.getConnection("jdbc:sqlite:${library.directory.resolve("library.sqlite")}").use { conn ->
                conn.prepareStatement("DELETE FROM book_structures WHERE book=?").use { sql ->
                    sql.setString(1, imported.id)
                    sql.executeUpdate()
                }
            }
            val reopened = TextbookLibrary(library.directory)
            val legacy = reopened.book(imported.id)
            assertNull(legacy.structure, "旧书在用户未点「重新整理章节」前不应强行启用新分节")
            val expectedLegacyIds = legacyPlanBeforeFix(legacy.id, legacy.title, legacy.pages).map { it.id }
            assertEquals(expectedLegacyIds, legacy.lessons.map { it.id }, "旧书课次 ID 必须与改动前逐字一致")
            val secondLessonId = "${legacy.id}:p1:2400"
            assertTrue(secondLessonId in legacy.lessons.map { it.id }, "超过 2400 字的旧书第二节 ID 必须保持 p1:2400")

            val savedAnswer = PracticeAnswer(text = "技术变革", revealed = true, understood = true)
            // This is an existing review, with its original frozen options and solution.
            val oldSection = legacy.lessons.single { it.id == secondLessonId }
            val oldPractice = Practice("迁移验证", oldSection.text, "另一选项", listOf("技术变革", "另一选项"))
            reopened.withCourseDatabase { db ->
                TextbookReviewStore({ 0L }).recordLesson(db, legacy, oldSection, QuestionMode.CHOICE, oldPractice, savedAnswer)
            }
            reopened.saveAnswer(legacy.id, secondLessonId, QuestionMode.CHOICE, savedAnswer)
            val afterReload = TextbookLibrary(library.directory)
            assertEquals(savedAnswer, afterReload.answer(legacy.id, secondLessonId, QuestionMode.CHOICE), "旧书已存作答必须持续可查")
            assertTrue(afterReload.reviews().any { it.book == legacy.id && it.lesson == secondLessonId }, "旧书已存复习键不能失联")

            // 用户显式点「重新整理章节」后，才切换到无句中切口的新结构
            val reorganized = afterReload.reorganizeSections(legacy.id)
            assertNotNull(reorganized.structure)
            assertEquals(0, countMidSentenceCuts(reorganized.lessons), "重新整理章节后句中切口应为 0")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun ComposeUiTest.savePng(path: Path) {
        val img = (this as SkikoComposeUiTest).captureToImage().toAwtImage()
        assertTrue(ImageIO.write(img, "png", path.toFile()))
    }

    /** 模拟改动前旧版 `StudyCoachCourse.blocks()`（每 2400 字硬切）+ `plan()`（每 3000 字硬切），用于前后对比句中切口数。 */
    private fun legacyPlanBeforeFix(bookId: String, title: String, pages: List<BookPage>): List<BookLesson> {
        val oldHeading = Regex("^(?:#{1,6}\\s+|第[一二三四五六七八九十百0-9]+[章节篇]|(?:Chapter|Section)\\s+\\d|\\d+(?:\\.\\d+)+\\s+).+", RegexOption.IGNORE_CASE)
        val blocks = buildList {
            for (page in pages) {
                val paragraph = Regex("[^\\r\\n]+(?:\\r?\\n(?!\\s*\\r?\\n|#{1,6}\\s)[^\\r\\n]+)*")
                for (match in paragraph.findAll(page.text)) {
                    val lines = match.value.lines()
                    var offset = match.range.first
                    var pending = ""
                    var pendingStart = offset
                    fun flush() {
                        if (pending.isNotBlank()) {
                            var start = pendingStart
                            var left = pending
                            while (left.isNotEmpty()) {
                                val count = minOf(left.length, 2400)
                                add(SourceBlock("$bookId:p${page.number}:$start", page.number, start, start + count, false, left.take(count)))
                                start += count; left = left.drop(count)
                            }
                        }
                        pending = ""
                    }
                    for (line in lines) {
                        if (line.length <= 150 && oldHeading.matches(line.trim())) {
                            flush()
                            add(SourceBlock("$bookId:p${page.number}:$offset", page.number, offset, offset + line.length, true, line))
                        } else {
                            if (pending.isEmpty()) pendingStart = offset
                            pending += (if (pending.isEmpty()) "" else "\n") + line
                        }
                        offset += line.length + 1
                    }
                    flush()
                }
            }
        }
        val result = mutableListOf<BookLesson>()
        var group = mutableListOf<SourceBlock>()
        var name = title
        var part = 1
        fun flush() {
            if (group.any { !it.heading && it.text.isNotBlank() }) {
                result += BookLesson(group.first().id, if (part == 1) name else "$name · $part", group.toList())
                part++
            }
            group = mutableListOf()
        }
        for (b in blocks) {
            if (b.heading) { flush(); name = b.text.trim().trimStart('#').trim(); part = 1 }
            else if (group.sumOf { it.text.length } + b.text.length > 3000) flush()
            group += b
        }
        flush()
        return result
    }

    /** 统计切分出的相邻两节之间，前一节结尾不是句末标点（句中硬切）的次数。 */
    private fun countMidSentenceCuts(lessons: List<BookLesson>): Int {
        val ends = setOf('。', '！', '？', '.', '!', '?', '”', '’', '」', '』', ')', '）', '：', ':')
        var cuts = 0
        for (i in 0 until lessons.lastIndex) {
            val tail = lessons[i].text.trimEnd()
            if (tail.isNotEmpty() && tail.last() !in ends) cuts++
        }
        return cuts
    }

    private fun prose(topic: String, chars: Int): String {
        val base = "围绕${topic}，教材从基本概念出发，结合真实情境分析核心规律与常见误区，帮助学习者建立清晰的知识框架并能够迁移到新问题之中。"
        val sb = StringBuilder()
        var k = 1
        while (ExplainClassifier.nonWhitespaceLength(sb.toString()) < chars) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("要点$k：$base$base$base")
            k++
        }
        return sb.toString()
    }

    private fun writeCurriculumStandardPdf(target: Path): Path {
        PDDocument().use { pdf ->
            val font = PDType0Font.load(pdf, Path.of("../shared/ui/src/androidMain/res/font/noto_serif_sc.ttf").toFile())
            val pdPages = mutableListOf<PDPage>()
            fun addTextPage(blocks: List<String>) {
                val page = PDPage()
                pdf.addPage(page)
                pdPages += page
                PDPageContentStream(pdf, page).use { out ->
                    var y = 745f
                    for (block in blocks) {
                        for (para in block.split("\n\n")) {
                            for (line in para.replace('\n', ' ').chunked(38)) {
                                if (y < 60f) break
                                out.beginText()
                                out.setFont(font, 10.5f)
                                out.newLineAtOffset(52f, y)
                                out.showText(line)
                                out.endText()
                                y -= 15f
                            }
                            y -= 10f
                        }
                        y -= 4f
                    }
                }
            }
            // Page 1: 版权页
            addTextPage(listOf("图书在版编目（CIP）数据", "义务教育科学课程标准示例", "ISBN 978-7-107-36000-0", "© 2022 人民教育出版社有限公司版权所有，翻印必究。"))
            // Page 2: 目录页
            addTextPage(listOf(
                "目 录",
                "一、课程性质与任务 …………………………………… 1",
                "（一）性质定位 ……………………………………… 1",
                "（二）基本任务 ……………………………………… 2",
                "二、课程目标与核心素养 ……………………………… 3",
                "（一）核心素养内涵 ………………………………… 3",
                "（二）学段目标要求 ………………………………… 4",
                "三、教学实施与评价 …………………………………… 5",
                "（一）教学实施建议 ………………………………… 5",
                "1",
            ))
            // Page 3..7: 正文各节（每节 ~1000-1300 字）
            addTextPage(listOf("一、课程性质与任务", "（一）性质定位", prose("课程性质与育人定位", 1050), "- 1 -"))
            addTextPage(listOf("（二）基本任务", prose("课程基本任务与实践路径", 1050), "- 2 -"))
            addTextPage(listOf("二、课程目标与核心素养", "（一）核心素养内涵", prose("科学观念与探究思维", 1050), "- 3 -"))
            addTextPage(listOf("（二）学段目标要求", prose("分学段素养进阶要求", 1050), "- 4 -"))
            addTextPage(listOf("三、教学实施与评价", "（一）教学实施建议", prose("情境教学与形成性评价", 1050), "- 5 -"))

            // 添加 PDF 书签（PDDocumentOutline）
            val outline = PDDocumentOutline()
            pdf.documentCatalog.documentOutline = outline
            fun item(title: String, pageIdx: Int): PDOutlineItem = PDOutlineItem().apply {
                this.title = title
                destination = PDPageFitDestination().apply { page = pdPages[pageIdx] }
            }
            val ch1 = item("一、课程性质与任务", 2).apply {
                addLast(item("（一）性质定位", 2))
                addLast(item("（二）基本任务", 3))
            }
            val ch2 = item("二、课程目标与核心素养", 4).apply {
                addLast(item("（一）核心素养内涵", 4))
                addLast(item("（二）学段目标要求", 5))
            }
            val ch3 = item("三、教学实施与评价", 6).apply {
                addLast(item("（一）教学实施建议", 6))
            }
            outline.addLast(ch1); outline.addLast(ch2); outline.addLast(ch3)
            pdf.save(target.toFile())
        }
        return target
    }

    /** 模拟 TON-1767 类型的扫描教材 PDF（无 PDF 书签，仅靠目录页 + 扩充标题规则识别结构）。 */
    private fun writeScannedOcrLikePdf(target: Path): Path {
        PDDocument().use { pdf ->
            val font = PDType0Font.load(pdf, Path.of("../shared/ui/src/androidMain/res/font/noto_serif_sc.ttf").toFile())
            fun addTextPage(blocks: List<String>) {
                val page = PDPage(); pdf.addPage(page)
                PDPageContentStream(pdf, page).use { out ->
                    var y = 745f
                    for (block in blocks) {
                        for (para in block.split("\n\n")) {
                            for (line in para.replace('\n', ' ').chunked(38)) {
                                if (y < 60f) break
                                out.beginText(); out.setFont(font, 10.5f); out.newLineAtOffset(52f, y); out.showText(line); out.endText()
                                y -= 15f
                            }
                            y -= 10f
                        }
                        y -= 4f
                    }
                }
            }
            addTextPage(listOf("出版发行：科学教育出版社", "ISBN 978-7-03-065000-1", "版权所有 翻印必究"))
            addTextPage(listOf(
                "目 录",
                "第一章 细胞与生命活动 …………………… 1",
                "第一节 细胞的基本结构 …………………… 1",
                "第二节 细胞膜与物质运输 ………………… 2",
                "第二章 能量代谢 …………………………… 3",
                "第一节 光合作用原理 ……………………… 3",
                "第二节 细胞呼吸与能量 …………………… 4",
            ))
            addTextPage(listOf("第一章 细胞与生命活动", "第一节 细胞的基本结构", prose("细胞器结构与功能分工", 1050), "1"))
            addTextPage(listOf("第二节 细胞膜与物质运输", prose("跨膜运输与选择透过性", 1050), "2"))
            addTextPage(listOf("第二章 能量代谢", "第一节 光合作用原理", prose("光反应与暗反应偶联机制", 1050), "3"))
            addTextPage(listOf("第二节 细胞呼吸与能量", prose("有氧呼吸与ATP合成路径", 1050), "4"))
            pdf.save(target.toFile())
        }
        return target
    }

    private fun writeSampleEpub(target: Path): Path {
        fun xhtml(title: String, body: String) = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body>
<h1>$title</h1>
${body.split("\n\n").joinToString("\n") { "<p>$it</p>" }}
</body></html>"""
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>经济学基础</dc:title></metadata>
<manifest>
  <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
  <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
  <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
  <item id="c3" href="c3.xhtml" media-type="application/xhtml+xml"/>
  <item id="c4" href="c4.xhtml" media-type="application/xhtml+xml"/>
</manifest>
<spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/><itemref idref="c4"/></spine>
</package>""")
            put("OEBPS/nav.xhtml", """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>
  <li><span>第一章 微观基础</span><ol>
    <li><a href="c1.xhtml">第一节 供给与需求</a></li>
    <li><a href="c2.xhtml">第二节 价格弹性</a></li>
  </ol></li>
  <li><span>第二章 宏观视角</span><ol>
    <li><a href="c3.xhtml">第一节 国民收入核算</a></li>
    <li><a href="c4.xhtml">第二节 通货膨胀与失业</a></li>
  </ol></li>
</ol></nav></body></html>""")
            put("OEBPS/c1.xhtml", xhtml("第一节 供给与需求", prose("市场供求均衡与价格机制", 1100)))
            put("OEBPS/c2.xhtml", xhtml("第二节 价格弹性", prose("需求价格弹性与总收益变化", 1100)))
            put("OEBPS/c3.xhtml", xhtml("第一节 国民收入核算", prose("国内生产总值的支出法核算", 1100)))
            put("OEBPS/c4.xhtml", xhtml("第二节 通货膨胀与失业", prose("菲利普斯曲线与宏观调控", 1100)))
        }
        return target
    }

    private fun writeSampleDocx(target: Path): Path {
        val sections = listOf(
            Triple("Heading1", "第一章 数据结构基础", ""),
            Triple("Heading2", "1.1 线性数据结构", prose("数组链表栈与队列的适用场景", 1100)),
            Triple("Heading2", "1.2 树与图模型", prose("二叉树遍历与图搜索算法", 1100)),
            Triple("Heading1", "第二章 核心算法设计", ""),
            Triple("Heading2", "2.1 排序算法比较", prose("归并排序与快速排序的时间复杂度", 1100)),
            Triple("Heading2", "2.2 动态规划思想", prose("最优子结构与状态转移方程构建", 1100)),
        )
        val bodyXml = buildString {
            for ((style, heading, text) in sections) {
                append("""<w:p><w:pPr><w:pStyle w:val="$style"/></w:pPr><w:r><w:t>$heading</w:t></w:r></w:p>""")
                if (text.isNotEmpty()) {
                    for (para in text.split("\n\n")) {
                        append("""<w:p><w:r><w:t>$para</w:t></w:r></w:p>""")
                    }
                }
            }
        }
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            put("[Content_Types].xml", """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/></Types>""")
            put("word/styles.xml", """<?xml version="1.0"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:pPr><w:outlineLvl w:val="0"/></w:pPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:pPr><w:outlineLvl w:val="1"/></w:pPr></w:style></w:styles>""")
            put("word/document.xml", """<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$bodyXml</w:body></w:document>""")
        }
        return target
    }

    /** 没有任何标题的长纯文本（含超过 2400 字的超长段，专门验证旧版硬切句中切口 vs 新版 0 句中切口）。 */
    private fun writePlainTextNoHeadings(target: Path): Path {
        val sentence = "历史研究表明，技术变革不仅改变生产效率，还会深刻重塑社会组织形态与知识传播方式。"
        val giantPara = (1..75).joinToString("") { "第${it}句：$sentence" } // ~3100字单段，旧版会在第 2400 字句中硬切
        val normalParas = (1..8).joinToString("\n\n") { i ->
            "关于历史制度演进的第${i}段讨论：" + sentence.repeat(12)
        }
        Files.writeString(target, "$giantPara\n\n$normalParas")
        return target
    }

    private class GuidedFakeExplainer(home: Path) :
        BookExplainer(DesktopConnectionSettings(home = home, environment = { null })) {
        val guideCalls = mutableListOf<ExplainSectionContext>()
        val selectionCalls = mutableListOf<Triple<String, ExplainSectionContext?, List<FollowUpTurn>>>()

        override suspend fun load() {
            identity = DesktopIdentity("http://service.test:18791", PairedCredentials("test-device", "test-token"))
            loaded = true
        }

        override suspend fun explainSectionGuide(context: ExplainSectionContext, bookPages: Int, requestId: String): SectionGuideResult {
            guideCalls += context
            val body = context.sectionBlocks.filterNot { it.heading }
            val cited = body.take(2).mapIndexed { idx, b -> Citation("S${idx + 1}", b.id, b.page) }
            val bridge = if (context.previousRecap != null) "承接上一节定位，本节展开具体要求与落地路径。" else "本节作为开篇，界定课程的整体性质与核心育人定位。"
            val brief = listOf(
                bridge,
                "核心要点一：立足真实生活与跨学科实践情境。",
                "核心要点二：注重过程性理解与能力迁移，而非机械记忆。",
            )
            val fullText = "$bridge【S1】在学习这一节时，可以重点把握概念之间的因果衔接与实践要求。【S${cited.size}】"
            return SectionGuideResult.Done(SectionGuide(brief, Explanation(fullText, cited, 15L), "本节界定了${context.location}的核心定位与实践要点。"))
        }

        override suspend fun explainSelection(
            kind: String,
            chosen: SourceBlock,
            context: ExplainSectionContext?,
            question: String?,
            history: List<FollowUpTurn>,
            requestId: String,
        ): ExplainResult {
            selectionCalls += Triple(kind, context, history)
            val anchorBlock = context?.sectionBlocks?.firstOrNull { !it.heading } ?: chosen
            val reply = when (kind) {
                "ask" -> "承接上一节的课程定位，这里进一步把抽象目标拆成课堂可落地的实践任务，两者是总目标与实施路径的衔接关系。【S1】"
                "example" -> "假设在课堂里围绕垃圾分类设计跨学科调查任务，学生需要综合运用数据统计与科学推理来得出结论。【S1】"
                "rephrase" -> "换句话说，这段不是要求背诵条文，而是希望在真实问题里把知识真正用起来。【S1】"
                else -> "这段在整节中的作用是点明实践情境的核心价值：承接前文定位，把素养培养落到具体探究活动上。【S1】"
            }
            return ExplainResult.Done(Explanation(reply, listOf(Citation("S1", anchorBlock.id, anchorBlock.page)), 10L))
        }
    }
}
