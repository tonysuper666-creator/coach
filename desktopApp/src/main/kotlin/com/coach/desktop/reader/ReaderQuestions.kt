package com.coach.desktop.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun RelatedPane(session: ReaderSession, onPractice: ((StudyQuestion) -> Unit)?) {
    if (session.remote != null) { RemoteStudy(session, session.remote, onPractice); return }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("题目", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        session.fixture.questions.forEachIndexed { i, q ->
            val selected = session.selectedQuestion == i
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).then(if (selected) Modifier.background(Color(0xFFE6EEF6)) else Modifier).testTag("question-$i")) {
                Text(q.stem, fontSize = 13.sp)
                val chosen = session.answers[i]
                Text(
                    if (chosen == null) "未作答" else "已选：${q.options[chosen]}",
                    Modifier.testTag("answer-$i"),
                    fontSize = 12.sp,
                    color = Color(0xFF555555),
                )
                if (selected) Text("这句在哪考过（回车）", Modifier.clickable { session.openProvenanceOfSelected() }, color = Accent, fontSize = 12.sp)
            }
        }
        session.message?.let { Text(it, Modifier.testTag("message").padding(top = 8.dp), color = Color(0xFFA33B2D), fontSize = 13.sp) }
    }
}

@Composable
private fun RemoteStudy(session: ReaderSession, remote: ReaderRemote, onPractice: ((StudyQuestion) -> Unit)?) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("题目", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        when (val s = remote.study) {
            StudyState.Idle -> {}
            StudyState.Loading -> Text("加载中", fontSize = 12.sp, color = Color(0xFF666666))
            is StudyState.Failed -> Text(s.failure.text, Modifier.testTag("study-failure"), fontSize = 12.sp, color = Color(0xFFA33B2D))
            is StudyState.Ready -> {
                if (!s.view.available) Text("这本书还没有登记题目", Modifier.testTag("study-unavailable"), fontSize = 12.sp)
                s.view.questions.forEachIndexed { i, q ->
                    val selected = session.selectedQuestion == i
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp).then(if (selected) Modifier.background(Color(0xFFE6EEF6)) else Modifier).testTag("study-$i")) {
                        Text(q.stem, fontSize = 13.sp)
                        if (q.week != null && q.id.isNotBlank() && onPractice != null) Text(
                            "做这题", Modifier.testTag("study-practice-$i").clickable {
                                session.selectRemoteQuestion(i)
                                onPractice(q)
                            }.padding(vertical = 4.dp), color = Accent, fontSize = 12.sp,
                        )
                        if (onPractice != null && (q.week == null || q.id.isBlank())) Text(
                            "这题的所属周或编号待确认，暂不能练习", fontSize = 12.sp, color = Color(0xFF666666),
                        )
                        if (selected) Text(
                            if (q.anchor.isBlank()) "这题没有登记原句" else "这句在哪考过（回车）",
                            Modifier.testTag("study-source-$i").clickable { session.openRemoteProvenance() }, color = Accent, fontSize = 12.sp,
                        )
                    }
                }
                if (s.view.unlocated > 0) Text("本课程另有${s.view.unlocated}个题目或考点条目位置待确认", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = Color(0xFF666666))
            }
        }
        session.message?.let { Text(it, Modifier.testTag("message").padding(top = 8.dp), color = Color(0xFFA33B2D), fontSize = 13.sp) }
    }
}
