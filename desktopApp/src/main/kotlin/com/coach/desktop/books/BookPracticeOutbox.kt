package com.coach.desktop.books

import com.coach.client.SyncEngine
import com.coach.domain.*
import com.coach.protocol.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

/**
 * A local Practice is ready for the sync chain only when the paired server already posed it to this device.
 *
 * The server pose is the identity: its attempt, question, frozen options and letter map come from the
 * ledger replica, never from the book. The client only checks that the pose names this exact book, lesson,
 * block and Practice text. A pose created through a command (commandId != null), on another device, voided,
 * or without the server's `bookPractice` link is not an identity, so the answer stays local. An assistant
 * session (agentRunId != null) never binds or queues a learner's attempt.
 *
 * A Ready is only a snapshot: [BookPracticeOutbox.submit] re-binds against the current replica before
 * adding a command, so a void or scope change that arrived after binding refuses the answer.
 *
 * Contract expected from the server pose (not produced here): a `question_posed` event appended by the
 * server for the requesting paired device, with
 * `bookPractice = {bookSha256, lessonId, blockId, reference}` derived from the server's own seeded textbook,
 * `stem == Practice.prompt` and option texts equal to `Practice.options`.
 */
internal sealed interface BookPracticeBinding {
    data class Ready(val courseId: String, val posed: Event, val book: Textbook, val lesson: BookLesson,
        val practice: Practice) : BookPracticeBinding {
        val attemptId get() = posed.payload.str("attemptId")
    }
    data class NotReady(val reason: String) : BookPracticeBinding
}

internal class BookPracticeOutbox(private val sync: SyncEngine) {
    fun bind(courseId: String, book: Textbook, lesson: BookLesson, practice: Practice): BookPracticeBinding {
        if (sync.agentRunId != null) return BookPracticeBinding.NotReady("BOOK_PRACTICE_AGENT_SCOPE")
        val block = lesson.blocks.firstOrNull { !it.heading && it.text.contains(practice.reference) }
            ?: return BookPracticeBinding.NotReady("BOOK_PRACTICE_SOURCE_MISSING")
        val facts = learnerEvidence(sync.localEvents(courseId))
        val voided = facts.filter { it.type == "attempt_voided" }.map { it.payload.str("attemptId") }.toSet()
        val poses = facts.filter { e ->
            val p = e.payload
            val link = p["bookPractice"] as? JsonObject
            e.type == "question_posed" && e.courseId == courseId && e.commandId == null && e.deviceId == sync.deviceId &&
                p.str("format") == "SINGLE_CHOICE" && p.str("attemptId").isNotBlank() && link != null &&
                link.str("bookSha256") == book.id && link.str("lessonId") == lesson.id &&
                link.str("blockId") == block.id && link.str("reference") == practice.reference &&
                p.str("stem") == practice.prompt && frozenTexts(p)?.sorted() == practice.options.sorted()
        }
        val posed = poses.singleOrNull() ?: return BookPracticeBinding.NotReady(
            if (poses.isEmpty()) "BOOK_PRACTICE_UNPOSED" else "BOOK_PRACTICE_AMBIGUOUS")
        if (posed.payload.str("attemptId") in voided) return BookPracticeBinding.NotReady("BOOK_PRACTICE_VOIDED")
        return BookPracticeBinding.Ready(courseId, posed, book, lesson, practice)
    }

    /**
     * Queues the learner's own choice for the server's original attempt. A retry, or a restart before the
     * command was acknowledged, returns the same outbox command instead of adding a second answer.
     * A new command is added only if binding the same Practice now still yields this exact pose.
     */
    fun submit(ready: BookPracticeBinding.Ready, answer: PracticeAnswer, now: Long): String {
        check(sync.agentRunId == null) { "BOOK_PRACTICE_AGENT_SCOPE" }
        val p = ready.posed.payload
        val option = LedgerJson.decodeFromJsonElement<List<Option>>(p.getValue("options")).singleOrNull { it.text == answer.text }
        requireNotNull(option) { "Answer is not one of the frozen options" }
        val letter = (p["letterMap"] as? JsonObject)?.get(option.optionId)?.jsonPrimitive?.contentOrNull
        requireNotNull(letter) { "Missing frozen letter" }
        val queued = sync.retainedCommands().firstOrNull { c ->
            val body = runCatching { LedgerJson.decodeFromString<CommandBody>(c.payloadBytes) }.getOrNull()
            body?.type == "submit_answer" && body.courseId == ready.courseId && body.payload.str("attemptId") == ready.attemptId
        }
        if (queued != null) {
            val body = LedgerJson.decodeFromString<CommandBody>(queued.payloadBytes).payload
            require(body.str("answer") == letter && body.flag("guessed") == answer.guessed) { "Answer is already locked" }
            return queued.commandId
        }
        val current = bind(ready.courseId, ready.book, ready.lesson, ready.practice)
        check(current == ready) { (current as? BookPracticeBinding.NotReady)?.reason ?: "BOOK_PRACTICE_STALE" }
        check(sync.localEvents(ready.courseId).none { it.type in setOf("answer_submitted", "answer_orphaned") &&
            it.payload.str("attemptId") == ready.attemptId }) { "Attempt already answered" }
        return sync.enqueue(CommandBody("submit_answer", ready.courseId, now, json("attemptId" to ready.attemptId,
            "answer" to letter, "guessed" to answer.guessed, "posedSnapshot" to p))).commandId
    }

    private fun frozenTexts(p: JsonObject): List<String>? = runCatching {
        val options = LedgerJson.decodeFromJsonElement<List<Option>>(p.getValue("options"))
        val letters = p.getValue("letterMap").jsonObject
        options.takeIf { o -> o.isNotEmpty() && o.all { letters[it.optionId] is JsonPrimitive } }?.map { it.text }
    }.getOrNull()
}
