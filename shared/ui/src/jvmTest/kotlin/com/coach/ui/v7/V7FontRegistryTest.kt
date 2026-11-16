package com.coach.ui.v7

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.paragraph.FontCollection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V7FontRegistryTest {
    /** If a Compose upgrade moves its font cache, this fails instead of Chinese silently turning back into the system sans. */
    @Test fun chineseReadingFaceIsRegisteredPerWeight() {
        val resolver = createFontFamilyResolver()
        assertTrue(V7FontRegistry.register(resolver))
        val loader = resolver.javaClass.getDeclaredField("platformFontLoader").apply { isAccessible = true }.get(resolver)
        val cache = (loader.javaClass.getDeclaredField("fontCache\$delegate").apply { isAccessible = true }.get(loader) as Lazy<*>).value!!
        val fonts = cache.javaClass.getDeclaredField("fonts").apply { isAccessible = true }.get(cache) as FontCollection
        val aliases = arrayOf(V7FontRegistry.alias("literata", reading.only), V7FontRegistry.alias("noto_serif_sc", reading.only))
        val loaded = resolver.resolve(V7FontRegistry.family(reading), FontWeight.W700).value!!
        val resolved = loaded.javaClass.getDeclaredField("aliases").apply { isAccessible = true }.get(loaded)
        assertEquals(aliases.toList(), resolved, "Compose hands Skia the whole chain")
        for (weight in listOf(300, 400, 700)) {
            val faces = fonts.findTypefaces(aliases, FontStyle.NORMAL.withWeight(weight)).filterNotNull()
            assertEquals(2, faces.size, "both faces of the reading chain at $weight")
            assertTrue(faces[1].familyName.startsWith("Noto Serif SC"), faces[1].familyName)
            assertEquals(weight, faces[1].fontStyle.weight)
            assertTrue(faces[1].getUTF32Glyph('细'.code) != 0.toShort(), "Noto Serif SC has 细")
            assertEquals(0.toShort(), faces[0].getUTF32Glyph('细'.code), "Literata lacks 细, so Skia falls back to Noto")
        }
    }
}
