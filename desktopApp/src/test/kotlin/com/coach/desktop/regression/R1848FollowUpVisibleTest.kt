package com.coach.desktop.regression

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import com.coach.desktop.books.CompanionAskPane
import com.coach.desktop.books.Explanation
import com.coach.desktop.books.InlineExplainEntry
import com.coach.desktop.books.InlineExplainThread
import com.coach.desktop.books.TextbookLibrary
import com.coach.ui.CoachTheme
import com.coach.ui.v7.LocalV7ReducedMotion
import java.nio.file.Files
import kotlin.test.Test

/**
 * TON-1848: a follow-up under the original explanation stays on screen in a short window.
 */
@OptIn(ExperimentalTestApi::class)
class R1848FollowUpVisibleTest {
    @Test
    fun followUpReplyIsVisibleInAShortPane() {
        val root = Files.createTempDirectory("coach-r1848-follow-")
        try {
            val library = TextbookLibrary(root.resolve("library"))
            val book = library.importBook(
                root.resolve("page.md").also {
                    Files.writeString(it, "# Chapter 1 Opening\n\nThe sentence under discussion stays on the page.\n")
                },
            )
            val block = book.sourceBlocks().first { it.text.contains("sentence under discussion") }
            val followUp = "这是追问的单独答复：专门回答为什么作者这样写。"
            val turns = buildList {
                repeat(6) { index ->
                    add(
                        InlineExplainEntry(
                            "讲讲",
                            Explanation("第一轮讲解占满窗口。".repeat(12) + " turn $index", emptyList(), 1L),
                            listOf(block),
                        ),
                    )
                }
                add(InlineExplainEntry("追问", Explanation(followUp, emptyList(), 1L), listOf(block)))
            }
            val thread = InlineExplainThread(selection = block, context = null, turns = turns)
            runSkikoComposeUiTest(size = Size(820f, 240f)) {
                setContent {
                    CoachTheme(false) {
                        CompositionLocalProvider(LocalV7ReducedMotion provides true) {
                            Box(Modifier.size(820.dp, 240.dp)) {
                                CompanionAskPane(
                                    book = book,
                                    allBlocks = book.sourceBlocks(),
                                    pageBlocks = listOf(block),
                                    activeSelection = block,
                                    currentSection = null,
                                    thread = thread,
                                    draft = "",
                                    compact = true,
                                    onDraftChange = {},
                                    onPickPassage = {},
                                    onSubmitQuestion = {},
                                    onRetry = {},
                                    onOpenLogin = {},
                                    onOpenCitation = { _, _ -> },
                                    onClearSelection = {},
                                )
                            }
                        }
                    }
                }
                waitForIdle()
                onNodeWithText(followUp, substring = true).assertIsDisplayed()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
