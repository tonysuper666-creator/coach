package com.coach.ui.v7

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.GenericFontFamily
import androidx.compose.ui.text.platform.Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.TypefaceFontProvider
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

private val weights = listOf(300, 370, 400, 500, 600, 700)

/** Same chains as Android: Latin face first, Chinese face second, then the system. */
internal class Chain(val latin: String, val cjk: String, val only: List<Int> = weights)

internal val reading = Chain("literata", "noto_serif_sc")
private val ui = Chain("ibm_plex_sans", "noto_sans_sc")
private val note = Chain("ibm_plex_sans", "lxgw_wenkai")
private val numbers = Chain("ibm_plex_mono", "noto_sans_sc")
private val display = Chain("literata", "noto_serif_sc", listOf(300))

@Composable
internal actual fun rememberV7Fonts(): V7Fonts {
    val resolver = LocalFontFamilyResolver.current
    return remember(resolver) {
        V7FontRegistry.fonts(resolver) ?: V7Fonts(resourceFamily(reading), resourceFamily(ui), resourceFamily(note),
            resourceFamily(numbers), resourceFamily(display))
    }
}

/** Fallback when the registry cannot reach Compose's font cache: Latin is right, Chinese falls to the system face. */
private fun resourceFamily(chain: Chain) = FontFamily(listOf(chain.latin, chain.cjk).flatMap { file ->
    chain.only.map { Font("font/$file.ttf", FontWeight(it), FontStyle.Normal, FontVariation.Settings(FontVariation.weight(it))) }
})

/**
 * Compose Desktop resolves a family of resource fonts to a single face per weight, so Skia never
 * tries the Chinese face for glyphs the Latin face lacks. Here every face is registered once under
 * its own family name, one clone per weight, in the same font cache Compose draws with, and each
 * chain is added to Compose's generic family table, the way `FontFamily.Serif` lists its system
 * faces. Skia then gets the whole chain: it picks the weight inside each face and falls back glyph
 * by glyph from the Latin face to the Chinese one.
 */
internal object V7FontRegistry {
    private val done: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val faces = mutableMapOf<String, Typeface?>()

    fun alias(file: String, only: List<Int>) = "Coach V7 $file" + if (only == weights) "" else " " + only.joinToString("-")

    fun name(chain: Chain) = "coach-v7-" + alias(chain.latin, chain.only) + "+" + chain.cjk

    /** Skia gets both aliases, Latin first, and matches the requested weight inside each one. */
    fun family(chain: Chain): FontFamily = name(chain).let {
        GenericFontFamily::class.java.getDeclaredConstructor(String::class.java, String::class.java)
            .apply { isAccessible = true }.newInstance(it, it)
    }

    /** null when Compose's internals moved; the caller then keeps the resource fonts. */
    fun fonts(resolver: FontFamily.Resolver): V7Fonts? = runCatching {
        check(register(resolver))
        V7Fonts(family(reading), family(ui), family(note), family(numbers), family(display))
    }.getOrElse {
        System.err.println("V7 fonts: falling back to resource fonts, Chinese uses the system face ($it)")
        null
    }

    fun register(resolver: FontFamily.Resolver): Boolean = runCatching {
        val cache = fontCache(resolver)
        if (cache in done) return true
        val provider = field(cache, "fontProvider") as TypefaceFontProvider
        @Suppress("UNCHECKED_CAST") val registered = field(cache, "registered") as MutableSet<String>
        val collection = field(cache, "fonts") as FontCollection
        synchronized(cache) {
            for (chain in listOf(reading, ui, note, numbers, display)) for (file in listOf(chain.latin, chain.cjk)) {
                val alias = alias(file, chain.only)
                if (alias in registered) continue
                val base = face(file) ?: return false
                val variable = base.variationAxes?.any { it.tag == "wght" } == true
                if (variable) chain.only.forEach { provider.registerTypeface(base.makeClone(arrayOf(org.jetbrains.skia.FontVariation("wght", it.toFloat()))), alias) }
                else provider.registerTypeface(base, alias)
                registered += alias
            }
            collection.paragraphCache.reset()
        }
        @Suppress("UNCHECKED_CAST")
        val generic = (staticField("androidx.compose.ui.text.platform.PlatformFont_skikoKt", "GenericFontFamiliesMapping\$delegate") as Lazy<*>).value as MutableMap<String, List<String>>
        synchronized(generic) {
            for (chain in listOf(reading, ui, note, numbers, display))
                generic[name(chain)] = listOf(alias(chain.latin, chain.only), alias(chain.cjk, chain.only))
        }
        done += cache
        true
    }.getOrElse {
        System.err.println("V7 fonts: cannot register faces ($it)")
        false
    }

    private fun face(file: String): Typeface? = synchronized(faces) {
        faces.getOrPut(file) {
            val bytes = V7FontRegistry::class.java.classLoader.getResourceAsStream("font/$file.ttf")?.use { it.readBytes() }
            bytes?.let { FontMgr.default.makeFromData(Data.makeFromBytes(it)) }
        }
    }

    private fun fontCache(resolver: FontFamily.Resolver): Any {
        val loader = field(resolver, "platformFontLoader")
        return (field(loader, "fontCache\$delegate") as Lazy<*>).value!!
    }

    private fun staticField(className: String, name: String): Any =
        Class.forName(className).getDeclaredField(name).apply { isAccessible = true }.get(null)

    private fun field(target: Any, name: String): Any =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}

/**
 * Follows Windows "Animation effects" (SPI_GETCLIENTAREAANIMATION, stored as bit 1 of
 * byte 4 in HKCU\Control Panel\Desktop\UserPreferencesMask). `-Dcoach.reducedMotion=true|false`
 * overrides it. Reads the setting only; never changes it.
 */
@Composable
internal actual fun rememberReducedMotion(): Boolean {
    val forced = System.getProperty("coach.reducedMotion")?.toBooleanStrictOrNull()
    if (forced != null) return forced
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return false
    var reduced by remember { mutableStateOf(WindowsMotion.last) }
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { WindowsMotion.read() }?.let { reduced = it; WindowsMotion.last = it }
            delay(30_000)
        }
    }
    return reduced
}

internal object WindowsMotion {
    @Volatile var last = false

    fun read(): Boolean? = runCatching {
        val process = ProcessBuilder("reg", "query", "HKCU\\Control Panel\\Desktop", "/v", "UserPreferencesMask")
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); return null }
        parse(output)
    }.getOrNull()

    /** null when the value is missing or malformed: keep animations rather than guess. */
    fun parse(regOutput: String): Boolean? {
        val hex = Regex("UserPreferencesMask\\s+REG_BINARY\\s+([0-9A-Fa-f]+)").find(regOutput)?.groupValues?.get(1) ?: return null
        if (hex.length < 10) return null
        return hex.substring(8, 10).toInt(16) and 0x02 == 0
    }
}
