package com.coach.android.reader.v2

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.coach.ui.reader.v2.*
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlinx.serialization.json.*
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
class ReaderV2MissingMetricsInteractionTest {
    @get:Rule val compose = createComposeRule()
    private var session by mutableStateOf(ReaderV2Fixture.session())
    private val paints = mutableMapOf<Int, Float>()
    private var density = 1f
    private val output get() = requireNotNull(File(requireNotNull(System.getProperty("coach.v7.output"))).parentFile).resolve("reader-v2/missing-metrics").apply { mkdirs() }

    private fun exercise(name: String, window: ReaderWindow, invalidMetric: Float? = null) {
        val page = ReaderV2MissingMetricsTest.doclingPage().copy(bodyFontSize = invalidMetric)
        compose.setContent { MaterialTheme {
            density = LocalDensity.current.density
            Box(Modifier.requiredSize(window.width.dp, window.height.dp).testTag("unknown-metrics-frame")) {
                ReaderV2FixtureHost(window, session, { session = it }, onBodySize = { index, px -> paints[index] = px },
                    pages = listOf(page, page.copy(pageIndex0 = 1)))
            }
        } }
        fun record(stage: String) {
            compose.onNodeWithTag("unknown-metrics-frame").captureToImage()
            val bodyPx = paints.getValue(session.source?.pageIndex0 ?: session.position.pageIndex0)
            // This observation is specific to the invented .024-page-width glyphs,
            // not a measured-font assertion about the unknown Docling document.
            val pageWidthDp = bodyPx / .024f / density
            output.resolve("$name-$stage.json").writeText(buildJsonObject {
                put("material", "synthetic only; no real-text readability claim")
                put("metadataBodyFontSize", JsonNull)
                put("inputBodyMetric", invalidMetric?.toString() ?: "absent")
                put("windowWidthDp", window.width); put("windowHeightDp", window.height)
                put("density", density); put("syntheticPaintBodyPx", bodyPx)
                put("renderedPageWidthDp", pageWidthDp)
                put("renderedPageLongSideDp", pageWidthDp * 800f / 600f)
            }.toString())
            compose.onNodeWithTag("unknown-metrics-frame").captureRoboImage(output.resolve("$name-$stage.png").absolutePath)
            assertTrue("Finite scale in $name/$stage", pageWidthDp.isFinite())
            assertTrue("Unknown metrics must not magnify the fixture to 45..57dp in $name/$stage", pageWidthDp <= 900.5f)
        }
        record("default")
        compose.onNodeWithTag("reader-next-column").performClick()
        record("column")
        compose.onNodeWithText("下一页").performScrollTo().performClick()
        val compact = readerPanes(window, true).arrangement == ReaderArrangement.COMPACT
        if (compact) compose.onNodeWithTag("reader-margin").performScrollTo().performClick()
        compose.onNodeWithTag("reader-answer-slope").performTextInput("缺省度量的原题答案")
        compose.onNodeWithTag("reader-source-slope").performClick()
        compose.onNodeWithTag("reader-source-highlight").assertIsDisplayed()
        record("source")
        if (compact) {
            compose.onNodeWithTag("reader-return").assertTextEquals("返回题目")
            compose.onNodeWithTag("reader-sheet-handle").performTouchInput {
                swipeDown(startY = 1f, endY = height - 1f, durationMillis = 500)
            }
        } else compose.onNodeWithTag("reader-return").performClick()
        compose.onNodeWithTag("reader-answer-slope").assertIsDisplayed().assertTextContains("缺省度量的原题答案")
        compose.runOnIdle {
            assertEquals(if (compact) ReaderDrawer.QUESTIONS else ReaderDrawer.NONE, session.drawer)
            assertEquals(ReaderPosition(1), session.position)
            assertNull(session.source)
        }
    }

    @Test fun ordinaryPhoneWithoutMetricsCanReadAndReturnToItsMarginQuestion() = exercise("phone", ReaderWindow(411f, 900f))
    @Test fun landscapeDoclingWithoutMetricsDoesNotMagnifyColumns() = exercise("landscape", ReaderWindow(914f, 380f))
    @Test fun flexDoclingWithoutMetricsUsesBoundedReadingAndSourceGeometry() = exercise("flex", ReaderWindow(900f, 844f,
        ReaderFold(ReaderPane(0f, 400f, 900f, 420f), false, true)))
    @Test fun flexWithInvalidMetricsUsesTheSameBoundedReadingAndSourcePath() = exercise("flex-invalid", ReaderWindow(900f, 844f,
        ReaderFold(ReaderPane(0f, 400f, 900f, 420f), false, true)), Float.NaN)
}
