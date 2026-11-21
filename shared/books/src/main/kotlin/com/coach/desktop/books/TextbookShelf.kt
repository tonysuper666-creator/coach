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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.adaptive.Hinge
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
    val pine = colors.actionPrimary
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
    onPath: (LearningPath) -> Unit, onCancelImport: () -> Unit = {}, hinge: Hinge? = LocalBookHinge.current,
    onExportNotes: ((Textbook, Int) -> Unit)? = null) {
    val book = ui.book
    val colors = CoachTheme.colors
    val scroll = rememberScrollState()
    val rightScroll = rememberScrollState()
    val density = LocalDensity.current
    var sheetOriginLeftDp by remember { mutableStateOf<Float?>(null) }
    val line = colors.borderSubtle
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = if (hinge != null) CoachV7.Space.s3 else CoachV7.Space.s6, vertical = CoachV7.Space.s3), contentAlignment = Alignment.TopCenter) {
        val sectionSpacing = if (maxHeight < 560.dp) CoachV7.Space.s2 else CoachV7.Space.s5
        // V7 首页：居中单栏，最宽 720dp；折叠屏横放时才按铰链分左右两页。
        Box(Modifier.widthIn(max = if (hinge != null) 1000.dp else 720.dp).fillMaxWidth().fillMaxHeight().paperShadow("sheet", colors.dark)
            .clip(RoundedCornerShape(CoachV7.Radius.sheet)).background(colors.surface1).testTag("shelf-sheet")) {
            val catalogHeader: @Composable () -> Unit = {
                Text("书架", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("library-title"))
                Text("带来一本教材，从想读的地方开始。", color = colors.fgSecondary)
                if (book == null) {
                    PrimaryButton(onImport, enabled = !ui.busy, modifier = Modifier.testTag("upload-textbook")) { Text("上传教材") }
                } else {
                    V7TextButton(onImport, enabled = !ui.busy, modifier = Modifier.testTag("upload-textbook")) { Text("上传教材") }
                }
                Text("${BookFormats.description} · 教材和进度保存在此设备", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
            }
            val catalogList: @Composable () -> Unit = {
                if (book == null) {
                    Text("书架还是空的", style = MaterialTheme.typography.titleLarge)
                    Text("上传后，可以直接开始学，也可以先当一本书读。", color = colors.fgSecondary)
                } else {
                    Text("全部教材", style = MaterialTheme.typography.titleMedium)
                    Column {
                        ShelfDivider(line)
                        for (item in ui.catalog) {
                            ShelfBookRow(
                                item = item,
                                current = item.id == book.id,
                                enabled = !ui.busy,
                                activeImport = ui.importing?.takeIf { it.recognizing && it.bookId == item.id },
                                onCancelImport = onCancelImport,
                            ) {
                                if (item.id == book.id) onRead() else onSelect(item.id)
                            }
                            ShelfDivider(line)
                        }
                    }
                }
            }
            val selectedActions: @Composable () -> Unit = {
                if (book != null) {
                    Text(book.title, style = MaterialTheme.typography.headlineLarge, fontFamily = LocalV7Fonts.current.display)
                    PrimaryButton(onRead, enabled = !ui.busy, modifier = Modifier.testTag("home-read")) {
                        Text(if (book.readingPage > 1) "继续阅读 · 第 ${book.readingPage} 页" else "开始阅读")
                    }
                    if (onExportNotes != null) {
                        Column {
                            ShelfDivider(line)
                            EntryRow("导出笔记与复习提纲", "生成本教材的 Markdown 笔记与可打印 PDF。", "book-export-notes", !ui.busy) {
                                onExportNotes(book, book.readingPage)
                            }
                            ShelfDivider(line)
                        }
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize()
                .padding(start = if (hinge != null) CoachV7.Space.s4 else CoachV7.Space.s7,
                    top = if (hinge != null) CoachV7.Space.s4 else CoachV7.Space.s7,
                    end = if (hinge != null) CoachV7.Space.s4 else CoachV7.Space.s8,
                    bottom = if (hinge != null) CoachV7.Space.s4 else CoachV7.Space.s8)
                .onGloballyPositioned { coords -> sheetOriginLeftDp = coords.positionInRoot().x / density.density }) {
                val verticalSplit = if (book != null) bookVerticalSplit(maxWidth.value, maxHeight.value, sheetOriginLeftDp ?: 0f, hinge) else null
                if (verticalSplit != null) {
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.width(verticalSplit.leftDp.dp).fillMaxHeight().verticalScroll(scroll)
                            .testTag("shelf-half-fold-left"), verticalArrangement = Arrangement.spacedBy(sectionSpacing)) {
                            catalogHeader()
                            catalogList()
                        }
                        Spacer(Modifier.width(verticalSplit.gapDp.dp).fillMaxHeight().testTag("shelf-half-fold-hinge"))
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rightScroll)
                            .testTag("shelf-half-fold-right"), verticalArrangement = Arrangement.spacedBy(sectionSpacing)) {
                            selectedActions()
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(sectionSpacing)) {
                        catalogHeader()
                        selectedActions()
                        catalogList()
                    }
                    BookScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = CoachV7.Space.s4))
                }
            }
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
    val wash = colors.actionPrimary.copy(alpha = 0.12f)
    Column(Modifier.fillMaxWidth().background(if (current) wash else androidx.compose.ui.graphics.Color.Transparent)) {
        Row(Modifier.fillMaxWidth().testTag("book-${item.id}")
            .shelfClick(enabled, onClick).padding(horizontal = CoachV7.Space.s3, vertical = CoachV7.Space.s3),
            horizontalArrangement = Arrangement.spacedBy(CoachV7.Space.s3), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(CoachV7.Space.s1).height(CoachV7.Space.s7)
                .background(if (current) colors.actionPrimary else androidx.compose.ui.graphics.Color.Transparent))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CoachV7.Space.s1)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
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
