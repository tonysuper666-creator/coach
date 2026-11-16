package com.coach.desktop.books

import com.coach.domain.LadderSession
import com.coach.domain.QuestionLadder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path

internal enum class BookScreen { HOME, READER, LEARNING, REVIEWS, REVIEWING }
internal enum class BookPrompt { IMPORTED, START, AFTER_READING, QUESTIONS }
internal data class LibraryUi(
    val catalog: List<BookSummary> = emptyList(), val book: Textbook? = null,
    val screen: BookScreen = BookScreen.HOME, val prompt: BookPrompt? = null,
    val page: Int = 1, val source: BookLesson? = null,
    val answer: PracticeAnswer = PracticeAnswer(), val busy: Boolean = false, val error: String? = null,
    val reviews: List<LocalBookReview> = emptyList(), val review: LocalBookReview? = null,
    val learningPath: LearningPath = LearningPath.READ, val questionsOpen: Boolean = false,
    /** Whether this book already has a chosen walk in the library, as opposed to the READ preview. */
    val learningPathSaved: Boolean = false,
    /** 这本书「做题学」的累计会话：六题一次小结，继续后接着累计。 */
    val ladder: LadderSession = LadderSession(),
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
        val active = library.activeReview()
        val book = active?.let { library.book(it.book) } ?: catalog.firstOrNull()?.let { library.book(it.id) }
        mutable.value = LibraryUi(catalog = catalog, book = book, busy = true, reviews = library.reviews(),
            review = active, answer = active?.answer ?: PracticeAnswer(),
            learningPath = book?.let(::restoredPath) ?: LearningPath.READ,
            learningPathSaved = book?.let { library.learningPath(it.id) != null } ?: false,
            ladder = book?.let { library.ladder(it.id) } ?: LadderSession(),
            screen = if (active == null) BookScreen.HOME else BookScreen.REVIEWING)
    }

    suspend fun importBook(path: Path) = action {
        val book = library.importBook(path)
        library.closeReview()
        mutable.value = LibraryUi(library.catalog(), book, prompt = BookPrompt.IMPORTED, page = book.readingPage,
            busy = true, reviews = library.reviews(),
            learningPath = library.learningPath(book.id)?.let { LearningPath.restore(it, book.learning) } ?: LearningPath.QUESTION_FIRST,
            learningPathSaved = library.learningPath(book.id) != null,
            ladder = library.ladder(book.id))
    }

    suspend fun select(id: String) = action {
        val book = library.book(id)
        library.save(book)
        library.closeReview()
        mutable.value = LibraryUi(library.catalog(), book, page = book.readingPage, busy = true, reviews = library.reviews(),
            learningPath = restoredPath(book), learningPathSaved = library.learningPath(book.id) != null,
            ladder = library.ladder(book.id))
    }

    private fun persist(book: Textbook) {
        library.save(book)
        mutable.update { it.copy(book = book, catalog = library.catalog()) }
    }

    private fun restoredPath(book: Textbook) = LearningPath.restore(library.learningPath(book.id), book.learning)

    private fun openReading(book: Textbook, promptToLearn: Boolean = false) {
        library.closeReview()
        mutable.update { it.copy(review = null, questionsOpen = false,
            screen = BookScreen.READER, page = book.readingPage, source = null,
            prompt = if (promptToLearn && !book.promptSeen && !book.learning) BookPrompt.AFTER_READING else null) }
    }

    suspend fun read() = action { mutable.value.book?.let { openReading(it, promptToLearn = true) } }

    /** An explicit entry changes the walk; the shared continue action restores the saved walk. */
    suspend fun learn(path: LearningPath? = null) = action {
        val book = mutable.value.book ?: return@action
        // A legacy book with no chosen walk keeps the original start-learning action,
        // even after ordinary reading. An explicit saved READ still continues reading.
        val target = path ?: if (library.learningPath(book.id) == null)
            LearningPath.QUESTION_FIRST else restoredPath(book)
        if (target == LearningPath.READ) {
            if (path != null) library.saveLearningPath(book.id, target.name)
            mutable.update { it.copy(learningPath = target, learningPathSaved = it.learningPathSaved || path != null) }
            openReading(book); return@action
        }
        library.closeReview()
        mutable.update { it.copy(review = null, learningPath = target, learningPathSaved = it.learningPathSaved || book.learning) }
        if (!book.learning) mutable.update { it.copy(prompt = BookPrompt.START) }
        else {
            library.saveLearningPath(book.id, target.name)
            openLearning(book, target)
        }
    }

    private fun openLearning(book: Textbook, path: LearningPath = mutable.value.learningPath) {
        val lesson = book.lessons.getOrNull(book.lessonIndex)
        val answer = lesson?.let { library.answer(book.id, it.id, book.questionMode) } ?: PracticeAnswer()
        mutable.update { it.copy(screen = BookScreen.LEARNING, prompt = null, source = null, answer = answer, review = null,
            learningPath = path, ladder = library.ladder(book.id),
            questionsOpen = path == LearningPath.QUESTION_FIRST || answer.text.isNotBlank() || answer.revealed) }
    }

    suspend fun chooseLearningPath(path: LearningPath) = action(showBusy = false) {
        mutable.update { it.copy(learningPath = path) }
    }

    suspend fun beginQuestions() = action(showBusy = false) {
        if (mutable.value.screen == BookScreen.LEARNING) mutable.update { it.copy(questionsOpen = true) }
    }

    suspend fun enableLearning() = action {
        val book = mutable.value.book ?: return@action
        val path = mutable.value.learningPath
        if (path == LearningPath.READ) {
            library.saveLearningPath(book.id, path.name)
            mutable.update { it.copy(learningPathSaved = true) }
            openReading(book); return@action
        }
        require(book.lessons.isNotEmpty()) { "这本书暂时没有可提取的文字。仍可翻看 PDF 原页；开启 learning 需要带文字层的教材。" }
        val enabled = book.copy(learning = true, promptSeen = true)
        library.saveLearningPath(book.id, path.name)
        mutable.update { it.copy(learningPathSaved = true) }
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
        require(book.page(number) != null) { "页码超出了这本教材的范围。" }
        if (mutable.value.source == null) persist(book.copy(readingPage = number))
        mutable.update { it.copy(page = number) }
    }

    suspend fun source() = action {
        val book = mutable.value.book ?: return@action
        val lesson = mutable.value.review?.let { review -> book.lessons.find { it.id == review.lesson } }
            ?: book.lessons.getOrNull(book.lessonIndex) ?: return@action
        mutable.update { it.copy(screen = BookScreen.READER, source = lesson, page = lesson.firstPage, prompt = null) }
    }

    /** A verified citation can arrive after the reader has changed books or lessons. */
    suspend fun citationSource(bookId: String, lessonId: String, page: Int) = action {
        val current = mutable.value
        val book = current.book ?: return@action
        val lesson = book.lessons.getOrNull(book.lessonIndex) ?: return@action
        if (current.screen != BookScreen.LEARNING || book.id != bookId || lesson.id != lessonId) return@action
        require(book.page(page) != null) { "页码超出了这本教材的范围。" }
        mutable.update { it.copy(screen = BookScreen.READER, source = lesson, page = page, prompt = null) }
    }

    suspend fun back() = action {
        if (mutable.value.source != null) mutable.update {
            it.copy(screen = if (it.review == null) BookScreen.LEARNING else BookScreen.REVIEWING, source = null)
        } else {
            val fromReview = mutable.value.review != null
            library.closeReview()
            mutable.update { it.copy(screen = if (fromReview) BookScreen.REVIEWS else BookScreen.HOME,
                review = null, prompt = null, reviews = library.reviews()) }
        }
    }

    suspend fun lesson(index: Int) = action {
        val book = mutable.value.book ?: return@action
        require(index in 0..book.lessons.size) { "没有这一节学习内容。" }
        val next = book.copy(lessonIndex = index)
        persist(next); openLearning(next)
    }

    suspend fun showReviews() = action {
        library.closeReview()
        mutable.update { it.copy(screen = BookScreen.REVIEWS, review = null, source = null,
            prompt = null, reviews = library.reviews()) }
    }

    /** The mounted host refreshes this projection across local midnight, including on 今天. */
    suspend fun refreshReviews() = action(showBusy = false) {
        val current = mutable.value.review
        val updated = current?.let { library.review(it.book, it.lesson, it.mode) }
        mutable.update { it.copy(reviews = library.reviews(), review = updated,
            answer = updated?.answer ?: it.answer) }
    }

    suspend fun openReview(entry: LocalBookReview) = action {
        val review = library.review(entry.book, entry.lesson, entry.mode)
        val book = library.book(review.book)
        library.openReview(review)
        mutable.update { it.copy(book = book, screen = BookScreen.REVIEWING, source = null,
            prompt = null, review = review, answer = review.answer, reviews = library.reviews()) }
    }

    suspend fun saveReviewDraft(book: String, lesson: String, mode: QuestionMode, answer: PracticeAnswer): Boolean {
        var saved = false
        action(showBusy = false) {
            val review = library.review(book, lesson, mode)
            library.saveReviewAnswer(review, answer)
            val updated = library.review(book, lesson, mode)
            val open = mutable.value.review
            mutable.update { it.copy(reviews = library.reviews()) }
            if (open?.book == book && open.lesson == lesson && open.mode == mode) {
                mutable.update { it.copy(review = updated, answer = answer) }
            }
            saved = true
        }
        return saved
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
    suspend fun saveDraft(book: String, lesson: String, mode: QuestionMode, answer: PracticeAnswer): Boolean {
        var saved = false
        action(showBusy = false) {
            writeAnswer(book, lesson, mode, answer)
            saved = true
        }
        return saved
    }

    private fun writeAnswer(book: String, lesson: String, mode: QuestionMode, answer: PracticeAnswer) {
        library.saveAnswer(book, lesson, mode, answer)
        mutable.update { it.copy(reviews = library.reviews()) }
        val open = mutable.value.book
        if (mutable.value.review == null && open?.id == book && open.lessons.getOrNull(open.lessonIndex)?.id == lesson && open.questionMode == mode) {
            mutable.update { it.copy(answer = answer, ladder = library.ladder(book)) }
        }
    }

    /** 「接着做题」只推进小结游标：答过的题、选过的选项和走到哪一节都不动。 */
    suspend fun recapContinue() = action(showBusy = false) {
        val book = mutable.value.book ?: return@action
        val next = QuestionLadder.closeRecap(mutable.value.ladder)
        library.saveLadder(book.id, next)
        mutable.update { it.copy(ladder = next) }
    }
}
