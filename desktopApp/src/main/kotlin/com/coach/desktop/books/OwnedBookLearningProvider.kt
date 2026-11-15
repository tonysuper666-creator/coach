package com.coach.desktop.books

import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Coordinates are UTF-16 offsets in the locally extracted physical page, end exclusive. */
internal data class OwnedBookCitation(val book: String, val page: Int, val start: Int, val end: Int)
internal data class OwnedBookExcerpt(val citation: OwnedBookCitation, val text: String)
internal enum class OwnedBookAction { EXPLAIN, ASK }
internal data class OwnedBookPrompt(val action: OwnedBookAction, val question: String?, val excerpts: List<OwnedBookExcerpt>)
internal data class OwnedBookAnswer(val text: String, val citations: List<OwnedBookCitation>)

/**
 * This is an injection boundary, not permission to send a private book anywhere.
 * No executor is installed by default. A production executor must enforce the
 * book's existing privacy policy before transmitting excerpts.
 */
internal fun interface OwnedBookExecutor {
    suspend fun answer(prompt: OwnedBookPrompt): OwnedBookAnswer
}

internal class OwnedBookLearningProvider(
    private val library: TextbookLibrary,
    private val executor: OwnedBookExecutor,
) : BookLearningSupport {
    override suspend fun explain(request: BookExplanationRequest): BookLearningReply = safely {
        val checked = { explanation(request) }
        answer(checked)
    }

    override suspend fun ask(request: BookPassageQuestion): BookLearningReply = safely {
        val checked = { question(request) }
        answer(checked)
    }

    private suspend fun answer(checked: () -> OwnedBookPrompt): BookLearningReply {
        val prompt = withContext(Dispatchers.IO) { checked() }
        currentCoroutineContext().ensureActive()
        val result = executor.answer(prompt)
        currentCoroutineContext().ensureActive()
        // Detect changes to the file or extracted library pages while the executor ran.
        require(withContext(Dispatchers.IO) { checked() } == prompt)
        val text = result.text.trim()
        require(text.isNotEmpty() && !text.equals("unavailable", ignoreCase = true))
        // Reject whitespace and invisible format-character changes to copied text.
        // This remains an exact-copy heuristic, not a test of explanation quality.
        val normalized = normalizeCopy(text)
        require(normalized.isNotEmpty())
        require(prompt.excerpts.none { normalized == normalizeCopy(it.text) })
        require(normalized != normalizeCopy(prompt.excerpts.joinToString("") { it.text }))
        val allowed = prompt.excerpts.map { it.citation }.toSet()
        require(result.citations.isNotEmpty() && result.citations.all { it in allowed })
        val references = result.citations.distinct().joinToString("；") {
            "书 SHA-256 ${it.book}，第 ${it.page} 页，字符 ${it.start}–${it.end}"
        }
        return BookLearningReply.Text("$text\n\n出处：$references")
    }

    private fun normalizeCopy(text: String): String = buildString {
        text.codePoints().forEach { codePoint ->
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint) &&
                Character.getType(codePoint) != Character.FORMAT.toInt()) appendCodePoint(codePoint)
        }
    }

    private fun explanation(request: BookExplanationRequest): OwnedBookPrompt {
        val book = verifiedBook(request.book)
        val lesson = book.lessons.single { it.id == request.lesson }
        require(request.blocks == lesson.blocks)
        val excerpts = request.blocks.filterNot { it.heading }.map { block ->
            excerpt(book, BookPassage.from(book, block))
        }
        require(excerpts.isNotEmpty())
        return OwnedBookPrompt(OwnedBookAction.EXPLAIN, null, excerpts)
    }

    private fun question(request: BookPassageQuestion): OwnedBookPrompt {
        val question = request.question.trim()
        require(question.length in 1..500)
        val book = verifiedBook(request.passage.book)
        return OwnedBookPrompt(OwnedBookAction.ASK, question, listOf(excerpt(book, request.passage)))
    }

    private fun excerpt(book: Textbook, passage: BookPassage): OwnedBookExcerpt {
        require(passage.book == book.id && passage.text.isNotBlank())
        val page = book.pages.single { it.number == passage.page }
        require(passage.start >= 0 && passage.end > passage.start && passage.end <= page.text.length)
        require(page.text.substring(passage.start, passage.end) == passage.text)
        // The selection must belong wholly to one currently generated body block.
        val block = StudyCoachCourse.blocks(book.id, book.pages).single {
            !it.heading && it.page == passage.page && passage.start >= it.start && passage.end <= it.end
        }
        require(block.id == "${book.id}:p${block.page}:${block.start}")
        require(page.text.substring(block.start, block.end) == block.text)
        return OwnedBookExcerpt(OwnedBookCitation(book.id, passage.page, passage.start, passage.end), passage.text)
    }

    private fun verifiedBook(id: String): Textbook {
        require(id.matches(Regex("[a-f0-9]{64}")))
        val book = library.book(id)
        require(book.id == id && book.format in setOf("pdf", "epub", "md", "txt"))
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(library.directory.resolve("$id.${book.format}")).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == id)
        return book
    }

    private suspend fun safely(block: suspend () -> BookLearningReply): BookLearningReply = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        BookLearningReply.Unavailable("讲解暂时不可用，请检查书籍和来源后重试。")
    }
}
