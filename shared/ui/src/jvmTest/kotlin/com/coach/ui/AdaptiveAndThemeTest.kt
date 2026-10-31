package com.coach.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.coach.ui.adaptive.*
import com.coach.ui.input.EnterGate
import com.coach.ui.theme.CoachColors
import kotlin.math.roundToInt
import kotlin.test.*

class AdaptiveAndThemeTest {
    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble(); val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test fun textColoursAreReadableInLightAndDark() {
        for ((name, colors) in listOf("light" to CoachColors.light(), "dark" to CoachColors.dark())) {
            for ((fg, bg) in colors.textPairs()) assertTrue(contrast(fg, bg) >= 4.5, "$name $fg on $bg = ${contrast(fg, bg)}")
        }
        // Tertiary text is decorative/disabled only, but dark mode keeps it ≥4.5 on the card surface (DESIGN §9).
        assertTrue(contrast(CoachColors.dark().fgTertiary, CoachColors.dark().surface1) >= 4.5)
    }

    // 420 dpi = density 2.625: the two Fold8 screens in px -> dp.
    private fun dp(px: Int) = (px / 2.625f)
    private val outer = WindowSpec(dp(1248), dp(1972))
    private val inner = WindowSpec(dp(1848), dp(2448))

    @Test fun foldedAndUnfoldedWindowsGetTheirOwnLayout() {
        assertEquals(475, outer.widthDp.roundToInt()); assertEquals(751, outer.heightDp.roundToInt())
        assertEquals(704, inner.widthDp.roundToInt()); assertEquals(933, inner.heightDp.roundToInt())
        val o = LayoutRules.compute(outer); val i = LayoutRules.compute(inner)
        assertEquals(WidthClass.Compact, o.widthClass); assertEquals(NavStyle.BottomBar, o.nav); assertEquals(AsidePlacement.BottomSheet, o.aside)
        assertEquals(WidthClass.Medium, i.widthClass); assertEquals(NavStyle.Rail, i.nav); assertEquals(AsidePlacement.Overlay, i.aside)
    }

    @Test fun shortWindowBeatsWidthRules() {
        val l = LayoutRules.compute(WindowSpec(dp(1972), dp(1248) - 1f)) // outer landscape: 751 x 475
        assertTrue(l.shortHeight); assertEquals(NavStyle.IconRail, l.nav); assertFalse(l.showHeader); assertEquals(AsidePlacement.None, l.aside)
    }

    @Test fun conversationNeverDropsBelowItsMinimumWhenPanesAreDocked() {
        var w = 360f
        while (w <= 1700f) {
            var h = 480f
            while (h <= 1300f) {
                val l = LayoutRules.compute(WindowSpec(w, h))
                if (l.dockedPaneCount > 0) {
                    val conversation = w - l.navWidthDp - l.outlineWidthDp - (if (l.aside == AsidePlacement.Docked) l.asideWidthDp else 0f)
                    assertTrue(conversation >= LayoutRules.ConversationMinDp, "width $w x $h leaves $conversation dp")
                }
                h += 120f
            }
            w += 1f
        }
    }

    @Test fun wideWindowsAddPanesInPriorityOrder() {
        val expanded = LayoutRules.compute(WindowSpec(933f, 704f))
        assertEquals(AsidePlacement.Docked, expanded.aside); assertFalse(expanded.outlineDocked)
        val large = LayoutRules.compute(WindowSpec(1260f, 800f))
        assertEquals(AsidePlacement.Docked, large.aside); assertTrue(large.outlineDocked)
        // Not enough room for both: outline goes first, then the aside collapses to an overlay; the conversation is squeezed last.
        val tight = LayoutRules.compute(WindowSpec(1200f, 800f))
        assertEquals(AsidePlacement.Docked, tight.aside)
        val narrowExpanded = LayoutRules.compute(WindowSpec(840f, 700f))
        assertEquals(AsidePlacement.Docked, narrowExpanded.aside) // 840 - 72 - 360 = 408 >= 360
        assertEquals(AsidePlacement.Overlay, LayoutRules.compute(WindowSpec(780f, 900f)).aside)
    }

    @Test fun bookPostureSplitsOnTheHinge() {
        val l = LayoutRules.compute(WindowSpec(933f, 704f, Hinge(466f)))
        assertEquals(466f, l.dividerAtDp)
        assertNull(LayoutRules.compute(WindowSpec(933f, 704f)).dividerAtDp)
    }

    private class Clock(var ms: Long = 10_000) { fun now() = ms }

    @Test fun enterNeverSendsInsideAComposition() {
        val clock = Clock(); val gate = EnterGate(60L) { clock.now() }
        assertTrue(gate.shouldSend(true, keyDown = true, shift = false, composingNow = false))
        assertFalse(gate.shouldSend(true, keyDown = true, shift = false, composingNow = true), "composing")
        assertFalse(gate.shouldSend(true, keyDown = false, shift = false, composingNow = false), "KeyUp")
        assertFalse(gate.shouldSend(true, keyDown = true, shift = true, composingNow = false), "Shift+Enter is a newline")
        assertFalse(gate.shouldSend(false, keyDown = true, shift = false, composingNow = false), "phone: Enter is a newline")
    }

    @Test fun enterThatConfirmsACompositionIsSwallowedEvenWhenCommitArrivesFirst() {
        val clock = Clock(); val gate = EnterGate(60L) { clock.now() }
        // IME: 'ni' composing -> candidate committed; the Enter that confirmed it is delivered after the commit.
        gate.onEdit(composingBefore = false, composingAfter = true)
        clock.ms += 30; gate.onEdit(composingBefore = true, composingAfter = false)
        clock.ms += 5
        assertFalse(gate.shouldSend(true, keyDown = true, shift = false, composingNow = false), "the confirming Enter must not send")
        // A deliberate Enter afterwards sends.
        clock.ms += 400
        assertTrue(gate.shouldSend(true, keyDown = true, shift = false, composingNow = false))
        // Single-letter composition confirmed by Enter, then Enter again.
        gate.onEdit(false, true); clock.ms += 10; gate.onEdit(true, false); clock.ms += 3
        assertFalse(gate.shouldSend(true, true, false, false))
        clock.ms += 300
        assertTrue(gate.shouldSend(true, true, false, false))
    }
}
