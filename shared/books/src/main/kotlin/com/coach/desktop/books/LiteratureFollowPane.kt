package com.coach.desktop.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LiteratureFollowPane(
    detail: CourseDetailSnapshot,
    library: TextbookLibrary,
    onBack: () -> Unit,
    onJump: (bookId: String, pdfPage: Int, quote: String) -> Unit,
) {
    val bookIds = remember(detail.courseId, detail.materials) {
        detail.materials.filter { it.format.equals("pdf", ignoreCase = true) }.map { it.bookId }
    }
    var papers by remember(detail.courseId) { mutableStateOf<List<FollowPaper>>(emptyList()) }
    var ready by remember(detail.courseId) { mutableStateOf(false) }
    var notice by remember(detail.courseId) { mutableStateOf("") }
    LaunchedEffect(detail.courseId, bookIds) {
        ready = false
        papers = emptyList()
        try {
            val built = withContext(Dispatchers.IO) { LiteratureFollow.loadPapers(library, bookIds) }
            papers = built
            ready = built.isNotEmpty()
            notice = if (built.isEmpty()) "这些 PDF 里没有可跟读的正文。" else ""
            if (built.isEmpty()) return@LaunchedEffect
            papers = withContext(Dispatchers.IO) { LiteratureFollow.fillStops(library, built) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            notice = failure.message ?: "跟读路线暂时整理不了。"
        }
    }
    Box(Modifier.fillMaxSize().testTag("follow-screen"), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            V7TextButton(onBack, modifier = Modifier.testTag("follow-back")) { Text("← 课程") }
            Text("按小节跟读", style = MaterialTheme.typography.headlineSmall)
            if (!ready) Text(if (notice.isEmpty()) "正在按小节整理…" else notice)
            if (ready) {
                Column(Modifier.testTag("follow-ready"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    papers.forEach { paper ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(paper.title, style = MaterialTheme.typography.titleMedium)
                            paper.sections.forEachIndexed { index, section ->
                                val jump = section.stop ?: section.sentences.firstOrNull()
                                val pageLabel = jump?.let { sentence ->
                                    LiteraturePrintedPages.display(sentence.pdfPage, paper.printedByPdfPage.getOrNull(sentence.pdfPage - 1))
                                }.orEmpty()
                                V7TextButton(
                                    onClick = { jump?.let { onJump(it.bookId, it.pdfPage, it.quote) } },
                                    modifier = Modifier.testTag("follow-section-${paper.bookId}-$index"),
                                ) {
                                    Text("${section.title} · $pageLabel", style = MaterialTheme.typography.bodyMedium)
                                }
                                val stop = section.stop
                                if (stop != null) {
                                    V7TextButton(
                                        onClick = { onJump(stop.bookId, stop.pdfPage, stop.quote) },
                                        modifier = Modifier.testTag("follow-stop-${paper.bookId}-$index"),
                                    ) {
                                        Text(stop.quote, style = MaterialTheme.typography.bodySmall)
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
