package com.coach.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.coach.domain.math.MathDelimiters
import com.coach.ui.markdown.MarkdownContent
import com.coach.ui.markdown.StreamingMarkdown
import com.coach.ui.markdown.layoutMathSegments
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MarkdownStructureTest {
    @Test fun sealingPlainListsQuotesAndTablesKeepsTheirPixels() = runComposeUiTest {
        val source = "## **重点**\n\n- 中文与 English 的长句在窄屏换行，保留 *强调* 与 `code`。\n- 价格是 \$100，另一件 \$200。\n\n> 引用里的 **粗体** 和长句也不能在封口时移动。\n\n| A | B |\n|---|---|\n| one | two |"
        val blocks = StreamingMarkdown().append(source, true)
        val sealed = mutableStateOf(false)
        setContent {
            MaterialTheme { Column(Modifier.width(240.dp).testTag("content")) {
                blocks.forEach { MarkdownContent(it.copy(sealed = sealed.value), emptyMap()) }
            } }
        }
        val before = onNodeWithTag("content").captureToImage().toAwtImage()
        runOnIdle { sealed.value = true }
        val after = onNodeWithTag("content").captureToImage().toAwtImage()
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        assertContentEquals(
            before.getRGB(0, 0, before.width, before.height, null, 0, before.width),
            after.getRGB(0, 0, after.width, after.height, null, 0, after.width),
        )
    }

    @Test fun quizTextKeepsPlainContentAndRendersStructuredContent() = runComposeUiTest {
        val math = MathAssets()
        setContent {
            MaterialTheme { Column {
                QuizText("医疗支出为 50%，请选择合理解释。", "plain", math)
                QuizText("It's a single-choice question, worth 1.5 marks.", "english", math)
                QuizText("**重点**：保留格式", "bold", math)
                QuizText("1. 先比较\n2. 再计算", "list", math)
                QuizText("1. 单行列表", "single-list", math)
            } }
        }
        onNodeWithText("医疗支出为 50%，请选择合理解释。").assertExists()
        onNodeWithText("It's a single-choice question, worth 1.5 marks.").assertExists()
        onNodeWithText("重点：保留格式").assertExists()
        onAllNodes(hasText("**", substring = true)).assertCountEquals(0)
        onAllNodesWithText("1.").assertCountEquals(2)
        onNodeWithText("单行列表").assertExists()
        onNodeWithText("先比较").assertExists()
        onNodeWithText("再计算").assertExists()
    }

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

    @Test fun punctuationAfterAFormulaStaysOnItsLine() {
        assertEquals("生病时最终收入：\n⟦I:x=250⟧。", joined("生病时最终收入：\n\$\$x=250\$\$\n。"))
        assertEquals("未覆盖：⟦I:800-300=500⟧，所以是部分保险。", joined("未覆盖：\$\$800-300=500\$\$，所以是部分保险。"))
    }

    @Test fun currencyDollarsAreNotFormulas() {
        val tokens = MathDelimiters.tokenize("价格是 \$100，另一件 \$200。", true).segments
        assertTrue(tokens.none { it.math != null })
        assertEquals("价格是 \$100，另一件 \$200。", layoutMathSegments(tokens).joinToString("") { it.text!! })
    }
}
