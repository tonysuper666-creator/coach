package com.coach.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.coach.domain.math.MathDelimiters
import com.coach.ui.markdown.MarkdownContent
import com.coach.ui.markdown.StreamingMarkdown
import com.coach.ui.markdown.layoutMathSegments
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MarkdownStructureTest {
    private val sample = "## 先估算再检验\n\n- 第一步：**算人均**医疗支出\n- 第二步：与 GDP 比较\n  - 注意单位\n\n1. 先写公式\n2. 再代入\n\n> 提示：别漏掉人口\n\n结论放在最后。"

    @Test fun listsAndHeadingsAreRenderedNotShownAsMarkdownSource() = runComposeUiTest {
        val blocks = StreamingMarkdown().append(sample, true)
        setContent { MaterialTheme { Column { blocks.forEach { MarkdownContent(it, emptyMap()) } } } }
        // The raw markers never reach the screen.
        onAllNodes(hasText("- ", substring = true)).assertCountEquals(0)
        onAllNodes(hasText("## ", substring = true)).assertCountEquals(0)
        onAllNodes(hasText("**", substring = true)).assertCountEquals(0)
        onAllNodes(hasText("> ", substring = true)).assertCountEquals(0)
        // Bullets and numbers are drawn as their own marks; the text is still there.
        assertTrue(onAllNodesWithText("•").fetchSemanticsNodes().size >= 3)
        onNodeWithText("1.").assertExists(); onNodeWithText("2.").assertExists()
        onNodeWithText("先估算再检验").assertExists()
        onNodeWithText("注意单位").assertExists()
        onNodeWithText("结论放在最后。").assertExists()
    }

    @Test fun streamingTailAlreadyShowsListStructure() = runComposeUiTest {
        // Unsealed (still arriving) list: bullets are shown while streaming, not "- ".
        val blocks = StreamingMarkdown().append("- 第一条\n- 第二条正在输入", false)
        assertFalse(blocks.last().sealed)
        setContent { MaterialTheme { Column { blocks.forEach { MarkdownContent(it, emptyMap()) } } } }
        onAllNodes(hasText("- ", substring = true)).assertCountEquals(0)
        assertEquals(2, onAllNodesWithText("•").fetchSemanticsNodes().size)
    }

    private fun joined(src: String) = layoutMathSegments(MathDelimiters.tokenize(src, true).segments)
        .joinToString("") { it.text ?: if (it.display) "⟦D:${it.math}⟧" else "⟦I:${it.math}⟧" }

    @Test fun formulaInsideASentenceStaysInTheLineAndNextTextHasNoLeadingSpace() {
        assertEquals("人均医疗支出为⟦I:5⟧美元，约占 GDP 的 5%。", joined("人均医疗支出为 \$\$5\$\$ 美元，约占 GDP 的 5%。"))
        // Stands on its own: still a display formula, and the following text does not start with a blank.
        assertEquals("公式如下：⟦D:a+b⟧其中 a 是收入。", joined("公式如下：\n\n\$\$a+b\$\$\n\n 其中 a 是收入。").replace("\n", ""))
    }

    @Test fun currencyDollarsAreNotFormulas() {
        val tokens = MathDelimiters.tokenize("价格是 \$100，另一件 \$200。", true).segments
        assertTrue(tokens.none { it.math != null })
        assertEquals("价格是 \$100，另一件 \$200。", layoutMathSegments(tokens).joinToString("") { it.text!! })
    }
}
