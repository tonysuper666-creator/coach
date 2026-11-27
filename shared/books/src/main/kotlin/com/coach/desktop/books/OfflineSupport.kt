package com.coach.desktop.books

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

internal enum class CoachConnectivityStatus {
    UNKNOWN,
    ONLINE,
    OFFLINE,
}

/**
 * Separates slow/flaky links (e.g. a dead secondary NIC adding 12s to DNS, or 1–2 transient packet drops)
 * from genuine offline state:
 * - Each probe attempt allows [attemptTimeoutMs] (15s by default, covering a 12s DNS failover).
 * - Before transitioning to [CoachConnectivityStatus.OFFLINE], retries up to [maxAttempts] times
 *   with progressively longer delays ([retryDelaysMs]).
 * - Once already [CoachConnectivityStatus.OFFLINE], each background poll runs a single attempt so
 *   reconnection is detected immediately on the next tick.
 */
internal data class ConnectivityProbePolicy(
    val maxAttempts: Int = 3,
    val attemptTimeoutMs: Long = 15_000L,
    val retryDelaysMs: List<Long> = listOf(1_000L, 2_500L),
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(attemptTimeoutMs > 0L) { "attemptTimeoutMs must be > 0" }
    }

    fun delayAfterFailure(attemptZeroBased: Int): Long {
        if (retryDelaysMs.isEmpty()) return 0L
        return retryDelaysMs.getOrElse(attemptZeroBased) { retryDelaysMs.last() }.coerceAtLeast(0L)
    }
}

internal suspend fun <T> retryBeforeOffline(
    serverUrl: String,
    policy: ConnectivityProbePolicy = CoachConnectivityRegistry.probePolicy,
    stillCurrent: suspend () -> Boolean = { true },
    isReachableResult: (T) -> Boolean,
    attempt: suspend (timeoutMs: Long) -> T,
): T {
    val alreadyOffline = CoachConnectivityRegistry.isOffline(serverUrl)
    val limit = if (alreadyOffline) 1 else policy.maxAttempts
    var last: T? = null
    for (index in 0 until limit) {
        val result = attempt(policy.attemptTimeoutMs)
        last = result
        if (isReachableResult(result)) {
            CoachConnectivityRegistry.markOnline(serverUrl)
            return result
        }
        if (index + 1 < limit) {
            val waitMs = policy.delayAfterFailure(index)
            if (waitMs > 0L) kotlinx.coroutines.delay(waitMs)
            if (!stillCurrent()) return result
        }
    }
    CoachConnectivityRegistry.markOffline(serverUrl)
    return checkNotNull(last)
}

internal object OfflineCopy {
    const val NEEDS_NETWORK = "需要联网"
    const val EXPLAIN_OFFLINE = "需要联网才能使用，读书和做题照常。"
    const val ASK_OFFLINE = "需要联网才能提问，已输入的问题已保留，联网后一键可发。"
    const val COLLECTION_ASK_OFFLINE = "跨材料提问需要联网，已输入的问题已保留，联网后一键可发。"
    const val BOOK_MAP_OFFLINE = "生成全书地图需要联网，可先查看本机章节目录。"
    const val REVIEW_DIAGNOSE_OFFLINE = "批改与错因诊断需要联网，本机复习与排程照常保存。"
    const val HOST_BADGE_OFFLINE = "当前离线 · 讲解需要联网"

    private val technicalMarkers = Regex(
        """(Exception|Error|HTTP|http|timeout|Timeout|timed out|Connect|Socket|EOF|<html>|502|503|504)""",
    )

    fun normalizeOfflineMessage(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return "需要联网才能使用，已保留本机内容。"
        if (technicalMarkers.containsMatchIn(text)) return "需要联网才能使用，已保留本机内容。"
        if (NEEDS_NETWORK in text) return text
        if ("离线" in text || "连不上" in text) {
            return "需要联网才能使用，已保留本机内容。"
        }
        return text
    }
}

/**
 * Tracks the latest observed server reachability per normalized server URL so AI panels,
 * host status badges, and background upload/sync recovery share one consistent signal.
 */
internal object CoachConnectivityRegistry {
    private val flows = ConcurrentHashMap<String, MutableStateFlow<CoachConnectivityStatus>>()
    @Volatile
    var probePolicy: ConnectivityProbePolicy = ConnectivityProbePolicy()

    private fun key(serverUrl: String): String = serverUrl.trim().trimEnd('/')

    fun flowFor(serverUrl: String): StateFlow<CoachConnectivityStatus> {
        val k = key(serverUrl)
        return flows.computeIfAbsent(k) { MutableStateFlow(CoachConnectivityStatus.UNKNOWN) }.asStateFlow()
    }

    fun statusFor(serverUrl: String): CoachConnectivityStatus {
        val k = key(serverUrl)
        return flows[k]?.value ?: CoachConnectivityStatus.UNKNOWN
    }

    fun isOffline(serverUrl: String): Boolean = statusFor(serverUrl) == CoachConnectivityStatus.OFFLINE

    fun markOnline(serverUrl: String) {
        val k = key(serverUrl)
        if (k.isBlank()) return
        flows.computeIfAbsent(k) { MutableStateFlow(CoachConnectivityStatus.ONLINE) }.value = CoachConnectivityStatus.ONLINE
    }

    fun markOffline(serverUrl: String) {
        val k = key(serverUrl)
        if (k.isBlank()) return
        flows.computeIfAbsent(k) { MutableStateFlow(CoachConnectivityStatus.OFFLINE) }.value = CoachConnectivityStatus.OFFLINE
    }

    fun resetForTests() {
        flows.clear()
        probePolicy = ConnectivityProbePolicy()
    }
}

internal val LocalCoachConnectivity = compositionLocalOf { CoachConnectivityStatus.UNKNOWN }
internal val LocalConnectivityPollMs = compositionLocalOf { 4_000L }

/**
 * Shared calm V7 notice for AI features that require network connectivity.
 */
@Composable
internal fun OfflineNeedsNetworkNotice(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    tag: String = "offline-needs-network",
    retryTag: String = "offline-needs-network-retry",
) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(CoachV7.Radius.m)
    val dotColor = if (colors.dark) Color(0xFFE0B45C) else Color(0xFFB37A1E)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface2, shape)
            .border(BorderStroke(1.dp, colors.borderSubtle), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.fgPrimary,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            V7TextButton(onClick = onRetry, modifier = Modifier.testTag(retryTag), ringInside = true) {
                Text("重试", maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * Compact status pill placed inside the fixed-height [HostTopBar] when the server is unreachable.
 */
@Composable
internal fun HostOfflineBadge(modifier: Modifier = Modifier) {
    val colors = CoachTheme.colors
    val shape = RoundedCornerShape(999.dp)
    val dotColor = if (colors.dark) Color(0xFFE0B45C) else Color(0xFFB37A1E)
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.surface2, shape)
            .border(BorderStroke(1.dp, colors.borderSubtle), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("host-offline-badge"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Text(
            OfflineCopy.HOST_BADGE_OFFLINE,
            style = MaterialTheme.typography.labelSmall,
            color = colors.fgSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
