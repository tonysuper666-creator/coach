package com.coach.domain

import kotlinx.serialization.Serializable

/** A locator only. The paired device cannot supply a question, answer key, KP or approval. */
@Serializable data class BookPracticeSource(
    val bookSha256: String, val lessonId: String, val pdfPage: Int,
    val start: Int, val end: Int, val reference: String,
)

/** Explicit content-owner mapping, installed through the existing offline content import only.
 * The question must already have passed the server's independent review/key gate.
 */
@Serializable data class BookPracticeMapping(
    val source: BookPracticeSource, val kpId: String, val questionId: String,
    val questionFingerprint: String,
)
