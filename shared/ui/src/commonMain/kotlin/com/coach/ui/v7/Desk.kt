package com.coach.ui.v7

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

val LocalReadingFocus = staticCompositionLocalOf { false }
val LocalFootnotePrefix = staticCompositionLocalOf { "" }
val LocalV7ReducedMotion = staticCompositionLocalOf { false }
val LocalDeskWindow = staticCompositionLocalOf<com.coach.ui.adaptive.WindowSpec?> { null }
val LocalDeskSelect = staticCompositionLocalOf<(AppTab) -> Unit> { {} }
val LocalDeskNavigation = staticCompositionLocalOf<(@Composable (Boolean) -> Unit)?> { null }

enum class DeskWindow { Inner, OuterLandscape, Outer }
/** Window geometry, not device names: the cover landscape must never become three columns. */
fun deskWindow(width: Float, height: Float): DeskWindow = when {
    (width >= 1100f && height >= 700f) || (width >= 840f && height >= 600f && width / height <= 1.52f) -> DeskWindow.Inner
    width > height -> DeskWindow.OuterLandscape
    else -> DeskWindow.Outer
}

@Immutable
data class DeskMetrics(val window: DeskWindow, val horizontalPadding: Dp, val topPadding: Dp, val optionHeight: Dp) {
    val inner get() = window == DeskWindow.Inner
}
val LocalDeskMetrics = staticCompositionLocalOf { DeskMetrics(DeskWindow.Inner, CoachV7.Layout.mainPx, CoachV7.Layout.mainPt, CoachV7.Layout.optH) }

/** The page stays in the same composition when the directory/margin collapse or focus mode changes. */
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
    directory: @Composable () -> Unit = {},
    margin: @Composable () -> Unit = {},
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
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
    BoxWithConstraints(modifier.fillMaxSize().background(if (focused) colors.surface1 else colors.bgCanvas).testTag("v7-desk")) {
        val logicalWindow = LocalDeskWindow.current
        val window = deskWindow(logicalWindow?.widthDp ?: maxWidth.value, logicalWindow?.heightDp ?: maxHeight.value)
        val windowWidth = maxWidth
        val inner = window == DeskWindow.Inner
        val landscape = window == DeskWindow.OuterLandscape
        val metrics = DeskMetrics(window,
            when { focused -> 24.dp; inner -> (CoachV7.Layout.mainPx * (windowWidth.value / 1224f)).coerceAtLeast(24.dp); landscape -> V7Windows.OuterL.mainPx; else -> V7Windows.Outer.mainPx },
            when { focused -> 72.dp; inner -> CoachV7.Layout.mainPt; landscape -> V7Windows.OuterL.mainPt; else -> V7Windows.Outer.mainPt },
            if (inner) CoachV7.Layout.optH else if (landscape) V7Windows.OuterL.optH else V7Windows.Outer.optH)
        CompositionLocalProvider(LocalDeskMetrics provides metrics, LocalReadingFocus provides focused) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(if (landscape) V7Windows.OuterL.barH else CoachV7.Layout.barH).padding(horizontal = if (inner) CoachV7.Layout.pad else 20.dp)) {
                    Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                        if (inner && backLabel != "← 复习") Box {
                            var menu by remember { mutableStateOf(false) }
                            val select = LocalDeskSelect.current
                            Text("Coach", Modifier.clickable { menu = true }, style = MaterialTheme.typography.labelLarge, fontWeight = CoachV7.Weight.uiStrong)
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                AppTab.entries.forEach { tab -> DropdownMenuItem(text = { Text(tab.label) }, onClick = { menu = false; select(tab) }) }
                            }
                        }
                        else if (onBack != null) Text(backLabel, Modifier.clickable(onClick = onBack), style = MaterialTheme.typography.labelLarge)
                        else Text("Coach", style = MaterialTheme.typography.labelLarge, fontWeight = CoachV7.Weight.uiStrong)
                    }
                    Text(buildAnnotatedString {
                        append(crumb)
                        val start = crumb.lastIndexOf('·').takeIf { it >= 0 }?.plus(1) ?: 0
                        addStyle(SpanStyle(fontWeight = CoachV7.Weight.uiStrong, color = colors.fgPrimary), start, crumb.length)
                    }, Modifier.align(Alignment.Center).widthIn(max = windowWidth * .44f), style = MaterialTheme.typography.labelLarge,
                        color = colors.fgSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        val barStatus = if (inner) status else compactStatus
                        if (barStatus.isNotBlank()) Text(barStatus, style = MaterialTheme.typography.labelLarge, color = colors.fgSecondary)
                        var typeMenu by remember { mutableStateOf(false) }
                        if (inner || compactTypeControl) Box {
                            Text("Aa", Modifier.clickable { typeMenu = true }.testTag("v7-type-menu"), style = MaterialTheme.typography.labelLarge, color = colors.fgSecondary)
                            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                                Text("字号跟随系统设置", Modifier.padding(16.dp), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        if (inner) Text(if (focused) "退出专心读" else "专心读",
                            Modifier.clickable { if (onFocus != null) onFocus() else localFocus = !localFocus }.testTag("v7-focus"), style = MaterialTheme.typography.labelLarge, color = colors.fgSecondary)
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth().padding(top = if (inner && !focused) 8.dp else 0.dp, bottom = if (inner && !focused) 12.dp else 0.dp)) {
                    // Keep all three containers in place so a fold never recreates the reading composition.
                    val tocWidth = if (inner && !focused) CoachV7.Layout.wToc * (windowWidth.value / 1224f).coerceAtMost(1f) else 0.dp
                    val marginWidth = if (inner && !focused) CoachV7.Layout.wRail * (windowWidth.value / 1224f).coerceAtMost(1f) else 0.dp
                    Box(Modifier.width(tocWidth).fillMaxHeight()) {
                        if (tocWidth > 0.dp) Column(Modifier.padding(start = 8.dp, end = 0.dp, top = 24.dp).verticalScroll(rememberScrollState()).testTag("v7-directory")) {
                            directory()
                        }
                    }
                    if (landscape && !focused) Box(Modifier.width(V7Windows.OuterL.navW).fillMaxHeight().padding(top = 20.dp)) { LocalDeskNavigation.current?.invoke(true) }
                    Box(Modifier.weight(1f).fillMaxHeight().padding(top = if (inner && !focused) 4.dp else 0.dp)
                        .then(if (inner && !focused) Modifier.paperShadow("sheet", colors.dark) else Modifier)
                        .clip(if (focused) RoundedCornerShape(0.dp) else if (!inner) RoundedCornerShape(bottomStart = CoachV7.Radius.sheet, bottomEnd = CoachV7.Radius.sheet) else RoundedCornerShape(CoachV7.Radius.sheet))
                        .background(colors.surface1)
                        .graphicsLayer { alpha = progress.value; translationY = if (focused || reduced) 0f else 8.dp.toPx() * (1 - progress.value) }
                        .testTag("v7-paper"), contentAlignment = Alignment.TopCenter) { page() }
                    Box(Modifier.width(marginWidth).fillMaxHeight()) {
                        if (marginWidth > 0.dp) Box(Modifier.fillMaxSize().padding(start = 24.dp, end = 28.dp, top = 20.dp, bottom = 20.dp).testTag("v7-margin")) { margin() }
                    }
                }
                if (footer != null && inner) Row(Modifier.fillMaxWidth().background(colors.bgCanvas).height(if (inner) CoachV7.Layout.footH else 52.dp).padding(horizontal = 28.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, content = footer)
                if (!inner && !landscape && !focused) LocalDeskNavigation.current?.invoke(false)
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
        val titleStyle = if (m.inner) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall
        if (week != null) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val size = if (m.inner) coach.design.tokens.V7Reading.weekDisplay else coach.design.tokens.V7Reading.coverDisplay
            Text(week.toString().padStart(2, '0'), fontFamily = LocalV7Fonts.current.display,
                fontWeight = FontWeight.Light, fontSize = size, lineHeight = size * .8f, letterSpacing = (-.03).em,
                style = TextStyle(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both, LineHeightStyle.Mode.Tight)),
                modifier = Modifier.height((size.value * .8f).dp).wrapContentHeight(Alignment.CenterVertically, unbounded = true).testTag("v7-week-number"))
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
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(letter, Modifier.width(16.2.dp), fontFamily = LocalV7Fonts.current.numbers, style = MaterialTheme.typography.labelLarge, color = if (correct != null) verdictColor else colors.fgSecondary)
            Box(Modifier.weight(1f)) {
                MaterialTheme(typography = MaterialTheme.typography.copy(bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.65.sp))) { content() }
            }
            if (correct != null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (correct) "✓" else "✗", style = MaterialTheme.typography.labelMedium, color = verdictColor)
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
