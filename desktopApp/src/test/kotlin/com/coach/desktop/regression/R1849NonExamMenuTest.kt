package com.coach.desktop.regression

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import com.coach.desktop.WindowsTextbookHost
import com.coach.desktop.books.CourseAutoAligner
import com.coach.desktop.books.CourseExamProfile
import com.coach.desktop.books.CourseStore
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import com.coach.ui.CoachTheme
import com.coach.ui.v7.LocalV7ReducedMotion
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * TON-1849: a course with no exam date must not offer "设置考试日" beside "要考".
 * The same source fails before the menu change and passes after it.
 */
@OptIn(ExperimentalTestApi::class)
class R1849NonExamMenuTest {
    @Test
    fun nonExamOverflowKeepsYaoKaoAndDropsSetExamDate() {
        val dir = Files.createTempDirectory("coach-r1849-menu-")
        try {
            val library = TextbookLibrary(dir.resolve("library"), lazyPdfExtras = false)
            val bookFile = dir.resolve("Micro.md")
            Files.writeString(
                bookFile,
                buildString {
                    for (ch in 1..3) {
                        append("# Chapter $ch Core Concepts\n\n")
                        append("Section $ch explains how households choose between consumption and saving.\n\n")
                    }
                },
            )
            val book = library.stageBookImport(bookFile).book
            val courseId = library.withCourseDatabase { db ->
                val (cid, rejected) = CourseStore.createCourse(
                    db = db,
                    title = "Micro course",
                    examProfile = CourseExamProfile(),
                    materialBookIds = listOf(book.id),
                )
                check(rejected.isEmpty())
                CourseAutoAligner.alignLocally(db, cid)
                cid
            }
            val controller = TextbookController(library)
            runBlocking {
                controller.load()
                controller.openCourse(courseId)
            }
            runSkikoComposeUiTest(size = Size(1100f, 780f)) {
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                            WindowsTextbookHost(controller) { null }
                        }
                    }
                }
                waitUntil(timeoutMillis = 15_000) {
                    onAllNodesWithTag("course-screen").fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                onNodeWithTag("course-overflow").performClick()
                waitForIdle()
                onNodeWithText("要考").assertIsDisplayed()
                onNodeWithText("设置考试日").assertDoesNotExist()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
