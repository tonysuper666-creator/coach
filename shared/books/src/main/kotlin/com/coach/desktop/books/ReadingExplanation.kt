package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.coach.ui.v7.V7TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

internal fun explanationScope(first: Int, last: Int, title: String): String {
    val pages = if (first == last) "$first" else "$first–$last"
    return "讲的是：第 $pages 页 · $title"
}

private val LEADING_CAPTION_LINE_RE = Regex(
    "^(?:fig(?:ure)?\\.?|plate|illustration|photo(?:graph)?|x-?ray|diagram|chart|图\\s*\\d+|插图)\\b.*",
    RegexOption.IGNORE_CASE,
)

private fun isLeadingImageCaptionBlock(block: SourceBlock, illustrationCaptions: Set<String>): Boolean {
    val trimmed = block.text.trim()
    if (trimmed.isEmpty()) return true
    if (trimmed in illustrationCaptions) return true
    if (trimmed.length <= 220 && LEADING_CAPTION_LINE_RE.matches(trimmed)) return true
    return false
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
    visibleRanges: List<IntRange> = emptyList(),
): List<SourceBlock> {
    val allOnPage = book.sourceBlocks().filter { it.page == page }
    val bodyOnPage = allOnPage.filter { it.isBodyParagraph }
    if (bodyOnPage.isEmpty()) return emptyList()

    val screenCandidates = when {
        visibleRanges.isNotEmpty() -> {
            val overlapping = bodyOnPage.filter { block ->
                visibleRanges.any { range -> block.end > range.first && block.start <= range.last }
            }
            if (overlapping.isNotEmpty()) {
                if (anchorOffsetUtf16 > 0) overlapping.filter { it.end > anchorOffsetUtf16 }.ifEmpty { overlapping }
                else overlapping
            } else if (anchorOffsetUtf16 > 0) {
                bodyOnPage.filter { it.end > anchorOffsetUtf16 }.ifEmpty { bodyOnPage }
            } else bodyOnPage
        }
        anchorOffsetUtf16 > 0 -> bodyOnPage.filter { it.end > anchorOffsetUtf16 }.ifEmpty { bodyOnPage }
        else -> bodyOnPage
    }

    val captionSet = book.page(page)?.illustrations.orEmpty()
        .mapNotNull { it.caption?.trim()?.takeIf(String::isNotEmpty) }
        .toSet()
    val hasFollowingProse = screenCandidates.any { !isLeadingImageCaptionBlock(it, captionSet) }
    val hasPrecedingHeading = allOnPage.any { it.heading && it.start < (screenCandidates.lastOrNull()?.start ?: 0) }
    val filtered = if (screenCandidates.size >= 2 && hasFollowingProse &&
        (isLeadingImageCaptionBlock(screenCandidates.first(), captionSet) || hasPrecedingHeading)) {
        screenCandidates.dropWhile { isLeadingImageCaptionBlock(it, captionSet) }.ifEmpty { screenCandidates }
    } else screenCandidates

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

internal fun pageExplainCacheKey(page: Int, blocks: List<SourceBlock>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    blocks.forEach { b ->
        digest.update(b.id.toByteArray(Charsets.UTF_8))
        digest.update(':'.code.toByte())
        digest.update(b.text.length.toString().toByteArray(Charsets.UTF_8))
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
    visibleRanges: List<IntRange> = emptyList(),
    library: TextbookLibrary? = null,
) {
    val blocks = remember(book, page, currentPageOffset, visibleRanges) {
        selectScreenExplainBlocks(book, page, currentPageOffset, visibleRanges)
    }
    val cacheKey = remember(page, blocks) { pageExplainCacheKey(page, blocks) }
    var attempt by remember(book.id, cacheKey) { mutableIntStateOf(0) }
    val reply by produceState<ExplainResult?>(null, blocks, cacheKey, explainer.loaded, explainer.identity, enabled, attempt) {
        value = null
        if (enabled && blocks.isNotEmpty()) {
            val cached = if (library != null) {
                withContext(Dispatchers.IO) { runCatching { library.sectionGuide(book.id, cacheKey) }.getOrNull() }
            } else null
            if (cached != null) {
                value = ExplainResult.Done(SectionGuide.fromCached(cached).explanation)
                return@produceState
            }
            if (explainer.loaded && explainer.identity != null) {
                val res = try {
                    explainer.explainLesson(blocks, book.pages.maxOf { it.number })
                } catch (_: IllegalArgumentException) {
                    ExplainResult.Refused("这一页的段落位置无法核对，可以选中一段再问。")
                }
                if (res is ExplainResult.Done && library != null) {
                    val toCache = CachedSectionGuide(
                        brief = emptyList(),
                        text = res.explanation.text,
                        citations = res.explanation.citations.map { CachedCitation(it.anchor, it.blockId, it.page) },
                        recap = "",
                    )
                    withContext(Dispatchers.IO) { runCatching { library.saveSectionGuide(book.id, cacheKey, toCache) } }
                }
                value = res
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
            is ExplainResult.Done -> ExplanationView(book, book.sourceBlocks(), result.explanation, blocks, showReaderPage = true) { citation ->
                blocks.firstOrNull { it.id == citation.blockId }?.let(onOpen)
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
