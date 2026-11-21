package com.coach.ui.v7

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import kotlin.math.PI

/** Destructive confirmation button rendered with V7's danger surface and AA foreground ink. */
@Composable
fun DangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val c = CoachTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(CoachV7.Radius.btn)
    Row(
        modifier
            .v7FocusRing(shape)
            .clip(shape)
            .background(c.actionDanger.copy(alpha = if (enabled) 1f else .4f))
            .v7ButtonClick(interaction, enabled, onClick)
            .defaultMinSize(minHeight = if (LocalDeskMetrics.current.inner) 36.dp else 44.dp)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides c.onAccent.copy(alpha = if (enabled) 1f else .7f)) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = CoachV7.Weight.uiStrong)) { content() }
        }
    }
}

/**
 * Sable-aligned empty state: one quiet accent dot, a direct title, a single-sentence next step,
 * and at most one primary action button.
 */
@Composable
fun CoachEmptyState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = CoachTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(CoachV7.Space.s7).testTag("coach-empty-state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CoachV7.Space.s3),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(c.accent))
        Text(title, style = MaterialTheme.typography.headlineMedium, color = c.fgPrimary, textAlign = TextAlign.Center)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = c.fgSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Spacer(Modifier.height(CoachV7.Space.s2))
            PrimaryButton(onClick = onAction, modifier = Modifier.testTag("coach-empty-action")) { Text(actionLabel) }
        }
    }
}

/** Inline error banner preserving the surrounding screen state with a direct retry affordance. */
@Composable
fun CoachErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    retryLabel: String = "重试",
    onRetry: (() -> Unit)? = null,
) {
    val c = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.m)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.dangerBg, shape)
            .border(BorderStroke(1.dp, c.dangerFg.copy(alpha = 0.35f)), shape)
            .padding(horizontal = CoachV7.Space.s4, vertical = CoachV7.Space.s3)
            .testTag("coach-error-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CoachV7.Space.s3),
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = c.dangerFg, modifier = Modifier.weight(1f))
        if (onRetry != null) {
            V7TextButton(onClick = onRetry, modifier = Modifier.testTag("coach-error-retry")) {
                Text(retryLabel, color = c.dangerFg, fontWeight = CoachV7.Weight.uiStrong)
            }
        }
    }
}

/** Calm offline status strip with an amber dot and plain-language reassurance. */
@Composable
fun CoachOfflineBanner(
    modifier: Modifier = Modifier,
    message: String = "当前离线。本地记录已保留，恢复连接后会继续同步。",
) {
    val c = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.m)
    val dotColor = if (c.dark) Color(0xFFE0B45C) else Color(0xFFB37A1E)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface2, shape)
            .border(BorderStroke(1.dp, c.borderSubtle), shape)
            .padding(horizontal = CoachV7.Space.s4, vertical = 10.dp)
            .testTag("coach-offline-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Text(message, style = MaterialTheme.typography.bodySmall, color = c.fgPrimary)
    }
}

/**
 * Quiet three-dot breathing indicator (1800ms phase cycle, opacity/scale only, zero rotation).
 * Collapses to steady dots when [LocalV7ReducedMotion] is enabled.
 */
@Composable
fun CoachLoadingDots(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val c = CoachTheme.colors
    val reduced = LocalV7ReducedMotion.current
    val phase = if (reduced) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "coach-loading-breathe")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )
        value
    }
    Row(
        modifier = modifier.testTag("coach-loading-dots"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CoachV7.Space.s2),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { index ->
                val t = if (reduced) 0.5f else (phase + index / 3f) % 1f
                val wave = 0.5f - 0.5f * kotlin.math.cos(2.0 * PI * t).toFloat()
                val alpha = if (reduced) 0.85f else 0.45f + 0.55f * wave
                val dotScale = if (reduced) 1f else 0.90f + 0.10f * wave
                Box(
                    Modifier
                        .size(7.dp)
                        .scale(dotScale)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(c.fgSecondary),
                )
            }
        }
        if (!label.isNullOrBlank()) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.fgSecondary)
        }
    }
}

/** 4dp pill progress bar rendered with V7 ink tokens. */
@Composable
fun CoachProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
) {
    val c = CoachTheme.colors
    val reduced = LocalV7ReducedMotion.current
    val clamped = progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = clamped,
        animationSpec = tween(durationMillis = if (reduced) 0 else 350, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "progress",
    )
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(shape)
            .background(c.borderSubtle)
            .testTag("coach-progress-bar"),
    ) {
        if (animated > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(if (warn) c.actionDanger else c.actionPrimary),
            )
        }
    }
}

/** Single-line muted footer metadata with tabular numbers for cards and list rows. */
@Composable
fun CoachCardMeta(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.testTag("coach-card-meta"),
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = LocalV7Fonts.current.numbers),
        color = CoachTheme.colors.fgSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
