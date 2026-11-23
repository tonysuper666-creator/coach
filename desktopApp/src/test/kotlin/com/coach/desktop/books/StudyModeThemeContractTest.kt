package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import com.coach.ui.CoachTheme
import com.coach.ui.theme.CoachTheme as Theme
import com.coach.ui.v7.LocalV7ReducedMotion
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StudyModeThemeContractTest {

    private fun syntheticBook24Chapters(): Textbook {
        val pages = (1..620).map { p ->
            BookPage(number = p, text = "Page $p synthetic economics text about adverse selection and equilibrium.")
        }
        val lessons = buildList {
            add(BookLesson("front-1", "Preface", listOf(SourceBlock("f1", 2, 0, 10, true, "Preface"))))
            for (ch in 1..24) {
                val p = 10 + (ch - 1) * 24
                add(
                    BookLesson(
                        id = "ch-$ch",
                        title = "$ch Chapter $ch",
                        blocks = listOf(
                            SourceBlock("c-$ch-h", p, 0, 12, true, "$ch Chapter $ch"),
                            SourceBlock("c-$ch-b", p + 2, 13, 60, false, "Adverse selection model chapter $ch"),
                        ),
                    ),
                )
                add(
                    BookLesson(
                        id = "ch-$ch-ex",
                        title = "$ch Exercises",
                        blocks = listOf(
                            SourceBlock("c-$ch-ex", p + 18, 0, 14, true, "$ch Exercises"),
                        ),
                    ),
                )
            }
        }
        val structure = BookStructure(pageRoles = emptyMap(), sections = emptyList(), source = "PDF_OUTLINE").apply {
            resolvedLessons = lessons
        }
        return Textbook(
            id = "bk-24",
            title = "Synthetic Economics",
            format = "pdf",
            pages = pages,
            readingPage = 179,
            structure = structure,
        )
    }

    @Test
    fun studyModeSelectorAndAllSurfacesRenderInLightAndDarkThemes() {
        val dir = Files.createTempDirectory("study-mode-theme-contract-")
        try {
            val library = TextbookLibrary(dir)
            library.catalog()
            val book = syntheticBook24Chapters()
            val ch8Lessons = book.lessons.filter { it.id.startsWith("ch-8") }
            val ch8Group = ChapterSectionGroup(
                chapterId = "ch-8",
                chapterTitle = "8 Chapter 8",
                sections = ch8Lessons,
                firstPage = ch8Lessons.first().firstPage,
                lastPage = ch8Lessons.last().lastPage,
            )
            StudyModeStore.saveModePackJson(
                library,
                book.id,
                8,
                BookStudyMode.BOOK_MAP,
                """{"firstScreen":{"headlineZh":"第 8 章一页纸","bigPictureThesisZh":"逆向选择核心论断","sectionArchitecture":[{"sectionId":"8.1","roleLabelZh":"引子","oneLineZh":"从保险直觉切入","pdfPage":179}],"chapterLinks":{"prerequisiteChapter":"承上第 7 章","downstreamChapter":"启下第 9 章"}}}""",
            )
            StudyModeStore.saveModePackJson(
                library,
                book.id,
                8,
                BookStudyMode.EXAM_FOCUS,
                """{"firstScreen":{"headlineZh":"第 8 章考纲分流","courseWeekBadgeZh":"对应第 4 周","mustStudySections":[{"sectionId":"8.2","weightTag":"★ 必考核心","examAngleZh":"条件期望推导","pdfPage":180}],"skimOrSkipSections":[{"sectionId":"8.1","weightTag":"○ 背景速览","whySkimZh":"背景铺垫"}],"examPitfallAlertZh":"区分系数 3/2 与 5/2"}}""",
            )
            val samplePack = BookCompanionPack(
                bookId = book.id,
                chapterId = "ch-8",
                chapterTitle = "8 Chapter 8",
                coreQuestion = "核心问题",
                pretest = listOf(
                    BookCompanionQuiz(
                        id = "diag-q1",
                        sectionId = "ch-8",
                        stem = "测试摸底题干",
                        options = listOf("选项 A", "选项 B"),
                        correctOption = "选项 A",
                        explanation = "解析",
                        anchor = BookCompanionAnchor(179, "anchor", 0, 6, "sha"),
                    ),
                ),
            )

            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(900f, 800f)) {
                    var pref by mutableStateOf(
                        BookStudyPreference(
                            bookId = book.id,
                            mode = BookStudyMode.READ_THROUGH,
                            userLocked = false,
                            defaultReason = "英文专业教材，默认逐节精读配中英对照；随时可换。",
                            examMode = true,
                        ),
                    )
                    var draft by mutableStateOf(ChapterPracticeDraft(bookId = book.id, tocNodeId = "ch-8", page = 179))
                    var lastModeChanged by mutableStateOf(false)
                    var jumpedPage by mutableStateOf(-1)

                    setContent {
                        CoachTheme(dark) {
                            CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                                Column(Modifier.fillMaxSize().background(Theme.colors.bgCanvas).padding(16.dp)) {
                                    StudyModeSelectorButton(
                                        preference = pref,
                                        onUpdatePreference = { next, changed ->
                                            pref = next
                                            lastModeChanged = changed
                                        },
                                        tag = "theme-mode-trigger",
                                    )
                                    if (pref.mode != BookStudyMode.READ_THROUGH) {
                                        ModeDistinctCompanionSurface(
                                            book = book,
                                            library = library,
                                            currentPage = 179,
                                            preference = pref,
                                            chapterGroup = ch8Group,
                                            currentSectionIndex = 0,
                                            pack = samplePack,
                                            draft = draft,
                                            onUpdateDraft = { draft = it(draft) },
                                            onUpdateInquiry = { q -> pref = pref.copy(inquiryQuestion = q) },
                                            onJumpPage = { jumpedPage = it },
                                            onOpenAnchor = { jumpedPage = it.pdfPage },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 2-click switch: Click 1 open popover -> Click 2 pick BOOK_MAP
                    onNodeWithTag("theme-mode-trigger").assertIsDisplayed().performClick()
                    waitForIdle()
                    onNodeWithTag("study-mode-default-reason").assertIsDisplayed()
                    onNodeWithTag("mode-option-BOOK_MAP").assertIsDisplayed().performClick()
                    waitForIdle()

                    assertEquals(BookStudyMode.BOOK_MAP, pref.mode)
                    assertTrue(pref.userLocked)
                    assertTrue(lastModeChanged)
                    onNodeWithTag("mode-surface-BOOK_MAP").assertIsDisplayed()
                    onNodeWithText("第 8 / 24 章", substring = true).assertIsDisplayed()
                    onNodeWithText("各节角色分工").assertIsDisplayed()
                    onNodeWithText("前后章脉络关系").assertIsDisplayed()

                    // Switch to EXAM_FOCUS -> real triage pack renders
                    onNodeWithTag("theme-mode-trigger").performClick()
                    waitForIdle()
                    onNodeWithTag("mode-option-EXAM_FOCUS").performClick()
                    waitForIdle()
                    onNodeWithTag("mode-surface-EXAM_FOCUS").assertIsDisplayed()
                    onNodeWithText("对应第 4 周").assertIsDisplayed()

                    // Switch to DIAGNOSTIC_SKIP -> anti-guessing unsure option works
                    onNodeWithTag("theme-mode-trigger").performClick()
                    waitForIdle()
                    onNodeWithTag("mode-option-DIAGNOSTIC_SKIP").performClick()
                    waitForIdle()
                    onNodeWithTag("mode-surface-DIAGNOSTIC_SKIP").assertIsDisplayed()
                    onNodeWithTag("diagnostic-unsure-diag-q1").performClick()
                    waitForIdle()
                    assertEquals(true, draft.pretestGuessed["diag-q1"])

                    // Switch to PROBLEM_DRIVEN & QUESTION_DRIVEN
                    onNodeWithTag("theme-mode-trigger").performClick()
                    waitForIdle()
                    onNodeWithTag("mode-option-PROBLEM_DRIVEN").performClick()
                    waitForIdle()
                    onNodeWithTag("mode-surface-PROBLEM_DRIVEN").assertIsDisplayed()
                    onNodeWithTag("problem-jump-exercises").performClick()
                    assertTrue(jumpedPage > 0)

                    onNodeWithTag("theme-mode-trigger").performClick()
                    waitForIdle()
                    onNodeWithTag("mode-option-QUESTION_DRIVEN").performClick()
                    waitForIdle()
                    onNodeWithTag("mode-surface-QUESTION_DRIVEN").assertIsDisplayed()

                    // Adjust detail level to ORIGINAL_ONLY via knob pill
                    onNodeWithTag("theme-mode-trigger").performClick()
                    waitForIdle()
                    onNodeWithTag("mode-detail-original").performClick()
                    waitForIdle()
                    assertEquals(ExplainDetailLevel.ORIGINAL_ONLY, pref.detailLevel)
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun studyModeSourceObeysUxAndHarnessContracts() {
        val repoRoot = locateRepoRoot()
        val uiSource = File(repoRoot, "shared/books/src/main/kotlin/com/coach/desktop/books/StudyModeUi.kt").readText()
        assertFalse("AlertDialog" in uiSource, "StudyModeUi.kt must not use blocking AlertDialog")
        assertFalse("Dialog(" in uiSource, "StudyModeUi.kt must not use blocking Dialog(")
        assertFalse("PrimaryButton(" in uiSource, "StudyModeUi.kt must not add PrimaryButton (UX-4)")
        assertFalse("idx > 0" in uiSource, "EXAM_FOCUS must never guess importance from section index idx > 0")

        val harnessSource = File(repoRoot, "tools/v2-acceptance/ReaderAcceptanceHarness.java").readText()
        val modesIdx = harnessSource.indexOf("private static void runModes(")
        assertTrue(modesIdx > 0, "ReaderAcceptanceHarness.java must define runModes")
        val modesSlice = harnessSource.substring(modesIdx, harnessSource.indexOf("private static void runNewcomer(", modesIdx))
        assertFalse("Math.max(2," in modesSlice, "runModes must not clamp click count with Math.max(2, ...)")
        val afterClick = modesSlice.substring(modesSlice.indexOf("home-mode-trigger"))
        assertFalse("controller.page(" in afterClick, "runModes must not call controller.page(...) after switching mode")
    }

    private fun locateRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "shared/books/src/main").isDirectory && File(dir, "tools/v2-acceptance").isDirectory) {
                return dir
            }
            dir = dir.parentFile
        }
        error("Cannot locate repo root")
    }
}
