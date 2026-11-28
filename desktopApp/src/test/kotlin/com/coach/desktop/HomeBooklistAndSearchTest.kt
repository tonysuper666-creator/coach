package com.coach.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import com.coach.desktop.books.LibraryUi
import com.coach.desktop.books.TextbookController
import com.coach.desktop.books.TextbookLibrary
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TON-1985 第 4 项：
 * - 新建书单不再要求先选两本书：空着能建、一本也能建，勾一本书时提示还在但「创建书单」仍可点；
 * - 顶栏搜索的弹层按 Esc 关、点外面也关，关掉之后首页还在，背后那颗「＋」没有被顺手点开（还是模态）。
 */
@OptIn(ExperimentalTestApi::class)
class HomeBooklistAndSearchTest {
    private fun ComposeUiTest.ready(c: TextbookController, condition: (LibraryUi) -> Boolean) {
        waitUntil(timeoutMillis = 20_000) { !c.state.value.busy && condition(c.state.value) }
        assertNull(c.state.value.error)
        waitForIdle()
    }

    @Test
    fun booklistNeedsNoSecondBookAndCanStartEmpty() {
        val root = Files.createTempDirectory("coach-ton1985-booklist-")
        try {
            val bookA = root.resolve("book-a.md").also {
                Files.writeString(it, "# Chapter 1\n\nInformation asymmetry changes contract pricing.")
            }
            val library = TextbookLibrary(root.resolve("library"))
            val c = TextbookController(library)
            runBlocking {
                c.importBook(bookA)
                c.back()
            }
            val book = library.catalog().single()

            runSkikoComposeUiTest(size = Size(1280f, 820f), density = Density(1f)) {
                setContent { WindowsTextbookHost(controller = c, chooseBook = { bookA }) }
                ready(c) { it.initialized && it.catalog.size == 1 }

                // 1) 一本书都不勾，只填名字就能建。
                onNodeWithTag("host-add").performClick()
                waitForIdle()
                onNodeWithTag("host-add-booklist").performClick()
                waitForIdle()
                onNodeWithTag("create-collection-dialog").assertIsDisplayed()
                onNodeWithTag("collection-booklist-hint").assertIsDisplayed()
                onNodeWithTag("collection-title-input").performTextInput("先空着")
                onNodeWithTag("collection-confirm").assertIsEnabled().performClick()
                ready(c) { it.collections.any { col -> col.record.title == "先空着" } }

                // 2) 只勾一本：提示还在（说明没到两本），但「创建书单」照样能点。
                onNodeWithTag("host-add").performClick()
                waitForIdle()
                onNodeWithTag("host-add-booklist").performClick()
                waitForIdle()
                onNodeWithTag("collection-title-input").performTextInput("一本也行")
                onNodeWithTag("collection-existing-${book.id}").performClick()
                waitForIdle()
                onNodeWithTag("collection-booklist-hint").assertIsDisplayed()
                onNodeWithTag("collection-confirm").assertIsEnabled().performClick()

                ready(c) { it.collections.any { col -> col.record.title == "一本也行" && col.materialIds.size == 1 } }
                val oneBook = c.state.value.collections.single { it.record.title == "一本也行" }
                assertTrue(book.id in oneBook.materialIds)
                val empty = c.state.value.collections.single { it.record.title == "先空着" }
                assertTrue(empty.materialIds.isEmpty())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun searchClosesOnEscapeAndOnOutsideClickWithoutTouchingWhatIsBehind() {
        val root = Files.createTempDirectory("coach-ton1985-search-")
        try {
            val bookA = root.resolve("book-a.md").also {
                Files.writeString(it, "# Chapter 1\n\nIncentives shape decisions under uncertainty.")
            }
            val library = TextbookLibrary(root.resolve("library"))
            val c = TextbookController(library)
            runBlocking {
                c.importBook(bookA)
                c.back()
            }

            runSkikoComposeUiTest(size = Size(1280f, 820f), density = Density(1f)) {
                setContent { WindowsTextbookHost(controller = c, chooseBook = { bookA }) }
                ready(c) { it.initialized && it.catalog.size == 1 }

                // Esc 关掉搜索框。
                onNodeWithTag("host-search").performClick()
                waitForIdle()
                onNodeWithTag("host-search-dialog").assertIsDisplayed()
                onNodeWithTag("host-search-input").performClick()
                onNodeWithTag("host-search-input").performKeyInput { pressKey(Key.Escape) }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("host-search-dialog").fetchSemanticsNodes().isEmpty()
                }
                onNodeWithTag("host-bar").assertIsDisplayed()

                // 点外面也关，而且这一下不会落到背后的「＋」上。
                onNodeWithTag("host-search").performClick()
                waitForIdle()
                onNodeWithTag("host-search-dialog").assertIsDisplayed()
                val addBounds = onNodeWithTag("host-add").fetchSemanticsNode().boundsInRoot
                // 弹层是单独一层窗口并盖住整窗；点它自身（而不是背后首页）就算「点外面」。
                onAllNodes(isRoot()).onLast().performMouseInput {
                    click(Offset(addBounds.center.x, addBounds.center.y))
                }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithTag("host-search-dialog").fetchSemanticsNodes().isEmpty()
                }
                assertTrue(
                    onAllNodesWithTag("host-add-menu").fetchSemanticsNodes().isEmpty(),
                    "点到弹层外面只该关掉搜索，背后的「＋」不该跟着打开",
                )
                onNodeWithTag("host-bar").assertIsDisplayed()

                // 关掉之后还能再打开，输入框是空的。
                onNodeWithTag("host-search").performClick()
                waitForIdle()
                onNodeWithTag("host-search-dialog").assertIsDisplayed()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
