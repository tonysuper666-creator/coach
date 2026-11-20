package com.coach.ui.adaptive

/** Window-based, not device-based (DESIGN §8): the same rules run on the Fold8 cover/inner screen, a split-screen window and a Mac window. */
enum class WidthClass { Compact, Medium, Expanded, Large }

enum class NavStyle { BottomBar, IconRail, Rail }

/** How the secondary pane (reference page / outline) is shown. */
enum class AsidePlacement { None, BottomSheet, Overlay, Docked }

enum class HingeAxis { Vertical, Horizontal }

/**
 * Hinge reported by the window (not guessed from the screen): [atDp] is the distance from the start edge of the window
 * to the hinge line — x for a vertical hinge (book posture), y for a horizontal one (half-folded, sitting on a table).
 */
data class Hinge(val atDp: Float, val widthDp: Float = 0f, val axis: HingeAxis = HingeAxis.Vertical)

data class WindowSpec(val widthDp: Float, val heightDp: Float, val hinge: Hinge? = null)

/**
 * How a reading surface opens up: the same rule for the original PDF (TON-1723) and the text reader (TON-1724),
 * so neither lane invents its own spread threshold. Measured on the whole window — see [LayoutRules.readingSpread].
 */
data class ReadingSpread(
    /** 1 = one page; 2 = facing pages with the spine on the hinge. */
    val columns: Int,
    /** Empty band kept clear next to the spine (the hinge itself plus some air); 0 for a single page. */
    val gutterDp: Float,
    /** Width of one page; the window width when [columns] is 1. */
    val pageWidthDp: Float,
)

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
    /** 横向折痕的高度：桌面姿态（半折横放）时内容在上半屏、操作在下半屏，折痕上不放字。 */
    val tabletopAtDp: Float? = null,
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
    /** 桌面姿态的窗口下限：再窄就不值得上下分区，直接按普通窗口排。 */
    const val TabletopMinWidthDp = 500f
    const val TabletopMinHeightDp = 600f
    /** 上半屏至少放得下一段讲解，下半屏至少放得下一行答题台。 */
    const val TabletopMinContentDp = 240f
    const val TabletopMinControlsDp = 180f
    /** 一页至少这么宽，一行才排得下十来个字；再窄就退回单页，别把字挤成一条。 */
    const val ReadingPageMinDp = 300f
    /** 书本半折时每一页（导航页、内容页）至少要有的宽度。 */
    const val BookPaneMinDp = 240f
    /** 书脊处除铰链本身之外再留的空白。 */
    const val SpreadGutterDp = 16f

    /**
     * 阅读面按折痕摊开：竖折痕且左右两半都放得下一页时对开，书脊落在折痕上。
     * 从窗口左边量起，**调用方必须自己收起侧栏**（72dp 的导航栏会把 352 的左页压成 280，摊不成对开）。
     */
    fun readingSpread(spec: WindowSpec, minPageDp: Float = ReadingPageMinDp): ReadingSpread {
        val hinge = spec.hinge?.takeIf { it.axis == HingeAxis.Vertical }
            ?: return ReadingSpread(1, 0f, spec.widthDp)
        val left = hinge.atDp.coerceAtLeast(0f)
        val right = (spec.widthDp - hinge.atDp).coerceAtLeast(0f)
        return if (left >= minPageDp && right >= minPageDp)
            ReadingSpread(2, hinge.widthDp + SpreadGutterDp, minOf(left, right))
        else ReadingSpread(1, 0f, spec.widthDp)
    }

    /** App chrome contract, also used by Desk: portrait Fold is single-column; the directory is a drawer. */
    fun navigation(spec: WindowSpec): PaneLayout {
        val layout = compute(spec)
        // 只有折痕真能把窗口分成两栏（book）时才换掉手机布局：外屏和窄窗口上系统仍可能报铰链，那里还是单栏。
        val portrait = !layout.shortHeight && spec.widthDp < 840f && spec.heightDp > spec.widthDp && layout.dividerAtDp == null
        return if (portrait) layout.copy(nav = NavStyle.BottomBar, navWidthDp = 0f, aside = AsidePlacement.BottomSheet,
            asideWidthDp = 0f, outlineDocked = false, outlineWidthDp = 0f)
        else layout.copy(outlineDocked = false, outlineWidthDp = 0f)
    }

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
        // 横向折痕 = 桌面姿态（半折后横放在桌上）：上半屏内容、下半屏操作。竖折痕才是「书」。
        val tabletop = spec.hinge?.takeIf { it.axis == HingeAxis.Horizontal }?.takeIf { w >= TabletopMinWidthDp && h >= TabletopMinHeightDp }
        if (short) return PaneLayout(cls, true, NavStyle.IconRail, false, AsidePlacement.None, 0f, false, 0f, ConversationMaxMediumDp, IconRailDp, null)
        if (tabletop != null) return PaneLayout(cls, false, NavStyle.BottomBar, true, AsidePlacement.BottomSheet, 0f, false, 0f,
            ConversationMaxMediumDp, 0f, null, tabletopAtDp = tabletop.atDp.coerceIn(TabletopMinContentDp, h - TabletopMinControlsDp))
        if (cls == WidthClass.Compact) return PaneLayout(cls, false, NavStyle.BottomBar, true, AsidePlacement.BottomSheet, 0f, false, 0f, ConversationMaxMediumDp, 0f, null)
        val book = spec.hinge?.axis == HingeAxis.Vertical && w >= 700f && h >= 600f
        val asideW = if (cls == WidthClass.Large) AsideLargeDp else AsideDp
        val convMax = when (cls) { WidthClass.Medium -> ConversationMaxMediumDp; WidthClass.Expanded -> ConversationMaxExpandedDp; else -> ConversationMaxLargeDp }
        val twoPane = w >= 840f || book
        var free = w - RailDp
        var asidePlacement = AsidePlacement.Overlay
        var outline = false
        if (twoPane && free - asideW >= ConversationMinDp) { asidePlacement = AsidePlacement.Docked; free -= asideW }
        if (cls == WidthClass.Large && asidePlacement == AsidePlacement.Docked && free - OutlineDp >= ConversationMinDp) { outline = true; free -= OutlineDp }
        val divider = if (book) spec.hinge.atDp else null
        return PaneLayout(cls, false, NavStyle.Rail, true, asidePlacement, asideW, outline, if (outline) OutlineDp else 0f, convMax, RailDp, divider)
    }
}
