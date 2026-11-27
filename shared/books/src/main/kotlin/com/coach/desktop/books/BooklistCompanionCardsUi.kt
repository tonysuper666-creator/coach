package com.coach.desktop.books

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PaperCard

/** The companion column's viewport. The sayings pane scrolls this, not the window. */
internal class CompanionScrollAnchor {
    var scroll: ScrollState? = null
    var viewport by mutableStateOf<LayoutCoordinates?>(null)
}

internal val LocalCompanionScrollAnchor = staticCompositionLocalOf<CompanionScrollAnchor?> { null }

/**
 * 右侧伴读抽屉底部的安静跨书联动微卡区（最多展示 2 张，正文区域 0px 行内标注/弹窗）。
 * 点击卡片右上角 `×` 立即对整个主题执行永久消音（`concept_entities.muted = 1`）。
 */
@Composable
internal fun CrossBookCompanionSection(
    cards: List<CrossBookCompanionCard>,
    onJumpToBookPage: (bookId: String, page: Int, quoteAnchor: String?, offsetUtf16: Int) -> Unit,
    onMuteConcept: (conceptId: String) -> Unit,
    modifier: Modifier = Modifier,
    board: ConceptSayingsBoard? = null,
    checking: Boolean = false,
    notice: String? = null,
    onOpenConcept: (conceptId: String) -> Unit = {},
) {
    val visibleCards = cards.take(ConceptStore.MAX_COMPANION_CARDS_PER_SECTION)
    if (visibleCards.isEmpty() && board == null && !checking && notice.isNullOrBlank()) return

    val colors = CoachTheme.colors
    val badgeShape = RoundedCornerShape(CoachV7.Radius.s)

    Column(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp).testTag("crossbook-companion-section"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (visibleCards.isNotEmpty()) {
            HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.7f))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "书单联动",
                    style = MaterialTheme.typography.labelMedium,
                    color = v7Pine(),
                    modifier = Modifier.testTag("crossbook-section-title"),
                )
                CoachCardMeta("仅关联已读章节 · 点 × 静音该主题")
            }
        }

        for (card in visibleCards) {
            PaperCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("crossbook-card-${card.conceptId}"),
                contentPadding = PaddingValues(12.dp),
                contentSpacing = 6.dp,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        card.conceptName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.fgPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clip(badgeShape)
                            .clickable(role = Role.Button, onClickLabel = "别的书怎么说") {
                                onOpenConcept(card.conceptId)
                            }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .testTag("crossbook-open-${card.conceptId}"),
                    )
                    Text(
                        card.relationType.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = v7Pine(),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(badgeShape)
                            .background(v7Pine().copy(alpha = 0.08f))
                            .border(1.dp, v7Pine().copy(alpha = 0.28f), badgeShape)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Text(
                        "×",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.fgTertiary,
                        modifier = Modifier
                            .clip(badgeShape)
                            .clickable(role = Role.Button, onClickLabel = "静音主题 ${card.conceptName}") {
                                onMuteConcept(card.conceptId)
                            }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("crossbook-mute-${card.conceptId}"),
                    )
                }

                Text(
                    card.diffSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fgPrimary,
                    modifier = Modifier.testTag("crossbook-diff-${card.conceptId}"),
                )

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "《${card.otherBookTitle}》第 ${card.otherPage} 页",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = LocalV7Fonts.current.numbers,
                        color = v7Pine(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(badgeShape)
                            .background(v7Pine().copy(alpha = 0.08f))
                            .border(1.dp, v7Pine().copy(alpha = 0.28f), badgeShape)
                            .clickable(role = Role.Button) {
                                onJumpToBookPage(card.otherBookId, card.otherPage, card.otherQuoteAnchor, card.otherOffsetUtf16)
                            }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("crossbook-jump-${card.conceptId}"),
                    )
                    if (card.hasUserAnnotation) {
                        CoachCardMeta("含你的批注")
                    }
                }
            }
        }
        if (board != null || checking || !notice.isNullOrBlank()) {
            ConceptSayingsBoardPane(board, checking, notice, colors.dark, onJumpToBookPage)
        }
    }
}

@Composable
private fun ConceptSayingsBoardPane(
    board: ConceptSayingsBoard?,
    checking: Boolean,
    notice: String?,
    dark: Boolean,
    onJumpToBookPage: (bookId: String, page: Int, quoteAnchor: String?, offsetUtf16: Int) -> Unit,
) {
    val colors = CoachTheme.colors
    if (board == null && !checking && notice.isNullOrBlank()) return
    val revealKey = listOf(board?.conceptId, board?.inputHash, board?.pending, checking, notice)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().revealSayingsInCompanion(revealKey),
    ) {
        HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.7f))
        Text(
            "别的书怎么说",
            style = MaterialTheme.typography.labelMedium,
            color = v7Pine(),
            modifier = Modifier.testTag("concept-sayings-title"),
        )
        if (checking) {
            Text(
                "正在核对各书说法，核对完留在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.fgSecondary,
                modifier = Modifier.testTag("concept-sayings-checking"),
            )
        }
        if (!notice.isNullOrBlank()) {
            Text(
                notice,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgPrimary,
                modifier = Modifier.testTag("concept-sayings-notice"),
            )
        }
        if (board != null) {
            Column(
                Modifier.fillMaxWidth().testTag("concept-sayings-${board.conceptId}"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (board.onlyThisBook) {
                    Text(
                        ConceptSayingsBoard.ONLY_THIS_BOOK,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.fgPrimary,
                        modifier = Modifier.testTag("concept-sayings-only-one"),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (column in board.columns) {
                        SayingColumnCard(column, dark, onJumpToBookPage)
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.revealSayingsInCompanion(revealKey: Any?): Modifier {
    val anchor = LocalCompanionScrollAnchor.current
    var self by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(revealKey, anchor) {
        if (revealKey == null || anchor == null) return@LaunchedEffect
        val placed = snapshotFlow {
            val coords = self
            val view = anchor.viewport
            val max = anchor.scroll?.maxValue
            Triple(coords, view, max)
        }.first { (coords, view, max) ->
            coords != null && view != null && coords.isAttached && view.isAttached &&
                coords.size.height > 0 && view.size.height > 0 && max != null &&
                (max > 0 || runCatching {
                    view.localPositionOf(coords, Offset.Zero).y + coords.size.height <= view.size.height + 1f
                }.getOrDefault(false))
        }
        val coords = placed.first ?: return@LaunchedEffect
        val view = placed.second ?: return@LaunchedEffect
        val scrollState = anchor.scroll ?: return@LaunchedEffect
        if (!coords.isAttached || !view.isAttached) return@LaunchedEffect
        val top = runCatching { view.localPositionOf(coords, Offset.Zero).y }.getOrNull() ?: return@LaunchedEffect
        val delta = sayingsScrollDelta(top, coords.size.height.toFloat(), view.size.height.toFloat())
        if (abs(delta) < 1f) return@LaunchedEffect
        val target = (scrollState.value + delta).roundToInt().coerceIn(0, scrollState.maxValue)
        if (target != scrollState.value) scrollState.scrollTo(target)
    }
    return this.onGloballyPositioned { self = it }
}

private fun sayingsScrollDelta(topInViewport: Float, paneHeight: Float, viewHeight: Float): Float {
    val bottom = topInViewport + paneHeight
    if (topInViewport >= -0.5f && bottom <= viewHeight + 0.5f) return 0f
    return if (paneHeight <= viewHeight) {
        if (topInViewport < 0f) topInViewport else bottom - viewHeight
    } else {
        topInViewport
    }
}

@Composable
private fun SayingColumnCard(
    column: ConceptSayingColumn,
    dark: Boolean,
    onJumpToBookPage: (bookId: String, page: Int, quoteAnchor: String?, offsetUtf16: Int) -> Unit,
) {
    val colors = CoachTheme.colors
    val badgeShape = RoundedCornerShape(CoachV7.Radius.s)
    val stanceColor = when (column.stance) {
        SayingStance.SAME -> v7Pine()
        SayingStance.ANGLE -> colors.fgSecondary
        SayingStance.CONFLICT -> if (dark) CoachV7.Dark.zhu else CoachV7.Light.zhu
    }
    PaperCard(
        modifier = Modifier.width(200.dp).testTag("concept-saying-col-${column.bookId}"),
        contentPadding = PaddingValues(10.dp),
        contentSpacing = 6.dp,
    ) {
        Text(
            column.bookTitle,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.fgPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            column.stance.label,
            style = MaterialTheme.typography.labelSmall,
            color = stanceColor,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .clip(badgeShape)
                .background(stanceColor.copy(alpha = 0.08f))
                .border(1.dp, stanceColor.copy(alpha = 0.28f), badgeShape)
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag("concept-saying-stance-${column.bookId}"),
        )
        Text(
            column.statement,
            style = MaterialTheme.typography.bodySmall,
            color = colors.fgPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            column.quoteAnchor,
            style = MaterialTheme.typography.labelSmall,
            color = stanceColor,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(badgeShape)
                .background(stanceColor.copy(alpha = 0.08f))
                .border(1.dp, stanceColor.copy(alpha = 0.28f), badgeShape)
                .clickable(role = Role.Button, onClickLabel = "跳到原文") {
                    onJumpToBookPage(column.bookId, column.page, column.quoteAnchor, column.offsetUtf16)
                }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .testTag("concept-saying-quote-${column.bookId}"),
        )
    }
}
