package com.coach.ui.v7

import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily as AndroidFontFamily
import android.graphics.fonts.FontStyle
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font as ComposeFont
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.coach.shared.ui.R
import java.util.WeakHashMap

@Composable
internal actual fun rememberV7Fonts(): V7Fonts {
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    return remember(resources, configuration) { V7FontCache.get(resources) }
}

/** Cache font data, never an Activity or Resources. Asset managers are weak keys. */
private object V7FontCache {
    private data class Source(val cookie: Int, val path: String)
    private class Entry {
        val families = mutableMapOf<Source, AndroidFontFamily>()
        val themes = mutableMapOf<List<Source>, V7Fonts>()
    }
    private val entries = WeakHashMap<AssetManager, Entry>()
    private val ids = listOf(R.font.literata, R.font.noto_serif_sc, R.font.ibm_plex_sans,
        R.font.noto_sans_sc, R.font.lxgw_wenkai, R.font.ibm_plex_mono)

    @Synchronized
    fun get(resources: Resources): V7Fonts {
        // Resolve the actual files: unrelated configuration changes reuse the same
        // data, while qualified resources and overlays get their own font families.
        val sources = ids.map { id ->
            val value = TypedValue()
            resources.getValue(id, value, true)
            Source(value.assetCookie, requireNotNull(value.string).toString())
        }
        val entry = entries.getOrPut(resources.assets) { Entry() }
        return entry.themes.getOrPut(sources) {
            fun family(id: Int): AndroidFontFamily = entry.families.getOrPut(sources[ids.indexOf(id)]) {
                // The copy constructor shares the buffer/native font data. Opening
                // the resource for every weight retains six whole-file buffers.
                val source = Font.Builder(resources, id).build()
                val fonts = listOf(300, 370, 400, 500, 600, 700).map { weight ->
                    Font.Builder(source).setWeight(weight).setFontVariationSettings("'wght' $weight").build()
                }
                AndroidFontFamily.Builder(fonts.first()).apply { fonts.drop(1).forEach(::addFont) }.build()
            }
            fun chain(latin: Int, cjk: Int, fallback: String): FontFamily =
                FontFamily(Typeface.CustomFallbackBuilder(family(latin))
                    .addCustomFallback(family(cjk)).setSystemFallback(fallback)
                    .setStyle(FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT)).build())

            V7Fonts(chain(R.font.literata, R.font.noto_serif_sc, "serif"),
                chain(R.font.ibm_plex_sans, R.font.noto_sans_sc, "sans-serif"),
                chain(R.font.ibm_plex_sans, R.font.lxgw_wenkai, "serif"),
                chain(R.font.ibm_plex_mono, R.font.noto_sans_sc, "sans-serif"),
                FontFamily(ComposeFont(R.font.literata, weight = FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300)))))
        }
    }
}

/** Observe the live setting as well as cold start; never change the user's setting. */
@Composable
internal actual fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun disabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember(resolver) { mutableStateOf(disabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = disabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
