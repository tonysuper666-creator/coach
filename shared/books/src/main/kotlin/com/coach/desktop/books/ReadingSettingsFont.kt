package com.coach.desktop.books

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import java.util.regex.Pattern

internal val LocalReadingFont = compositionLocalOf { ReadingFont.SONG }

// Include variation selectors in the preceding glyph's UTF-16 range.
private const val chineseGlyphs = """\p{IsHan}\p{InCJK_Symbols_and_Punctuation}，．！？：；（）［］｛｝"""
private val chineseRun = Pattern.compile("""[$chineseGlyphs][$chineseGlyphs\uFE00-\uFE0F\x{E0100}-\x{E01EF}]*""")
internal val wenKaiBody by lazy { SpanStyle(fontFamily = bookWenKaiFontFamily()) }

internal fun AnnotatedString.Builder.applyReadingFont(font: ReadingFont, source: String, model: BookParagraphs) {
    if (font == ReadingFont.SONG) return
    val runs = chineseRun.matcher(source)
    for (paragraph in model.paragraphs) {
        if (paragraph.kind != BookParagraph.Kind.BODY) continue
        runs.region(paragraph.start, paragraph.end)
        while (runs.find()) addStyle(wenKaiBody, runs.start(), runs.end())
    }
}
