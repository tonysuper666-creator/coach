package com.coach.client

import com.coach.domain.reader.ClaimSourceAnchor
import com.coach.domain.reader.KnowledgeSourceChoice
import com.coach.domain.reader.KnowledgeSourceProblem
import kotlinx.serialization.json.*

enum class ClaimSourceFailure {
    SOURCE_CONTRACT, COURSE_MISMATCH, CATALOG_UNAVAILABLE, DOCUMENT_NOT_REGISTERED, KIND_MISMATCH,
    PAGE_OUTSIDE_DOCUMENT, SOURCE_NOT_SELECTED, PAGE_OUTSIDE_SOURCE,
    PAGE_UNAVAILABLE, DOCUMENT_CHANGED, PAGE_REPLY_MISMATCH, NO_TEXT,
}

sealed interface ClaimSourceRead {
    data class ChooseSource(val sources: List<ClaimSourceAnchor>) : ClaimSourceRead
    data class ChoosePage(val source: ClaimSourceAnchor, val version: String) : ClaimSourceRead
    /** A successful existing reader request proves location/edition only, not semantic claim support. */
    data class Opened(val source: ClaimSourceAnchor, val pdfPage: Int, val version: String, val text: String) : ClaimSourceRead
    data class Unconfirmed(val reason: ClaimSourceFailure, val contractProblem: KnowledgeSourceProblem? = null) : ClaimSourceRead
}

private data class ClaimDocument(val id: String, val version: String, val pages: Int, val kind: String)

/**
 * Consumes the actual CourseView row and paired ReaderHttp. A range requires an explicit page; several
 * sources require an explicit source. No min/first-page fallback, search, AI, progress write or new endpoint.
 */
suspend fun ReaderHttp.readClaimSource(
    kp: KpRow,
    claimRef: String? = null,
    supportedSubpoint: String? = null,
    source: ClaimSourceAnchor? = null,
    pdfPage: Int? = null,
): ClaimSourceRead {
    val selection = kp.sourceReferences.forClaim(claimRef, supportedSubpoint)
    if (selection is KnowledgeSourceChoice.Unconfirmed)
        return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.SOURCE_CONTRACT, selection.reason)
    if (courseId.isNullOrBlank() || kp.sourceCourseId != courseId)
        return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.COURSE_MISMATCH)
    val candidates = (selection as KnowledgeSourceChoice.Candidates).anchors
    if (source != null && source !in candidates) return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.SOURCE_NOT_SELECTED)

    val catalog = catalog()
    val entries = catalog.body?.get("documents") as? JsonArray
    if (!catalog.ok || entries == null) return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.CATALOG_UNAVAILABLE)
    fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    val documents = entries.map { raw ->
        val o = raw as? JsonObject ?: return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.CATALOG_UNAVAILABLE)
        val id = o.string("id")?.takeIf { it.isNotBlank() } ?: return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.CATALOG_UNAVAILABLE)
        val version = o.string("version")?.takeIf { it.isNotBlank() } ?: return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.CATALOG_UNAVAILABLE)
        val pages = (o["pages"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 } ?: return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.CATALOG_UNAVAILABLE)
        val kind = o.string("kind") ?: return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.CATALOG_UNAVAILABLE)
        ClaimDocument(id, version, pages, kind)
    }
    // Validate the whole selected claim's declared support; an unregistered second source is not silently dropped.
    for (candidate in candidates) {
        val doc = documents.singleOrNull { it.id == candidate.docId }
            ?: return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.DOCUMENT_NOT_REGISTERED)
        if (doc.kind != candidate.sourceKind.catalogKind) return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.KIND_MISMATCH)
        if (candidate.pdfPageEnd > doc.pages) return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.PAGE_OUTSIDE_DOCUMENT)
    }
    val selected = source ?: candidates.singleOrNull() ?: return ClaimSourceRead.ChooseSource(candidates)
    val document = documents.single { it.id == selected.docId }
    val page = pdfPage ?: if (selected.pdfPage == selected.pdfPageEnd) selected.pdfPage
        else return ClaimSourceRead.ChoosePage(selected, document.version)
    if (page !in selected.pdfPage..selected.pdfPageEnd) return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.PAGE_OUTSIDE_SOURCE)
    val reply = this.page(selected.docId, page)
    if (reply.status == 409) return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.DOCUMENT_CHANGED)
    if (!reply.ok) return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.PAGE_UNAVAILABLE)
    val body = reply.body ?: return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.PAGE_UNAVAILABLE)
    if (body.string("documentId") != selected.docId || body.string("version") != document.version ||
        (body["pdfPage"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull != page)
        return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.PAGE_REPLY_MISMATCH)
    val text = body.string("text")?.takeIf { it.isNotBlank() } ?: return ClaimSourceRead.Unconfirmed(ClaimSourceFailure.NO_TEXT)
    return ClaimSourceRead.Opened(selected, page, document.version, text)
}
