package com.coach.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.desktop.books.PaperColor
import com.coach.desktop.books.ReadingFont
import com.coach.desktop.books.ReadingSize
import com.coach.desktop.books.Textbook
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import com.coach.ui.v7.paperShadow
import java.nio.file.Path

/** 主卡宽度上限：内栏与阅读正文栏（CoachV7.Layout.colw = 594dp）同宽，左右各留 44dp 纸面留白。 */
internal val HostSheetMaxWidth = CoachV7.Layout.colw + 88.dp

/** 测试截图用真实形状路径覆盖设置弹窗显示的目录，不改变实际书库路径。 */
internal val LocalSettingsDirectoryOverride = staticCompositionLocalOf<Path?> { null }

private fun defaultUserHome(): Path? =
    System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it) }.getOrNull() }

/** 设置弹窗里将用户主目录缩写成「用户文件夹」（例如「用户文件夹\Coach\books」），目录外路径保持原样。 */
internal fun formatLibraryPathForDisplay(directory: Path, userHome: Path? = defaultUserHome()): String {
    val normalizedDir = directory.toAbsolutePath().normalize()
    val normalizedHome = userHome?.toAbsolutePath()?.normalize()
    if (normalizedHome != null && normalizedDir.startsWith(normalizedHome)) {
        val relative = normalizedHome.relativize(normalizedDir).toString()
        if (relative.isEmpty()) return "用户文件夹"
        return "用户文件夹${normalizedDir.fileSystem.separator}$relative"
    }
    return normalizedDir.toString()
}

/** 今天: the book on this machine, at the page and lesson 书架 left off. */
@Composable
internal fun HostTodayBook(book: Textbook, busy: Boolean, reviews: Int, onReview: () -> Unit, onRead: () -> Unit, onLearn: () -> Unit) {
    HostPanel(
        tag = "host-today-book",
        kicker = "今天接着读",
        kickerTag = "host-today-kicker",
        title = book.title,
        titleTag = "host-today-title",
        body = book.progressLine(),
        bodyTag = "host-today-progress",
        busy = busy,
        onPrimary = onRead,
        primaryTag = "host-today-read",
        primaryLabel = "接着读 · 第 ${book.readingPage} 页",
        onSecondary = onLearn,
        secondaryTag = "host-today-learn",
        secondaryLabel = if (book.learning) "继续学 · 第 ${book.lessonIndex + 1} / ${book.lessons.size} 节" else "开始学这本书",
        secondaryExtra = {
            if (reviews > 0) PaperButton(onReview, enabled = !busy, modifier = Modifier.testTag("host-today-review")) {
                Text("复习 $reviews 题")
            }
        },
    )
}

/** 课程: the same book split into lessons; no remote course or knowledge map is invented here. */
@Composable
internal fun HostCourseBook(book: Textbook, busy: Boolean, onLearn: () -> Unit, onRead: () -> Unit,
    exams: @Composable () -> Unit) {
    if (book.lessons.isEmpty()) HostPanel(
        tag = "host-course-book",
        kicker = "课程",
        kickerTag = "host-course-kicker",
        title = "这门课还开不了",
        titleTag = "host-course-title",
        body = "《${book.title}》没有可提取的文字，按节学需要带文字层的教材。可以先在书架当一本书读。",
        bodyTag = "host-course-progress",
        busy = busy,
        onPrimary = onRead,
        primaryTag = "host-course-read",
        primaryLabel = "读这本书的原文",
        onSecondary = null,
        secondaryTag = null,
        secondaryLabel = null,
        extra = exams,
    ) else HostPanel(
        tag = "host-course-book",
        kicker = "课程",
        kickerTag = "host-course-kicker",
        title = book.title,
        titleTag = "host-course-title",
        body = "这门课就是书架上的这本教材，共 ${book.lessons.size} 节，${book.progressLine()}。",
        bodyTag = "host-course-progress",
        busy = busy,
        onPrimary = onLearn,
        primaryTag = "host-course-learn",
        primaryLabel = if (book.learning) "继续第 ${book.lessonIndex + 1} 节" else "开始这门课",
        onSecondary = onRead,
        secondaryTag = "host-course-read",
        secondaryLabel = "接着读 · 第 ${book.readingPage} 页",
        extra = exams,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostPanel(
    tag: String,
    kicker: String,
    kickerTag: String,
    title: String,
    titleTag: String,
    body: String,
    bodyTag: String,
    busy: Boolean,
    onPrimary: () -> Unit,
    primaryTag: String,
    primaryLabel: String,
    onSecondary: (() -> Unit)?,
    secondaryTag: String?,
    secondaryLabel: String?,
    secondaryExtra: @Composable FlowRowScope.() -> Unit = {},
    extra: (@Composable () -> Unit)? = null,
) {
    val colors = CoachTheme.colors
    HostSheet {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 44.dp, vertical = 36.dp).testTag(tag),
        ) {
            Text(kicker, style = MaterialTheme.typography.labelMedium, color = colors.fgTertiary, modifier = Modifier.testTag(kickerTag))
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, color = colors.fgPrimary, modifier = Modifier.testTag(titleTag))
            Spacer(Modifier.height(10.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary, modifier = Modifier.testTag(bodyTag))
            Spacer(Modifier.height(22.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PrimaryButton(onPrimary, enabled = !busy, modifier = Modifier.testTag(primaryTag)) { Text(primaryLabel) }
                if (onSecondary != null && secondaryTag != null && secondaryLabel != null) {
                    PaperButton(onSecondary, enabled = !busy, modifier = Modifier.testTag(secondaryTag)) { Text(secondaryLabel) }
                }
                secondaryExtra()
            }
            if (extra != null) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = colors.borderSubtle)
                Spacer(Modifier.height(18.dp))
                extra()
            }
        }
    }
}

/**
 * V7 puts every page on a sheet with the desk showing round it: centered inside the host area, capped to the
 * same reading column width as `Desk` so 今天 / 课程 read as a composed sheet rather than an empty slab.
 */
@Composable
private fun HostSheet(content: @Composable () -> Unit) {
    val colors = CoachTheme.colors
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.widthIn(max = HostSheetMaxWidth).fillMaxWidth()
                .paperShadow("sheet", colors.dark)
                .clip(RoundedCornerShape(CoachV7.Radius.sheet))
                .background(colors.surface1)
                .testTag("host-sheet"),
        ) { content() }
    }
}

private fun Textbook.progressLine(): String =
    "读到第 $readingPage 页" + if (learning) "，学习停在第 ${lessonIndex + 1} / ${lessons.size} 节" else "，还没开始按节学"

@Composable
internal fun HostEmptyState(tag: String, title: String, text: String) {
    val colors = CoachTheme.colors
    HostSheet {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 44.dp, vertical = 36.dp).testTag(tag),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = colors.fgPrimary)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
        }
    }
}

@Composable
private fun SettingsSection(title: String, tag: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = CoachTheme.colors.fgPrimary,
        modifier = Modifier.padding(top = 4.dp).testTag(tag))
}

@Composable
private fun <T> SettingsChoice(label: String, options: List<T>, selected: T, name: (T) -> String, tag: String, onChoose: (T) -> Unit) {
    val colors = CoachTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary, modifier = Modifier.weight(1f))
        options.forEachIndexed { index, option ->
            V7TextButton(onClick = { onChoose(option) }, modifier = Modifier.testTag("$tag-$index"), ringInside = true) {
                Text(name(option), style = MaterialTheme.typography.labelMedium,
                    color = if (option == selected) colors.fgPrimary else colors.fgTertiary,
                    fontWeight = if (option == selected) androidx.compose.ui.text.font.FontWeight.SemiBold else null)
            }
        }
    }
}

@Composable
private fun SettingsSwitch(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = CoachTheme.colors.fgPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

/**
 * V7：设置弹窗和其他弹窗一样是 surface2 的纸。教材位置降为 fgSecondary 小字并把用户主目录缩写为「用户文件夹」，
 * 同时保留完整路径的复制与选中能力；「知道了」走纸面按钮。
 */
@Composable
internal fun HostSettings(
    directory: Path,
    onPersonalCourse: (() -> Unit)? = null,
    preferences: com.coach.desktop.books.ReadingSettings? = null,
    onClose: () -> Unit,
) {
    val colors = CoachTheme.colors
    val paper = RoundedCornerShape(CoachV7.Radius.btn)
    val effectiveDirectory = LocalSettingsDirectoryOverride.current ?: directory
    val fullPath = remember(effectiveDirectory) { effectiveDirectory.toAbsolutePath().normalize().toString() }
    val displayPath = remember(effectiveDirectory) { formatLibraryPathForDisplay(effectiveDirectory) }
    val abbreviated = displayPath != fullPath
    var showFullPath by remember(fullPath) { mutableStateOf(false) }
    var copied by remember(fullPath) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    AlertDialog(onDismissRequest = onClose, title = { Text("设置") }, text = {
        // 单页四段：账号与连接 / 阅读与外观 / 学习默认项 / 数据与关于。没有二级页，往下滚就能看全。
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).testTag("settings-page"),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsSection("账号与连接", "settings-section-account")
            Text("教材和进度保存在此设备。提问与讲解时只发送当前所选片段或本节正文至 Coach 服务。", style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
            if (onPersonalCourse != null) TextButton(onPersonalCourse) { Text("打开已配对课程") }
            Text("课程里可做本地教材参考练习；服务上的课程数据仍独立。",
                style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
            if (preferences != null) {
                SettingsSection("阅读与外观", "settings-section-reading")
                SettingsChoice("字号", ReadingSize.entries, preferences.size, { it.label }, "settings-size") { preferences.chooseSize(it) }
                SettingsChoice("字体", ReadingFont.entries, preferences.font, { it.label }, "settings-font") { preferences.chooseFont(it) }
                SettingsChoice("纸色", PaperColor.entries, preferences.paper, { it.label }, "settings-paper") { preferences.choosePaper(it) }
                SettingsSection("学习默认项", "settings-section-learning")
                SettingsSwitch("读完一节先给导读", preferences.autoGuide, "settings-auto-guide") { preferences.chooseAutoGuide(it) }
                SettingsSwitch("每节读完给小结", preferences.sectionSummary, "settings-section-summary") { preferences.chooseSectionSummary(it) }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("伴读讲解与提问", style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
                        Text(
                            if (preferences.aiExplain) "已开启，可在阅读时使用讲解与提问" else "已关闭，仅本地阅读不发送正文",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.fgSecondary,
                        )
                    }
                    Switch(
                        checked = preferences.aiExplain,
                        onCheckedChange = { preferences.chooseAiExplain(it) },
                        modifier = Modifier.testTag("settings-ai-toggle"),
                    )
                }
            }
            SettingsSection("数据与关于", "settings-section-data")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("教材位置", style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                SelectionContainer {
                    Text(
                        displayPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.fgSecondary,
                        fontFamily = LocalV7Fonts.current.numbers,
                        modifier = Modifier.fillMaxWidth().clip(paper).background(colors.surface1)
                            .border(1.dp, colors.borderSubtle, paper)
                            .padding(horizontal = 12.dp, vertical = 8.dp).testTag("settings-library-directory"),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    V7TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(fullPath))
                            copied = true
                        },
                        modifier = Modifier.testTag("settings-library-copy"),
                        ringInside = true,
                    ) {
                        Text(if (copied) "已复制完整路径" else "复制完整路径", style = MaterialTheme.typography.labelSmall, color = colors.fgSecondary)
                    }
                    if (abbreviated) {
                        V7TextButton(
                            onClick = { showFullPath = !showFullPath },
                            modifier = Modifier.testTag("settings-library-toggle-full"),
                            ringInside = true,
                        ) {
                            Text(if (showFullPath) "收起完整路径" else "查看完整路径", style = MaterialTheme.typography.labelSmall, color = colors.fgTertiary)
                        }
                    }
                }
                if (abbreviated && showFullPath) {
                    SelectionContainer {
                        Text(
                            fullPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.fgTertiary,
                            fontFamily = LocalV7Fonts.current.numbers,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("settings-library-full-path"),
                        )
                    }
                }
            }
        }
    }, confirmButton = {
        PaperButton(onClose, modifier = Modifier.testTag("close-settings")) { Text("知道了") }
    })
}
