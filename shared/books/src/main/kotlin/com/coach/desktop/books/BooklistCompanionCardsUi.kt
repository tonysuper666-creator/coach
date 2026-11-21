package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.LocalV7Fonts
import com.coach.ui.v7.PaperCard

/**
 * 右侧伴读抽屉底部的安静跨书联动微卡区（最多展示 2 张，正文区域 0px 行内标注/弹窗）。
 * 点击卡片右上角 `×` 立即对整个主题执行永久消音（`concept_entities.muted = 1`）。
 */
@Composable
internal fun CrossBookCompanionSection(
    cards: List<CrossBookCompanionCard>,
    onJumpToBookPage: (bookId: String, page: Int, quoteAnchor: String?) -> Unit,
    onMuteConcept: (conceptId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleCards = cards.take(ConceptStore.MAX_COMPANION_CARDS_PER_SECTION)
    if (visibleCards.isEmpty()) return

    val colors = CoachTheme.colors
    val badgeShape = RoundedCornerShape(CoachV7.Radius.s)

    Column(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp).testTag("crossbook-companion-section"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                        modifier = Modifier.weight(1f),
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
                                onJumpToBookPage(card.otherBookId, card.otherPage, card.otherQuoteAnchor)
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
    }
}
