@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.coach.desktop.books

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

private const val ORIGINAL_CHARACTER = "coach.book.original-character"

/** Display and source share UTF-16 offsets, including supplementary characters and CRLF. */
internal fun bookParagraphText(
    original: AnnotatedString, model: BookParagraphs, body: TextStyle,
    smallGap: TextUnit, headingGap: TextUnit, chapterLine: TextUnit, sectionLine: TextUnit,
): AnnotatedString = buildAnnotatedString {
    append(model.display)
    addStyle(body.toSpanStyle(), 0, length)
    // Heading and citation spans override the base typography, using unchanged source ranges.
    original.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
    for ((i, paragraph) in model.paragraphs.withIndex()) {
        val next = model.paragraphs.getOrNull(i + 1)
        val gap = when {
            next == null -> 0.sp
            next.kind == BookParagraph.Kind.HEADING -> headingGap
            paragraph.kind != BookParagraph.Kind.BODY || next.kind != BookParagraph.Kind.BODY -> smallGap
            else -> 0.sp
        }
        val end = if (gap.value == 0f) next?.start ?: length else paragraph.end
        addStyle(ParagraphStyle(
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None, LineHeightStyle.Mode.Fixed),
            textAlign = if (paragraph.kind == BookParagraph.Kind.BODY && paragraph.chinese) TextAlign.Justify else TextAlign.Start,
            textIndent = TextIndent(body.fontSize * paragraph.indentEm, 0.sp),
            lineHeight = if (paragraph.kind != BookParagraph.Kind.HEADING) body.lineHeight
                else if (original.text.substring(paragraph.start, paragraph.end).startsWith("# ")) chapterLine else sectionLine,
        ), if (i == 0) 0 else paragraph.start, end)
        if (next != null && gap.value > 0f) {
            addStyle(ParagraphStyle(lineHeight = gap,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None, LineHeightStyle.Mode.Fixed)), paragraph.end, next.start)
            addStyle(SpanStyle(fontSize = 0.1.sp), paragraph.end, next.start)
        }
    }
    for (i in original.indices) if (original[i] != model.display[i])
        addStringAnnotation(ORIGINAL_CHARACTER, original[i].toString(), i, i + 1)
}

/** SelectionContainer clips these one-character annotations together with the selected text. */
internal fun originalBookSelection(selected: AnnotatedString): String {
    val restored = selected.text.toCharArray()
    selected.getStringAnnotations(ORIGINAL_CHARACTER, 0, selected.length).forEach {
        if (it.start in restored.indices) restored[it.start] = it.item.single()
    }
    return String(restored)
}

internal class BookClipboard(private val delegate: Clipboard) : Clipboard by delegate {
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val transferable = clipEntry?.asAwtTransferable
        val flavor = DataFlavor(AnnotatedString::class.java, "AnnotatedString")
        val selected = if (transferable?.isDataFlavorSupported(flavor) == true)
            transferable.getTransferData(flavor) as? AnnotatedString else null
        if (selected != null && selected.getStringAnnotations(ORIGINAL_CHARACTER, 0, selected.length).isNotEmpty())
            delegate.setClipEntry(ClipEntry(StringSelection(originalBookSelection(selected))))
        else delegate.setClipEntry(clipEntry)
    }
}

@Composable
internal fun BookSelectionClipboard(content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val originalClipboard = remember(clipboard) { BookClipboard(clipboard) }
    CompositionLocalProvider(LocalClipboard provides originalClipboard, content = content)
}
