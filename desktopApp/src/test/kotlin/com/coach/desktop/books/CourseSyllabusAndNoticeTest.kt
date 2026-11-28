package com.coach.desktop.books

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import com.coach.ui.CoachTheme
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TON-1986 第 4 条：课程页不重复催考纲，关掉提示后入口还在。
 *
 * 以前课程页一条考纲状态、模考页另一条考纲提示，同一件事问两遍；真题过滤说明还关不掉。
 */
@OptIn(ExperimentalTestApi::class)
class CourseSyllabusAndNoticeTest {

    private val shotsDir: Path? =
        System.getenv("COACH_TON1986_SHOTS")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

    private fun SkikoComposeUiTest.shot(dark: Boolean, name: String) {
        val dir = shotsDir ?: return
        val output = Files.createDirectories(dir).resolve("$name-${if (dark) "dark" else "light"}.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", output.toFile())
    }

    private fun examDetail(
        promptState: CourseSyllabusPromptState = CourseSyllabusPromptState.UNASKED,
        spoilerCount: Int = 0,
    ) = CourseDetailSnapshot(
        courseId = "course-1",
        title = "微观经济学",
        examProfile = CourseExamProfile(examEnabled = true, syllabusPromptState = promptState),
        materials = emptyList(),
        examinableTopics = emptyList(),
        pendingTopics = emptyList(),
        excludedTopics = emptyList(),
        spoilerSanitizedCount = spoilerCount,
    )

    @Test
    fun coursePageHasOneSyllabusBarAndKeepsItsEntryAfterMute() {
        for (dark in listOf(false, true)) {
            var detail by mutableStateOf(examDetail())
            runSkikoComposeUiTest(size = Size(920f, 940f)) {
                setContent {
                    CoachTheme(dark) {
                        Surface(modifier = Modifier.padding(16.dp)) {
                            CourseScreen(
                                detail = detail,
                                busy = false,
                                notice = null,
                                onBack = {},
                                onOpenTopic = {},
                                onAddMaterials = {},
                                onRealign = {},
                                onUpdateMaterialAssignment = { _, _, _ -> },
                                onRemoveMaterial = {},
                                onRunHandwrittenVision = {},
                                onRenameTopic = { _, _ -> },
                                onUnlinkTopicSource = { _, _ -> },
                                onSetTopicStatus = { _, _ -> },
                                onUpdateExamProfile = { profile -> detail = detail.copy(examProfile = profile) },
                            )
                        }
                    }
                }

                // 只有一条考纲条：左边是当前范围，右边是「放进考纲」和「不再提示」
                onAllNodesWithTag("course-exam-scope-line").assertCountEquals(1)
                onAllNodesWithTag("course-syllabus-prompt-bar").assertCountEquals(0)
                onNodeWithText("按整本书安排，放进考纲后可以缩小范围").assertIsDisplayed()
                onNodeWithText("放进考纲").assertIsDisplayed()
                onNodeWithTag("course-syllabus-prompt-add").assertIsDisplayed()
                onNodeWithTag("course-syllabus-prompt-skip").assertIsDisplayed()
                shot(dark, "01-course-syllabus-bar")

                // 排计划页用的是同一条：这时才多一句问询，问的正是这一步要决定的事
                onNodeWithTag("course-overflow").performClick()
                onNodeWithTag("course-menu-weekly-plan-action").performClick()
                waitForIdle()
                onNodeWithTag("course-syllabus-prompt-bar").assertIsDisplayed()
                onNodeWithText("有考纲或往年题吗？没有也能先学").assertIsDisplayed()
                onNodeWithTag("course-syllabus-prompt-add").assertIsDisplayed()
                shot(dark, "02-weekly-plan-syllabus-bar")
                onNodeWithTag("course-syllabus-prompt-skip").performClick()
                waitForIdle()
                onAllNodesWithTag("course-syllabus-prompt-bar").assertCountEquals(0)

                // 回到课程页：只有一条状态条，考纲区仍留着「放进考纲」，不再出现「不再提示」
                onNodeWithTag("course-schedule-back").performClick()
                waitForIdle()
                onAllNodesWithTag("course-exam-scope-line").assertCountEquals(1)
                onNodeWithText("按整本书安排，放进考纲后可以缩小范围").assertIsDisplayed()
                onNodeWithTag("course-syllabus-prompt-add").assertIsDisplayed()
                onNodeWithTag("course-syllabus-prompt-skip").assertDoesNotExist()
                onNodeWithText("暂不放考纲", substring = true, useUnmergedTree = true).assertIsDisplayed()
                shot(dark, "03-course-after-mute")
            }
        }
    }

    @Test
    fun spoilerNoticeClosesKeepsAnEntryAndStaysMuted() {
        val detail = examDetail(spoilerCount = 3)
        for (dark in listOf(false, true)) {
            var muted by mutableStateOf(false)
            runSkikoComposeUiTest(size = Size(920f, 940f)) {
                setContent {
                    CoachTheme(dark) {
                        Surface(modifier = Modifier.padding(16.dp)) {
                            CourseScreen(
                                detail = detail,
                                busy = false,
                                notice = null,
                                onBack = {},
                                onOpenTopic = {},
                                onAddMaterials = {},
                                onRealign = {},
                                onUpdateMaterialAssignment = { _, _, _ -> },
                                onRemoveMaterial = {},
                                onRunHandwrittenVision = {},
                                onRenameTopic = { _, _ -> },
                                onUnlinkTopicSource = { _, _ -> },
                                onSetTopicStatus = { _, _ -> },
                                onUpdateExamProfile = {},
                                spoilerNoticeMuted = muted,
                                onMuteSpoilerNotice = { muted = true },
                            )
                        }
                    }
                }

                onNodeWithTag("course-spoiler-notice").assertIsDisplayed()
                shot(dark, "04-spoiler-notice")
                onNodeWithTag("course-spoiler-notice-mute").performClick()
                waitForIdle()
                assertTrue(muted, "不再提示必须真的落成静音，而不是只藏一次")
                onNodeWithTag("course-spoiler-notice").assertDoesNotExist()
                onNodeWithTag("course-spoiler-entry").assertIsDisplayed()
                onNodeWithText("个人笔记里的真题回忆已过滤 3 处").assertIsDisplayed()
                shot(dark, "05-spoiler-entry")

                // 入口还在：点开仍能看到完整说明
                onNodeWithTag("course-spoiler-entry").performClick()
                waitForIdle()
                onNodeWithText("真题回忆已过滤").assertIsDisplayed()
                onNodeWithTag("course-spoiler-info-close").performClick()
                waitForIdle()
                onNodeWithText("真题回忆已过滤").assertDoesNotExist()
            }
        }

        // 重开窗口后仍然记得，且入口还在
        runSkikoComposeUiTest(size = Size(920f, 940f)) {
            setContent {
                CoachTheme(false) {
                    Surface(modifier = Modifier.padding(16.dp)) {
                        CourseScreen(
                            detail = detail,
                            busy = false,
                            notice = null,
                            onBack = {},
                            onOpenTopic = {},
                            onAddMaterials = {},
                            onRealign = {},
                            onUpdateMaterialAssignment = { _, _, _ -> },
                            onRemoveMaterial = {},
                            onRunHandwrittenVision = {},
                            onRenameTopic = { _, _ -> },
                            onUnlinkTopicSource = { _, _ -> },
                            onSetTopicStatus = { _, _ -> },
                            onUpdateExamProfile = {},
                            spoilerNoticeMuted = true,
                            onMuteSpoilerNotice = {},
                        )
                    }
                }
            }
            onNodeWithTag("course-spoiler-notice").assertDoesNotExist()
            onNodeWithTag("course-spoiler-entry").assertIsDisplayed()
        }
    }

    @Test
    fun muteChoiceSurvivesGoingBackToTheCourseLater() {
        val dir = Files.createTempDirectory("coach-spoiler-pref-")
        val store = CourseUiPreferenceStore(dir)
        assertFalse(store.isSpoilerNoticeMuted("course-1"))
        store.muteSpoilerNotice("course-1")
        assertTrue(CourseUiPreferenceStore(dir).isSpoilerNoticeMuted("course-1"))
        assertFalse(CourseUiPreferenceStore(dir).isSpoilerNoticeMuted("course-2"))
    }
}
