package com.coach.desktop.books

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.coach.ui.CoachTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/** The sayings pane is the last block in the companion column. The first show must scroll it into that column. */
@OptIn(ExperimentalTestApi::class)
class ConceptSayingsScrollTest {
    @Test fun shortCompanionViewportShowsTheNoticeAndTheResult() = runSkikoComposeUiTest(
        size = Size(900f, 800f),
        density = Density(1f),
    ) {
        setContent {
            CoachTheme(false) {
                SayingsViewport(heightDp = 420) {
                    CrossBookCompanionSection(
                        cards = emptyList(),
                        board = sampleBoard(),
                        checking = false,
                        notice = ConceptSayingLogic.RETRY_LATER,
                        onJumpToBookPage = { _, _, _, _ -> },
                        onMuteConcept = {},
                    )
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            nodeInsideViewport("concept-sayings-notice") && nodeInsideViewport("concept-sayings-title")
        }
        assertTrue(nodeInsideViewport("concept-sayings-c1"))
        onNodeWithTag("concept-sayings-notice").assertIsDisplayed()
        onNodeWithTag("concept-sayings-title").assertIsDisplayed()
        onNodeWithTag("concept-sayings-c1").assertIsDisplayed()
    }

    @Test fun paneTallerThanTheViewportAlignsTheNoticeToTheTop() = runSkikoComposeUiTest(
        size = Size(900f, 800f),
        density = Density(1f),
    ) {
        setContent {
            CoachTheme(false) {
                SayingsViewport(heightDp = 150) {
                    CrossBookCompanionSection(
                        cards = emptyList(),
                        board = sampleBoard(),
                        checking = false,
                        notice = ConceptSayingLogic.RETRY_LATER,
                        onJumpToBookPage = { _, _, _, _ -> },
                        onMuteConcept = {},
                    )
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            nodeInsideViewport("concept-sayings-notice") && nodeInsideViewport("concept-sayings-title")
        }
        val viewport = onNodeWithTag("sayings-viewport").getUnclippedBoundsInRoot()
        val title = onNodeWithTag("concept-sayings-title").getUnclippedBoundsInRoot()
        assertTrue(title.top < viewport.top + 48.dp, "title landed at ${title.top}, viewport ${viewport.top}..${viewport.bottom}")
        onNodeWithTag("concept-sayings-notice").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.nodeInsideViewport(tag: String): Boolean {
        return runCatching {
            val viewport = onNodeWithTag("sayings-viewport").getUnclippedBoundsInRoot()
            val node = onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            node.top >= viewport.top - 1.dp && node.bottom <= viewport.bottom + 1.dp
        }.getOrDefault(false)
    }

    @androidx.compose.runtime.Composable
    private fun SayingsViewport(heightDp: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scroll = rememberScrollState()
        val anchor = remember { CompanionScrollAnchor() }
        anchor.scroll = scroll
        Box(
            Modifier
                .height(heightDp.dp)
                .fillMaxWidth()
                .testTag("sayings-viewport")
                .onGloballyPositioned { anchor.viewport = it },
        ) {
            CompositionLocalProvider(LocalCompanionScrollAnchor provides anchor) {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                    Spacer(Modifier.height(1400.dp))
                    content()
                }
            }
        }
    }

    private fun sampleBoard(): ConceptSayingsBoard {
        val column = ConceptSayingColumn(
            bookId = "b1",
            bookTitle = "甲书",
            statement = "甲书这么说。",
            stance = SayingStance.SAME,
            page = 1,
            offsetUtf16 = 0,
            quoteAnchor = "甲书这么说。",
            mentionId = "m1",
        )
        return ConceptSayingsBoard(
            conceptId = "c1",
            conceptName = "premium",
            anchorBookId = "b1",
            inputHash = "hash",
            columns = listOf(column),
            pending = false,
            candidates = emptyList(),
        )
    }
}
