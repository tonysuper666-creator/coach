package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable
internal fun LiteratureGuidePane(
    detail: CourseDetailSnapshot,
    library: TextbookLibrary,
    explainer: BookExplainer,
    onBack: () -> Unit,
    onSource: (GuideExcerpt) -> Unit,
) {
    val materials = detail.materials.filter { it.format.equals("pdf", true) || it.format.equals("epub", true) }
    val scope = rememberCoroutineScope()
    var selected by remember(detail.courseId) { mutableStateOf(materials.takeIf { it.size in 3..5 }?.map { it.bookId }.orEmpty()) }
    val paperMetas = remember(detail.courseId) { mutableStateMapOf<String, LiteraturePaperMeta>() }
    var questions by remember(detail.courseId) { mutableStateOf("") }
    var guide by remember(detail.courseId) { mutableStateOf<LiteratureGuide?>(null) }
    var failures by remember(detail.courseId) { mutableStateOf<List<GuideItemFailure>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf("") }
    val enabled = LocalReadingAiExplain.current
    val consent = remember(library) { ExplainConsent(library.directory) }

    suspend fun pollModel(submission: JsonObject, ids: List<String>, paired: Any?): JsonObject {
        while (true) {
            check(explainer.identity == paired) { "连接已更换，请重新打开导读。" }
            check(ids.all(consent::allowed)) { "AI 讲解已关闭。" }
            val response = explainer.courseTopicPack(submission)
            val result = response.body ?: error(response.error ?: "连接失败，请重试。")
            if ((result["pending"] as? JsonPrimitive)?.booleanOrNull != true) return result
            notice = "批处理正在生成。可以离开，回来后用同一组材料接着查看。"
            delay((result["retryAfterMs"] as? JsonPrimitive)?.longOrNull?.coerceIn(1000, 60000) ?: 60000)
        }
    }

    suspend fun open(ids: List<String>, prompts: List<String>, metaSnapshot: Map<String, LiteraturePaperMeta>, generate: Boolean) {
        try {
            failures = emptyList()
            val books = withContext(Dispatchers.IO) { ids.map(library::literatureBook) }
            val input = withContext(Dispatchers.Default) { LiteratureInput(books, prompts, metaSnapshot) }
            val key = LiteratureGuideStore.digest(input.request())
            val cached = withContext(Dispatchers.IO) {
                library.withCourseDatabase { LiteratureGuideStore.cached(it, detail.courseId, key) }
            }
            if (cached != null) {
                val restored = LiteratureInput.restoreStored(cached, books, prompts)
                if (restored != null) {
                    guide = restored
                    notice = ""
                    return
                }
            }
            if (!generate) return
            check(enabled && ids.all(consent::allowed)) { "请先在阅读设置中开启 AI 讲解。" }
            explainer.load()
            val paired = explainer.identity
            withContext(Dispatchers.IO) {
                library.withCourseDatabase { LiteratureGuideStore.saveSelection(it, detail.courseId, key, input) }
            }

            val ocr = CachingOcrVerifier(object : LiteratureOcrVerifier {
                // EPUB 是文字版，不需要 OCR：用已清洗的页文本核对引文。
                override fun ocrPageText(book: Textbook, pdfPage: Int): String =
                    if (book.format == "epub") book.page(pdfPage)?.text.orEmpty()
                    else library.ocrPdfPageForGuide(book.id, pdfPage)
                override fun ocrQuoteCropText(book: Textbook, pdfPage: Int, quote: String): String? =
                    if (book.format == "epub") null
                    else library.ocrPdfQuoteCropForGuide(book.id, pdfPage, quote)
            })
            val passes = mutableListOf<GuidePassDiagnostic>()

            val firstReq = input.request()
            notice = "正在生成一页纸…"
            val firstReply = pollModel(firstReq, ids, paired)
            notice = "正在用本地 OCR 独立核对引文…"
            val firstCheck = withContext(Dispatchers.IO) { input.verify(firstReply, ocr) }
            passes += GuidePassDiagnostic(
                pass = 1,
                mode = "initial",
                requestBytes = firstReq.toString().toByteArray().size,
                candidateCount = input.excerpts.size,
                rawReply = firstReply,
                verification = firstCheck,
            )

            var finalCheck = firstCheck
            var completed = firstCheck.toComplete(books, prompts)
            if (completed == null) {
                val repairInput = withContext(Dispatchers.Default) { input.buildRepair(firstCheck) }
                val repairReq = repairInput.request()
                notice = "有 ${firstCheck.failures.size} 项未通过核验，正在定向补问（最多 1 次）…"
                val repairReply = pollModel(repairReq, ids, paired)
                notice = "正在用本地 OCR 复核补问引文…"
                val (pass2Check, merged) = withContext(Dispatchers.IO) {
                    repairInput.verifyAndMerge(firstCheck, repairReply, ocr)
                }
                passes += GuidePassDiagnostic(
                    pass = 2,
                    mode = "repair",
                    requestBytes = repairReq.toString().toByteArray().size,
                    candidateCount = repairInput.excerpts.size,
                    rawReply = repairReply,
                    verification = pass2Check,
                )
                finalCheck = merged
                completed = merged.toComplete(books, prompts)
            }

            withContext(Dispatchers.IO) {
                LiteratureDiagnostics.write(
                    libraryDir = library.directory,
                    courseId = detail.courseId,
                    digest = key,
                    passes = passes,
                    finalVerification = finalCheck,
                    complete = completed != null,
                )
            }

            if (completed != null) {
                val stored = LiteratureInput.toStoredJson(completed)
                withContext(Dispatchers.IO) {
                    library.withCourseDatabase { LiteratureGuideStore.saveComplete(it, detail.courseId, key, input, stored) }
                }
                guide = completed
                failures = emptyList()
                notice = ""
            } else {
                guide = null
                failures = finalCheck.failures
                notice = "补问后仍有 ${finalCheck.failures.size} 项未通过核验，未存成完成的一页纸。诊断记录已写入本机。"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice = e.message ?: "导读暂未完成，请重试。"
        } finally {
            busy = false
        }
    }

    LaunchedEffect(detail.courseId, materials.map { it.bookId }) {
        try {
            val prefilled = withContext(Dispatchers.IO) {
                materials.associate { m -> m.bookId to library.literaturePaperMeta(m.bookId) }
            }
            prefilled.forEach { (id, meta) -> if (id !in paperMetas) paperMetas[id] = meta }
            val last = withContext(Dispatchers.IO) {
                library.withCourseDatabase { LiteratureGuideStore.latest(it, detail.courseId) }
            }
            if (last != null && last.books.all { id -> materials.any { it.bookId == id } }) {
                selected = last.books
                questions = last.questions.joinToString("\n")
                last.metas.forEach { (id, meta) -> paperMetas[id] = meta }
                open(last.books, last.questions, paperMetas.toMap(), generate = false)
            } else if (last != null) {
                // Some of the chosen files left the course; keep what the user wrote and the files still here.
                if (questions.isBlank()) questions = last.questions.joinToString("\n")
                last.books.filter { id -> materials.any { it.bookId == id } }.takeIf { it.isNotEmpty() }?.let { selected = it }
                last.metas.forEach { (id, meta) -> paperMetas[id] = meta }
            }
        } finally {
            busy = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val columns = if (maxWidth >= 800.dp) 2 else 1
        Column(
            Modifier.widthIn(max = 920.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                V7TextButton(onBack) { Text("← 课程") }
                if (guide != null) {
                    V7TextButton({ guide = null; failures = emptyList(); notice = "" }) { Text("换一组材料") }
                }
            }
            val current = guide
            if (current == null) {
                Text("本周文献一页纸", style = MaterialTheme.typography.headlineSmall)
                Text("选 3–5 份 PDF 或 EPUB，核对作者、年份与标题后生成。只将选中文献的片段和思考题发送给学习服务。")
                materials.forEach { m ->
                    val checked = m.bookId in selected
                    val meta = paperMetas[m.bookId] ?: LiteraturePaperMeta(
                        m.bookId,
                        LiteratureTextNorm.decodeHtmlEntities(m.title),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { add ->
                                    selected = if (add) selected + m.bookId else selected - m.bookId
                                },
                                enabled = !busy && (checked || selected.size < 5),
                            )
                            Text(meta.title.ifBlank { m.title }, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (checked) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 40.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = meta.author,
                                    onValueChange = { paperMetas[m.bookId] = meta.copy(author = it) },
                                    enabled = !busy,
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).testTag("guide-meta-author-${m.bookId}"),
                                    label = { Text("作者") },
                                )
                                OutlinedTextField(
                                    value = meta.year,
                                    onValueChange = { paperMetas[m.bookId] = meta.copy(year = it) },
                                    enabled = !busy,
                                    singleLine = true,
                                    modifier = Modifier.width(104.dp).testTag("guide-meta-year-${m.bookId}"),
                                    label = { Text("年份") },
                                )
                                OutlinedTextField(
                                    value = meta.title,
                                    onValueChange = { paperMetas[m.bookId] = meta.copy(title = it) },
                                    enabled = !busy,
                                    singleLine = true,
                                    modifier = Modifier.weight(1.6f).testTag("guide-meta-title-${m.bookId}"),
                                    label = { Text("标题") },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = questions,
                    onValueChange = { questions = it },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("guide-questions"),
                    label = { Text("老师的思考题，每行一题") },
                )
                val prompts = questions.lines().map(String::trim).filter(String::isNotEmpty)
                PrimaryButton(
                    onClick = {
                        busy = true
                        notice = "正在核对材料…"
                        val snapshot = paperMetas.toMap()
                        scope.launch { open(selected, prompts, snapshot, generate = true) }
                    },
                    modifier = Modifier.testTag("guide-generate"),
                    enabled = !busy && selected.size in 3..5 && prompts.size in 1..7 && prompts.all { it.length <= 400 },
                ) {
                    Text("生成或打开一页纸")
                }
                if (prompts.size > 7) Text("一次最多放 7 个思考题。")
                if (materials.size < 3) Text("请先回课程页补充 PDF 或 EPUB 材料。")
                if (notice.isNotEmpty()) Text(notice, modifier = Modifier.testTag("guide-notice"))
                if (failures.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().testTag("guide-failures").padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        failures.forEach { f ->
                            Text("• ${f.label}：${f.detail}（${f.rule}）", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().testTag("guide-one-page"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(current.question, style = MaterialTheme.typography.titleLarge)
                    current.papers.chunked(columns).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            pair.forEach { paper ->
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    HorizontalDivider()
                                    val headerPrefix = buildString {
                                        if (paper.author.isNotBlank()) {
                                            append(paper.author)
                                            if (paper.year.isNotBlank()) append(" (${paper.year})")
                                        } else {
                                            append(paper.title)
                                            if (paper.year.isNotBlank()) append(" (${paper.year})")
                                        }
                                    }
                                    Text("$headerPrefix · ${paper.keyword}", style = MaterialTheme.typography.titleSmall)
                                    if (paper.author.isNotBlank() && paper.title.isNotBlank()) {
                                        Text(paper.title, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text(paper.claim, style = MaterialTheme.typography.bodyMedium)
                                    GuideSource(paper.source, showQuote = true, onSource = onSource)
                                }
                            }
                            if (pair.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                    HorizontalDivider()
                    Text(current.thread, style = MaterialTheme.typography.titleMedium)
                    val metaByBook = current.papers.associateBy { it.bookId }
                    current.answers.forEach { answer ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(answer.question, style = MaterialTheme.typography.titleSmall)
                            Text(answer.sentences.joinToString(" ") { it.text }, style = MaterialTheme.typography.bodyMedium)
                            val distinctSources = answer.sentences.flatMap { it.sources }
                                .distinctBy { it.book.id to it.anchor.pdfPage }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                distinctSources.forEach { src ->
                                    val paper = metaByBook[src.book.id]
                                    val tag = paper?.author?.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty()
                                    V7TextButton(
                                        onClick = { onSource(src) },
                                        modifier = Modifier.testTag("guide-answer-source-${src.book.id}-${src.anchor.pdfPage}"),
                                    ) {
                                        Text("${tag}${if (src.book.format == "epub") "" else "PDF "}第 ${src.anchor.pdfPage} 页", style = MaterialTheme.typography.labelSmall)
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

@Composable
private fun GuideSource(source: GuideExcerpt, showQuote: Boolean, onSource: (GuideExcerpt) -> Unit) {
    V7TextButton(
        onClick = { onSource(source) },
        modifier = Modifier.testTag("guide-source-${source.anchor.pdfPage}"),
    ) {
        val label = if (showQuote) "${if (source.book.format == "epub") "" else "PDF "}第 ${source.anchor.pdfPage} 页　“${source.anchor.quoteAnchor}”"
        else "${if (source.book.format == "epub") "" else "PDF "}第 ${source.anchor.pdfPage} 页"
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
