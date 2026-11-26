package com.coach.desktop.books

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
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
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CourseExamPointMapTest {
    @Test
    fun weakestFiveOpenTheirOwnPracticeInBothThemes() {
        val names = listOf("恒星视差", "轨道周期", "逃逸速度", "凌星深度", "月相", "太阳风")
        val points = names.mapIndexed { i, name ->
            CourseTopicEntry("point-$i", "course", CourseExamPoints.LABEL, null, i, name,
                CourseTopicStatus.EXAMINABLE, false)
        }
        val gaps = points.mapIndexed { i, point ->
            TopicCountdownGap(point.topicId, point.weekLabel, point.title, 1.0, 0.88 - i * 0.14,
                1.0, 0.0, 0.0, hasPracticeEvidence = true)
        }
        val plan = ExamCountdownPlan("course", phase = CountdownPhase.FOUNDATION, fsrsDailyCap = 20,
            overdueAlertCount = 0, rankedTopics = gaps, dailySlots = emptyList(), todayFocusTopicId = null,
            headerSummaryLine = "", primaryCtaLabel = "")
        val detail = CourseDetailSnapshot("course", "天文学", CourseExamProfile(examEnabled = true), emptyList(),
            points, emptyList(), emptyList(), countdownPlan = plan, pastPaperTechniques = mapOf("point-5" to listOf(ExamTechnique.COMPARISON)))
        for (dark in listOf(false, true)) {
            var selected: String? = null
            runSkikoComposeUiTest(size = Size(820f, 400f)) {
                setContent {
                    CoachTheme(dark) {
                        Surface(modifier = Modifier.padding(16.dp)) {
                            CourseExamPointMap(detail) { selected = it.topicId }
                        }
                    }
                }
                onNodeWithTag("exam-map-point-0").assertDoesNotExist()
                (1..5).forEach { onNodeWithTag("exam-map-point-$it").assertIsDisplayed() }
                onNodeWithText("比较", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithTag("exam-map-point-5").performClick()
                assertEquals("point-5", selected)
                System.getenv("COACH_TON1890_SHOTS")?.let { dir ->
                    val output = Files.createDirectories(Path.of(dir)).resolve(if (dark) "map-dark.png" else "map-light.png")
                    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", output.toFile())
                }
                onNodeWithText("查看全部 6 个考点").performClick()
                onNodeWithTag("exam-map-point-0").assertIsDisplayed()
            }
        }
    }
}
