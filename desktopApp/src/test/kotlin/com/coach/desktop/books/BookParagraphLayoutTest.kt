package com.coach.desktop.books

import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class BookParagraphLayoutTest {
    @Test fun paragraphBoundariesHaveIndentsWithoutEmptyLines() = runSkikoComposeUiTest {
        val source = "第一段中文内容。\n\n第二段中文内容。\n\n- 特殊块\n- 第二行\n\n第三段正文。\n\n# Heading\n\nFirst English paragraph.\n\nSecond English paragraph."
        val model = bookParagraphs(source, false)
        val marked = bookParagraphText(AnnotatedString(source), model,
            TextStyle(fontSize=18.sp,lineHeight=33.3.sp),12.sp,32.sp,40.sp,32.sp)
        var laid:TextLayoutResult? = null
        setContent { Text(marked, fontSize=0.1.sp, lineHeight=0.1.sp, modifier=Modifier.width(594.dp), onTextLayout={laid=it}) }
        waitForIdle()
        val l=laid!!
        fun top(i:Int)=l.getBoundingBox(model.paragraphs[i].start).top
        fun bottom(i:Int)=l.getBoundingBox(model.paragraphs[i].end-1).bottom
        for(i in model.paragraphs.indices) println("BLOCK $i top=${top(i)} bottom=${bottom(i)} left=${l.getBoundingBox(model.paragraphs[i].start).left}")
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
}
