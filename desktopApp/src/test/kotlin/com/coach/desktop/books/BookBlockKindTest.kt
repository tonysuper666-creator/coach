package com.coach.desktop.books

import kotlin.test.*

/**
 * TON-1766：讲解面板只给正文段落挂「讲解这段」。课程标准一类 PDF 的目录页每行都挂了入口，讲出来全是废话。
 * 这里只用自拟的句子，不放任何教材原文。
 */
class BookBlockKindTest {
    private var next = 0
    private fun block(text: String, page: Int = 1, heading: Boolean = false) =
        SourceBlock("book:p$page:${next++}", page, 0, text.length, heading, text)
    private fun kind(text: String, heading: Boolean = false) = BookBlockKinds.of(block(text, heading = heading))

    @Test fun tocLinesWithLeadersOrTrailingPageNumbersAreToc() {
        for (line in listOf(
            "一、课程性质与任务 ………………………… 1",
            "第二部分 学段目标 ............ 12",
            "（三）教学建议 · · · · · · · · 27",
            "Introduction      3",
            "1.2 学习目标 5",
            "Preface ........ ix",
        )) {
            assertTrue(BookBlockKinds.tocLine(line), line)
            assertEquals(BookBlockKind.Toc, kind(line), line)
        }
        // 导入时「第一章 总论 …… 1」会被切成章标题块，仍算目录，不算标题。
        assertEquals(BookBlockKind.Toc, kind("第一章 总论 ………… 1", heading = true))
    }

    @Test fun aMultiLineTocBlockIsTocEvenWithChapterLinesBetween() {
        val toc = listOf(
            "前言 ………………………… 1",
            "一、课程性质 ………………… 2",
            "二、课程理念 ………………… 3",
            "课程目标",
            "（一）核心素养 ……………… 5",
            "（二）总目标 ………………… 8",
        ).joinToString("\n")
        assertEquals(BookBlockKind.Toc, kind(toc))
    }

    @Test fun headingsAreHeadings() {
        assertEquals(BookBlockKind.Heading, kind("第三章 光与植物", heading = true))
        assertEquals(BookBlockKind.Heading, kind("一、课程性质"))
        assertEquals(BookBlockKind.Heading, kind("（二）学段目标与内容"))
        assertEquals(BookBlockKind.Heading, kind("2.3 实验与探究"))
    }

    @Test fun standalonePageNumbersArePageNumbers() {
        for (line in listOf("12", "- 3 -", "— 45 —", "第 3 页", "3 / 20", "第 3 页 共 20 页", "Page 4 of 9", "xii", "１２")) {
            assertEquals(BookBlockKind.PageNumber, kind(line), line)
        }
    }

    @Test fun shortLinesAreNotExplained() {
        assertEquals(BookBlockKind.Short, kind("见下图。"))
        assertEquals(BookBlockKind.Short, kind("注：本表数据为示例。"))
        // 去掉空白后不到 40 字的都不给入口，哪怕是一句完整的话。
        assertFalse(BookBlockKinds.explainable(block("这是一句完整的话，但是它只有不到四十个字，所以不讲。")))
    }

    @Test fun aNormalParagraphIsBody() {
        val paragraph = "光合作用是绿色植物利用光能，把二氧化碳和水转化为储存能量的有机物，并且释放出氧气的过程。" +
            "这个过程主要在叶片的叶肉细胞里进行，叶绿体是它发生的场所。"
        assertEquals(BookBlockKind.Body, kind(paragraph))
        assertTrue(BookBlockKinds.explainable(block(paragraph)))
        // 句子以数字结尾、但很长的正文不是目录行。
        val endsInNumber = "全书按单元编排，每个单元后面都附有练习和拓展阅读，供课后自学和小组讨论使用，配套的参考答案见第 245"
        assertFalse(BookBlockKinds.tocLine(endsInNumber))
        assertEquals(BookBlockKind.Body, kind(endsInNumber))
    }

    @Test fun aMixedChineseAndEnglishParagraphIsBody() {
        val mixed = "光合作用（photosynthesis）分为光反应（light reactions）和暗反应（Calvin cycle）两个阶段，" +
            "前者在类囊体膜上产生 ATP 和 NADPH，后者在基质里用它们固定 CO2。"
        assertEquals(BookBlockKind.Body, kind(mixed))
        val english = "Photosynthesis is the process by which green plants use light energy to turn carbon dioxide and water into sugars."
        assertEquals(BookBlockKind.Body, kind(english))
        // 多行的正文段（PDF 一行一断），每行都不像目录。
        val wrapped = "Leaves are thin and wide, so that most of their cells are close to the light\n" +
            "and to the air spaces that carry carbon dioxide in from the stomata; in a\n" +
            "mild climate a single tree can fix several kilograms of carbon in a civil year"
        assertEquals(BookBlockKind.Body, kind(wrapped))
    }

    @Test fun printedPageMarksAndRunningHeadersAreNotBody() {
        assertEquals(BookBlockKind.PageMark, kind("<!-- PDF 第 3 页 · 书第 1 页 -->"))
        val header = (1..4).map { block("义务教育示例课程标准（2022年版）", page = it) }
        val footer = (1..4).map { block("示例出版社 · 第 $it 页 · 内部试用", page = it) }
        val running = BookBlockKinds.running(header + footer)
        assertEquals(BookBlockKind.Running, BookBlockKinds.of(header[2], running))
        assertEquals(BookBlockKind.Running, BookBlockKinds.of(footer[1], running))
        // 只在两页出现的一行不算页眉页脚。
        assertTrue(BookBlockKinds.running(header.take(2)).isEmpty())
    }

    @Test fun pageKindsDecideWhetherTheWholePageCanBeExplained() {
        val body = "光合作用是绿色植物利用光能，把二氧化碳和水转化为储存能量的有机物，并且释放出氧气的过程。这个过程主要在叶片里进行。"
        val tocPage = listOf(block("目录"), block("一、课程性质 ………………… 2"), block("二、课程理念 ………………… 3"),
            block("三、课程目标 ………………… 5"), block("12"))
        assertEquals(BookPageKind.Toc, BookBlockKinds.page(tocPage))
        assertEquals("这一页是目录，翻到正文再讲。", BookPageKind.Toc.note)

        val bodyPage = listOf(block("一、课程性质"), block(body), block(body + "叶绿体是光合作用发生的场所。"), block("3"))
        assertEquals(BookPageKind.Body, BookBlockKinds.page(bodyPage))
        assertNull(BookPageKind.Body.note)

        val copyright = listOf(block("图书在版编目（CIP）数据"), block("示例课程标准 / 示例编写组编. —北京：示例出版社"),
            block("ISBN 978-7-000-00000-0"), block("版权所有 侵权必究"))
        assertEquals(BookPageKind.Copyright, BookBlockKinds.page(copyright))

        assertEquals(BookPageKind.Blank, BookBlockKinds.page(emptyList()))
        assertEquals(BookPageKind.Blank, BookBlockKinds.page(listOf(block("<!-- PDF 第 2 页 · 书第 ii 页 -->"))))
        assertEquals(BookPageKind.TitleOnly, BookBlockKinds.page(listOf(block("第一章 光与植物", heading = true), block("8"))))
    }
}
