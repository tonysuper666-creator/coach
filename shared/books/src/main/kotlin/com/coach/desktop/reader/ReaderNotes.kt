package com.coach.desktop.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun NotesSection(session: ReaderSession, remote: ReaderRemote, view: ReaderPageView, textColor: Color = Ink, keyboardHint: Boolean = true) {
    val mine = remote.notes.filter { it.pdfPage == view.pdfPage || it.stale || it.version != remote.current?.version }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(if (keyboardHint) "批注（按 N 为一行写批注）" else "批注", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (mine.isEmpty()) Text("这一页还没有批注", Modifier.testTag("notes-empty"), fontSize = 12.sp, color = Color(0xFF666666))
        for (n in mine) {
            val valid = remote.noteValid(n, view)
            Column(Modifier.testTag("note-${n.id}").padding(top = 6.dp)) {
                if (!valid) Text("原文或资料版本已变化，位置待重新确认（原 PDF 第${n.pdfPage}页）", Modifier.testTag("note-stale-${n.id}"), fontSize = 12.sp, color = Color(0xFFA33B2D))
                Text("我的原句：${n.quote}", fontSize = 12.sp)
                if (n.note.isNotEmpty()) Text("我的批注：${n.note}", fontSize = 12.sp)
                if (valid) Text("在页上标出", Modifier.testTag("note-show-${n.id}").clickable { session.highlightNoteId = n.id }, fontSize = 12.sp, color = Accent)
            }
        }
        val local = session.localDrafts()
        if (local.isNotEmpty() && session.noteDraft == null) {
            Column(Modifier.testTag("local-drafts").padding(top = 8.dp)) {
                Text("本页有未保存的草稿（只在本机）", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF8A6D1F))
                local.forEachIndexed { i, sd ->
                    Row {
                        Text("继续编辑：" + sd.quote.take(24), Modifier.testTag("local-draft-$i").clickable { session.resumeLocalDraft(sd) }.padding(top = 2.dp), fontSize = 12.sp, color = Accent)
                        Text("　丢弃", Modifier.testTag("local-draft-discard-$i").clickable { session.discardLocalDraft(sd) }.padding(top = 2.dp), fontSize = 12.sp, color = Color(0xFFA33B2D))
                    }
                }
            }
        }
        val d = session.noteDraft
        if (d == null) SideEffect { session.uiNoteStatus = null }
        if (d != null) {
            Column(Modifier.testTag("note-editor").padding(top = 8.dp).border(BorderStroke(1.dp, Accent)).padding(8.dp)) {
                Text("原句：${d.quote}", fontSize = 12.sp)
                val inputFocus = remember(d.id) { FocusRequester() }
                LaunchedEffect(d.id) { runCatching { inputFocus.requestFocus() } } // 选定一行后光标直接进输入框，可以马上键入
                // 光标起点在已有文字末尾（编辑已有批注时从末尾接着写）；外部改文字（采用服务端版本/找回草稿）时同步进输入框。
                var field by remember(d.id) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(session.noteText, androidx.compose.ui.text.TextRange(session.noteText.length))) }
                if (field.text != session.noteText) field = androidx.compose.ui.text.input.TextFieldValue(session.noteText, androidx.compose.ui.text.TextRange(session.noteText.length))
                androidx.compose.foundation.text.BasicTextField(
                    value = field, onValueChange = { field = it; session.editNote(it.text) },
                    modifier = Modifier.testTag("note-input").focusRequester(inputFocus).fillMaxWidth().padding(vertical = 6.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = textColor),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(textColor),
                )
                Text(if (session.draftsKept()) "Ctrl+回车保存；Esc 关闭，没保存的文字会留在本机" else "Ctrl+回车保存，Esc 放弃", fontSize = 11.sp, color = Color(0xFF666666))
                if (session.drafts != null && session.persistFailed) Text("本机草稿没能写入磁盘，文字只在这个编辑区里，关闭后会丢", Modifier.testTag("note-draft-io-failed"), fontSize = 11.sp, color = Color(0xFFA33B2D))
                if (session.draftsKept()) Text("放弃草稿", Modifier.testTag("note-discard").clickable { session.discardDraft() }.padding(top = 2.dp), fontSize = 11.sp, color = Accent)
                val status = session.noteStatus()
                val statusText = status?.let { NoteStatusRules.statusText(it, session.draftsKept()) }
                SideEffect { session.uiNoteStatus = statusText }
                if (status != null) Text(
                    statusText!!, Modifier.testTag("note-status"), fontSize = 12.sp,
                    color = when (status) {
                        NoteStatus.Saved -> Color(0xFF2F6B3A)
                        NoteStatus.Dirty, NoteStatus.Offline, NoteStatus.Saving, NoteStatus.NotSaved -> Color(0xFF8A6D1F)
                        else -> Color(0xFFA33B2D)
                    },
                )
                (remote.noteSave as? NoteSaveState.Failed)?.let { if (status == NoteStatus.Failed) Text(NoteStatusRules.failureText(it.failure, session.draftsKept()), Modifier.testTag("note-failure-reason"), fontSize = 12.sp, color = Color(0xFFA33B2D)) }
                val conflict = remote.noteSave as? NoteSaveState.Conflict
                if (status == NoteStatus.Conflict && conflict != null) ConflictPanel(session, conflict)
                if (session.noteStash != null) Text("找回我的草稿", Modifier.testTag("note-restore-stash").clickable { session.restoreStashedDraft() }.padding(top = 4.dp), fontSize = 12.sp, color = Accent)
            }
        }
    }
}

@Composable
private fun ConflictPanel(session: ReaderSession, c: NoteSaveState.Conflict) {
    val scope = rememberCoroutineScope()
    Column(Modifier.testTag("note-conflict-panel").padding(top = 6.dp)) {
        when {
            c.refreshFailed -> Text("连不上服务，看不到服务端版本，不能覆盖", Modifier.testTag("note-conflict-refresh-failed"), fontSize = 12.sp, color = Color(0xFF8A6D1F))
            c.versionChanged -> Text("资料版本变了，不能覆盖；可另存为新批注", Modifier.testTag("note-conflict-version"), fontSize = 12.sp, color = Color(0xFFA33B2D))
            c.server == null -> Text("服务端看不到这条批注（可能已删除），不能覆盖；可另存为新批注", Modifier.testTag("note-conflict-missing"), fontSize = 12.sp, color = Color(0xFFA33B2D))
            else -> Text("服务端版本（修订 ${c.server.revision}）：${c.server.note}", Modifier.testTag("note-conflict-server"), fontSize = 12.sp)
        }
        val trusted = !c.refreshFailed && !c.versionChanged && c.server != null
        if (trusted) {
            Text("采用服务端版本", Modifier.testTag("note-adopt-server").clickable { session.adoptServerVersion() }.padding(top = 4.dp), fontSize = 12.sp, color = Accent)
            Text("用我的版本覆盖", Modifier.testTag("note-overwrite-mine").clickable { scope.launch { session.overwriteWithMine() } }.padding(top = 4.dp), fontSize = 12.sp, color = Accent)
        }
        if (c.refreshFailed) Text("重新获取服务端版本", Modifier.testTag("note-conflict-refresh").clickable { scope.launch { session.retryConflictRefresh() } }.padding(top = 4.dp), fontSize = 12.sp, color = Accent)
        Text("另存为新批注", Modifier.testTag("note-save-as-new").clickable { session.saveAsNewNote() }.padding(top = 4.dp), fontSize = 12.sp, color = Accent)
    }
}
