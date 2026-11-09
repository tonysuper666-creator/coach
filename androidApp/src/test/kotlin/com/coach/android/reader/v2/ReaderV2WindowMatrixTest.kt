package com.coach.android.reader.v2

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.ui.reader.v2.*
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Simulated content windows, not Fold8/OPPO hardware evidence. Each receipt
 * records the rendered bounds, density, fontScale and actual Android Paint size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1100dp-h1100dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderV2WindowMatrixTest(private val windowName: String, private val width: Int, private val height: Int,
    private val dark: Boolean, private val scale: Float) {
    @get:Rule val compose = createComposeRule()

    companion object {
        @JvmStatic @Parameters(name = "{0}-dark{3}-font{4}") fun cases(): List<Array<Any>> = buildList {
            for ((name, dimensions) in listOf("inner-landscape" to (933 to 704), "inner-portrait" to (704 to 933), "outer" to (411 to 900)))
                for (dark in listOf(false, true)) for (scale in listOf(1f, 1.15f))
                    add(arrayOf(name, dimensions.first, dimensions.second, dark, scale))
        }
    }

    @Test fun measuredWindowRetainsReadableTextAndAccessibleQuestionControls() {
        val app = RuntimeEnvironment.getApplication()
        val config = Configuration(app.resources.configuration).apply {
            fontScale = scale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
        var session by mutableStateOf(ReaderV2Fixture.session())
        var actualBodyPx = Float.NaN
        var actualDensity = Density(1f)
        compose.setContent {
            val supplied = Density(2.625f, app.resources.configuration.fontScale)
            CompositionLocalProvider(LocalDensity provides supplied) {
                actualDensity = LocalDensity.current
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.requiredSize(width.dp, height.dp).testTag("matrix-frame")) {
                        ReaderV2FixtureHost(ReaderWindow(width.toFloat(), height.toFloat()), session, { session = it },
                            onBodySize = { _, px -> actualBodyPx = px })
                    }
                }
            }
        }
        compose.onNodeWithTag("reader-page-0").assertIsDisplayed()
        val frame = compose.onNodeWithTag("matrix-frame").fetchSemanticsNode().boundsInRoot
        val page = compose.onNodeWithTag("reader-page-pane").fetchSemanticsNode().boundsInRoot
        assertEquals(width.toFloat(), frame.width / actualDensity.density, .5f)
        assertEquals(height.toFloat(), frame.height / actualDensity.density, .5f)
        assertEquals(2.625f, actualDensity.density, 0f)
        assertEquals(scale, actualDensity.fontScale, 0f)
        // Semantic visibility does not draw an Android Canvas in Robolectric.
        // Render before reading the independent Paint measurement.
        compose.onNodeWithTag("matrix-frame").captureToImage()
        compose.runOnIdle {
            assertTrue("Measured Android Paint size must respect the 16sp body floor: px=$actualBodyPx density=${actualDensity.density} fontScale=${actualDensity.fontScale}",
                actualBodyPx.isFinite() && with(actualDensity) { actualBodyPx.toSp().value } >= 15.95f)
        }
        if (width >= 700) {
            compose.onNodeWithTag("reader-question-pane").assertIsDisplayed()
            compose.onNodeWithTag("reader-answer-slope").assertIsDisplayed()
            compose.onNodeWithTag("reader-source-slope").assertIsDisplayed()
            if (dark) {
                val title = compose.onNodeWithText(ReaderV2Fixture.questions.first().text).captureToImage().toPixelMap()
                val ink = darkColorScheme().onBackground.toArgb()
                assertTrue("Dark question text must render with the light theme ink",
                    (0 until title.height).any { y -> (0 until title.width).any { x -> title[x, y].toArgb() == ink } })
            }
        } else compose.onNodeWithTag("reader-question-pane").assertDoesNotExist()
        if (windowName == "inner-landscape") assertTrue(page.width / actualDensity.density >= 520f)

        val dir = requireNotNull(File(requireNotNull(System.getProperty("coach.v7.output"))).parentFile).resolve("reader-v2")
        dir.mkdirs()
        val name = "synthetic-$windowName-dark$dark-font$scale"
        compose.onNodeWithTag("matrix-frame").captureRoboImage(dir.resolve("$name.png").absolutePath)
        val nativeBodyPx = actualBodyPx
        val receipt = buildJsonObject {
            put("material", "synthetic only; Robolectric API34, not hardware acceptance")
            put("contentWidthPx", frame.width); put("contentHeightPx", frame.height)
            put("actualContentWidthDp", frame.width / actualDensity.density)
            put("actualContentHeightDp", frame.height / actualDensity.density)
            put("pageViewportWidthDp", page.width / actualDensity.density)
            put("density", actualDensity.density); put("fontScale", actualDensity.fontScale)
            put("resourceFontScale", app.resources.configuration.fontScale); put("dark", dark)
            put("androidPaintBodyPx", nativeBodyPx)
            put("androidPaintBodySp", with(actualDensity) { nativeBodyPx.toSp().value })
        }
        dir.resolve("$name.json").writeText(receipt.toString())
        if (width < 700) compose.onNodeWithText("做题").performClick()
        compose.onNodeWithTag("reader-source-slope").performClick()
        compose.onNodeWithTag("reader-source-highlight").assertIsDisplayed()
        compose.onNodeWithTag("reader-return").performClick()
        compose.runOnIdle { assertEquals(ReaderDrawer.NONE, session.drawer) }
    }
}
