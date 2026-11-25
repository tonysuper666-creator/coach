package com.coach.desktop.regression

import com.coach.desktop.books.BookChapterSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * TON-1847: sequential chapter dwell records every page of the chapter.
 * A jump records the landing page only, never the pages that were skipped.
 * Before ChapterDwellTracker exists, the recorded set stays empty.
 */
class R1847SequentialChapterTest {
    @Test
    fun sequentialDwellRecordsTheChapterAndAJumpDoesNotFillTheGap() {
        val span = BookChapterSpan("ch-7", "Chapter 7", 10, 13, emptyList())
        val sequential = mutableSetOf<Int>()
        val reader = openTracker()
        if (reader != null) {
            for (page in 10..13) {
                sequential += pagesOf(reader.call("onLocationChanged", span, setOf(page), false))
                sequential += pagesOf(reader.call("onTick", span, setOf(page), 8_000L))
            }
        }
        assertEquals(setOf(10, 11, 12, 13), sequential, "sequential read did not record chapter pages")

        val jumped = mutableSetOf<Int>()
        val jumper = openTracker()
        if (jumper != null) {
            jumped += pagesOf(jumper.call("onLocationChanged", span, setOf(10), false))
            jumped += pagesOf(jumper.call("onTick", span, setOf(10), 8_000L))
            jumped += pagesOf(jumper.call("onLocationChanged", span, setOf(13), true))
            jumped += pagesOf(jumper.call("onTick", span, setOf(13), 8_000L))
        }
        assertFalse(11 in jumped || 12 in jumped, "jump recorded skipped pages $jumped")
    }

    private class Tracker(private val target: Any) {
        fun call(name: String, span: BookChapterSpan, pages: Set<Int>, third: Any): List<*> {
            val method = target.javaClass.methods.first { it.name == name && it.parameterCount == 3 }
            return method.invoke(target, span, pages, third) as List<*>
        }
    }

    private fun pagesOf(rows: List<*>): Set<Int> = rows.map { row ->
        row!!.javaClass.getMethod("getSecond").invoke(row) as Int
    }.toSet()

    private fun openTracker(): Tracker? = try {
        val type = Class.forName("com.coach.desktop.books.ChapterDwellTracker")
        val ctor = type.constructors.firstOrNull { it.parameterCount == 0 }
            ?: type.constructors.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == Long::class.javaPrimitiveType }
            ?: type.constructors.firstOrNull { it.parameterCount == 2 && it.parameterTypes[0] == Long::class.javaPrimitiveType }
        val instance = when (ctor?.parameterCount) {
            0 -> ctor.newInstance()
            1 -> ctor.newInstance(8_000L)
            2 -> ctor.newInstance(8_000L, null)
            else -> null
        }
        instance?.let(::Tracker)
    } catch (_: ClassNotFoundException) {
        null
    }
}
