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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LiteratureStationFollowPane(
    bookIds: List<String>,
    load: suspend (List<String>) -> List<LiteratureStation>,
    onBack: () -> Unit,
    onJump: (LiteratureStation) -> Unit,
) {
    var stations by remember(bookIds) { mutableStateOf<List<LiteratureStation>>(emptyList()) }
    var ready by remember(bookIds) { mutableStateOf(false) }
    var notice by remember(bookIds) { mutableStateOf("") }
    LaunchedEffect(bookIds) {
        ready = false
        stations = emptyList()
        notice = ""
        try {
            val built = withContext(Dispatchers.IO) { load(bookIds) }
            stations = built
            ready = true
            notice = if (built.isEmpty()) "这些材料里没有可跟读的正文。" else ""
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ready = true
            notice = failure.message ?: "逐站跟读暂时整理不了。"
        }
    }
    Box(Modifier.fillMaxSize().testTag("station-follow-screen"), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            V7TextButton(onBack, modifier = Modifier.testTag("station-follow-back")) { Text("← 课程") }
            Text("逐站跟读", style = MaterialTheme.typography.headlineSmall)
            Text(
                "按小节停在一句正文上。点一句就回到那一页。",
                style = MaterialTheme.typography.bodyMedium,
                color = CoachTheme.colors.fgSecondary,
            )
            if (!ready) Text(if (notice.isEmpty()) "正在按小节整理…" else notice)
            if (ready && notice.isNotEmpty()) Text(notice, modifier = Modifier.testTag("station-follow-notice"))
            if (ready && stations.isNotEmpty()) {
                Column(Modifier.testTag("station-follow-ready"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    stations.groupBy { it.bookId }.forEach { (_, paper) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(paper.first().bookTitle, style = MaterialTheme.typography.titleMedium)
                            paper.forEachIndexed { index, station ->
                                V7TextButton(
                                    onClick = { onJump(station) },
                                    modifier = Modifier.testTag("station-stop-${station.bookId}-$index"),
                                ) {
                                    Column {
                                        Text(
                                            "${station.title} · 第 ${station.page} 页",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            station.quote,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
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
