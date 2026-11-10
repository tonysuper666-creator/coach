package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.client.CourseHttp
import com.coach.domain.MainlineEssayIdentity
import com.coach.domain.MainlineEssayDocument

/** Keeps only position outside composition. Every opening fetches with the current paired account. */
@Composable
internal fun MainlineEssayReader(http: CourseHttp, week: Int, identity: MainlineEssayIdentity,
                                 initialScroll: Int, saveScroll: (Int) -> Unit, onClose: () -> Unit) {
    var document by remember(identity) { mutableStateOf<MainlineEssayDocument?>(null) }
    var error by remember(identity) { mutableStateOf<String?>(null) }
    var reload by remember(identity) { mutableIntStateOf(0) }
    var asking by remember(identity) { mutableStateOf(false) }
    val scroll = rememberScrollState(initialScroll)
    LaunchedEffect(identity, reload) {
        document = null; error = null
        val response = http.mainlineEssay(week, identity)
        document = response.document; error = response.error
    }
    LaunchedEffect(identity, scroll) { snapshotFlow { scroll.value }.collect(saveScroll) }
    AppBackHandler(asking, priority = 350) { asking = false }
    Column(Modifier.fillMaxSize().padding(20.dp).testTag("mainline-essay-reader"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onClose, modifier = Modifier.testTag("mainline-essay-close")) { Text("回到这道题") }
            if (document != null) TextButton(onClick = { asking = !asking }, modifier = Modifier.testTag("mainline-essay-ask")) { Text(if (asking) "回到示范" else "问一句") }
        }
        Text("考场答卷示范", style = MaterialTheme.typography.headlineSmall)
        if (asking) {
            LadderQuestionAsk(http, week, identity.itemId, {}, { asking = false })
        } else {
            val current = document
            if (current == null) {
                Text(error ?: "正在读取示范全文…", Modifier.testTag("mainline-essay-message"))
                if (error != null) TextButton(onClick = { reload++ }, modifier = Modifier.testTag("mainline-essay-retry")) { Text("重试读取") }
            } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("mainline-essay-scroll"),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (current.syntheticOnly) Text("合成示范，仅用于验证题路，不计掌握。")
                Text(current.modelAnswer, Modifier.testTag("mainline-essay-full-text"), style = MaterialTheme.typography.bodyLarge)
                Text("对应评分点", style = MaterialTheme.typography.titleMedium)
                current.rubricPoints.forEach { point -> Text(point.text, style = MaterialTheme.typography.bodyLarge) }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
