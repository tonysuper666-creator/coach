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

val LocalReadingFocus = staticCompositionLocalOf { false }
val LocalFootnotePrefix = staticCompositionLocalOf { "" }
val LocalV7ReducedMotion = staticCompositionLocalOf { false }
val LocalDeskWindow = staticCompositionLocalOf<com.coach.ui.adaptive.WindowSpec?> { null }
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
    directory: @Composable () -> Unit = {},
    margin: @Composable () -> Unit = {},
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = CoachTheme.colors
    val reduced = LocalV7ReducedMotion.current
    val progress = remember { Animatable(0f) }
    var previousFocus by remember { mutableStateOf(focus) }
    LaunchedEffect(crumb, focus, reduced) {
        val focusChanged = previousFocus != focus
        previousFocus = focus
        progress.snapTo(0f)
        progress.animateTo(1f, tween(if (reduced) 0 else if (focusChanged) 180 else CoachV7.Motion.pageMs,
            easing = if (focusChanged || focus) LinearEasing else CoachV7.Motion.easing))
    }
    val contentNow by rememberUpdatedState(content)
    val page = remember { movableContentOf { contentNow() } }
    BoxWithConstraints(modifier.fillMaxSize().background(if (focus) colors.surface1 else colors.bgCanvas).testTag("v7-desk")) {
        val logicalWindow = LocalDeskWindow.current
        val window = deskWindow(logicalWindow?.widthDp ?: maxWidth.value, logicalWindow?.heightDp ?: maxHeight.value)
        val windowWidth = maxWidth
        val inner = window == DeskWindow.Inner
        val landscape = window == DeskWindow.OuterLandscape
        val metrics = DeskMetrics(window,
            when { focus -> 24.dp; inner -> (CoachV7.Layout.mainPx * (windowWidth.value / 1224f)).coerceAtLeast(24.dp); landscape -> V7Windows.OuterL.mainPx; else -> V7Windows.Outer.mainPx },
            when { focus -> 72.dp; inner -> CoachV7.Layout.mainPt; landscape -> V7Windows.OuterL.mainPt; else -> V7Windows.Outer.mainPt },
            if (inner) CoachV7.Layout.optH else if (landscape) V7Windows.OuterL.optH else V7Windows.Outer.optH)
        CompositionLocalProvider(LocalDeskMetrics provides metrics, LocalReadingFocus provides focus) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(if (landscape) V7Windows.OuterL.barH else CoachV7.Layout.barH).padding(horizontal = if (inner) CoachV7.Layout.pad else 20.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (onBack != null) TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text(backLabel, style = MaterialTheme.typography.labelLarge) }
                    Text("Coach", style = MaterialTheme.typography.labelLarge, fontWeight = CoachV7.Weight.uiStrong, modifier = Modifier.weight(1f))
                    Text(crumb, style = MaterialTheme.typography.labelLarge, color = colors.fgSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(if (inner) 2f else 3f))
                    if (onFocus != null) TextButton(onClick = onFocus, modifier = Modifier.testTag("v7-focus"), contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(if (focus) "退出专心读" else "专心读", style = MaterialTheme.typography.labelLarge, color = if (focus) colors.fgPrimary else colors.fgSecondary)
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth().padding(top = if (inner && !focus) 8.dp else 0.dp, bottom = if (inner && !focus) 12.dp else 0.dp)) {
                    // Keep all three containers in place so a fold never recreates the reading composition.
                    val tocWidth = if (inner && !focus) CoachV7.Layout.wToc * (windowWidth.value / 1224f).coerceAtMost(1f) else 0.dp
                    val marginWidth = if (inner && !focus) CoachV7.Layout.wRail * (windowWidth.value / 1224f).coerceAtMost(1f) else 0.dp
                    Box(Modifier.width(tocWidth).fillMaxHeight()) {
                        if (tocWidth > 0.dp) Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 24.dp).verticalScroll(rememberScrollState()).testTag("v7-directory")) {
                            directory()
                            LocalDeskNavigation.current?.let { nav -> Spacer(Modifier.height(32.dp)); nav(true) }
                        }
                    }
                    if (landscape && !focus) Box(Modifier.width(V7Windows.OuterL.navW).fillMaxHeight().padding(top = 20.dp)) { LocalDeskNavigation.current?.invoke(true) }
                    Box(Modifier.weight(1f).fillMaxHeight().padding(top = if (inner && !focus) 4.dp else 0.dp)
                        .then(if (inner && !focus) Modifier.shadow(12.dp, RoundedCornerShape(CoachV7.Radius.sheet), ambientColor = Color.Black.copy(alpha = .09f), spotColor = Color.Black.copy(alpha = .09f)) else Modifier)
                        .clip(if (focus) RoundedCornerShape(0.dp) else RoundedCornerShape(CoachV7.Radius.sheet))
                        .background(colors.surface1)
                        .graphicsLayer { alpha = progress.value; translationY = if (focus || reduced) 0f else 8.dp.toPx() * (1 - progress.value) }
                        .testTag("v7-paper")) { page() }
                    Box(Modifier.width(marginWidth).fillMaxHeight()) {
                        if (marginWidth > 0.dp) Box(Modifier.fillMaxSize().padding(start = 24.dp, end = 28.dp, top = 20.dp).testTag("v7-margin")) { margin() }
                    }
                }
                if (footer != null) Row(Modifier.fillMaxWidth().height(if (inner) CoachV7.Layout.footH else 52.dp).padding(horizontal = 28.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, content = footer)
                else if (!inner && !landscape && !focus) LocalDeskNavigation.current?.invoke(false)
            }
        }
    }
}

@Composable
fun PageHeader(kicker: String, title: String, deck: String = "", meta: String = "", week: Int? = null) {
    val m = LocalDeskMetrics.current
    val colors = CoachTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(CoachV7.Space.s2)) {
        Text(kicker, style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
        val titleStyle = if (m.inner) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall
        if (week != null) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(CoachV7.Space.s5)) {
            val size = if (m.inner) coach.design.tokens.V7Reading.weekDisplay else coach.design.tokens.V7Reading.coverDisplay
            Text(week.toString().padStart(2, '0'), fontFamily = LocalV7Fonts.current.display,
                fontWeight = FontWeight.Light, fontSize = size, lineHeight = size * .8f, letterSpacing = (-.03).em,
                style = TextStyle(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both, LineHeightStyle.Mode.Tight)),
                modifier = Modifier.testTag("v7-week-number"))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = titleStyle)
                if (deck.isNotBlank()) Text(deck, style = MaterialTheme.typography.bodyLarge, color = colors.fgSecondary)
            }
        } else {
            Text(title, style = titleStyle)
            if (deck.isNotBlank()) Text(deck, style = MaterialTheme.typography.bodyLarge, color = colors.fgSecondary)
        }
        if (meta.isNotBlank()) Text(meta, Modifier.padding(top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
    }
}

@Composable
fun PaperCard(modifier: Modifier = Modifier, color: Color = CoachTheme.colors.surface2,
    contentSpacing: Dp = 8.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = CoachV7.Space.s4, vertical = CoachV7.Space.s3),
    content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(CoachV7.Radius.m), ambientColor = Color.Black.copy(alpha = .07f), spotColor = Color.Black.copy(alpha = .05f)), color = color, shape = RoundedCornerShape(CoachV7.Radius.m)) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(contentSpacing), content = content)
    }
}

/** Correct/incorrect options keep exactly the same geometry and shadow as idle options. */
@Composable
fun AnswerOption(letter: String, selected: Boolean, correct: Boolean?, enabled: Boolean, modifier: Modifier = Modifier,
    onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = CoachTheme.colors
    val option = if (colors.dark) CoachV7.Dark.optBg else CoachV7.Light.optBg
    val fg = when (correct) { true -> colors.successFg; false -> colors.dangerFg; null -> colors.fgPrimary }
    val bg = when (correct) { true -> colors.successBg; false -> colors.dangerBg; null -> if (selected) colors.accentSoft else option }
    Surface(onClick = onClick, enabled = enabled, color = bg, contentColor = fg, shape = RoundedCornerShape(CoachV7.Radius.m), shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth().heightIn(min = maxOf(48.dp, LocalDeskMetrics.current.optionHeight)), selected = selected) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(letter, fontFamily = LocalV7Fonts.current.numbers, style = MaterialTheme.typography.labelLarge, color = if (correct == null) colors.fgSecondary else fg)
            Box(Modifier.weight(1f)) {
                MaterialTheme(typography = MaterialTheme.typography.copy(bodyLarge = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = CoachV7.Size.opt, lineHeight = CoachV7.Size.opt * 1.5f))) { content() }
            }
            Box(Modifier.width(20.dp)) {
                if (correct != null || selected) Text(when (correct) { false -> "✗"; else -> "✓" }, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
