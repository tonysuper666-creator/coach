package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.CoachCardMeta
import com.coach.ui.v7.CoachEmptyState
import com.coach.ui.v7.PaperCard
import com.coach.ui.v7.V7TextButton

/**
 * 一本书的概念掌握图。点开一个概念看证据，每条证据回到原句并高亮。
 */
@Composable
internal fun ConceptMasteryMapPanel(
    book: Textbook,
    library: TextbookLibrary,
    onOpenAnchor: (BookCompanionAnchor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val chapters = remember(book.id, book.pages.size, book.structure?.editRevision) {
        library.conceptMasteryMap(book)
    }
    var openKey by remember(book.id) { mutableStateOf<String?>(null) }
    Column(
        modifier
            .fillMaxSize()
            .background(colors.bgCanvas)
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
            .testTag("concept-mastery-map"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("概念掌握", style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
        CoachCardMeta("按章列出。点一条证据，回到书里那一句。")
        if (chapters.isEmpty()) {
            CoachEmptyState(
                title = "还没有记到概念上的作答。",
                hint = "做完选择题、开放题或章末题后，这里按章列出哪些概念会了。",
            )
            return@Column
        }
        var row = 0
        chapters.forEach { chapter ->
            Text(
                chapter.chapterTitle,
                style = MaterialTheme.typography.labelLarge,
                color = colors.fgPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("concept-mastery-chapter-${chapter.chapterId}"),
            )
            chapter.concepts.forEach { concept ->
                val index = row++
                val open = openKey == concept.conceptKey
                PaperCard(Modifier.fillMaxWidth().testTag("concept-mastery-row-$index")) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { openKey = if (open) null else concept.conceptKey }
                                .pointerHoverIcon(PointerIcon.Hand),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                concept.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.fgPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${concept.levelLabel} · ${concept.evidenceCount} 条证据",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.fgSecondary,
                                modifier = Modifier.testTag("concept-mastery-meta-$index"),
                            )
                        }
                        if (open) {
                            concept.evidence.forEach { line ->
                                Column(Modifier.fillMaxWidth().testTag("concept-evidence-${line.eventId}")) {
                                    Text(line.label, style = MaterialTheme.typography.bodySmall, color = colors.fgPrimary)
                                    Text("第 ${line.page} 页", style = MaterialTheme.typography.labelSmall, color = colors.fgSecondary)
                                    V7TextButton(
                                        onClick = { onOpenAnchor(ConceptMasteryMap.anchorFor(line)) },
                                        modifier = Modifier.testTag("concept-evidence-jump-${line.eventId}"),
                                    ) { Text("回到原句") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
