package com.coach.desktop.books

import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookParagraphsTest {
    @Test fun sourceOffsetsAndParagraphRules() {
        val samples = listOf(
            "# 章节\n\n这是第一段中文。\n\n这是第二段中文。",
            "# Chapter\n\nFirst paragraph.\n\nSecond paragraph.",
            "中文文字接\n下来继续。\n\nAnother line of\nEnglish text.",
            "- 列表\n> 引文\n| 表格 |\n（1）条目\n```\n代码\n```",
        )
        for (source in samples) {
            val result = bookParagraphs(source, pdf = true)
            assertEquals(source.length, result.display.length)
            source.indices.filter { !source[it].isWhitespace() }.forEach {
                assertEquals(source[it], result.display[it], "offset $it")
            }
        }
        val chinese = bookParagraphs(samples[0], pdf = false)
        assertEquals(listOf(0, 2, 2), chinese.paragraphs.map { it.indentEm })
        assertTrue("\n\n" !in chinese.display)
        val english = bookParagraphs(samples[1], pdf = false)
        assertEquals(listOf(0, 0, 1), english.paragraphs.map { it.indentEm })
        val pdf = bookParagraphs(samples[2], pdf = true)
        assertTrue("接\u200b下" in pdf.display)
        assertTrue("of English" in pdf.display)
        assertTrue(bookParagraphs(samples[3], pdf = true).paragraphs.all { it.indentEm == 0 })
    }

    @Test fun whitespaceOnlySeparatorsKeepOffsetsWithoutVisibleIndent() {
        val source = "第一段含有😀。\n  \t\n第二段。"
        val result = bookParagraphs(source, pdf = false)
        assertEquals(source.length, result.display.length)
        assertEquals("第一段含有😀。\u200b\u200b\u200b\u200b\u200b第二段。", result.display)
        source.indices.filter { !source[it].isWhitespace() }.forEach { assertEquals(source[it], result.display[it]) }
    }

    @Test fun windowsLineEndingsAndCodeArePreserved() {
        val source = "中文接\r\n下文\r\n\r\n```\r\n\r\n    代码\r\n```"
        val result = bookParagraphs(source, pdf = true)
        assertEquals(source.length, result.display.length)
        assertTrue("接\u200b\u200b下" in result.display)
        assertTrue("```\r\n\r\n    代码\r\n```" in result.display)
    }
    @Test fun selectionsRestoreEverySourceOffsetIncludingPdfAndWindowsSeparators() {
        val samples = listOf(
            "# 标题\r\n\r\n中文接\r\n下文😀𠀀\r\n  \t\r\nEnglish words\r\ncontinue here",
            "```\n\ncode\n```\n\n$$\nx = y\n$$\n\n图1：示意\n\n> quote\n> next",
        )
        for (source in samples) for (pdf in listOf(false, true)) {
            val model = bookParagraphs(source, pdf)
            assertEquals(source.length, model.display.length)
            source.indices.filter { !source[it].isWhitespace() }.forEach { assertEquals(source[it], model.display[it]) }
            val marked = bookParagraphText(androidx.compose.ui.text.AnnotatedString(source), model,
                androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 33.sp), 12.sp, 32.sp, 40.sp, 32.sp)
            for (start in source.indices) for (end in start..source.length)
                assertEquals(source.substring(start,end), originalBookSelection(marked.subSequence(start,end)), "$start..$end pdf=$pdf")
        }
        val unicode = bookParagraphs("𠀀𠀀😀abc",false)
        assertTrue(!unicode.paragraphs.single().chinese)
        assertTrue(bookParagraphs("𠀀𠀀𠀀𠀀a",false).paragraphs.single().chinese)
        val code = bookParagraphs(samples[1], true).paragraphs.first()
        assertEquals("```\n\ncode\n```", samples[1].substring(code.start,code.end))
    }

}
