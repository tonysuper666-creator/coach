package com.coach.ui.v7

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.LocalCoachColors

/** Stroke and gap of the V7 keyboard focus ring. The gap keeps the ring readable on filled (ink) buttons. */
val V7FocusRingWidth = 2.dp
val V7FocusRingGap = 2.dp

/**
 * The one V7 keyboard focus indicator: a [V7FocusRingWidth] ring in `CoachColors.focusRing` (ink),
 * drawn [V7FocusRingGap] outside the control while the next focus target in the chain holds focus.
 * Place it before the control's clip and clickable; nothing is drawn and layout is unchanged when unfocused.
 */
fun Modifier.v7FocusRing(shape: Shape = RoundedCornerShape(CoachV7.Radius.btn)): Modifier = this then V7FocusRingElement(shape)

private data class V7FocusRingElement(val shape: Shape) : ModifierNodeElement<V7FocusRingNode>() {
    override fun create() = V7FocusRingNode(shape)
    override fun update(node: V7FocusRingNode) { node.shape = shape; node.invalidateDraw() }
    override fun InspectorInfo.inspectableProperties() { name = "v7FocusRing"; properties["shape"] = shape }
}

private class V7FocusRingNode(var shape: Shape) : Modifier.Node(), FocusEventModifierNode, DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focused == focusState.isFocused) return
        focused = focusState.isFocused
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        val stroke = V7FocusRingWidth.toPx()
        val offset = V7FocusRingGap.toPx() + stroke / 2
        val ring = Size(size.width + offset * 2, size.height + offset * 2)
        val outline = shape.createOutline(ring, layoutDirection, this)
        translate(-offset, -offset) { drawOutline(outline, currentValueOf(LocalCoachColors).focusRing, style = Stroke(stroke)) }
    }
}
