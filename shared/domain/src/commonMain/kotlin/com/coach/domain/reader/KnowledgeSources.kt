package com.coach.domain.reader

import com.coach.domain.LedgerJson
import com.coach.domain.SourceAnchor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
enum class KnowledgeSourceKind {
    @SerialName("textbook") TEXTBOOK,
    @SerialName("slides") SLIDES,
    @SerialName("reading") READING;

    val catalogKind: String get() = when (this) {
        TEXTBOOK -> "textbook"
        SLIDES -> "slides"
        READING -> "reading"
    }
}

/** A declared claim-to-source link, never a verdict that the page proves the claim. PDF pages are one based, inclusive. */
@Serializable
data class ClaimSourceAnchor(
    val docId: String,
    val pdfPage: Int,
    val sourceKind: KnowledgeSourceKind,
    val pdfPageEnd: Int = pdfPage,
    val claimRef: String? = null,
    val supportedSubpoint: String? = null,
    val bookPage: Int? = null,
) {
    init {
        require(docId.isNotBlank() && docId == docId.trim() && docId.none { it.isISOControl() })
        require(pdfPage > 0 && pdfPageEnd >= pdfPage)
        require(claimRef == null || claimRef.isNotBlank() && claimRef == claimRef.trim())
        require(supportedSubpoint == null || supportedSubpoint.isNotBlank() && supportedSubpoint == supportedSubpoint.trim())
        require(claimRef != null || supportedSubpoint != null)
        require(bookPage == null || bookPage > 0)
    }

    fun supports(claim: String?, subpoint: String?): Boolean =
        (claim == null || claimRef == claim) && (subpoint == null || supportedSubpoint == subpoint)
}

enum class KnowledgeSourceProblem {
    MALFORMED_SOURCES, INVALID_ANCHOR, MISSING_CLAIM, LEGACY_PAGE_ONLY, CLAIM_NOT_MAPPED,
}

data class KnowledgeSourceIssue(val index: Int?, val problem: KnowledgeSourceProblem)

sealed interface KnowledgeSourceChoice {
    data class Candidates(val anchors: List<ClaimSourceAnchor>) : KnowledgeSourceChoice
    data class Unconfirmed(val reason: KnowledgeSourceProblem) : KnowledgeSourceChoice
}

/** An explicit new field is authoritative, even when empty/invalid; never fall back to a stale chapter anchor. */
data class KnowledgeSourceReferences(
    val sourceAnchors: List<ClaimSourceAnchor> = emptyList(),
    val legacyAnchors: List<SourceAnchor> = emptyList(),
    val structured: Boolean = false,
    val issues: List<KnowledgeSourceIssue> = emptyList(),
) {
    fun forClaim(claimRef: String? = null, supportedSubpoint: String? = null): KnowledgeSourceChoice {
        if (claimRef == null && supportedSubpoint == null ||
            claimRef != null && claimRef.isBlank() || supportedSubpoint != null && supportedSubpoint.isBlank())
            return KnowledgeSourceChoice.Unconfirmed(KnowledgeSourceProblem.MISSING_CLAIM)
        if (!structured) return KnowledgeSourceChoice.Unconfirmed(KnowledgeSourceProblem.LEGACY_PAGE_ONLY)
        if (issues.isNotEmpty()) return KnowledgeSourceChoice.Unconfirmed(issues.first().problem)
        val matching = sourceAnchors.filter { it.supports(claimRef, supportedSubpoint) }
        return if (matching.isEmpty()) KnowledgeSourceChoice.Unconfirmed(KnowledgeSourceProblem.CLAIM_NOT_MAPPED)
        else KnowledgeSourceChoice.Candidates(matching)
    }
}

/** Reads all three generations. Malformed new entries remain an explicit failure instead of disappearing. */
fun readKnowledgeSources(point: JsonObject): KnowledgeSourceReferences {
    val legacy = ((point["anchors"] as? JsonArray).orEmpty() + listOfNotNull(point["sourceAnchor"]))
        .mapNotNull { raw -> runCatching { LedgerJson.decodeFromJsonElement<SourceAnchor>(raw) }.getOrNull() }
        .filter { it.docId.isNotBlank() && it.pdfPage > 0 }.distinct()
    val raw = point["sourceAnchors"]
    if (raw == null || raw == JsonNull) return KnowledgeSourceReferences(legacyAnchors = legacy)
    if (raw !is JsonArray) return KnowledgeSourceReferences(legacyAnchors = legacy, structured = true,
        issues = listOf(KnowledgeSourceIssue(null, KnowledgeSourceProblem.MALFORMED_SOURCES)))
    val issues = mutableListOf<KnowledgeSourceIssue>()
    val anchors = raw.mapIndexedNotNull { index, entry ->
        val decoded = runCatching { LedgerJson.decodeFromJsonElement<ClaimSourceAnchor>(entry) }.getOrNull()
        if (decoded == null) issues += KnowledgeSourceIssue(index, KnowledgeSourceProblem.INVALID_ANCHOR)
        decoded
    }
    return KnowledgeSourceReferences(anchors, legacy, true, issues)
}

/** Preserve every unrelated field and the old alias. Never manufacture a primary/earliest page for old clients. */
fun writeKnowledgeSources(point: JsonObject, anchors: List<ClaimSourceAnchor>): JsonObject =
    JsonObject(point + ("sourceAnchors" to LedgerJson.parseToJsonElement(LedgerJson.encodeToString(anchors))))
