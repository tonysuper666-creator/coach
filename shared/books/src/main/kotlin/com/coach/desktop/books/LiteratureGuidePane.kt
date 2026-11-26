package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.ui.v7.PaperButton
import com.coach.ui.v7.PrimaryButton
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable
internal fun LiteratureGuidePane(
    detail: CourseDetailSnapshot, library: TextbookLibrary, explainer: BookExplainer,
    tab: Int, onTab: (Int) -> Unit, onBack: () -> Unit, onSource: (GuideExcerpt) -> Unit,
) {
    val materials = detail.materials.filter { it.format.equals("pdf", true) }
    val scope = rememberCoroutineScope()
    var selected by remember(detail.courseId) { mutableStateOf(materials.takeIf { it.size in 3..5 }?.map { it.bookId }.orEmpty()) }
    var questions by remember(detail.courseId) { mutableStateOf("") }
    var guide by remember(detail.courseId) { mutableStateOf<LiteratureGuide?>(null) }
    var busy by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf("") }
    val enabled = LocalReadingAiExplain.current
    val consent = remember(library) { ExplainConsent(library.directory) }

    suspend fun open(ids: List<String>, prompts: List<String>, generate: Boolean) {
        try {
            val books = withContext(Dispatchers.IO) { ids.map(library::literatureBook) }
            var input = withContext(Dispatchers.Default) { LiteratureInput(books, prompts) }
            val key = LiteratureGuideStore.digest(input.request())
            suspend fun nextRevision(rejected: Int? = null) = withContext(Dispatchers.IO) {
                library.withCourseDatabase { LiteratureGuideStore.revision(it, detail.courseId, key, rejected) }
            }
            val revision = nextRevision()
            if (revision != 0) input = withContext(Dispatchers.Default) { LiteratureInput(books, prompts, revision) }
            val cached = withContext(Dispatchers.IO) {
                library.withCourseDatabase { LiteratureGuideStore.cached(it, detail.courseId, key, revision) }
            }
            if (cached != null) {
                val checked = runCatching { input.verified(cached) }.getOrNull()
                if (checked != null) {
                    guide = checked
                    if (generate) withContext(Dispatchers.IO) {
                        library.withCourseDatabase { LiteratureGuideStore.save(it, detail.courseId, key, input, null) }
                    }
                    return
                }
                val next = nextRevision(revision)
                input = withContext(Dispatchers.Default) { LiteratureInput(books, prompts, next) }
            }
            if (!generate) return
            check(enabled && ids.all(consent::allowed)) { "请先在阅读设置中开启 AI 讲解。" }
            explainer.load()
            val paired = explainer.identity
            withContext(Dispatchers.IO) {
                library.withCourseDatabase { LiteratureGuideStore.save(it, detail.courseId, key, input, null) }
            }
            var repaired = false
            var submission = input.request()
            while (true) {
                check(explainer.identity == paired) { "连接已更换，请重新打开导读。" }
                check(ids.all(consent::allowed)) { "AI 讲解已关闭。" }
                val response = explainer.courseTopicPack(submission)
                val result = response.body ?: error(response.error ?: "连接失败，请重试。")
                if ((result["pending"] as? JsonPrimitive)?.booleanOrNull != true) {
                    val verified = runCatching { input.verified(result) }.getOrNull()
                    if (verified == null) {
                        val next = nextRevision(input.revision)
                        check(!repaired) { "重选正文后仍缺少可核对的答案，请检查文献或调整思考题。" }
                        input = withContext(Dispatchers.Default) { LiteratureInput(books, prompts, next) }
                        submission = input.request()
                        repaired = true
                        notice = "上一份导读缺少正文依据，正在重选片段。"
                        continue
                    }
                    withContext(Dispatchers.IO) {
                        library.withCourseDatabase { LiteratureGuideStore.save(it, detail.courseId, key, input, result) }
                    }
                    guide = verified
                    notice = ""
                    break
                }
                notice = "批处理正在生成。可以离开，回来后用同一组材料接着查看。"
                delay((result["retryAfterMs"] as? JsonPrimitive)?.longOrNull?.coerceIn(1000, 60000) ?: 60000)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { notice = e.message ?: "导读暂未完成，请重试。" }
        finally { busy = false }
    }

    LaunchedEffect(detail.courseId) {
        try {
            val last = withContext(Dispatchers.IO) {
                library.withCourseDatabase { LiteratureGuideStore.latest(it, detail.courseId) }
            }
            if (last != null && last.books.all { id -> materials.any { it.bookId == id } }) {
                selected = last.books
                questions = last.questions.joinToString("\n")
                open(last.books, last.questions, false)
            }
        } finally { busy = false }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        val columns = if (maxWidth >= 800.dp) 2 else 1
        Column(Modifier.widthIn(max = 920.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            V7TextButton(onBack) { Text("← 课程") }
            val current = guide
            if (current == null) {
                Text("本周文献导读", style = MaterialTheme.typography.headlineSmall)
                Text("选 3–5 篇 PDF。生成时会将选中文献的片段和思考题发送给学习服务。")
                materials.forEach { m ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(m.bookId in selected, { add ->
                            selected = if (add) selected + m.bookId else selected - m.bookId
                        }, enabled = !busy && (m.bookId in selected || selected.size < 5))
                        Text(m.title)
                    }
                }
                OutlinedTextField(questions, { questions = it }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("guide-questions"),
                    label = { Text("老师的思考题，每行一题") })
                val prompts = questions.lines().map(String::trim).filter(String::isNotEmpty)
                PrimaryButton({
                    busy = true
                    notice = "正在核对材料…"
                    scope.launch { open(selected, prompts, true) }
                }, modifier = Modifier.testTag("guide-generate"), enabled = !busy && selected.size in 3..5 && prompts.size in 1..7 && prompts.all { it.length <= 400 }) {
                    Text("生成或打开导读")
                }
                if (prompts.size > 7) Text("每个思考题至少配两道自测题，一次最多放 7 个思考题。")
                if (materials.size < 3) Text("请先回课程页补充 PDF 材料。")
                if (notice.isNotEmpty()) Text(notice)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("一页纸", "跟读路线", "自测").forEachIndexed { i, title ->
                        V7TextButton({ onTab(i) }, Modifier.testTag("guide-tab-$i")) { Text(if (tab == i) "● $title" else title) }
                    }
                    V7TextButton({ guide = null; notice = ""; onTab(0) }) { Text("换一组材料") }
                }
                when (tab) {
                    0 -> {
                        Text(current.question, style = MaterialTheme.typography.titleLarge)
                        current.papers.chunked(columns).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                pair.forEach { paper ->
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        HorizontalDivider()
                                        Text("${paper.source.book.title} · ${paper.keyword}", style = MaterialTheme.typography.titleSmall)
                                        Text(paper.claim)
                                        GuideSource(paper.source, onSource)
                                    }
                                }
                                if (pair.size < columns) Spacer(Modifier.weight(1f))
                            }
                        }
                        Text(current.thread, style = MaterialTheme.typography.titleMedium)
                        current.answers.forEach { (q, answer) ->
                            Text(q, style = MaterialTheme.typography.titleSmall)
                            Text(answer)
                        }
                    }
                    1 -> current.stops.groupBy { it.source.book.title to it.source.book.id }.forEach { (paper, stops) ->
                        Text(paper.first, style = MaterialTheme.typography.titleMedium)
                        stops.forEach {
                            Text(it.title)
                            GuideSource(it.source, onSource)
                        }
                    }
                    else -> LiteratureSelfTest(current, library, explainer, consent, onSource)
                }
            }
        }
    }
}

@Composable
private fun GuideSource(source: GuideExcerpt, onSource: (GuideExcerpt) -> Unit) {
    V7TextButton({ onSource(source) }, modifier = Modifier.testTag("guide-source-${source.anchor.pdfPage}")) {
        val printed = source.region.printedPage?.let { "印刷第 $it 页" } ?: "印刷页码未标出"
        Text("PDF 第 ${source.anchor.pdfPage} 页 · $printed　“${source.anchor.quoteAnchor}”", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LiteratureSelfTest(guide: LiteratureGuide, library: TextbookLibrary, explainer: BookExplainer, consent: ExplainConsent,
    onSource: (GuideExcerpt) -> Unit) {
    var index by remember(guide) { mutableStateOf(0) }
    val answers = remember(guide) { mutableStateMapOf<Int, String>() }
    var loading by remember(guide) { mutableStateOf(true) }
    LaunchedEffect(guide) {
        val saved = withContext(Dispatchers.IO) {
            guide.quiz.mapIndexedNotNull { i, q ->
                library.answer(q.source.book.id, q.item.id, QuestionMode.CHOICE).takeIf { it.revealed }?.let { i to it.text }
            }
        }
        answers.putAll(saved)
        loading = false
    }
    val diagnoses = remember(guide) { mutableStateMapOf<Int, String>() }
    val question = guide.quiz[index]
    val item = question.item
    val choice = answers[index]
    val scope = rememberCoroutineScope()
    var saveError by remember { mutableStateOf("") }
    Text("第 ${index + 1} 题 / ${guide.quiz.size}", style = MaterialTheme.typography.labelLarge)
    Text(item.stem, style = MaterialTheme.typography.titleMedium)
    item.options.forEach { option ->
        PaperButton({
            val pos = index
            loading = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        library.saveSectionCheckAnswer(question.source.book.id, question.source.section, pos,
                            Practice(item.stem, question.source.anchor.quoteAnchor, item.correctOption, item.options),
                            PracticeAnswer(text = option, revealed = true, understood = option == item.correctOption),
                            questionKey = item.id)
                    }
                    answers[pos] = option
                    saveError = ""
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { saveError = "答案未保存，请重试。" }
                finally { loading = false }
            }
        }, Modifier.testTag("guide-option-${item.options.indexOf(option)}"), enabled = choice == null && !loading) { Text(option) }
    }
    if (saveError.isNotEmpty()) Text(saveError)
    if (choice != null) {
        Text(if (choice == item.correctOption) "答对了" else "正确答案 · ${item.correctOption}")
        Text(item.explanation, Modifier.testTag("guide-reasons"))
        GuideSource(question.source, onSource)
        if (choice != item.correctOption && LocalReadingAiExplain.current && consent.allowed(question.source.book.id)) {
            val pos = index
            V7TextButton({
                diagnoses[pos] = "正在核对…"
                scope.launch {
                    try {
                        explainer.load()
                        val request = PracticeGradingJson.buildSingleBookRequest(question.source.book, question.source.section,
                            item, choice, "DIAGNOSE_ONLY", choice)
                        diagnoses[pos] = when (val reply = explainer.gradeAndDiagnosePractice(request)) {
                            is PracticeGradeDiagnoseResult.Ready -> reply.response.diagnosis?.diagnosisZh ?: reply.response.summaryMetaLine
                            is PracticeGradeDiagnoseResult.Failed -> reply.message
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { diagnoses[pos] = "批改暂时不可用。" }
                }
            }, enabled = diagnoses[pos] != "正在核对…") { Text("请 AI 分析错因") }
            diagnoses[pos]?.let { Text(it) }
        }
    }
    Row {
        V7TextButton({ index-- }, enabled = index > 0) { Text("上一题") }
        V7TextButton({ index++ }, enabled = index < guide.quiz.lastIndex) { Text("下一题") }
    }
}
