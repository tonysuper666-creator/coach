package com.coach.desktop.books

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.coach.desktop.WindowsTextbookHost
import com.coach.ui.adaptive.Hinge
import com.coach.ui.adaptive.HingeAxis
import com.coach.ui.v7.LocalV7ReducedMotion
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookHalfFoldLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun fixtureController(): TextbookController {
        val context = compose.activity.applicationContext
        PDFBoxResourceLoader.init(context)
        Class.forName("org.sqlite.JDBC")
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "android-halffold-")
        val sourceFile = root.resolve("HalfFoldSample.md").also { file ->
            val body = (1..20).joinToString("\n\n") { n ->
                "Paragraph $n. Synthetic half-fold textbook paragraph for verifying book and tabletop hinge splits without overlapping."
            }
            Files.writeString(file, "# 第一节 折叠布局样本\n\n$body")
        }
        val library = TextbookLibrary(root.resolve("library"))
        val book = library.importBook(sourceFile)
        library.save(book.copy(promptSeen = true))
        return TextbookController(library).also { runBlocking { it.load() } }
    }

    @Test fun geometrySplitsAlignToRootHingeAndRejectNarrowWindows() {
        val v = bookVerticalSplit(containerWidthDp = 628f, containerHeightDp = 900f, containerLeftDp = 76f, hinge = Hinge(352f, 24f, HingeAxis.Vertical))
        assertNotNull(v)
        assertEquals(276f, v!!.leftDp, 0.01f)
        assertEquals(24f, v.gapDp, 0.01f)
        assertNull(bookVerticalSplit(containerWidthDp = 411f, containerHeightDp = 900f, containerLeftDp = 0f, hinge = Hinge(205f, 0f, HingeAxis.Vertical)))
        assertNull(bookVerticalSplit(containerWidthDp = 704f, containerHeightDp = 900f, containerLeftDp = 0f, hinge = null))

        val h = bookTabletopSplit(containerWidthDp = 900f, containerHeightDp = 620f, containerTopDp = 80f, hinge = Hinge(352f, 16f, HingeAxis.Horizontal))
        assertNotNull(h)
        assertEquals(272f, h!!.topDp, 0.01f)
        assertEquals(16f, h.gapDp, 0.01f)
        assertNull(bookTabletopSplit(containerWidthDp = 411f, containerHeightDp = 800f, containerTopDp = 80f, hinge = Hinge(400f, 0f, HingeAxis.Horizontal)))
    }

    @Test fun shelfAndReaderKeepBookHalfFoldClearOfVerticalHinge() {
        val controller = fixtureController()
        compose.setContent {
            CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                Box(Modifier.requiredSize(704.dp, 933.dp)) {
                    WindowsTextbookHost(controller, hinge = Hinge(352f, widthDp = 24f, axis = HingeAxis.Vertical)) { null }
                }
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("shelf-half-fold-left").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()

        val shelfLeft = compose.onNodeWithTag("shelf-half-fold-left").getUnclippedBoundsInRoot()
        val shelfRight = compose.onNodeWithTag("shelf-half-fold-right").getUnclippedBoundsInRoot()
        assertTrue("shelf left overlaps hinge: ${shelfLeft.right}", shelfLeft.right.value <= 352.5f)
        assertTrue("shelf right overlaps hinge: ${shelfRight.left}", shelfRight.left.value >= 375.5f)

        runBlocking { controller.learn(LearningPath.READ) }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("book-half-fold-left").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val readerLeft = compose.onNodeWithTag("book-half-fold-left").getUnclippedBoundsInRoot()
        val readerRight = compose.onNodeWithTag("book-half-fold-right").getUnclippedBoundsInRoot()
        assertTrue("reader left overlaps hinge: ${readerLeft.right}", readerLeft.right.value <= 352.5f)
        assertTrue("reader right overlaps hinge: ${readerRight.left}", readerRight.left.value >= 375.5f)
        compose.onNodeWithTag("ask-book-passage").assertIsDisplayed()

        runBlocking {
            controller.learn(LearningPath.QUESTION_FIRST)
            controller.enableLearning()
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("learning-half-fold-left").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val learnLeft = compose.onNodeWithTag("learning-half-fold-left").getUnclippedBoundsInRoot()
        val learnRight = compose.onNodeWithTag("learning-half-fold-right").getUnclippedBoundsInRoot()
        assertTrue("learning left overlaps hinge: ${learnLeft.right}", learnLeft.right.value <= 352.5f)
        assertTrue("learning right overlaps hinge: ${learnRight.left}", learnRight.left.value >= 375.5f)
    }

    @Test fun tabletopHalfFoldKeepsContentAboveHingeAndControlsBelow() {
        val controller = fixtureController()
        runBlocking { controller.learn(LearningPath.READ) }
        compose.setContent {
            CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                Box(Modifier.requiredSize(933.dp, 704.dp)) {
                    WindowsTextbookHost(controller, hinge = Hinge(352f, widthDp = 24f, axis = HingeAxis.Horizontal)) { null }
                }
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("book-tabletop-content").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()

        val content = compose.onNodeWithTag("book-tabletop-content").getUnclippedBoundsInRoot()
        val controls = compose.onNodeWithTag("book-tabletop-controls").getUnclippedBoundsInRoot()
        assertTrue("tabletop content crosses hinge: ${content.bottom}", content.bottom.value <= 352.5f)
        assertTrue("tabletop controls cross hinge: ${controls.top}", controls.top.value >= 375.5f)
        compose.onNodeWithTag("next-page").assertIsDisplayed()

        runBlocking {
            controller.learn(LearningPath.QUESTION_FIRST)
            controller.enableLearning()
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("learning-tabletop-content").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val learnContent = compose.onNodeWithTag("learning-tabletop-content").getUnclippedBoundsInRoot()
        val learnControls = compose.onNodeWithTag("learning-tabletop-controls").getUnclippedBoundsInRoot()
        assertTrue("learning tabletop crosses hinge: ${learnContent.bottom}", learnContent.bottom.value <= 352.5f)
        assertTrue("learning controls cross hinge: ${learnControls.top}", learnControls.top.value >= 375.5f)
    }
}
