package com.coach.desktop.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.client.CourseHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.UUID

data class ExplanationOwner(val course: String, val week: Int, val item: String) {
    init { require(course.isNotBlank() && week > 0 && item.isNotBlank()) }
}

interface DesktopExplanationTransport {
    suspend fun week(week: Int): JsonObject
    suspend fun explain(week: Int, input: JsonObject): JsonObject
}

class CourseExplanationTransport(private val http: CourseHttp) : DesktopExplanationTransport {
    override suspend fun week(week: Int) = http.mainlineWeek(week).let { reply ->
        reply.body?.takeIf { reply.error == null } ?: error(reply.error ?: "暂时取不到题目")
    }
    override suspend fun explain(week: Int, input: JsonObject): JsonObject {
        val reply = http.explainSelection(week, input)
        return reply.body?.takeIf { reply.error == null } ?: error(reply.error ?: "暂时取不到讲解")
    }
}

/** The owner and unsubmitted answer live outside ReaderSession, which resets answers on book changes. */
class DesktopExplanationSession(
    val owner: ExplanationOwner, private val transport: DesktopExplanationTransport, private val reader: ReaderSource,
) {
    var question by mutableStateOf<JsonObject?>(null); private set
    var choice by mutableStateOf<Int?>(null); private set
    var draft by mutableStateOf(""); private set
    var explanation by mutableStateOf(""); private set
    var citations by mutableStateOf<List<ExplanationCitation>>(emptyList()); private set
    var opened by mutableStateOf<ReaderSession?>(null); private set
    var activeCitation by mutableStateOf<ExplanationCitation?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var clickToDrawMs by mutableStateOf<Long?>(null); private set
    private var clickedAt = 0L
    fun choose(index: Int) { if (index in options.indices) choice = index }
    fun edit(value: String) { draft = value }
    val options get() = (question?.get("options") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    suspend fun load() = guarded {
        val response = transport.week(owner.week)
        require((response["week"] as? JsonPrimitive)?.intOrNull == owner.week) { "题目周次不一致" }
        question = (response["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .singleOrNull { it["id"] == JsonPrimitive(owner.item) } ?: error("这道题当前不可用")
    }
    suspend fun explain() = guarded {
        require(question != null) { "请先读取题目" }
        citations = emptyList(); explanation = ""
        val body = transport.explain(owner.week, buildJsonObject {
            put("requestId", UUID.randomUUID().toString().replace("-", ""))
            put("itemId", owner.item); put("kind", "explain"); put("question", draft)
        })
        val raw = body["sources"] as? JsonArray ?: error("讲解缺少出处信息")
        val parsed = raw.map { ExplanationCitation.parse(it as? JsonObject ?: error("出处格式无效")) }
        explanation = (body["text"] as? JsonPrimitive)?.contentOrNull ?: error("讲解内容无效")
        citations = parsed
    }
    suspend fun open(citation: ExplanationCitation) = guarded {
        require(citation in citations) { "出处不属于当前讲解" }
        opened = null; activeCitation = null; clickToDrawMs = null; clickedAt = System.nanoTime()
        val source = CitationReaderSource.create(citation, reader)
        val remote = ReaderRemote(source)
        remote.loadCatalog()
        val session = ReaderSession(ReaderFixture("出处原文", emptyList(), emptyList()), 1200, remote, readOnly = true)
        session.openRemoteAt(citation.document, citation.pdfPage, citation.text.takeIf { citation.isBlock })
        require(remote.current != null) { "出处原文无法打开" }
        remote.show(citation.pdfPage)
        require(remote.page is PageState.Ready) { "出处原文当前不可用" }
        session.onBack = ::back; session.backLabel = "返回这一题"
        activeCitation = citation; opened = session
    }
    fun back() { opened = null; activeCitation = null }
    fun contentDrawn() {
        if (clickToDrawMs == null && opened?.remote?.page is PageState.Ready)
            clickToDrawMs = (System.nanoTime() - clickedAt) / 1_000_000
    }
    private suspend fun guarded(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        try { action() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "暂时无法打开出处" }
        finally { busy = false }
    }
}

/** Actual ReaderWindow host entry: HTTP question → explanation → checked source → the existing reader. */
@Composable
fun ReaderExplanationWorkspace(session: DesktopExplanationSession) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(session) { session.load() }
    val opened = session.opened
    if (opened != null) {
        Column(Modifier.fillMaxSize()) {
            Text(if (session.activeCitation?.isBlock == true) "对应原文段落" else "这一段的位置无法确认，显示已核对的整页。",
                Modifier.padding(8.dp).testTag("citation-mode"))
            Box(Modifier.weight(1f).drawWithContent { drawContent(); session.contentDrawn() }) { ReaderWindow(opened) }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text((session.question?.get("stem") as? JsonPrimitive)?.contentOrNull ?: "正在读取题目", Modifier.testTag("explanation-question"))
            session.options.forEachIndexed { i, option ->
                Row {
                    RadioButton(session.choice == i, { session.choose(i) }, Modifier.testTag("explanation-choice-$i"))
                    Text(option)
                }
            }
            OutlinedTextField(session.draft, session::edit, label = { Text("还想问什么") }, modifier = Modifier.fillMaxWidth().testTag("explanation-draft"))
            Button({ scope.launch { session.explain() } }, enabled = !session.busy && session.question != null && session.draft.isNotBlank(), modifier = Modifier.testTag("explanation-ask")) { Text("查看讲解") }
            Text(session.explanation, Modifier.testTag("explanation-text"))
            session.citations.forEachIndexed { index, citation ->
                Button({ scope.launch { session.open(citation) } }, enabled = !session.busy, modifier = Modifier.testTag("explanation-source-$index")) { Text("PDF 第 ${citation.pdfPage} 页") }
            }
            session.error?.let { Text(it, Modifier.testTag("explanation-error")) }
        }
    }
}
