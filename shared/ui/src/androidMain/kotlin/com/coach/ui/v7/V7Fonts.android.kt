package com.coach.ui.v7

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily as AndroidFontFamily
import android.graphics.fonts.FontStyle
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font as ComposeFont
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.coach.shared.ui.R

@Composable
internal actual fun rememberV7Fonts(): V7Fonts {
    val resources = LocalContext.current.resources
    return remember(resources) {
        fun chain(latin: Int, cjk: Int, fallback: String): FontFamily {
            fun family(id: Int): AndroidFontFamily {
                val weights = listOf(300, 370, 400, 500, 600, 700)
                val fonts = weights.map { weight ->
                    Font.Builder(resources, id).setWeight(weight)
                        .setFontVariationSettings("'wght' $weight").build()
                }
                return AndroidFontFamily.Builder(fonts.first()).apply { fonts.drop(1).forEach(::addFont) }.build()
            }
            return FontFamily(Typeface.CustomFallbackBuilder(family(latin))
                .addCustomFallback(family(cjk)).setSystemFallback(fallback)
                .setStyle(FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT)).build())
        }
        V7Fonts(chain(R.font.literata, R.font.noto_serif_sc, "serif"),
            chain(R.font.ibm_plex_sans, R.font.noto_sans_sc, "sans-serif"),
            chain(R.font.ibm_plex_sans, R.font.lxgw_wenkai, "serif"),
            chain(R.font.ibm_plex_mono, R.font.noto_sans_sc, "sans-serif"),
            FontFamily(ComposeFont(R.font.literata, weight = FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300)))))
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
