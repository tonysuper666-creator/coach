package com.coach.desktop.books

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path

internal enum class BookScreen { HOME, READER, LEARNING }
internal enum class BookPrompt { IMPORTED, START, AFTER_READING, QUESTIONS }
internal data class LibraryUi(
    val catalog: List<BookSummary> = emptyList(), val book: Textbook? = null,
    val screen: BookScreen = BookScreen.HOME, val prompt: BookPrompt? = null,
    val page: Int = 1, val source: BookLesson? = null,
    val answer: PracticeAnswer = PracticeAnswer(), val busy: Boolean = false, val error: String? = null,
)

internal class TextbookController(val library: TextbookLibrary) {
    private val mutable = MutableStateFlow(LibraryUi())
    val state = mutable.asStateFlow()
    private val actions = Mutex()

    private suspend fun action(showBusy: Boolean = true, block: suspend () -> Unit) = actions.withLock {
        mutable.update { it.copy(busy = showBusy, error = null) }
        try { withContext(Dispatchers.IO) { block() } }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            mutable.update { it.copy(error = if (failure is IllegalArgumentException) failure.message else "暂时无法读取或保存教材，请确认文件完整、未加密，然后重试。") }
        } finally { mutable.update { it.copy(busy = false) } }
    }

    suspend fun load() = action {
        val catalog = library.catalog()
        val book = catalog.firstOrNull()?.let { library.book(it.id) }
        mutable.value = LibraryUi(catalog = catalog, book = book, busy = true)
    }

    suspend fun importBook(path: Path) = action {
        val book = library.importBook(path)
        mutable.value = LibraryUi(library.catalog(), book, prompt = BookPrompt.IMPORTED, page = book.readingPage, busy = true)
    }

    suspend fun select(id: String) = action {
        val book = library.book(id)
        library.save(book)
        mutable.value = LibraryUi(library.catalog(), book, page = book.readingPage, busy = true)
    }

    private fun persist(book: Textbook) {
        library.save(book)
        mutable.update { it.copy(book = book, catalog = library.catalog()) }
    }

    suspend fun read() = action {
        val book = mutable.value.book ?: return@action
        mutable.update { it.copy(screen = BookScreen.READER, page = book.readingPage, source = null,
            prompt = if (!book.promptSeen && !book.learning) BookPrompt.AFTER_READING else null) }
    }

    suspend fun learn() = action {
        val book = mutable.value.book ?: return@action
        if (!book.learning) mutable.update { it.copy(prompt = BookPrompt.START) }
        else openLearning(book)
    }

    private fun openLearning(book: Textbook) {
        val lesson = book.lessons.getOrNull(book.lessonIndex)
        val answer = lesson?.let { library.answer(book.id, it.id, book.questionMode) } ?: PracticeAnswer()
        mutable.update { it.copy(screen = BookScreen.LEARNING, prompt = null, source = null, answer = answer) }
    }

    suspend fun enableLearning() = action {
        val book = mutable.value.book ?: return@action
        require(book.lessons.isNotEmpty()) { "这本书暂时没有可提取的文字。仍可翻看 PDF 原页；开启 learning 需要带文字层的教材。" }
        val enabled = book.copy(learning = true, promptSeen = true)
        persist(enabled); openLearning(enabled)
    }

    suspend fun dismissPrompt() = action {
        val book = mutable.value.book
        if (mutable.value.prompt == BookPrompt.AFTER_READING && book != null) persist(book.copy(promptSeen = true))
        mutable.update { it.copy(prompt = null) }
    }

    suspend fun questionSettings() = action { mutable.update { it.copy(prompt = BookPrompt.QUESTIONS) } }
    suspend fun mode(mode: QuestionMode) = action {
        val book = mutable.value.book ?: return@action
        val changed = book.copy(questionMode = mode)
        persist(changed)
        val answer = changed.lessons.getOrNull(changed.lessonIndex)?.let { library.answer(changed.id, it.id, mode) } ?: PracticeAnswer()
        mutable.update { it.copy(answer = answer) }
    }

    suspend fun page(number: Int) = action {
        val book = mutable.value.book ?: return@action
        require(number in 1..book.pages.size) { "页码超出了这本教材的范围。" }
        if (mutable.value.source == null) persist(book.copy(readingPage = number))
        mutable.update { it.copy(page = number) }
    }

    suspend fun source() = action {
        val book = mutable.value.book ?: return@action
        val lesson = book.lessons.getOrNull(book.lessonIndex) ?: return@action
        mutable.update { it.copy(screen = BookScreen.READER, source = lesson, page = lesson.firstPage, prompt = null) }
    }

    suspend fun back() = action {
        if (mutable.value.source != null) mutable.update { it.copy(screen = BookScreen.LEARNING, source = null) }
        else mutable.update { it.copy(screen = BookScreen.HOME, prompt = null) }
    }

    suspend fun lesson(index: Int) = action {
        val book = mutable.value.book ?: return@action
        require(index in 0..book.lessons.size) { "没有这一节学习内容。" }
        val next = book.copy(lessonIndex = index)
        persist(next); openLearning(next)
    }

    suspend fun answer(answer: PracticeAnswer) = action(showBusy = false) {
        val book = mutable.value.book ?: return@action
        val lesson = book.lessons.getOrNull(book.lessonIndex) ?: return@action
        writeAnswer(book.id, lesson.id, book.questionMode, answer)
    }

    /**
     * Stores an answer under the book, lesson and mode it was typed in, not the one that happens to be open.
     *
     * The identity check and the write share this action's lock: a lesson switch that was already waiting on
     * SQLite updates the open lesson first, so checking before the lock would let a keystroke typed in the
     * still-visible lesson land in the one the switch finally opens.
     */
    suspend fun saveDraft(book: String, lesson: String, mode: QuestionMode, answer: PracticeAnswer) = action(showBusy = false) {
        writeAnswer(book, lesson, mode, answer)
    }

    private fun writeAnswer(book: String, lesson: String, mode: QuestionMode, answer: PracticeAnswer) {
        library.saveAnswer(book, lesson, mode, answer)
        val open = mutable.value.book
        if (open?.id == book && open.lessons.getOrNull(open.lessonIndex)?.id == lesson && open.questionMode == mode) {
            mutable.update { it.copy(answer = answer) }
        }
    }
}
