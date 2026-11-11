package com.coach.android

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.TextRunShaper
import android.util.TypedValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import com.coach.shared.ui.R
import com.coach.ui.CoachTheme
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.V7Fonts
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V7FontLoadingTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()

    @Suppress("DEPRECATION")
    private fun context(night: Boolean = false, redirectReading: Boolean = false): Context {
        val config = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val resources = object : Resources(app.assets, app.resources.displayMetrics, config) {
            override fun getValue(id: Int, outValue: TypedValue, resolveRefs: Boolean) {
                super.getValue(if (redirectReading && id == R.font.literata) R.font.ibm_plex_sans else id,
                    outValue, resolveRefs)
            }
        }
        return object : ContextWrapper(app) {
            override fun getResources(): Resources = resources
        }
    }

    @Test fun recreatingThemesWithFreshResourcesReusesFontsAndKeepsAllWeightsReadable() {
        val generation = mutableStateOf(0)
        val currentContext = mutableStateOf(context())
        lateinit var fonts: V7Fonts
        compose.setContent {
            CompositionLocalProvider(LocalContext provides currentContext.value,
                LocalConfiguration provides currentContext.value.resources.configuration) {
                key(generation.value) { CoachTheme { fonts = LocalV7Fonts.current } }
            }
        }
        compose.waitForIdle()
        val original = fonts
        repeat(3) { index ->
            compose.runOnIdle {
                currentContext.value = context(night = index % 2 == 0)
                generation.value++
            }
            compose.runOnIdle { assertSame("Recreation must reuse the loaded font data", original, fonts) }
        }
        compose.runOnIdle {
            val resolver = createFontFamilyResolver(app)
            for (family in listOf(fonts.reading, fonts.ui, fonts.note, fonts.numbers)) {
                // Compose wraps a supplied native Typeface without changing its
                // style. Ask Android to select each weight from that native family.
                val base = resolver.resolve(family, FontWeight.Normal, FontStyle.Normal, FontSynthesis.None).value as Typeface
                for (weight in listOf(300, 370, 400, 500, 600, 700)) {
                    val face = Typeface.create(base, weight, false)
                    assertEquals(weight, face.weight)
                    val paint = Paint().apply { typeface = face }
                    assertTrue("Latin must remain readable at $weight", paint.hasGlyph("A"))
                    assertTrue("CJK must remain readable at $weight", paint.hasGlyph("学"))
                    val glyphs = TextRunShaper.shapeTextRun("A学", 0, 2, 0, 2, 0f, 0f, false, paint)
                    assertTrue(glyphs.glyphCount > 0)
                    for (index in 0 until glyphs.glyphCount) {
                        assertEquals("The selected Latin/CJK font must retain its weight", weight, glyphs.getFont(index).style.weight)
                    }
                }
            }
        }
    }

    @Test fun changedResolvedFontFileDoesNotReuseAStaleTheme() {
        val currentContext = mutableStateOf(context())
        lateinit var fonts: V7Fonts
        compose.setContent {
            CompositionLocalProvider(LocalContext provides currentContext.value,
                LocalConfiguration provides currentContext.value.resources.configuration) {
                CoachTheme { fonts = LocalV7Fonts.current }
            }
        }
        compose.waitForIdle()
        val original = fonts
        compose.runOnIdle { currentContext.value = context(redirectReading = true) }
        compose.runOnIdle { assertNotSame("A changed resource must reload its font chain", original, fonts) }
        compose.runOnIdle { currentContext.value = context() }
        compose.runOnIdle { assertSame("Returning to the same files must reuse their fonts", original, fonts) }
    }
}
