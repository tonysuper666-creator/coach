package com.coach.android.reader.v2

import com.coach.ui.reader.v2.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReaderV2StateTest {
    @Test fun sourceReturnRestoresTheOriginatingQuestionDrawerAndFocusAfterRecheck() {
        val initial = ReaderV2Fixture.session().copy(drawer = ReaderDrawer.QUESTIONS,
            position = ReaderPosition(1, 2), focus = ReaderFocus.READING,
            answers = mapOf("slope" to "原题答案"), noteDraft = "页边批注", selectionAnchorId = "selected")
        val source = initial.showSource(ReaderV2Fixture.questions.first())
        assertEquals("返回题目", source.drawerReturnLabel)
        val rechecked = source.copy(focus = ReaderFocus.QUESTIONS).showSource(ReaderV2Fixture.questions.first())
        val returned = rechecked.closeDrawer()
        assertEquals(ReaderDrawer.QUESTIONS, returned.drawer)
        assertEquals(initial.focus, returned.focus)
        assertEquals(initial.position, returned.position)
        assertEquals(initial.answers, returned.answers)
        assertEquals(initial.noteDraft, returned.noteDraft)
        assertEquals(initial.selectionAnchorId, returned.selectionAnchorId)
        assertEquals("返回原页", returned.drawerReturnLabel)
        assertNull(returned.source)
        assertNull(returned.sourceOrigin)
        assertEquals(ReaderDrawer.NONE, returned.closeDrawer().drawer)
    }

    @Test fun sourceOriginSurvivesRestartWithoutRestoringGeometryAndLegacySnapshotsStillReturn() {
        val initial = ReaderV2Fixture.session().copy(drawer = ReaderDrawer.QUESTIONS, position = ReaderPosition(1, 2),
            answers = mapOf("slope" to "原题答案"))
        val opened = initial.showSource(ReaderV2Fixture.questions.first())
        val restored = ReaderSession.restore(opened.snapshot())!!
        assertNull(restored.source)
        assertEquals(ReaderSourceFailure.RECHECK, restored.sourceFailure)
        assertEquals("返回题目", restored.drawerReturnLabel)
        val returned = restored.showSource(ReaderV2Fixture.questions.first()).closeDrawer()
        assertEquals(ReaderDrawer.QUESTIONS, returned.drawer)
        assertEquals(initial.position, returned.position)
        assertEquals(initial.focus, returned.focus)
        assertEquals(initial.answers, returned.answers)
        val unconfirmed = restored.showSource(ReaderV2Fixture.questions.last()).closeDrawer()
        assertEquals(ReaderDrawer.QUESTIONS, unconfirmed.drawer)
        val standalone = ReaderV2Fixture.session().copy(focus = ReaderFocus.QUESTIONS).showSource(ReaderV2Fixture.questions.first())
        val legacy = JsonObject(Json.parseToJsonElement(standalone.snapshot()).jsonObject.filterKeys { it != "sourceOrigin" })
        val legacyReturn = ReaderSession.restore(legacy.toString())!!.closeDrawer()
        assertEquals(ReaderDrawer.NONE, legacyReturn.drawer)
        assertEquals(ReaderFocus.QUESTIONS, legacyReturn.focus)
    }

    @Test fun currentAndroidWindowBreakpointsKeepPageAndQuestionPanesContiguous() {
        assertEquals(ReaderArrangement.COMPACT, readerPanes(ReaderWindow(699f, 900f), true).arrangement)
        for (width in listOf(700f, 704f, 933f, 1099f)) {
            val panes = readerPanes(ReaderWindow(width, 900f), true)
            assertEquals(ReaderArrangement.EXPANDED, panes.arrangement)
            assertNull(panes.navigation)
            assertEquals(panes.page.right, panes.questions!!.left)
        }
        val three = readerPanes(ReaderWindow(1100f, 900f), true)
        assertEquals(three.navigation!!.right, three.page.left)
        assertEquals(three.page.right, three.questions!!.left)
        assertTrue(readerPanes(ReaderWindow(933f, 704f), true).page.width >= 520f)
    }

    @Test fun readingUsesActualPaneHeightAndAFontFloorWhileOverviewStaysExplicit() {
        val page = ReaderV2Fixture.pages.first()
        for ((width, height) in listOf(714f to 250f, 900f to 270f, 500f to 700f)) {
            val plan = readerPagePlan(page, null, false, null, false, width, height, 1f, 16f)
            assertEquals(1, plan.columnId)
            assertEquals(width, plan.region.width * plan.viewport.pageWidth, .05f)
            assertTrue(plan.bodySizePx!! >= 16f)
            assertTrue(plan.viewport.contentHeight > height)
        }
        val explicit = readerPagePlan(page, 2, false, null, false, 900f, 270f, 1f, 16f)
        assertEquals(2, explicit.columnId)
        assertEquals(900f, explicit.region.width * explicit.viewport.pageWidth, .05f)
        val readablePage = page.copy(bodyFontSize = 48f)
        assertNull(readerPagePlan(readablePage, null, false, null, false, 700f, 700f, 1f, 16f).columnId)
        val unknownFont = page.copy(bodyFontSize = null)
        assertNull(readerPagePlan(unknownFont, null, false, null, false, 700f, 700f, 1f, 16f).bodySizePx)
        val overview = readerPagePlan(page, null, false, null, true, 714f, 250f, 1f, 16f)
        assertNull(overview.columnId)
        assertEquals(ReaderRegion.Full, overview.region)
        assertTrue(overview.bodySizePx!! < 16f)
        val figure = readerPagePlan(page.copy(columns = emptyList(), trim = ReaderRegion.Full), null, true,
            null, false, 300f, 250f, 1f, 16f)
        assertTrue(figure.bodySizePx!! >= 16f)
        assertTrue(figure.viewport.contentWidth > 300f)
    }

    @Test fun compactExpandedAndRuntimeHingesKeepContentOutsideReservedRegions() {
        assertEquals(ReaderArrangement.COMPACT, readerPanes(ReaderWindow(390f, 844f), true).arrangement)
        val expanded = readerPanes(ReaderWindow(820f, 720f), true)
        assertEquals(ReaderArrangement.EXPANDED, expanded.arrangement)
        assertEquals(expanded.page.right, expanded.questions!!.left)
        assertEquals(820f, readerPanes(ReaderWindow(820f, 720f), false).page.width)
        val book = readerPanes(ReaderWindow(900f, 720f, ReaderFold(ReaderPane(440f, 0f, 460f, 720f), true, true)), true)
        assertEquals(ReaderArrangement.BOOK, book.arrangement)
        assertEquals(book.page.width, book.questions!!.width)
        assertTrue(book.questions!!.right <= 440 && book.page.left >= 460)
        val flex = readerPanes(ReaderWindow(820f, 900f, ReaderFold(ReaderPane(0f, 430f, 820f, 450f), false, true)), true)
        assertEquals(ReaderArrangement.TABLETOP, flex.arrangement)
        assertTrue(flex.page.bottom <= 430 && flex.questions!!.top >= 450)
        val outside = ReaderFold(ReaderPane(950f, 0f, 970f, 720f), true, true)
        assertEquals(ReaderArrangement.EXPANDED, readerPanes(ReaderWindow(820f, 720f, outside), true).arrangement)
    }

    @Test fun sourceOpenAndReturnPreserveAnswerNoteSelectionAndOriginalColumn() {
        val initial = ReaderV2Fixture.session().copy(position = ReaderPosition(0, 2), focus = ReaderFocus.QUESTIONS,
            answers = mapOf("slope" to "向右下"), noteDraft = "合成批注", selectionAnchorId = "selection")
        val opened = initial.showSource(ReaderV2Fixture.questions.first())
        assertEquals(1, opened.source!!.pageIndex0)
        assertEquals(initial.position, opened.returnPosition)
        assertEquals(initial.answers, opened.answers)
        val returned = opened.closeDrawer()
        assertEquals(initial.position, returned.position)
        assertEquals(initial.focus, returned.focus)
        assertEquals("合成批注", returned.noteDraft)
        assertEquals("selection", returned.selectionAnchorId)
        assertNull(returned.source)
    }

    @Test fun changedOrAmbiguousSourcesNeverExposeAHighlight() {
        val changed = ReaderV2Fixture.session().copy(sha256 = "b".repeat(64)).showSource(ReaderV2Fixture.questions.first())
        assertNull(changed.source)
        assertEquals(ReaderSourceFailure.CHANGED, changed.sourceFailure)
        val ambiguous = ReaderV2Fixture.session().showSource(ReaderV2Fixture.questions.last())
        assertNull(ambiguous.source)
        assertTrue(ambiguous.confirmationNeeded)
        assertEquals(ReaderSourceFailure.AMBIGUOUS, ambiguous.sourceFailure)
    }

    @Test fun restartRetainsDraftsButRequiresFreshNativeSourceVerification() {
        val before = ReaderV2Fixture.session().copy(position = ReaderPosition(0, 2), overview = true,
            answers = mapOf("slope" to "右下"), noteDraft = "合成笔记", selectionAnchorId = "selected", railVisible = false)
            .showSource(ReaderV2Fixture.questions.first())
        val restored = ReaderSession.restore(before.snapshot())!!
        assertEquals(before.answers, restored.answers)
        assertEquals(before.noteDraft, restored.noteDraft)
        assertEquals(before.selectionAnchorId, restored.selectionAnchorId)
        assertEquals(before.returnPosition, restored.returnPosition)
        assertTrue(restored.overview)
        assertFalse(restored.railVisible)
        assertNull(restored.source)
        assertEquals(ReaderSourceFailure.RECHECK, restored.sourceFailure)
        assertTrue(restored.confirmationNeeded)
        assertEquals(before.position, restored.closeDrawer().position)
        assertNull(ReaderSession.restore("{\"schemaVersion\":99}"))
        assertNull(ReaderSession.restore("broken"))
    }

    private fun layoutJson(order: String = "ok", rotation: Int = 0, fullWidthFigure: Boolean = false, figureColumn: Int = 0): String = buildJsonObject {
        put("schema_version", 1); put("doc_sha256", ReaderV2Fixture.Sha256)
        put("page_index", 0); put("page_number", 1); put("page_base", 1)
        put("mediabox", JsonArray(listOf(0, 0, 600, 800).map(::JsonPrimitive)))
        put("status", "ok"); put("rotation", rotation); put("geometry", "page_space")
        put("coord_space", "pdf-points; origin = MediaBox top-left; x right, y down; unrotated page space (see README rotation note)")
        put("reading_order_status", order)
        put("blocks", buildJsonArray {
            fun block(id: Int, column: Int, bbox: List<Int>, kind: String = "text") { add(buildJsonObject {
                put("id", "synthetic-$id"); put("reading_order", id); put("column", column)
                put("kind", kind)
                put("bbox", JsonArray(bbox.map(::JsonPrimitive)))
            }) }
            block(0, 1, listOf(48, 96, 276, 704)); block(1, 2, listOf(324, 96, 552, 704))
            if (fullWidthFigure) block(2, figureColumn, listOf(48, 710, 552, 752), "figure")
        })
    }.toString()

    @Test fun layoutV1RespectsReadingOrderAndNeverSlicesTheFullWidthFigure() {
        val p = ReaderLayoutV1.decode(layoutJson(), ReaderV2Fixture.Sha256)
        assertEquals(listOf(1, 2), p.columns.map { it.id })
        assertEquals(2, p.nextColumn(1))
        assertEquals(.08f, p.trim.left, .0001f)
        for (figureColumn in listOf(0, 1)) {
            val withFigure = ReaderLayoutV1.decode(layoutJson(fullWidthFigure = true, figureColumn = figureColumn), ReaderV2Fixture.Sha256)
            withFigure.columns.forEach { assertTrue(it.region.left <= .08f && it.region.right >= .92f && it.region.bottom >= .94f) }
        }
    }

    @Test fun unreliableLayoutsUseOriginalPageAndDifferentVersionsAreRejected() {
        for (json in listOf(layoutJson("conflict"), layoutJson(rotation = 90))) {
            val p = ReaderLayoutV1.decode(json, ReaderV2Fixture.Sha256)
            assertTrue(p.columns.isEmpty()); assertEquals(ReaderRegion.Full, p.trim)
        }
        assertThrows(IllegalArgumentException::class.java) { ReaderLayoutV1.decode(layoutJson(), "b".repeat(64)) }
        val unsupported = layoutJson().replace("\"schema_version\":1", "\"schema_version\":2")
        assertThrows(IllegalArgumentException::class.java) { ReaderLayoutV1.decode(unsupported, ReaderV2Fixture.Sha256) }
    }

    @Test fun columnTransformMapsTheExactSameNativeSentenceBoxAsThePage() {
        val page = ReaderV2Fixture.pages[1]
        val column = page.columns[1].region
        val v = readerViewport(page, column, 390f, 600f, false)
        assertEquals(0f, column.left * v.pageWidth + v.x, .001f)
        assertEquals(390f, column.right * v.pageWidth + v.x, .001f)
        val target = (ReaderV2Fixture.questions.first().source as ReaderSourceResolution.Found).target.boxes.single()
        assertTrue(target.top * v.pageHeight + v.y >= 0)
        assertTrue(target.right * v.pageWidth + v.x <= 390.001f)
        val full = readerViewport(page, ReaderRegion.Full, 800f, 600f, true)
        assertEquals(600f, full.pageHeight, .001f)
    }
}
