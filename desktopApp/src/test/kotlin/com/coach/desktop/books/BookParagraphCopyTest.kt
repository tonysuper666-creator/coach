@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.coach.desktop.books

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import java.awt.datatransfer.DataFlavor
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class BookParagraphCopyTest {
    @Test fun realSelectionCopyRestoresParagraphAndPdfNewlines() = runSkikoComposeUiTest {
        val source = "# 章节\r\n\r\n中文段落接\r\n下文。\r\n\r\nEnglish words\ncontinue here."
        val model = bookParagraphs(source, true)
        val displayed = bookParagraphText(AnnotatedString(source), model,
            TextStyle(fontSize=18.sp,lineHeight=33.3.sp),12.sp,32.sp,40.sp,32.sp)
        val state = SelectionState()
        var copied: ClipEntry? = null
        val clipboard = object : Clipboard {
            override suspend fun getClipEntry() = copied
            override suspend fun setClipEntry(clipEntry: ClipEntry?) { copied = clipEntry }
        }
        setContent { CompositionLocalProvider(LocalClipboard provides clipboard) {
            BookSelectionClipboard { SelectionContainer(state) {
                Text(displayed, fontSize=18.sp,lineHeight=33.3.sp,inlineContent=bookParagraphInlineContent(displayed,model,TextStyle(fontSize=18.sp,lineHeight=33.3.sp),12.sp,32.sp),modifier=Modifier.width(594.dp).testTag("copy-text"))
            } }
        } }
        onNodeWithTag("copy-text").performMouseInput { click(Offset(45f,15f)) }
        runOnIdle { state.selectAll() }
        waitForIdle()
        assertTrue(state.selectedTexts.isNotEmpty())
        val copyModifier = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft
        onNodeWithTag("copy-text").performKeyInput { keyDown(copyModifier); pressKey(Key.C); keyUp(copyModifier) }
        waitUntil { copied != null }
        val plain = copied!!.asAwtTransferable!!.getTransferData(DataFlavor.stringFlavor) as String
        assertEquals(source, plain)
        assertEquals(source,originalBookSelection(state.selectedTexts.single()))
        println("PASS real Compose selection + $copyModifier+C: CRLF, paragraph separators, Han and western PDF joins restored")
    }
}
