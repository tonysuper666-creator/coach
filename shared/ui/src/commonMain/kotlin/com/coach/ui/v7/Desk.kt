package com.coach.ui.v7

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import coach.design.tokens.V7Surfaces
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import coach.design.tokens.CoachV7
import coach.design.tokens.V7Windows
import com.coach.ui.theme.CoachTheme
import com.coach.ui.AppTab
import com.coach.ui.LocalV7TextScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import kotlinx.coroutines.launch

val LocalReadingFocus = staticCompositionLocalOf { false }
val LocalFootnotePrefix = staticCompositionLocalOf { "" }
val LocalV7ReducedMotion = staticCompositionLocalOf { false }
val LocalDeskWindow = staticCompositionLocalOf<com.coach.ui.adaptive.WindowSpec?> { null }
val LocalDeskSelect = staticCompositionLocalOf<(AppTab) -> Unit> { {} }
val LocalDeskNavigation = staticCompositionLocalOf<(@Composable (Boolean) -> Unit)?> { null }

enum class DeskWindow { Inner, Medium, OuterLandscape, Outer }
/** Columns depend only on the available width, including split-screen windows. */
fun deskWindow(width: Float, height: Float): DeskWindow = when {
    width >= 1100f -> DeskWindow.Inner
    width >= 700f -> DeskWindow.Medium
    width > height -> DeskWindow.OuterLandscape
    else -> DeskWindow.Outer
}

@Immutable
data class DeskMetrics(val window: DeskWindow, val horizontalPadding: Dp, val topPadding: Dp, val optionHeight: Dp,
    val statusInBar: Boolean = false) {
    val inner get() = window == DeskWindow.Inner || window == DeskWindow.Medium
}
val LocalDeskMetrics = staticCompositionLocalOf { DeskMetrics(DeskWindow.Inner, CoachV7.Layout.mainPx, CoachV7.Layout.mainPt, CoachV7.Layout.optH) }

/** The page stays in the same composition when the directory/margin collapse or focus mode changes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Desk(
    crumb: String,
    modifier: Modifier = Modifier,
    focus: Boolean = false,
    onFocus: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "‹",
    status: String = "",
    compactStatus: String = "",
    compactTypeControl: Boolean = true,
    compactFooter: Boolean = false,
    showNavigation: Boolean = true,
    onCrumbClick: (() -> Unit)? = null,
    directory: @Composable () -> Unit = {},
    margin: @Composable () -> Unit = {},
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val managed = com.coach.ui.LocalAppManagedNavigation.current
    var localFocus by remember { mutableStateOf(false) }
    val focused = focus || (onFocus == null && localFocus)
    val colors = CoachTheme.colors
    val reduced = LocalV7ReducedMotion.current
    val progress = remember { Animatable(0f) }
    var previousFocus by remember { mutableStateOf(focused) }
    LaunchedEffect(crumb, focused, reduced) {
        val focusChanged = previousFocus != focused
        previousFocus = focused
        progress.snapTo(0f)
        progress.animateTo(1f, tween(if (reduced) 0 else if (focusChanged) 180 else CoachV7.Motion.pageMs,
            easing = if (focusChanged || focused) LinearEasing else CoachV7.Motion.easing))
    }
    val contentNow by rememberUpdatedState(content)
    val page = remember { movableContentOf { contentNow() } }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    com.coach.ui.AppBackHandler(drawerState.isOpen, priority = 400) { scope.launch { drawerState.close() } }
    com.coach.ui.TaskChrome(focused || !showNavigation)
    com.coach.ui.AppBackHandler(focused && onBack == null, priority = 100) { if (onFocus != null) onFocus() else localFocus = false }
    var marginCollapsed by rememberSaveable { mutableStateOf(false) }
    var marginSheet by remember { mutableStateOf(false) }
    com.coach.ui.AppBackHandler(marginSheet, priority = 300) { marginSheet = false }
    ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(300.dp)) {
                TextButton(onClick = { scope.launch { drawerState.close() } }) { Text("收起目录") }
                if (drawerState.isOpen) Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()).testTag("v7-directory-drawer")) { directory() }
            }
        }) {
    BoxWithConstraints(modifier.fillMaxSize().background(if (focused) colors.surface1 else colors.bgCanvas).testTag("v7-desk")) {
        val logicalWindow = LocalDeskWindow.current
        val window = if (managed && logicalWindow != null && com.coach.ui.adaptive.LayoutRules.navigation(logicalWindow).nav == com.coach.ui.adaptive.NavStyle.BottomBar) DeskWindow.Outer
            else deskWindow(maxWidth.value, logicalWindow?.heightDp ?: maxHeight.value)
        val windowWidth = maxWidth
        val sheetHeight = maxHeight * .7f
        val inner = window == DeskWindow.Inner || window == DeskWindow.Medium
        val threeColumns = window == DeskWindow.Inner && !managed
        val landscape = window == DeskWindow.OuterLandscape
        val metrics = DeskMetrics(window,
            if (inner) 24.dp else 16.dp,
            if (landscape) 12.dp else 20.dp,
            48.dp, statusInBar = threeColumns)
        CompositionLocalProvider(LocalDeskMetrics provides metrics, LocalReadingFocus provides focused) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(if (inner) 48.dp else 72.dp).padding(horizontal = 16.dp)) {
                    Row(Modifier.align(if (inner) Alignment.CenterStart else Alignment.TopStart).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        val home = LocalDeskSelect.current
                        if (managed) Text("Coach", Modifier.clickable { home(AppTab.TODAY) }, style = MaterialTheme.typography.labelLarge, fontWeight = CoachV7.Weight.uiStrong)
                        else Box {
                            var menu by remember { mutableStateOf(false) }
                            val select = LocalDeskSelect.current
                            Text("Coach", Modifier.clickable { menu = true }, style = MaterialTheme.typography.labelLarge, fontWeight = CoachV7.Weight.uiStrong)
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                AppTab.entries.forEach { tab -> DropdownMenuItem(text = { Text(tab.label) }, onClick = { menu = false; select(tab) }) }
                            }
                        }
                        if (!threeColumns) {
                            TextButton(onClick = { scope.launch { drawerState.open() } }, modifier = Modifier.testTag("v7-directory-toggle"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("目录") }
                        }
                        if (onBack != null) {
                            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(backLabel, style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                    Text(buildAnnotatedString {
                        append(crumb)
                        val start = crumb.lastIndexOf('·').takeIf { it >= 0 }?.plus(1) ?: 0
                        addStyle(SpanStyle(fontWeight = CoachV7.Weight.uiStrong, color = colors.fgPrimary), start, crumb.length)
                    }, Modifier.align(if (inner) Alignment.Center else Alignment.BottomStart).padding(bottom = if (inner) 0.dp else 4.dp).widthIn(max = if (inner) windowWidth * .30f else windowWidth - 32.dp)
                        .then(if (onCrumbClick != null) Modifier.clickable(onClick = onCrumbClick).testTag("v7-week-picker") else Modifier), style = MaterialTheme.typography.labelLarge,
                        color = colors.fgSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Row(Modifier.align(if (inner) Alignment.CenterEnd else Alignment.TopEnd).height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val barStatus = if (inner) status else compactStatus
                        if (metrics.statusInBar && barStatus.isNotBlank()) Text(barStatus, style = MaterialTheme.typography.labelLarge, color = colors.fgSecondary)
                        var typeMenu by remember { mutableStateOf(false) }
                        Box {
                            val textScale = LocalV7TextScale.current
                            Text("Aa", Modifier.clickable { typeMenu = true }.testTag("v7-type-menu"), style = MaterialTheme.typography.labelLarge, color = colors.fgSecondary)
                            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                                listOf("小" to .9f, "中" to 1f, "大" to 1.15f).forEach { (label, scale) ->
                                    DropdownMenuItem(text = { Text(label + if (textScale.value == scale) " ✓" else "") },
                                        modifier = Modifier.testTag("v7-type-$label"), onClick = { textScale.value = scale; typeMenu = false })
                                }
                            }
                        }
                        if (!focused) Text(if (inner && !marginCollapsed) "收起" else "出处与批注",
                            Modifier.clickable { if (inner) marginCollapsed = !marginCollapsed else marginSheet = true }.testTag("v7-margin-toggle"), style = MaterialTheme.typography.labelLarge, color = colors.fgSecondary)
                        Text(if (focused) "退出专心读" else "专心读",
                            Modifier.clickable { if (onFocus != null) onFocus() else localFocus = !localFocus }.testTag("v7-focus"), style = MaterialTheme.typography.labelLarge, color = colors.fgSecondary)
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth().padding(top = if (inner && !focused) 8.dp else 0.dp, bottom = if (inner && !focused) 12.dp else 0.dp)) {
                    // Keep all three containers in place so a fold never recreates the reading composition.
                    val tocWidth = if (threeColumns && !focused) 160.dp else 0.dp
                    val marginWidth = if (inner && !focused && !marginCollapsed) (windowWidth * .30f).coerceIn(260.dp, 280.dp) else 0.dp
                    Box(Modifier.width(tocWidth).fillMaxHeight()) {
                        if (tocWidth > 0.dp) Column(Modifier.padding(start = 8.dp, end = 0.dp, top = 24.dp).verticalScroll(rememberScrollState()).testTag("v7-directory")) {
                            directory()
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().padding(top = if (inner && !focused) 4.dp else 0.dp)
                        .then(if (inner && !focused) Modifier.paperShadow("sheet", colors.dark) else Modifier)
                        .clip(if (focused) RoundedCornerShape(0.dp) else if (!inner) RoundedCornerShape(bottomStart = CoachV7.Radius.sheet, bottomEnd = CoachV7.Radius.sheet) else RoundedCornerShape(CoachV7.Radius.sheet))
                        .background(colors.surface1)
                        .graphicsLayer { alpha = progress.value; translationY = if (focused || reduced) 0f else 8.dp.toPx() * (1 - progress.value) }
                        .testTag("v7-paper"), contentAlignment = Alignment.TopCenter) { page() }
                    Box(Modifier.width(marginWidth).fillMaxHeight()) {
                        if (marginWidth > 0.dp) Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 16.dp).testTag("v7-margin")) { margin() }
                    }
                }
                if (footer != null && (inner || compactFooter)) Row(Modifier.fillMaxWidth().background(colors.bgCanvas).height(40.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, content = footer)
                if (!managed && !inner && !focused && showNavigation) LocalDeskNavigation.current?.invoke(false)
            }
        }
        if (marginSheet && !inner && !focused) ModalBottomSheet(onDismissRequest = { marginSheet = false }) {
            Box(Modifier.fillMaxWidth().height(sheetHeight).padding(16.dp).testTag("v7-margin-sheet")) { margin() }
        }
    }
    }
}


/** CSS shadows use blur radius and offset, rather than Material elevation. */
fun Modifier.paperShadow(kind: String, dark: Boolean): Modifier {
    val shape = RoundedCornerShape(if (kind == "sheet") 14.dp else if (kind == "button") 8.dp else 10.dp)
    return V7Surfaces.shadows(kind, dark).fold(this) { modifier, css ->
        val shadow = Shadow(radius = css.blur / 2, spread = css.spread, color = css.color, offset = DpOffset(css.x, css.y))
        if (css.inset) modifier.innerShadow(shape, shadow) else modifier.dropShadow(shape, shadow)
    }
}

@Composable
fun PageHeader(kicker: String, title: String, deck: String = "", meta: String = "", week: Int? = null) {
    val m = LocalDeskMetrics.current
    val colors = CoachTheme.colors
    Column {
        Text(kicker, style = MaterialTheme.typography.labelMedium.copy(lineHeight = 16.8.sp), color = colors.fgSecondary)
        Spacer(Modifier.height(if (week == null) 12.dp else 8.dp))
        val titleStyle = if (week != null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium
        if (week != null) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val size = 48.sp * LocalV7TextScale.current.value
            Text(week.toString().padStart(2, '0'), fontFamily = LocalV7Fonts.current.display,
                fontWeight = FontWeight.Light, fontSize = size, lineHeight = size * .8f, letterSpacing = (-.03).em,
                style = TextStyle(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both, LineHeightStyle.Mode.Tight)),
                modifier = Modifier.wrapContentHeight().testTag("v7-week-number"))
            Column {
                Text(title, style = titleStyle)
                if (deck.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(deck, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp), color = colors.fgSecondary) }
            }
        } else {
            Text(title, style = titleStyle)
            if (deck.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(deck, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp), color = colors.fgSecondary) }
        }
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(annotatedMarks(meta), style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun PaperCard(modifier: Modifier = Modifier, color: Color = CoachTheme.colors.surface2,
    contentSpacing: Dp = 8.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit) {
    val dark = CoachTheme.colors.dark
    Column(modifier.fillMaxWidth().paperShadow("card", dark).clip(RoundedCornerShape(10.dp)).background(color).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(contentSpacing), content = content)
}

@Composable
fun AnswerOption(letter: String, selected: Boolean, correct: Boolean?, enabled: Boolean, modifier: Modifier = Modifier,
    onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = CoachTheme.colors
    val bg = when (correct) { true -> colors.successBg; false -> colors.dangerBg; null -> if (colors.dark) CoachV7.Dark.optBg else CoachV7.Light.optBg }
    val verdictColor = if (correct == true) colors.successFg else colors.dangerFg
    Surface(onClick = onClick, enabled = enabled, color = bg, contentColor = colors.fgPrimary, shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth().paperShadow("option", colors.dark).heightIn(min = LocalDeskMetrics.current.optionHeight), selected = selected) {
        Row(Modifier.padding(horizontal = if (LocalDeskMetrics.current.inner) 16.dp else 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(letter, Modifier.width(16.2.dp), fontFamily = LocalV7Fonts.current.numbers, style = MaterialTheme.typography.labelLarge, color = if (correct != null) verdictColor else colors.fgSecondary)
            Box(Modifier.weight(1f)) {
                MaterialTheme(typography = MaterialTheme.typography.copy(bodyLarge = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp * LocalV7TextScale.current.value))) { content() }
            }
            if (correct != null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                VerdictIcon(correct, Modifier.size(14.dp), verdictColor)
                Text(if (correct) "正确" else "你选的", style = MaterialTheme.typography.labelMedium, color = verdictColor)
            }
            else if (selected) Text("●", style = MaterialTheme.typography.labelMedium, color = colors.fgPrimary)
        }
    }
}

/** Mark emphasis using the imported ink tokens, including in mixed source/status lines. */
@Composable internal fun annotatedMarks(text: String): AnnotatedString {
    val c = CoachTheme.colors
    return buildAnnotatedString {
        append(text)
        text.forEachIndexed { i, ch -> if (ch == '★') addStyle(SpanStyle(color = c.dangerFg), i, i + 1) }
        val source = text.indexOf("原页")
        if (source >= 0) addStyle(SpanStyle(color = c.successFg, fontSize = CoachV7.Size.cap), source, source + 2)
    }
}
