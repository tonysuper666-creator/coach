package com.coach.desktop.books

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.V7TextButton
import com.coach.ui.v7.paperShadow
import com.coach.ui.v7.v7FocusRing

@Composable
internal fun BookImportProgressBar(
    progress: BookImportProgress,
    onCancel: (() -> Unit)? = null,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val pine = v7Pine()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                formatImportProgressLabel(progress),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
                fontFamily = LocalV7Fonts.current.numbers,
            )
            if (onCancel != null) {
                V7TextButton(onCancel, modifier = Modifier.testTag("book-import-cancel"), ringInside = true) {
                    Text(if (compact) "取消" else "取消导入")
                }
            }
        }
        val barModifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).testTag("book-import-progress-bar")
        if (!progress.recognizing && progress.total <= 0) {
            LinearProgressIndicator(modifier = barModifier, color = pine, trackColor = colors.borderSubtle)
        } else {
            val fraction = if (progress.total <= 0) 0f else (progress.page.toFloat() / progress.total).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = barModifier,
                color = pine,
                trackColor = colors.borderSubtle,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
internal fun BookImportToast(notice: BookImportNotice?, onDismiss: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (notice == null) return
    LaunchedEffect(notice.id) {
        kotlinx.coroutines.delay(4_000)
        onDismiss(notice.id)
    }
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.btn)
    Box(
        modifier
            .padding(top = 56.dp, start = 16.dp, end = 16.dp)
            .paperShadow("card", colors.dark)
            .clip(shape)
            .background(colors.fgPrimary)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("book-import-toast"),
    ) {
        Text(notice.message, style = MaterialTheme.typography.bodySmall, color = colors.surface1)
    }
}

@Composable
internal fun LibraryHome(ui: LibraryUi, onImport: () -> Unit, onSelect: (String) -> Unit, onRead: () -> Unit, onLearn: () -> Unit,
    onPath: (LearningPath) -> Unit, onCancelImport: () -> Unit = {}) {
    val book = ui.book
    val colors = CoachTheme.colors
    val scroll = rememberScrollState()
    val line = if (colors.dark) CoachV7.Dark.line else CoachV7.Light.line
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = CoachV7.Space.s6, vertical = CoachV7.Space.s3), contentAlignment = Alignment.TopCenter) {
        val sectionSpacing = if (maxHeight < 560.dp) CoachV7.Space.s2 else CoachV7.Space.s5
        Box(Modifier.widthIn(max = 1000.dp).fillMaxWidth().fillMaxHeight().paperShadow("sheet", colors.dark)
            .clip(RoundedCornerShape(CoachV7.Radius.sheet)).background(colors.surface1).testTag("shelf-sheet")) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll)
                .padding(start = CoachV7.Space.s7, top = CoachV7.Space.s7, end = CoachV7.Space.s8, bottom = CoachV7.Space.s8),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing)) {
                Text("书架", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("library-title"))
                Text("带来一本教材，从想读的地方开始。", color = colors.fgSecondary)
                if (book == null) {
                    PrimaryButton(onImport, enabled = !ui.busy, modifier = Modifier.testTag("upload-textbook")) { Text("上传教材") }
                } else {
                    V7TextButton(onImport, enabled = !ui.busy, modifier = Modifier.testTag("upload-textbook")) { Text("上传教材") }
                }
                Text("${BookFormats.description} · 教材和进度保存在此设备", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                if (book == null) {
                    Text("书架还是空的", style = MaterialTheme.typography.titleLarge)
                    Text("上传后，可以直接开始学，也可以先当一本书读。", color = colors.fgSecondary)
                } else {
                    Text(book.title, style = MaterialTheme.typography.headlineLarge, fontFamily = LocalV7Fonts.current.display)
                    PrimaryButton(onLearn, enabled = !ui.busy, modifier = Modifier.testTag("home-learn")) {
                        Text(if (!ui.learningPathSaved && !book.learning) "开始学"
                        else "继续${ui.learningPath.label}" + if (ui.learningPath == LearningPath.READ) " · 第 ${book.readingPage} 页" else " · 第 ${book.lessonIndex + 1} 节")
                    }
                    Column {
                        ShelfDivider(line)
                        EntryRow("看书", "翻开第 ${book.readingPage} 页，可就原文段落提问。", "home-read", !ui.busy, onRead)
                        ShelfDivider(line)
                        EntryRow("边看边学", "先看讲解，再选择是否做题。", "home-guided", !ui.busy) { onPath(LearningPath.READ_AND_LEARN) }
                        ShelfDivider(line)
                        EntryRow("做题学", "先试题，答后看讲解；原文按需打开。", "home-question", !ui.busy) { onPath(LearningPath.QUESTION_FIRST) }
                        ShelfDivider(line)
                    }
                    Text("全部教材", style = MaterialTheme.typography.titleMedium, fontFamily = LocalV7Fonts.current.display)
                    Column {
                        ShelfDivider(line)
                        for (item in ui.catalog) {
                            ShelfBookRow(
                                item = item,
                                current = item.id == book.id,
                                enabled = !ui.busy,
                                activeImport = ui.importing?.takeIf { it.recognizing && it.bookId == item.id },
                                onCancelImport = onCancelImport,
                            ) { onSelect(item.id) }
                            ShelfDivider(line)
                        }
                    }
                }
            }
            BookScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = CoachV7.Space.s4))
        }
    }
}

@Composable
private fun ShelfDivider(color: androidx.compose.ui.graphics.Color) {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(color))
}

/** Matches the shared V7 button hover, press, pointer and keyboard focus behavior on the sheet. */
@Composable
private fun Modifier.shelfClick(enabled: Boolean, onClick: () -> Unit): Modifier {
    val colors = CoachTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val wash = if (pressed) {
        if (colors.dark) CoachV7.Dark.selBg else CoachV7.Light.selBg
    } else {
        if (colors.dark) CoachV7.Dark.zhuWash else CoachV7.Light.zhuWash
    }
    val shape = RoundedCornerShape(CoachV7.Radius.s)
    return v7FocusRing(shape).clip(shape)
        .background(if (enabled && (hovered || pressed)) wash.compositeOver(colors.surface1) else androidx.compose.ui.graphics.Color.Transparent)
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .alpha(if (enabled) 1f else .5f)
}

@Composable
private fun EntryRow(title: String, description: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).shelfClick(enabled, onClick)
        .padding(horizontal = CoachV7.Space.s3, vertical = CoachV7.Space.s3),
        horizontalArrangement = Arrangement.spacedBy(CoachV7.Space.s4), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.width(112.dp), style = MaterialTheme.typography.titleMedium, fontFamily = LocalV7Fonts.current.display)
        Text(description, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = CoachTheme.colors.fgSecondary)
    }
}

@Composable
private fun ShelfBookRow(
    item: BookSummary,
    current: Boolean,
    enabled: Boolean,
    activeImport: BookImportProgress? = null,
    onCancelImport: () -> Unit = {},
    onClick: () -> Unit,
) {
    val colors = CoachTheme.colors
    val wash = if (colors.dark) CoachV7.Dark.pineWash else CoachV7.Light.pineWash
    Column(Modifier.fillMaxWidth().background(if (current) wash else androidx.compose.ui.graphics.Color.Transparent)) {
        Row(Modifier.fillMaxWidth().testTag("book-${item.id}")
            .shelfClick(enabled, onClick).padding(horizontal = CoachV7.Space.s3, vertical = CoachV7.Space.s3),
            horizontalArrangement = Arrangement.spacedBy(CoachV7.Space.s3), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(CoachV7.Space.s1).height(CoachV7.Space.s7)
                .background(if (current) v7Pine() else androidx.compose.ui.graphics.Color.Transparent))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CoachV7.Space.s1)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontFamily = LocalV7Fonts.current.display)
                val statusSuffix = when {
                    activeImport != null -> " · 识别中 ${activeImport.page} / ${activeImport.total} 页"
                    item.pendingOcrPages > 0 -> " · 待识别 ${item.pendingOcrPages} 页"
                    item.learning -> " · 在学"
                    else -> ""
                }
                Text("${item.pageCount} 页 · 读到第 ${item.readingPage} 页$statusSuffix",
                    style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary, fontFamily = LocalV7Fonts.current.numbers)
            }
        }
        if (activeImport != null) {
            BookImportProgressBar(
                progress = activeImport,
                onCancel = onCancelImport,
                compact = true,
                modifier = Modifier.fillMaxWidth()
                    .padding(start = CoachV7.Space.s3 + CoachV7.Space.s1 + CoachV7.Space.s3, end = CoachV7.Space.s3, bottom = CoachV7.Space.s3)
                    .testTag("book-import-progress"),
            )
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

/**
 * 一组单选里的一格（这次怎么读、出题）。没选是 surface1 的纸配细描边；选中是松绿描边、松绿淡底、松绿字，
 * 左边一个画出来的对勾，和笔记导出、答对的选项同一套颜色。对勾的位置每格都留着，选中时文字不跳。
 */
@Composable
private fun V7Choice(label: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.btn)
    val pine = v7Pine()
    val wash = if (colors.dark) CoachV7.Dark.pineWash else CoachV7.Light.pineWash
    Row(Modifier.testTag(tag).v7FocusRing(shape).clip(shape).background(if (selected) wash else colors.surface1)
        .border(1.dp, if (selected) pine else colors.borderSubtle, shape)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .alpha(if (enabled) 1f else .5f).heightIn(min = 36.dp).padding(start = 10.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(14.dp)) {
            if (!selected) return@Canvas
            val w = size.width
            drawPath(Path().apply { moveTo(w * 0.12f, w * 0.52f); lineTo(w * 0.40f, w * 0.78f); lineTo(w * 0.88f, w * 0.24f) },
                pine, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) pine else colors.fgPrimary)
    }
}

@Composable
private fun QuestionModePicker(selected: QuestionMode, enabled: Boolean, onMode: (QuestionMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("出题", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in QuestionMode.entries) V7Choice(mode.label, selected == mode, enabled, "question-mode-${mode.name}") { onMode(mode) }
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
                    BookPrompt.IMPORTED -> if (book.hasPendingOcr) "正在后台识别文字，可以先在阅读器里翻看原页，识别完的页也可以直接读和讲解。" else "可以先在阅读器里读，也可以直接按章节开始学。"
                    BookPrompt.AFTER_READING -> "阅读器已经打开。开启后会按教材章节继续学；暂不开启，就只读这本书。"
                    BookPrompt.QUESTIONS -> "出题类型只影响学习练习，切换后保留学习方式和原作答。"
                    else -> "从教材原文生成学习路线，按顺序理解、回忆和继续。"
                })
                if (!settings) {
                    Text("这次怎么读", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LearningPath.entries.forEach { path ->
                            V7Choice(path.label, ui.learningPath == path, !ui.busy, "learning-path-${path.name}") { onPath(path) }
                        }
                    }
                }
                if (ui.learningPath != LearningPath.READ) QuestionModePicker(book.questionMode, !ui.busy, onMode)
                if (book.pages.any { it.text.isBlank() && !it.ocrPending }) Text("部分页面没有可提取文字，仍可在阅读器查看原页。学的时候只用能提取出文字的部分。", style = MaterialTheme.typography.bodySmall)
                ui.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = v7Alert()) }
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
