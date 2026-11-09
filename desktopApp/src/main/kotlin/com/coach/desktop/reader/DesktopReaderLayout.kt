package com.coach.desktop.reader

/** 桌面阅读窗口的分栏方案：按窗口宽度选，不按机型。纯逻辑，不依赖 Compose。 */
enum class ReaderPane { Shelf, Body, Related }

data class ReaderLayout(
    /** 同时可见的栏，从左到右。 */
    val visible: List<ReaderPane>,
    /** 窄窗口下被收拢、用抽屉/切换打开的栏。 */
    val collapsed: List<ReaderPane>,
) {
    val isSplit get() = visible.size > 1
}

object DesktopReaderLayouts {
    const val SHELF_MIN_DP = 1100 // 书架 + 正文 + 关联题
    const val BODY_RELATED_MIN_DP = 760 // 正文 + 关联题
    const val MIN_WINDOW_DP = 480

    fun forWidth(widthDp: Int): ReaderLayout = when {
        widthDp >= SHELF_MIN_DP -> ReaderLayout(listOf(ReaderPane.Shelf, ReaderPane.Body, ReaderPane.Related), emptyList())
        widthDp >= BODY_RELATED_MIN_DP -> ReaderLayout(listOf(ReaderPane.Body, ReaderPane.Related), listOf(ReaderPane.Shelf))
        else -> ReaderLayout(listOf(ReaderPane.Body), listOf(ReaderPane.Shelf, ReaderPane.Related))
    }

    /** 缩放窗口时保持焦点栏：焦点栏被收拢时回到正文，焦点不丢。 */
    fun focusAfterResize(focus: ReaderPane, layout: ReaderLayout): ReaderPane =
        if (focus in layout.visible) focus else ReaderPane.Body

    /** 桌面尺寸夹具：用于布局测试与截图，不含任何书库数据。 */
    val fixtures: Map<String, Int> = linkedMapOf("narrow" to 520, "medium" to 900, "wide" to 1440)
}
