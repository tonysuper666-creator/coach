package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import com.coach.ui.v7.paperShadow
import com.coach.ui.v7.v7FocusRing

@Composable
internal fun LibraryHome(ui: LibraryUi, onImport: () -> Unit, onSelect: (String) -> Unit, onRead: () -> Unit, onLearn: () -> Unit,
    onPath: (LearningPath) -> Unit) {
    val book = ui.book
    val colors = CoachTheme.colors
    // V7 keeps every page on one sheet of paper with the desk showing around it, the same sheet the
    // Today and Course pages sit on; layers are told apart by colour and space, never by outlines.
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 1000.dp).fillMaxWidth().fillMaxHeight().paperShadow("sheet", colors.dark)
            .clip(RoundedCornerShape(CoachV7.Radius.sheet)).background(colors.surface1).testTag("shelf-sheet")) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(36.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Text("书架", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.testTag("library-title"))
                Text("带来一本教材，从想读的地方开始。", color = colors.fgSecondary)
                PrimaryButton(onImport, enabled = !ui.busy, modifier = Modifier.testTag("upload-textbook")) { Text("上传教材") }
                Text("PDF、EPUB、Markdown 或文本 · 教材和进度保存在这台电脑", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                if (book == null) {
                    Text("书架还是空的", style = MaterialTheme.typography.titleLarge)
                    Text("上传后，可以直接开始学，也可以先当一本书读。", color = colors.fgSecondary)
                } else {
                    Text(book.title, style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        EntryCard("看书", "翻开第 ${book.readingPage} 页，可就原文段落提问。", "home-read", !ui.busy, Modifier.weight(1f), onRead)
                        EntryCard("边看边学", "先看讲解，再选择是否做题。", "home-guided", !ui.busy, Modifier.weight(1f)) { onPath(LearningPath.READ_AND_LEARN) }
                        EntryCard("做题学", "先试题，答后看讲解；原文按需打开。", "home-question", !ui.busy, Modifier.weight(1f)) { onPath(LearningPath.QUESTION_FIRST) }
                    }
                    PrimaryButton(onLearn, enabled = !ui.busy, modifier = Modifier.testTag("home-learn")) {
                        // An old book without a chosen walk starts learning here; saying "continue reading" while the
                        // button opens the start-learning prompt is what this label used to get wrong.
                        Text(if (!ui.learningPathSaved && !book.learning) "开始学"
                        else "继续${ui.learningPath.label}" + if (ui.learningPath == LearningPath.READ) " · 第 ${book.readingPage} 页" else " · 第 ${book.lessonIndex + 1} 节")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("全部教材", style = MaterialTheme.typography.titleMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (item in ui.catalog) {
                            PaperTile(!ui.busy, Modifier.fillMaxWidth().testTag("book-${item.id}"), { onSelect(item.id) },
                                PaddingValues(horizontal = 18.dp, vertical = 12.dp), 4.dp) {
                                Text(item.title)
                                Text("${item.pageCount} 页 · 读到第 ${item.readingPage} 页${if (item.learning) " · 在学" else ""}",
                                    style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A borderless card-coloured block on the sheet that reads as one button: V7's card, focus ring and press. */
@Composable
internal fun PaperTile(enabled: Boolean, modifier: Modifier, onClick: () -> Unit, padding: PaddingValues, spacing: Dp,
    content: @Composable ColumnScope.() -> Unit) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.v7FocusRing(shape).paperShadow("card", colors.dark).clip(shape).background(colors.surface2)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).alpha(if (enabled) 1f else .5f).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

@Composable
private fun EntryCard(title: String, description: String, tag: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    PaperTile(enabled, modifier.heightIn(min = 148.dp).testTag(tag), onClick, PaddingValues(20.dp), 12.dp) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = CoachTheme.colors.fgSecondary)
    }
}

@Composable
private fun QuestionModePicker(selected: QuestionMode, enabled: Boolean, onMode: (QuestionMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("出题", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in QuestionMode.entries) FilterChip(selected = selected == mode, onClick = { onMode(mode) },
                enabled = enabled, label = { Text(mode.label) }, modifier = Modifier.testTag("question-mode-${mode.name}"))
        }
        Text(when (selected) {
            QuestionMode.NONE -> "只学教材内容，随时可以再开启练习。"
            QuestionMode.CHOICE -> "用原文填空选择题回忆教材内容。"
            QuestionMode.SHORT_ANSWER -> "用自己的话作答，再对照原文自检。"
        }, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun BookEntryPrompt(ui: LibraryUi, onDismiss: () -> Unit, onRead: () -> Unit, onEnable: () -> Unit, onMode: (QuestionMode) -> Unit,
    onPath: (LearningPath) -> Unit) {
    val book = checkNotNull(ui.book)
    val settings = ui.prompt == BookPrompt.QUESTIONS
    AlertDialog(onDismissRequest = { if (!ui.busy) onDismiss() },
        title = { Text(when (ui.prompt) {
            BookPrompt.IMPORTED -> "教材已加入书架"
            BookPrompt.AFTER_READING -> "要开始学吗？"
            BookPrompt.QUESTIONS -> "出题方式"
            else -> "开始学这本教材"
        }) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(book.title)
                Text(when (ui.prompt) {
                    BookPrompt.IMPORTED -> "可以先在阅读器里读，也可以直接按章节开始学。"
                    BookPrompt.AFTER_READING -> "阅读器已经打开。开启后会按教材章节继续学；暂不开启，就只读这本书。"
                    BookPrompt.QUESTIONS -> "出题类型只影响学习练习，切换后保留学习方式和原作答。"
                    else -> "从教材原文生成学习路线，按顺序理解、回忆和继续。"
                })
                if (!settings) {
                    Text("这次怎么读", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LearningPath.entries.forEach { path ->
                            FilterChip(selected = ui.learningPath == path, onClick = { onPath(path) }, enabled = !ui.busy,
                                label = { Text(path.label) }, modifier = Modifier.testTag("learning-path-${path.name}"))
                        }
                    }
                }
                if (ui.learningPath != LearningPath.READ) QuestionModePicker(book.questionMode, !ui.busy, onMode)
                if (book.pages.any { it.text.isBlank() }) Text("部分页面没有可提取文字，仍可在阅读器查看原页。学的时候只用能提取出文字的部分。", style = MaterialTheme.typography.bodySmall)
                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            V7TextButton(onClick = if (settings) onDismiss else onEnable, enabled = !ui.busy,
                modifier = Modifier.testTag(if (settings) "close-question-settings" else "enable-learning")) {
                Text(if (settings) "确定" else "开始${ui.learningPath.label}")
            }
        }, dismissButton = {
            if (!settings) V7TextButton(onClick = if (ui.prompt == BookPrompt.IMPORTED) onRead else onDismiss, enabled = !ui.busy,
                modifier = Modifier.testTag("read-only")) { Text(if (ui.prompt == BookPrompt.IMPORTED) "先读" else "暂不开启") }
        })
}
