@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.coach.desktop.books

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private const val ORIGINAL_CHARACTER = "coach.book.original-character"

private fun gapId(start: Int) = "coach.book.gap.$start"

/** Put spacing above the next first baseline, avoiding Skia's minimum empty-line height. */
@Composable
internal fun bookParagraphInlineContent(
    text: AnnotatedString, model: BookParagraphs, body: TextStyle,
    smallGap: TextUnit, headingGap: TextUnit,
): Map<String, InlineTextContent> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return model.paragraphs.mapIndexedNotNull { i, paragraph ->
        val previous = model.paragraphs.getOrNull(i - 1) ?: return@mapIndexedNotNull null
        val gap = paragraphGap(previous, paragraph, smallGap, headingGap)
        if (gap.value == 0f) return@mapIndexedNotNull null
        // Use the actual paragraph font, heading spans and line-height policy. The
        // placeholder extends only its first ascent; wrapped lines keep normal leading.
        val normal = measurer.measure(text.subSequence(paragraph.start, paragraph.end), body)
        val height = with(density) { (normal.firstBaseline + gap.toPx()).toSp() }
        gapId(paragraph.start) to InlineTextContent(
            Placeholder(0.sp, height, PlaceholderVerticalAlign.AboveBaseline)
        ) {}
    }.toMap()
}

private fun paragraphGap(current: BookParagraph, next: BookParagraph?, small: TextUnit, heading: TextUnit) = when {
    next == null -> 0.sp
    next.kind == BookParagraph.Kind.HEADING -> heading
    current.kind != BookParagraph.Kind.BODY || next.kind != BookParagraph.Kind.BODY -> small
    else -> 0.sp
}

/** Display and source share UTF-16 offsets, including supplementary characters and CRLF. */
internal fun bookParagraphText(
    original: AnnotatedString, model: BookParagraphs, body: TextStyle,
    smallGap: TextUnit, headingGap: TextUnit, chapterLine: TextUnit, sectionLine: TextUnit,
): AnnotatedString = buildAnnotatedString {
    var copiedUntil = 0
    for ((i, paragraph) in model.paragraphs.withIndex()) {
        val next = model.paragraphs.getOrNull(i + 1)
        val gap = paragraphGap(paragraph, next, smallGap, headingGap)
        if (next != null && gap.value > 0f) {
            append(model.display.substring(copiedUntil, paragraph.end))
            // Skia represents each placeholder as one UTF-16 character. Cover exactly
            // one separator so hit-testing and all subsequent source offsets stay aligned.
            appendInlineContent(gapId(next.start), model.display.substring(paragraph.end, paragraph.end + 1))
            copiedUntil = paragraph.end + 1
        }
    }
    append(model.display.substring(copiedUntil))
    addStyle(body.toSpanStyle(), 0, length)
    // Heading and citation spans override the base typography, using unchanged source ranges.
    original.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
    for ((i, paragraph) in model.paragraphs.withIndex()) {
        val next = model.paragraphs.getOrNull(i + 1)
        val gap = paragraphGap(paragraph, next, smallGap, headingGap)
        val end = if (gap.value == 0f) next?.start ?: length else paragraph.end
        // Skia TextLine::format returns before applying the indent when a line fits
        // exactly (maxWidth - width - indent == 0). This occurs often in a 33em
        // Chinese column. A subpixel allowance keeps the normal justify path;
        // it neither inserts characters nor changes selection/source offsets.
        val indent = if (paragraph.indentEm == 0) 0.sp
            else (body.fontSize.value * paragraph.indentEm - 0.001f).sp
        addStyle(ParagraphStyle(
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
            textAlign = if (paragraph.kind == BookParagraph.Kind.BODY && paragraph.chinese) TextAlign.Justify else TextAlign.Start,
            textIndent = TextIndent(indent, 0.sp),
            lineHeight = if (paragraph.kind != BookParagraph.Kind.HEADING) body.lineHeight
                else if (original.text.substring(paragraph.start, paragraph.end).startsWith("# ")) chapterLine else sectionLine,
        ), when {
            i == 0 -> 0
            paragraphGap(model.paragraphs[i - 1], paragraph, smallGap, headingGap).value > 0f -> model.paragraphs[i - 1].end
            else -> paragraph.start
        }, end)
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
        val selected = bookClipboardSelection(clipEntry)
        if (selected != null && selected.getStringAnnotations(ORIGINAL_CHARACTER, 0, selected.length).isNotEmpty())
            delegate.setClipEntry(bookClipboardText(originalBookSelection(selected)))
        else delegate.setClipEntry(clipEntry)
    }
}

@Composable
internal fun BookSelectionClipboard(content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val originalClipboard = remember(clipboard) { BookClipboard(clipboard) }
    CompositionLocalProvider(LocalClipboard provides originalClipboard, content = content)
}
