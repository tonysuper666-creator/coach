package com.coach.desktop.books

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.domain.LadderSession
import com.coach.domain.QuestionLadder
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.PrimaryButton

/**
 * 「做题学」累计到六题时卡在这里：把这一轮做过的题各自的原文原句连起来，读一遍再往下走。
 *
 * 小结的内容和阈值都来自主线已有的 [QuestionLadder]：`record` 记一笔，六题后 `recapOpen`，
 * `recap` 取最近五句，`closeRecap` 推进游标。教材入口只补了「哪来的六题」和这张卡，
 * 没有第二套小结算法，也不把这一节的作答摘要当成知识小结。
 *
 * 和这一节的 [LessonSummaryCard] 同屏时，这张卡排在它下面：这一节只是刚才那一题，六题连起来的
 * 才是这一轮的知识，标题也写大一号；「接着做题」是这一屏唯一的一组操作，上面的小结卡不再铺按钮。
 */
@Composable
internal fun KnowledgeRecapCard(session: LadderSession, onContinue: () -> Unit) {
    val lines = QuestionLadder.recap(session)
    if (lines.isEmpty()) return
    val answered = session.responses.size - session.recapThrough
    val muted = CoachTheme.colors.fgSecondary
    V7ExpandIn {
        PaperCard(Modifier.v7Resize().testTag("book-knowledge-recap"), contentSpacing = 8.dp,
            contentPadding = PaddingValues(16.dp)) {
            Text("这六题学到的", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("book-knowledge-recap-title"))
            Text("你在「做题学」里做了 $answered 题，连起来是这 ${lines.size} 句：",
                style = MaterialTheme.typography.bodySmall, color = muted)
            lines.forEachIndexed { index, line ->
                Text("${index + 1}. $line", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("book-knowledge-recap-line-$index"))
            }
            Text("答过的题、选过的选项和走到哪一节都留着；回到这一节还是原样。",
                style = MaterialTheme.typography.bodySmall, color = muted)
            PrimaryButton(onContinue, modifier = Modifier.testTag("book-knowledge-recap-continue")) { Text("接着做题") }
        }
    }
}
