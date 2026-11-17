package com.coach.desktop.books

import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class BookParagraphLayoutTest {
    @Test fun paragraphBoundariesHaveIndentsWithoutEmptyLines() = runSkikoComposeUiTest {
        val source = "第一段中文内容。\n\n第二段中文内容。\n\n- 特殊块\n- 第二行\n\n第三段正文。\n\n# Heading\n\nFirst English paragraph.\n\nSecond English paragraph."
        val model = bookParagraphs(source, false)
        val marked = bookParagraphText(AnnotatedString(source), model,
            TextStyle(fontSize=18.sp,lineHeight=33.3.sp),12.sp,32.sp,40.sp,32.sp)
        var laid:TextLayoutResult? = null
        var normal: List<TextLayoutResult> = emptyList()
        setContent {
            val measurer = rememberTextMeasurer()
            normal = model.paragraphs.map { measurer.measure(marked.subSequence(it.start,it.end), TextStyle(fontSize=18.sp,lineHeight=33.3.sp)) }
            Text(marked, fontSize=18.sp, lineHeight=33.3.sp, inlineContent=bookParagraphInlineContent(marked,model,TextStyle(fontSize=18.sp,lineHeight=33.3.sp),12.sp,32.sp), modifier=Modifier.width(594.dp), onTextLayout={laid=it}) }
        waitForIdle()
        val l=laid!!
        // Exclude the first-line spacer from each normal paragraph's content box.
        // Independent unspaced measurements include font leading, unlike glyph bounds.
        fun top(i:Int)=l.getLineBaseline(l.getLineForOffset(model.paragraphs[i].start))-normal[i].firstBaseline
        fun bottom(i:Int)=l.getLineBaseline(l.getLineForOffset(model.paragraphs[i].end-1))+normal[i].size.height-normal[i].lastBaseline
        for (i in model.paragraphs.indices) {
            val start = model.paragraphs[i].start
            val box = l.getBoundingBox(start)
            assertEquals(start, l.getOffsetForPosition(Offset(box.left + box.width * 0.25f, box.center.y)),
                "hit testing after a spacer keeps the source offset")
            println("BLOCK $i top=${top(i)} bottom=${bottom(i)} left=${box.left}")
        }
        assertEquals(36f,l.getBoundingBox(0).left,1f)
        assertEquals(36f,l.getBoundingBox(model.paragraphs[1].start).left,1f)
        assertEquals(0f,top(1)-bottom(0),2f,"正文段间不留空行")
        assertEquals(12f,top(2)-bottom(1),2f,"特殊块上方12dp")
        assertEquals(12f,top(3)-bottom(2),2f,"特殊块下方12dp")
        assertEquals(32f,top(4)-bottom(3),2f,"标题上方32dp")
        assertEquals(12f,top(5)-bottom(4),2f,"标题下方12dp")
        assertEquals(0f,l.getBoundingBox(model.paragraphs[5].start).left,1f)
        assertEquals(18f,l.getBoundingBox(model.paragraphs[6].start).left,1f)
        assertEquals(0f,top(6)-bottom(5),2f)
        assertEquals(source,originalBookSelection(marked))
        println("PASS book layout: CJK=2em, English=0/1em, body gap=0, special=12dp, heading=32/12dp")
    }

    @Test fun fullChineseLinesKeepFirstLineIndentAndNormalLeading() = runSkikoComposeUiTest {
        val paragraph = "文".repeat(90)
        val source = "$paragraph\n\n$paragraph\n\n# Heading\n\n$paragraph"
        val model = bookParagraphs(source, false)
        val marked = bookParagraphText(AnnotatedString(source), model,
            TextStyle(fontSize=18.sp,lineHeight=33.3.sp),12.sp,32.sp,40.sp,32.sp)
        var laid: TextLayoutResult? = null
        setContent { Text(marked, fontSize=18.sp, lineHeight=33.3.sp, inlineContent=bookParagraphInlineContent(marked,model,TextStyle(fontSize=18.sp,lineHeight=33.3.sp),12.sp,32.sp),
            modifier=Modifier.width(594.dp), onTextLayout={laid=it}) }
        waitForIdle()
        val layout = laid!!
        val leading = layout.getLineBaseline(1) - layout.getLineBaseline(0)
        assertEquals(33.3f, leading, 1f, "line height allows native font metric rounding")
        for (block in model.paragraphs.filter { it.kind == BookParagraph.Kind.BODY }) {
            val first = layout.getLineForOffset(block.start)
            val last = layout.getLineForOffset(block.end - 1)
            assertTrue(last > first, "long Chinese paragraph must wrap")
            assertEquals(36f, layout.getBoundingBox(block.start).left, 0.02f, "first line is 2em in")
            assertEquals(0f, layout.getLineLeft(first + 1), 0.02f, "continuation returns to margin")
            assertEquals(594f, layout.getLineRight(first), 0.1f, "full first line remains justified")
            for (line in first until last)
                assertEquals(leading, layout.getLineBaseline(line + 1) - layout.getLineBaseline(line), 0.1f,
                    "wrapped lines have consistent leading")
        }
        val boundary = layout.getLineForOffset(model.paragraphs[1].start)
        assertEquals(leading, layout.getLineBaseline(boundary) - layout.getLineBaseline(boundary - 1), 0.1f,
            "paragraph boundary has the same leading as wrapped lines")
        assertEquals(source, originalBookSelection(marked))
        println("PASS full Chinese column: first=2em, continuation=0, justified right=594px, baseline=${leading}px (33.3px with native rounding), paragraph boundary matches wrapped lines")
    }
}
