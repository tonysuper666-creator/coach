package com.coach.desktop.books.exam

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.coach.desktop.books.Textbook
import com.coach.desktop.books.TextbookLibrary
import com.coach.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal data class LocalExamUi(
    val book: Textbook? = null, val reference: LocalPaperAvailability? = null,
    val papers: List<JsonObject> = emptyList(), val active: JsonObject? = null,
    val history: List<JsonObject> = emptyList(), val attempt: JsonObject? = null, val source: JsonObject? = null,
    val busy: Boolean = false, val saving: Boolean = false, val error: String? = null, val conflict: Boolean = false,
)

/** Window-owned accepted writes survive course/tab detours and participate in the existing close guard. */
internal class LocalExamController(library: TextbookLibrary, private val scope: CoroutineScope) {
    private val store = LocalExamStore(library)
    private val mutable = MutableStateFlow(LocalExamUi())
    val state = mutable.asStateFlow()
    private val actions = Mutex()
    private data class Key(val book: String, val attempt: String)
    private data class Draft(val answers: JsonObject, val sequence: Long)
    private val queueLock = Any()
    private val pending = linkedMapOf<Key, Draft>()
    private var writer: Job? = null

    private suspend fun action(showBusy: Boolean = true, block: () -> Unit): Boolean = actions.withLock {
        mutable.update { it.copy(busy = showBusy, error = null) }
        try {
            withContext(Dispatchers.IO) { block() }
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            mutable.update { it.copy(error = if (failure is java.sql.SQLException) "作答还没保存，请稍后重试。当前内容仍在本页。"
                else failure.message ?: "练习暂时无法保存，请保留本页内容后重试。") }
            false
        } finally { mutable.update { it.copy(busy = false) } }
    }

    private fun refresh(book: Textbook, opened: String? = null) {
        mutable.update { it.copy(book = book, reference = textbookReferencePaper(book), papers = store.papers(book.id),
            active = store.active(book.id), history = store.history(book.id),
            attempt = opened?.let { id -> store.get(book.id, id) }, source = null) }
    }

    suspend fun load(book: Textbook) {
        if (mutable.value.book?.id == book.id) return
        if (flush()) action { refresh(book) }
    }

    suspend fun generateAndStart() {
        if (!flush()) return
        action {
            val book = checkNotNull(mutable.value.book)
            val paper = mutable.value.reference?.paper ?: error("这本教材还没有可用练习。")
            store.register(paper)
            val opened = store.start(book.id, paper.id)
            refresh(book, opened.str("mockId"))
        }
    }

    suspend fun start(paper: String) {
        if (!flush()) return
        action {
            val book = checkNotNull(mutable.value.book)
            refresh(book, store.start(book.id, paper).str("mockId"))
        }
    }

    suspend fun open(attempt: String) {
        if (!flush()) return
        action { refresh(checkNotNull(mutable.value.book), attempt) }
    }

    suspend fun back() {
        if (flush()) action { refresh(checkNotNull(mutable.value.book)) }
    }

    suspend fun submit() {
        if (!flush()) return
        action {
            val book = checkNotNull(mutable.value.book)
            val id = checkNotNull(mutable.value.attempt).str("mockId")
            store.submit(book.id, id)
            refresh(book, id)
        }
    }

    fun showSource(source: JsonObject) { mutable.update { it.copy(source = source) } }
    fun closeSource() { mutable.update { it.copy(source = null) } }

    fun answer(question: String, answer: JsonObject) = synchronized(queueLock) {
        val ui = mutable.value
        val attempt = ui.attempt ?: return@synchronized
        if (ui.busy || ui.conflict || attempt.str("status") != "active") return@synchronized
        val key = Key(attempt.str("bookId"), attempt.str("mockId"))
        val answers = JsonObject(attempt.getValue("answers").jsonObject + (question to answer))
        val sequence = Math.addExact(attempt.num("answersSeq"), 1)
        pending[key] = Draft(answers, sequence)
        mutable.update { it.copy(attempt = JsonObject(attempt + json("answers" to answers, "answersSeq" to sequence)),
            saving = true, error = null) }
        startWriter()
    }

    private fun startWriter() {
        if (writer?.isActive != true) writer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { drain() }
        }
    }

    private suspend fun drain() {
        while (true) {
            val (key, draft) = synchronized(queueLock) { pending.entries.firstOrNull()?.toPair() } ?: return
            if (!action(showBusy = false) {
                val reply = try { store.save(key.book, key.attempt, draft.answers, draft.sequence) }
                catch (changed: IllegalStateException) {
                    mutable.update { it.copy(conflict = true) }
                    throw IllegalStateException("无法确认这次作答的已保存状态。本页内容仍保留，请确认后重新载入。", changed)
                }
                if (!reply.flag("saved")) {
                    mutable.update { it.copy(conflict = true) }
                    error("另一个窗口已更新这次作答。本页内容仍保留，请确认后读取已保存作答。")
                }
                mutable.update { ui ->
                    if (ui.attempt?.str("mockId") == key.attempt && ui.attempt.num("answersSeq") <= reply.num("answersSeq"))
                        ui.copy(attempt = reply, active = reply)
                    else ui
                }
            }) return
            synchronized(queueLock) {
                if (pending[key] == draft) pending.remove(key)
                mutable.update { it.copy(saving = pending.isNotEmpty()) }
            }
        }
    }

    suspend fun reloadSaved() {
        if (!mutable.value.conflict) return
        synchronized(queueLock) { writer }?.join()
        action {
            val book = checkNotNull(mutable.value.book)
            val id = checkNotNull(mutable.value.attempt).str("mockId")
            // Read/validate the authoritative saved attempt before discarding this explicitly rejected draft.
            refresh(book, id)
            synchronized(queueLock) {
                pending.remove(Key(book.id, id))
                mutable.update { it.copy(saving = pending.isNotEmpty(), conflict = false) }
            }
        }
    }

    suspend fun flush(): Boolean {
        if (mutable.value.conflict) return false
        val job = synchronized(queueLock) {
            if (pending.isNotEmpty()) startWriter()
            writer
        }
        job?.join()
        return synchronized(queueLock) { pending.isEmpty() }
    }
}

@Composable
internal fun rememberLocalExamController(library: TextbookLibrary): LocalExamController {
    val scope = rememberCoroutineScope()
    return remember(library, scope) { LocalExamController(library, scope) }
}
