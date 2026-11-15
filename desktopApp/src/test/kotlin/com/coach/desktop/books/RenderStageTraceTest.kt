package com.coach.desktop.books

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** 归因用的阶段戳：预热各阶段按顺序、单调；卡顿观察不改变被包住调用的结果与异常。 */
class RenderStageTraceTest {
    @Test fun warmupRecordsEveryStageInOrderWithMonotonicNanoTimes() {
        TextbookLibrary(Files.createTempDirectory("coach-stage-"))
        assertTrue(PdfWarmup.done.get(60, TimeUnit.SECONDS) >= 0)
        val names = PdfWarmup.stages.map { it.substringBefore(" ") }
        assertEquals(listOf("begin", "font-ctor-enter", "font-ctor-exit", "save-enter", "save-exit",
            "loadPDF-enter", "loadPDF-exit", "renderImage-enter", "renderImage-exit"), names)
        val nanos = PdfWarmup.stages.map { it.substringAfter("nano=").substringBefore(" ").toLong() }
        assertEquals(nanos.sorted(), nanos)
        assertTrue(PdfWarmup.stages.all { it.contains("thread=pdf-warmup") })
    }

    @Test fun stallWatchPassesResultsAndExceptionsThroughUnchanged() {
        assertEquals(42, StallWatch.watch("x") { 42 })
        assertFailsWith<IllegalStateException> { StallWatch.watch<Unit>("x") { throw IllegalStateException("boom") } }
    }
}
