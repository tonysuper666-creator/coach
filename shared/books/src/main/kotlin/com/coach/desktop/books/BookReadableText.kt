package com.coach.desktop.books

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.em

/**
 * 给读者看的文字只说人话：不露书的哈希、块编号、服务端的【S1】引用标记。
 * 这里是纯文本规则，界面（选段就问、讲解卡、答后讲解）都走这一套，测试也直接测它。
 */
internal object ReadableText {
    /** 服务端在正文里写的引用标记：【S1】、[S1]、【S1，S2】、［S2］，连同前面的空格。 */
    val CITATION_MARK = Regex("""[ \t　]*[【\[［]\s*(S\d+(?:\s*[,，、;；]\s*S\d+)*)\s*[】\]］]""")

    /** 用户可见文字里不该出现的内部格式：书的哈希块编号（`0123abcd…:p12` / `<40 位十六进制>:p12:340`）和引用标记。 */
    val INTERNAL_ID = Regex("""[0-9a-f]{8,}…?:p\d+|[【\[［]\s*S\d+\s*[】\]］]""")

    /** 去掉所有引用标记；出处由正文下面那一行「出处 · 第 N 页」给出。 */
    fun withoutCitationMarks(text: String): String = text.replace(CITATION_MARK, "").trim()

    /** 一个标记里引用的锚点，`【S1，S2】` → `[S1, S2]`。 */
    fun anchorsIn(mark: MatchResult): List<String> = mark.groupValues[1].split(Regex("""\s*[,，、;；]\s*""")).filter { it.isNotBlank() }

    /**
     * PDF 抽出来的段落每行都带硬换行。按段落重排：段内换行接起来（中文之间不加空格，西文之间加一个），
     * 空行才算分段。没有换行的文字原样返回，所以原本就是一段的原文一个字都不变。
     */
    fun reflow(text: String): String {
        if ('\n' !in text && '\r' !in text) return text
        val paragraphs = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("""\n[ \t　]*\n+"""))
        return paragraphs.map { paragraph ->
            paragraph.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.fold(StringBuilder()) { out, line ->
                if (out.isNotEmpty() && needsSpace(out.last(), line.first())) out.append(' ')
                out.append(line)
            }.toString()
        }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun needsSpace(before: Char, after: Char): Boolean =
        !(wide(before) || wide(after) || before == '-' || before == '–' || before == '—' || before == '/')

    /** 中日文字和全角标点：两边接行不加空格。 */
    private fun wide(c: Char): Boolean = Character.isSurrogate(c) || Character.UnicodeBlock.of(c) in WIDE

    private val WIDE = setOf(
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS, Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS, Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
        Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS, Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA,
    )

    /**
     * 讲解正文：认得的引用标记换成小号上标数字（第几条出处），点了打开那条出处；认不得的标记直接去掉。
     * [anchors] 是出处的顺序，序号从 1 起，和下面「出处」一行的序号一致。
     */
    fun cited(text: String, anchors: List<String>, link: TextLinkStyles?, onOpen: (Int) -> Unit): AnnotatedString = buildAnnotatedString {
        var at = 0
        val body = text.trim()
        for (mark in CITATION_MARK.findAll(body)) {
            append(body.substring(at, mark.range.first))
            at = mark.range.last + 1
            val indexes = anchorsIn(mark).map(anchors::indexOf).filter { it >= 0 }.distinct()
            for (index in indexes) {
                withLink(LinkAnnotation.Clickable("source-$index", link) { onOpen(index) }) {
                    pushStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.72.em, fontWeight = FontWeight.Medium))
                    append(superscriptLabel(index))
                    pop()
                }
            }
        }
        append(body.substring(at))
    }

    /** 一句报错是不是写给人看的中文。库和系统抛出的英文（`Failed requirement.`、PDF 解析的原话）不直接给读者看。 */
    fun readsAsChinese(message: String?): Boolean = message != null && message.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS }

    /** 出处的序号：1、2、3…，两条挨着时中间用细空格隔开不粘连。 */
    fun superscriptLabel(index: Int): String = " ${index + 1}"
}

/** 出处一行用的那句短引文：「“前 16 字…”」，按码点截，不切断代理对；换行先接上。 */
internal fun shortQuote(text: String): String {
    // Imported Markdown can store a physical-page marker and its first paragraph in the same source block.
    val source = text.trimStart()
    val marker = PRINTED_PAGE_MARK.matchAt(source, 0)
    val prose = if (marker == null) source else source.substring(marker.range.last + 1)
    val flat = prose.replace(Regex("""\s+"""), " ").trim()
    val points = flat.codePointCount(0, flat.length)
    return "“" + (if (points <= 16) flat else flat.substring(0, flat.offsetByCodePoints(0, 16)).trimEnd() + "…") + "”"
}

@Composable
internal fun v7LinkStyles(): TextLinkStyles = TextLinkStyles(style = SpanStyle(color = v7Pine()))
