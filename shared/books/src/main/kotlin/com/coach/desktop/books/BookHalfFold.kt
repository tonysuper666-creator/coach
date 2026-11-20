package com.coach.desktop.books

import androidx.compose.runtime.compositionLocalOf
import com.coach.ui.adaptive.Hinge
import com.coach.ui.adaptive.HingeAxis
import com.coach.ui.adaptive.LayoutRules

/** Local half-fold window hinge threaded from Android platform host to shared textbook screens. */
internal val LocalBookHinge = compositionLocalOf<Hinge?> { null }

internal data class VerticalHingeSplit(val leftDp: Float, val gapDp: Float)

internal data class HorizontalHingeSplit(val topDp: Float, val gapDp: Float)

private const val BookHalfFoldMinPaneDp = 200f
private const val BookTabletopMinTopDp = 160f
private const val BookTabletopMinBottomDp = 140f

/** Computes a vertical book-posture split aligned to the physical hinge coordinate in root space. */
internal fun bookVerticalSplit(
    containerWidthDp: Float,
    containerHeightDp: Float,
    containerLeftDp: Float,
    hinge: Hinge?,
): VerticalHingeSplit? {
    if (hinge == null || hinge.axis != HingeAxis.Vertical || containerHeightDp < 480f) return null
    val gapDp = maxOf(hinge.widthDp, LayoutRules.SpreadGutterDp)
    val leftAvailable = hinge.atDp - containerLeftDp
    val rightAvailable = containerLeftDp + containerWidthDp - (hinge.atDp + gapDp)
    if (leftAvailable < BookHalfFoldMinPaneDp || rightAvailable < BookHalfFoldMinPaneDp) return null
    return VerticalHingeSplit(leftDp = leftAvailable, gapDp = gapDp)
}

/** Computes a horizontal tabletop-posture split aligned to the physical hinge coordinate in root space. */
internal fun bookTabletopSplit(
    containerWidthDp: Float,
    containerHeightDp: Float,
    containerTopDp: Float,
    hinge: Hinge?,
): HorizontalHingeSplit? {
    if (hinge == null || hinge.axis != HingeAxis.Horizontal || containerWidthDp < LayoutRules.TabletopMinWidthDp) return null
    val gapDp = maxOf(hinge.widthDp, LayoutRules.SpreadGutterDp)
    val topAvailable = hinge.atDp - containerTopDp
    val bottomAvailable = containerTopDp + containerHeightDp - (hinge.atDp + gapDp)
    if (topAvailable < BookTabletopMinTopDp || bottomAvailable < BookTabletopMinBottomDp) return null
    return HorizontalHingeSplit(topDp = topAvailable, gapDp = gapDp)
}
