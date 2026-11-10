package com.coach.desktop.reader

/** 阅读位置：只存稳定锚点 id 与页序，不存像素，窗口缩放/换版后由布局层重新定位。 */
data class ReaderPosition(val page: Int, val anchorId: String? = null)

data class ReaderState(
    val layout: ReaderLayout,
    val focus: ReaderPane = ReaderPane.Body,
    val position: ReaderPosition = ReaderPosition(0),
    val pageCount: Int = 0,
    val zoomPercent: Int = 100,
    /** 当前选中的原句锚点（选段批注/题目指向的原句）。 */
    val selectedAnchor: String? = null,
    /** 出处跳转前的位置栈；返回时弹出，题目窗口的答案不在这里，因而不会被清空。 */
    val provenanceStack: List<ReaderPosition> = emptyList(),
    /** 跳到出处后需要闪示的原句。 */
    val flashAnchor: String? = null,
) {
    fun apply(action: ReaderAction): ReaderState = when (action) {
        ReaderAction.NextPage -> goTo(position.page + 1)
        ReaderAction.PrevPage -> goTo(position.page - 1)
        ReaderAction.FirstPage -> goTo(0)
        ReaderAction.LastPage -> goTo(pageCount - 1)
        ReaderAction.ScrollDown, ReaderAction.ScrollUp -> this
        ReaderAction.NextPane -> movePane(+1)
        ReaderAction.PrevPane -> movePane(-1)
        ReaderAction.OpenProvenance -> selectedAnchor?.let { openProvenance(it, position.page) } ?: this
        ReaderAction.ReturnFromProvenance -> returnFromProvenance()
        ReaderAction.ZoomIn -> copy(zoomPercent = (zoomPercent + 10).coerceAtMost(300))
        ReaderAction.ZoomOut -> copy(zoomPercent = (zoomPercent - 10).coerceAtLeast(50))
    }

    fun goTo(page: Int): ReaderState {
        if (pageCount <= 0) return this
        val p = page.coerceIn(0, pageCount - 1)
        return if (p == position.page) this else copy(position = ReaderPosition(p), flashAnchor = null)
    }

    private fun movePane(delta: Int): ReaderState {
        val v = layout.visible
        if (v.isEmpty()) return this
        val i = v.indexOf(focus).let { if (it < 0) 0 else it }
        return copy(focus = v[(i + delta).coerceIn(0, v.lastIndex)])
    }

    /** 题目点出处：记下当前位置，跳到原句所在页并闪示；页码未知时诚实保持原位。 */
    fun openProvenance(anchorId: String, page: Int): ReaderState {
        if (page < 0 || (pageCount > 0 && page >= pageCount)) return this
        return copy(
            provenanceStack = provenanceStack + position,
            position = ReaderPosition(page, anchorId),
            selectedAnchor = anchorId,
            flashAnchor = anchorId,
            focus = ReaderPane.Body,
        )
    }

    fun returnFromProvenance(): ReaderState {
        val back = provenanceStack.lastOrNull() ?: return this
        return copy(
            position = back,
            provenanceStack = provenanceStack.dropLast(1),
            flashAnchor = null,
            focus = ReaderPane.Related.takeIf { it in layout.visible } ?: focus,
        )
    }

    /** 窗口宽度变化：重新分栏，位置与出处栈原样保留，焦点落在仍可见的栏。 */
    fun resize(widthDp: Int): ReaderState {
        val l = DesktopReaderLayouts.forWidth(widthDp)
        return copy(layout = l, focus = DesktopReaderLayouts.focusAfterResize(focus, l))
    }

    /** 注意：快照不含 selectedAnchor，恢复后需由窗口层重新选中原句才能按 Enter 开出处。 */
    fun encode(): String = buildString {
        appendLine("v=1")
        appendLine("page=${position.page}")
        position.anchorId?.let { appendLine("anchor=${esc(it)}") }
        appendLine("zoom=$zoomPercent")
        appendLine("focus=${focus.name}")
        provenanceStack.forEach { appendLine("back=${it.page}|${esc(it.anchorId.orEmpty())}") }
    }

    companion object {
        private fun esc(s: String) = s.replace("%", "%25").replace("\n", "%0A").replace("|", "%7C")
        private fun unesc(s: String) = s.replace("%7C", "|").replace("%0A", "\n").replace("%25", "%")

        /** 恢复：损坏或版本不符返回 null，由调用方回到第一页，不猜。 */
        fun restore(text: String?, widthDp: Int, pageCount: Int): ReaderState? {
            if (text.isNullOrBlank()) return null
            val kv = text.lines().filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }
            if (kv.firstOrNull() != ("v" to "1")) return null
            return try {
                val page = kv.first { it.first == "page" }.second.toInt()
                if (pageCount > 0 && page !in 0 until pageCount) return null
                val anchor = kv.firstOrNull { it.first == "anchor" }?.second?.let(::unesc)
                val zoom = kv.firstOrNull { it.first == "zoom" }?.second?.toInt()?.coerceIn(50, 300) ?: 100
                val stack = kv.filter { it.first == "back" }.map {
                    if ('|' !in it.second) return null
                    val bp = it.second.substringBefore('|').toInt()
                    if (pageCount > 0 && bp !in 0 until pageCount) return null
                    if (bp < 0) return null
                    ReaderPosition(bp, unesc(it.second.substringAfter('|')).ifEmpty { null })
                }
                val layout = DesktopReaderLayouts.forWidth(widthDp)
                val focus = kv.firstOrNull { it.first == "focus" }?.second
                    ?.let { n -> ReaderPane.entries.firstOrNull { it.name == n } } ?: ReaderPane.Body
                ReaderState(layout, DesktopReaderLayouts.focusAfterResize(focus, layout), ReaderPosition(page, anchor), pageCount, zoom, provenanceStack = stack)
            } catch (_: Exception) {
                null
            }
        }
    }
}
