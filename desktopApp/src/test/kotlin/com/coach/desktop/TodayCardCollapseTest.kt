package com.coach.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.*

/**
 * TON-1985 第 3 条：今日卡平时只占一行摘要，点开才是课表与时长；
 * 时长选完立刻按它排；1360×820 的第一屏里同时看得到「继续」和书架。
 */
@OptIn(ExperimentalTestApi::class)
class TodayCardCollapseTest {
    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    @Test fun summaryIsOneLineTheCapAppliesAtOnceAndTheShelfFitsTheFirstScreen() {
        val root = Files.createTempDirectory("coach-today-collapse-")
        try {
            val library = TextbookLibrary(root.resolve("library"), todayCardEnabled = true)
            val exam = LocalDate.now().plusDays(14)
            val c = TextbookController(library)
            runBlocking {
                c.importBook(syntheticTextbook(root.resolve("synthetic")))
                c.back()
            }
            val book = c.state.value.catalog.single()
            library.withCourseDatabase { db ->
                val courseId = CourseStore.createCourse(
                    db,
                    "Biology",
                    CourseExamProfile(
                        examDate = exam.toString(),
                        examFormat = "2道单选",
                        durationMinutes = 30,
                        examEnabled = true,
                    ),
                ).first
                db.prepareStatement(
                    """INSERT INTO course_topics(topic_id, collection_id, week_label, ord, title, exam_status, user_locked, exam_weight, pack_payload_json, practice_draft_json, mastery_state, updated_at)
                       VALUES(?,?,?,?,?,'EXAMINABLE',0,1.0,?,'{}','UNSEEN',?)""",
                ).use { sql ->
                    sql.setString(1, "topic-light"); sql.setString(2, courseId); sql.setString(3, "第 1 周")
                    sql.setInt(4, 1); sql.setString(5, "Light"); sql.setString(6, null); sql.setLong(7, 1)
                    sql.executeUpdate()
                }
            }
            runBlocking { c.load() }

            runSkikoComposeUiTest(size = Size(1360f, 820f), density = Density(1f)) {
                setContent { WindowsTextbookHost(c) { null } }
                ready(c) { it.screen == BookScreen.HOME && it.catalog.isNotEmpty() }

                // 平时一行：只有摘要和「展开」，课表和时长都收着。
                onNodeWithTag("home-exam-summary").assertIsDisplayed()
                onNodeWithTag("home-exam-toggle", useUnmergedTree = true).assertTextEquals("展开")
                onAllNodesWithTag("home-exam-course").assertCountEquals(0)
                onAllNodesWithTag("home-exam-day").assertCountEquals(0)
                onAllNodesWithTag("home-exam-minutes-30").assertCountEquals(0)
                // 第一次用 30 分钟，不必先选。
                onNodeWithTag("home-exam-countdown", useUnmergedTree = true).assertTextContains("/30 分钟", substring = true)
                assertNull(
                    library.withCourseDatabase {
                        ExamDailyStateStore.read(it, ExamDailyStateStore.SHARED_BUDGET_ID).first
                    },
                    "第一屏不该写死时长，用户选了才存",
                )

                // 「继续」和书架都在 1360×820 的第一屏里，不用滚。
                val read = onNodeWithTag("home-read").fetchSemanticsNode().boundsInRoot
                val shelf = onNodeWithTag("book-${book.id}").fetchSemanticsNode().boundsInRoot
                assertTrue(read.bottom <= 820f, "home-read=$read")
                assertTrue(shelf.bottom <= 820f, "book=${book.id} $shelf")

                // 点开才给课表和时长档位。
                onNodeWithTag("home-exam-summary").performClick()
                waitForIdle()
                onNodeWithTag("home-exam-toggle", useUnmergedTree = true).assertTextEquals("收起")
                onNodeWithTag("home-exam-course").assertExists()
                onAllNodesWithTag("home-exam-day").assertCountEquals(5)

                // 点了 90 分钟就按 90 排，摘要立刻跟着变，并落到库里下次沿用。
                onNodeWithTag("home-exam-minutes-90").performClick()
                waitUntil(timeoutMillis = 10_000) {
                    onAllNodesWithText("/90 分钟", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(
                    90,
                    library.withCourseDatabase {
                        ExamDailyStateStore.read(it, ExamDailyStateStore.SHARED_BUDGET_ID).first
                    },
                )
                onNodeWithTag("home-exam-countdown", useUnmergedTree = true).assertTextContains("/90 分钟", substring = true)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
