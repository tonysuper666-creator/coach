package com.coach.desktop.books

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal enum class CollectionAskScope {
    SECTION,
    COLLECTION,
}

internal data class ActiveAskCollection(
    val id: String,
    val kind: CollectionKind,
    val title: String,
) {
    val scopeToggleLabel: String
        get() = when (kind) {
            CollectionKind.BOOKLIST -> "已读书目"
            else -> "整门课"
        }
}

internal data class CollectionQaBlock(
    val blockId: String,
    val bookId: String,
    val docLabel: String,
    val shortLabel: String,
    val authorityRank: Int,
    val page: Int,
    val timestampLabel: String? = null,
    val text: String,
)

internal data class CollectionQaCitation(
    val collectionId: String,
    val bookId: String,
    val docLabel: String,
    val shortLabel: String,
    val page: Int,
    val timestampLabel: String? = null,
    val quoteAnchor: String,
    val startUtf16: Int = 0,
    val endUtf16: Int = 0,
) {
    fun capsuleText(): String =
        if (!timestampLabel.isNullOrBlank()) "$shortLabel $timestampLabel"
        else "$shortLabel p.$page"
}

internal data class CollectionQaSentence(
    val claim: String,
    val citation: CollectionQaCitation,
    val rawExactMatch: Boolean = true,
)

internal data class CollectionQaConflictCard(
    val id: String,
    val point: String,
    val authoritativeView: String,
    val secondaryView: String,
    val advice: String,
    val citations: List<CollectionQaCitation> = emptyList(),
)

internal data class MatchedOfflineDiscrepancy(
    val id: String,
    val point: String,
    val authoritativeView: String,
    val secondaryView: String,
    val advice: String,
    val directAspectMatch: Boolean,
    val citations: List<CourseCitationRef>,
)

internal data class CollectionQaTurn(
    val question: String,
    val sentences: List<CollectionQaSentence>,
    val hasMaterialConflict: Boolean,
    val conflictSummaryZh: String,
    val conflictCards: List<CollectionQaConflictCard> = emptyList(),
    val fallbackMessage: String? = null,
    val droppedUnverifiedCount: Int = 0,
)

internal data class CollectionQaSessionState(
    val scope: CollectionAskScope = CollectionAskScope.SECTION,
    val turns: List<CollectionQaTurn> = emptyList(),
    val loading: Boolean = false,
    val error: Pair<String, ExplainTrouble>? = null,
    val lastQuestion: String? = null,
    val fallbackPages: List<CollectionQaCitation> = emptyList(),
)

internal object CollectionQaJson {
    fun encodeRequest(
        requestId: String,
        collection: ActiveAskCollection,
        question: String,
        blocks: List<CollectionQaBlock>,
        discrepancies: List<MatchedOfflineDiscrepancy>,
        shortLabelByBookId: Map<String, String> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("requestId", requestId)
        put("collectionId", collection.id)
        put("collectionKind", collection.kind.name)
        put("collectionTitle", collection.title)
        put("question", question)
        put("blocks", buildJsonArray {
            for (b in blocks) {
                add(buildJsonObject {
                    put("blockId", b.blockId)
                    put("bookId", b.bookId)
                    put("docLabel", b.docLabel)
                    put("authorityRank", b.authorityRank)
                    put("page", b.page)
                    b.timestampLabel?.let { put("timestampLabel", it) }
                    put("text", b.text)
                })
            }
        })
        if (discrepancies.isNotEmpty()) {
            put("discrepancies", buildJsonArray {
                for (d in discrepancies) {
                    add(buildJsonObject {
                        put("id", d.id)
                        put("point", d.point)
                        put("authoritativeView", d.authoritativeView)
                        put("secondaryView", d.secondaryView)
                        put("advice", d.advice)
                        put("directAspectMatch", d.directAspectMatch)
                        put("citations", buildJsonArray {
                            for (c in d.citations) {
                                add(buildJsonObject {
                                    put("bookId", c.bookId)
                                    put("docLabel", shortLabelByBookId[c.bookId] ?: c.bookTitle)
                                    put("page", c.page)
                                    c.timestampLabel?.let { put("timestampLabel", it) }
                                    put("quoteAnchor", c.quote)
                                })
                            }
                        })
                    })
                }
            })
        }
    }

    internal fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    internal fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    internal fun JsonObject.boolOrFalse(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    internal fun JsonObject.arr(key: String): JsonArray =
        this[key] as? JsonArray ?: JsonArray(emptyList())
}
