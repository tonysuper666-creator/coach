package com.coach.desktop.books

import androidx.compose.ui.text.LinkAnnotation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readLines
import kotlin.test.*

/** TON-1694：读者看到的字只说人话。纯文本规则 + 一遍源码扫描（git grep 式），不需要窗口。 */
class ReadableTextTest {
    @Test fun reflowJoinsHardWrappedLinesAndKeepsParagraphs() {
        val pdf = "Hospitals that perform a procedure more often tend to report\nbetter results.\n\nA busy unit may do well because practice\nbuilds skill."
        assertEquals("Hospitals that perform a procedure more often tend to report better results.\nA busy unit may do well because practice builds skill.",
            ReadableText.reflow(pdf))
        // 中文之间接行不加空格；连字符结尾的行直接接上。
        assertEquals("小医院的结果波动更大，因为病例少。", ReadableText.reflow("小医院的结果波动更大，\n因为病例少。"))
        assertEquals("volume-outcome", ReadableText.reflow("volume-\noutcome"))
        // 没有换行的原文一个字都不动（含首尾空白）。
        val single = "  One paragraph, already flowing. "
        assertSame(single, ReadableText.reflow(single))
        assertEquals("a b\nc", ReadableText.reflow("a\r\nb\r\n \r\nc\n"))
    }

    @Test fun citationMarksAreStrippedOrTurnedIntoNumbers() {
        assertEquals("病例越多结局越好。但这只是相关。", ReadableText.withoutCitationMarks("病例越多结局越好。【S1】但这只是相关。 [S2]"))
        assertEquals("见原文。", ReadableText.withoutCitationMarks("见原文。【S1，S2】"))
        assertEquals("见原文。", ReadableText.withoutCitationMarks("见原文。［S3］"))

        var opened = -1
        val shown = ReadableText.cited("结局更好。【S1】但原因可能反过来。【S2、S9】", listOf("S1", "S2"), null) { opened = it }
        assertEquals("结局更好。${ReadableText.superscriptLabel(0)}但原因可能反过来。${ReadableText.superscriptLabel(1)}", shown.text)
        assertFalse(ReadableText.INTERNAL_ID.containsMatchIn(shown.text))
        val links = shown.getLinkAnnotations(0, shown.length)
        assertEquals(2, links.size, "认得的两个标记各一个可点的上标，认不得的 S9 去掉")
        (links[1].item as LinkAnnotation.Clickable).linkInteractionListener!!.onClick(links[1].item)
        assertEquals(1, opened)
    }

    @Test fun internalIdPatternCatchesWhatUsedToLeak() {
        for (leak in listOf("43dc0c53…:p15", "43dc0c53b1e2f0aa43dc0c53b1e2f0aa43dc0c53:p1:25", "【S1】", "[S2]", "出处 · af774362…:p1:25"))
            assertTrue(ReadableText.INTERNAL_ID.containsMatchIn(leak), leak)
        for (fine in listOf("出处 1 · 第 15 页 · 第 3 段 · “Small units are not…”", "本章第 3 段", "第 3 页", "S1 级", "13 / 500"))
            assertFalse(ReadableText.INTERNAL_ID.containsMatchIn(fine), fine)
        assertFalse(ReadableText.readsAsChinese("Failed requirement."))
        assertTrue(ReadableText.readsAsChinese("文件已加密，请先解密。"))
    }

    @Test fun shortQuoteCutsByCodePoint() {
        assertEquals("“短句”", shortQuote("短句"))
        assertEquals("“Small units are…”", shortQuote("Small units are not\nautomatically unsafe."))
    }

    /**
     * 源码扫描：桌面端界面代码里，不能把书的块编号、引用锚点或缩写编号直接交给 Text 显示，
     * 也不能在字符串里拼【S1】这类标记。改前 BookExplain、AnswerExplain 各有几处会被这里抓到。
     */
    @Test fun noScreenTextShowsInternalIds() {
        val root = Path.of("src/main/kotlin")
        assertTrue(Files.isDirectory(root), "从 desktopApp 目录运行：${root.toAbsolutePath()}")
        val shown = Regex("""Text\([^"]*(\.blockId\b|shortId\(|\.anchor\b|\bkpId\b)""")
        val literal = Regex(""""[^"]*([【\[［]S\d|[【\[［]\$\{[^}]*anchor)""")
        val hits = Files.walk(root).use { files ->
            files.filter { it.extension == "kt" }.toList().flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val code = line.substringBefore("//")
                    if (shown.containsMatchIn(code) || literal.containsMatchIn(code) || "shortId(" in code)
                        "${root.relativize(file).invariantSeparatorsPathString}:${index + 1}: ${line.trim()}" else null
                }
            }
        }
        assertTrue(hits.isEmpty(), "界面代码里还在显示内部编号：\n" + hits.joinToString("\n"))
    }
}
