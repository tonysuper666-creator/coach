package com.coach.desktop.books

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coach.design.tokens.CoachV7
import com.coach.protocol.AnswerLayerKind
import com.coach.protocol.BookStance
import com.coach.protocol.BookStanceCopy
import com.coach.protocol.BookStanceGuard
import com.coach.ui.theme.CoachTheme
import com.coach.ui.v7.PaperCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 随单书讲解/选段提问请求发往服务端的立场与边界参数。
 * 书为「未判定」时调用方传 null，请求不带任何立场字段，走旧路径。
 */
internal data class ExplainStanceRequest(
    val stance: BookStance,
    val guards: Set<BookStanceGuard> = emptySet(),
    val readThroughPage: Int? = null,
    val bookPages: Int? = null,
)

internal data class StanceCitation(
    val docId: String,
    val page: Int,
    val startUtf16: Int,
    val endUtf16: Int,
    val quote: String,
)

internal data class StanceBookSentence(
    val text: String,
    val citations: List<StanceCitation>,
)

internal sealed interface StanceLayer {
    val kind: AnswerLayerKind
    val label: String

    data class Book(
        override val label: String,
        val sentences: List<StanceBookSentence>,
    ) : StanceLayer {
        override val kind: AnswerLayerKind = AnswerLayerKind.BOOK
    }

    data class Outside(
        override val label: String,
        val text: String,
    ) : StanceLayer {
        override val kind: AnswerLayerKind = AnswerLayerKind.OUTSIDE
    }

    data class Guess(
        override val label: String,
        val text: String,
    ) : StanceLayer {
        override val kind: AnswerLayerKind = AnswerLayerKind.GUESS
    }
}

internal data class StanceAnswer(
    val stance: BookStance,
    val layers: List<StanceLayer>,
    val highRiskNotice: String? = null,
    val systemNotice: String? = null,
    val noBookNotice: String? = null,
    val boundary: String? = null,
    val modelCalled: Boolean = true,
    val droppedSentences: Int = 0,
    val outOfRange: Int = 0,
)

/**
 * 立场回答与旧版出处的持久化编解码器（向下兼容 `book_ask_turns.citations_json`）：
 * - 非立场回答（`stanceAnswer == null`）仍以旧格式 JSON 数组落盘，不改动既有同步与迁移行为；
 * - 立场回答以 JSON 对象 `{"citations":[...],"stanceAnswer":{...}}` 落盘，重开后完整恢复
 *   原题头、分层、逐句引文 UTF-16 区间、系统提示与拦截卡。
 */
internal object StanceAnswerCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeCitationsColumn(explanation: Explanation): String {
        val flat = buildJsonArray {
            for (c in explanation.citations) {
                add(buildJsonObject {
                    put("anchor", c.anchor)
                    put("blockId", c.blockId)
                    put("page", c.page)
                    if (c.quote.isNotEmpty()) put("quote", c.quote)
                })
            }
        }
        val stanceAnswer = explanation.stanceAnswer ?: return flat.toString()
        return buildJsonObject {
            put("citations", flat)
            put("stanceAnswer", encodeStanceAnswer(stanceAnswer))
        }.toString()
    }

    fun decodeCitationsColumn(raw: String): Pair<List<Citation>, StanceAnswer?> {
        if (raw.isBlank()) return emptyList<Citation>() to null
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList<Citation>() to null
        return when (root) {
            is JsonArray -> parseFlatCitations(root) to null
            is JsonObject -> {
                val flat = (root["citations"] as? JsonArray)?.let(::parseFlatCitations).orEmpty()
                val stance = (root["stanceAnswer"] as? JsonObject)?.let(::decodeStanceAnswer)
                flat to stance
            }
            else -> emptyList<Citation>() to null
        }
    }

    private fun parseFlatCitations(arr: JsonArray): List<Citation> = arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        Citation(
            anchor = o["anchor"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            blockId = o["blockId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
            page = o["page"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
            quote = o["quote"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    fun encodeStanceAnswer(answer: StanceAnswer): JsonObject = buildJsonObject {
        put("stance", answer.stance.wire)
        put("layers", buildJsonArray {
            for (layer in answer.layers) {
                add(buildJsonObject {
                    put("layer", layer.kind.wire)
                    put("label", layer.label)
                    when (layer) {
                        is StanceLayer.Book -> put("sentences", buildJsonArray {
                            for (s in layer.sentences) {
                                add(buildJsonObject {
                                    put("text", s.text)
                                    put("citations", buildJsonArray {
                                        for (c in s.citations) {
                                            add(buildJsonObject {
                                                put("docId", c.docId)
                                                put("pdfPage", c.page)
                                                put("startUtf16", c.startUtf16)
                                                put("endUtf16", c.endUtf16)
                                                put("quote", c.quote)
                                            })
                                        }
                                    })
                                })
                            }
                        })
                        is StanceLayer.Outside -> put("text", layer.text)
                        is StanceLayer.Guess -> put("text", layer.text)
                    }
                })
            }
        })
        answer.highRiskNotice?.let { put("highRiskNotice", it) }
        answer.systemNotice?.let { put("systemNotice", it) }
        answer.noBookNotice?.let { put("noBookNotice", it) }
        answer.boundary?.let { put("boundary", it) }
        put("modelCalled", answer.modelCalled)
        put("droppedSentences", answer.droppedSentences)
        put("outOfRange", answer.outOfRange)
    }

    fun decodeStanceAnswer(obj: JsonObject): StanceAnswer? {
        val stance = BookStance.fromWire((obj["stance"] as? JsonPrimitive)?.contentOrNull) ?: return null
        val modelCalled = (obj["modelCalled"] as? JsonPrimitive)?.booleanOrNull ?: true
        val dropped = (obj["droppedSentences"] as? JsonPrimitive)?.intOrNull ?: 0
        val outOfRange = (obj["outOfRange"] as? JsonPrimitive)?.intOrNull ?: 0
        val systemNotice = (obj["systemNotice"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val noBookNotice = (obj["noBookNotice"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val boundary = when (val rawBoundary = obj["boundary"]) {
            is JsonPrimitive -> rawBoundary.takeIf { it.isString }?.contentOrNull?.trim()
                ?.takeIf { it == BookStanceCopy.HIGH_RISK_BOUNDARY }
            // 向下兼容旧缓存中的对象形态，不渲染任何拦截卡，仅转为高危提示行形态
            is JsonObject -> BookStanceCopy.HIGH_RISK_BOUNDARY
            else -> null
        }
        val highRiskNotice = (obj["highRiskNotice"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: if (boundary == BookStanceCopy.HIGH_RISK_BOUNDARY) BookStanceCopy.HIGH_RISK_NOTICE else null
        val isHighRisk = boundary == BookStanceCopy.HIGH_RISK_BOUNDARY
        val layers = mutableListOf<StanceLayer>()
        for (el in (obj["layers"] as? JsonArray).orEmpty()) {
            val lObj = el as? JsonObject ?: continue
            val kind = AnswerLayerKind.fromWire((lObj["layer"] as? JsonPrimitive)?.contentOrNull) ?: continue
            if (isHighRisk && kind != AnswerLayerKind.BOOK) continue
            val label = (lObj["label"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            when (kind) {
                AnswerLayerKind.OUTSIDE -> {
                    val text = (lObj["text"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    layers += StanceLayer.Outside(label, text)
                }
                AnswerLayerKind.GUESS -> {
                    val text = (lObj["text"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    layers += StanceLayer.Guess(label, text)
                }
                AnswerLayerKind.BOOK -> {
                    val rawSents = lObj["sentences"] as? JsonArray ?: continue
                    if (rawSents.isEmpty()) continue
                    var valid = true
                    val sents = mutableListOf<StanceBookSentence>()
                    for (sEl in rawSents) {
                        val sObj = sEl as? JsonObject
                        val sText = (sObj?.get("text") as? JsonPrimitive)?.contentOrNull?.trim()
                        val rawCites = sObj?.get("citations") as? JsonArray
                        if (sText.isNullOrBlank() || rawCites == null || rawCites.isEmpty()) {
                            valid = false
                            break
                        }
                        val cites = mutableListOf<StanceCitation>()
                        for (cEl in rawCites) {
                            val cObj = cEl as? JsonObject
                            val docId = (cObj?.get("docId") as? JsonPrimitive)?.contentOrNull?.trim()
                            val page = (cObj?.get("pdfPage") as? JsonPrimitive)?.intOrNull
                            val startUtf16 = (cObj?.get("startUtf16") as? JsonPrimitive)?.intOrNull
                            val endUtf16 = (cObj?.get("endUtf16") as? JsonPrimitive)?.intOrNull
                            val quote = (cObj?.get("quote") as? JsonPrimitive)?.contentOrNull
                            if (docId.isNullOrBlank() || page == null || startUtf16 == null || endUtf16 == null ||
                                startUtf16 < 0 || endUtf16 <= startUtf16 || quote.isNullOrBlank()
                            ) {
                                valid = false
                                break
                            }
                            cites += StanceCitation(docId, page, startUtf16, endUtf16, quote)
                        }
                        if (!valid || cites.isEmpty()) {
                            valid = false
                            break
                        }
                        sents += StanceBookSentence(sText, cites)
                    }
                    if (valid && sents.isNotEmpty()) {
                        layers += StanceLayer.Book(label, sents)
                    }
                }
            }
        }
        return StanceAnswer(
            stance = stance,
            layers = layers,
            highRiskNotice = highRiskNotice,
            systemNotice = systemNotice,
            noBookNotice = noBookNotice,
            boundary = boundary,
            modelCalled = modelCalled,
            droppedSentences = dropped,
            outOfRange = outOfRange,
        )
    }
}

/**
 * 将逐句出处还原为原页上的精确 [BookPassage]（UTF-16 区间），点哪句就高亮哪句的原句。
 */
internal fun stanceCitationPassage(
    book: Textbook,
    blocks: List<SourceBlock>,
    citation: StanceCitation,
): BookPassage? {
    val pageObj = book.page(citation.page)
    if (pageObj != null) {
        val text = pageObj.text
        if (citation.startUtf16 in 0 until citation.endUtf16 &&
            citation.endUtf16 <= text.length &&
            text.substring(citation.startUtf16, citation.endUtf16) == citation.quote
        ) {
            return BookPassage(book.id, citation.page, citation.startUtf16, citation.endUtf16, citation.quote)
        }
        SentenceCitationGate.locatePassageInPage(book.id, pageObj, citation.quote)?.let { return it }
    }
    return blocks.firstOrNull { it.id == citation.docId }?.let { BookPassage.from(book, it) }
}

/**
 * 客户端共用分层回答卡组件（TON-1977 #1/#4 & TON-1982，供讲解、选段提问、阶段 2 库里提问复用）：
 * - 题头显示当前回答立场（古籍切知识显示「历史学角度」，玄学切知识显示「文献和思想史角度」）；
 * - `boundary == "high_risk"` 时不显示拦截卡或弹窗，直接在题头下第一行展示高危提示行，样式与体系提示行一致；玄学立场时两行都有；
 * - 本书块按返回 `label` 分块显示，每句各自带出处、可单独点击跳原页高亮该句原句；
 * - 书外 / 推想块显示固定标签（「书外 · 未对照原文」/「推想 · 书外 · 未对照原文」），不可点、不带页码；
 * - 卡底「换个角度：」列出其他立场（带 `esoteric` 标记的书上 `original` 显示为「只看书里怎么讲」），点击用新立场重问并将新卡接在下方，旧卡题头不变。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LayeredAnswerCard(
    book: Textbook,
    allBlocks: List<SourceBlock>,
    answer: StanceAnswer,
    bookDefaultStance: BookStance? = null,
    bookSecondaryStance: BookStance? = null,
    bookGuards: Set<BookStanceGuard> = emptySet(),
    showReaderPage: Boolean = false,
    onOpenCitation: (StanceCitation) -> Unit,
    onSwitchStance: ((BookStance) -> Unit)? = null,
    switchEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = CoachTheme.colors
    val pine = v7Pine()
    val pillShape = RoundedCornerShape(CoachV7.Radius.s)
    val pineWash = if (colors.dark) CoachV7.Dark.pineWash else CoachV7.Light.pineWash
    val mutedBg = colors.surface2
    val headerText = answer.stance.labelForBook(bookDefaultStance, bookGuards)
    val highRiskLine = answer.highRiskNotice?.takeIf { it.isNotBlank() }
        ?: if (answer.boundary == BookStanceCopy.HIGH_RISK_BOUNDARY) BookStanceCopy.HIGH_RISK_NOTICE else null

    PaperCard(
        modifier = modifier.fillMaxWidth().testTag("stance-answer-card"),
        contentPadding = PaddingValues(14.dp),
        contentSpacing = 10.dp,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                headerText,
                style = MaterialTheme.typography.labelMedium,
                color = pine,
                modifier = Modifier
                    .clip(pillShape)
                    .background(pineWash)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .testTag("stance-answer-header"),
            )
        }

        if (highRiskLine != null) {
            Text(
                highRiskLine,
                style = MaterialTheme.typography.labelSmall,
                color = colors.fgSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(pillShape)
                    .background(mutedBg)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("stance-high-risk-notice"),
            )
        }
        if (!answer.systemNotice.isNullOrBlank()) {
            Text(
                answer.systemNotice,
                style = MaterialTheme.typography.labelSmall,
                color = colors.fgSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(pillShape)
                    .background(mutedBg)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("stance-system-notice"),
            )
        }
        if (!answer.noBookNotice.isNullOrBlank()) {
            Text(
                answer.noBookNotice,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgSecondary,
                modifier = Modifier.fillMaxWidth().testTag("stance-no-book-notice"),
            )
        }

        answer.layers.forEachIndexed { layerIndex, layer ->
            when (layer) {
                is StanceLayer.Book -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .testTag("stance-layer-book-$layerIndex"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            layer.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = pine,
                            modifier = Modifier.testTag("stance-layer-book-label-$layerIndex"),
                        )
                        layer.sentences.forEachIndexed { sentIndex, sentence ->
                            val primary = sentence.citations.first()
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(pillShape)
                                    .clickable(role = Role.Button) { onOpenCitation(primary) }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .padding(vertical = 2.dp)
                                    .testTag("stance-book-sentence-$layerIndex-$sentIndex"),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    sentence.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.fgPrimary,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    sentence.citations.forEachIndexed { citeIndex, cite ->
                                        val loc = bookCitationLabel(
                                            book,
                                            allBlocks,
                                            Citation("S${citeIndex + 1}", cite.docId, cite.page, cite.quote),
                                        )
                                        val readerPage = if (showReaderPage && !loc.contains("第 ${cite.page} 页")) {
                                            "第 ${cite.page} 页 · "
                                        } else ""
                                        val snippet = shortQuote(cite.quote)
                                        Text(
                                            "出处 · $readerPage$loc · $snippet",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = pine,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textDecoration = TextDecoration.Underline,
                                            modifier = Modifier
                                                .clickable(role = Role.Button) { onOpenCitation(cite) }
                                                .pointerHoverIcon(PointerIcon.Hand)
                                                .padding(vertical = 2.dp)
                                                .testTag("stance-sentence-citation-$layerIndex-$sentIndex-$citeIndex"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                is StanceLayer.Outside -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(pillShape)
                            .background(mutedBg)
                            .border(1.dp, colors.borderSubtle, pillShape)
                            .padding(10.dp)
                            .testTag("stance-layer-outside-$layerIndex"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            layer.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.fgSecondary,
                            modifier = Modifier.testTag("stance-layer-outside-label-$layerIndex"),
                        )
                        Text(
                            layer.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.fgPrimary,
                        )
                    }
                }
                is StanceLayer.Guess -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(pillShape)
                            .background(mutedBg)
                            .border(1.dp, colors.borderSubtle, pillShape)
                            .padding(10.dp)
                            .testTag("stance-layer-guess-$layerIndex"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            layer.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.fgSecondary,
                            modifier = Modifier.testTag("stance-layer-guess-label-$layerIndex"),
                        )
                        Text(
                            layer.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.fgPrimary,
                        )
                    }
                }
            }
        }

        if (onSwitchStance != null) {
            HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle.copy(alpha = 0.6f))
            val alternatives = buildList {
                if (bookSecondaryStance != null && bookSecondaryStance != answer.stance) {
                    add(bookSecondaryStance)
                }
                for (st in BookStance.entries) {
                    if (st != answer.stance && st !in this) add(st)
                }
            }
            FlowRow(
                Modifier.fillMaxWidth().testTag("stance-switch-row"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    BookStanceCopy.SWITCH_PREFIX,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.fgSecondary,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                for (target in alternatives) {
                    val btnLabel = target.switchLabelForBook(bookDefaultStance, bookGuards)
                    Text(
                        btnLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (switchEnabled) pine else colors.fgTertiary,
                        modifier = Modifier
                            .clip(pillShape)
                            .background(colors.surface1)
                            .border(1.dp, colors.borderSubtle, pillShape)
                            .clickable(enabled = switchEnabled, role = Role.Button) { onSwitchStance(target) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("stance-switch-${target.wire}"),
                    )
                }
            }
        }
    }
}
