package com.coach.ui.v7

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
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

/**
 * Material [TextButton] carrying the V7 focus ring on its visible 40dp pill.
 * Material normally adds the 48dp touch box inside the button, so a ring on the caller's modifier would wrap
 * that box instead. Here the same centred 48dp box is laid outside the ring; size, touch area and look are unchanged.
 * [contentPadding] is passed to Material as is. [ringInside] draws the same ring just inside the pill's edge instead,
 * for a pill that sits on a clipping edge or right against a neighbour; layout and unfocused pixels stay the same.
 * Both ring placements show only while input is from the keyboard, so focus Material moves on its own after a click
 * (a drawer focusing its first button when it opens) stays as invisible as it is in Material.
 */
@Composable
fun V7TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    ringInside: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val touch: Dp = LocalMinimumInteractiveComponentSize.current
    val touchBox = if (touch.isSpecified) Modifier.sizeIn(minWidth = touch, minHeight = touch).wrapContentSize() else Modifier
    val ring = if (ringInside) V7FocusRingElement(ButtonDefaults.textShape, V7FocusRingGap + V7FocusRingWidth, keyboardOnly = true)
        else V7FocusRingElement(ButtonDefaults.textShape)
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        TextButton(onClick, modifier.then(touchBox).then(ring), enabled, contentPadding = contentPadding) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides touch) { content() }
        }
    }
}

/**
 * [inset] moves the ring inward by that much: gap + width puts the ring just inside the control's own edge.
 * [keyboardOnly] hides the ring while the input mode is not [InputMode.Keyboard].
 */
private data class V7FocusRingElement(val shape: Shape, val inset: Dp = 0.dp, val keyboardOnly: Boolean = true) :
    ModifierNodeElement<V7FocusRingNode>() {
    override fun create() = V7FocusRingNode(shape, inset, keyboardOnly)
    override fun update(node: V7FocusRingNode) { node.shape = shape; node.inset = inset; node.keyboardOnly = keyboardOnly; node.invalidateDraw() }
    override fun InspectorInfo.inspectableProperties() {
        name = "v7FocusRing"; properties["shape"] = shape; properties["inset"] = inset; properties["keyboardOnly"] = keyboardOnly
    }
}

private class V7FocusRingNode(var shape: Shape, var inset: Dp, var keyboardOnly: Boolean) : Modifier.Node(), FocusEventModifierNode, DrawModifierNode,
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
        if (keyboardOnly && currentValueOf(LocalInputModeManager).inputMode != InputMode.Keyboard) return
        val stroke = V7FocusRingWidth.toPx()
        val offset = V7FocusRingGap.toPx() + stroke / 2 - inset.toPx()
        val ring = Size(size.width + offset * 2, size.height + offset * 2)
        val outline = shape.createOutline(ring, layoutDirection, this)
        translate(-offset, -offset) { drawOutline(outline, currentValueOf(LocalCoachColors).focusRing, style = Stroke(stroke)) }
    }
}
