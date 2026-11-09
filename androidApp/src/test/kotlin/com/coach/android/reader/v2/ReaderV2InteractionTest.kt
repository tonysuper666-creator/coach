package com.coach.android.reader.v2

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.window.layout.FoldingFeature
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.coach.ui.reader.v2.*
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1100dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderV2InteractionTest {
    @get:Rule val compose = createComposeRule()
    private var session by mutableStateOf(ReaderV2Fixture.session())
    private var window by mutableStateOf(ReaderWindow(390f, 844f))
    private val bodyPixels = mutableMapOf<Int, Float>()

    private fun host() { compose.setContent { MaterialTheme {
        Box(Modifier.requiredSize(window.width.dp, window.height.dp).testTag("reader-fixture-frame")) {
            ReaderV2FixtureHost(window, session, { session = it }, onBodySize = { page, px -> bodyPixels[page] = px })
        }
    } } }

    private fun capture(name: String) {
        val base = requireNotNull(File(requireNotNull(System.getProperty("coach.v7.output"))).parentFile).resolve("reader-v2")
        base.mkdirs()
        compose.onNodeWithTag("reader-fixture-frame").captureRoboImage(base.resolve("synthetic-$name.png").absolutePath)
    }

    @Test fun oneSourceClickShowsCorrectPageInHalfSheetAndSwipeReturnsToQuestion() {
        session = session.copy(focus = ReaderFocus.QUESTIONS, position = ReaderPosition(0, 2))
        host()
        compose.onNodeWithTag("reader-answer-slope").performTextInput("向右下")
        compose.onNodeWithTag("reader-source-slope").performClick()
        compose.onNodeWithTag("reader-half-sheet").assertIsDisplayed()
        compose.onNodeWithTag("reader-source-highlight").assertIsDisplayed()
        compose.onNodeWithTag("reader-page-1").assertIsDisplayed()
        compose.onNodeWithTag("reader-answer-slope").assertIsDisplayed()
        val sheet = compose.onNodeWithTag("reader-half-sheet").fetchSemanticsNode().boundsInRoot
        assertTrue(sheet.height <= 844f / 2)
        assertTrue(sheet.top > 844f / 2)
        capture("phone-source")
        compose.onNodeWithTag("reader-sheet-handle").performTouchInput { swipeDown(startY = 1f, endY = height - 1f, durationMillis = 500) }
        compose.onNodeWithTag("reader-half-sheet").assertDoesNotExist()
        compose.onNodeWithTag("reader-answer-slope").assertTextContains("向右下")
        compose.runOnIdle { assertEquals(ReaderPosition(0, 2), session.position) }
    }

    private fun marginQuestionSourceRoundTrip(swipe: Boolean) {
        host()
        compose.onNodeWithText("下一页").performScrollTo().performClick()
        compose.onNodeWithTag("reader-margin").performScrollTo().performClick()
        compose.onNodeWithTag("reader-answer-slope").performTextInput("页边原题答案")
        compose.onNodeWithTag("reader-source-slope").performClick()
        compose.onNodeWithTag("reader-source-highlight").assertIsDisplayed()
        if (swipe) {
            compose.onNodeWithTag("reader-sheet-handle").performTouchInput {
                swipeDown(startY = 1f, endY = height - 1f, durationMillis = 500)
            }
        } else {
            compose.onNodeWithTag("reader-return").assertTextEquals("返回题目").performClick()
        }
        compose.runOnIdle {
            assertEquals(ReaderDrawer.QUESTIONS, session.drawer)
            assertEquals(ReaderFocus.READING, session.focus)
            assertEquals(ReaderPosition(1), session.position)
            assertEquals("slope", session.selectedQuestionId)
        }
        compose.onNodeWithTag("reader-half-sheet").assertIsDisplayed()
        compose.onNodeWithTag("reader-answer-slope").assertIsDisplayed().assertTextContains("页边原题答案")
        compose.onNodeWithTag("reader-source-highlight").assertDoesNotExist()
        capture(if (swipe) "margin-question-after-swipe" else "margin-question-after-return")
        compose.onNodeWithTag("reader-return").assertTextEquals("返回原页").performClick()
        compose.onNodeWithTag("reader-half-sheet").assertDoesNotExist()
        compose.onNodeWithTag("reader-page-1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(ReaderDrawer.NONE, session.drawer) }
    }

    @Test fun marginQuestionSourceButtonReturnsToTheSameQuestionDrawer() = marginQuestionSourceRoundTrip(swipe = false)

    @Test fun marginQuestionSourceSwipeReturnsToTheSameQuestionDrawer() = marginQuestionSourceRoundTrip(swipe = true)

    @Test fun columnsOverviewMarginDrawerAndUnconfirmedSourceAreReachable() {
        host()
        compose.onNodeWithTag("reader-next-column").performClick()
        compose.runOnIdle { assertEquals(2, session.position.columnId) }
        capture("phone-column")
        compose.onNodeWithText("整页").performClick()
        compose.runOnIdle { assertTrue(session.overview) }
        capture("phone-overview")
        compose.onNodeWithTag("reader-margin").performClick()
        compose.onNodeWithText("这一页还没有已核实的题目").assertIsDisplayed()
        compose.onNodeWithTag("reader-return").performClick()
        compose.onNodeWithText("做题").performClick()
        compose.onNodeWithTag("reader-source-unconfirmed").performScrollTo().performClick()
        compose.onNodeWithTag("reader-source-error").assertTextEquals("出处不止一处，无法确认")
        compose.onNodeWithTag("reader-source-highlight").assertDoesNotExist()
    }

    @Test fun resizingAndBookFlexKeepAnswerDraftAndSourceReturnLocation() {
        window = ReaderWindow(900f, 844f)
        host()
        capture("expanded")
        compose.onNodeWithTag("reader-answer-slope").performTextInput("合成答案")
        compose.onNodeWithText("收起题栏").performClick()
        compose.onNodeWithTag("reader-question-pane").assertDoesNotExist()
        capture("expanded-collapsed")
        compose.onNodeWithText("显示题栏").performClick()
        compose.runOnIdle { window = ReaderWindow(900f, 844f, ReaderFold(ReaderPane(440f, 0f, 460f, 844f), true, true)) }
        compose.onNodeWithTag("reader-source-slope").performClick()
        val left = compose.onNodeWithTag("reader-question-pane").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("reader-page-pane").fetchSemanticsNode().boundsInRoot
        assertTrue(left.right <= right.left - 20f)
        compose.onNodeWithTag("reader-half-sheet").assertDoesNotExist()
        compose.onNodeWithTag("reader-page-1").assertIsDisplayed()
        capture("book")
        compose.runOnIdle { window = ReaderWindow(900f, 844f, ReaderFold(ReaderPane(0f, 400f, 900f, 420f), false, true)) }
        compose.onNodeWithTag("reader-source-highlight").assertIsDisplayed()
        val top = compose.onNodeWithTag("reader-page-pane").fetchSemanticsNode().boundsInRoot
        val bottom = compose.onNodeWithTag("reader-question-pane").fetchSemanticsNode().boundsInRoot
        assertTrue(top.bottom <= bottom.top - 20f)
        val sourcePixels = compose.onNodeWithTag("reader-page-pane").captureToImage().toPixelMap()
        val border = Color(0xFF8C4B13)
        val widestSourceLine = (0 until sourcePixels.height).maxOf { y ->
            (0 until sourcePixels.width).count { x -> sourcePixels[x, y] == border }
        }
        assertTrue("The source sentence must use the available pane width, even in a shallow Flex pane",
            widestSourceLine >= sourcePixels.width * .9f)
        capture("flex")
        compose.runOnIdle { window = ReaderWindow(390f, 844f) }
        compose.onNodeWithTag("reader-half-sheet").assertIsDisplayed()
        compose.onNodeWithTag("reader-return").performClick()
        compose.onNodeWithText("做题").performClick()
        compose.onNodeWithTag("reader-answer-slope").assertTextContains("合成答案")
        compose.runOnIdle { assertEquals(0, session.position.pageIndex0) }
    }

    @Test fun landscapePhoneCanTurnPagesAndRecoverItsAnswer() {
        window = ReaderWindow(560f, 360f)
        session = session.answer("slope", "保留答案")
        host()
        compose.onNodeWithText("下一页").performScrollTo().performClick()
        compose.onNodeWithTag("reader-page-1").assertIsDisplayed()
        capture("phone-landscape")
        compose.onNodeWithText("上一页").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ReaderPosition(0), session.position); assertEquals("保留答案", session.answers["slope"]) }
    }

    @Test fun runtimeHingePixelsAreTranslatedPastContentInsetsBeforeSplitting() {
        val feature = object : FoldingFeature {
            override val bounds = android.graphics.Rect(880, 0, 920, 1800)
            override val orientation = FoldingFeature.Orientation.VERTICAL
            override val state = FoldingFeature.State.HALF_OPENED
            override val isSeparating = true
            override val occlusionType = FoldingFeature.OcclusionType.FULL
        }
        val mapped = readerFold(feature, 2f, Offset(0f, 48f))
        assertEquals(ReaderPane(440f, -24f, 460f, 876f), mapped.bounds)
        val split = readerPanes(ReaderWindow(900f, 844f, mapped), true)
        assertEquals(ReaderArrangement.BOOK, split.arrangement)
        assertEquals(440f, split.questions!!.right)
        assertEquals(460f, split.page.left)
    }

    @Test fun letterboxedColumnCannotLeakAdjacentColumnIntoItsMargins() {
        compose.setContent { Box(Modifier.requiredSize(400.dp, 800.dp).background(Color.White).testTag("crop-probe")) {
            // A genuinely readable height-fit crop still has letterbox margins.
            val readableCrop = ReaderV2Fixture.pages.first().copy(trim = ReaderV2Fixture.columns[1].region,
                columns = emptyList(), bodyFontSize = 48f)
            ReaderV2Page(readableCrop, null, false, null, 0, {}, pageContent = {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color.Red)
                    drawRect(Color.Blue, Offset(size.width * .54f, 0f), Size(size.width * .38f, size.height))
                }
            })
        } }
        val pixels = compose.onNodeWithTag("crop-probe").captureToImage().toPixelMap()
        assertEquals(Color.White, pixels[5, 400])
        assertEquals(Color.Blue, pixels[200, 400])
        assertEquals(Color.White, pixels[395, 400])
    }

    private fun assertReadableColumn() {
        val pixels = compose.onNodeWithTag("reader-page-0").captureToImage().toPixelMap()
        val ruler = Color(0xFF2F6A4B)
        val widest = (0 until pixels.height).maxOf { y -> (0 until pixels.width).count { x -> pixels[x, y] == ruler } }
        assertTrue("Reading column must fill the actual page viewport", widest >= pixels.width * .9f)
        compose.runOnIdle { assertTrue("Android Paint body must remain at least 16sp at mdpi/fontScale=1", bodyPixels.getValue(0) >= 15.95f) }
    }

    @Test fun wideLandscapePhoneReadsAtColumnWidthBeforeAndAfterNextColumn() {
        window = ReaderWindow(914f, 380f)
        host()
        assertReadableColumn()
        capture("phone-landscape-914x380-reading")
        compose.onNodeWithTag("reader-next-column").performClick()
        compose.runOnIdle { assertEquals(2, session.position.columnId) }
        assertReadableColumn()
    }

    @Test fun tabletopOrdinaryReadingUsesWidthAndVerticalScroll() {
        window = ReaderWindow(900f, 844f, ReaderFold(ReaderPane(0f, 400f, 900f, 420f), false, true))
        host()
        compose.onNodeWithTag("reader-half-sheet").assertDoesNotExist()
        compose.onNodeWithTag("reader-source-highlight").assertDoesNotExist()
        assertReadableColumn()
        capture("flex-reading")
        compose.onNodeWithTag("reader-next-column").performClick()
        compose.runOnIdle { assertEquals(2, session.position.columnId) }
        assertReadableColumn()
    }

    @Test fun expandingAQuestionDrawerDoesNotConsumeAnInvisibleBackPress() {
        host()
        compose.onNodeWithTag("reader-margin").performClick()
        compose.onNodeWithTag("reader-half-sheet").assertIsDisplayed()
        compose.runOnIdle { window = ReaderWindow(900f, 844f) }
        compose.onNodeWithTag("reader-half-sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(ReaderDrawer.NONE, session.drawer)
            assertFalse(session.handlesReaderBack(ReaderArrangement.EXPANDED))
        }
    }

    @Test fun aFullWidthFigureCanPanWithoutShrinkingBodyText() {
        compose.setContent { Box(Modifier.requiredSize(390.dp, 360.dp).testTag("figure-probe")) {
            val fullWidth = ReaderV2Fixture.pages.first().copy(trim = ReaderRegion.Full, columns = emptyList())
            ReaderV2Page(fullWidth, null, true, null, 0, {}, pageContent = {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color.Red)
                    drawRect(Color.Blue, Offset(size.width / 2, 0f), Size(size.width / 2, size.height))
                }
            })
        } }
        assertEquals(Color.Red, compose.onNodeWithTag("figure-probe").captureToImage().toPixelMap()[100, 100])
        compose.onNodeWithTag("reader-page-0").performTouchInput { swipeLeft() }
        assertEquals(Color.Blue, compose.onNodeWithTag("figure-probe").captureToImage().toPixelMap()[100, 100])
    }
}
