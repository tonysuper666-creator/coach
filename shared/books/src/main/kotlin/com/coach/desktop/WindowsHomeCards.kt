package com.coach.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.desktop.books.exam.*
import com.coach.desktop.books.Textbook
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.paperShadow
import java.nio.file.Path

/** 今天: the book on this machine, at the page and lesson 书架 left off. */
@Composable
internal fun HostTodayBook(book: Textbook, busy: Boolean, reviews: Int, onReview: () -> Unit, onRead: () -> Unit, onLearn: () -> Unit) {
    HostPanel("host-today-book", "今天接着读", "《${book.title}》${book.progressLine()}", busy, onRead,
        "host-today-read", "接着读 · 第 ${book.readingPage} 页", onLearn, "host-today-learn",
        if (book.learning) "继续学 · 第 ${book.lessonIndex + 1} / ${book.lessons.size} 节" else "开始学这本书",
        extra = {
            if (reviews > 0) PrimaryButton(onReview, enabled = !busy, modifier = Modifier.testTag("host-today-review")) {
                Text("复习 $reviews 题")
            }
        })
}

/** 课程: the same book split into lessons; no remote course or knowledge map is invented here. */
@Composable
internal fun HostCourseBook(book: Textbook, busy: Boolean, onLearn: () -> Unit, onRead: () -> Unit,
    exams: @Composable () -> Unit) {
    if (book.lessons.isEmpty()) HostPanel("host-course-book", "这门课还开不了",
        "《${book.title}》没有可提取的文字，按节学需要带文字层的教材。可以先在书架当一本书读。", busy, onRead,
        "host-course-read", "读这本书的原文", null, null, null, extra = exams)
    else HostPanel("host-course-book", book.title,
        "这门课就是书架上的这本教材，共 ${book.lessons.size} 节，${book.progressLine()}。", busy, onLearn,
        "host-course-learn", if (book.learning) "继续第 ${book.lessonIndex + 1} 节" else "开始这门课",
        onRead, "host-course-read", "接着读 · 第 ${book.readingPage} 页", extra = exams)
}

@Composable
private fun HostPanel(tag: String, title: String, body: String, busy: Boolean, onPrimary: () -> Unit,
    primaryTag: String, primaryLabel: String, onSecondary: (() -> Unit)?, secondaryTag: String?, secondaryLabel: String?,
    extra: @Composable () -> Unit = {}) {
    HostSheet {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(36.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(body, color = CoachTheme.colors.fgSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(onPrimary, enabled = !busy, modifier = Modifier.testTag(primaryTag)) { Text(primaryLabel) }
                if (onSecondary != null && secondaryTag != null && secondaryLabel != null) PaperButton(onSecondary,
                    enabled = !busy, modifier = Modifier.testTag(secondaryTag)) { Text(secondaryLabel) }
            }
            extra()
        }
    }
}

/**
 * V7 puts every page on a sheet with the desk showing round it: the same margins, corner and shadow as the
 * paper `Desk` lays out for the reader, so 今天 / 课程 and the reader read as the same desk.
 */
@Composable
private fun HostSheet(content: @Composable () -> Unit) {
    val colors = CoachTheme.colors
    Box(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 900.dp).fillMaxWidth().fillMaxHeight().paperShadow("sheet", colors.dark)
            .clip(RoundedCornerShape(CoachV7.Radius.sheet)).background(colors.surface1).testTag("host-sheet")) { content() }
    }
}

private fun Textbook.progressLine(): String =
    "读到第 $readingPage 页" + if (learning) "，学习停在第 ${lessonIndex + 1} / ${lessons.size} 节" else "，还没开始按节学"

@Composable
internal fun HostEmptyState(tag: String, title: String, text: String) {
    HostSheet {
        Column(Modifier.fillMaxSize().padding(36.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(text, color = CoachTheme.colors.fgSecondary)
        }
    }
}

/**
 * V7：设置弹窗和其他弹窗一样是 surface2 的纸。教材位置单列一格「字段名 + 叠在上面的一层 surface1 纸」，
 * 路径用数字字体、可以整段换行；说明是 fgSecondary 小字，「知道了」走纸面按钮。
 */
@Composable
internal fun HostSettings(directory: Path, onPersonalCourse: (() -> Unit)? = null, onClose: () -> Unit) {
    val colors = CoachTheme.colors
    val paper = RoundedCornerShape(CoachV7.Radius.btn)
    AlertDialog(onDismissRequest = onClose, title = { Text("设置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("教材和进度保存在此设备，不经过 Mac。", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("教材位置", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                Text(directory.toString(), style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary,
                    fontFamily = LocalV7Fonts.current.numbers,
                    modifier = Modifier.fillMaxWidth().clip(paper).background(colors.surface1).border(1.dp, colors.borderSubtle, paper)
                        .padding(horizontal = 12.dp, vertical = 10.dp).testTag("settings-library-directory"))
            }
            if (onPersonalCourse != null) TextButton(onPersonalCourse) { Text("打开已配对课程") }
            Text("课程里可做本地教材参考练习；Mac 上的课程数据仍独立。",
                style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
        }
    }, confirmButton = {
        PaperButton(onClose, modifier = Modifier.testTag("close-settings")) { Text("知道了") }
    })
}
