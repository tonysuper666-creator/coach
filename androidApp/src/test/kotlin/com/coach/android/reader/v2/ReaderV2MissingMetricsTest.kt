package com.coach.android.reader.v2

import com.coach.ui.reader.v2.*
import org.junit.Assert.*
import org.junit.Test

class ReaderV2MissingMetricsTest {
    companion object {
        // Same schema/geometry as Docling v1; intentionally no native glyph measurement.
        fun doclingPage(): ReaderPageLayout = ReaderLayoutV1.decode("""
            {"schema_version":1,"doc_sha256":"${ReaderV2Fixture.Sha256}",
             "page_index":0,"page_number":1,"page_base":1,"mediabox":[0,0,600,800],
             "status":"ok","rotation":0,"geometry":"page_space","reading_order_status":"ok",
             "coord_space":"pdf-points; origin = MediaBox top-left; x right, y down; unrotated page space",
             "blocks":[{"id":"invented-left","column":1,"reading_order":0,"kind":"text","bbox":[48,96,276,704]},
                       {"id":"invented-right","column":2,"reading_order":1,"kind":"text","bbox":[324,96,552,704]}]}
        """.trimIndent(), ReaderV2Fixture.Sha256)
    }

    private fun assertBounded(page: ReaderPageLayout, density: Float = 1f) {
        for ((width, height, compact) in listOf(Triple(411f, 770f, true), Triple(714f, 250f, false),
            Triple(900f, 270f, false), Triple(733f, 574f, false))) {
            for (column in listOf(null, 1, 2)) {
                val plan = readerPagePlan(page, column, compact, null, false, width * density, height * density,
                    density, 16f * density, 22f * density)
                assertNull("Unknown native type size must stay unknown", plan.bodySizePx)
                val v = plan.viewport
                assertTrue(listOf(v.pageWidth, v.pageHeight, v.x, v.y, v.contentWidth, v.contentHeight).all { it.isFinite() })
                assertTrue("Missing metrics must not enlarge a page beyond a 1200dp long side", maxOf(v.pageWidth, v.pageHeight) <= 1200.01f * density)
                assertTrue(v.pageWidth > 0 && v.pageHeight > 0)
                if (column != null) {
                    assertEquals(column, plan.columnId)
                    assertTrue(plan.region.left * v.pageWidth + v.x >= 16f * density - .01f)
                    assertTrue(plan.region.right * v.pageWidth + v.x <= width * density - 16f * density + .01f)
                } else if (!compact) assertNull("Unknown does not prove a need to magnify a column", plan.columnId)
            }
        }
    }

    @Test fun doclingDefaultMetadataCannotTriggerUnboundedColumnMagnification() {
        val page = doclingPage()
        assertNull(page.bodyFontSize)
        assertEquals(listOf(1, 2), page.columns.map { it.id })
        assertBounded(page)
        assertBounded(page, 2.625f)
    }

    @Test fun invalidOrUnusableNativeMetricsFallBackWithoutClaimingAFontSize() {
        for (metric in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            .0001f, Float.MIN_VALUE, Float.MAX_VALUE)) {
            assertBounded(doclingPage().copy(bodyFontSize = metric))
        }
        // Even consistent page/font units must fall back if the required scale
        // cannot be represented by the native renderer's Float transform.
        assertBounded(doclingPage().copy(width = 6e-37f, height = 8e-37f, bodyFontSize = 1.44e-38f))
    }

    @Test fun sourceAndOverviewWithUnknownMetricsKeepFiniteSharedGeometry() {
        val page = doclingPage().copy(pageIndex0 = 1)
        val target = (ReaderV2Fixture.questions.first().source as ReaderSourceResolution.Found).target
        for (overview in listOf(false, true)) {
            val plan = readerPagePlan(page, null, false, target, overview, 900f, 270f, 1f, 16f)
            val v = plan.viewport
            assertNull(plan.bodySizePx)
            assertEquals(2, plan.columnId)
            assertTrue(maxOf(v.pageWidth, v.pageHeight) <= 1200.01f)
            val box = target.boxes.single()
            assertTrue(box.left * v.pageWidth + v.x >= 16f)
            assertTrue(box.right * v.pageWidth + v.x <= 884f)
            assertEquals(page.width / page.height, v.pageWidth / v.pageHeight, .0001f)
        }
    }

    @Test fun aLateValidMeasurementReplacesTheGeometryFallbackAndRespectsFontScale() {
        val unknown = doclingPage()
        val measured = unknown.copy(bodyFontSize = 14.4f)
        for (density in listOf(1f, 2.625f)) for (fontScale in listOf(1f, 1.15f)) {
            val minPx = 16f * density * fontScale
            val maxPx = 22f * density * fontScale
            for (column in listOf(null, 1, 2)) {
                val before = readerPagePlan(unknown, column, false, null, false, 900f * density, 270f * density, density, minPx, maxPx)
                val after = readerPagePlan(measured, column, false, null, false, 900f * density, 270f * density, density, minPx, maxPx)
                assertNull(before.bodySizePx)
                assertTrue(after.bodySizePx!! >= minPx - .01f && after.bodySizePx!! <= maxPx + .01f)
                assertEquals(column, after.columnId)
            }
        }
    }
}
