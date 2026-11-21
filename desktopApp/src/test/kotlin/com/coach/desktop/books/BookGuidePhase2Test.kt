package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.client.PairedCredentials
import com.coach.desktop.DesktopConnectionSettings
import com.coach.desktop.DesktopIdentity
import com.coach.desktop.WindowsTextbookHost
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.DeskTypeControl
import com.coach.ui.v7.LocalDeskTypeControl
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
class BookGuidePhase2Test {
    @Test fun unstructuredBookSectioningBenchmarkCachingAndMonotonicFallback() = runBlocking {
        val root = Files.createTempDirectory("ton1768-unstructured-")
        try {
            val txtPath = writeUnstructuredLongText(root.resolve("unstructured-bio.txt"))
            val pdfPath = writeUnstructuredPdf(root.resolve("unstructured-history.pdf"))
            val structuredPath = writeStructuredMarkdown(root.resolve("structured-guide.md"))

            val library = TextbookLibrary(root.resolve("library"))
            val callsByBook = mutableMapOf<String, Int>()
            val sentByBook = mutableMapOf<String, List<SourceBlock>>()
            val topicMarkers = listOf(
                listOf("细胞膜主要由脂质双分子层构成", "叶绿体通过光反应捕获光能", "有氧呼吸在细胞质基质和线粒体", "遗传信息的转录以DNA一条链为模板"),
                listOf("造纸术的改进使书写载体摆脱了竹简", "活字印刷技术把文字排版从整版雕刻", "指南针在远洋航海中的应用大幅提升了", "火药配方在宋元时期的系统记载推动了"),
            )
            val topicTitles = listOf(
                listOf("细胞膜与跨膜物质运输", "光合作用光反应机制", "有氧呼吸与能量释放", "转录与蛋白质合成"),
                listOf("造纸术的工艺演进", "活字印刷与知识传播", "指南针与远洋航海", "火药配方的演变影响"),
            )

            val provider = BookStructureProvider { book, candidates, _ ->
                callsByBook[book.id] = (callsByBook[book.id] ?: 0) + 1
                sentByBook[book.id] = candidates
                val idx = if (book.format == "txt") 0 else 1
                val boundaries = topicMarkers[idx].zip(topicTitles[idx]).map { (marker, title) ->
                    val candidate = candidates.firstOrNull { marker in it.text }
                        ?: error("Candidate snippet missing topic start: $marker in ${candidates.map { it.text }}")
                    ModelSectionBoundary(candidate.id, title, candidate.page)
                }
                ModelSectionResult.Done(boundaries)
            }

            val controller = TextbookController(library, provider)

            // 1) 有标题结构的书不触发无结构模型分节
            controller.importBook(structuredPath)
            val structuredBook = controller.state.value.book!!
            assertEquals(0, callsByBook[structuredBook.id] ?: 0, "有结构书不应调 Haiku 分节")

            // 2) 两本无结构书：基线对比 + 单次 Haiku 分节 + 只送每页前几行/候选标题 + 按书缓存
            val beforeAfterLines = mutableListOf<String>()
            var legacyTopicHits = 0
            var newTopicHits = 0
            var totalTopics = 0
            var newInRange = 0
            var newUnits = 0
            var newMidCuts = 0

            for ((idx, path) in listOf(txtPath, pdfPath).withIndex()) {
                // 先记录未用模型前的基线（FALLBACK_LENGTH）
                val rawBook = TextbookLibrary(root.resolve("baseline-$idx")).importBook(path)
                val baselineLessons = rawBook.lessons
                val baselineHits = topicMarkers[idx].count { m ->
                    baselineLessons.any { it.blocks.firstOrNull { b -> !b.heading }?.text?.contains(m) == true }
                }
                legacyTopicHits += baselineHits

                // 导入并触发单次 Haiku 分节
                controller.importBook(path)
                val sectioned = controller.state.value.book!!
                val structure = assertNotNull(sectioned.structure)
                assertEquals(HeadingSource.MODEL_HAIKU.name, structure.source)
                assertTrue(structure.modelAttempted)
                assertEquals(1, callsByBook[sectioned.id], "无结构书导入时只调一次 Haiku")

                // 校验只送每页前几行/候选标题（远小于全文，且每块与原页精确前缀匹配，满足 ImportedBookWire）
                val sent = assertNotNull(sentByBook[sectioned.id])
                val fullChars = sectioned.pages.sumOf { it.text.length }
                val sentChars = sent.sumOf { it.text.length }
                assertTrue(sentChars < fullChars * 0.6, "只应发送每页前几行与候选标题，实发 $sentChars / 全书 $fullChars")
                assertTrue(sent.all { b ->
                    val pageText = sectioned.page(b.page)?.text.orEmpty()
                    b.text.length <= 140 && b.start >= 0 && b.end <= pageText.length &&
                        pageText.substring(b.start, b.end) == b.text
                }, "候选块必须保持原块 id 与 exact UTF-16 前缀偏移")

                // 验证按书缓存：再次调用 & 重开库后不再调用模型
                controller.ensureUnstructuredBookSectioned(sectioned.id, provider)
                assertEquals(1, callsByBook[sectioned.id], "重复调用不得重新触发模型")
                val reopenedLib = TextbookLibrary(root.resolve("library"))
                val reopenedController = TextbookController(reopenedLib, provider)
                reopenedController.ensureUnstructuredBookSectioned(sectioned.id, provider)
                assertEquals(1, callsByBook[sectioned.id], "重开库后必须命中缓存，只调一次")
                val cachedBook = reopenedLib.book(sectioned.id)
                assertEquals(sectioned.lessons.map { it.id }, cachedBook.lessons.map { it.id })

                val newLessons = cachedBook.lessons
                val hits = topicMarkers[idx].count { m ->
                    newLessons.any { it.blocks.firstOrNull { b -> !b.heading }?.text?.contains(m) == true }
                }
                val inRange = newLessons.count { ExplainClassifier.nonWhitespaceLength(it.text) in 800..4000 }
                val midCuts = countMidSentenceCuts(newLessons)
                totalTopics += topicMarkers[idx].size
                newTopicHits += hits
                newInRange += inRange
                newUnits += newLessons.size
                newMidCuts += midCuts

                val line = "BENCH_UNSTRUCTURED ${cachedBook.format.uppercase()}: sent=$sentChars/$fullChars " +
                    "baselineHits=$baselineHits/${topicMarkers[idx].size} " +
                    "haikuHits=$hits/${topicMarkers[idx].size} inRange800_4000=$inRange/${newLessons.size} midCuts=$midCuts"
                beforeAfterLines += line
                println(line)
                assertEquals(topicMarkers[idx].size, hits, "Haiku 分节应命中全部主题边界：$line")
                assertEquals(0, midCuts, "不得产生句中切口：$line")
            }

            println("BENCH_UNSTRUCTURED_TOTAL beforeHits=$legacyTopicHits/$totalTopics afterHits=$newTopicHits/$totalTopics " +
                "inRange800_4000=$newInRange/$newUnits midCuts=$newMidCuts")
            assertEquals(totalTopics, newTopicHits)
            assertEquals(newUnits, newInRange)

            // 3) 校验不通过（不存在块 id / 非单调递增 / 重复块 id / 模型失败）必须退回段落边界切分且缓存不重调
            for ((idx, kind) in listOf("unknown", "non-monotonic", "duplicate", "failed").withIndex()) {
                val fbLib = TextbookLibrary(root.resolve("fb-$idx"))
                var fbCalls = 0
                val fbProvider = BookStructureProvider { _, candidates, _ ->
                    fbCalls++
                    when (kind) {
                        "unknown" -> ModelSectionResult.Done(listOf(ModelSectionBoundary("deadbeef:p1:0", "不存在")))
                        "non-monotonic" -> ModelSectionResult.Done(listOf(
                            ModelSectionBoundary(candidates[3].id, "倒序一"),
                            ModelSectionBoundary(candidates[1].id, "倒序二"),
                        ))
                        "duplicate" -> ModelSectionResult.Done(listOf(
                            ModelSectionBoundary(candidates[1].id, "重复一"),
                            ModelSectionBoundary(candidates[1].id, "重复二"),
                        ))
                        else -> ModelSectionResult.Failed("unavailable")
                    }
                }
                val fbController = TextbookController(fbLib, fbProvider)
                fbController.importBook(txtPath)
                val fbBook = fbController.state.value.book!!
                val fbStructure = assertNotNull(fbBook.structure)
                assertEquals(HeadingSource.FALLBACK_LENGTH.name, fbStructure.source, "$kind 校验不通过应退回按长度切")
                assertTrue(fbStructure.modelAttempted, "$kind 失败后应标记已尝试防止反复请求")
                assertEquals(0, countMidSentenceCuts(fbBook.lessons), "$kind 退回切分必须在段落边界，0 句中切口")
                assertTrue(fbBook.lessons.size >= 2, "长书退回按长度切应拆成多节")
                fbController.ensureUnstructuredBookSectioned(fbBook.id, fbProvider)
                assertEquals(1, fbCalls, "$kind 缓存后不应重复调用模型")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun hardPointMarginMarkersAndZeroExtraCallExplanationWithScreenshots() {
        val root = Files.createTempDirectory("ton1768-hardpoint-")
        val outDir = Path.of("build/screens/ton1768")
        Files.createDirectories(outDir)
        try {
            val pdf = writeStructuredPdf(root.resolve("curriculum-hardpoint.pdf"))
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(pdf)
            library.saveTextView(book.id, true)
            ExplainConsent(library.directory).allow(book.id)
            val explainer = Phase2FakeExplainer(root.resolve("home"))

            val firstSection = book.lessons.first()
            val bodyBlocks = firstSection.blocks.filter { !it.heading && it.isBodyParagraph }
            assertTrue(bodyBlocks.size >= 2, "测试节需要至少 2 个正文段落用于难点锚定")

            runSkikoComposeUiTest(size = Size(1080f, 820f), density = Density(1f)) {
                var activePage by mutableStateOf(firstSection.firstPage)
                setContent {
                    val focus = remember { FocusRequester() }
                    CoachTheme(darkTheme = false) {
                        CompositionLocalProvider(
                            LocalReadingAutoGuide provides true,
                            LocalReadingSectionSummary provides false,
                        ) {
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
                val markerTag0 = "hard-point-marker-${bodyBlocks[0].id}"
                val markerTag1 = "hard-point-marker-${bodyBlocks[1].id}"
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithTag(markerTag0).fetchSemanticsNodes().isNotEmpty() &&
                        onAllNodesWithTag(markerTag1).fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()

                // 验证页边标记不压住正文区域
                val textBounds = onNodeWithTag("reader-text").fetchSemanticsNode().boundsInRoot
                val markerBounds = onNodeWithTag(markerTag0).fetchSemanticsNode().boundsInRoot
                assertTrue(
                    markerBounds.left >= textBounds.right - 2f || markerBounds.right <= textBounds.left + 2f,
                    "难点小标应落在页边空白处，不能压正文：text=$textBounds marker=$markerBounds",
                )
                savePng(outDir.resolve("ton1768-01-hard-point-margin.png"))

                val guideCallsBeforeClick = explainer.guideCalls.size
                val selectionCallsBeforeClick = explainer.selectionCalls.size

                // 点击页边难点标记 -> 原地展开整节语境讲解，且 0 额外模型调用
                onNodeWithTag(markerTag0).performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("hard-point-card").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                onNodeWithTag("hard-point-title").assertTextContains("核心概念", substring = true)
                onNodeWithTag("hard-point-explanation").assertTextContains("整节", substring = true)
                assertEquals(guideCallsBeforeClick, explainer.guideCalls.size, "点击难点不得再调导读模型")
                assertEquals(selectionCallsBeforeClick, explainer.selectionCalls.size, "点击难点不得额外调讲解模型")
                savePng(outDir.resolve("ton1768-02-hard-point-opened.png"))

                onNodeWithTag("hard-point-close").performClick()
                waitForIdle()
                onNodeWithTag("hard-point-card").assertDoesNotExist()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun legacySectionGuideCacheInvalidatesAndUpgradesInReaderAndExplainPanel() {
        val root = Files.createTempDirectory("ton1768-legacy-guide-")
        try {
            val pdf = writeStructuredPdf(root.resolve("legacy-cache-upgrade.pdf"))
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(pdf)
            library.saveTextView(book.id, true)
            ExplainConsent(library.directory).allow(book.id)

            val firstSection = book.lessons.first()
            val bodyBlocks = firstSection.blocks.filter { !it.heading && it.isBodyParagraph }
            assertTrue(bodyBlocks.size >= 2)
            val markerTag0 = "hard-point-marker-${bodyBlocks[0].id}"
            val panelMarkerTag0 = "explain-hard-point-marker-${bodyBlocks[0].id}"
            val dbPath = library.directory.resolve("library.sqlite")

            fun writeLegacyV1GuideRow() {
                // 模拟 1.1.1 写入的旧版导读缓存 JSON：只有 brief/text/recap/citations，无 version/hardPoints/summary。
                val v1Json = """
                    {"brief":["旧版要点"],"text":"旧版导读【S1】","recap":"旧版前情提要","citations":[{"anchor":"S1","blockId":"${bodyBlocks[0].id}","page":${bodyBlocks[0].page}}]}
                """.trimIndent()
                DriverManager.getConnection("jdbc:sqlite:$dbPath").use { db ->
                    db.prepareStatement(
                        "INSERT OR REPLACE INTO book_section_cache(book, section_id, kind, payload, updated_at) VALUES(?,?,?,?,?)",
                    ).use { sql ->
                        sql.setString(1, book.id)
                        sql.setString(2, firstSection.id)
                        sql.setString(3, "guide")
                        sql.setString(4, v1Json)
                        sql.setLong(5, 1_700_000_000_000L)
                        sql.executeUpdate()
                    }
                }
            }

            // 1) 1.1.1 旧缓存读出来必须失效（返回 null），不能直接当新版使用
            writeLegacyV1GuideRow()
            assertNull(library.sectionGuide(book.id, firstSection.id), "1.1.1 旧格式导读缓存必须失效以触发重生成")

            // 2) 阅读器路径（SectionGuideUi）：旧缓存失效 -> 用新版提示词重生成 1 次 -> 出现难点标并升级缓存；重开不再重调
            val explainer1 = Phase2FakeExplainer(root.resolve("home-1"))
            runSkikoComposeUiTest(size = Size(1080f, 820f), density = Density(1f)) {
                var activePage by mutableStateOf(firstSection.firstPage)
                setContent {
                    val focus = remember { FocusRequester() }
                    CoachTheme(darkTheme = false) {
                        CompositionLocalProvider(
                            LocalReadingAutoGuide provides true,
                            LocalReadingSectionSummary provides false,
                        ) {
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
                                explainProvider = explainer1,
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithTag(markerTag0).fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
            }
            assertEquals(1, explainer1.guideCalls.size, "命中 1.1.1 旧缓存应重调一次新版导读")
            val upgraded = assertNotNull(library.sectionGuide(book.id, firstSection.id))
            assertEquals(CachedSectionGuide.CURRENT_VERSION, upgraded.version)
            assertTrue(upgraded.hardPoints.isNotEmpty(), "升级后缓存应包含难点列表")
            assertTrue(upgraded.summary.isNotEmpty(), "升级后缓存应包含节末小结")

            val explainer2 = Phase2FakeExplainer(root.resolve("home-2"))
            runSkikoComposeUiTest(size = Size(1080f, 820f), density = Density(1f)) {
                var activePage by mutableStateOf(firstSection.firstPage)
                setContent {
                    val focus = remember { FocusRequester() }
                    CoachTheme(darkTheme = false) {
                        CompositionLocalProvider(
                            LocalReadingAutoGuide provides true,
                            LocalReadingSectionSummary provides false,
                        ) {
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
                                explainProvider = explainer2,
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithTag(markerTag0).fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
            }
            assertEquals(0, explainer2.guideCalls.size, "升级为 v2 缓存后重开阅读器应直接命中缓存")

            // 3) 讲解面板路径（BookExplain）：旧缓存失效 -> 点击「讲讲这一节」重生成 1 次并展示难点标
            writeLegacyV1GuideRow()
            assertNull(library.sectionGuide(book.id, firstSection.id))
            val explainer3 = Phase2FakeExplainer(root.resolve("home-3"))
            runSkikoComposeUiTest(size = Size(960f, 760f), density = Density(1f)) {
                setContent {
                    CoachTheme(darkTheme = false) {
                        Surface(Modifier.fillMaxSize(), color = CoachTheme.colors.surface1) {
                            BookExplainPanel(
                                book = book,
                                page = firstSection.firstPage,
                                consent = ExplainConsent(library.directory),
                                explainer = explainer3,
                                selection = null,
                                library = library,
                            )
                        }
                    }
                }
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithTag("explain-section-trigger").fetchSemanticsNodes()
                        .firstOrNull()?.config?.contains(SemanticsProperties.Disabled) == false
                }
                onNodeWithTag("explain-section-trigger").performClick()
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithTag(panelMarkerTag0).fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
            }
            assertEquals(1, explainer3.guideCalls.size, "讲解面板遇到 1.1.1 旧缓存也必须重新生成一次")
            val panelUpgraded = assertNotNull(library.sectionGuide(book.id, firstSection.id))
            assertEquals(CachedSectionGuide.CURRENT_VERSION, panelUpgraded.version)
            assertTrue(panelUpgraded.hardPoints.isNotEmpty())

            // 4) 新版（v2）本身无难点（hardPoints = []）的合法结果不得被误判失效导致死循环重生成
            library.saveSectionGuide(
                book.id,
                firstSection.id,
                CachedSectionGuide(
                    brief = listOf("要点一"),
                    text = "无难点节讲解【S1】",
                    citations = emptyList(),
                    recap = "本节前情提要",
                    hardPoints = emptyList(),
                    summary = listOf("小结一"),
                ),
            )
            val emptyHpV2 = assertNotNull(library.sectionGuide(book.id, firstSection.id))
            assertEquals(CachedSectionGuide.CURRENT_VERSION, emptyHpV2.version)
            assertTrue(emptyHpV2.hardPoints.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun sectionEndSummaryOptionalChoicesMainlineLinkAndSettingToggleWithScreenshots() {
        val root = Files.createTempDirectory("ton1768-summary-")
        val outDir = Path.of("build/screens/ton1768")
        Files.createDirectories(outDir)
        try {
            val mdPath = writeTwoPageSectionBook(root.resolve("two-page-section.md"))
            val library = TextbookLibrary(root.resolve("library"))
            val controller = TextbookController(library)
            runBlocking {
                controller.importBook(mdPath)
            }
            val book = controller.state.value.book!!
            assertTrue(book.pages.size >= 2)
            assertEquals(1, book.lessons.first().firstPage)
            assertTrue(book.lessons.first().lastPage >= 2)

            // 1) 验证设置项「节末小结」默认开启且可持久化关闭
            val initialSettings = ReadingSettings(library.directory)
            assertTrue(initialSettings.sectionSummary, "节末小结默认应开启")
            initialSettings.chooseSectionSummary(false)
            assertFalse(ReadingSettings(library.directory).sectionSummary)
            initialSettings.chooseSectionSummary(true)
            assertTrue(ReadingSettings(library.directory).sectionSummary)

            ExplainConsent(library.directory).allow(book.id)
            val explainer = Phase2FakeExplainer(root.resolve("home"))
            runBlocking { controller.learn(LearningPath.READ) }

            runSkikoComposeUiTest(size = Size(960f, 840f), density = Density(1f)) {
                val activeSettings = ReadingSettings(library.directory)
                setContent {
                    val ui by controller.state.collectAsState()
                    val focus = remember { FocusRequester() }
                    val typePanel: DeskTypeControl = { extra -> ReadingSettingsPanel(activeSettings, { false }, extra) }
                    CoachTheme(darkTheme = false) {
                        CompositionLocalProvider(
                            LocalDeskTypeControl provides typePanel,
                            LocalReadingAutoGuide provides false,
                            LocalReadingSectionSummary provides activeSettings.sectionSummary,
                        ) {
                            Surface(Modifier.fillMaxSize(), color = CoachTheme.colors.surface1) {
                                when (ui.screen) {
                                    BookScreen.READER -> TextbookReader(
                                        ui = ui,
                                        library = library,
                                        support = UnconnectedBookLearningSupport,
                                        practiceSource = null,
                                        pageFocus = focus,
                                        onNoteSession = {},
                                        onPage = { page -> runBlocking { controller.page(page) } },
                                        onBack = {},
                                        onLearn = {},
                                        explainProvider = explainer,
                                        onSaveSectionQuiz = { lesson, qIdx, practice, answer ->
                                            runBlocking { controller.recordSectionQuizAnswer(lesson, qIdx, practice, answer) }
                                        },
                                        onOpenSectionMainline = { secIdx ->
                                            runBlocking { controller.openSectionPractice(secIdx) }
                                        },
                                    )
                                    BookScreen.LEARNING -> {
                                        TextbookLearning(
                                            ui = ui,
                                            support = UnconnectedBookLearningSupport,
                                            scroll = androidx.compose.foundation.rememberScrollState(),
                                            lessonSourceFocus = remember { FocusRequester() },
                                            practiceSourceFocus = remember { FocusRequester() },
                                            returnFocus = null,
                                            explanationReply = remember { mutableStateOf(null) },
                                            answerExplain = null,
                                            answerExplainReply = remember { mutableStateOf(null) },
                                            sourceResolver = BookSourceResolver(library),
                                            citationFocus = remember { mutableMapOf() },
                                            citationIndex = 0,
                                            onFocusRestored = {},
                                            onBack = {},
                                            onSource = {},
                                            onCitation = { _, _ -> },
                                            onCitationRefused = {},
                                            onPracticeSource = {},
                                            onLesson = {},
                                            onSettings = {},
                                            onAnswer = {},
                                            onReviews = {},
                                            onQuestions = {},
                                            onRecapContinue = {},
                                        )
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
                waitUntil(timeoutMillis = 20_000) { onAllNodesWithTag("reader-text").fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                // 第 1 页（节首）尚未到节末，不出现节末小结
                onNodeWithTag("section-end-summary-card").assertDoesNotExist()

                // 翻到节末页
                var turns = 0
                while (turns < 20 && onAllNodesWithTag("section-end-summary-card").fetchSemanticsNodes().isEmpty()) {
                    onNodeWithTag("next-page").performClick()
                    waitForIdle()
                    turns++
                }
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithTag("section-end-summary-card").fetchSemanticsNodes().isNotEmpty() &&
                        explainer.guideCalls.isNotEmpty()
                }
                waitForIdle()
                onNodeWithTag("section-end-summary-lines").assertIsDisplayed()
                onNodeWithTag("section-end-start-quiz").assertIsDisplayed()
                savePng(outDir.resolve("ton1768-03-section-end-summary.png"))

                // 展开 1–2 道选择题并作答（第 1 题选错、第 2 题选对），验证接入现有做题主线（复习队列 + 题梯）
                onNodeWithTag("section-end-start-quiz").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("section-end-quiz-panel").fetchSemanticsNodes().isNotEmpty() }
                val practices = listOfNotNull(testPracticeFixture(book, book.lessons.first(), QuestionMode.CHOICE))
                assertEquals(2, practices.size, "本节应能出 2 道选择题")
                val wrongIdx0 = practices[0].options.indexOfFirst { it != practices[0].answer }
                val rightIdx1 = practices[1].options.indexOfFirst { it == practices[1].answer }
                assertTrue(wrongIdx0 >= 0 && rightIdx1 >= 0)
                onNodeWithTag("section-quiz-0-choice-$wrongIdx0").performScrollTo().performClick()
                onNodeWithTag("section-quiz-1-choice-$rightIdx1").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) {
                    library.reviews().isNotEmpty() && library.ladder(book.id).responses.size == 2
                }
                waitForIdle()
                savePng(outDir.resolve("ton1768-04-section-end-quiz.png"))

                // 设置里关闭「节末小结」，卡片立即收起并持久化
                onNodeWithTag("v7-type-menu").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("reading-sectionSummary-OFF").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("reading-sectionSummary-OFF").performClick()
                waitForIdle()
                assertFalse(ReadingSettings(library.directory).sectionSummary)
                onNodeWithTag("section-end-summary-card").assertDoesNotExist()
                savePng(outDir.resolve("ton1768-05-summary-off.png"))

                // 再开启「节末小结」并收起设置弹窗，然后点击「进入做题主线」跳转到 BookScreen.LEARNING
                onNodeWithTag("reading-sectionSummary-ON").performClick()
                waitForIdle()
                onNodeWithTag("v7-type-menu").performMouseInput { click() }
                onNodeWithTag("reading-settings-panel").assertDoesNotExist()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("section-end-open-mainline").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("section-end-open-mainline").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.screen == BookScreen.LEARNING }
                onNodeWithTag("textbook-learning").assertIsDisplayed()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun ComposeUiTest.savePng(path: Path) {
        val img = (this as SkikoComposeUiTest).captureToImage().toAwtImage()
        assertTrue(ImageIO.write(img, "png", path.toFile()))
    }

    private fun countMidSentenceCuts(lessons: List<BookLesson>): Int {
        val ends = setOf('。', '！', '？', '.', '!', '?', '”', '’', '」', '』', ')', '）', '：', ':')
        var cuts = 0
        for (i in 0 until lessons.lastIndex) {
            val tail = lessons[i].text.trimEnd()
            if (tail.isNotEmpty() && tail.last() !in ends) cuts++
        }
        return cuts
    }

    private fun writeUnstructuredLongText(target: Path): Path {
        val topics = listOf(
            "细胞膜主要由脂质双分子层构成，其中的蛋白质通道负责离子与小分子的选择性跨膜运输。",
            "叶绿体通过光反应捕获光能并转化为化学能，随后在暗反应阶段把二氧化碳固定为糖类有机物。",
            "有氧呼吸在细胞质基质和线粒体中分阶段推进，把葡萄糖彻底氧化分解并释放出大量可用能量。",
            "遗传信息的转录以DNA一条链为模板合成信使RNA，再经过核糖体上的翻译过程组装出多肽链。",
        )
        val body = topics.joinToString("\n\n") { opener ->
            val rest = "该过程具有严谨的酶促调控机制，任何环境温度或底物浓度的波动都会显著影响反应速率与稳态平衡。"
            (1..4).joinToString("\n\n") { idx ->
                val lead = if (idx == 1) opener else "在进一步分析中，实验观测表明这一环节与细胞内部微环境保持高度协同。"
                lead + rest.repeat(6)
            }
        }
        Files.writeString(target, body)
        return target
    }

    private fun writeUnstructuredPdf(target: Path): Path {
        val topics = listOf(
            "造纸术的改进使书写载体摆脱了竹简与帛书的笨重束缚，让典籍抄录和官府文书大幅降低了物质成本。",
            "活字印刷技术把文字排版从整版雕刻变为可重复拼装的单个字模，显著加快了书籍刊印与修订的速度。",
            "指南针在远洋航海中的应用大幅提升了船队在阴雨天辨别航向的能力，推动了跨海贸易航线的长期稳定。",
            "火药配方在宋元时期的系统记载推动了工程爆破与防御器械的演进，并沿着陆海通道传向更广的区域。",
        )
        PDDocument().use { pdf ->
            val font = PDType0Font.load(pdf, Path.of("../shared/ui/src/androidMain/res/font/noto_serif_sc.ttf").toFile())
            for ((topicIdx, opener) in topics.withIndex()) {
                repeat(2) { subPage ->
                    val page = PDPage()
                    pdf.addPage(page)
                    val text1 = if (subPage == 0) opener + "历史上工匠在长期实践中不断改进原料配比与工艺细节，形成了稳定可复用的技术传统。".repeat(5)
                    else "围绕上述工艺的第${topicIdx + 1}阶段演进，各地作坊逐步完善了分工流程与检验标准，推动了跨地域交流。".repeat(5)
                    val text2 = "与此同时，制度需求与经济往来为技术扩散提供了持续动力，使生产效率在数代之内实现了阶梯式跃升。".repeat(5)
                    PDPageContentStream(pdf, page).use { out ->
                        var y = 740f
                        for (para in listOf(text1, text2)) {
                            for (line in para.chunked(38)) {
                                out.beginText(); out.setFont(font, 10.5f); out.newLineAtOffset(52f, y); out.showText(line); out.endText()
                                y -= 16f
                            }
                            y -= 14f
                        }
                    }
                }
            }
            pdf.save(target.toFile())
        }
        return target
    }

    private fun writeStructuredMarkdown(target: Path): Path {
        val text = "# 第一章 导论\n\n" +
            "## 第一节 基本概念\n\n" +
            "课程研究知识建构与思维迁移的基本方法，强调在真实问题情境中验证概念之间的因果联系。".repeat(12)
        Files.writeString(target, text)
        return target
    }

    private fun writeStructuredPdf(target: Path): Path {
        PDDocument().use { pdf ->
            val font = PDType0Font.load(pdf, Path.of("../shared/ui/src/androidMain/res/font/noto_serif_sc.ttf").toFile())
            val page = PDPage()
            pdf.addPage(page)
            val para1 = "科学探究是指学习者围绕可验证的自然现象提出假设、收集证据并做出合理解释的完整思维过程。".repeat(4)
            val para2 = "能量转化指的是能量从光能、化学能或机械能等一种形式转变为另一种形式，同时总量保持守恒。".repeat(4)
            PDPageContentStream(pdf, page).use { out ->
                var y = 740f
                for (block in listOf("一、核心概念与方法", "（一）科学探究与能量守恒", para1, para2)) {
                    for (line in block.chunked(38)) {
                        out.beginText(); out.setFont(font, 10.5f); out.newLineAtOffset(52f, y); out.showText(line); out.endText()
                        y -= 16f
                    }
                    y -= 14f
                }
            }
            pdf.save(target.toFile())
        }
        return target
    }

    private fun writeTwoPageSectionBook(target: Path): Path {
        val p1 = (1..6).joinToString("\n\n") { i ->
            "第一部分第${i}段：生态位是指物种在群落中占据的空间位置以及与其他生物的功能关系，决定了资源利用方式。".repeat(4)
        }
        val p2 = (1..4).joinToString("\n\n") { i ->
            "第二部分第${i}段：群落演替指的是随着时间推移一个群落被另一个群落替代的自然过程，分为初生演替与次生演替。".repeat(4)
        }
        val content = """
            <!-- PDF 第 1 页 · 书第 1 页 -->
            # 第一章 群落生态学

            $p1

            <!-- PDF 第 2 页 · 书第 2 页 -->
            $p2
        """.trimIndent()
        Files.writeString(target, content)
        return target
    }

    private class Phase2FakeExplainer(home: Path) :
        BookExplainer(DesktopConnectionSettings(home = home, environment = { null })) {
        val guideCalls = mutableListOf<ExplainSectionContext>()
        val selectionCalls = mutableListOf<String>()

        override suspend fun load() {
            identity = DesktopIdentity("http://service.test:18791", PairedCredentials("test-device", "test-token"))
            loaded = true
        }

        override suspend fun explainSectionGuide(
            context: ExplainSectionContext,
            bookPages: Int,
            requestId: String,
        ): SectionGuideResult {
            guideCalls += context
            val body = context.sectionBlocks.filterNot { it.heading }
            val cited = body.take(2).mapIndexed { idx, b -> Citation("S${idx + 1}", b.id, b.page) }
            val hardPoints = body.take(2).mapIndexed { idx, b ->
                SectionHardPoint(
                    blockId = b.id,
                    anchor = "S${idx + 1}",
                    page = b.page,
                    label = if (idx == 0) "核心概念" else "常见易错",
                    explanation = "放在整节语境里看，这一段把基本定义和后续规律串联起来，做题时常考它的成立条件。",
                )
            }
            val brief = listOf(
                "本节围绕${context.location}展开核心定义与分析。",
                "重点一：区分核心概念的适用边界。",
                "重点二：结合前后段落理清因果条件。",
            )
            val summary = listOf(
                "掌握核心概念的定义与成立前提。",
                "能够区分易混表述并回到原文证据核对。",
                "做好下一节迁移练习前先用两道小题自检。",
            )
            return SectionGuideResult.Done(
                SectionGuide(
                    brief = brief,
                    explanation = Explanation("本节讲解：概念定义与规律转化。【S1】", cited, 12L),
                    recap = "本节梳理了核心概念的定义与边界。",
                    hardPoints = hardPoints,
                    summary = summary,
                ),
            )
        }

        override suspend fun explainSelection(
            kind: String,
            chosen: SourceBlock,
            context: ExplainSectionContext?,
            question: String?,
            history: List<FollowUpTurn>,
            requestId: String,
        ): ExplainResult {
            selectionCalls += kind
            val anchor = context?.sectionBlocks?.firstOrNull { !it.heading } ?: chosen
            return ExplainResult.Done(Explanation("结合整节语境的追问说明。【S1】", listOf(Citation("S1", anchor.id, anchor.page)), 10L))
        }
    }
}
