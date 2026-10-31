package com.coach.ui.adaptive

/** Window-based, not device-based (DESIGN §8): the same rules run on the Fold8 cover/inner screen, a split-screen window and a Mac window. */
enum class WidthClass { Compact, Medium, Expanded, Large }

enum class NavStyle { BottomBar, IconRail, Rail }

/** How the secondary pane (reference page / outline) is shown. */
enum class AsidePlacement { None, BottomSheet, Overlay, Docked }

/** Vertical hinge in a book posture; panes split on it and no text crosses it. */
data class Hinge(val xDp: Float, val widthDp: Float = 0f)

data class WindowSpec(val widthDp: Float, val heightDp: Float, val hinge: Hinge? = null)

data class PaneLayout(
    val widthClass: WidthClass,
    val shortHeight: Boolean,
    val nav: NavStyle,
    /** Header collapses to a floating KP name when the window is short. */
    val showHeader: Boolean,
    val aside: AsidePlacement,
    val asideWidthDp: Float,
    val outlineDocked: Boolean,
    val outlineWidthDp: Float,
    /** Reading column cap; the conversation is centred within the space it is given. */
    val conversationMaxDp: Float,
    val navWidthDp: Float,
    val dividerAtDp: Float?,
) {
    val dockedPaneCount: Int get() = (if (aside == AsidePlacement.Docked) 1 else 0) + (if (outlineDocked) 1 else 0)
}

object LayoutRules {
    const val ShortHeightDp = 480f
    const val RailDp = 72f
    const val IconRailDp = 56f
    const val ConversationMinDp = 360f
    const val ConversationMaxMediumDp = 680f
    const val ConversationMaxExpandedDp = 720f
    const val ConversationMaxLargeDp = 760f
    const val AsideDp = 360f
    const val AsideLargeDp = 380f
    const val OutlineDp = 300f

    fun widthClass(widthDp: Float) = when {
        widthDp < 600f -> WidthClass.Compact
        widthDp < 840f -> WidthClass.Medium
        widthDp < 1200f -> WidthClass.Expanded
        else -> WidthClass.Large
    }

    /**
     * Panes are added by priority conversation > aside > outline; one that would push the conversation under
     * [ConversationMinDp] is collapsed first (aside to an overlay, outline hidden) before the conversation is squeezed.
     * Height < 480dp beats every width rule: icon rail, no header, nothing docked.
     */
    fun compute(spec: WindowSpec): PaneLayout {
        val w = spec.widthDp; val h = spec.heightDp
        val cls = widthClass(w)
        val short = h < ShortHeightDp
        val book = spec.hinge != null && w >= 700f && h >= 600f
        if (short) return PaneLayout(cls, true, NavStyle.IconRail, false, AsidePlacement.None, 0f, false, 0f, ConversationMaxMediumDp, IconRailDp, null)
        if (cls == WidthClass.Compact) return PaneLayout(cls, false, NavStyle.BottomBar, true, AsidePlacement.BottomSheet, 0f, false, 0f, ConversationMaxMediumDp, 0f, null)
        val asideW = if (cls == WidthClass.Large) AsideLargeDp else AsideDp
        val convMax = when (cls) { WidthClass.Medium -> ConversationMaxMediumDp; WidthClass.Expanded -> ConversationMaxExpandedDp; else -> ConversationMaxLargeDp }
        val twoPane = w >= 840f || book
        var free = w - RailDp
        var asidePlacement = AsidePlacement.Overlay
        var outline = false
        if (twoPane && free - asideW >= ConversationMinDp) { asidePlacement = AsidePlacement.Docked; free -= asideW }
        if (cls == WidthClass.Large && asidePlacement == AsidePlacement.Docked && free - OutlineDp >= ConversationMinDp) { outline = true; free -= OutlineDp }
        val divider = if (book) spec.hinge?.xDp else null
        return PaneLayout(cls, false, NavStyle.Rail, true, asidePlacement, asideW, outline, if (outline) OutlineDp else 0f, convMax, RailDp, divider)
    }
}
