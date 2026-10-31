package com.coach.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.coach.ui.markdown.*
import kotlin.test.*

/** What a learner sees when the server cannot draw a formula: a plain-words explanation, the source kept, the text around it untouched. */
@OptIn(ExperimentalTestApi::class)
class FormulaFailureTest {
    private fun blocks(source: String): List<MarkdownBlock> = StreamingMarkdown().append(source, true)
    @androidx.compose.runtime.Composable private fun Show(source: String, failed: Set<String> = emptySet()) =
        androidx.compose.foundation.layout.Column { blocks(source).forEach { MarkdownContent(it, emptyMap(), failed = failed) } }

    @Test fun displayFormulaThatFailedExplainsItselfAndKeepsItsSource() = runComposeUiTest {
        val tex = "\\varepsilon=\\frac{\\Delta Q}{"
        val source = "公式前的话。\n\n$$$tex$$\n\n公式后的话。"
        setContent { MaterialTheme { Show(source, setOf(mathKey(tex, true))) } }
        onNodeWithText(FORMULA_FAILED_BLOCK_NOTE).assertExists()
        onNodeWithText(tex).assertExists()
        onNodeWithText("公式前的话。", substring = true).assertExists()
        onNodeWithText("公式后的话。", substring = true).assertExists()
    }

    @Test fun inlineFormulaThatFailedStaysInTheSentenceAndTheNoteAppearsOnce() = runComposeUiTest {
        val source = "先写 \$a_{\$ 然后再写 \$b^{\$ 两处。"
        val tokens = com.coach.domain.math.MathDelimiters.tokenize(source, true).segments.mapNotNull { it.math?.let { t -> mathKey(t, false) } }
        setContent { MaterialTheme { Show(source, tokens.toSet()) } }
        onAllNodesWithText(FORMULA_FAILED_INLINE_NOTE).assertCountEquals(if (tokens.isEmpty()) 0 else 1)
    }

    @Test fun formulaStillLoadingShowsNoFailureNote() = runComposeUiTest {
        val source = "弹性 \$\\varepsilon\$ 的值。"
        setContent { MaterialTheme { Show(source) } }
        onAllNodesWithText(FORMULA_FAILED_INLINE_NOTE).assertCountEquals(0)
        onAllNodesWithText(FORMULA_FAILED_BLOCK_NOTE).assertCountEquals(0)
    }
}
