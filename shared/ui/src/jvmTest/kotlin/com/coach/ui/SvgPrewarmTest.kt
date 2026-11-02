package com.coach.ui

import com.coach.ui.math.MathSvgDocument
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** TON-1142: formula SVGs are parsed off the UI thread ahead of the block that seals with them. */
class SvgPrewarmTest {
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 -750 1000 900"><g transform="translate(10,0) scale(1,1)"><path d="M0 0L100 0L100 100Z"/></g></svg>"""

    @Test fun prewarmedDocumentIsTheOneParseReturns() { runBlocking {
        val key = "prewarm-a" to svg
        MathSvgDocument.prewarm(listOf(key))
        val first = MathSvgDocument.parse(key.first, key.second)
        assertSame(first, MathSvgDocument.parse(key.first, key.second))
        assertEquals(1f, first.widthEm)
        assertEquals(listOf("translate" to listOf(10f, 0f), "scale" to listOf(1f, 1f)), first.root.children.single().transforms())
    } }

    @Test fun unparseableSvgIsLeftForParseToReport() { runBlocking {
        val broken = "prewarm-broken" to "<svg viewBox=\"0 0 10 10\"><circle/></svg>"
        MathSvgDocument.prewarm(listOf(broken, "prewarm-b" to svg))
        assertFailsWith<IllegalArgumentException> { MathSvgDocument.parse(broken.first, broken.second) }
        assertNotNull(MathSvgDocument.parse("prewarm-b", svg))
    } }
}
