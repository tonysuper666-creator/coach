package com.coach.desktop.reader

/** 批注编辑区显示的保存状态；只由“当前草稿内容”和“服务端实际保存过什么”决定，不看别的草稿的旧回执。 */
enum class NoteStatus(val text: String) {
    NotSaved("尚未保存"),
    Dirty("有未保存的修改"),
    Saving("正在保存…"),
    Saved("已保存"),
    Offline("连不上服务，内容原样保留，再按 Ctrl+回车重试"),
    Failed("没有保存"),
    Conflict("这条批注有冲突，草稿保留在本机，没有覆盖对方"),
}

object NoteStatusRules {
    /**
     * @param baseline 服务端已确认保存的文字（新建且从未保存成功为 null）
     * @param attempt 最近一次发出去的文字（没发过为 null）
     */
    fun of(draftId: String, text: String, baseline: String?, attempt: String?, saving: Boolean, save: NoteSaveState): NoteStatus {
        if (saving) return NoteStatus.Saving
        val mine = when (save) {
            is NoteSaveState.Saved -> save.id == draftId
            is NoteSaveState.Offline -> save.id == draftId
            is NoteSaveState.Conflict -> save.id == draftId
            is NoteSaveState.Failed -> attempt != null
            NoteSaveState.Idle -> false
        }
        if (mine && save is NoteSaveState.Conflict) return NoteStatus.Conflict
        if (mine && save is NoteSaveState.Offline && text == attempt) return NoteStatus.Offline
        if (mine && save is NoteSaveState.Failed && text == attempt) return NoteStatus.Failed
        if (baseline == null) return if (text.isEmpty()) NoteStatus.NotSaved else NoteStatus.Dirty
        return if (text == baseline) NoteStatus.Saved else NoteStatus.Dirty
    }
}
