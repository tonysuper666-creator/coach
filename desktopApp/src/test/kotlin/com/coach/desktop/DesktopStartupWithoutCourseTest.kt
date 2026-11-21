package com.coach.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.*
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DesktopStartupWithoutCourseTest {
    @Test fun missingCourseSelectsBooksOnEveryDesktopHost() {
        for (host in listOf(System.getProperty("os.name"), "Windows 11", "Mac OS X", "Linux").distinct()) {
            assertTrue(desktopUsesTextbooks(host, null, null), "$host must open local books without a course")
            assertTrue(desktopUsesTextbooks(host, "  ", "\t"), "$host must treat blank course settings as absent")
        }
    }

    @Test fun configuredCourseKeepsThePairedRouteAndItsExactIdentity() {
        for (host in listOf("Windows 11", "Mac OS X", "Linux")) {
            assertFalse(desktopUsesTextbooks(host, " C ", null))
            assertFalse(desktopUsesTextbooks(host, null, "packaged-course"))
            assertEquals("C", resolveCourse(" C ", "different-course"))
            assertEquals("packaged-course", resolveCourse("  ", "packaged-course"))
        }
    }

    @Test fun localBooksImportReadAndReopenWithoutAConfiguredCourse() {
        val root = Files.createTempDirectory("synthetic-unconfigured-books-").toRealPath()
        val source = root.resolve("synthetic-orbits.txt")
        Files.writeString(source, "Chapter 1 Orbits\n\nOrbit is the path of an object around another body.\u000c" +
            "Chapter 2 Gravity\n\nGravity is the attraction between bodies with mass.")
        val directory = root.resolve("library")
        var controller by mutableStateOf(TextbookController(TextbookLibrary(directory)))
        assertTrue(desktopUsesTextbooks(System.getProperty("os.name"), null, null))
        runSkikoComposeUiTest(size = Size(1100f, 800f), density = Density(1f)) {
            setContent { key(controller) { WindowsTextbookHost(controller) { source } } }
            ready(controller) { it.catalog.isEmpty() }
            onNodeWithTag("upload-textbook").performClick()
            ready(controller) { it.prompt == BookPrompt.IMPORTED }
            onNodeWithTag("learning-path-READ").performClick()
            ready(controller) { it.learningPath == LearningPath.READ }
            onNodeWithTag("enable-learning").performClick()
            ready(controller) { it.screen == BookScreen.READER && it.prompt == null }
            val id = controller.state.value.book!!.id
            onNodeWithTag("reader-text").assertTextContains("Orbit is the path", substring = true)
            onNodeWithTag("choice-0").assertDoesNotExist()
            onNodeWithTag("next-page").performClick()
            ready(controller) { it.page == 2 && it.book?.readingPage == 2 }
            assertFalse(controller.state.value.book!!.learning)
            assertTrue(controller.library.reviews().isEmpty())
            runOnIdle { controller = TextbookController(TextbookLibrary(directory)) }
            ready(controller) { it.book?.id == id && it.screen == BookScreen.READER && it.page == 2 }
            assertEquals(id, controller.state.value.book!!.id)
            assertEquals("READ", controller.library.learningPath(id))
            onNodeWithTag("reader-text").assertTextContains("Gravity is the attraction", substring = true)
            val image = onNodeWithTag("windows-host").captureToImage().toAwtImage()
            val output = Path.of("build/screens/startup-without-course/local-reading-reopened.png")
            Files.createDirectories(output.parent)
            assertTrue(ImageIO.write(image, "png", output.toFile()))
            assertEquals(1100, image.width); assertEquals(800, image.height)
            println("NO_COURSE_BOOKS_TRACE local-import -> READ -> page2 -> new-library/controller/host -> same-book/page2; no paired course supplied; offscreen=true")
        }
    }

    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }
}
