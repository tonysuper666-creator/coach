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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import com.coach.ui.v7.paperShadow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

/** 按书持久化「用户是否曾主动收起顶底栏」：首次打开某本书时栏常显，主动收起一次后才开始自动隐藏。 */
internal class BookHudPreferenceStore(private val directory: Path) {
    private val file: Path get() = directory.resolve("reader-hud-dismissed.txt")

    fun isDismissed(bookId: String): Boolean {
        if (bookId.isBlank()) return false
        return runCatching {
            if (!Files.exists(file)) false
            else Files.readAllLines(file).any { it.trim() == bookId }
        }.getOrDefault(false)
    }

    fun markDismissed(bookId: String) {
        if (bookId.isBlank()) return
        runCatching {
            Files.createDirectories(directory)
            val existing = if (Files.exists(file)) Files.readAllLines(file).map(String::trim).filter(String::isNotEmpty).toMutableSet()
            else linkedSetOf()
            if (existing.add(bookId)) {
                Files.writeString(file, existing.joinToString("\n", postfix = "\n"))
            }
        }
    }
}

/** 统一伴读抽屉的三个页签：讲解、提问、批注，合在同一平面容器里，不再一层套一层。 */
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

/** 伴读抽屉在当前窗口尺寸下的布局形态（按 2.0 合并定稿裁决）。 */
internal enum class CompanionPresentation {
    /** 宽屏 >= 1280dp：默认并排停靠（零像素遮挡正文，保留浮层切换）。 */
    WIDE_SPLIT,
    /** 窄桌面 1000..1279dp：右侧浮层。 */
    NARROW_OVERLAY,
    /** 小窗与手机 < 1000dp（含 winbox 752×552 与直板机）：底部半屏抽屉，正文不归零。 */
    BOTTOM_SHEET,
}

internal fun companionPresentationForWidth(widthDp: Float): CompanionPresentation = when {
    widthDp >= 1280f -> CompanionPresentation.WIDE_SPLIT
    widthDp >= 1000f -> CompanionPresentation.NARROW_OVERLAY
    else -> CompanionPresentation.BOTTOM_SHEET
}

internal fun companionSideWidth(widthDp: Float): Dp = when {
    widthDp >= 1440f -> 380.dp
    else -> 360.dp
}

/**
 * 沉浸阅读底边 2dp 进度细线：默认隐藏全部工具栏时，仍以极低干扰显示全书阅读进度。
 */
@Composable
internal fun ImmersiveProgressHairline(
    currentPage: Int,
    lastPage: Int,
    subPageFraction: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val effectivePage = (currentPage - 1 + subPageFraction.coerceIn(0f, 0.99f))
    val fraction = if (lastPage > 1) (effectivePage / (lastPage - 1)).coerceIn(0f, 1f) else (subPageFraction.takeIf { it > 0f } ?: 1f).coerceIn(0f, 1f)
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
                .background(colors.actionPrimary.copy(alpha = 0.72f)),
        )
    }
}

/**
 * 顶部浮动工具栏（44dp）：默认隐藏，鼠标移至顶部边缘、轻点空白区或按 Tab 呼出，浮在正文上方不挤压版面。
 * 全按钮强约束 `maxLines = 1, softWrap = false`，窄窗与小屏绝不出现竖排单字折行。
 */
@Composable
internal fun ImmersiveTopHud(
    visible: Boolean,
    availableWidth: Dp,
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
    modeControl: (@Composable () -> Unit)? = null,
    onMoreMenuDismiss: () -> Unit = {},
    onOpenBookNotes: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val accent = colors.actionPrimary
    val barBg = colors.surface1.copy(alpha = 0.96f)
    val compact = availableWidth < 860.dp
    val veryCompact = availableWidth < 560.dp
    var moreMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (!visible) moreMenuOpen = false }
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
                    .padding(horizontal = if (compact) 8.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp),
            ) {
                V7TextButton(
                    onClick = onBack,
                    enabled = !busy,
                    modifier = Modifier.testTag("immersive-back"),
                    contentPadding = PaddingValues(horizontal = if (compact) 6.dp else 8.dp, vertical = 4.dp),
                    ringInside = true,
                ) {
                    Text(
                        if (veryCompact) "←" else "← $backLabel",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.fgPrimary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                V7TextButton(
                    onClick = onToggleToc,
                    modifier = Modifier.testTag("v7-directory-toggle"),
                    contentPadding = PaddingValues(horizontal = if (compact) 6.dp else 8.dp, vertical = 4.dp),
                    ringInside = true,
                ) {
                    Text(
                        "目录",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (tocOpen) accent else colors.fgSecondary,
                        fontWeight = if (tocOpen) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                val titleText = remember(bookTitle, sectionTitle, compact) {
                    when {
                        sectionTitle.isNullOrBlank() || sectionTitle == bookTitle -> bookTitle
                        compact -> sectionTitle
                        else -> "$bookTitle · $sectionTitle"
                    }
                }
                Text(
                    titleText,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.fgSecondary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = if (compact) 4.dp else 8.dp).testTag("immersive-title"),
                )
                if (modeControl != null) {
                    modeControl()
                }
                V7TextButton(
                    onClick = onToggleCompanion,
                    modifier = Modifier.testTag("immersive-companion-toggle"),
                    contentPadding = PaddingValues(horizontal = if (compact) 6.dp else 8.dp, vertical = 4.dp),
                    ringInside = true,
                ) {
                    Text(
                        if (companionOpen) (if (compact) "收起" else "收起伴读") else "伴读",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (companionOpen) accent else colors.fgPrimary,
                        fontWeight = if (companionOpen) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                // 次要动作收进「⋯」菜单，顶栏保持克制清爽（对齐 Sable +/⋯ 收纳规范）
                Box {
                    V7TextButton(
                        onClick = { moreMenuOpen = !moreMenuOpen },
                        modifier = Modifier.testTag("immersive-more-menu"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        ringInside = true,
                    ) {
                        Text(
                            "⋯",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (moreMenuOpen || bookmarked || pinnedHud) accent else colors.fgSecondary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    DropdownMenu(
                        expanded = moreMenuOpen,
                        onDismissRequest = {
                            moreMenuOpen = false
                            onMoreMenuDismiss()
                        },
                        containerColor = colors.surface1,
                    ) {
                        if (hasOriginal) {
                            Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                ViewModeSwitchPill(textView = textView, compact = veryCompact, onSelect = {
                                    moreMenuOpen = false
                                    onSelectView(it)
                                })
                            }
                        }
                        DropdownMenuItem(
                            text = { Text("查找", style = MaterialTheme.typography.labelLarge) },
                            enabled = !busy,
                            onClick = { moreMenuOpen = false; onToggleSearch() },
                            modifier = Modifier.testTag("book-search-toggle"),
                        )
                        DropdownMenuItem(
                            text = { Text("这本书的笔记", style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary) },
                            enabled = !busy,
                            onClick = { moreMenuOpen = false; onOpenBookNotes() },
                            modifier = Modifier.testTag("book-notes-open"),
                        )
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { typeControl() }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (bookmarked) "取消书签" else "添加书签（Ctrl+D）",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (bookmarked) accent else colors.fgPrimary,
                                )
                            },
                            onClick = { moreMenuOpen = false; onToggleBookmark() },
                            modifier = Modifier.testTag("immersive-bookmark-toggle"),
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (pinnedHud) "取消固定工具栏" else "固定顶底栏",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (pinnedHud) accent else colors.fgPrimary,
                                )
                            },
                            onClick = { moreMenuOpen = false; onTogglePinHud() },
                            modifier = Modifier.testTag("immersive-pin-hud"),
                        )
                        if (showLearnAction) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        learnLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = colors.fgPrimary,
                                    )
                                },
                                enabled = !busy,
                                onClick = { moreMenuOpen = false; onLearn() },
                                modifier = Modifier.testTag("immersive-learn-btn"),
                            )
                        }
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
    compact: Boolean = false,
    onSelect: (Boolean) -> Unit,
) {
    val colors = CoachTheme.colors
    val accent = colors.actionPrimary
    val outerShape = CircleShape
    val itemShape = CircleShape
    val activeBg = accent.copy(alpha = 0.14f)
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
                color = if (selected) accent else colors.fgSecondary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(itemShape)
                    .background(if (selected) activeBg else Color.Transparent)
                    .clickable(role = Role.Tab) { if (!selected) onSelect(isText) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = if (compact) 7.dp else 10.dp, vertical = 4.dp)
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
    availableWidth: Dp,
    currentPage: Int,
    lastPage: Int,
    pageStatus: (Int) -> String,
    canPrev: Boolean,
    canNext: Boolean,
    editingJump: Boolean,
    jumpSession: Int,
    jumpText: String,
    onJumpTextChange: (String) -> Unit,
    onStartEditJump: () -> Unit,
    onCancelEditJump: () -> Unit,
    onJumpFocusChange: (Boolean) -> Unit,
    onSubmitJump: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onScrubToPage: (Int) -> Unit,
    subPageFraction: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val barBg = colors.surface1.copy(alpha = 0.96f)
    val compact = availableWidth < 760.dp
    var sliderPos by remember(currentPage) { mutableFloatStateOf(currentPage.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val effectiveSlider = if (dragging) sliderPos else (currentPage + subPageFraction.coerceIn(0f, 0.99f)).coerceIn(1f, lastPage.coerceAtLeast(1).toFloat())
    val shownPage = if (dragging) sliderPos.roundToInt().coerceIn(1, lastPage.coerceAtLeast(1)) else currentPage

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
                    .padding(horizontal = if (compact) 8.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
            ) {
                V7TextButton(
                    onClick = onPrev,
                    enabled = canPrev,
                    modifier = Modifier.testTag("previous-page"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ringInside = true,
                ) { Text(if (compact) "‹" else "‹ 上一页", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false) }

                if (lastPage > 1) {
                    Slider(
                        value = effectiveSlider.coerceIn(1f, lastPage.toFloat()),
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
                            thumbColor = colors.actionPrimary,
                            activeTrackColor = colors.actionPrimary,
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
                ) { Text(if (compact) "›" else "下一页 ›", style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false) }

                if (!editingJump) {
                    Text(
                        pageStatus(shownPage),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = LocalV7Fonts.current.numbers,
                        color = colors.fgSecondary,
                        maxLines = 1,
                        softWrap = false,
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
                    // 草稿留在输入框这一层。阅读器整页重组慢，连敲的数字若等父状态回来再画，会丢位（154 收成 15）。
                    // jumpSession 只在打开或再次 Ctrl+G 时变，用来重新种入当前页；敲字过程中不把父状态回写进来。
                    var draft by remember(jumpSession) {
                        mutableStateOf(TextFieldValue(jumpText, TextRange(jumpText.length)))
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { next ->
                            val filtered = next.text.filter(Char::isDigit).take(5)
                            draft = if (filtered == next.text) next else TextFieldValue(filtered, TextRange(filtered.length))
                            onJumpTextChange(filtered)
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelMedium.copy(
                            color = colors.fgPrimary,
                            textAlign = TextAlign.Center,
                            fontFamily = LocalV7Fonts.current.numbers,
                        ),
                        cursorBrush = SolidColor(colors.fgPrimary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            draft.text.toIntOrNull()?.let(onSubmitJump)
                        }),
                        modifier = Modifier
                            .width(64.dp)
                            .clip(fieldShape)
                            .background(colors.surface2)
                            .border(1.dp, colors.actionPrimary, fieldShape)
                            .focusRequester(jumpFocus)
                            .onFocusChanged { onJumpFocusChange(it.isFocused) }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                    draft.text.toIntOrNull()?.let(onSubmitJump); true
                                } else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                    onCancelEditJump(); true
                                } else false
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .testTag("page-jump"),
                    )
                    V7TextButton(
                        onClick = { draft.text.toIntOrNull()?.let(onSubmitJump) },
                        enabled = draft.text.toIntOrNull()?.let { it in 1..lastPage } == true,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("page-jump-go"),
                        ringInside = true,
                    ) { Text("前往", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) }
                    LaunchedEffect(Unit) { runCatching { jumpFocus.requestFocus() } }
                }

            }
        }
    }
}

/**
 * 左侧浮动抽屉（目录 + 书签双页签）：使用 LazyColumn 虚拟化列表、自动滚动到当前节、点击即跳。
 */
@Composable
internal fun ImmersiveTocDrawer(
    visible: Boolean,
    availableWidth: Dp,
    book: Textbook,
    currentPage: Int,
    currentAnchor: BookPageAnchor,
    currentChapterId: String?,
    bodyStart: BookPageAnchor,
    bodyStartCorrected: Boolean,
    bookmarks: List<BookBookmark>,
    busy: Boolean,
    onJumpPage: (Int) -> Unit,
    onToggleBookmark: (Int) -> Unit,
    onJumpToc: (BookTocNode) -> Unit,
    onSaveBodyStart: suspend (Int?) -> Unit,
    onEditChapter: (suspend (StructureEdit) -> Unit)? = null,
    onReorganizeSections: (() -> Unit)?,
    onClose: () -> Unit,
    readChapterIds: Set<String> = emptySet(),
    activeTocNodeId: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    var activeTab by remember(book.id) { mutableStateOf(ReaderLeftTab.TOC) }
    val activeBg = colors.actionPrimary.copy(alpha = 0.12f)
    val rowShape = RoundedCornerShape(CoachV7.Radius.s)
    val drawerWidth = minOf(308.dp, (availableWidth * 0.85f).coerceAtLeast(240.dp))
    val tocListState = rememberLazyListState()

    val entries = book.tocNodes
    val resolvedNode = remember(entries, currentAnchor, activeTocNodeId, book) {
        BookBoundaries.activeTocNode(
            entries,
            currentAnchor.importPage,
            currentAnchor.offsetUtf16,
            activeTocNodeId,
            book.page(currentAnchor.importPage)?.text,
        )
    }
    val activeIndex = if (resolvedNode != null) entries.indexOfFirst { it.id == resolvedNode.id } else -1
    LaunchedEffect(visible, activeTab, activeIndex) {
        if (visible && activeTab == ReaderLeftTab.TOC && activeIndex >= 0) {
            withFrameNanos { }
            if (tocListState.layoutInfo.totalItemsCount > 0) {
                tocListState.scrollToItem((activeIndex - 2).coerceAtLeast(0))
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInHorizontally { -it },
        exit = fadeOut() + slideOutHorizontally { -it },
        modifier = modifier.fillMaxHeight(),
    ) {
        Row(Modifier.width(drawerWidth).fillMaxHeight().testTag("immersive-toc-drawer")) {
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
                            color = if (selected) colors.actionPrimary else colors.fgSecondary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
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
                    ) { Text("收起", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) }
                }
                HorizontalDivider(
                    Modifier.padding(top = 6.dp),
                    thickness = 1.dp,
                    color = colors.borderSubtle,
                )
                when (activeTab) {
                    ReaderLeftTab.TOC -> {
                        TocStructureSummary(book, bodyStart, bodyStartCorrected, busy, onSaveBodyStart, currentChapterId, currentAnchor, onEditChapter)
                        if (entries.isEmpty()) {
                            Column(
                                Modifier.weight(1f).fillMaxWidth().padding(20.dp).testTag("v7-directory-drawer"),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("暂无识别出的目录章节", style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
                                Spacer(Modifier.height(6.dp))
                                Text("可直接翻页阅读，或点击下方重新识别目录。", style = MaterialTheme.typography.bodySmall, color = colors.fgTertiary)
                            }
                        } else {
                            LazyColumn(
                                state = tocListState,
                                modifier = Modifier.weight(1f).fillMaxWidth().testTag("v7-directory-drawer"),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                itemsIndexed(entries, key = { _, item -> item.id }) { idx, node ->
                                    val active = idx == activeIndex || node.id == currentChapterId
                                    val indent = ((node.level - 1).coerceIn(0, 3) * 10).dp
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clip(rowShape)
                                            .background(if (active) activeBg else Color.Transparent)
                                            .clickable(enabled = !busy) { onJumpToc(node) }
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .padding(start = 10.dp + indent, end = 10.dp, top = 8.dp, bottom = 8.dp)
                                            .testTag("toc-item-$idx"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (node.kind == TocNodeKind.CHAPTER) {
                                            val read = node.id in readChapterIds
                                            val dot = Modifier.size(6.dp)
                                            Spacer((if (read) dot.background(v7Pine(), CircleShape)
                                                else dot.border(1.dp, colors.fgTertiary, CircleShape)).semantics {
                                                stateDescription = if (read) "已读" else "未读"
                                            }.testTag("toc-read-dot-$idx"))
                                        }
                                        Text(
                                            node.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (active) colors.actionPrimary else colors.fgPrimary,
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            node.startPage.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = LocalV7Fonts.current.numbers,
                                            color = if (active) colors.actionPrimary else colors.fgTertiary,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
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
                                ) { Text("重新识别目录", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) }
                            }
                        }
                    }
                    ReaderLeftTab.BOOKMARKS -> {
                        val sorted = remember(bookmarks) { bookmarks.sortedBy { it.page } }
                        if (sorted.isEmpty()) {
                            Column(
                                Modifier.weight(1f).fillMaxWidth().padding(20.dp).testTag("bookmarks-empty"),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("还没有书签", style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
                                Spacer(Modifier.height(6.dp))
                                Text("按 Ctrl+D 可收藏当前页。", style = MaterialTheme.typography.bodySmall, color = colors.fgTertiary)
                            }
                        } else {
                            LazyColumn(
                                Modifier.weight(1f).fillMaxWidth().testTag("bookmarks-list"),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                itemsIndexed(sorted, key = { _, bm -> bm.page }) { _, bookmark ->
                                    val active = bookmark.page == currentPage
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(rowShape)
                                            .background(if (active) activeBg else Color.Transparent)
                                            .clickable(enabled = !busy) { onJumpPage(bookmark.page) }
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .padding(horizontal = 10.dp, vertical = 8.dp)
                                            .testTag("bookmark-item-${bookmark.page}"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                book.pageLabel(bookmark.page),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = LocalV7Fonts.current.numbers,
                                                color = if (active) colors.actionPrimary else colors.fgPrimary,
                                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                            if (bookmark.note.isNotBlank()) {
                                                Text(
                                                    bookmark.note,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = colors.fgTertiary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        Text(
                                            "移除",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.fgTertiary,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier
                                                .clickable { onToggleBookmark(bookmark.page) }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                .testTag("bookmark-remove-${bookmark.page}"),
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
 * 统一伴读抽屉（讲解 / 提问 / 批注合一）：
 * - 宽屏（>=1280dp）：默认并排停靠（Split-Pane），正文零像素被遮挡，可一键切浮层；
 * - 窄桌面（1000..1279dp）：右侧浮层；
 * - 小窗与手机（<1000dp，含 winbox 752×552）：底部半屏抽屉，正文始终可见不归零。
 */
@Composable
internal fun ImmersiveCompanionDrawer(
    visible: Boolean,
    docked: Boolean,
    canDock: Boolean,
    bottomSheet: Boolean = false,
    drawerWidth: Dp = 380.dp,
    bottomHeight: Dp = 280.dp,
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
    val activeBg = colors.actionPrimary.copy(alpha = 0.12f)
    val tabShape = RoundedCornerShape(CoachV7.Radius.s)

    val headerAndBody: @Composable (Modifier) -> Unit = { innerModifier ->
        Column(innerModifier.background(colors.surface1)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(if (bottomSheet) 38.dp else 44.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (tab in ReaderCompanionTab.entries) {
                    val selected = activeTab == tab
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) colors.actionPrimary else colors.fgSecondary,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(tabShape)
                            .background(if (selected) activeBg else Color.Transparent)
                            .clickable(role = Role.Tab) { onSelectTab(tab) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag(tab.tag),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (canDock && !bottomSheet) {
                    V7TextButton(
                        onClick = onToggleDock,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("companion-dock-toggle"),
                        ringInside = true,
                    ) {
                        Text(if (docked) "浮层" else "并排", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
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

    if (bottomSheet) {
        if (visible) {
            Column(modifier.fillMaxWidth().height(bottomHeight).testTag("immersive-companion-drawer")) {
                HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle)
                headerAndBody(Modifier.weight(1f).fillMaxWidth())
            }
        }
    } else if (docked) {
        if (visible) {
            Row(modifier.width(drawerWidth).fillMaxHeight().testTag("immersive-companion-drawer")) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.borderSubtle))
                headerAndBody(Modifier.weight(1f).fillMaxHeight())
            }
        }
    } else {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInHorizontally { it },
            exit = fadeOut() + slideOutHorizontally { it },
            modifier = modifier.fillMaxHeight(),
        ) {
            Row(Modifier.width(drawerWidth).fillMaxHeight().testTag("immersive-companion-drawer")) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.borderSubtle))
                headerAndBody(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/**
 * 选段后浮出的紧凑操作条（高亮 / 讲讲 / 举个例子 / 问… / 笔记），直接把结果路由到伴读抽屉。
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
    onCompare: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.m)
    Row(
        modifier
            .paperShadow("button", colors.dark)
            .clip(shape)
            .background(colors.surface1)
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
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(if (highlighted) "取消高亮" else "高亮", style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary, maxLines = 1, softWrap = false)
            }
        }
        for (action in listOf(SelectionExplainAction.EXPLAIN, SelectionExplainAction.SPLIT, SelectionExplainAction.EXAMPLE, SelectionExplainAction.ASK)) {
            V7TextButton(
                onClick = { onAction(action) },
                modifier = Modifier.testTag(action.tag),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(action.label, style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary, maxLines = 1, softWrap = false)
            }
        }
        if (onCompare != null) {
            V7TextButton(
                onClick = onCompare,
                modifier = Modifier.testTag("selection-action-other-books"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("其他书", style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary, maxLines = 1, softWrap = false)
            }
        }
        V7TextButton(
            onClick = onNote,
            modifier = Modifier.testTag("selection-action-note"),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text("笔记", style = MaterialTheme.typography.labelLarge, color = colors.fgPrimary, maxLines = 1, softWrap = false)
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
 * 伴读抽屉内的「提问」面板：无嵌套卡片，扁平对话流 + 始终固定在底部的输入框（任何窗高零滚屏即见输入框与发送键）。
 * 处于课程或书单阅读时，吸底提问框上方支持轻量切换「本节 | 整门课（已读书目）」。
 */
@Composable
internal fun CompanionAskPane(
    book: Textbook,
    allBlocks: List<SourceBlock>,
    pageBlocks: List<SourceBlock>,
    activeSelection: SourceBlock?,
    currentSection: BookLesson?,
    thread: InlineExplainThread?,
    draft: String,
    compact: Boolean = false,
    onDraftChange: (String) -> Unit,
    onPickPassage: (SourceBlock) -> Unit,
    onSubmitQuestion: () -> Unit,
    onRetry: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenCitation: (Citation, SourceBlock) -> Unit,
    onClearSelection: () -> Unit,
    onInputFocusChanged: (Boolean) -> Unit = {},
    collectionScopeLabel: String? = null,
    askScope: CollectionAskScope = CollectionAskScope.SECTION,
    onSelectAskScope: (CollectionAskScope) -> Unit = {},
    collectionTurns: List<CollectionQaTurn> = emptyList(),
    collectionLoading: Boolean = false,
    collectionError: Pair<String, ExplainTrouble>? = null,
    collectionFallbackPages: List<CollectionQaCitation> = emptyList(),
    onRetryCollection: () -> Unit = {},
    onOpenCollectionCitation: (CollectionQaCitation) -> Unit = {},
) {
    val colors = CoachTheme.colors
    val offline = LocalCoachConnectivity.current == CoachConnectivityStatus.OFFLINE
    var pickingParagraph by remember(book.id, pageBlocks) { mutableStateOf(false) }
    val quoteSource = thread?.selection ?: activeSelection
    val isCollectionMode = collectionScopeLabel != null && askScope == CollectionAskScope.COLLECTION
    val isLoadingNow = if (isCollectionMode) collectionLoading else (thread?.loading == true)
    val canSendNow = draft.isNotBlank() && (isCollectionMode || quoteSource != null || pageBlocks.isNotEmpty())
    val askFocus = remember { FocusRequester() }
    LaunchedEffect(askScope, quoteSource?.id) {
        withFrameNanos { }
        runCatching { askFocus.requestFocus() }
    }
    DisposableEffect(Unit) {
        onDispose { onInputFocusChanged(false) }
    }

    Column(
        Modifier.fillMaxSize().padding(if (compact) 10.dp else 14.dp).testTag("companion-ask-pane"),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
    ) {
        if (isCollectionMode) {
            Text(
                if (collectionScopeLabel == "已读书目") "只在已读章节里跨书查依据，不剧透未读内容。"
                else "在整门课全部材料中查原页依据并核对分歧。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (quoteSource != null) {
            val sources = remember(thread, quoteSource) { ExplainWire.selectionBlocks(quoteSource, thread?.context) }
            val chapter = remember(book, quoteSource.page) { BookChapterResolver.chapterForPage(book, quoteSource.page) }
            Text(explanationScope(sources.minOf { it.page }, sources.maxOf { it.page }, chapter?.title ?: book.title),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("inline-explain-scope"))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.actionPrimary.copy(alpha = 0.12f), RoundedCornerShape(CoachV7.Radius.s))
                    .padding(horizontal = 10.dp, vertical = if (compact) 5.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "引文 · ${shortQuote(quoteSource.text)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgPrimary,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("companion-ask-quote"),
                )
                Text(
                    "清除",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.fgTertiary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.clickable { onClearSelection() },
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    pageBlocks.firstOrNull()?.let { "引文 · ${shortQuote(it.text)}" }
                        ?: currentSection?.let { "围绕「${it.title}」提问，或先选中一段原文。" }
                        ?: "选中一段原文，或直接就当前页提问。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (pageBlocks.isNotEmpty()) {
                    Text(
                        if (pickingParagraph) "收起段落" else "选本页段落",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.actionPrimary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clickable { pickingParagraph = !pickingParagraph }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .testTag("companion-pick-paragraph"),
                    )
                }
            }
        }

        val listScroll = rememberScrollState()
        val scrollTrigger = if (isCollectionMode) {
            Triple(collectionTurns.size, collectionLoading, collectionError != null)
        } else {
            Triple(thread?.turns?.size ?: 0, thread?.loading == true, thread?.error != null)
        }
        LaunchedEffect(isCollectionMode, scrollTrigger) {
            withFrameNanos { }
            withFrameNanos { }
            if (listScroll.maxValue > 0) listScroll.scrollTo(listScroll.maxValue)
        }

        Box(Modifier.weight(1f).fillMaxWidth().testTag("companion-answer-history")) {
            Column(
                Modifier.fillMaxSize().verticalScroll(listScroll).padding(end = if (listScroll.maxValue > 0) 14.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isCollectionMode) {
                    for ((index, turn) in collectionTurns.withIndex()) {
                        CollectionQaTurnView(
                            turn = turn,
                            turnIndex = index,
                            tagPrefix = "collection-qa",
                            onOpenCitation = onOpenCollectionCitation,
                        )
                        HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.6f))
                    }
                    if (collectionLoading && !offline) {
                        Text(
                            "正在跨材料核对原页…",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.fgSecondary,
                            modifier = Modifier.testTag("collection-qa-loading"),
                        )
                    }
                    collectionError?.let { (message, trouble) ->
                        RetrievedPageLinks(collectionFallbackPages, { "${it.shortLabel} · 第 ${it.page} 页" }, onOpenCollectionCitation)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (trouble == ExplainTrouble.Revoked) v7Alert() else colors.fgSecondary,
                                modifier = Modifier.weight(1f).testTag("collection-qa-error"),
                            )
                            if (trouble.needsPairing) {
                                TextButton(onClick = onOpenLogin) { Text("重新登录", maxLines = 1, softWrap = false) }
                            } else {
                                TextButton(
                                    onClick = onRetryCollection,
                                    modifier = Modifier.testTag("collection-qa-retry"),
                                ) { Text("重试", maxLines = 1, softWrap = false) }
                            }
                        }
                    }
                } else {
                    if (pickingParagraph && quoteSource == null) {
                        pageBlocks.forEachIndexed { idx, block ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(CoachV7.Radius.s))
                                    .clickable {
                                        pickingParagraph = false
                                        onPickPassage(block)
                                    }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .testTag("ask-passage-block-$idx"),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "${idx + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = LocalV7Fonts.current.numbers,
                                    color = colors.actionPrimary,
                                )
                                Text(
                                    shortQuote(block.text),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.fgPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.5f))
                        }
                    }
                    if (thread != null) {
                        for ((index, entry) in thread.turns.withIndex()) {
                            Column(Modifier.fillMaxWidth().testTag("inline-explain-turn-$index"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.label, style = MaterialTheme.typography.labelMedium, color = colors.actionPrimary)
                                if (entry.explanation.citations.isEmpty()) {
                                    Text(ReadableText.withoutCitationMarks(entry.explanation.text).ifBlank { "尚未收到回答" })
                                    if (entry.explanation.text.isNotBlank()) Text("回答中断", style = MaterialTheme.typography.labelSmall)
                                } else ExplanationView(book, allBlocks, entry.explanation, entry.sentBlocks, showReaderPage = true) { citation ->
                                    entry.sentBlocks.firstOrNull { it.id == citation.blockId }?.let { onOpenCitation(citation, it) }
                                }
                            }
                            HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.6f))
                        }
                        if (thread.loading && (!offline || thread.pendingText.isNotBlank())) {
                            Column(Modifier.fillMaxWidth().testTag("inline-explain-pending"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                thread.pendingLabel?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.labelMedium, color = colors.actionPrimary)
                                }
                                Text(thread.pendingText.takeIf { it.isNotBlank() }?.let(ReadableText::withoutCitationMarks) ?: "正在整理…", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary, modifier = Modifier.testTag("inline-explain-loading"))
                            }
                        }
                        thread.error?.let { (message, trouble) ->
                            RetrievedPageLinks(thread.fallbackBlocks, { block ->
                                if (block.page == thread.selection.page) "选段在第 ${block.page} 页" else "相关段落在第 ${block.page} 页"
                            }) { block ->
                                onOpenCitation(Citation("", block.id, block.page), block)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (trouble == ExplainTrouble.Revoked) v7Alert() else colors.fgSecondary,
                                    modifier = Modifier.weight(1f).testTag("inline-explain-error"),
                                )
                                if (trouble.needsPairing) TextButton(onClick = onOpenLogin) { Text("重新登录", maxLines = 1, softWrap = false) }
                                else TextButton(onClick = onRetry, modifier = Modifier.testTag("inline-explain-retry")) { Text("重试", maxLines = 1, softWrap = false) }
                            }
                        }
                    }
                }
            }
            if (listScroll.maxValue > 0) {
                BookScrollbar(
                    listScroll,
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp).testTag("companion-ask-scrollbar"),
                )
            }
        }

        if (collectionScopeLabel != null) {
            Row(
                Modifier.fillMaxWidth().testTag("ask-scope-bar"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScopePillButton(
                    label = "本节",
                    selected = askScope == CollectionAskScope.SECTION,
                    modifier = Modifier.testTag("ask-scope-section"),
                    onClick = { onSelectAskScope(CollectionAskScope.SECTION) },
                )
                ScopePillButton(
                    label = collectionScopeLabel,
                    selected = askScope == CollectionAskScope.COLLECTION,
                    modifier = Modifier.testTag("ask-scope-collection"),
                    onClick = { onSelectAskScope(CollectionAskScope.COLLECTION) },
                )
            }
        }

        if (draft.isBlank()) {
            Text("先输入问题", style = MaterialTheme.typography.labelSmall, color = colors.fgSecondary)
        } else if (isCollectionMode && draft.trim().length > 600) {
            Text("问题太长，请缩短后发送", style = MaterialTheme.typography.labelSmall, color = colors.fgSecondary)
        } else if (!isCollectionMode && draft.trim().length > BookPassageQuestion.MAX_QUESTION_LENGTH) {
            Text("问题太长，请缩短后发送", style = MaterialTheme.typography.labelSmall, color = colors.fgSecondary)
        }
        if (offline) {
            OfflineNeedsNetworkNotice(
                message = if (isCollectionMode) OfflineCopy.COLLECTION_ASK_OFFLINE else OfflineCopy.ASK_OFFLINE,
                tag = "companion-ask-offline-notice",
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { runCatching { askFocus.requestFocus() } },
            ) {
                V7Field(
                    value = draft,
                    onValueChange = onDraftChange,
                    label = when {
                        isCollectionMode -> "向$collectionScopeLabel 提问（核对原页与分歧）…"
                        quoteSource != null -> "问这句的意思、公式推导或例子…"
                        else -> "输入问题…"
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(askFocus)
                        .onFocusChanged { onInputFocusChanged(it.hasFocus) }
                        .testTag("inline-followup-input")
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed &&
                                canSendNow && !isLoadingNow) {
                                onSubmitQuestion(); true
                            } else false
                        },
                )
            }
            PrimaryButton(
                onClick = {
                    if (!isLoadingNow) {
                        onSubmitQuestion()
                        runCatching { askFocus.requestFocus() }
                    }
                },
                enabled = canSendNow,
                modifier = Modifier.testTag("inline-followup-send"),
            ) { Text("发送", maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
private fun ScopePillButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.s)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) colors.actionPrimary.copy(alpha = 0.14f) else colors.surface2)
            .border(1.dp, if (selected) colors.actionPrimary else colors.borderSubtle, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.actionPrimary else colors.fgSecondary,
            fontWeight = if (selected) CoachV7.Weight.uiStrong else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
internal fun CollectionQaTurnView(
    turn: CollectionQaTurn,
    turnIndex: Int,
    tagPrefix: String,
    onOpenCitation: (CollectionQaCitation) -> Unit,
) {
    val colors = CoachTheme.colors
    val pine = v7Pine()
    val pillShape = RoundedCornerShape(CoachV7.Radius.s)
    Column(
        Modifier.fillMaxWidth().testTag("$tagPrefix-turn-$turnIndex"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "问：${turn.question}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.actionPrimary,
        )
        if (turn.sentences.isNotEmpty()) {
            turn.sentences.forEachIndexed { sIdx, sentence ->
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        sentence.claim,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.fgPrimary,
                    )
                    Box(
                        Modifier
                            .clip(pillShape)
                            .background(pine.copy(alpha = 0.10f))
                            .border(1.dp, pine.copy(alpha = 0.35f), pillShape)
                            .clickable(role = Role.Button) { onOpenCitation(sentence.citation) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("$tagPrefix-citation-$turnIndex-$sIdx"),
                    ) {
                        Text(
                            "[${sentence.citation.capsuleText()}]",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = LocalV7Fonts.current.numbers,
                            color = pine,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        } else if (!turn.fallbackMessage.isNullOrBlank()) {
            Text(
                turn.fallbackMessage,
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
                modifier = Modifier.testTag("$tagPrefix-fallback-$turnIndex"),
            )
        }

        if (turn.hasMaterialConflict && (turn.conflictSummaryZh.isNotBlank() || turn.conflictCards.isNotEmpty())) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(pillShape)
                    .background(colors.surface1)
                    .border(1.dp, colors.borderSubtle, pillShape)
                    .padding(10.dp)
                    .testTag("$tagPrefix-conflict-card-$turnIndex"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "各材料说法不一",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.accentDeep,
                    fontWeight = CoachV7.Weight.uiStrong,
                )
                if (turn.conflictSummaryZh.isNotBlank()) {
                    Text(
                        turn.conflictSummaryZh,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.fgPrimary,
                    )
                }
                turn.conflictCards.forEachIndexed { cIdx, card ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (card.point.isNotBlank()) {
                            Text(card.point, style = MaterialTheme.typography.labelMedium, color = colors.fgPrimary)
                        }
                        if (card.authoritativeView.isNotBlank()) {
                            Text("主口径：${card.authoritativeView}", style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
                        }
                        if (card.secondaryView.isNotBlank()) {
                            Text("另一材料：${card.secondaryView}", style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                        }
                        if (card.advice.isNotBlank()) {
                            Text("备考建议：${card.advice}", style = MaterialTheme.typography.bodySmall, color = pine)
                        }
                        if (card.citations.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                card.citations.forEachIndexed { rIdx, cit ->
                                    Box(
                                        Modifier
                                            .clip(pillShape)
                                            .background(colors.surface2)
                                            .border(1.dp, colors.borderSubtle, pillShape)
                                            .clickable(role = Role.Button) { onOpenCitation(cit) }
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .padding(horizontal = 7.dp, vertical = 3.dp)
                                            .testTag("$tagPrefix-conflict-jump-$turnIndex-$cIdx-$rIdx"),
                                    ) {
                                        Text(
                                            "[${cit.capsuleText()}]",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = LocalV7Fonts.current.numbers,
                                            color = pine,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
