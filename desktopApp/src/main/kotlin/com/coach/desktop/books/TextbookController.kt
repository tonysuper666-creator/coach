package com.coach.desktop.books

import com.coach.client.SyncEngine
import com.coach.domain.Option
import com.coach.domain.questionFingerprint
import com.coach.domain.str
import com.coach.domain.LedgerJson
import com.coach.protocol.CommandBody
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.joinAll
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
    val practiceSync: String = "同步未备好：尚未连接配对课程。",
    val practiceUnavailable: Boolean = false,
    val sourceRegistration: String? = null,
)

/** Supplied only by an already paired learning session; never derive course or device from a book. */
internal class BookPracticeUnavailableException : Exception()

internal class BookPracticeSession(val sync: SyncEngine, val courseId: String,
    val synchronize: suspend () -> Unit = {}, val now: () -> Long = System::currentTimeMillis)

internal class TextbookController(val library: TextbookLibrary, private val practiceSession: BookPracticeSession? = null,
    private val connectPractice: (suspend (Textbook, BookLesson, Practice) -> BookPracticeSession?)? = null,
    private val sourceRegistration: BookSourceRegistrationPort = UnconnectedBookSourceRegistration,
) {
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
            screen = if (active == null) BookScreen.HOME else BookScreen.REVIEWING)
    }

    suspend fun importBook(path: Path) = action {
        val book = library.importBook(path)
        library.closeReview()
        mutable.value = LibraryUi(library.catalog(), book, prompt = BookPrompt.IMPORTED, page = book.readingPage,
            busy = true, reviews = library.reviews(),
            learningPath = library.learningPath(book.id)?.let { LearningPath.restore(it, book.learning) } ?: LearningPath.QUESTION_FIRST,
            learningPathSaved = library.learningPath(book.id) != null)
    }

    suspend fun select(id: String) = action {
        val book = library.book(id)
        library.save(book)
        library.closeReview()
        mutable.value = LibraryUi(library.catalog(), book, page = book.readingPage, busy = true, reviews = library.reviews(),
            learningPath = restoredPath(book), learningPathSaved = library.learningPath(book.id) != null)
    }

    suspend fun removeBook(id: String, consent: ConsentingBookLearningSupport?) = action {
        if (consent != null) consent.forgetBook(id)
        else OwnedBookConsentStore(library).revoke(id).joinAll()
        library.removeBook(id)
        library.closeReview()
        val catalog = library.catalog()
        val next = catalog.firstOrNull()?.let { library.book(it.id) }
        mutable.value = LibraryUi(catalog = catalog, book = next, busy = true,
            reviews = library.reviews(), learningPath = next?.let(::restoredPath) ?: LearningPath.READ,
            learningPathSaved = next?.let { library.learningPath(it.id) != null } ?: false)
    }

    private fun persist(book: Textbook) {
        library.save(book)
        mutable.update { it.copy(book = book, catalog = library.catalog()) }
    }

    /** Explicit request from the current book's settings. No answer or approval is written here. */
    suspend fun registerPracticeSource(expectedBook: String, course: String, confirmed: Boolean) = action {
        mutable.update { it.copy(sourceRegistration = null) }
        val book = mutable.value.book
        if (!confirmed || book == null || book.id != expectedBook || course.isBlank() ||
            course.trim().length > 128 || course.any(Char::isISOControl)) {
            mutable.update { it.copy(sourceRegistration = "尚未登记：请确认当前教材、课程和发送范围。") }
            return@action
        }
        val lesson = book.lessons.getOrNull(book.lessonIndex)
        val practice = lesson?.let { StudyCoachCourse.practice(book, it, QuestionMode.CHOICE) }
        if (book.questionMode != QuestionMode.CHOICE || lesson == null || practice == null) {
            mutable.update { it.copy(sourceRegistration = "尚未登记：当前课时没有可核对的选择题。") }
            return@action
        }
        val ready = try {
            val session = sourceRegistration.prepare(BookSourceRegistrationRequest(course.trim(), book, lesson, practice))
            val binding = session?.let { BookPracticeOutbox(it.sync).bind(it.courseId, book, lesson, practice) }
            session?.courseId == course.trim() && binding is BookPracticeBinding.Ready && validPracticeFingerprint(binding.posed.payload)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { false }
        mutable.update { it.copy(sourceRegistration = if (ready)
            "已核对课程中确认过的原题，可以返回学习继续作答。"
        else "尚未登记：配对身份或课程原题关联未确认；本地阅读和作答仍保留。") }
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

    private suspend fun openLearning(book: Textbook, path: LearningPath = mutable.value.learningPath) {
        val lesson = book.lessons.getOrNull(book.lessonIndex)
        val answer = lesson?.let { library.answer(book.id, it.id, book.questionMode) } ?: PracticeAnswer()
        mutable.update { it.copy(screen = BookScreen.LEARNING, prompt = null, source = null, answer = answer, review = null,
            learningPath = path, questionsOpen = path == LearningPath.QUESTION_FIRST || answer.text.isNotBlank() || answer.revealed) }
        refreshPracticeSync(book, lesson, answer, false)
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
        refreshPracticeSync(changed, changed.lessons.getOrNull(changed.lessonIndex), answer, false)
    }

    suspend fun page(number: Int) = action {
        val book = mutable.value.book ?: return@action
        // Page numbers are the book's own PDF pages, which need not start at 1 or run without gaps.
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

    suspend fun openReview(entry: LocalBookReview) = action {
        val review = library.review(entry.book, entry.lesson, entry.mode)
        val book = library.book(review.book)
        library.openReview(review)
        mutable.update { it.copy(book = book, screen = BookScreen.REVIEWING, source = null,
            prompt = null, review = review, answer = review.answer, reviews = library.reviews()) }
    }

    suspend fun startReviewVariant() = action {
        val current = mutable.value.review ?: return@action
        val review = library.startReviewVariant(current)
        mutable.update { it.copy(review = review, answer = review.answer, reviews = library.reviews()) }
    }

    suspend fun importReviewSource(path: Path) = action {
        val current = mutable.value.review ?: return@action
        library.importReviewVariantSource(current, path)
        val review = library.review(current.book, current.lesson, current.mode)
        mutable.update { it.copy(review = review, answer = review.answer, reviews = library.reviews()) }
    }

    suspend fun saveReviewDraft(book: String, lesson: String, mode: QuestionMode, answer: PracticeAnswer, variantAttemptId: String? = null): Boolean {
        var saved = false
        action(showBusy = false) {
            val review = library.review(book, lesson, mode)
            require(review.variant?.allocation?.attemptId == variantAttemptId) { "这道复习题已变更，请重新打开。" }
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

    private suspend fun writeAnswer(book: String, lesson: String, mode: QuestionMode, answer: PracticeAnswer) {
        library.saveAnswer(book, lesson, mode, answer)
        mutable.update { it.copy(reviews = library.reviews()) }
        val open = mutable.value.book
        if (mutable.value.review == null && open?.id == book && open.lessons.getOrNull(open.lessonIndex)?.id == lesson && open.questionMode == mode) {
            mutable.update { it.copy(answer = answer) }
            refreshPracticeSync(open, open.lessons.getOrNull(open.lessonIndex), answer, true)
        }
    }

    suspend fun retryPracticeSync() = action(showBusy = false) {
        val book = mutable.value.book ?: return@action
        refreshPracticeSync(book, book.lessons.getOrNull(book.lessonIndex), mutable.value.answer, true)
    }

    private suspend fun refreshPracticeSync(book: Textbook, lesson: BookLesson?, answer: PracticeAnswer, submit: Boolean) {
        if ((practiceSession == null && connectPractice == null) || lesson == null || book.questionMode != QuestionMode.CHOICE) {
            mutable.update { if (it.practiceUnavailable) it else it.copy(practiceSync = "同步未备好：尚未连接配对课程。") }
            return
        }
        val practice = StudyCoachCourse.practice(book, lesson, QuestionMode.CHOICE)
        if (practice == null) {
            mutable.update { if (it.practiceUnavailable) it else it.copy(practiceSync = "同步未备好：没有对应练习。") }
            return
        }
        try {
            val session = if (connectPractice == null) practiceSession else connectPractice.invoke(book, lesson, practice)
            if (session == null) {
                mutable.update { if (it.practiceUnavailable) it else it.copy(practiceSync = "同步未备好：缺少配对身份或已核准的书课关联。") }
                return
            }
            val outbox = BookPracticeOutbox(session.sync)
            val binding = outbox.bind(session.courseId, book, lesson, practice)
            if (binding !is BookPracticeBinding.Ready || !validPracticeFingerprint(binding.posed.payload)) {
                mutable.update { if (it.practiceUnavailable) it else it.copy(practiceSync = "同步未备好：原题身份尚未确认。") }
                return
            }
            // Entering learning only reads an existing submission; it must not enqueue an answer.
            val queued = !submit && session.sync.retainedCommands().any { command ->
                val body = runCatching { LedgerJson.decodeFromString<CommandBody>(command.payloadBytes) }.getOrNull()
                body?.type == "submit_answer" && body.courseId == binding.courseId &&
                    body.payload.str("attemptId") == binding.attemptId
            }
            val message = if ((submit || queued) && answer.revealed && answer.text.isNotBlank()) {
                // The idempotent submit also checks the frozen answer/guessed lock before using its receipt.
                val commandId = outbox.submit(binding, answer, session.now())
                session.synchronize()
                val receipt = session.sync.receipt(commandId)
                when (receipt?.status) {
                    "OK" -> "原作答已同步。"
                    "REJECTED" -> "同步被拒绝：${receipt.error ?: "未知原因"}"
                    else -> "原作答已保存，等待同步。"
                }
            } else "原题已备好，可同步本次作答。"
            mutable.update { it.copy(practiceSync = message, practiceUnavailable = false) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: BookPracticeUnavailableException) {
            mutable.update { it.copy(practiceUnavailable = true, practiceSync = "这本原书的题目已撤回或暂不可用，请核对课程并重新登记原书。") }
        }
        catch (failure: Exception) {
            val message = if (failure.message == "Answer is already locked" || failure.message == "Attempt already answered")
                "原作答已锁定，当前本机修改未同步。" else "原作答已保存在本机，同步未完成；请重试。"
            mutable.update { if (it.practiceUnavailable) it else it.copy(practiceSync = message) }
        }
    }

    private fun validPracticeFingerprint(payload: JsonObject): Boolean = runCatching {
        val options = LedgerJson.decodeFromJsonElement<List<Option>>(payload.getValue("options"))
        payload.str("fingerprint").isNotBlank() && payload.str("fingerprint") == questionFingerprint(payload.str("stem"), options)
    }.getOrDefault(false)
}
