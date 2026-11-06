package com.coach.ai

import com.coach.core.ExamTextGuard

/** Last gate, after visible-text transformations and before WS/ledger publication. */
class ExamDelivery(private val newGuard: () -> ExamTextGuard) {
    private var guard = newGuard()
    private val pending = StringBuilder()
    val source = StringBuilder()
    val blocked: Boolean get() = guard.blocked

    fun push(segment: VisibleStream.Segment): List<VisibleStream.Segment> {
        val markdown = markdown(segment)
        source.append(markdown)
        return typed(guard.push(markdown))
    }

    fun finish(): List<VisibleStream.Segment> = typed(guard.finish(), final = true)

    fun replace(markdown: String): List<VisibleStream.Segment> {
        guard = newGuard()
        pending.setLength(0)
        source.setLength(0)
        source.append(markdown)
        return typed(guard.push(markdown))
    }

    // Only restore typed formulas. Removing any text after the final word check
    // could join previously separated words into an unchecked exam quotation.
    private fun typed(text: String, final: Boolean = false): List<VisibleStream.Segment> {
        pending.append(text)
        val out = ArrayList<VisibleStream.Segment>()
        while (pending.isNotEmpty()) {
            val open = pending.indexOf("\$\$")
            if (open < 0) {
                val end = pending.length - if (!final && pending.last() == '$') 1 else 0
                if (end > 0) { out += VisibleStream.Segment.Text(pending.substring(0, end)); pending.delete(0, end) }
                break
            }
            if (open > 0) { out += VisibleStream.Segment.Text(pending.substring(0, open)); pending.delete(0, open) }
            val close = pending.indexOf("\$\$", 2)
            if (close < 0) {
                if (final) { out += VisibleStream.Segment.Text(pending.toString()); pending.setLength(0) }
                break
            }
            out += VisibleStream.Segment.Math(pending.substring(2, close), true)
            pending.delete(0, close + 2)
        }
        return out
    }

    companion object {
        fun markdown(segment: VisibleStream.Segment): String = when (segment) {
            is VisibleStream.Segment.Text -> segment.text
            is VisibleStream.Segment.Math -> "\$\$${segment.tex}\$\$"
        }
    }
}
