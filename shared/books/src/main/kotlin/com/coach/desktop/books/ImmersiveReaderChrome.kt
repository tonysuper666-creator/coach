@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.coach.desktop.books

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import com.coach.ui.v7.paperShadow
import com.coach.ui.v7.v7FocusRing
import kotlin.math.roundToInt

/** 右侧统一伴读抽屉的三个页签：讲解、提问、批注，合在同一面板里，不再一层套一层。 */
internal enum class ReaderCompanionTab(val label: String, val tag: String) {
    EXPLAIN("讲解", "companion-tab-explain"),
    ASK("提问", "companion-tab-ask"),
    NOTES("批注", "companion-tab-notes"),
}

/** 左侧抽屉的两个页签：目录与书签。 */
internal enum class ReaderLeftTab(val label: String, val tag: String) {
    TOC("目录", "left-tab-toc"),
    BOOKMARKS("书签", "left-tab-bookmarks"),
}

/**
 * 沉浸阅读底边 2dp 进度细线：默认隐藏全部工具栏时，仍以极低干扰显示全书阅读进度。
 */
@Composable
internal fun ImmersiveProgressHairline(
    currentPage: Int,
    lastPage: Int,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val fraction = if (lastPage > 1) ((currentPage - 1).toFloat() / (lastPage - 1)).coerceIn(0f, 1f) else 1f
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(colors.borderSubtle.copy(alpha = 0.45f))
            .testTag("immersive-progress-hairline"),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(v7Pine().copy(alpha = 0.72f)),
        )
    }
}

/**
 * 顶部浮动工具栏（44dp）：默认隐藏，鼠标移至顶部边缘、轻点空白区或按 Tab 呼出，浮在正文上方不挤压版面。
 */
@Composable
internal fun ImmersiveTopHud(
    visible: Boolean,
    bookTitle: String,
    sectionTitle: String?,
    backLabel: String,
    hasOriginal: Boolean,
    textView: Boolean,
    tocOpen: Boolean,
    companionOpen: Boolean,
    searchOpen: Boolean,
    bookmarked: Boolean,
    pinnedHud: Boolean,
    busy: Boolean,
    showLearnAction: Boolean,
    learnLabel: String,
    onBack: () -> Unit,
    onToggleToc: () -> Unit,
    onSelectView: (textView: Boolean) -> Unit,
    onToggleSearch: () -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleCompanion: () -> Unit,
    onTogglePinHud: () -> Unit,
    onLearn: () -> Unit,
    typeControl: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val barBg = colors.surface1.copy(alpha = 0.96f)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().background(barBg).testTag("immersive-top-hud")) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                V7TextButton(
                    onClick = onBack,
                    enabled = !busy,
                    modifier = Modifier.testTag("immersive-back"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ringInside = true,
                ) {
                    Text("← $backLabel", style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
                }
                V7TextButton(
                    onClick = onToggleToc,
                    modifier = Modifier.testTag("v7-directory-toggle"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ringInside = true,
                ) {
                    Text(
                        "目录",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (tocOpen) v7Pine() else colors.fgSecondary,
                        fontWeight = if (tocOpen) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                val titleText = remember(bookTitle, sectionTitle) {
                    if (sectionTitle.isNullOrBlank() || sectionTitle == bookTitle) bookTitle
                    else "$bookTitle · $sectionTitle"
                }
                Text(
                    titleText,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.fgSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("immersive-title"),
                )
                if (hasOriginal) {
                    ViewModeSwitchPill(
                        textView = textView,
                        onSelect = onSelectView,
                    )
                }
                V7TextButton(
                    onClick = onToggleSearch,
                    enabled = !busy,
                    modifier = Modifier.testTag("book-search-toggle"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ringInside = true,
                ) {
                    Text("查找", style = MaterialTheme.typography.labelLarge, color = if (searchOpen) v7Pine() else colors.fgSecondary)
                }
                V7TextButton(
                    onClick = onToggleBookmark,
                    modifier = Modifier.testTag("immersive-bookmark-toggle"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ringInside = true,
                ) {
                    Text(
                        if (bookmarked) "已存书签" else "书签",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (bookmarked) v7Pine() else colors.fgSecondary,
                    )
                }
                Box(Modifier.padding(horizontal = 4.dp)) { typeControl() }
                V7TextButton(
                    onClick = onToggleCompanion,
                    modifier = Modifier.testTag("immersive-companion-toggle"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ringInside = true,
                ) {
                    Text(
                        if (companionOpen) "收起伴读" else "伴读",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (companionOpen) v7Pine() else colors.fgPrimary,
                        fontWeight = if (companionOpen) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
                V7TextButton(
                    onClick = onTogglePinHud,
                    modifier = Modifier.testTag("immersive-pin-hud"),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    ringInside = true,
                ) {
                    Text(
                        if (pinnedHud) "取消固定" else "固定栏",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (pinnedHud) v7Pine() else colors.fgTertiary,
                    )
                }
                if (showLearnAction) {
                    V7TextButton(
                        onClick = onLearn,
                        enabled = !busy,
                        modifier = Modifier.testTag("immersive-learn-btn"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ringInside = true,
                    ) {
                        Text(learnLabel, style = MaterialTheme.typography.labelLarge, color = v7Pine(), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.7f))
        }
    }
}

/** 统一「原页 / 文字」分段切换胶囊，同锚点原地切换。 */
@Composable
private fun ViewModeSwitchPill(
    textView: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    val colors = CoachTheme.colors
    val outerShape = RoundedCornerShape(CoachV7.Radius.btn)
    val itemShape = RoundedCornerShape(CoachV7.Radius.s)
    val activeBg = if (colors.dark) CoachV7.Dark.pineWash else CoachV7.Light.pineWash
    Row(
        Modifier
            .clip(outerShape)
            .background(colors.surface2)
            .border(1.dp, colors.borderSubtle, outerShape)
            .padding(2.dp)
            .testTag("book-original-toggle"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val options = listOf(false to "原页", true to "文字")
        for ((isText, label) in options) {
            val selected = textView == isText
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) v7Pine() else colors.fgSecondary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(itemShape)
                    .background(if (selected) activeBg else Color.Transparent)
                    .clickable(role = Role.Tab) { if (!selected) onSelect(isText) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag(if (isText) "view-mode-text" else "view-mode-original"),
            )
        }
    }
}

/**
 * 底部浮动进度与跳页栏（44dp）：默认隐藏，鼠标移至底边或按 Tab / Ctrl+G 呼出。
 */
@Composable
internal fun ImmersiveBottomScrubber(
    visible: Boolean,
    currentPage: Int,
    lastPage: Int,
    pageLabel: String,
    sectionTitle: String?,
    canPrev: Boolean,
    canNext: Boolean,
    editingJump: Boolean,
    jumpText: String,
    onJumpTextChange: (String) -> Unit,
    onStartEditJump: () -> Unit,
    onCancelEditJump: () -> Unit,
    onJumpFocusChange: (Boolean) -> Unit,
    onSubmitJump: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onScrubToPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val barBg = colors.surface1.copy(alpha = 0.96f)
    var sliderPos by remember(currentPage) { mutableFloatStateOf(currentPage.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val shownPage = if (dragging) sliderPos.roundToInt().coerceIn(1, lastPage.coerceAtLeast(1)) else currentPage
    val percent = if (lastPage > 1) ((shownPage - 1) * 100 / (lastPage - 1)).coerceIn(0, 100) else 100

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().background(barBg).testTag("immersive-bottom-hud")) {
            HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.7f))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                V7TextButton(
                    onClick = onPrev,
                    enabled = canPrev,
                    modifier = Modifier.testTag("previous-page"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ringInside = true,
                ) { Text("‹ 上一页", style = MaterialTheme.typography.labelLarge) }

                if (lastPage > 1) {
                    Slider(
                        value = sliderPos.coerceIn(1f, lastPage.toFloat()),
                        onValueChange = {
                            dragging = true
                            sliderPos = it
                        },
                        onValueChangeFinished = {
                            dragging = false
                            val target = sliderPos.roundToInt().coerceIn(1, lastPage)
                            if (target != currentPage) onScrubToPage(target)
                        },
                        valueRange = 1f..lastPage.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = v7Pine(),
                            activeTrackColor = v7Pine(),
                            inactiveTrackColor = colors.borderSubtle,
                        ),
                        modifier = Modifier.weight(1f).testTag("immersive-page-slider"),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }

                V7TextButton(
                    onClick = onNext,
                    enabled = canNext,
                    modifier = Modifier.testTag("next-page"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ringInside = true,
                ) { Text("下一页 ›", style = MaterialTheme.typography.labelLarge) }

                if (!editingJump) {
                    val label = if (dragging) "第 $shownPage / $lastPage 页" else "$pageLabel / $lastPage"
                    Text(
                        "$label · $percent%",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = LocalV7Fonts.current.numbers,
                        color = colors.fgSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(CoachV7.Radius.s))
                            .clickable(role = Role.Button) { onStartEditJump() }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("reader-page-number"),
                    )
                } else {
                    val jumpFocus = remember { FocusRequester() }
                    val fieldShape = RoundedCornerShape(CoachV7.Radius.s)
                    BasicTextField(
                        value = jumpText,
                        onValueChange = { onJumpTextChange(it.filter(Char::isDigit).take(5)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelMedium.copy(
                            color = colors.fgPrimary,
                            textAlign = TextAlign.Center,
                            fontFamily = LocalV7Fonts.current.numbers,
                        ),
                        cursorBrush = SolidColor(colors.fgPrimary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            jumpText.toIntOrNull()?.let(onSubmitJump)
                        }),
                        modifier = Modifier
                            .width(72.dp)
                            .clip(fieldShape)
                            .background(colors.surface2)
                            .border(1.dp, v7Pine(), fieldShape)
                            .focusRequester(jumpFocus)
                            .onFocusChanged { onJumpFocusChange(it.isFocused) }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                    jumpText.toIntOrNull()?.let(onSubmitJump); true
                                } else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                    onCancelEditJump(); true
                                } else false
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .testTag("page-jump"),
                    )
                    LaunchedEffect(Unit) { runCatching { jumpFocus.requestFocus() } }
                }

                if (!sectionTitle.isNullOrBlank()) {
                    Text(
                        sectionTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.fgTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                }
            }
        }
    }
}

/**
 * 左侧浮动抽屉（300dp）：目录 + 书签双页签，点击即跳，不挤占正文宽度。
 */
@Composable
internal fun ImmersiveTocDrawer(
    visible: Boolean,
    book: Textbook,
    currentPage: Int,
    currentSectionIndex: Int,
    bookmarks: Set<Int>,
    busy: Boolean,
    onJumpPage: (Int) -> Unit,
    onToggleBookmark: (Int) -> Unit,
    onReorganizeSections: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    var activeTab by remember { mutableStateOf(ReaderLeftTab.TOC) }
    val activeBg = if (colors.dark) CoachV7.Dark.pineWash else CoachV7.Light.pineWash
    val rowShape = RoundedCornerShape(CoachV7.Radius.s)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInHorizontally { -it },
        exit = fadeOut() + slideOutHorizontally { -it },
        modifier = modifier.fillMaxHeight(),
    ) {
        Row(Modifier.width(308.dp).fillMaxHeight().testTag("immersive-toc-drawer")) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(colors.surface1)
                    .padding(top = 12.dp, bottom = 12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (tab in ReaderLeftTab.entries) {
                        val selected = activeTab == tab
                        val countSuffix = if (tab == ReaderLeftTab.BOOKMARKS && bookmarks.isNotEmpty()) " (${bookmarks.size})" else ""
                        Text(
                            tab.label + countSuffix,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) v7Pine() else colors.fgSecondary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(rowShape)
                                .background(if (selected) activeBg else Color.Transparent)
                                .clickable(role = Role.Tab) { activeTab = tab }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .testTag(tab.tag),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    V7TextButton(
                        onClick = onClose,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("toc-drawer-close"),
                        ringInside = true,
                    ) { Text("收起", style = MaterialTheme.typography.labelMedium) }
                }
                HorizontalDivider(
                    Modifier.padding(top = 6.dp),
                    thickness = 1.dp,
                    color = colors.borderSubtle,
                )
                when (activeTab) {
                    ReaderLeftTab.TOC -> {
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth().testTag("v7-directory-drawer"),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            itemsIndexed(book.lessons) { idx, lesson ->
                                val active = idx == currentSectionIndex
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(rowShape)
                                        .background(if (active) activeBg else Color.Transparent)
                                        .clickable(enabled = !busy) { onJumpPage(lesson.firstPage) }
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        lesson.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (active) v7Pine() else colors.fgPrimary,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${lesson.firstPage}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = LocalV7Fonts.current.numbers,
                                        color = if (active) v7Pine() else colors.fgTertiary,
                                    )
                                }
                            }
                        }
                        if (onReorganizeSections != null) {
                            HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle)
                            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                                V7TextButton(
                                    onClick = onReorganizeSections,
                                    enabled = !busy,
                                    modifier = Modifier.testTag("reorganize-sections"),
                                    ringInside = true,
                                ) { Text("重新识别目录", style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                    }
                    ReaderLeftTab.BOOKMARKS -> {
                        val sorted = remember(bookmarks) { bookmarks.sorted() }
                        if (sorted.isEmpty()) {
                            Column(
                                Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("还没有书签", style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
                                Spacer(Modifier.height(6.dp))
                                Text("按 Ctrl+D 可收藏当前页。", style = MaterialTheme.typography.bodySmall, color = colors.fgTertiary)
                            }
                        } else {
                            LazyColumn(
                                Modifier.weight(1f).fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                itemsIndexed(sorted) { _, pageNum ->
                                    val active = pageNum == currentPage
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(rowShape)
                                            .background(if (active) activeBg else Color.Transparent)
                                            .clickable(enabled = !busy) { onJumpPage(pageNum) }
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            book.pageLabel(pageNum),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = LocalV7Fonts.current.numbers,
                                            color = if (active) v7Pine() else colors.fgPrimary,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            "移除",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.fgTertiary,
                                            modifier = Modifier
                                                .clickable { onToggleBookmark(pageNum) }
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.borderSubtle))
        }
    }
}

/**
 * 右侧统一伴读抽屉（380dp）：讲解、提问、批注收进同一个平面容器，以细线分隔，杜绝嵌套卡片。
 */
@Composable
internal fun ImmersiveCompanionDrawer(
    visible: Boolean,
    docked: Boolean,
    canDock: Boolean,
    activeTab: ReaderCompanionTab,
    onSelectTab: (ReaderCompanionTab) -> Unit,
    onToggleDock: () -> Unit,
    onClose: () -> Unit,
    explainContent: @Composable () -> Unit,
    askContent: @Composable () -> Unit,
    notesContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val activeBg = if (colors.dark) CoachV7.Dark.pineWash else CoachV7.Light.pineWash
    val tabShape = RoundedCornerShape(CoachV7.Radius.s)

    val body: @Composable () -> Unit = {
        Row(Modifier.width(380.dp).fillMaxHeight().testTag("immersive-companion-drawer")) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.borderSubtle))
            Column(Modifier.weight(1f).fillMaxHeight().background(colors.surface1)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (tab in ReaderCompanionTab.entries) {
                        val selected = activeTab == tab
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) v7Pine() else colors.fgSecondary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(tabShape)
                                .background(if (selected) activeBg else Color.Transparent)
                                .clickable(role = Role.Tab) { onSelectTab(tab) }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .testTag(tab.tag),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (canDock) {
                        V7TextButton(
                            onClick = onToggleDock,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("companion-dock-toggle"),
                            ringInside = true,
                        ) {
                            Text(if (docked) "浮层" else "并排", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    V7TextButton(
                        onClick = onClose,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("companion-close"),
                        ringInside = true,
                    ) { Text("×", style = MaterialTheme.typography.labelLarge) }
                }
                HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (activeTab) {
                        ReaderCompanionTab.EXPLAIN -> explainContent()
                        ReaderCompanionTab.ASK -> askContent()
                        ReaderCompanionTab.NOTES -> notesContent()
                    }
                }
            }
        }
    }

    if (docked) {
        if (visible) Box(modifier.fillMaxHeight()) { body() }
    } else {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInHorizontally { it },
            exit = fadeOut() + slideOutHorizontally { it },
            modifier = modifier.fillMaxHeight(),
        ) {
            body()
        }
    }
}

/**
 * 选段后浮出的紧凑操作条（高亮 / 讲讲 / 举个例子 / 问… / 记笔记），直接把结果路由到右侧伴读抽屉。
 */
@Composable
internal fun ImmersiveSelectionPill(
    showHighlight: Boolean,
    highlighted: Boolean,
    highlightBusy: Boolean,
    onHighlight: () -> Unit,
    onAction: (SelectionExplainAction) -> Unit,
    onNote: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.m)
    Row(
        modifier
            .paperShadow("button", colors.dark)
            .clip(shape)
            .background(if (colors.dark) CoachV7.Dark.menuBg else CoachV7.Light.menuBg)
            .border(1.dp, colors.borderSubtle, shape)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag("selection-explain-toolbar"),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showHighlight) {
            V7TextButton(
                onClick = onHighlight,
                enabled = !highlightBusy,
                modifier = Modifier.testTag("original-pdf-highlight"),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
            ) {
                Text(if (highlighted) "取消高亮" else "高亮", style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
            }
        }
        for (action in listOf(SelectionExplainAction.EXPLAIN, SelectionExplainAction.EXAMPLE, SelectionExplainAction.ASK)) {
            V7TextButton(
                onClick = { onAction(action) },
                modifier = Modifier.testTag(action.tag),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
            ) {
                Text(action.label, style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
            }
        }
        V7TextButton(
            onClick = onNote,
            modifier = Modifier.testTag("selection-action-note"),
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
        ) {
            Text("笔记", style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary)
        }
        V7TextButton(
            onClick = onDismiss,
            modifier = Modifier.testTag("selection-toolbar-close"),
            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
        ) {
            Text("×", style = MaterialTheme.typography.labelLarge, color = colors.fgTertiary)
        }
    }
}

/**
 * 伴读抽屉内的「提问」面板：无嵌套卡片，扁平对话流 + 底部提问输入框。
 */
@Composable
internal fun CompanionAskPane(
    book: Textbook,
    allBlocks: List<SourceBlock>,
    activeSelection: SourceBlock?,
    currentSection: BookLesson?,
    thread: InlineExplainThread?,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSubmitQuestion: () -> Unit,
    onRetry: () -> Unit,
    onOpenPair: () -> Unit,
    onOpenCitation: (Citation, SourceBlock) -> Unit,
    onClearSelection: () -> Unit,
) {
    val colors = CoachTheme.colors
    Column(Modifier.fillMaxSize().padding(14.dp).testTag("companion-ask-pane"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val quoteSource = thread?.selection ?: activeSelection
        if (quoteSource != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (colors.dark) CoachV7.Dark.pineWash else CoachV7.Light.pineWash, RoundedCornerShape(CoachV7.Radius.s))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "引文 · ${shortQuote(quoteSource.text)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "清除",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.fgTertiary,
                    modifier = Modifier.clickable { onClearSelection() },
                )
            }
        } else {
            Text(
                currentSection?.let { "围绕「${it.title}」提问，或先选中一段原文再追问。" }
                    ?: "选中一段原文，或直接就当前页提问。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
            )
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (thread != null) {
                for ((index, entry) in thread.turns.withIndex()) {
                    Column(Modifier.fillMaxWidth().testTag("inline-explain-turn-$index"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.label, style = MaterialTheme.typography.labelMedium, color = v7Pine())
                        ExplanationView(book, allBlocks, entry.explanation, entry.sentBlocks) { citation ->
                            entry.sentBlocks.firstOrNull { it.id == citation.blockId }?.let { onOpenCitation(citation, it) }
                        }
                    }
                    HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.6f))
                }
                if (thread.loading) {
                    Text("思考中…", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary, modifier = Modifier.testTag("inline-explain-loading"))
                }
                thread.error?.let { (message, trouble) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (trouble == ExplainTrouble.Revoked) v7Alert() else colors.fgSecondary,
                            modifier = Modifier.weight(1f).testTag("inline-explain-error"),
                        )
                        if (trouble.needsPairing) TextButton(onClick = onOpenPair) { Text("连接服务") }
                        else TextButton(onClick = onRetry) { Text("重试") }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                V7Field(
                    value = draft,
                    onValueChange = onDraftChange,
                    label = if (quoteSource != null) "就这段问一句…" else "输入问题…",
                    enabled = thread?.loading != true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inline-followup-input")
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed &&
                                BookPassageQuestion.accepts(draft) && thread?.loading != true) {
                                onSubmitQuestion(); true
                            } else false
                        },
                )
            }
            PrimaryButton(
                onClick = onSubmitQuestion,
                enabled = thread?.loading != true && BookPassageQuestion.accepts(draft),
                modifier = Modifier.testTag("inline-followup-send"),
            ) { Text("发送") }
        }
    }
}
