package com.coach.desktop.books

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownHeadingsTest {
    @Test fun headingMarksAndTitlesKeepTheirOriginalOffsets() {
        val text = "## 第1节 细胞\n\n细胞是基本单位。#不是标题\n### 第2节 细胞膜  \r\n#没有空格\n"
        val headings = markdownHeadings(text)
        assertEquals(2, headings.size)
        assertEquals(listOf(2, 3), headings.map { it.level })
        assertEquals(listOf("## ", "### "), headings.map { text.substring(it.marks.first, it.marks.last + 1) })
        assertEquals(listOf("第1节 细胞", "第2节 细胞膜"), headings.map { text.substring(it.title.first, it.title.last + 1) })
    }
}
