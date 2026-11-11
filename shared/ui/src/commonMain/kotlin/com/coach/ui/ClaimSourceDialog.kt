package com.coach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.coach.client.ClaimSourceFailure
import com.coach.client.ClaimSourceRead
import com.coach.client.KpRow
import com.coach.client.ReaderHttp
import com.coach.client.readClaimSource
import com.coach.domain.reader.ClaimSourceAnchor
import com.coach.domain.reader.KnowledgeSourceKind
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

private data class SourceClaim(val ref: String?, val subpoint: String?) {
    val label: String get() = subpoint ?: ref.orEmpty()
}

private data class ClaimRequest(
    val claim: SourceClaim,
    val source: ClaimSourceAnchor? = null,
    val page: Int? = null,
    val version: String? = null,
)

private data class ClaimResult(val read: ClaimSourceRead, val document: PrivateDocument? = null)

/** The existing answer citation keeps its legacy resolver until structured references are present. */
@Composable
internal fun ClaimSourceReferences(reader: ReaderHttp, kp: KpRow?, source: String, anchor: String = "", ownerKey: String = "") {
    if (kp?.sourceReferences?.structured != true) {
        PrivateSourceReferences(reader, source, anchor)
        return
    }
    key(reader, kp, ownerKey) {
        var opened by remember { mutableStateOf(false) }
        TextButton(onClick = { opened = true }, modifier = Modifier.testTag("claim-source-open")) { Text("查看主张出处") }
        if (opened) ClaimSourceDialog(reader, kp, onClose = { opened = false })
    }
}

@Composable
internal fun ClaimSourceDialog(reader: ReaderHttp?, kp: KpRow, backLabel: String = "返回题目", onClose: () -> Unit) {
    val back = LocalAppBack.current
    AppBackHandler(true, priority = 15, onBack = onClose)
    Dialog(onDismissRequest = { if (back == null) onClose() else back.back() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        key(reader, kp) {
            Surface(Modifier.fillMaxSize().testTag("claim-source-dialog")) {
                if (reader == null) Column(Modifier.fillMaxSize()) {
                    TextButton(onClick = onClose, modifier = Modifier.testTag("claim-source-close")) { Text("← $backLabel") }
                    SourceUnconfirmed(ClaimSourceFailure.CATALOG_UNAVAILABLE, onClose)
                } else ClaimSourceContent(reader, kp, backLabel, onClose)
            }
        }
    }
}

@Composable
private fun ClaimSourceContent(reader: ReaderHttp, kp: KpRow, backLabel: String, onClose: () -> Unit) {
    val claims = remember(kp) { kp.sourceReferences.sourceAnchors.map { SourceClaim(it.claimRef, it.supportedSubpoint) }.distinct() }
    var request by remember(reader, kp) { mutableStateOf(claims.singleOrNull()?.let { ClaimRequest(it) }) }
    // Each owner/request gets its own state. A cancelled or late response can only update its abandoned state.
    val result = remember(reader, kp, request) { mutableStateOf<ClaimResult?>(null) }
    LaunchedEffect(reader, kp, request) {
        val selected = request ?: return@LaunchedEffect
        val read = reader.readClaimSource(kp, selected.claim.ref, selected.claim.subpoint,
            selected.source, selected.page, selected.version)
        coroutineContext.ensureActive()
        result.value = if (read is ClaimSourceRead.Opened) {
            val catalog = reader.catalog()
            coroutineContext.ensureActive()
            val doc = if (catalog.ok) privateDocuments(catalog.body)?.singleOrNull { it.id == read.source.docId } else null
            when {
                !catalog.ok -> ClaimResult(ClaimSourceRead.Unconfirmed(ClaimSourceFailure.CATALOG_UNAVAILABLE))
                doc == null -> ClaimResult(ClaimSourceRead.Unconfirmed(ClaimSourceFailure.DOCUMENT_NOT_REGISTERED))
                doc.version != read.version -> ClaimResult(ClaimSourceRead.Unconfirmed(ClaimSourceFailure.DOCUMENT_CHANGED))
                read.pdfPage !in 1..doc.pages -> ClaimResult(ClaimSourceRead.Unconfirmed(ClaimSourceFailure.PAGE_REPLY_MISMATCH))
                else -> ClaimResult(read, doc)
            }
        } else ClaimResult(read)
    }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onClose, modifier = Modifier.testTag("claim-source-close")) { Text("← $backLabel") }
        HorizontalDivider()
        val selected = request
        val loaded = result.value
        when {
            claims.isEmpty() -> SourceUnconfirmed(ClaimSourceFailure.SOURCE_CONTRACT, onClose)
            selected == null -> LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
                item { Text("选择要查看的主张", style = MaterialTheme.typography.titleMedium) }
                items(claims.size) { index ->
                    TextButton(onClick = { request = ClaimRequest(claims[index]) },
                        modifier = Modifier.fillMaxWidth().testTag("claim-source-claim-$index")) { Text(claims[index].label) }
                }
            }
            loaded == null -> CenterMessage("正在确认出处…")
            else -> when (val read = loaded.read) {
                is ClaimSourceRead.ChooseSource -> LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
                    item {
                        Text(selected.claim.label, style = MaterialTheme.typography.titleMedium)
                        Text("选择来源", style = MaterialTheme.typography.bodyLarge)
                    }
                    items(read.sources.size) { index ->
                        val source = read.sources[index]
                        TextButton(onClick = { request = selected.copy(source = source, page = null, version = read.versions[source.docId]) },
                            modifier = Modifier.fillMaxWidth().testTag("claim-source-choice-$index")) {
                            Text("${source.kindLabel()} · ${source.docId} · PDF ${source.pageLabel()}")
                        }
                    }
                }
                is ClaimSourceRead.ChoosePage -> LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
                    item {
                        Text(selected.claim.label, style = MaterialTheme.typography.titleMedium)
                        Text("选择页码 · ${read.source.docId} · PDF ${read.source.pageLabel()}")
                    }
                    items(read.source.pdfPageEnd - read.source.pdfPage + 1) { index ->
                        val page = read.source.pdfPage + index
                        TextButton(onClick = { request = selected.copy(source = read.source, page = page, version = read.version) },
                            modifier = Modifier.fillMaxWidth().testTag("claim-source-page-$page")) { Text("PDF 第 $page 页") }
                    }
                }
                is ClaimSourceRead.Unconfirmed -> SourceUnconfirmed(read.reason, onClose)
                is ClaimSourceRead.Opened -> PrivatePdfDocument(reader, loaded.document!!, read.pdfPage, onBack = onClose)
            }
        }
    }
}

@Composable
private fun SourceUnconfirmed(reason: ClaimSourceFailure, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("无法确认出处", Modifier.testTag("claim-source-unconfirmed"), style = MaterialTheme.typography.titleMedium)
        Text(when (reason) {
            ClaimSourceFailure.COURSE_MISMATCH -> "出处所属课程与当前课程不同。"
            ClaimSourceFailure.DOCUMENT_NOT_REGISTERED -> "来源文档尚未登记，或已移出当前目录。"
            ClaimSourceFailure.DOCUMENT_CHANGED, ClaimSourceFailure.PAGE_REPLY_MISMATCH -> "资料版本或返回页面已变化，请返回后重新选择出处。"
            ClaimSourceFailure.CATALOG_UNAVAILABLE -> "资料目录暂不可读，请检查连接和访问权限。"
            ClaimSourceFailure.SOURCE_CONTRACT, ClaimSourceFailure.SOURCE_NOT_SELECTED -> "这条主张缺少可确认的来源。"
            ClaimSourceFailure.KIND_MISMATCH -> "来源类型与登记文档不一致。"
            ClaimSourceFailure.PAGE_OUTSIDE_DOCUMENT, ClaimSourceFailure.PAGE_OUTSIDE_SOURCE -> "页码超出已登记的来源范围。"
            ClaimSourceFailure.PAGE_UNAVAILABLE, ClaimSourceFailure.NO_TEXT -> "原页暂不可读，或访问权限已变化。"
        })
        TextButton(onClick = onClose) { Text("返回") }
    }
}

private fun ClaimSourceAnchor.pageLabel(): String = if (pdfPage == pdfPageEnd) "$pdfPage" else "$pdfPage–$pdfPageEnd"
private fun ClaimSourceAnchor.kindLabel(): String = when (sourceKind) {
    KnowledgeSourceKind.TEXTBOOK -> "教材"
    KnowledgeSourceKind.SLIDES -> "课件"
    KnowledgeSourceKind.READING -> "文献"
}
