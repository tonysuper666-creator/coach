package com.coach.desktop.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

@Composable
internal fun LiteratureComparisonPane(
    detail: CourseDetailSnapshot,
    library: TextbookLibrary,
    explainer: BookExplainer,
    onBack: () -> Unit,
    onSource: (GuideExcerpt) -> Unit,
) {
    val pdfs = detail.materials.filter { it.format.equals("pdf", true) }
    val weeks = pdfs.mapNotNull { it.weekNum }.distinct().sorted()
    val scope = rememberCoroutineScope()
    var selected by remember(detail.courseId) { mutableStateOf<List<String>>(emptyList()) }
    var table by remember(detail.courseId) { mutableStateOf<LiteratureComparison?>(null) }
    var busy by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf("") }
    var markdown by remember { mutableStateOf("") }
    val enabled = LocalReadingAiExplain.current
    val consent = remember(library) { ExplainConsent(library.directory) }

    fun persist(next: LiteratureComparison, bookIds: List<String>) {
        scope.launch(Dispatchers.IO) {
            library.withCourseDatabase { LiteratureComparisonStore.save(it, detail.courseId, bookIds, next) }
        }
    }

    fun applyEdit(transform: (LiteratureComparison) -> LiteratureComparison) {
        val latest = table ?: return
        val next = transform(latest)
        table = next
        persist(next, next.rows.map { it.bookId })
    }

    suspend fun pollModel(submission: kotlinx.serialization.json.JsonObject, ids: List<String>, paired: Any?): kotlinx.serialization.json.JsonObject {
        while (true) {
            check(explainer.identity == paired) { "连接已更换，请重新打开对比表。" }
            check(ids.all(consent::allowed)) { "AI 讲解已关闭。" }
            val response = explainer.courseTopicPack(submission)
            val result = response.body ?: error(response.error ?: "连接失败，请重试。")
            if ((result["pending"] as? JsonPrimitive)?.booleanOrNull != true) return result
            notice = "批处理正在生成。可以离开，回来后用同一组文献接着查看。"
            delay((result["retryAfterMs"] as? JsonPrimitive)?.longOrNull?.coerceIn(1000, 60000) ?: 60000)
        }
    }

    suspend fun open(ids: List<String>, generate: Boolean) {
        if (ids.size < COMPARISON_MIN_PAPERS) {
            table = null
            markdown = ""
            notice = comparisonRefusal(ids.size)
            busy = false
            return
        }
        if (ids.size > COMPARISON_MAX_PAPERS) {
            table = null
            markdown = ""
            notice = comparisonTooMany(ids.size)
            busy = false
            return
        }
        try {
            val books = withContext(Dispatchers.IO) { ids.map(library::literatureBook) }
            val input = withContext(Dispatchers.Default) { LiteratureComparisonInput(books) }
            if (!generate) {
                val key = LiteratureComparisonStore.digest(ids)
                val cached = withContext(Dispatchers.IO) {
                    library.withCourseDatabase { LiteratureComparisonStore.load(it, detail.courseId, key) }
                }
                val restored = cached?.stored?.let { LiteratureComparisonInput.restore(it, books) }
                if (restored != null) {
                    table = restored
                    markdown = ""
                    notice = "这是上次存在本机的对比表，没有重新生成。"
                    return
                }
                notice = if (cached?.stored != null) "上次的对比表对不上现在的原文，需要重新生成。" else ""
                return
            }
            check(enabled && ids.all(consent::allowed)) { "请先在阅读设置中开启 AI 讲解。" }
            explainer.load()
            val paired = explainer.identity
            notice = "正在生成对比表…"
            val reply = pollModel(input.request(), ids, paired)
            notice = "正在用本地 OCR 核对出处…"
            val outcome = withContext(Dispatchers.IO) { input.verify(reply, guideOcr(library)) }
            val built = outcome.table
            if (built == null) {
                table = null
                markdown = ""
                notice = outcome.notice
                return
            }
            withContext(Dispatchers.IO) {
                library.withCourseDatabase { LiteratureComparisonStore.save(it, detail.courseId, ids, built) }
            }
            table = built
            markdown = ""
            notice = outcome.notice
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            table = null
            notice = e.message ?: "对比表暂未完成，请重试。"
        } finally {
            busy = false
        }
    }

    LaunchedEffect(detail.courseId, pdfs.map { it.bookId }) {
        try {
            val last = withContext(Dispatchers.IO) {
                library.withCourseDatabase { LiteratureComparisonStore.latest(it, detail.courseId) }
            }
            if (last != null && last.bookIds.all { id -> pdfs.any { it.bookId == id } }) {
                selected = last.bookIds
                open(last.bookIds, generate = false)
            }
        } finally {
            busy = false
        }
    }

    val reason = when {
        pdfs.size < COMPARISON_MIN_PAPERS -> comparisonRefusal(pdfs.size)
        selected.size > COMPARISON_MAX_PAPERS -> comparisonTooMany(selected.size)
        selected.isNotEmpty() && selected.size < COMPARISON_MIN_PAPERS -> comparisonRefusal(selected.size)
        else -> ""
    }

    Column(
        Modifier.widthIn(max = 980.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            V7TextButton(onBack) { Text("← 课程") }
            if (table != null) {
                V7TextButton({
                    table = null
                    markdown = ""
                    notice = ""
                }) { Text("换一组文献") }
                V7TextButton(
                    onClick = {
                        val ids = table?.rows?.map { it.bookId }.orEmpty()
                        busy = true
                        notice = ""
                        markdown = ""
                        scope.launch { open(ids, generate = true) }
                    },
                    modifier = Modifier.testTag("comparison-regenerate"),
                    enabled = !busy,
                ) { Text("重新生成") }
            }
        }
        val current = table
        if (current == null) {
            Text("文献对比", style = MaterialTheme.typography.headlineSmall)
            Text("选一周的阅读，或勾选至少两篇 PDF。软件先排对比表和综述提纲，每一格的原句从文献里取，不由模型抄写。")
            if (weeks.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    weeks.forEach { week ->
                        val ids = pdfs.filter { it.weekNum == week }.map { it.bookId }
                        V7TextButton(
                            onClick = { selected = ids },
                            modifier = Modifier.testTag("comparison-week-$week"),
                        ) { Text("第 $week 周") }
                    }
                }
            }
            pdfs.forEach { material ->
                val checked = material.bookId in selected
                Row(
                    Modifier.fillMaxWidth().testTag("comparison-paper-${material.bookId}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { on ->
                            selected = if (on) selected + material.bookId else selected - material.bookId
                        },
                    )
                    Text(material.title, style = MaterialTheme.typography.bodyMedium)
                }
            }
            PrimaryButton(
                onClick = {
                    busy = true
                    notice = ""
                    markdown = ""
                    scope.launch { open(selected, generate = true) }
                },
                modifier = Modifier.testTag("comparison-generate"),
                enabled = !busy && selected.size in COMPARISON_MIN_PAPERS..COMPARISON_MAX_PAPERS,
            ) { Text("生成对比表") }
            val shown = notice.ifBlank { reason }
            if (shown.isNotEmpty()) Text(shown, modifier = Modifier.testTag("comparison-notice"))
        } else {
            Text("文献对比", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("comparison-table"))
            if (notice.isNotEmpty()) Text(notice, modifier = Modifier.testTag("comparison-notice"))
            current.rows.forEach { row ->
                Column(Modifier.fillMaxWidth().testTag("comparison-row-${row.bookId}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider()
                    Text(row.label(), style = MaterialTheme.typography.titleSmall)
                    if (row.title.isNotBlank() && row.label() != row.title) {
                        Text(row.title, style = MaterialTheme.typography.labelSmall)
                    }
                    CellEditor("主张", row.claim, "comparison-claim-${row.bookId}", "comparison-source-${row.bookId}-claim", onSource) { text ->
                        applyEdit { it.editClaim(row.bookId, text) }
                    }
                    CellEditor("方法 / 证据", row.method, "comparison-method-${row.bookId}", "comparison-source-${row.bookId}-method", onSource) { text ->
                        applyEdit { it.editMethod(row.bookId, text) }
                    }
                    Text("关键概念", style = MaterialTheme.typography.labelMedium)
                    if (row.concepts.isEmpty()) Text(COMPARISON_PENDING)
                    row.concepts.forEachIndexed { index, cell ->
                        CellEditor(
                            label = "概念 ${index + 1}",
                            cell = cell,
                            fieldTag = "comparison-concept-${row.bookId}-$index",
                            sourceTag = "comparison-source-${row.bookId}-concept-$index",
                            onSource = onSource,
                        ) { text ->
                            applyEdit { it.editConcept(row.bookId, index, text) }
                        }
                    }
                    Text("与其他几篇的关系", style = MaterialTheme.typography.labelMedium)
                    if (row.relations.isEmpty()) Text(COMPARISON_PENDING)
                    row.relations.forEachIndexed { index, relation ->
                        val kind = relation.kind?.zh()?.let { "$it " }.orEmpty()
                        CellEditor(
                            label = "$kind${relation.otherLabel}",
                            cell = relation.cell,
                            fieldTag = "comparison-relation-${row.bookId}-$index",
                            sourceTag = "comparison-source-${row.bookId}-relation-$index",
                            onSource = onSource,
                        ) { text ->
                            applyEdit { it.editRelation(row.bookId, index, text) }
                        }
                    }
                }
            }
            Text("综述提纲", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("comparison-outline"))
            if (current.outline.isEmpty()) Text("没有可用论点，没有硬补。")
            current.outline.forEachIndexed { index, point ->
                OutlinedTextField(
                    value = point.thesis,
                    onValueChange = { text -> applyEdit { it.editThesis(index, text) } },
                    modifier = Modifier.fillMaxWidth().testTag("comparison-thesis-$index"),
                    label = { Text("论点 ${index + 1}") },
                )
                if (point.supports.isEmpty()) Text("支持：没有列出")
                point.supports.forEach { side -> SideLine("支持", side, onSource) }
                if (point.opposes.isEmpty()) Text("反对：没有列出")
                point.opposes.forEach { side -> SideLine("反对", side, onSource) }
            }
            V7TextButton(
                onClick = {
                    val text = current.toMarkdown()
                    markdown = text
                    scope.launch(Dispatchers.IO) {
                        val dir = library.directory.resolve("literature-comparisons")
                        Files.createDirectories(dir)
                        val name = LiteratureComparisonStore.digest(current.rows.map { it.bookId }).take(12) + ".md"
                        Files.writeString(dir.resolve(name), text, Charsets.UTF_8)
                    }
                },
                modifier = Modifier.testTag("comparison-export"),
            ) { Text("导出 Markdown") }
            if (markdown.isNotEmpty()) {
                Text(markdown, modifier = Modifier.testTag("comparison-markdown"))
            }
        }
    }
}

@Composable
private fun CellEditor(
    label: String,
    cell: ComparisonCell,
    fieldTag: String,
    sourceTag: String,
    onSource: (GuideExcerpt) -> Unit,
    onEdit: (String) -> Unit,
) {
    OutlinedTextField(
        value = cell.text,
        onValueChange = onEdit,
        modifier = Modifier.fillMaxWidth().testTag(fieldTag),
        label = { Text(label) },
    )
    val source = cell.source
    if (cell.pending || source == null) {
        Text(COMPARISON_PENDING, style = MaterialTheme.typography.bodySmall)
    } else {
        V7TextButton(onClick = { onSource(source) }, modifier = Modifier.testTag(sourceTag)) {
            Text("PDF 第 ${source.anchor.pdfPage} 页　“${source.anchor.quoteAnchor}”", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SideLine(role: String, side: OutlineSide, onSource: (GuideExcerpt) -> Unit) {
    val source = side.source
    if (side.pending || source == null) {
        Text("$role：${side.label}（$COMPARISON_PENDING）", style = MaterialTheme.typography.bodySmall)
    } else {
        V7TextButton(
            onClick = { onSource(source) },
            modifier = Modifier.testTag("comparison-outline-${side.bookId}-${source.anchor.pdfPage}"),
        ) {
            Text("$role：${side.label}　PDF 第 ${source.anchor.pdfPage} 页　“${source.anchor.quoteAnchor}”", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun guideOcr(library: TextbookLibrary) = CachingOcrVerifier(object : LiteratureOcrVerifier {
    override fun ocrPageText(book: Textbook, pdfPage: Int): String = library.ocrPdfPageForGuide(book.id, pdfPage)
    override fun ocrQuoteCropText(book: Textbook, pdfPage: Int, quote: String): String? =
        library.ocrPdfQuoteCropForGuide(book.id, pdfPage, quote)
})
