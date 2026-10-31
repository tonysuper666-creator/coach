package com.coach.ui.markdown

import com.coach.domain.math.MathSegment

private fun isCjk(c: Char?) = c != null && (c in '㐀'..'鿿' || c in '　'..'〿' || c in '＀'..'￯')
private val sentenceEnd = "。！？：；.!?:;"
private val closingPunctuation = "，。、；：！？,.;:!?)）】」』”"

/**
 * Model text often puts a `$$…$$` formula in the middle of a sentence ("医疗支出为 $$5$$ 美元"). A display formula gets its own
 * line only when it stands on its own; one with text on both sides of the same line stays in the line.
 * Then the stray spaces around formulas next to Chinese text are dropped ("$$5$$ 美元" → "5美元"), and whitespace left at a
 * display formula's edges goes away so the following text does not start with a blank.
 */
internal fun layoutMathSegments(tokens: List<MathSegment>): List<MathSegment> {
    val out = tokens.toMutableList()
    for (i in out.indices) {
        val s = out[i]
        if (s.math == null || !s.display) continue
        val before = out.getOrNull(i - 1)?.text?.trimEnd(' ', '\t')
        val after = out.getOrNull(i + 1)?.text?.trimStart(' ', '\t')
        val standsInSentence = !before.isNullOrEmpty() && !before.endsWith("\n") && before.last() !in sentenceEnd &&
            !after.isNullOrEmpty() && !after.startsWith("\n")
        // A formula followed straight by punctuation ("…=250" then "。") keeps the punctuation on its line instead of orphaning it.
        val punctuationFollows = out.getOrNull(i + 1)?.text?.trimStart()?.firstOrNull()?.let { it in closingPunctuation } == true
        if (standsInSentence || punctuationFollows) out[i] = s.copy(display = false)
    }
    for (i in out.indices) {
        val s = out[i]
        val text = s.text ?: continue
        val prevMath = out.getOrNull(i - 1)?.math != null
        val nextMath = out.getOrNull(i + 1)?.math != null
        var t = text
        if (prevMath) {
            val prevDisplay = out[i - 1].display
            val stripped = t.trimStart(' ', '\t')
            // Text after a display formula starts a fresh line; after an inline one only a space next to CJK is stray.
            val closes = t.trimStart().firstOrNull()?.let { it in closingPunctuation } == true
            t = if (prevDisplay || closes) t.trimStart() else if (isCjk(stripped.firstOrNull())) stripped else t
        }
        if (nextMath) {
            val nextDisplay = out[i + 1].display
            val stripped = t.trimEnd(' ', '\t')
            t = if (nextDisplay) t.trimEnd() else if (isCjk(stripped.lastOrNull())) stripped else t
        }
        if (t != text) out[i] = s.copy(text = t)
    }
    return out.filterNot { it.text != null && it.text!!.isEmpty() }
}
