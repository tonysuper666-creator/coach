package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.ui.CoachTheme
import kotlin.test.*

/** Separates the sheet component's reflow contract from Desk's existing width cap. */
@OptIn(ExperimentalTestApi::class)
class BookPageViewTest {
    @Test fun spreadKeepsBothInnerFootersAndSourceAnchorOnNarrowing() = runSkikoComposeUiTest(
        size = Size(1800f, 1000f), density = Density(1f),
    ) {
        val book = Textbook("isolated-spread", "阅读与证据", "md", listOf(BookPage(41,
            "# 第三章 阅读与证据\n\n" + "读者回到原文检查证据，再把结论接回已有经验。😀𠮷".repeat(300))))
        val state = BookPagingState(BookPageAnchor(41))
        var width by mutableStateOf(1600.dp)
        setContent { CoachTheme {
            Box(Modifier.width(width).height(900.dp)) {
                BookPageView(book, state, emptyMap(), onTurn = { state.move(it) })
            }
        } }
        waitForIdle()
        onNodeWithTag("book-sheet-0").assertExists()
        onNodeWithTag("book-sheet-1").assertExists()
        onAllNodesWithText(Regex("本章还剩 \\d+ 页")).assertCountEquals(2)
        assertTrue(state.pages.size > 3)
        val anchor = state.pages[2].anchor.let { it.copy(offsetUtf16 = it.offsetUtf16 + 10) }
        runOnIdle { state.anchor = anchor }
        waitForIdle()
        runOnIdle { width = 1100.dp }
        waitForIdle()
        onNodeWithTag("book-sheet-1").assertDoesNotExist()
        assertFalse(state.spread)
        assertEquals(anchor, state.anchor)
        assertTrue(state.pages[state.index].sources.any {
            it.importPage == anchor.importPage && anchor.offsetUtf16 in it.startUtf16 until it.endUtf16
        })
    }
}
