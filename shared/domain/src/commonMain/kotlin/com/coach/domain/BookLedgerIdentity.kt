package com.coach.domain

import com.coach.domain.reviewqueue.QueueQuestion
import kotlinx.serialization.Serializable

/** Explicit source-owner link. Original file bytes and rendered Markdown have separate digests. */
@Serializable data class BookLedgerBlock(
    val id: String, val pdfPage: Int, val start: Int, val end: Int, val textSha256: String,
)

/** A claim carries no authority: both the local library and the ledger must verify every edge. */
@Serializable data class BookLedgerIdentity(
    val bookSha256: String, val lessonId: String, val block: BookLedgerBlock,
    val docId: String, val docBlobSha256: String, val courseId: String, val kpId: String,
    val question: QueueQuestion, val originalAttemptId: String,
    val prompt: String, val options: List<String>, val reference: String,
    val correctAnswer: String, val originalAnswer: String, val guessed: Boolean,
)

/** Event identities, not just question IDs: repeated poses and poses on other devices also count. */
@Serializable data class BookLedgerPose(
    val eventId: String, val seq: Long, val attemptId: String, val question: QueueQuestion,
)
@Serializable data class BookLedgerCoverage(val courseId: String, val cursor: Long, val poses: List<BookLedgerPose>)

/** Server-selected ledger entry and candidate. Never a local:<book>:<lesson> queue identity. */
@Serializable data class BookLedgerRegistration(
    val identity: BookLedgerIdentity, val coverage: BookLedgerCoverage,
    val entryId: String, val attemptId: String, val candidate: QueueQuestion,
)
