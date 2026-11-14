package com.coach.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Optional synthetic-test receipt; production does not install a listener. */
internal val LocalFeedbackLayoutTrace = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Read live coordinates together after placement instead of mixing cached layout generations. */
internal class LessonFeedbackCoordinates {
    var container: LayoutCoordinates? = null
    var viewport: LayoutCoordinates? = null
    var actions: LayoutCoordinates? = null
    var actionHeight: Int? = null
    var feedback: LayoutCoordinates? = null
    var citation: LayoutCoordinates? = null

    fun snapshot(scroll: Int, maxScroll: Int, appliedInset: Int, docked: Boolean): LessonFeedbackGeometry? {
        val box = container?.takeIf { it.isAttached } ?: return null
        val view = viewport?.takeIf { it.isAttached } ?: return null
        val body = feedback?.takeIf { it.isAttached } ?: return null
        val source = citation?.takeIf { it.isAttached } ?: return null
        val barHeight = if (docked) {
            actions?.takeIf { it.isAttached } ?: return null
            actionHeight ?: return null
        } else 0
        val top = view.positionInRoot().y
        return LessonFeedbackGeometry(box.size.height, barHeight, appliedInset,
            top, top + view.size.height, view.size.height,
            body.positionInRoot().y + scroll, body.positionInRoot().y + body.size.height + scroll,
            source.positionInRoot().y + source.size.height + scroll, scroll, maxScroll)
    }
}

internal data class LessonFeedbackGeometry(
    val containerHeight: Int, val actionHeight: Int, val appliedInset: Int,
    val viewportTop: Float, val viewportBottom: Float, val viewportHeight: Int,
    val feedbackTop: Float, val feedbackBottom: Float, val citationBottom: Float,
    val scroll: Int, val maxScroll: Int
) {
    fun ready(minInset: Int): Boolean {
        val expectedInset = maxOf(actionHeight, minInset)
        return viewportHeight > 0 && abs(containerHeight - viewportHeight - expectedInset) <= 1 &&
            abs(appliedInset - expectedInset) <= 1
    }

    fun target(tail: Float, citationClearance: Float): Int {
        val lo = ceil(maxOf(feedbackBottom + tail, citationBottom + citationClearance) - viewportBottom).toInt().coerceAtLeast(0)
        val hi = floor(feedbackTop - viewportTop).toInt().coerceAtMost(maxScroll)
        // A fitting section keeps its opening and its tail visible. Oversized explanations
        // expose the citation and selected reason; their opening remains scrollable above.
        return if (lo <= hi) lo else lo.coerceAtMost(maxScroll)
    }

    fun settled(target: Int, tail: Float, citationClearance: Float): Boolean {
        if (abs(scroll - target) > 1) return false
        val lo = ceil(maxOf(feedbackBottom + tail, citationBottom + citationClearance) - viewportBottom).toInt().coerceAtLeast(0)
        val hi = floor(feedbackTop - viewportTop).toInt().coerceAtMost(maxScroll)
        val tailVisible = feedbackBottom - scroll + tail <= viewportBottom + 1f &&
            citationBottom - scroll + citationClearance <= viewportBottom + 1f
        return tailVisible && (lo > hi || feedbackTop - scroll >= viewportTop - 1f)
    }
}
