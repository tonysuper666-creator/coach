package com.coach.desktop.books

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Answer writes that outlive the bookshelf composition.
 *
 * The workspace is removed from composition whenever the learner switches to 今天 or 课程, so a save
 * launched from its `rememberCoroutineScope` was cancelled while it was still waiting on SQLite. This
 * queue belongs to the host window instead, and every entry carries the book, lesson and question mode
 * it was typed in: a write that waits long enough for the learner to move on still lands on the lesson
 * it came from instead of the one that happens to be open when it finally runs.
 */
internal class AnswerDrafts(private val controller: TextbookController, private val scope: CoroutineScope) {
    private data class Key(val book: String, val lesson: String, val mode: QuestionMode, val review: Boolean = false)

    private val pending = mutableStateMapOf<Key, PracticeAnswer>()
    private var writer: Job? = null

    fun save(book: String, lesson: String, mode: QuestionMode, answer: PracticeAnswer) {
        pending[Key(book, lesson, mode)] = answer
        startWriter()
    }

    fun current(book: String, lesson: String, mode: QuestionMode, saved: PracticeAnswer): PracticeAnswer =
        pending[Key(book, lesson, mode)] ?: saved

    fun saveReview(entry: LocalBookReview, answer: PracticeAnswer) {
        pending[Key(entry.book, entry.lesson, entry.mode, review = true)] = answer
        startWriter()
    }

    fun currentReview(entry: LocalBookReview): PracticeAnswer =
        pending[Key(entry.book, entry.lesson, entry.mode, review = true)] ?: entry.answer

    /** A failed write remains queued for an explicit retry, including when closing the window. */
    suspend fun flush(): Boolean {
        if (writer?.isActive != true && pending.isNotEmpty()) startWriter()
        writer?.join()
        return pending.isEmpty()
    }

    private fun startWriter() {
        if (writer?.isActive != true) {
            // Start before the owning composition can disappear; finish accepted writes even if it does.
            writer = scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) { drain() } }
        }
    }

    private suspend fun drain() {
        while (true) {
            val (key, answer) = pending.entries.firstOrNull()?.toPair() ?: break
            // The controller checks the identity and writes under the same lock, so a lesson switch that is
            // still waiting on SQLite cannot make this draft land on the lesson it finally opens.
            val saved = if (key.review) controller.saveReviewDraft(key.book, key.lesson, key.mode, answer)
                else controller.saveDraft(key.book, key.lesson, key.mode, answer)
            if (!saved) return
            if (pending[key] == answer) pending.remove(key)
        }
    }
}

/** A workspace-local queue, for callers that own the whole screen and never unmount it mid-answer. */
@Composable
internal fun rememberAnswerDrafts(controller: TextbookController): AnswerDrafts {
    val scope = rememberCoroutineScope()
    return remember(controller) { AnswerDrafts(controller, scope) }
}
