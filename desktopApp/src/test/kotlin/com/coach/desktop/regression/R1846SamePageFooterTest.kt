package com.coach.desktop.regression

import androidx.compose.ui.text.AnnotatedString
import com.coach.desktop.books.BookBoundaries
import com.coach.desktop.books.BookChapterSpan
import com.coach.desktop.books.BookLaidPage
import com.coach.desktop.books.BookPageAnchor
import com.coach.desktop.books.BookPageSlice
import com.coach.desktop.books.BookPagingState
import com.coach.desktop.books.ReaderTurn
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * TON-1846: the next screen of the same imported page must change the footer page status.
 * Publishing only the import page leaves the footer unchanged, so this fails until that changes.
 */
class R1846SamePageFooterTest {
    @Test
    fun nextScreenInsideOneImportPageChangesTheFooter() {
        fun laid(offset: Int) = BookLaidPage(
            sources = listOf(BookPageSlice(2, offset, offset + 20)),
            chapter = 1,
            title = "Chapter",
            chapterNumber = "1",
            opener = false,
            text = AnnotatedString("x".repeat(20)),
            placeholders = emptyList(),
            topPx = 0,
            heightPx = 400,
        )
        val state = BookPagingState(BookPageAnchor(2, 0))
        val screens = listOf(laid(0), laid(80))
        state.pages = screens
        val chapter = BookChapterSpan("ch", "Chapter", 2, 5, emptyList())
        fun status(): String {
            val screen = screens.indexOfFirst { it.anchor.offsetUtf16 == state.anchor.offsetUtf16 }
                .let { index -> if (index < 0) 1 else index + 1 }
            return footerStatus(state.publishedImportPage, 5, chapter, screen, screens.size)
        }
        val before = status()
        state.move(ReaderTurn.Next)
        val after = status()
        assertNotEquals(before, after, "footer stayed $after after moving to offset ${state.anchor.offsetUtf16}")
    }

    /**
     * Same imported page, next screen. The five-argument status adds the screen fraction.
     * The three-argument status ignores that move, so the two strings stay equal.
     */
    private fun footerStatus(
        page: Int,
        lastPage: Int,
        chapter: BookChapterSpan,
        subPage: Int,
        subPageCount: Int,
    ): String {
        val ranged = BookBoundaries::class.java.methods.firstOrNull {
            it.name == "pageStatus" && it.parameterCount == 5
        }
        return if (ranged != null) {
            ranged.invoke(BookBoundaries, page, lastPage, chapter, subPage, subPageCount) as String
        } else {
            BookBoundaries.pageStatus(page, lastPage, chapter)
        }
    }
}
