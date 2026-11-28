package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.protocol.BookStance
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

internal fun explanationScope(first: Int, last: Int, title: String): String {
    val pages = if (first == last) "$first" else "$first–$last"
    return "讲的是：第 $pages 页 · $title"
}

private val LEADING_CAPTION_LINE_RE = Regex(
    "^(?:(?:fig(?:ure)?\\.?|plate|illustration|photo(?:graph)?|x-?ray|diagram|chart)(?:\\b|(?=[\\s:：.\\-—]|$))|(?:图\\s*\\d+|插图)).*",
    RegexOption.IGNORE_CASE,
)

internal fun pageIllustrationCaptions(book: Textbook, page: Int): Set<String> =
    book.page(page)?.illustrations.orEmpty()
        .mapNotNull { it.caption?.trim()?.takeIf(String::isNotEmpty) }
        .toSet()

internal fun isLeadingImageCaptionBlock(block: SourceBlock, illustrationCaptions: Set<String>): Boolean {
    val trimmed = block.text.trim()
    if (trimmed.isEmpty()) return true
    if (trimmed in illustrationCaptions) return true
    if (trimmed.length <= 220 && LEADING_CAPTION_LINE_RE.matches(trimmed)) return true
    return false
}

internal fun isLeadingCaptionOnlySection(book: Textbook, section: BookLesson): Boolean {
    val effective = section.blocks.filterNot { it.heading }.ifEmpty { section.blocks }
    if (effective.isEmpty()) return false
    return effective.all { blk -> isLeadingImageCaptionBlock(blk, pageIllustrationCaptions(book, blk.page)) }
}

/**
 * 为「只讲这一页 / 当前屏」筛选真正位于当前可见屏的正文块：
 * 1. 若提供了精确可见分片（[visibleRanges]）或页内锚点偏移（[anchorOffsetUtf16]），优先取当前屏起始偏移起的块；
 * 2. 若当前屏开头是同页前序插图/X-Ray 图片说明且其后紧跟正文段（或同页标题之后），跳过纯图注以免抢讲图片；
 * 3. 在上限内按顺序截取，避免超大 EPUB 单页触发 `TOO_LONG`。
 */
internal fun selectScreenExplainBlocks(
    book: Textbook,
    page: Int,
    anchorOffsetUtf16: Int = 0,
    visibleRanges: List<IntRange>? = null,
): List<SourceBlock> {
    val allOnPage = book.sourceBlocks().filter { it.page == page }
    val bodyOnPage = allOnPage.filter { it.isBodyParagraph }
    if (bodyOnPage.isEmpty()) return emptyList()

    // A laid sheet also covers text replaced by illustrations. Only its rendered text
    // runs are eligible. Merge adjacent column slices before clipping a paragraph.
    val ranges = visibleRanges?.sortedBy { it.first }?.fold(mutableListOf<IntRange>()) { merged, range ->
        val last = merged.lastOrNull()
        if (!range.isEmpty()) {
            if (last != null && range.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else merged.add(range)
        }
        merged
    }
    val pageText = book.page(page)?.text ?: return emptyList()
    val screenCandidates = if (ranges == null) {
        bodyOnPage.filter { it.end > anchorOffsetUtf16 }
    } else bodyOnPage.flatMap { block ->
        ranges.mapNotNull { range ->
            val start = maxOf(block.start, range.first, anchorOffsetUtf16)
            val end = minOf(block.end, range.last + 1, pageText.length)
            if (start >= end) return@mapNotNull null
            val text = pageText.substring(start, end)
            if (text.isBlank()) null else block.copy(id = "${book.id}:p$page:$start", start = start, end = end, text = text)
        }
    }
    val captions = pageIllustrationCaptions(book, page)
    val filtered = screenCandidates.dropWhile { isLeadingImageCaptionBlock(it, captions) }.ifEmpty { screenCandidates }

    val bounded = ArrayList<SourceBlock>(minOf(filtered.size, 128))
    var chars = 0L
    for (block in filtered) {
        if (bounded.size >= 128) break
        if (bounded.isNotEmpty() && chars + block.text.length > 24_000L) break
        bounded += block
        chars += block.text.length
    }
    return bounded
}

/** Source intervals painted as text in the current columns, in reading order. */
internal fun BookPagingState.visibleProseSlices(): List<BookPageSlice> {
    val first = index
    return (0 until columns).mapNotNull { pages.getOrNull(first + it) }
        .flatMap { sheet -> sheet.runs.flatMap { it.sources } }
}

internal fun pageExplainCacheKey(page: Int, blocks: List<SourceBlock>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    blocks.forEach { b ->
        digest.update(b.id.toByteArray(Charsets.UTF_8))
        digest.update(':'.code.toByte())
        digest.update(b.text.toByteArray(Charsets.UTF_8))
        digest.update('\n'.code.toByte())
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    return "page-explain:$page:$hash"
}

@Composable
internal fun <T> RetrievedPageLinks(
    sources: List<T>,
    label: (T) -> String,
    onOpen: (T) -> Unit,
) {
    if (sources.isEmpty()) return
    Text("这次没讲成，先看原文", style = MaterialTheme.typography.bodySmall)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sources.forEachIndexed { index, source ->
            V7TextButton({ onOpen(source) }, modifier = Modifier.testTag("explain-fallback-page-$index")) {
                Text(label(source))
            }
        }
    }
}

@Composable
internal fun PageExplanation(
    book: Textbook,
    page: Int,
    explainer: BookExplainer,
    enabled: Boolean,
    onOpen: (SourceBlock) -> Unit,
    onLogin: () -> Unit,
    currentPageOffset: Int = 0,
    visibleRanges: List<IntRange>? = null,
    library: TextbookLibrary? = null,
    onOpenPassage: ((BookPassage) -> Unit)? = null,
) {
    val blocks = remember(book, page, currentPageOffset, visibleRanges) {
        selectScreenExplainBlocks(book, page, currentPageOffset, visibleRanges)
    }
    val stanceResolution = remember(book.id, library) {
        library?.let { BookStanceSources.forLibrary(it).resolve(book) } ?: BookStanceResolution.Undetermined
    }
    val determinedStance = stanceResolution as? BookStanceResolution.Determined
    val bookPages = remember(book.id, book.pages) {
        maxOf(book.pages.size, book.pages.maxOfOrNull { it.number } ?: 1)
    }
    val connectivity = LocalCoachConnectivity.current
    val cacheKey = remember(page, blocks) { pageExplainCacheKey(page, blocks) }
    var attempt by remember(book.id, cacheKey) { mutableIntStateOf(0) }
    val switchedCards = remember(book.id, cacheKey) { mutableStateListOf<Explanation>() }
    var switching by remember(book.id, cacheKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun buildStanceReq(override: BookStance? = null): ExplainStanceRequest? {
        val det = determinedStance ?: return null
        return ExplainStanceRequest(
            stance = override ?: det.defaultStance,
            guards = det.guards,
            readThroughPage = book.readingPage.takeIf { it in 1..bookPages },
            bookPages = bookPages,
        )
    }

    val reply by produceState<ExplainResult?>(null, blocks, cacheKey, explainer.loaded, explainer.identity, enabled, attempt, determinedStance) {
        value = null
        if (enabled && blocks.isNotEmpty()) {
            val cached = if (library != null && determinedStance == null) {
                withContext(Dispatchers.IO) { runCatching { library.sectionGuide(book.id, cacheKey) }.getOrNull() }
            } else null
            if (cached != null) {
                value = ExplainResult.Done(SectionGuide.fromCached(cached).explanation)
                return@produceState
            }
            if (explainer.loaded && explainer.identity != null) {
                val positionRefusal = ExplainResult.Refused("这一页的段落位置无法核对，可以选中一段再问。")
                val stanceReq = buildStanceReq()
                suspend fun ask(): ExplainResult = try {
                    if (stanceReq != null) explainer.explainLessonWithStance(blocks, bookPages, stanceReq)
                    else explainer.explainLesson(blocks, bookPages)
                } catch (_: IllegalArgumentException) {
                    positionRefusal
                }
                // A fast empty-source refusal is one transient reply. Ask once more before painting it,
                // and leave the card on 「正在讲解这一页…」 until that reply arrives. A paragraph that
                // cannot be located is local and is not asked again. Manual 「重试」 is a new pass.
                var res = ask()
                if (attempt == 0 && res !== positionRefusal && res is ExplainResult.Refused && res.trouble.retry) {
                    res = ask()
                }
                if (res is ExplainResult.Done && library != null && determinedStance == null) {
                    val toCache = CachedSectionGuide(
                        brief = emptyList(),
                        text = res.explanation.text,
                        citations = res.explanation.citations.map { CachedCitation(it.anchor, it.blockId, it.page) },
                        recap = "",
                    )
                    withContext(Dispatchers.IO) { runCatching { library.saveSectionGuide(book.id, cacheKey, toCache, book) } }
                }
                value = res
            }
        }
    }
    LaunchedEffect(connectivity) {
        val failed = reply as? ExplainResult.Refused
        if (connectivity == CoachConnectivityStatus.ONLINE && failed?.trouble == ExplainTrouble.Unreachable) {
            attempt++
        }
    }

    fun switchPageStance(nextStance: BookStance) {
        if (switching || determinedStance == null || blocks.isEmpty()) return
        switching = true
        scope.launch {
            try {
                val req = buildStanceReq(nextStance) ?: return@launch
                val res = explainer.explainLessonWithStance(blocks, bookPages, req)
                if (res is ExplainResult.Done) {
                    switchedCards += res.explanation
                }
            } finally {
                switching = false
            }
        }
    }

    when {
        blocks.isEmpty() -> Text("这一页没有可讲解的正文。")
        !enabled -> Text("伴读讲解已在设置中关闭。")
        !explainer.loaded -> Text("正在打开讲解…")
        explainer.identity == null -> {
            RetrievedPageLinks(blocks.take(1), { "第 ${it.page} 页" }, onOpen)
            V7TextButton(onLogin) { Text("登录后讲解这一页") }
        }
        else -> when (val result = reply) {
            null -> Text("正在讲解这一页…")
            is ExplainResult.Done -> {
                val allBlocks = remember(book.id, book.pages, book.structure) { book.sourceBlocks() }
                val cards = listOf(result.explanation) + switchedCards
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cards.forEach { exp ->
                        val stanceAns = exp.stanceAnswer
                        if (stanceAns != null) {
                            LayeredAnswerCard(
                                book = book,
                                allBlocks = allBlocks,
                                answer = stanceAns,
                                bookDefaultStance = determinedStance?.defaultStance,
                                bookSecondaryStance = determinedStance?.secondaryStance,
                                bookGuards = determinedStance?.guards.orEmpty(),
                                showReaderPage = true,
                                onOpenCitation = { cite ->
                                    val passage = stanceCitationPassage(book, blocks, cite)
                                    if (passage != null && onOpenPassage != null) {
                                        onOpenPassage(passage)
                                    } else {
                                        blocks.firstOrNull { it.id == cite.docId }?.let(onOpen)
                                    }
                                },
                                onSwitchStance = { nextStance -> switchPageStance(nextStance) },
                                onBoundaryReadBook = { switchPageStance(stanceAns.stance) },
                                switchEnabled = !switching,
                            )
                        } else {
                            ExplanationView(book, allBlocks, exp, blocks, showReaderPage = true) { citation ->
                                blocks.firstOrNull { it.id == citation.blockId }?.let(onOpen)
                            }
                        }
                    }
                    if (switching) {
                        Text("正在换个角度整理…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            is ExplainResult.Refused -> {
                Text(result.message, modifier = Modifier.testTag("page-explanation-error"))
                RetrievedPageLinks(blocks.take(1), { "第 ${it.page} 页" }, onOpen)
                if (result.trouble.needsPairing) V7TextButton(onLogin) { Text("重新登录") }
                else V7TextButton({ attempt++ }) { Text("重试") }
            }
        }
    }
}
