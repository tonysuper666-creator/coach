package com.coach.desktop.reader

enum class ReaderKey { Left, Right, Up, Down, PageUp, PageDown, Home, End, Space, Tab, Escape, Enter, Back, Other }

data class KeyInput(val key: ReaderKey, val shift: Boolean = false, val ctrlOrCmd: Boolean = false, val alt: Boolean = false)

sealed interface ReaderAction {
    data object NextPage : ReaderAction
    data object PrevPage : ReaderAction
    data object FirstPage : ReaderAction
    data object LastPage : ReaderAction
    data object ScrollDown : ReaderAction
    data object ScrollUp : ReaderAction
    data object NextPane : ReaderAction
    data object PrevPane : ReaderAction
    data object OpenProvenance : ReaderAction
    data object ReturnFromProvenance : ReaderAction
    data object ZoomIn : ReaderAction
    data object ZoomOut : ReaderAction
}

/**
 * 键盘映射。Tab 在各栏间循环，到头后返回 null 交还系统焦点，因此焦点不会被困在阅读区。
 * Esc、Back、Alt+左 先返回出处，栈空时一律放行。
 *
 * 适配边界：Enter 只在正文栏有焦点且有选中原句时生效；题目栏的"这句在哪考过"键盘入口由题目列表层另接。
 * OpenProvenance 动作只压栈并闪示，anchorId→页码须由窗口层解析后调用 [ReaderState.openProvenance]。
 */
object ReaderKeys {
    fun map(input: KeyInput, state: ReaderState): ReaderAction? = when {
        input.ctrlOrCmd && input.key == ReaderKey.Up -> ReaderAction.ZoomIn
        input.ctrlOrCmd && input.key == ReaderKey.Down -> ReaderAction.ZoomOut
        input.ctrlOrCmd -> null
        input.alt && input.key == ReaderKey.Left -> if (state.provenanceStack.isNotEmpty()) ReaderAction.ReturnFromProvenance else null
        input.alt -> null
        input.key == ReaderKey.Back -> if (state.provenanceStack.isNotEmpty()) ReaderAction.ReturnFromProvenance else null
        input.key == ReaderKey.Escape -> if (state.provenanceStack.isNotEmpty()) ReaderAction.ReturnFromProvenance else null
        input.key == ReaderKey.Tab -> when {
            state.layout.visible.isEmpty() -> null
            input.shift && state.focus == state.layout.visible.first() -> null
            !input.shift && state.focus == state.layout.visible.last() -> null
            input.shift -> ReaderAction.PrevPane
            else -> ReaderAction.NextPane
        }
        state.focus != ReaderPane.Body -> null // 书架/题目栏的方向键归各自列表处理
        input.key == ReaderKey.Right || input.key == ReaderKey.PageDown -> ReaderAction.NextPage
        input.key == ReaderKey.Left || input.key == ReaderKey.PageUp -> ReaderAction.PrevPage
        input.key == ReaderKey.Space -> if (input.shift) ReaderAction.PrevPage else ReaderAction.NextPage
        input.key == ReaderKey.Down -> ReaderAction.ScrollDown
        input.key == ReaderKey.Up -> ReaderAction.ScrollUp
        input.key == ReaderKey.Home -> ReaderAction.FirstPage
        input.key == ReaderKey.End -> ReaderAction.LastPage
        input.key == ReaderKey.Enter && state.selectedAnchor != null -> ReaderAction.OpenProvenance
        else -> null
    }
}
